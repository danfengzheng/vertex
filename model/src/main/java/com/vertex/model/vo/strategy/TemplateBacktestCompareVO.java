package com.vertex.model.vo.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 模板 A/B 对比结果 —— 2+ 个候选（模板+参数）跑同一段回测的对比矩阵。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateBacktestCompareVO implements Serializable {

    /** 每个候选一份摘要 */
    private List<Candidate> candidates;

    /** 冠军候选下标（按总收益率排序） */
    private Integer winnerIndex;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate implements Serializable {
        /** 候选名（前端展示） */
        private String label;
        /** 使用的模板 id + version */
        private Long templateId;
        private Integer templateVersion;
        /** 关键指标摘要 */
        private java.math.BigDecimal returnRate;
        private java.math.BigDecimal maxDrawdown;
        private java.math.BigDecimal winRate;
        private Integer totalTrades;
        private java.math.BigDecimal sharpeRatio;
        private java.math.BigDecimal profitLossRatio;
        /** 完整回测 VO —— 前端可下钻查看 trades / equity */
        private BacktestResultVO full;
        /** 生成时用户填的参数 */
        private Map<String, Object> userParams;
        /** 失败信息（成功=null） */
        private String errorMessage;
    }
}
