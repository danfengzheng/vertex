package com.vertex.service.chain.scorer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 评分维度枚举
 * <p>
 * 四个维度的权重之和为 100 分，对应不同的分析角度。
 */
@Getter
@RequiredArgsConstructor
public enum ScoreDimension {

    /** 链上指标（持有者数、交易活跃度、合约验证、流动性锁定） */
    ON_CHAIN("链上指标", 30),

    /** 市场指标（流动性、成交量、市值、价格动能、买盘压力） */
    MARKET("市场指标", 40),

    /** 代币经济（持仓集中度、部署者持仓、LP 占比） */
    TOKENOMICS("代币经济", 20),

    /** 新颖度（存在时长、Pump.fun 上市、LP 添加次数） */
    NOVELTY("新颖度", 10);

    /** 中文名称 */
    private final String label;

    /** 满分权重 */
    private final int maxScore;
}
