package com.vertex.service.chain.source;

import java.util.List;

/**
 * 链上数据源接口
 * <p>
 * 每条链提供一个实现，通过 {@code @ConditionalOnProperty} 按配置开关激活。
 * {@link com.vertex.service.chain.scanner.ChainTokenScanService} 通过
 * {@code List<ChainDataSource>} 自动收集所有已激活的数据源。
 */
public interface ChainDataSource {

    /**
     * 链标识符，用于日志和数据库记录，e.g. "BNB", "SOL"
     */
    String chainCode();

    /**
     * 数据源唯一标识，用于区分同一链上的多个数据源。
     * 默认返回 chainCode()，多数据源时需覆盖以避免重名。
     * e.g. "bnb_primary", "bnb_alpha", "bnb_trending"
     */
    default String sourceId() {
        return chainCode();
    }

    /**
     * 数据源是否可用（配置完整、API 连通）
     */
    boolean isAvailable();

    /**
     * 拉取代币原始数据列表
     *
     * @param scanWindowMinutes 向前扫描的时间窗口（分钟），筛选模式下可忽略此参数
     * @return 代币列表，空列表表示无数据或发生错误
     */
    List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes);

    /**
     * 是否为潜力筛选模式（screening mode）。
     * <p>
     * 新币发现模式（false）：已存在的代币直接跳过，幂等插入。<br>
     * 潜力筛选模式（true）：已存在的代币也会更新指标快照和评分，
     * 用于持续跟踪 Binance Alpha、BSC 趋势等已上市但持续挖掘潜力的代币。
     */
    default boolean isScreeningMode() {
        return false;
    }
}
