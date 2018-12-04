package moe.cnkirito.kiritodb;

import com.alibabacloud.polar_race.engine.common.AbstractVisitor;
import com.alibabacloud.polar_race.engine.common.exceptions.EngineException;
import com.alibabacloud.polar_race.engine.common.exceptions.RetCodeEnum;
import moe.cnkirito.kiritodb.common.Constant;
import moe.cnkirito.kiritodb.common.Util;
import moe.cnkirito.kiritodb.data.CommitLog;
import moe.cnkirito.kiritodb.index.CommitLogIndex;
import moe.cnkirito.kiritodb.partition.HighTenPartitioner;
import moe.cnkirito.kiritodb.partition.Partitionable;
import moe.cnkirito.kiritodb.range.FetchDataProducer;
import moe.cnkirito.kiritodb.range.RangeTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author kirito.moe@foxmail.com
 * Date 2018-10-28
 */
public class KiritoDB {

    private static final Logger logger = LoggerFactory.getLogger(KiritoDB.class);
    // partition num
    private final int partitionNum = Constant.partitionNum;
    // key -> partition
    private volatile Partitionable partitionable;
    // data
    public volatile CommitLog[] commitLogs;
    // index
    private volatile CommitLogIndex[] commitLogIndices;
    // true meas need to load, false no need
    private volatile boolean loadFlag = false;

    public KiritoDB() {
        partitionable = new HighTenPartitioner();
    }

    public void open(String path) throws EngineException {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        commitLogs = new CommitLog[partitionNum];
        commitLogIndices = new CommitLogIndex[partitionNum];
        try {
            for (int i = 0; i < partitionNum; i++) {
                commitLogs[i] = new CommitLog();
                commitLogs[i].init(path, i);
            }
            for (int i = 0; i < partitionNum; i++) {
                commitLogIndices[i] = new CommitLogIndex();
                commitLogIndices[i].init(path, i);
                commitLogIndices[i].setCommitLog(commitLogs[i]);
                this.loadFlag = commitLogIndices[i].isLoadFlag();
            }
            if (!loadFlag) {
                loadAllIndex();
            }
        } catch (IOException e) {
            throw new EngineException(RetCodeEnum.IO_ERROR, "open exception");
        }
    }

    private AtomicBoolean writeFirst = new AtomicBoolean(false);

    public void write(byte[] key, byte[] value) throws EngineException {
        if (writeFirst.compareAndSet(false, true)) {
            logger.info("[jvm info] write first, now {} ", Util.getFreeMemory());
        }
        int partition = partitionable.getPartition(key);
        CommitLog hitCommitLog = commitLogs[partition];
        CommitLogIndex hitIndex = commitLogIndices[partition];
        synchronized (hitCommitLog) {
            hitCommitLog.write(value);
            hitIndex.write(key);
        }
    }

    private AtomicBoolean readFirst = new AtomicBoolean(false);

    public byte[] read(byte[] key) throws EngineException {
        if (readFirst.compareAndSet(false, true)) {
            logger.info("[jvm info] read first now {} ", Util.getFreeMemory());
        }
        int partition = partitionable.getPartition(key);
        CommitLog hitCommitLog = commitLogs[partition];
        CommitLogIndex hitIndex = commitLogIndices[partition];
        Long offset = hitIndex.read(key);
        if (offset == null) {
            throw new EngineException(RetCodeEnum.NOT_FOUND, Util.bytes2Long(key) + " not found");
        }
        try {
            return hitCommitLog.read(offset);
        } catch (IOException e) {
            throw new EngineException(RetCodeEnum.IO_ERROR, "commit log read exception");
        }
    }

    // fetch thread flag
    private final AtomicBoolean rangFirst = new AtomicBoolean(false);
    private static ThreadLocal<byte[]> visitorCallbackValue = ThreadLocal.withInitial(() -> new byte[Constant.VALUE_LENGTH]);
    private final static int THREAD_NUM = 64;
    private LinkedBlockingQueue<RangeTask> rangeTaskLinkedBlockingQueue = new LinkedBlockingQueue<>();

