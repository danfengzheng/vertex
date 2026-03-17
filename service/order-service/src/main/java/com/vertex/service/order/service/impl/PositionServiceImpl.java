package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.trading.IPositionService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.PositionQueryDTO;
import com.vertex.model.entity.trading.Position;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.model.vo.trading.PositionVO;
import com.vertex.common.core.context.UserContext;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.order.mapper.PositionMapper;
import com.vertex.service.order.mapper.StrategyRefMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 持仓服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionServiceImpl implements IPositionService {

    private final PositionMapper positionMapper;
    private final StrategyRefMapper strategyMapper;
    private final PositionManagementService positionManagementService;
    private final PaperTradingService paperTradingService;
    private final TradeExecutionService tradeExecutionService;

    @Override
    public PositionVO getById(Long id) {
        Position position = positionMapper.selectById(id);
        if (position == null) {
            throw new BizException(GlobalError.POSITION_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(position.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        String strategyName = resolveStrategyName(position.getStrategyId());
        return toVO(position, strategyName);
    }

    @Override
    public PageResult<PositionVO> page(PositionQueryDTO query) {
        Page<Position> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getDeleted, 0);

        if (!UserContext.isAdmin()) {
            wrapper.eq(Position::getCreateBy, UserContext.getUserId());
        }

        wrapper.eq(query.getStrategyId() != null, Position::getStrategyId, query.getStrategyId())
                .eq(query.getExchange() != null, Position::getExchange, query.getExchange())
                .eq(query.getSymbol() != null, Position::getSymbol, query.getSymbol())
                .eq(query.getStatus() != null, Position::getStatus, query.getStatus())
                .eq(query.getTradeMode() != null, Position::getTradeMode, query.getTradeMode())
                .eq(query.getMarketType() != null, Position::getMarketType, query.getMarketType())
                .orderByDesc(Position::getCreateTime);

        Page<Position> result = positionMapper.selectPage(page, wrapper);

        // 批量查策略名，避免 N+1
        Set<Long> strategyIds = result.getRecords().stream()
                .map(Position::getStrategyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> strategyNameMap = strategyIds.isEmpty()
                ? Map.of()
                : strategyMapper.selectBatchIds(strategyIds).stream()
                        .collect(Collectors.toMap(Strategy::getId, Strategy::getName, (a, b) -> a));

        List<PositionVO> records = result.getRecords().stream()
                .map(p -> toVO(p, strategyNameMap.get(p.getStrategyId())))
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), records);
    }

    @Override
    public void close(Long id) {
        Position position = positionMapper.selectById(id);
        if (position == null) {
            throw new BizException(GlobalError.POSITION_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(position.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        if (position.getStatus() != PositionStatus.OPEN) {
            throw new BizException(GlobalError.POSITION_ALREADY_CLOSED);
        }

        // 统一走 executeClose：
        // - LIVE 模式：向交易所提交 MARKET SELL，按实际成交更新本地持仓（含手续费扣减）
        // - PAPER 模式：直接按当前市价更新本地持仓记录
        tradeExecutionService.executeClose(position);
    }

    /** 单条查询时解析策略名称 */
    private String resolveStrategyName(Long strategyId) {
        if (strategyId == null) return null;
        Strategy strategy = strategyMapper.selectById(strategyId);
        return strategy != null ? strategy.getName() : null;
    }

    private PositionVO toVO(Position position, String strategyName) {
        // 活跃持仓实时刷新当前价格和未实现盈亏
        BigDecimal currentPrice = position.getCurrentPrice();
        BigDecimal unrealizedPnl = position.getUnrealizedPnl();

        if (position.getStatus() == PositionStatus.OPEN) {
            BigDecimal latestPrice = paperTradingService.getCurrentPrice(
                    position.getExchange(), position.getSymbol());
            if (latestPrice != null) {
                currentPrice = latestPrice;
                if (position.getEntryPrice() != null
                        && position.getQuantity() != null
                        && position.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    // SHORT：价格下跌获利；LONG：价格上涨获利
                    if (position.getSide() == PositionSide.SHORT) {
                        unrealizedPnl = position.getEntryPrice().subtract(latestPrice)
                                .multiply(position.getQuantity())
                                .setScale(10, RoundingMode.HALF_UP);
                    } else {
                        unrealizedPnl = latestPrice.subtract(position.getEntryPrice())
                                .multiply(position.getQuantity())
                                .setScale(10, RoundingMode.HALF_UP);
                    }
                }
            }
        }

        return PositionVO.builder()
                .id(position.getId())
                .strategyId(position.getStrategyId())
                .strategyName(strategyName)
                .accountId(position.getAccountId())
                .exchange(position.getExchange())
                .symbol(position.getSymbol())
                .side(position.getSide())
                .quantity(position.getQuantity())
                .entryPrice(position.getEntryPrice())
                .currentPrice(currentPrice)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(position.getRealizedPnl())
                .stopLoss(position.getStopLoss())
                .takeProfit(position.getTakeProfit())
                .closePrice(position.getClosePrice())
                .closedAt(position.getClosedAt())
                .status(position.getStatus())
                .tradeMode(position.getTradeMode())
                .marketType(position.getMarketType())
                .leverage(position.getLeverage())
                .marginType(position.getMarginType())
                .liquidationPrice(position.getLiquidationPrice())
                .fundingRate(position.getFundingRate())
                .createTime(position.getCreateTime())
                .updateTime(position.getUpdateTime())
                .build();
    }
}
