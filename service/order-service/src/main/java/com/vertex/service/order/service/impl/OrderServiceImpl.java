package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.trading.IOrderService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.OrderCreateDTO;
import com.vertex.model.dto.trading.OrderQueryDTO;
import com.vertex.model.entity.trading.*;
import com.vertex.model.vo.trading.OrderVO;
import com.vertex.service.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 交易订单服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements IOrderService {

    private final OrderMapper orderMapper;

    @Override
    public Long create(OrderCreateDTO dto) {
        Order order = new Order();
        order.setAccountId(dto.getAccountId());
        order.setExchange(dto.getExchange());
        order.setSymbol(dto.getSymbol());
        order.setSide(dto.getSide());
        order.setOrderType(dto.getOrderType());
        order.setQuantity(dto.getQuantity());
        order.setPrice(dto.getPrice());
        order.setStatus(OrderStatus.PENDING);
        order.setTradeMode(dto.getExecutionMode());

        orderMapper.insert(order);
        return order.getId();
    }

    @Override
    public void cancel(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.SUBMITTED) {
            throw new BizException(GlobalError.TRADE_ORDER_CANNOT_CANCEL);
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderMapper.updateById(order);
    }

    @Override
    public void confirm(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(GlobalError.TRADE_ORDER_CANNOT_CANCEL);
        }
        // 标记为 SUBMITTED，由 TradeExecutionService 实际执行
        order.setStatus(OrderStatus.SUBMITTED);
        orderMapper.updateById(order);
    }

    @Override
    public void reject(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(GlobalError.TRADE_ORDER_CANNOT_CANCEL);
        }
        order.setStatus(OrderStatus.REJECTED);
        orderMapper.updateById(order);
    }

    @Override
    public OrderVO getById(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        return toVO(order);
    }

    @Override
    public PageResult<OrderVO> page(OrderQueryDTO query) {
        Page<Order> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getDeleted, 0)
                .eq(query.getStrategyId() != null, Order::getStrategyId, query.getStrategyId())
                .eq(query.getExchange() != null, Order::getExchange, query.getExchange())
                .eq(query.getSymbol() != null, Order::getSymbol, query.getSymbol())
                .eq(query.getSide() != null, Order::getSide, query.getSide())
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                .eq(query.getTradeMode() != null, Order::getTradeMode, query.getTradeMode())
                .ge(query.getStartTime() != null, Order::getCreateTime,
                        query.getStartTime() != null ?
                                LocalDateTime.ofEpochSecond(query.getStartTime() / 1000, 0, ZoneOffset.ofHours(8)) : null)
                .le(query.getEndTime() != null, Order::getCreateTime,
                        query.getEndTime() != null ?
                                LocalDateTime.ofEpochSecond(query.getEndTime() / 1000, 0, ZoneOffset.ofHours(8)) : null)
                .orderByDesc(Order::getCreateTime);

        Page<Order> result = orderMapper.selectPage(page, wrapper);
        List<OrderVO> records = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), records);
    }

    private OrderVO toVO(Order order) {
        return OrderVO.builder()
                .id(order.getId())
                .strategyId(order.getStrategyId())
                .accountId(order.getAccountId())
                .signalId(order.getSignalId())
                .exchange(order.getExchange())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .filledQuantity(order.getFilledQuantity())
                .filledPrice(order.getFilledPrice())
                .fee(order.getFee())
                .status(order.getStatus())
                .tradeMode(order.getTradeMode())
                .exchangeOrderId(order.getExchangeOrderId())
                .errorMsg(order.getErrorMsg())
                .createTime(order.getCreateTime())
                .updateTime(order.getUpdateTime())
                .build();
    }
}
