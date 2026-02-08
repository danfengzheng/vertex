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
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.StrategyVO;
import com.vertex.service.strategy.mapper.StrategyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 策略服务实现
 */
@Service
@RequiredArgsConstructor
public class StrategyServiceImpl implements IStrategyService {

    private final StrategyMapper strategyMapper;

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
