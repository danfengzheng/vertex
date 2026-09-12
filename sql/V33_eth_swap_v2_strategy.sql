-- ============================================================================
-- V33: 新建 ETH-SWAP V2 策略（重构版）
--
-- 血缘：source_id = 2037222651905372162 (ETH-SWAP V1，当前 paused，作对照 baseline)
-- 目的：修正 V1 里 ATR 死票 / VOL_CONFIRM 噪音 / 仓位过重 / R:R 过低 / 出场缺失 等 15 项问题
-- 部署：先 PAPER 3-7 天观察 → 达标后切 LIVE，同时 disabled V1
--
-- ⚠️ 上 LIVE 前 checklist：
--   1. UPDATE strategy SET enabled = 0 WHERE id = 2037222651905372162;  (关掉 V1)
--   2. UPDATE strategy SET execution_mode = 'LIVE', position_ratio = 0.10 WHERE id = <此 V2 id>;  (从 10% 起步)
--   3. 观察 3 天后再逐步升到 0.25
-- ============================================================================

-- 记录当前时间基准
SET @now = NOW();
SET @v2_id = 2100000000000000001;   -- 手动分配一个 snowflake-like id（不影响业务，仅避免冲突）
SET @v1_id = 2037222651905372162;

INSERT INTO stg_strategy (
    id,
    name,
    description,
    exchange,
    symbol,
    `interval`,
    indicator_configs,
    enabled,
    auto_trade,
    min_signal_strength,
    trade_mode,
    execution_mode,
    account_id,
    position_sizing,
    trade_quantity,
    position_ratio,
    initial_capital,
    stop_loss_pct,
    take_profit_pct,
    fee_rate,
    leverage,
    margin_type,
    -- ATR-based stops 保持 NULL（V2 用固定 pct + EMA trail + 分段 TP）
    atr_stop_multiplier,
    atr_take_profit_multiplier,
    initial_stop_multiplier,
    breakeven_activation_multiplier,
    trailing_activation_multiplier,
    trailing_distance_multiplier,
    atr_interval,
    -- 出场配置
    exit_indicator_configs,
    max_holding_bars,
    trailing_drop_pct,
    trading_paused_until,
    pause_on_stop_loss,
    super_trend_sl_offset_pct,
    exit_on_opposite_vote_ratio,
    -- 分段止盈
    take_profit_pct1, take_profit_size1,
    take_profit_pct2, take_profit_size2,
    take_profit_pct3, take_profit_size3,
    move_stop_to_breakeven_after_stage,
    -- 血缘
    source_template_id,
    source_template_version,
    template_user_params_json,
    -- EMA trail
    ema_trail_period,
    ema_trail_lookback,
    ema_trail_buffer_pct,
    ema_trailing_enabled,
    -- 审计
    create_by, create_time, update_by, update_time, deleted
) VALUES (
    @v2_id,
    'ETH-SWAP-V2-MACD',
    'V2 重构版（源自 ETH-SWAP id=2037222651905372162）：MACD+DUAL_EMA 主投票 + ATR 有条件投票 + 双向 RSI filter + 3 段 TP + EMA trail；仓位 25% × 2lev，R:R 2:1',
    'binance',
    'ETHUSDT',
    'M5',
    -- indicator_configs：核心重构
    -- Filters (hardFilter):
    --   1. SUPERTREND[H1] - 主趋势方向对齐
    --   2. ADX[M30] trendThreshold=25 - 强度过滤（弱趋势不入场）
    --   3. RSI[M15] - 双向对称：BUY要求 rsi<72，SELL要求 rsi>28
    -- Voters:
    --   4. MACD[M5] w=35 - M5 主周期动量（macdHist 与 macd 同向）
    --   5. DUAL_EMA_TREND[M15] w=30 - 结构性趋势
    --   6. VOL_CONFIRM[M5] w=25 - 成交量确认（保留但降权，走内部 suggestion）
    --   7. ATR[M15] w=10 - 只在 atrPercent>0.35（波动足够）时投票，避免死票
    --   总权重 100，min_signal_strength=65 → 至少 65 权重同向才触发
    '[{"indicatorType":"SUPERTREND","interval":"H1","hardFilter":true,"params":{"period":10,"multiplier":2},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"ADX","interval":"M30","hardFilter":true,"params":{"period":14,"trendThreshold":25}},{"indicatorType":"RSI","interval":"M15","hardFilter":true,"params":{"period":14},"filterConditions":[{"applyTo":"BUY","field":"rsi14","op":"LT","threshold":72.0},{"applyTo":"SELL","field":"rsi14","op":"GT","threshold":28.0}]},{"indicatorType":"MACD","interval":"M5","weight":35,"params":{"fast":12,"slow":26,"signal":9},"buyConditions":[{"field":"macdHist","op":"GT","threshold":0.0},{"field":"macd","op":"GT","threshold":0.0}],"sellConditions":[{"field":"macdHist","op":"LT","threshold":0.0},{"field":"macd","op":"LT","threshold":0.0}]},{"indicatorType":"DUAL_EMA_TREND","interval":"M15","weight":30,"params":{"fastPeriod":12,"slowPeriod":26},"buyConditions":[{"field":"trend","op":"GT","threshold":0.0}],"sellConditions":[{"field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"VOL_CONFIRM","interval":"M5","weight":25,"params":{"period":20,"volMultiplier":1.6}},{"indicatorType":"ATR","interval":"M15","weight":10,"params":{"period":14},"buyConditions":[{"field":"atrPercent","op":"GT","threshold":0.35}],"sellConditions":[{"field":"atrPercent","op":"GT","threshold":0.35}]}]',
    1,                    -- enabled
    1,                    -- auto_trade
    65,                   -- min_signal_strength（60→65，配合新权重）
    'AUTO',
    'PAPER',              -- ⚠️ 先 PAPER！稳定后再手动改 LIVE
    2023534755928092679,  -- 同 V1 account
    'PERCENT',
    NULL,
    0.25,                 -- position_ratio 0.98 → 0.25（关键）
    10000.0000000000,
    4.00,                 -- SL 保持
    8.00,                 -- TP 4.20 → 8.00（R:R 2:1）
    0.0005,               -- fee_rate 显式设定
    2,                    -- leverage 保持
    'ISOLATED',           -- CROSS → ISOLATED（隔离风险）
    -- ATR stops 全 NULL（V2 用固定 pct 系列）
    NULL, NULL, NULL, NULL, NULL, NULL,
    'H1',                 -- atr_interval 保持
    NULL,                 -- exit_indicator_configs（用 EMA trail + 反向占比即可）
    NULL,                 -- max_holding_bars
    NULL,                 -- trailing_drop_pct（用 EMA trail 代替）
    NULL,                 -- trading_paused_until（新策略无 pause 状态）
    1,                    -- pause_on_stop_loss（保持）
    NULL,                 -- super_trend_sl_offset_pct
    0.60,                 -- exit_on_opposite_vote_ratio（V24 反向占比出场，60%）
    -- 分段止盈：2.5 / 5.0 / 8.0，分别平 40/35/25
    2.50, 0.40,
    5.00, 0.35,
    8.00, 0.25,
    1,                    -- move_stop_to_breakeven_after_stage=1（第 1 段后 SL 移保本）
    NULL,                 -- source_template_id（未走 template 路径）
    NULL,                 -- source_template_version
    CONCAT('{"source_strategy_id":"', @v1_id, '","source_strategy_name":"ETH-SWAP","refactor_note":"V2 fixes: ATR-dead-vote, VOL-noise, R:R, position size, exit"}'),
    -- EMA trail（V1 里 enabled=1 但参数全 NULL，V2 补齐）
    20,                   -- ema_trail_period
    3,                    -- ema_trail_lookback
    0.30,                 -- ema_trail_buffer_pct (0.3%)
    1,                    -- ema_trailing_enabled
    1, @now, 1, @now, 0
);

-- ============================================================================
-- 部署后监控建议（PAPER 期间每天检查）
-- ============================================================================
-- 1. Signal 频率：日均 3-15 个合理，<3 说明过严，>20 说明过松
--    SELECT DATE(FROM_UNIXTIME(signal_time/1000)) d, signal_type, COUNT(*)
--    FROM signal WHERE strategy_id = @v2_id GROUP BY d, signal_type ORDER BY d DESC;
--
-- 2. voteBreakdown 分布：看 buy_count/sell_count/neutral_count 有无异常倾斜
--
-- 3. PAPER 累计 P&L 走势：若 3 天正 EV → 可以考虑切 LIVE
--    SELECT SUM(profit_loss) FROM trade WHERE strategy_id = @v2_id;
--
-- 4. 触发过 SL 的次数：>3 天内 2 次 → 复盘触发时机，可能 SL 4% 太紧
