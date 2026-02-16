package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.trading.IPositionService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.PositionQueryDTO;
import com.vertex.model.entity.trading.Position;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.model.vo.trading.PositionVO;
import com.vertex.service.order.mapper.PositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 持仓服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionServiceImpl implements IPositionService {

    private final PositionMapper positionMapper;
    private final PositionManagementService positionManagementService;
    private final PaperTradingService paperTradingService;

    @Override
    public PositionVO getById(Long id) {
        Position position = positionMapper.selectById(id);
        if (position == null) {
            throw new BizException(GlobalError.POSITION_NOT_FOUND);
        }
        return toVO(position);
    }

    @Override
    public PageResult<PositionVO> page(PositionQueryDTO query) {
        Page<Position> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getDeleted, 0)
                .eq(query.getStrategyId() != null, Position::getStrategyId, query.getStrategyId())
                .eq(query.getExchange() != null, Position::getExchange, query.getExchange())
                .eq(query.getSymbol() != null, Position::getSymbol, query.getSymbol())
                .eq(query.getStatus() != null, Position::getStatus, query.getStatus())
                .eq(query.getTradeMode() != null, Position::getTradeMode, query.getTradeMode())
                .orderByDesc(Position::getCreateTime);

        Page<Position> result = positionMapper.selectPage(page, wrapper);
        List<PositionVO> records = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), records);
    }

    @Override
    public void close(Long id) {
        Position position = positionMapper.selectById(id);
        if (position == null) {
            throw new BizException(GlobalError.POSITION_NOT_FOUND);
        }
        if (position.getStatus() != PositionStatus.OPEN) {
            throw new BizException(GlobalError.POSITION_ALREADY_CLOSED);
        }

        // 获取当前价格
        BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                position.getExchange(), position.getSymbol());
        if (currentPrice == null) {
            throw new BizException(GlobalError.KLINE_NOT_FOUND);
        }

        positionManagementService.closePosition(position, currentPrice);
    }

    private PositionVO toVO(Position position) {
        return PositionVO.builder()
                .id(position.getId())
                .strategyId(position.getStrategyId())
                .accountId(position.getAccountId())
                .exchange(position.getExchange())
                .symbol(position.getSymbol())
                .side(position.getSide())
                .quantity(position.getQuantity())
                .entryPrice(position.getEntryPrice())
                .currentPrice(position.getCurrentPrice())
                .unrealizedPnl(position.getUnrealizedPnl())
                .realizedPnl(position.getRealizedPnl())
                .stopLoss(position.getStopLoss())
                .takeProfit(position.getTakeProfit())
                .status(position.getStatus())
                .tradeMode(position.getTradeMode())
                .createTime(position.getCreateTime())
                .updateTime(position.getUpdateTime())
                .build();
    }
}
