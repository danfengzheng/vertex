package com.vertex.service.chain.scorer;

import com.vertex.service.chain.source.NewTokenRawData;

/**
 * 代币评分器接口
 * <p>
 * 每个实现负责一个维度的评分，返回该维度的得分（不超过 {@link #dimension()} 的 maxScore）。
 * 所有实现通过 {@link CompositeTokenScorer} 汇总。
 */
public interface TokenScorer {

    /**
     * 当前评分器对应的维度
     */
    ScoreDimension dimension();

    /**
     * 对给定代币数据打分
     *
     * @param data 代币原始数据
     * @return 该维度得分（0 ~ dimension().getMaxScore()）
     */
    int score(NewTokenRawData data);
}
