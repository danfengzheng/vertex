-- ============================================================================
-- V35: ETH-SWAP V5 策略（H1 波段趋势跟踪）
--
-- 血缘：V1 亏损 / V2 M5趋势 -46% / V3-V4 M5反转 (bug版+74% → 修复后-48%)
-- 结论：M5 上的策略在 ETH 上没有 alpha，转向 H1 波段
--
-- 核心思路变更：
--   1. 主周期 M5 → H1（每根 K 线代表 1 小时的价格行为）
--   2. 策略类型：反转 → **趋势跟踪 + 回调入场**
--   3. 交易频率：每天 6-8 单 → 每月 5-15 单
--   4. 单笔目标：0.3% → 3-8%
--   5. R:R 目标 2.5:1+
--
-- 逻辑：大周期 (H4/D1) 定方向 → H1 主周期抓趋势延续 → EMA_TRAIL 保护
--
-- 期待表现（保守估计）：
--   - 胜率 35-45%
--   - 盈亏比 2.5-3.5
--   - 月均 5-15 单
--   - 年化 15-30%（含 fee 和滑点）
--   - 最大回撤 5-10%
-- ============================================================================

SET @now = NOW();
SET @v5_id = 2100000000000000005;
SET @v1_id = 2037222651905372162;

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
    @v5_id,
    'ETH-SWAP-V5-H1-SWING',
    'V5 H1 波段（放弃 M5 转 H1）：D1/H4 大周期定方向 + H1 MACD/EMA 结构趋势跟踪 + RSI 中段过滤 + EMA_TRAIL 波段保护；仓位 15%×2lev，R:R ~3:1',
    'binance', 'ETHUSDT', 'H1',
    -- indicator_configs：
    -- Filters (hardFilter，大周期定盘 + 强度过滤):
    --   1. SUPERTREND[D1] - 大方向：D1 uptrend 只做 BUY，downtrend 只做 SELL
    --   2. SUPERTREND[H4] - 中周期确认（防止 D1 刚翻转时中周期还在反向）
    --   3. ADX[H1] adx ≥ 20 - 至少有些趋势力度（纯 chop 不入场）
    -- Voters (H1 主周期):
    --   4. MACD[H1] w=40 - 主动量指标（金叉/死叉 + histogram 同向）
    --   5. DUAL_EMA_TREND[H1] w=30 - EMA 结构确认（快慢均线排列）
    --   6. RSI[H1] w=30 - 动量中段过滤：
    --        BUY: 50 < rsi < 65（不追高，也不 buy the dip 过早）
    --        SELL: 35 < rsi < 50（对称）
    --   总权重 100，min_signal_strength=70 → 至少 70 权重同向（约 2-3 指标一致）
    '[{"indicatorType":"SUPERTREND","interval":"D1","hardFilter":true,"params":{"period":10,"multiplier":3},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"SUPERTREND","interval":"H4","hardFilter":true,"params":{"period":10,"multiplier":2},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"ADX","interval":"H1","hardFilter":true,"params":{"period":14,"trendThreshold":20},"filterConditions":[{"applyTo":null,"field":"adx","op":"GT","threshold":20.0}]},{"indicatorType":"MACD","interval":"H1","weight":40,"params":{"fast":12,"slow":26,"signal":9},"buyConditions":[{"field":"macd","op":"GT","threshold":0.0},{"field":"histogram","op":"GT","threshold":0.0},{"field":"histogramDelta","op":"GT","threshold":0.0}],"sellConditions":[{"field":"macd","op":"LT","threshold":0.0},{"field":"histogram","op":"LT","threshold":0.0},{"field":"histogramDelta","op":"LT","threshold":0.0}]},{"indicatorType":"DUAL_EMA_TREND","interval":"H1","weight":30,"params":{"fastPeriod":20,"slowPeriod":50},"buyConditions":[{"field":"alignment","op":"GT","threshold":0.0},{"field":"priceOverFast","op":"GT","threshold":0.0}],"sellConditions":[{"field":"alignment","op":"LT","threshold":0.0},{"field":"priceOverFast","op":"LT","threshold":0.0}]},{"indicatorType":"RSI","interval":"H1","weight":30,"params":{"period":14},"buyConditions":[{"field":"rsi14","op":"GT","threshold":50.0},{"field":"rsi14","op":"LT","threshold":65.0}],"sellConditions":[{"field":"rsi14","op":"GT","threshold":35.0},{"field":"rsi14","op":"LT","threshold":50.0}]}]',
    1,                    -- enabled
    1,                    -- auto_trade
    70,                   -- min_signal_strength 70（严格门槛，避免过频）
    'AUTO',
    'PAPER',              -- 先 PAPER 观察
    2023534755928092679,  -- 同 account
    'PERCENT',
    NULL,
    0.15,                 -- position_ratio 0.15（波段少交易，稍大仓位）
    10000.0000000000,
    1.50,                 -- SL 1.5%（H1 波段波动大）
    5.00,                 -- 总 TP 5%
    0.0005,               -- fee_rate 显式
    2,                    -- leverage 2
    'ISOLATED',           -- 隔离风险
    -- ATR stops 关闭
    NULL, NULL, NULL, NULL, NULL, NULL,
    'H4',                 -- atr_interval 用 H4
    NULL,                 -- exit_indicator_configs
    48,                   -- max_holding_bars=48（H1×48=2 天）
    NULL,                 -- trailing_drop_pct
    NULL,                 -- trading_paused_until
    1,                    -- pause_on_stop_loss=1
    NULL,                 -- super_trend_sl_offset_pct（用 EMA_TRAIL 代替）
    0.70,                 -- 反向占比 0.70（更严，波段策略要给趋势时间）
    -- 分段止盈：2 / 3.5 / 5，分别平 40/35/25
    2.00, 0.40,
    3.50, 0.35,
    5.00, 0.25,
    1,                    -- move_stop_to_breakeven_after_stage=1（TP1 后 SL 移保本）
    NULL, NULL,
    CONCAT('{"source_strategy_id":"', @v1_id, '","strategy_type":"h1_swing_trend_following","refactor_note":"V5 abandons M5 (V2-V4 all failed real-market test). Switched to H1 swing with D1/H4 direction filter + trend-following voters."}'),
    -- EMA trail（H1 上 buffer 更大以避免噪音提前止损）
    20,                   -- ema_trail_period（20 根 H1 = ~1 周）
    5,                    -- lookback 5
    0.80,                 -- buffer 0.80%（H1 波动大，需要空间）
    1,                    -- ema_trailing_enabled
    1, @now, 1, @now, 0
);

