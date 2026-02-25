package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.trading.IPnlAnalysisService;
import com.vertex.model.dto.trading.PnlAnalysisQueryDTO;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.Position;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.model.vo.trading.DailyPnlVO;
import com.vertex.model.vo.trading.PnlAnalysisVO;
import com.vertex.model.vo.trading.StrategyPnlItem;
import com.vertex.model.vo.trading.SymbolPnlItem;
import com.vertex.service.order.mapper.PositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 已平仓盈亏分析服务实现
 * <p>
 * 仅统计 status=CLOSED 的持仓，对 realizedPnl 按总/虚拟/实盘/策略/交易对维度聚合。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PnlAnalysisServiceImpl implements IPnlAnalysisService {

    private final PositionMapper positionMapper;

    @Override
    public PnlAnalysisVO getPnlAnalysis(PnlAnalysisQueryDTO query) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, PositionStatus.CLOSED)
                .eq(Position::getDeleted, 0)
                .eq(query.getTradeMode() != null, Position::getTradeMode, query.getTradeMode())
                .eq(query.getStrategyId() != null, Position::getStrategyId, query.getStrategyId())
                .eq(query.getSymbol() != null, Position::getSymbol, query.getSymbol())
                .eq(query.getAccountId() != null, Position::getAccountId, query.getAccountId())
                .ge(query.getClosedAtStart() != null, Position::getClosedAt, query.getClosedAtStart())
                .le(query.getClosedAtEnd() != null, Position::getClosedAt, query.getClosedAtEnd());
        List<Position> closed = positionMapper.selectList(wrapper);

        BigDecimal totalPnl = sumRealizedPnl(closed);
        BigDecimal paperPnl = sumRealizedPnl(closed.stream()
                .filter(p -> p.getTradeMode() == ExecutionMode.PAPER)
                .collect(Collectors.toList()));
        BigDecimal livePnl = sumRealizedPnl(closed.stream()
                .filter(p -> p.getTradeMode() == ExecutionMode.LIVE)
                .collect(Collectors.toList()));

        List<StrategyPnlItem> byStrategy = aggregateByStrategy(closed);
        List<SymbolPnlItem> bySymbol = aggregateBySymbol(closed);

        return PnlAnalysisVO.builder()
                .totalPnl(totalPnl != null ? totalPnl : BigDecimal.ZERO)
                .paperPnl(paperPnl != null ? paperPnl : BigDecimal.ZERO)
                .livePnl(livePnl != null ? livePnl : BigDecimal.ZERO)
                .byStrategy(byStrategy)
                .bySymbol(bySymbol)
                .build();
    }

    private static BigDecimal sumRealizedPnl(List<Position> positions) {
        return positions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<StrategyPnlItem> aggregateByStrategy(List<Position> closed) {
        Map<Long, List<Position>> byId = closed.stream().collect(Collectors.groupingBy(Position::getStrategyId));
        List<StrategyPnlItem> result = new ArrayList<>();
        byId.forEach((strategyId, list) -> {
            BigDecimal pnl = sumRealizedPnl(list);
            result.add(StrategyPnlItem.builder()
                    .strategyId(strategyId)
                    .strategyName(null)
                    .closedCount(list.size())
                    .realizedPnl(pnl)
                    .build());
        });
        result.sort((a, b) -> (b.getRealizedPnl() != null ? b.getRealizedPnl() : BigDecimal.ZERO)
                .compareTo(a.getRealizedPnl() != null ? a.getRealizedPnl() : BigDecimal.ZERO));
        return result;
    }

    private static List<SymbolPnlItem> aggregateBySymbol(List<Position> closed) {
        Map<String, List<Position>> bySymbol = closed.stream().collect(Collectors.groupingBy(Position::getSymbol));
        List<SymbolPnlItem> result = new ArrayList<>();
        bySymbol.forEach((symbol, list) -> {
            BigDecimal pnl = sumRealizedPnl(list);
            result.add(SymbolPnlItem.builder()
                    .symbol(symbol)
                    .closedCount(list.size())
                    .realizedPnl(pnl)
                    .build());
        });
        result.sort((a, b) -> (b.getRealizedPnl() != null ? b.getRealizedPnl() : BigDecimal.ZERO)
                .compareTo(a.getRealizedPnl() != null ? a.getRealizedPnl() : BigDecimal.ZERO));
        return result;
    }

    @Override
    public List<DailyPnlVO> getDailyPnl(PnlAnalysisQueryDTO query) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, PositionStatus.CLOSED)
                .eq(Position::getDeleted, 0)
                .eq(query.getTradeMode() != null, Position::getTradeMode, query.getTradeMode())
                .eq(query.getStrategyId() != null, Position::getStrategyId, query.getStrategyId())
                .eq(query.getSymbol() != null, Position::getSymbol, query.getSymbol())
                .eq(query.getAccountId() != null, Position::getAccountId, query.getAccountId())
                .ge(query.getClosedAtStart() != null, Position::getClosedAt, query.getClosedAtStart())
                .le(query.getClosedAtEnd() != null, Position::getClosedAt, query.getClosedAtEnd());
        List<Position> closed = positionMapper.selectList(wrapper);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 用 TreeMap 按日期字符串自然升序排列
        TreeMap<String, BigDecimal> dailyMap = new TreeMap<>();
        for (Position p : closed) {
            if (p.getClosedAt() == null) continue;
            String date = p.getClosedAt().toLocalDate().format(fmt);
            BigDecimal pnl = p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO;
            dailyMap.merge(date, pnl, BigDecimal::add);
        }

        List<DailyPnlVO> result = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : dailyMap.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            result.add(DailyPnlVO.builder()
                    .date(entry.getKey())
                    .dailyPnl(entry.getValue())
                    .cumulativePnl(cumulative)
                    .build());
        }
        return result;
    }
}
