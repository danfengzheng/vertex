-- ============================================================================
-- V36: ETH-SWAP V5.1 策略（H1 波段 + D1 ADX regime filter）
--
-- 血缘：V5 (id=2100000000000000005) 分段测试印证 regime dependency：
--   - 2025 全年 (趋势期): +45.50%, 胜率 64.58%, 144 单
--   - 2025-10~2026-09 (震荡期): -40.67%, 胜率 49.06%, 159 单
--   - 21 月合计（fee 0.0005）: -13.68%
--
-- 结论：V5 在趋势期有真实 alpha，但在震荡期被 whipsaw 亏损。
--       加"日线 ADX regime filter"只在趋势期入场，理论上能过滤 60% 坏行情。
--
-- 唯一变化 vs V5：
--   ★ 新增 ADX[D1] adx > 25 作为 hardFilter（第 4 个过滤器）
--   其他所有参数与 V5 完全一致
--
-- 期待表现：
--   - 交易数：303 → 100-150（减 50%）
--   - 胜率：56% → 60%+（过滤震荡期）
--   - 总收益：-13.68% → +10~+25%（21 月）
--   - 最大回撤：46% → 15-20%
-- ============================================================================

SET @now = NOW();
SET @v51_id = 2100000000000000006;
SET @v5_id  = 2100000000000000005;
SET @v1_id  = 2037222651905372162;

INSERT INTO stg_strategy (
    id, name, description,
    exchange, symbol, `interval`,
    indicator_configs,
    enabled, auto_trade, min_signal_strength,
    trade_mode, execution_mode, account_id,
    position_sizing, trade_quantity, position_ratio,
    initial_capital, stop_loss_pct, take_profit_pct, fee_rate,
    leverage, margin_type,
    atr_stop_multiplier, atr_take_profit_multiplier,
    initial_stop_multiplier, breakeven_activation_multiplier,
    trailing_activation_multiplier, trailing_distance_multiplier,
    atr_interval,
    exit_indicator_configs, max_holding_bars, trailing_drop_pct,
    trading_paused_until, pause_on_stop_loss,
    super_trend_sl_offset_pct, exit_on_opposite_vote_ratio,
    take_profit_pct1, take_profit_size1,
    take_profit_pct2, take_profit_size2,
    take_profit_pct3, take_profit_size3,
    move_stop_to_breakeven_after_stage,
    source_template_id, source_template_version, template_user_params_json,
    ema_trail_period, ema_trail_lookback, ema_trail_buffer_pct, ema_trailing_enabled,
    create_by, create_time, update_by, update_time, deleted
) VALUES (
    @v51_id,
    'ETH-SWAP-V5.1-REGIME',
    'V5.1 = V5 + D1 ADX regime filter：只在日线 ADX>25（趋势明确）时入场，避开震荡期。分段测试显示 V5 在趋势期 +45.50%/9月，震荡期 -40.67%/11.5月 → 加 filter 后期望正 EV。',
    'binance', 'ETHUSDT', 'H1',
    -- indicator_configs：V5 基础 + 新增 ADX[D1] filter
    -- Filters (hardFilter):
    --   1. ★ NEW: ADX[D1] adx > 25 - 日线趋势强度过滤（regime filter）
    --   2. SUPERTREND[D1] - 大方向定盘
    --   3. SUPERTREND[H4] - 中周期确认
    --   4. ADX[H1] adx ≥ 20 - H1 趋势力度
    -- Voters (H1 主周期): 与 V5 完全一致
    --   5. MACD[H1] w=40 - 主动量
    --   6. DUAL_EMA_TREND[H1] w=30 - EMA 结构
    --   7. RSI[H1] w=30 - 中段过滤
    '[{"indicatorType":"ADX","interval":"D1","hardFilter":true,"params":{"period":14},"filterConditions":[{"applyTo":null,"field":"adx","op":"GT","threshold":25.0}]},{"indicatorType":"SUPERTREND","interval":"D1","hardFilter":true,"params":{"period":10,"multiplier":3},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"SUPERTREND","interval":"H4","hardFilter":true,"params":{"period":10,"multiplier":2},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"ADX","interval":"H1","hardFilter":true,"params":{"period":14,"trendThreshold":20},"filterConditions":[{"applyTo":null,"field":"adx","op":"GT","threshold":20.0}]},{"indicatorType":"MACD","interval":"H1","weight":40,"params":{"fast":12,"slow":26,"signal":9},"buyConditions":[{"field":"macd","op":"GT","threshold":0.0},{"field":"histogram","op":"GT","threshold":0.0},{"field":"histogramDelta","op":"GT","threshold":0.0}],"sellConditions":[{"field":"macd","op":"LT","threshold":0.0},{"field":"histogram","op":"LT","threshold":0.0},{"field":"histogramDelta","op":"LT","threshold":0.0}]},{"indicatorType":"DUAL_EMA_TREND","interval":"H1","weight":30,"params":{"fastPeriod":20,"slowPeriod":50},"buyConditions":[{"field":"alignment","op":"GT","threshold":0.0},{"field":"priceOverFast","op":"GT","threshold":0.0}],"sellConditions":[{"field":"alignment","op":"LT","threshold":0.0},{"field":"priceOverFast","op":"LT","threshold":0.0}]},{"indicatorType":"RSI","interval":"H1","weight":30,"params":{"period":14},"buyConditions":[{"field":"rsi14","op":"GT","threshold":50.0},{"field":"rsi14","op":"LT","threshold":65.0}],"sellConditions":[{"field":"rsi14","op":"GT","threshold":35.0},{"field":"rsi14","op":"LT","threshold":50.0}]}]',
    1,                    -- enabled
    1,                    -- auto_trade
    70,                   -- min_signal_strength 70（与 V5 一致）
    'AUTO',
    'PAPER',              -- 先 PAPER 验证
    2023534755928092679,  -- 同 account
    'PERCENT',
    NULL,
    0.15,                 -- position_ratio 0.15
    10000.0000000000,
    1.50,                 -- SL 1.5%
    5.00,                 -- 总 TP 5%
    0.0005,               -- fee_rate（保守假设 taker）
    2,                    -- leverage 2
    'ISOLATED',
    NULL, NULL, NULL, NULL, NULL, NULL,
    'H4',                 -- atr_interval
    NULL,                 -- exit_indicator_configs
    48,                   -- max_holding_bars 48 H1
    NULL,
    NULL,
    1,                    -- pause_on_stop_loss=1
    NULL,
    0.70,                 -- 反向占比 0.70
    2.00, 0.40,
    3.50, 0.35,
    5.00, 0.25,
    1,                    -- move_stop_to_breakeven_after_stage
    NULL, NULL,
    CONCAT('{"source_strategy_id":"', @v5_id, '","source_ancestor":"', @v1_id, '","strategy_type":"h1_swing_trend_following_with_regime_filter","refactor_note":"V5.1 adds D1 ADX>25 filter to skip choppy periods where V5 loses -40%. V5 trend-period stats: +45.5%/9mo trending, -40.7%/11.5mo choppy. Regime filter expected to keep trending gains + skip choppy losses."}'),
    20,                   -- ema_trail_period
    5,                    -- lookback
    0.80,                 -- buffer 0.80%
    1,                    -- ema_trailing_enabled
    1, @now, 1, @now, 0
);