-- ============================================================================
-- 验证 checklist（回测通过后再上 PAPER，PAPER 通过后再上 LIVE）
-- ============================================================================
-- Step 1: 回测 2025-01-01 ~ 2026-09-12
--   目标：胜率 ≥ 35%，盈亏比 ≥ 2.5，总收益 > 0，回撤 < 15%
--   若不达标：调 min_signal_strength、SL/TP 阈值
--
-- Step 2: 若回测正 EV，切分月度看曲线均匀性
--   - 若某月贡献 >40% 收益 → 过度依赖特定行情，慎重
--
-- Step 3: PAPER 跑 2-4 周
--   - 实盘信号频率应该匹配回测（月 5-15）
--   - PAPER P&L 与同期回测对比，差异 < 30% 可接受
--
-- Step 4: LIVE 试盘
--   - position_ratio 从 0.03 起步
--   - 稳定 1 周升到 0.08
--   - 再稳定升到 0.15
--
-- Step 5: 前置停止旧策略
--   UPDATE stg_strategy SET enabled = 0
--   WHERE id IN (2037222651905372162, 2100000000000000001, 2100000000000000002);
-- ============================================================================

-- ============================================================================
-- 与之前策略的对比
-- ============================================================================
-- 版本 | 类型 | 主周期 | 结果 | 教训
-- V1  | 未知（现有）| M5 | 亏损，pause 中 | -
-- V2  | M5 趋势 | M5 | 回测 -46% (bug 修前) | 追涨杀跌
-- V3  | M5 反转 | M5 | bug 版 +74%, 真实 -48% | 提前止损 bug
-- V4  | M5 反转 | M5 | 同 V3 -48% | 确认 M5 反转不成立
-- V5  | H1 波段 | H1 | 待验证 | 换周期换思路
-- ============================================================================
