-- ============================================================================
-- V34: 新建 ETH-SWAP V3 策略（均值回归版）
--
-- 血缘：source_id = 2037222651905372162 (V1) / V2 回测 -46% 已废弃
-- 核心思路变更：**从趋势跟踪 → 均值回归**
--
-- V2 失败根因（回测数据）：
--   - 胜率 28.66% × 盈亏比 1.43 → EV = -0.30R 每笔
--   - 全部投票指标是"趋势追随型"（MACD/DUAL_EMA/VOL_CONFIRM）→ M5 尺度追高追低
--   - 高周期 filter（H1 SUPERTREND + M30 ADX）滞后 → 在趋势末端进场
--
-- V3 反过来做：
--   - Voters 全部换成"反转型"指标（KDJ / RSI / WR / STOCH_RSI）→ 超卖买、超买卖
--   - Filter 保留 SUPERTREND[H1]（大方向对齐，只做顺趋势回调）
--   - Filter 加 ADX[M30] < 25（**反向使用**，只在震荡市入场，趋势市不做）
--
-- 关键参数调整：
--   - SL 4% → 0.8%（震荡策略小损失）
--   - TP 8% → 分段 1.2 / 2.0 / 3.0%（匹配震荡幅度）
--   - position_ratio 0.25 → 0.10（频繁交易需小仓位）
--   - min_signal_strength 65 → 55（4 反转指标同向不易，降门槛）
--   - pause_on_stop_loss 1 → 0（震荡策略要连续跑，不因单笔 SL 停）
--
-- 部署：先 PAPER 回测 2025 全年 → 若胜率 > 55% 且总收益正 → PAPER 跑 3-7 天 → LIVE
-- ============================================================================

SET @now = NOW();
SET @v3_id = 2100000000000000002;
SET @v1_id = 2037222651905372162;

