package com.vertex.service.chain.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.chain.AlertRule;
import com.vertex.model.entity.chain.ChainToken;
import com.vertex.model.entity.chain.ChainTokenMetrics;
import com.vertex.service.chain.mapper.AlertRuleMapper;
import com.vertex.service.chain.mapper.ChainTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 链上告警服务
 * <p>
 * 负责：
 * <ol>
 *   <li>查询所有已启用的告警规则</li>
 *   <li>对每条代币记录逐规则匹配</li>
 *   <li>匹配成功则通过所有 {@link AlertNotifier} 发送通知</li>
 *   <li>标记代币为已告警，防止重复推送</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainAlertService {

    private final AlertRuleMapper alertRuleMapper;
    private final ChainTokenMapper chainTokenMapper;
    private final List<AlertNotifier> notifiers;

    /**
     * 对新评分代币执行告警检查
     *
     * @param token   已评分的代币
     * @param metrics 最新指标快照
     */
    public void checkAndAlert(ChainToken token, ChainTokenMetrics metrics) {
        // 已告警则跳过
        if (Integer.valueOf(1).equals(token.getAlerted())) {
            return;
        }

        List<AlertRule> rules = alertRuleMapper.selectList(
                new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getEnabled, 1));

        for (AlertRule rule : rules) {
            if (matches(token, metrics, rule)) {
                log.info("[ChainAlert] Token {} {} triggered rule '{}'",
                        token.getChain(), token.getContractAddress(), rule.getName());
                dispatchAlert(token, metrics, rule);
                markAlerted(token, token.getScore());
                return; // 首条匹配规则触发后即停止，避免重复告警
            }
        }
    }

    // ─── 规则匹配 ──────────────────────────────────────────

    private boolean matches(ChainToken token, ChainTokenMetrics metrics, AlertRule rule) {
        // 链过滤
        if (rule.getChain() != null && !"ALL".equalsIgnoreCase(rule.getChain())) {
            if (!rule.getChain().equalsIgnoreCase(token.getChain())) return false;
        }

        // 最低评分
        if (rule.getMinScore() != null && token.getScore() < rule.getMinScore()) return false;

        if (metrics != null) {
            // 最低市值
            if (rule.getMinMarketCapUsd() != null && metrics.getMarketCapUsd() != null) {
                if (metrics.getMarketCapUsd().compareTo(rule.getMinMarketCapUsd()) < 0) return false;
            }

            // 最低流动性
            if (rule.getMinLiquidityUsd() != null && metrics.getLiquidityUsd() != null) {
                if (metrics.getLiquidityUsd().compareTo(rule.getMinLiquidityUsd()) < 0) return false;
            }

            // 最低持有者数
            if (rule.getMinHolderCount() != null && metrics.getHolderCount() != null) {
                if (metrics.getHolderCount() < rule.getMinHolderCount()) return false;
            }

            // Top10 集中度上限
            if (rule.getMaxTop10HolderPct() != null && metrics.getTop10HolderPct() != null) {
                if (metrics.getTop10HolderPct().compareTo(rule.getMaxTop10HolderPct()) > 0) return false;
            }

            // 流动性锁定要求
            if (Integer.valueOf(1).equals(rule.getRequireLiquidityLocked())) {
                if (!Integer.valueOf(1).equals(metrics.getLiquidityLocked())) return false;
            }
        }

        return true;
    }

    // ─── 通知分发 ──────────────────────────────────────────

    private void dispatchAlert(ChainToken token, ChainTokenMetrics metrics, AlertRule rule) {
        // 解析通知渠道列表（JSON 数组 or 逗号分隔）
        String channels = rule.getNotifyChannels();
        for (AlertNotifier notifier : notifiers) {
            if (!notifier.isEnabled()) continue;
            if (channels != null && !channels.contains(notifier.channel())) continue;
            try {
                notifier.sendAlert(token, metrics, rule);
            } catch (Exception e) {
                log.error("[ChainAlert] Notifier [{}] failed for {} {}: {}",
                        notifier.channel(), token.getChain(), token.getContractAddress(), e.getMessage());
            }
        }
    }

    private void markAlerted(ChainToken token, int score) {
        token.setAlerted(1);
        token.setAlertScore(score);
        token.setStatus("ALERTED");
        chainTokenMapper.updateById(token);
    }
}
