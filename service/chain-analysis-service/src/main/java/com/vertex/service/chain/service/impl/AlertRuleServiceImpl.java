package com.vertex.service.chain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.chain.IAlertRuleService;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.chain.AlertRuleCreateDTO;
import com.vertex.model.dto.chain.AlertRuleUpdateDTO;
import com.vertex.model.entity.chain.AlertRule;
import com.vertex.model.query.PageQuery;
import com.vertex.model.vo.chain.AlertRuleVO;
import com.vertex.service.chain.mapper.AlertRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 链上告警规则服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleServiceImpl implements IAlertRuleService {

    private final AlertRuleMapper alertRuleMapper;

    @Override
    public Long create(AlertRuleCreateDTO dto) {
        AlertRule rule = new AlertRule();
        applyDto(rule, dto);
        rule.setEnabled(1);
        alertRuleMapper.insert(rule);
        log.info("[AlertRule] Created rule '{}', id={}", rule.getName(), rule.getId());
        return rule.getId();
    }

    @Override
    public void update(AlertRuleUpdateDTO dto) {
        AlertRule rule = requireById(dto.getId());
        applyDto(rule, dto);
        alertRuleMapper.updateById(rule);
        log.info("[AlertRule] Updated rule '{}', id={}", rule.getName(), rule.getId());
    }

    @Override
    public void delete(Long id) {
        AlertRule rule = requireById(id);
        rule.setDeleted(1);
        alertRuleMapper.updateById(rule);
        log.info("[AlertRule] Deleted rule id={}", id);
    }

    @Override
    public AlertRuleVO getById(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        return rule != null ? toVO(rule) : null;
    }

    @Override
    public PageResult<AlertRuleVO> page(PageQuery query) {
        Page<AlertRule> page = alertRuleMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<AlertRule>()
                        .eq(AlertRule::getDeleted, 0)
                        .orderByDesc(AlertRule::getCreateTime));
        List<AlertRuleVO> records = page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList());
        return PageResult.of(page.getTotal(), records);
    }

    @Override
    public void enable(Long id) {
        AlertRule rule = requireById(id);
        rule.setEnabled(1);
        alertRuleMapper.updateById(rule);
    }

    @Override
    public void disable(Long id) {
        AlertRule rule = requireById(id);
        rule.setEnabled(0);
        alertRuleMapper.updateById(rule);
    }

    // ─── 内部工具 ──────────────────────────────────────────

    private AlertRule requireById(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null || Integer.valueOf(1).equals(rule.getDeleted())) {
            throw new BizException("告警规则不存在，id=" + id);
        }
        return rule;
    }

    private void applyDto(AlertRule rule, AlertRuleCreateDTO dto) {
        rule.setName(dto.getName());
        rule.setChain(dto.getChain());
        rule.setMinScore(dto.getMinScore());
        rule.setMinMarketCapUsd(dto.getMinMarketCapUsd());
        rule.setMinLiquidityUsd(dto.getMinLiquidityUsd());
        rule.setMinHolderCount(dto.getMinHolderCount());
        rule.setMaxTop10HolderPct(dto.getMaxTop10HolderPct());
        rule.setNotifyChannels(dto.getNotifyChannels());
        rule.setRequireLiquidityLocked(dto.getRequireLiquidityLocked());
        rule.setDescription(dto.getDescription());
    }

    private AlertRuleVO toVO(AlertRule rule) {
        return AlertRuleVO.builder()
                .id(rule.getId())
                .name(rule.getName())
                .chain(rule.getChain())
                .minScore(rule.getMinScore())
                .minMarketCapUsd(rule.getMinMarketCapUsd())
                .minLiquidityUsd(rule.getMinLiquidityUsd())
                .minHolderCount(rule.getMinHolderCount())
                .maxTop10HolderPct(rule.getMaxTop10HolderPct())
                .notifyChannels(rule.getNotifyChannels())
                .requireLiquidityLocked(rule.getRequireLiquidityLocked())
                .enabled(rule.getEnabled())
                .description(rule.getDescription())
                .createTime(rule.getCreateTime())
                .updateTime(rule.getUpdateTime())
                .build();
    }
}