    public void range(byte[] lower, byte[] upper, AbstractVisitor visitor) throws EngineException {
        // 第一次 range 的时候开启 fetch 线程
        if (rangFirst.compareAndSet(false, true)) {
            logger.info("[jvm info] range first now {} ", Util.getFreeMemory());
            initPreFetchThreads();
        }
        RangeTask rangeTask = new RangeTask(visitor, new CountDownLatch(1));
        rangeTaskLinkedBlockingQueue.offer(rangeTask);
        try {
            rangeTask.getCountDownLatch().await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private volatile FetchDataProducer fetchDataProducer;

    private volatile int csize;
    private volatile int[] coffsetInts;
    private volatile long[] ckeys;
    private volatile ByteBuffer cbuffer[];

    private void initPreFetchThreads() {
        new Thread(() -> {
            RangeTask[] rangeTasks = new RangeTask[THREAD_NUM];
            for (int i = 0; i < THREAD_NUM; i++) {
                try {
                    rangeTasks[i] = rangeTaskLinkedBlockingQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (fetchDataProducer == null) {
                fetchDataProducer = new FetchDataProducer(this);
            }
            fetchDataProducer.startFetch();
            long visitTotalTime = 0;
            long rangeStartTime = System.currentTimeMillis();
            Semaphore visitSemaphore = new Semaphore(0);
            Semaphore visitDownSemaphore = new Semaphore(0);

            for (int i = 0; i < THREAD_NUM; i++) {
                final int rangeIndex = i;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            try {
                                visitSemaphore.acquire(1);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            byte[] bytes = visitorCallbackValue.get();
                            ByteBuffer[] slice = new ByteBuffer[cbuffer.length];
                            for (int j = 0; j < cbuffer.length; j++) {
                                slice[j] = cbuffer[j].slice();
                            }
                            int capacity = cbuffer[0].capacity() / Constant.VALUE_LENGTH;
                            for (int j = 0; j < csize; j++) {
                                int slicePartition = coffsetInts[j] / capacity;
                                int partitionInnerOffset = coffsetInts[j] % capacity;
                                try{
                                    slice[slicePartition].position(partitionInnerOffset * Constant.VALUE_LENGTH);
                                    slice[slicePartition].get(bytes);
                                }catch (Exception e){
                                    e.printStackTrace();
                                }
                                rangeTasks[rangeIndex].getAbstractVisitor().visit(Util.long2bytes(ckeys[j]), bytes);
                            }
                            visitDownSemaphore.release(1);
                        }
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
            // scan all partition
            for (int i = 0; i < partitionNum; i++) {
                cbuffer = fetchDataProducer.getBuffer(i);
                CommitLogIndex commitLogIndex = this.commitLogIndices[i];
                csize = commitLogIndex.getMemoryIndex().getSize();
                coffsetInts = commitLogIndex.getMemoryIndex().getOffsetInts();
                ckeys = commitLogIndex.getMemoryIndex().getKeys();
                // scan one partition 4kb by 4kb according to index
                visitSemaphore.release(THREAD_NUM);
                try {
                    visitDownSemaphore.acquire(THREAD_NUM);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                fetchDataProducer.release(i);
            }
            rangFirst.set(false);
            logger.info("visit total cost {} ms", visitTotalTime);
            logger.info("range total cost {} ms", System.currentTimeMillis() - rangeStartTime);
            for (RangeTask rangeTask : rangeTasks) {
                rangeTask.getCountDownLatch().countDown();
            }
        }).start();
    }

    private void loadAllIndex() {
        ExecutorService executorService = Executors.newFixedThreadPool(64);
        CountDownLatch countDownLatch = new CountDownLatch(partitionNum);
        for (int i = 0; i < partitionNum; i++) {
            final int index = i;
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    commitLogIndices[index].load();
                    countDownLatch.countDown();
                }
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            logger.error("load index interrupted", e);
        }
        executorService.shutdown();
        this.loadFlag = true;
    }

    public void close() {
        if (commitLogs != null) {
            for (CommitLog commitLog : commitLogs) {
                try {
                    commitLog.destroy();
                } catch (IOException e) {
                    logger.error("data destroy error", e);
                }
            }
        }
        if (commitLogIndices != null) {
            for (CommitLogIndex commitLogIndex : commitLogIndices) {
                try {
                    commitLogIndex.destroy();
                } catch (IOException e) {
                    logger.error("data destroy error", e);
                }
            }
        }
        if (this.fetchDataProducer != null) {
            fetchDataProducer.destroy();
        }
        this.partitionable = null;
        this.commitLogs = null;
        this.commitLogIndices = null;
    }
}