INSERT INTO stg_strategy (
    id,
    name,
    description,
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
    @v3_id,
    'ETH-SWAP-V3-REVERSION',
    'V3 均值回归版：4 反转指标投票（KDJ/RSI/WR/STOCH_RSI），SUPERTREND[H1] 顺趋势 + ADX[M30]<25 只做震荡；SL 0.8% / 分段 TP 1.2-3%',
    'binance', 'ETHUSDT', 'M5',
    -- Filters (hardFilter):
    --   1. SUPERTREND[H1] 顺大趋势方向（H1 涨 → 只做 BUY 回调，H1 跌 → 只做 SELL 反弹）
    --   2. ADX[M30] < 25 反向使用（只在弱趋势/震荡里入场，避免趋势市被反复止损）
    -- Voters (全部反转型，M5 主周期):
    --   3. KDJ[M5] w=30 - K<20 且 K>D 超卖金叉 BUY；K>80 且 K<D 超买死叉 SELL
    --   4. RSI[M5] w=25 - rsi<30 超卖 BUY；rsi>70 超买 SELL
    --   5. WR[M5]  w=25 - wr<-80 超卖 BUY；wr>-20 超买 SELL
    --   6. STOCH_RSI[M5] w=20 - K<20 金叉 BUY；K>80 死叉 SELL
    --   总 100，min_signal_strength=55 → 至少 55 权重同向（约 2-3 指标一致）
    '[{"indicatorType":"SUPERTREND","interval":"H1","hardFilter":true,"params":{"period":10,"multiplier":2},"filterConditions":[{"applyTo":"BUY","field":"trend","op":"GT","threshold":0.0},{"applyTo":"SELL","field":"trend","op":"LT","threshold":0.0}]},{"indicatorType":"ADX","interval":"M30","hardFilter":true,"params":{"period":14,"trendThreshold":25},"filterConditions":[{"applyTo":null,"field":"adx","op":"LT","threshold":25.0}]},{"indicatorType":"KDJ","interval":"M5","weight":30,"params":{"period":9,"kPeriod":3,"dPeriod":3},"buyConditions":[{"field":"k","op":"LT","threshold":20.0},{"field":"k","op":"GT","threshold":0.0}],"sellConditions":[{"field":"k","op":"GT","threshold":80.0},{"field":"k","op":"LT","threshold":100.0}]},{"indicatorType":"RSI","interval":"M5","weight":25,"params":{"period":14},"buyConditions":[{"field":"rsi14","op":"LT","threshold":30.0}],"sellConditions":[{"field":"rsi14","op":"GT","threshold":70.0}]},{"indicatorType":"WR","interval":"M5","weight":25,"params":{"period":14},"buyConditions":[{"field":"wr14","op":"LT","threshold":-80.0}],"sellConditions":[{"field":"wr14","op":"GT","threshold":-20.0}]},{"indicatorType":"STOCH_RSI","interval":"M5","weight":20,"params":{"rsiPeriod":14,"stochPeriod":14,"kSmooth":3,"dSmooth":3},"buyConditions":[{"field":"stochRsiK","op":"LT","threshold":20.0}],"sellConditions":[{"field":"stochRsiK","op":"GT","threshold":80.0}]}]',
    1,                    -- enabled
    1,                    -- auto_trade
    55,                   -- min_signal_strength（反转策略降门槛，需要约 2-3 指标同向）
    'AUTO',
    'PAPER',              -- ⚠️ 先 PAPER，回测通过再 LIVE
    2023534755928092679,  -- 同 V1 account
    'PERCENT',
    NULL,
    0.10,                 -- 仓位从 V2 0.25 → 0.10（震荡策略频繁交易需小仓位）
    10000.0000000000,
    0.80,                 -- SL 4% → 0.8%（震荡策略小损失）
    3.00,                 -- 总 TP 3%（配合分段止盈使用）
    0.0005,               -- fee_rate 显式
    2,                    -- leverage 保持 2
    'ISOLATED',           -- 隔离风险
    -- ATR-based stops 关闭（V3 用固定 pct）
    NULL, NULL, NULL, NULL, NULL, NULL,
    'H1',                 -- atr_interval 保持
    NULL,                 -- exit_indicator_configs
    24,                   -- max_holding_bars=24（M5×24=2h，反转策略持仓不宜过长）
    NULL,                 -- trailing_drop_pct
    NULL,                 -- trading_paused_until
    0,                    -- ⚠️ pause_on_stop_loss=0（震荡策略要连续跑，单笔 SL 不停）
    NULL,                 -- super_trend_sl_offset_pct
    0.65,                 -- exit_on_opposite_vote_ratio 65%（反向占比高即离场）
    -- 分段止盈：1.2 / 2.0 / 3.0（匹配震荡幅度，早出高胜率）
    1.20, 0.40,
    2.00, 0.35,
    3.00, 0.25,
    1,                    -- move_stop_to_breakeven_after_stage=1（第 1 段后 SL 移保本）
    NULL, NULL,
    CONCAT('{"source_strategy_id":"', @v1_id, '","strategy_type":"mean_reversion","refactor_note":"V3 abandons trend-following (V2 backtest -46%). Uses KDJ/RSI/WR/STOCH_RSI reversal + ADX<25 filter for chop-only entries."}'),
    -- EMA trail（辅助保护）
    20, 3, 0.20, 1,
    1, @now, 1, @now, 0
);

-- ============================================================================
-- V3 回测验证 checklist（关键！先跑再决定要不要 PAPER）
-- ============================================================================
-- 1. 回测 2025-01-01 ~ 2026-09-12（同 V2 期间）
--    目标：胜率 ≥ 55%（反转策略应远高于 V2 的 28%）
--    目标：盈亏比 ≥ 1.1（分段 TP 会拉低但胜率补偿）
--    目标：总收益率 > 0，回撤 < 20%
--    目标：夏普 > 0.5
--
-- 2. 分季度看曲线：
--    - 震荡期（横盘）→ 应表现最好（策略优势区）
--    - 单边趋势期 → 应该"少交易"（因 ADX>25 filter 挡住）
--    如果趋势期照样频繁交易且亏 → filter 未生效，需要看 ADX suggestion 逻辑
--
-- 3. 交易次数：157（V2）→ 预计 V3 约 200-400（因门槛降低 + ADX filter 挡住趋势市）
--    若 < 100 → filter 过严；若 > 500 → 过松
--
-- 4. 若回测 EV 仍为负 → 均值回归也不适合当前市况，考虑：
--    a. 换 symbol（BTC 比 ETH 震荡属性更强？）
--    b. 换周期（M15 主周期，减少 M5 噪音）
--    c. 放弃 M5 高频，改为 H1/H4 波段策略

-- ============================================================================
-- 与 V1/V2 的关系
-- ============================================================================
-- V1 (id=2037222651905372162): paused，保留作历史对比
-- V2 (id=2100000000000000001): PAPER 回测 -46% 已证伪，建议 disable
--    UPDATE stg_strategy SET enabled = 0 WHERE id = 2100000000000000001;
-- V3 (此策略): PAPER 开始，回测通过后手动切 LIVE
