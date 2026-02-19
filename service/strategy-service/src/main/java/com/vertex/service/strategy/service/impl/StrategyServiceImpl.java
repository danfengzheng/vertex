package com.vertex.service.strategy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.strategy.IStrategyService;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.StrategyCreateDTO;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.dto.strategy.StrategyQueryDTO;
import com.vertex.model.dto.strategy.StrategyUpdateDTO;
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
        strategy.setEnabled(0); // 默认禁用
        // 交易配置
        strategy.setAutoTrade(dto.getAutoTrade());
        strategy.setTradeMode(dto.getTradeMode());
        strategy.setExecutionMode(dto.getExecutionMode());
        strategy.setAccountId(dto.getAccountId());
        strategy.setTradeQuantity(dto.getTradeQuantity());
        strategy.setStopLossPct(dto.getStopLossPct());
        strategy.setTakeProfitPct(dto.getTakeProfitPct());
        strategy.setFeeRate(dto.getFeeRate());
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
        if (dto.getTradeQuantity() != null) strategy.setTradeQuantity(dto.getTradeQuantity());
        if (dto.getStopLossPct() != null) strategy.setStopLossPct(dto.getStopLossPct());
        if (dto.getTakeProfitPct() != null) strategy.setTakeProfitPct(dto.getTakeProfitPct());
        if (dto.getFeeRate() != null) strategy.setFeeRate(dto.getFeeRate());

        strategyMapper.updateById(strategy);
    }

    @Override
    public void delete(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        strategyMapper.deleteById(id);
    }

    @Override
    public StrategyVO getById(Long id) {
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        return toVO(strategy);
    }

    @Override
    public PageResult<StrategyVO> page(StrategyQueryDTO query) {
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .like(StringUtils.hasText(query.getName()), Strategy::getName, query.getName())
                .eq(StringUtils.hasText(query.getExchange()), Strategy::getExchange, query.getExchange())
                .eq(StringUtils.hasText(query.getSymbol()), Strategy::getSymbol, query.getSymbol())
                .eq(query.getEnabled() != null, Strategy::getEnabled, query.getEnabled())
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

            // 3. 逐一检查并订阅（日 K 以下由数据源合并为 trade，≥1D 按周期订阅 kline）
            // 策略侧保持「按区间订阅」调用即可；<1D 的 K 线由聚合器 flush 后经 KLineEvent 驱动策略
            for (KLineInterval iv : allIntervals) {
                boolean alreadySubscribed = ds.getSubscriptions().stream()
                        .anyMatch(sub -> strategy.getSymbol().equals(sub.get("symbol"))
                                && iv.name().equals(sub.get("interval")));

                if (!alreadySubscribed) {
                    log.info("[AutoSubscribe] Subscribing {}:{} on {}",
                            strategy.getSymbol(), iv.getCode(), ds.exchangeCode());
                    ds.subscribe(strategy.getSymbol(), iv);
                } else {
                    log.info("[AutoSubscribe] Already subscribed to {}:{} on {}",
                            strategy.getSymbol(), iv.getCode(), ds.exchangeCode());
                }
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

    private StrategyVO toVO(Strategy strategy) {
        StrategyVO vo = new StrategyVO();
        BeanUtil.copyProperties(strategy, vo, "indicatorConfigs");
        // JSON 字符串 → List 反序列化
        vo.setIndicatorConfigs(
                JSON.parseArray(strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class));
        return vo;
    }
}
