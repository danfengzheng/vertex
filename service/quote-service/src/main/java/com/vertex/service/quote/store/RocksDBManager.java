package com.vertex.service.quote.store;

import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RocksDB 生命周期管理
 */
@Slf4j
public class RocksDBManager implements InitializingBean, DisposableBean {

    private final String dataDir;
    private RocksDB db;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBManager(String dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        File dir = new File(dataDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Failed to create RocksDB data directory: " + dataDir);
        }

        Options options = new Options();
        options.setCreateIfMissing(true);
        options.setCompressionType(CompressionType.LZ4_COMPRESSION);
        options.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);

        // bloom filter 加速前缀查询
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setFilterPolicy(new BloomFilter(10, false));
        tableConfig.setBlockSize(16 * 1024);
        options.setTableFormatConfig(tableConfig);

        // 写缓冲
        options.setWriteBufferSize(64 * 1024 * 1024);
        options.setMaxWriteBufferNumber(3);

        db = RocksDB.open(options, dataDir);
        log.info("RocksDB opened at: {}", dataDir);
    }

    @Override
    public void destroy() {
        if (db != null) {
            db.close();
            log.info("RocksDB closed");
        }
    }

    /**
     * 写入数据
     */
    public void put(String key, byte[] value) throws RocksDBException {
        db.put(key.getBytes(StandardCharsets.UTF_8), value);
    }

    /**
     * 读取数据
     */
    public byte[] get(String key) throws RocksDBException {
        return db.get(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 删除数据
     */
    public void delete(String key) throws RocksDBException {
        db.delete(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 批量写入
     */
    public void putBatch(Map<String, byte[]> entries) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                batch.put(entry.getKey().getBytes(StandardCharsets.UTF_8), entry.getValue());
            }
            db.write(writeOptions, batch);
        }
    }

    /**
     * 前缀范围查询
     *
     * @param prefix    前缀
     * @param startKey  起始 key（含），为 null 则从 prefix 开始
     * @param endKey    结束 key（含），为 null 则查到 prefix 范围结束
     * @param limit     最大条数
     * @return key-value 列表
     */
    public List<Map.Entry<String, byte[]>> rangeQuery(String prefix, String startKey, String endKey, int limit) {
        List<Map.Entry<String, byte[]>> results = new ArrayList<>();
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

        try (ReadOptions readOptions = new ReadOptions()) {
            readOptions.setPrefixSameAsStart(true);
            readOptions.setTotalOrderSeek(false);

            try (RocksIterator iterator = db.newIterator(readOptions)) {
                byte[] seekKey = startKey != null
                        ? startKey.getBytes(StandardCharsets.UTF_8)
                        : prefixBytes;
                iterator.seek(seekKey);

                byte[] endKeyBytes = endKey != null ? endKey.getBytes(StandardCharsets.UTF_8) : null;

                while (iterator.isValid() && results.size() < limit) {
                    byte[] keyBytes = iterator.key();
                    String key = new String(keyBytes, StandardCharsets.UTF_8);

                    // 检查是否还在前缀范围内
                    if (!key.startsWith(prefix)) {
                        break;
                    }

                    // 检查是否超过 endKey
                    if (endKeyBytes != null && compareBytes(keyBytes, endKeyBytes) > 0) {
                        break;
                    }

                    results.add(Map.entry(key, iterator.value()));
                    iterator.next();
                }
            }
        }
        return results;
    }

    /**
     * 反向查询（获取最新数据）
     */
    public Map.Entry<String, byte[]> getLatest(String prefix) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

        try (ReadOptions readOptions = new ReadOptions()) {
            readOptions.setPrefixSameAsStart(true);

            try (RocksIterator iterator = db.newIterator(readOptions)) {
                // seek 到 prefix 后的下一个范围，然后 prev
                byte[] upperBound = incrementPrefix(prefixBytes);
                iterator.seek(upperBound);

                if (iterator.isValid()) {
                    iterator.prev();
                } else {
                    iterator.seekToLast();
                }

                if (iterator.isValid()) {
                    String key = new String(iterator.key(), StandardCharsets.UTF_8);
                    if (key.startsWith(prefix)) {
                        return Map.entry(key, iterator.value());
                    }
                }
            }
        }
        return null;
    }

    private int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (cmp != 0) return cmp;
        }
        return a.length - b.length;
    }

    private byte[] incrementPrefix(byte[] prefix) {
        byte[] result = prefix.clone();
        for (int i = result.length - 1; i >= 0; i--) {
            if ((result[i] & 0xFF) < 0xFF) {
                result[i]++;
                return result;
            }
            result[i] = 0;
        }
        // 全是 0xFF，追加一个字节
        byte[] extended = new byte[result.length + 1];
        System.arraycopy(result, 0, extended, 0, result.length);
        extended[result.length] = 0;
        return extended;
    }
}
