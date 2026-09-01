package com.vertex.service.strategy.template;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.dto.strategy.TemplateBuildDTO;
import com.vertex.model.dto.strategy.TemplateSaveDTO;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.strategy.StrategyTemplate;
import com.vertex.model.entity.strategy.StrategyTemplateHistory;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.vo.strategy.StrategyTemplateVO;
import com.vertex.model.vo.strategy.TemplateBacktestCompareVO;
import com.vertex.model.vo.strategy.TemplateExposedParam;
import com.vertex.service.strategy.backtest.BacktestService;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.mapper.StrategyTemplateHistoryMapper;
import com.vertex.service.strategy.mapper.StrategyTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 模板层核心服务：CRUD + 版本管理 + build/preview/quickBacktest + A/B compare。
 * <p>
 * "从模板创建策略"三条路径：
 * <ul>
 *   <li>{@link #build(TemplateBuildDTO)}         —— 落库新 Strategy</li>
 *   <li>{@link #preview(TemplateBuildDTO)}       —— 只生成 Strategy 结构（不落库，返给前端 JSON）</li>
 *   <li>{@link #quickBacktest(TemplateBuildDTO)} —— 预览 + 临时落库 + 回测 + 立即删除</li>
 * </ul>
 * A/B：{@link #abCompare(List, Long, Long, BigDecimal)} 并行跑 N 个候选。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyTemplateService {

    private final StrategyTemplateMapper mapper;
    private final StrategyTemplateHistoryMapper historyMapper;
    private final StrategyMapper strategyMapper;
    private final StrategyTemplateParser parser;
    private final TemplateValidator validator;
    private final BacktestService backtestService;

    // ─── CRUD ────────────────────────────────────────────────

    public List<StrategyTemplateVO> list(String status, String category) {
        LambdaQueryWrapper<StrategyTemplate> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(StrategyTemplate::getStatus, status);
        if (category != null && !category.isBlank()) w.eq(StrategyTemplate::getCategory, category);
        w.orderByDesc(StrategyTemplate::getIsBuiltin).orderByAsc(StrategyTemplate::getCode);
        return mapper.selectList(w).stream().map(this::toVO).toList();
    }

    public StrategyTemplateVO get(Long id) {
        StrategyTemplate t = mapper.selectById(id);
        return t == null ? null : toVO(t);
    }

    public StrategyTemplateVO getByCode(String code) {
        StrategyTemplate t = mapper.selectOne(new LambdaQueryWrapper<StrategyTemplate>()
                .eq(StrategyTemplate::getCode, code));
        return t == null ? null : toVO(t);
    }

    /** 保存（新增/更新）+ 可选发布。发布时写快照 + version++。 */
    @Transactional(rollbackFor = Exception.class)
    public StrategyTemplateVO save(TemplateSaveDTO dto, Long updateBy) {
        // 先校验
        List<TemplateExposedParam> exposed = dto.getExposedParams() == null ? List.of() :
                JSON.parseArray(JSON.toJSONString(dto.getExposedParams()), TemplateExposedParam.class);
        TemplateValidator.ValidationResult vr = validator.validate(dto.getTemplate(), exposed);
        if (!vr.valid()) {
            throw new IllegalArgumentException("模板校验失败：" + String.join("; ", vr.errors()));
        }

        boolean isNew = dto.getId() == null;
        StrategyTemplate entity = isNew ? new StrategyTemplate() : mapper.selectById(dto.getId());
        if (entity == null) throw new IllegalArgumentException("模板不存在：" + dto.getId());

        // 内置模板保护：只允许 fork（新建），不允许直接改
        if (!isNew && entity.getIsBuiltin() != null && entity.getIsBuiltin() == 1) {
            throw new IllegalStateException("内置模板不可直接编辑，请先 fork");
        }

        if (isNew) {
            entity.setCode(dto.getCode());
            entity.setVersion(1);
            entity.setIsBuiltin(0);
        }
        if (dto.getName() != null)         entity.setName(dto.getName());
        if (dto.getDescription() != null)  entity.setDescription(dto.getDescription());
        if (dto.getCategory() != null)     entity.setCategory(dto.getCategory());
        if (dto.getRiskProfile() != null)  entity.setRiskProfile(dto.getRiskProfile());
        if (dto.getMarketFit() != null)    entity.setMarketFitJson(JSON.toJSONString(dto.getMarketFit()));
        if (dto.getIntervalFit() != null)  entity.setIntervalFitJson(JSON.toJSONString(dto.getIntervalFit()));
        if (dto.getTemplate() != null)     entity.setTemplateJson(JSON.toJSONString(dto.getTemplate()));
        if (dto.getExposedParams() != null) entity.setExposedParamsJson(JSON.toJSONString(dto.getExposedParams()));
        if (dto.getSchemaVersion() != null) entity.setSchemaVersion(dto.getSchemaVersion());
        if (dto.getMinEngineVersion() != null) entity.setMinEngineVersion(dto.getMinEngineVersion());
        if (dto.getStatus() != null)       entity.setStatus(dto.getStatus());

        if (Boolean.TRUE.equals(dto.getPublish())) {
            entity.setStatus("PUBLISHED");
            if (!isNew) entity.setVersion((entity.getVersion() == null ? 1 : entity.getVersion()) + 1);
        }

        if (isNew) mapper.insert(entity);
        else mapper.updateById(entity);

        // 发布 = 写快照
        if (Boolean.TRUE.equals(dto.getPublish())) {
            StrategyTemplateHistory h = new StrategyTemplateHistory();
            h.setTemplateId(entity.getId());
            h.setVersion(entity.getVersion());
            h.setTemplateJsonSnapshot(entity.getTemplateJson());
            h.setExposedParamsSnapshot(entity.getExposedParamsJson());
            h.setChangeNote(dto.getChangeNote());
            h.setChangedBy(updateBy);
            h.setChangedAt(java.time.LocalDateTime.now());
            historyMapper.insert(h);
        }

        log.info("[Template] {} template id={} code={} version={} publish={}",
                isNew ? "created" : "updated", entity.getId(), entity.getCode(),
                entity.getVersion(), dto.getPublish());
        return toVO(entity);
    }

    public void delete(Long id) {
        StrategyTemplate t = mapper.selectById(id);
        if (t == null) return;
        if (t.getIsBuiltin() != null && t.getIsBuiltin() == 1) {
            throw new IllegalStateException("内置模板不可删除");
        }
        mapper.deleteById(id);
    }

    public List<StrategyTemplateHistory> history(Long templateId) {
        return historyMapper.selectList(new LambdaQueryWrapper<StrategyTemplateHistory>()
                .eq(StrategyTemplateHistory::getTemplateId, templateId)
                .orderByDesc(StrategyTemplateHistory::getVersion));
    }

    // ─── build / preview / quickBacktest ─────────────────────

    /** 生成 Strategy 但**不落库** —— 前端预览用 */
    public Strategy preview(TemplateBuildDTO dto) {
        StrategyTemplate tpl = mapper.selectById(dto.getTemplateId());
        if (tpl == null) throw new IllegalArgumentException("模板不存在：" + dto.getTemplateId());
        return parser.build(tpl, dto.getUserParams(), dto.getStrategyName());
    }

    /** 生成 + 落库 → 返回新 Strategy 的 id */
    @Transactional(rollbackFor = Exception.class)
    public Strategy build(TemplateBuildDTO dto) {
        Strategy s = preview(dto);
        strategyMapper.insert(s);
        log.info("[Template] built strategy id={} name={} from templateId={}",
                s.getId(), s.getName(), dto.getTemplateId());
        return s;
    }

    /**
     * 预览 + 临时落库 + 跑一次回测 + 立即物理删除临时策略。
     * 中间任何异常都保证清理临时策略（finally）。
     */
    public BacktestResultVO quickBacktest(TemplateBuildDTO dto) {
        Strategy tmp = preview(dto);
        // 名字加上前缀避免和用户策略混淆
        tmp.setName("__PREVIEW__" + System.currentTimeMillis() + "__" + tmp.getName());
        tmp.setEnabled(0);
        tmp.setAutoTrade(0);
        Long tmpId = null;
        try {
            strategyMapper.insert(tmp);
            tmpId = tmp.getId();

            BacktestConfigDTO cfg = new BacktestConfigDTO();
            cfg.setStrategyId(tmpId);
            long now = System.currentTimeMillis();
            cfg.setStartTime(dto.getBacktestStartTime() != null ? dto.getBacktestStartTime()
                    : now - 30L * 24 * 3600 * 1000);   // 默认最近 30 天
            cfg.setEndTime(dto.getBacktestEndTime() != null ? dto.getBacktestEndTime() : now);
            if (dto.getBacktestInitialCapital() != null) {
                cfg.setInitialCapital(dto.getBacktestInitialCapital());
            }
            cfg.setForceRefresh(true);   // 临时策略不走缓存

            return backtestService.runBacktest(cfg);
        } finally {
            if (tmpId != null) {
                try {
                    // 物理删除，MyBatis-Plus 默认软删除；这里用 deleteById 会走 @TableLogic
                    // 直接调 mapper.deleteById → 会 UPDATE deleted=1 —— 我们只是标记删除，OK
                    strategyMapper.deleteById(tmpId);
                } catch (Exception ex) {
                    log.warn("[Template] quickBacktest cleanup failed: id={}, err={}", tmpId, ex.getMessage());
                }
            }
        }
    }

    // ─── A/B compare ─────────────────────────────────────────

    /**
     * 每个候选 = 一份 TemplateBuildDTO；跑 N 次 quickBacktest 汇总对比。
     * 冠军按 returnRate 降序取 index 0。
     */
    public TemplateBacktestCompareVO abCompare(List<TemplateBuildDTO> candidates,
                                                Long startTime, Long endTime, BigDecimal initialCapital) {
        if (candidates == null || candidates.size() < 2) {
            throw new IllegalArgumentException("A/B 对比至少需要 2 个候选");
        }

        List<TemplateBacktestCompareVO.Candidate> results = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            TemplateBuildDTO c = candidates.get(i);
            c.setBacktestStartTime(startTime);
            c.setBacktestEndTime(endTime);
            c.setBacktestInitialCapital(initialCapital);

            TemplateBacktestCompareVO.Candidate cand = TemplateBacktestCompareVO.Candidate.builder()
                    .label("候选 " + (i + 1))
                    .templateId(c.getTemplateId())
                    .userParams(c.getUserParams())
                    .build();
            try {
                StrategyTemplate tpl = mapper.selectById(c.getTemplateId());
                if (tpl != null) cand.setTemplateVersion(tpl.getVersion());

                BacktestResultVO r = quickBacktest(c);
                cand.setFull(r);
                cand.setReturnRate(r.getReturnRate());
                cand.setMaxDrawdown(r.getMaxDrawdown());
                cand.setWinRate(r.getWinRate());
                cand.setTotalTrades(r.getTotalTrades());
                cand.setSharpeRatio(r.getSharpeRatio());
                cand.setProfitLossRatio(r.getProfitLossRatio());
            } catch (Exception ex) {
                log.warn("[Template A/B] candidate {} failed: {}", i, ex.getMessage(), ex);
                cand.setErrorMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            results.add(cand);
        }

        // 找赢家 —— 只考虑成功的 + returnRate 最大
        int winner = -1;
        BigDecimal bestReturn = null;
        for (int i = 0; i < results.size(); i++) {
            TemplateBacktestCompareVO.Candidate r = results.get(i);
            if (r.getErrorMessage() != null || r.getReturnRate() == null) continue;
            if (bestReturn == null || r.getReturnRate().compareTo(bestReturn) > 0) {
                bestReturn = r.getReturnRate();
                winner = i;
            }
        }

        return TemplateBacktestCompareVO.builder()
                .candidates(results)
                .winnerIndex(winner < 0 ? null : winner)
                .build();
    }

    // ─── VO 转换 ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private StrategyTemplateVO toVO(StrategyTemplate e) {
        StrategyTemplateVO vo = new StrategyTemplateVO();
        vo.setId(e.getId());
        vo.setCode(e.getCode());
        vo.setName(e.getName());
        vo.setDescription(e.getDescription());
        vo.setCategory(e.getCategory());
        vo.setVersion(e.getVersion());
        vo.setStatus(e.getStatus());
        vo.setRiskProfile(e.getRiskProfile());
        vo.setMinEngineVersion(e.getMinEngineVersion());
        vo.setSchemaVersion(e.getSchemaVersion());
        vo.setIsBuiltin(e.getIsBuiltin());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        try {
            if (e.getMarketFitJson() != null) vo.setMarketFit((List<String>) JSON.parseArray(e.getMarketFitJson(), String.class));
            if (e.getIntervalFitJson() != null) vo.setIntervalFit((List<String>) JSON.parseArray(e.getIntervalFitJson(), String.class));
            if (e.getTemplateJson() != null) vo.setTemplate(JSON.parse(e.getTemplateJson()));
            if (e.getExposedParamsJson() != null) {
                vo.setExposedParams(JSON.parseArray(e.getExposedParamsJson(), TemplateExposedParam.class));
            }
        } catch (Exception ex) {
            log.warn("[Template] parse JSON failed for template id={} code={}: {}",
                    e.getId(), e.getCode(), ex.getMessage());
        }
        return vo;
    }
}