-- ============================================================================
-- 关键验证 checklist
-- ============================================================================
-- Step 1: 全期回测 2025-01-01 ~ 2026-09-12 (fee 0.0005)
--   ✓ 目标：总收益 > +10%
--   ✓ 目标：交易数 100-150（少于 V5 的 303，因 regime filter）
--   ✓ 目标：胜率 > 60%
--   ✓ 目标：最大回撤 < 20%
--
-- Step 2: 分段回测对比 V5
--   2025-01 ~ 2025-09（趋势期）：V5 +45.5% → V5.1 应仍 +30~40%
--       （filter 会砍掉部分交易，但保留大方向单）
--   2025-10 ~ 2026-09（震荡期）：V5 -40.67% → V5.1 应 <-10%
--       （filter 应大量过滤震荡期交易）
--
-- Step 3: 关键 SQL 查看 filter 效果
--   SELECT COUNT(*) FROM signal WHERE strategy_id = <v51> AND signal_type != 'NEUTRAL';
--   对比 V5 同期，若 V5.1 = V5 × 40-60% → filter 生效正常
--
-- Step 4: 若 Step 1 达标，PAPER 1-2 周实盘信号
-- Step 5: LIVE 起步 0.05 仓位
--
-- Step 6: 若 V5.1 Step 1 不达标（还是负收益）
--   → 说明 D1 ADX 不足以区分好坏 regime
--   → 尝试更严格 filter：ADX > 30 或加 W1 SUPERTREND 一致性
--   → 或彻底接受"策略在 ETH 无稳定 alpha"，考虑手动 or 换赛道
-- ============================================================================

-- ============================================================================
-- 停用之前失败的策略（可选，观察对比后再执行）
-- ============================================================================
-- UPDATE stg_strategy SET enabled = 0 WHERE id IN (
--     2037222651905372162,   -- V1
--     2100000000000000001,   -- V2
--     2100000000000000002,   -- V3
--     2100000000000000005    -- V5（保留作对照）
-- );
-- （建议 V5 保留 enabled=1 但 auto_trade=0，只出信号不下单，方便对比）
