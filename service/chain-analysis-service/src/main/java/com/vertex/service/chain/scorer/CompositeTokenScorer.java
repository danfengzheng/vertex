package com.vertex.service.chain.scorer;

import com.vertex.service.chain.source.NewTokenRawData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 复合代币评分器
 * <p>
 * 聚合所有 {@link TokenScorer} 实现，按维度汇总评分，生成 {@link TokenScoreResult}。
 * Spring 自动将所有 {@link TokenScorer} Bean 注入到 {@code scorers} 列表中。
 * 参考 {@code CompositeTradeNotifier} 模式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeTokenScorer {

    private final List<TokenScorer> scorers;

    /**
     * 对给定代币数据进行全维度评分
     *
     * @param data 代币原始数据
     * @return 各维度 + 总分结果
     */
    public TokenScoreResult score(NewTokenRawData data) {
        Map<ScoreDimension, Integer> scores = new EnumMap<>(ScoreDimension.class);

        for (TokenScorer scorer : scorers) {
            ScoreDimension dim = scorer.dimension();
            try {
                int dimScore = scorer.score(data);
                scores.merge(dim, dimScore, Integer::sum);
                log.debug("[Scorer] {} {} score={}", data.getChain(), dim.name(), dimScore);
            } catch (Exception e) {
                log.warn("[Scorer] {} failed for {} {}: {}",
                        scorer.getClass().getSimpleName(), data.getChain(), data.getContractAddress(), e.getMessage());
                scores.putIfAbsent(dim, 0);
            }
        }

        int onChain = scores.getOrDefault(ScoreDimension.ON_CHAIN, 0);
        int market = scores.getOrDefault(ScoreDimension.MARKET, 0);
        int tokenomics = scores.getOrDefault(ScoreDimension.TOKENOMICS, 0);
        int novelty = scores.getOrDefault(ScoreDimension.NOVELTY, 0);

        TokenScoreResult result = TokenScoreResult.of(onChain, market, tokenomics, novelty);
        log.info("[Scorer] {} {} total={} grade={} (onChain={} market={} tokenomics={} novelty={})",
                data.getChain(), data.getContractAddress(),
                result.getTotalScore(), result.getGrade(),
                onChain, market, tokenomics, novelty);
        return result;
    }

    /**
     * 已注册的评分器数量
     */
    public int scorerCount() {
        return scorers.size();
    }
}
