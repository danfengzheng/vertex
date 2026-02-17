package com.vertex.service.strategy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 策略模块配置
 */
@Data
@ConfigurationProperties(prefix = "vertex.strategy")
public class StrategyProperties {

    /** 是否启用策略模块 */
    private boolean enabled = true;

    /** RocksDB 配置 */
    private RocksDB rocksdb = new RocksDB();

    /** 引擎配置 */
    private Engine engine = new Engine();

    @Data
    public static class RocksDB {
        /** 数据目录 */
        private String dataDir = "./data/rocksdb/strategy";
    }

    @Data
    public static class Engine {
        /** 指标计算所需的最大历史K线数量 */
        private int maxKlineHistory = 500;
        /** 是否仅处理已收盘K线 */
        private boolean onlyClosedKlines = true;
        /** 策略评估最小间隔（毫秒），仅在 onlyClosedKlines=false 时生效，0 表示禁用节流 */
        private long minEvalIntervalMs = 5000;
    }
}
