package com.vertex.service.strategy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.strategy.ISignalService;
import com.vertex.common.core.context.UserContext;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.SignalCursorDTO;
import com.vertex.model.dto.strategy.SignalQueryDTO;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.vo.strategy.SignalCursorResult;
import com.vertex.model.vo.strategy.SignalVO;
import com.vertex.service.strategy.engine.StrategyEngineService;
import com.vertex.service.strategy.mapper.SignalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 信号服务实现
 */
@Service
@RequiredArgsConstructor
public class SignalServiceImpl implements ISignalService {

    private final SignalMapper signalMapper;
    private final StrategyEngineService engineService;

    @Override
    public PageResult<SignalVO> page(SignalQueryDTO query) {
        boolean isAdmin = UserContext.isAdmin();
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Signal> wrapper = new LambdaQueryWrapper<Signal>()
                .eq(query.getStrategyId() != null, Signal::getStrategyId, query.getStrategyId())
                .eq(StringUtils.hasText(query.getExchange()), Signal::getExchange, query.getExchange())
                .eq(StringUtils.hasText(query.getSymbol()), Signal::getSymbol, query.getSymbol())
                .eq(query.getInterval() != null, Signal::getInterval, query.getInterval())
                .eq(query.getSignalType() != null, Signal::getSignalType, query.getSignalType())
                .ge(query.getStartTime() != null, Signal::getSignalTime, query.getStartTime())
                .le(query.getEndTime() != null, Signal::getSignalTime, query.getEndTime())
                .eq(!isAdmin && currentUserId != null, Signal::getCreateBy, currentUserId)
                .orderByDesc(Signal::getSignalTime);

        // ── 跳过 COUNT + pageSize+1 探测 hasNext ─────────────────────────
        // 信号表数据量大（万到百万级），COUNT(*) 每次分页几百 ms 开销。
        // Page 构造第 3 参 false = 跳过 COUNT；用 pageSize+1 探针判定"还有下一页"。
        int pageSize = query.getPageSize();
        Page<Signal> page = signalMapper.selectPage(
                new Page<>(query.getPageNum(), pageSize + 1, false),
                wrapper);
        List<Signal> raw = page.getRecords();
        boolean hasNext = raw.size() > pageSize;
        List<Signal> trimmed = hasNext ? raw.subList(0, pageSize) : raw;

        List<SignalVO> records = trimmed.stream()
                .map(this::toVO)
                .toList();

        return PageResult.ofCursor(hasNext, records);
    }

    @Override
    public SignalCursorResult<SignalVO> pageByCursor(SignalCursorDTO query) {
        boolean isAdmin = UserContext.isAdmin();
        Long currentUserId = UserContext.getUserId();
        int pageSize = query.getPageSize() == null ? 20 : Math.max(1, Math.min(query.getPageSize(), 200));
        Long cursorTime = query.getCursorTime();
        Long cursorId = query.getCursorId();

        LambdaQueryWrapper<Signal> wrapper = new LambdaQueryWrapper<Signal>()
                .eq(query.getStrategyId() != null, Signal::getStrategyId, query.getStrategyId())
                .eq(StringUtils.hasText(query.getExchange()), Signal::getExchange, query.getExchange())
                .eq(StringUtils.hasText(query.getSymbol()), Signal::getSymbol, query.getSymbol())
                .eq(query.getInterval() != null, Signal::getInterval, query.getInterval())
                .eq(query.getSignalType() != null, Signal::getSignalType, query.getSignalType())
                .ge(query.getStartTime() != null, Signal::getSignalTime, query.getStartTime())
                .le(query.getEndTime() != null, Signal::getSignalTime, query.getEndTime())
                .eq(!isAdmin && currentUserId != null, Signal::getCreateBy, currentUserId);

        // ── 游标严格下界：(signal_time, id) < (cursorTime, cursorId) ─────────
        // 生成 SQL: AND ((signal_time < ?) OR (signal_time = ? AND id < ?))
        if (cursorTime != null && cursorId != null) {
            final long ct = cursorTime;
            final long ci = cursorId;
            wrapper.and(w -> w.lt(Signal::getSignalTime, ct)
                              .or(w2 -> w2.eq(Signal::getSignalTime, ct)
                                          .lt(Signal::getId, ci)));
        }
        wrapper.orderByDesc(Signal::getSignalTime)
               .orderByDesc(Signal::getId)
               .last("LIMIT " + (pageSize + 1));

        List<Signal> raw = signalMapper.selectList(wrapper);
        boolean hasNext = raw.size() > pageSize;
        List<Signal> trimmed = hasNext ? raw.subList(0, pageSize) : raw;

        String nextCursor = null;
        if (hasNext && !trimmed.isEmpty()) {
            Signal last = trimmed.get(trimmed.size() - 1);
            nextCursor = last.getSignalTime() + "_" + last.getId();
        }

        List<SignalVO> records = trimmed.stream().map(this::toVO).toList();
        return SignalCursorResult.<SignalVO>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    @Override
    public SignalVO getById(Long id) {
        Signal signal = signalMapper.selectById(id);
        if (signal == null) {
            throw new BizException(GlobalError.SIGNAL_NOT_FOUND);
        }
        // 数据权限：非管理员只能查看自己的信号
        if (!UserContext.isAdmin()) {
            Long currentUserId = UserContext.getUserId();
            if (currentUserId != null && !currentUserId.equals(signal.getCreateBy())) {
                throw new BizException(GlobalError.SIGNAL_NOT_FOUND);
            }
        }
        return toVO(signal);
    }

    @Override
    public void triggerAnalysis(Long strategyId) {
        engineService.runStrategyNow(strategyId);
    }

    private SignalVO toVO(Signal signal) {
        SignalVO vo = new SignalVO();
        BeanUtil.copyProperties(signal, vo, "indicators");
        // JSON 字符串 → Map 反序列化
        if (signal.getIndicators() != null) {
            vo.setIndicators(JSON.parseObject(signal.getIndicators(),
                    new TypeReference<Map<String, Object>>() {}));
        }
        return vo;
    }
}
