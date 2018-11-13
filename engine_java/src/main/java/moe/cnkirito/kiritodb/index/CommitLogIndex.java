package moe.cnkirito.kiritodb.index;

import com.carrotsearch.hppc.LongIntHashMap;
import moe.cnkirito.kiritodb.common.Constant;
import moe.cnkirito.kiritodb.common.Util;
import moe.cnkirito.kiritodb.data.CommitLog;
import moe.cnkirito.kiritodb.data.CommitLogAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author kirito.moe@foxmail.com
 * Date 2018-10-28
 */
public class CommitLogIndex implements CommitLogAware {

    private final static Logger logger = LoggerFactory.getLogger(CommitLogIndex.class);
    // key 和文件逻辑偏移的映射
    private LongIntHashMap key2OffsetMap;
    private FileChannel fileChannel;
    private MappedByteBuffer mappedByteBuffer;
    // 当前索引写入的区域
    private AtomicLong wrotePosition;
    private CommitLog commitLog;
    private volatile boolean loadFlag = false;

    public void init(String path, int no) throws IOException {
        // 先创建文件夹
        File dirFile = new File(path);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
            loadFlag = true;
        }
        // 创建多个索引文件
        File file = new File(path + Constant.IndexName + no + Constant.IndexSuffix);
        if (!file.exists()) {
            file.createNewFile();
            loadFlag = true;
        }
        // 文件position
        this.fileChannel = new RandomAccessFile(file, "rw").getChannel();
        this.wrotePosition = new AtomicLong(0);
        this.mappedByteBuffer = this.fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, Constant.IndexLength * 252000);
        this.key2OffsetMap = new LongIntHashMap(252000, 0.99);
    }

    public void load() {
        // 说明索引文件中已经有内容，则读取索引文件内容到内存中
        MappedByteBuffer mappedByteBuffer = this.mappedByteBuffer;
        int indexSize = commitLog.getFileLength();
        wrotePosition.set(indexSize * Constant.IndexLength);
        for (int curIndex = 0; curIndex < indexSize; curIndex++) {
            mappedByteBuffer.position(curIndex * Constant.IndexLength);
            long key = mappedByteBuffer.getLong();
            int offset = mappedByteBuffer.getInt();
            // 插入内存
            insertIndexCache(key, offset);
        }
        this.loadFlag = true;
    }

    public void destroy() throws IOException {
        fileChannel.close();
        Util.clean(this.mappedByteBuffer);
    }

    public Long read(byte[] key) {
        int offsetInt = this.key2OffsetMap.getOrDefault(Util.bytes2Long(key), -1);
        if (offsetInt == -1) {
            return null;
        }
        return ((long) offsetInt) * Constant.ValueLength;
    }

    public void write(byte[] key, int offsetInt) {
        try {
            writeIndexFile(key, offsetInt);
        } catch (Exception e) {
            logger.error("写入文件错误, error", e);
        }
    }

    private void insertIndexCache(long key, Integer value) {
        // TODO need synchronized?
        synchronized (this) {
            this.key2OffsetMap.put(key, value);
        }
    }

    private void writeIndexFile(byte[] key, Integer value) {
        long position = this.wrotePosition.getAndAdd(Constant.IndexLength);
        ByteBuffer buffer = this.mappedByteBuffer.slice();
        buffer.position((int) position);
        buffer.put(key);
        buffer.putInt(value);
    }

    public boolean isLoadFlag() {
        return loadFlag;
    }

    @Override
    public CommitLog getCommitLog() {
        return this.commitLog;
    }

    @Override
    public void setCommitLog(CommitLog commitLog) {
        this.commitLog = commitLog;
    }
}
