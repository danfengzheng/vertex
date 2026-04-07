package com.vertex.service.strategy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.strategy.IStrategyService;
import com.vertex.common.core.context.UserContext;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.FilterCondition;
import com.vertex.model.dto.strategy.StrategyCreateDTO;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.dto.strategy.StrategyQueryDTO;
import com.vertex.model.dto.strategy.StrategyUpdateDTO;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.StrategyVO;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.service.StrategyDataWarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 策略服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyServiceImpl implements IStrategyService {

    private final StrategyMapper strategyMapper;
    private final List<QuoteDataSource> dataSources;
    private final StrategyDataWarmupService dataWarmupService;

    @Override
    public Long create(StrategyCreateDTO dto) {
        // 名称唯一校验
        LambdaQueryWrapper<Strategy> nameCheck = new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getName, dto.getName())
                .eq(Strategy::getDeleted, 0);
        if (strategyMapper.selectCount(nameCheck) > 0) {
            throw new BizException(GlobalError.STRATEGY_ALREADY_EXISTS);
        }

        Strategy strategy = new Strategy();
        strategy.setName(dto.getName());
        strategy.setDescription(dto.getDescription());
        strategy.setExchange(dto.getExchange());
        strategy.setSymbol(dto.getSymbol());
        strategy.setInterval(dto.getInterval());
        strategy.setIndicatorConfigs(JSON.toJSONString(dto.getIndicatorConfigs()));
        strategy.setExitIndicatorConfigs(dto.getExitIndicatorConfigs() != null && !dto.getExitIndicatorConfigs().isEmpty()
                ? JSON.toJSONString(dto.getExitIndicatorConfigs()) : null);
        strategy.setMaxHoldingBars(dto.getMaxHoldingBars());
        strategy.setEnabled(0); // 默认禁用
        // 交易配置
        strategy.setAutoTrade(dto.getAutoTrade());
        strategy.setMinSignalStrength(dto.getMinSignalStrength());
        strategy.setTradeMode(dto.getTradeMode());
        strategy.setExecutionMode(dto.getExecutionMode());
        strategy.setAccountId(dto.getAccountId());
        strategy.setPositionSizing(dto.getPositionSizing());
        strategy.setTradeQuantity(dto.getTradeQuantity());
        strategy.setPositionRatio(dto.getPositionRatio());
        strategy.setInitialCapital(dto.getInitialCapital());
        strategy.setStopLossPct(dto.getStopLossPct());
        strategy.setTakeProfitPct(dto.getTakeProfitPct());
        strategy.setFeeRate(dto.getFeeRate());
        strategy.setAtrStopMultiplier(dto.getAtrStopMultiplier());
        strategy.setAtrTakeProfitMultiplier(dto.getAtrTakeProfitMultiplier());
        strategy.setInitialStopMultiplier(dto.getInitialStopMultiplier());
        strategy.setBreakevenActivationMultiplier(dto.getBreakevenActivationMultiplier());
        strategy.setTrailingActivationMultiplier(dto.getTrailingActivationMultiplier());
        strategy.setTrailingDistanceMultiplier(dto.getTrailingDistanceMultiplier());
        strategy.setAtrInterval(dto.getAtrInterval());
        strategy.setTrailingDropPct(dto.getTrailingDropPct());
        strategy.setDailyLossLimitPct(dto.getDailyLossLimitPct());
        strategyMapper.insert(strategy);
        return strategy.getId();
    }

    @Override
    public void update(StrategyUpdateDTO dto) {
        Strategy strategy = strategyMapper.selectById(dto.getId());
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        // 如果更新了名称，校验唯一性
        if (StringUtils.hasText(dto.getName()) && !dto.getName().equals(strategy.getName())) {
            LambdaQueryWrapper<Strategy> nameCheck = new LambdaQueryWrapper<Strategy>()
                    .eq(Strategy::getName, dto.getName())
                    .ne(Strategy::getId, dto.getId())
                    .eq(Strategy::getDeleted, 0);
            if (strategyMapper.selectCount(nameCheck) > 0) {
                throw new BizException(GlobalError.STRATEGY_ALREADY_EXISTS);
            }
            strategy.setName(dto.getName());
        }

        if (dto.getDescription() != null) strategy.setDescription(dto.getDescription());
        if (dto.getExchange() != null) strategy.setExchange(dto.getExchange());
        if (dto.getSymbol() != null) strategy.setSymbol(dto.getSymbol());
        if (dto.getInterval() != null) strategy.setInterval(dto.getInterval());
        if (dto.getIndicatorConfigs() != null && !dto.getIndicatorConfigs().isEmpty()) {
            strategy.setIndicatorConfigs(JSON.toJSONString(dto.getIndicatorConfigs()));
        }
        // 交易配置
        if (dto.getAutoTrade() != null) strategy.setAutoTrade(dto.getAutoTrade());
        if (dto.getTradeMode() != null) strategy.setTradeMode(dto.getTradeMode());
        if (dto.getExecutionMode() != null) strategy.setExecutionMode(dto.getExecutionMode());
        if (dto.getAccountId() != null) strategy.setAccountId(dto.getAccountId());
        if (dto.getPositionSizing() != null) strategy.setPositionSizing(dto.getPositionSizing());
        if (dto.getTradeQuantity() != null) strategy.setTradeQuantity(dto.getTradeQuantity());
        if (dto.getPositionRatio() != null) strategy.setPositionRatio(dto.getPositionRatio());
        if (dto.getInitialCapital() != null) strategy.setInitialCapital(dto.getInitialCapital());
        // 合约配置
        if (dto.getLeverage() != null) strategy.setLeverage(dto.getLeverage());
        if (dto.getMarginType() != null) strategy.setMarginType(dto.getMarginType());
        // 以下字段允许清空（前端明确发送 null 时置空数据库中的值）
        strategy.setMinSignalStrength(dto.getMinSignalStrength());
        strategy.setStopLossPct(dto.getStopLossPct());
        strategy.setTakeProfitPct(dto.getTakeProfitPct());
        strategy.setFeeRate(dto.getFeeRate());
        strategy.setAtrStopMultiplier(dto.getAtrStopMultiplier());
        strategy.setAtrTakeProfitMultiplier(dto.getAtrTakeProfitMultiplier());
        strategy.setInitialStopMultiplier(dto.getInitialStopMultiplier());
        strategy.setBreakevenActivationMultiplier(dto.getBreakevenActivationMultiplier());
        strategy.setTrailingActivationMultiplier(dto.getTrailingActivationMultiplier());
        strategy.setTrailingDistanceMultiplier(dto.getTrailingDistanceMultiplier());
        strategy.setAtrInterval(dto.getAtrInterval());
        strategy.setTrailingDropPct(dto.getTrailingDropPct());
        strategy.setDailyLossLimitPct(dto.getDailyLossLimitPct());
        // 出场配置：传 null=不改；传空列表=清除；传非空列表=更新
        if (dto.getExitIndicatorConfigs() != null) {
            strategy.setExitIndicatorConfigs(dto.getExitIndicatorConfigs().isEmpty()
                    ? null : JSON.toJSONString(dto.getExitIndicatorConfigs()));
        }
        strategy.setMaxHoldingBars(dto.getMaxHoldingBars());

        strategyMapper.updateById(strategy);
    }

    @Override
    public void delete(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        // 软删除前先重命名，释放 uk_name(name, deleted) 中的名称占用。
        // 若不重命名：create → delete → create(同名) → delete(同名) 时，
        // 两条记录的 (name, deleted=1) 组合相同，第二次 deleteById 会触发唯一键冲突。
        strategy.setName(strategy.getName() + "_del_" + id);
        strategyMapper.updateById(strategy);
        strategyMapper.deleteById(id);
    }

    @Override
    public Long copy(Long id) {
        Strategy source = strategyMapper.selectById(id);
        if (source == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        // 生成不重名的副本名称：原名 (副本)、原名 (副本2)、原名 (副本3) …
        String baseName = source.getName() + " (副本)";
        String copyName = baseName;
        int counter = 2;
        while (strategyMapper.selectCount(new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getName, copyName)
                .eq(Strategy::getDeleted, 0)) > 0) {
            copyName = baseName + counter++;
        }

        // 逐字段复制，确保所有配置完整保留
        Strategy copy = new Strategy();
        copy.setName(copyName);
        copy.setDescription(source.getDescription());
        copy.setExchange(source.getExchange());
        copy.setSymbol(source.getSymbol());
        copy.setInterval(source.getInterval());
        copy.setIndicatorConfigs(source.getIndicatorConfigs());
        copy.setExitIndicatorConfigs(source.getExitIndicatorConfigs());
        copy.setMaxHoldingBars(source.getMaxHoldingBars());
        copy.setEnabled(0); // 副本默认禁用，需用户确认后再启用

        // 交易配置
        copy.setAutoTrade(source.getAutoTrade());
        copy.setMinSignalStrength(source.getMinSignalStrength());
        copy.setTradeMode(source.getTradeMode());
        copy.setExecutionMode(source.getExecutionMode());
        copy.setAccountId(source.getAccountId());
        copy.setPositionSizing(source.getPositionSizing());
        copy.setTradeQuantity(source.getTradeQuantity());
        copy.setPositionRatio(source.getPositionRatio());
        copy.setInitialCapital(source.getInitialCapital());
        copy.setStopLossPct(source.getStopLossPct());
        copy.setTakeProfitPct(source.getTakeProfitPct());
        copy.setFeeRate(source.getFeeRate());

        // 合约配置
        copy.setLeverage(source.getLeverage());
        copy.setMarginType(source.getMarginType());

        // ATR 止损止盈
        copy.setAtrStopMultiplier(source.getAtrStopMultiplier());
        copy.setAtrTakeProfitMultiplier(source.getAtrTakeProfitMultiplier());

        // 移动ATR止损
        copy.setInitialStopMultiplier(source.getInitialStopMultiplier());
        copy.setBreakevenActivationMultiplier(source.getBreakevenActivationMultiplier());
        copy.setTrailingActivationMultiplier(source.getTrailingActivationMultiplier());
        copy.setTrailingDistanceMultiplier(source.getTrailingDistanceMultiplier());
        copy.setAtrInterval(source.getAtrInterval());
        copy.setTrailingDropPct(source.getTrailingDropPct());
        copy.setDailyLossLimitPct(source.getDailyLossLimitPct());
        // tradingPausedUntil 不复制：副本是新策略，不继承暂停状态

        strategyMapper.insert(copy);
        log.info("策略复制成功: 源={} → 副本={} (id={})", source.getName(), copyName, copy.getId());
        return copy.getId();
    }

    @Override
    public StrategyVO getById(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        // 数据权限：非管理员只能查看自己的策略
        if (!UserContext.isAdmin()) {
            Long currentUserId = UserContext.getUserId();
            if (currentUserId != null && !currentUserId.equals(strategy.getCreateBy())) {
                throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
            }
        }
        return toVO(strategy);
    }

    @Override
    public PageResult<StrategyVO> page(StrategyQueryDTO query) {
        boolean isAdmin = UserContext.isAdmin();
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .like(StringUtils.hasText(query.getName()), Strategy::getName, query.getName())
                .eq(StringUtils.hasText(query.getExchange()), Strategy::getExchange, query.getExchange())
                .eq(StringUtils.hasText(query.getSymbol()), Strategy::getSymbol, query.getSymbol())
                .eq(query.getEnabled() != null, Strategy::getEnabled, query.getEnabled())
                .eq(!isAdmin && currentUserId != null, Strategy::getCreateBy, currentUserId)
                .orderByDesc(Strategy::getCreateTime);

        Page<Strategy> page = strategyMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<StrategyVO> records = page.getRecords().stream()
                .map(this::toVO)
                .toList();

        return PageResult.of(page.getTotal(), records);
    }

    @Override
    public void enable(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        strategy.setEnabled(1);
        strategyMapper.updateById(strategy);

        // 自动连接数据源并订阅行情
        autoSubscribe(strategy);

        // 检查历史K线数据是否充足，不足则自动补全
        try {
            int backfilled = dataWarmupService.warmup(strategy);
            if (backfilled > 0) {
                log.info("[Enable] Strategy '{}' data warmup: {} K-lines backfilled.",
                        strategy.getName(), backfilled);
            }
        } catch (Exception e) {
            log.error("[Enable] Data warmup failed for strategy '{}': {}",
                    strategy.getName(), e.getMessage(), e);
        }
    }

    /**
     * 启用策略时自动确保数据源已连接，并订阅对应交易对和所有用到的周期。
     * 遍历所有指标配置，收集所有需要的 K线周期，逐一检查并订阅。
     */
    private void autoSubscribe(Strategy strategy) {
        try {
            QuoteDataSource ds = dataSources.stream()
                    .filter(d -> strategy.getExchange().equalsIgnoreCase(d.exchangeCode()))
                    .findFirst()
                    .orElse(null);
            if (ds == null) {
                log.warn("[AutoSubscribe] No data source found for exchange: {}", strategy.getExchange());
                return;
            }

            // 1. 如果未连接，自动启动
            if (!ds.isConnected()) {
                log.info("[AutoSubscribe] Data source '{}' is not connected, starting...", ds.exchangeCode());
                ds.start();
            }

            // 2. 收集策略所有用到的周期（含指标自定义周期）
            Set<KLineInterval> allIntervals = collectAllIntervals(strategy);

            // 3. 逐一订阅所需周期（直接调用，不跳过）
            // subscribeKline/subscribeTrade 内部已做 listener 去重，重复调用不会积累重复监听器；
            // 每次调用都会触发 scheduleBatchSubscribe() 把订阅重发给交易所，
            // 确保 WebSocket 重连后订阅状态与交易所保持同步。
            for (KLineInterval iv : allIntervals) {
                log.info("[AutoSubscribe] Subscribing {}:{} on {}",
                        strategy.getSymbol(), iv.getCode(), ds.exchangeCode());
                ds.subscribe(strategy.getSymbol(), iv);
            }
        } catch (Exception e) {
            // 自动订阅失败不阻塞策略启用
            log.error("[AutoSubscribe] Failed to auto-subscribe for strategy '{}': {}",
                    strategy.getName(), e.getMessage(), e);
        }
    }

    /** 收集策略所有用到的K线周期（含指标自定义周期，去重） */
    private Set<KLineInterval> collectAllIntervals(Strategy strategy) {
        Set<KLineInterval> intervals = new HashSet<>();
        intervals.add(strategy.getInterval()); // 始终包含默认周期
        List<StrategyIndicatorConfig> configs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (configs != null) {
            for (StrategyIndicatorConfig c : configs) {
                intervals.add(c.getInterval() != null ? c.getInterval() : strategy.getInterval());
            }
        }
        return intervals;
    }

    @Override
    public void disable(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        strategy.setEnabled(0);
        strategyMapper.updateById(strategy);
    }

    @Override
    public int addDivergenceExitToRunningStrategies() {
        // 查询所有运行中的策略
        List<Strategy> running = strategyMapper.selectList(
                new LambdaQueryWrapper<Strategy>()
                        .eq(Strategy::getEnabled, 1)
                        .eq(Strategy::getDeleted, 0));

        if (running.isEmpty()) {
            log.info("[AddDivergence] 当前没有运行中的策略，跳过");
            return 0;
        }

        // 构造默认背离出场配置
        FilterCondition sellCond = new FilterCondition();
        sellCond.setField("bearishDivergence");
        sellCond.setOp("GTE");
        sellCond.setThreshold(1.0);

        StrategyIndicatorConfig divergenceConfig = new StrategyIndicatorConfig();
        divergenceConfig.setIndicatorType(IndicatorType.DIVERGENCE);
        divergenceConfig.setParams(Map.of("lookback", 20, "rsiPeriod", 14, "swingStrength", 2));
        divergenceConfig.setWeight(100);
        divergenceConfig.setSellConditions(List.of(sellCond));

        int updated = 0;
        for (Strategy strategy : running) {
            // 解析现有出场指标配置
            List<StrategyIndicatorConfig> exitConfigs = strategy.getExitIndicatorConfigs() != null
                    ? new ArrayList<>(JSON.parseArray(strategy.getExitIndicatorConfigs(), StrategyIndicatorConfig.class))
                    : new ArrayList<>();

            // 检查是否已存在 DIVERGENCE 类型
            boolean alreadyExists = exitConfigs.stream()
                    .anyMatch(c -> IndicatorType.DIVERGENCE.equals(c.getIndicatorType()));
            if (alreadyExists) {
                log.info("[AddDivergence] 策略 '{}' (id={}) 已存在背离配置，跳过",
                        strategy.getName(), strategy.getId());
                continue;
            }

            // 追加背离配置并保存
            exitConfigs.add(divergenceConfig);
            strategy.setExitIndicatorConfigs(JSON.toJSONString(exitConfigs));
            strategyMapper.updateById(strategy);
            updated++;
            log.info("[AddDivergence] 策略 '{}' (id={}) 已追加背离出场配置",
                    strategy.getName(), strategy.getId());
        }

        log.info("[AddDivergence] 完成：共更新 {}/{} 个运行中策略", updated, running.size());
        return updated;
    }

    private StrategyVO toVO(Strategy strategy) {
        StrategyVO vo = new StrategyVO();
        BeanUtil.copyProperties(strategy, vo, "indicatorConfigs", "exitIndicatorConfigs");
        // JSON 字符串 → List 反序列化
        vo.setIndicatorConfigs(
                JSON.parseArray(strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class));
        vo.setExitIndicatorConfigs(
                JSON.parseArray(strategy.getExitIndicatorConfigs(), StrategyIndicatorConfig.class));
        vo.setMaxHoldingBars(strategy.getMaxHoldingBars());
        return vo;
    }
}
