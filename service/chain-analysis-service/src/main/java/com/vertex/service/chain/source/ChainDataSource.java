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
     * 数据源是否可用（配置完整、API 连通）
     */
    boolean isAvailable();

    /**
     * 拉取最近上线的新代币原始数据列表
     *
     * @param scanWindowMinutes 向前扫描的时间窗口（分钟）
     * @return 新代币列表，空列表表示无新代币或发生错误
     */
    List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes);
}
