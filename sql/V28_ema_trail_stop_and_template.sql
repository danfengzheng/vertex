-- ============================================
-- V28_ema_trail_stop_and_template.sql
-- 1) stg_strategy 加 EMA_TRAIL 止损 4 字段
-- 2) 新增内置策略模板 DUAL_EMA_TRAIL_TREND (id=1003)
--
-- EMA_TRAIL 止损语义（触发在 StopLossTakeProfitTask 每 10 秒 tick，
-- 但 stopLoss 值由 StrategyEngineService 每根 K 线收盘时更新）：
--
--   LONG  持仓：newSL = min(EMA[fast]  over last N bars) × (1 - buffer%)
--   SHORT 持仓：newSL = max(EMA[fast]  over last N bars) × (1 + buffer%)
--
--   trailing (默认 1)：newSL 只朝有利方向移动（LONG 只升不降，SHORT 只降不升）
--   trailing = 0     ：每根 K 线重算，允许倒退（波段止损）
--
-- 幂等：可重复执行。
-- ============================================

-- 1) stg_strategy 加字段
ALTER TABLE `stg_strategy`
    ADD COLUMN `ema_trail_period`      INT           DEFAULT NULL COMMENT 'EMA 周期（默认 21，NULL=不启用 EMA_TRAIL）',
    ADD COLUMN `ema_trail_lookback`    INT           DEFAULT NULL COMMENT '取最近 N 根 EMA 的极值（默认 20）',
    ADD COLUMN `ema_trail_buffer_pct`  DECIMAL(6,4)  DEFAULT NULL COMMENT 'buffer 百分比（0.5 = 0.5%）',
    ADD COLUMN `ema_trailing_enabled`  TINYINT       DEFAULT 1    COMMENT '1=trailing 只朝有利方向移动；0=每根 K 线重算允许倒退';

-- 2) 内置模板 #3：DUAL_EMA_TRAIL_TREND
--    组合：DualEmaTrend (21/55, price confirm) + 1D EMA hardFilter + EMA_TRAIL 止损 + 固定 % 止损兜底
--    暴露：symbol / exchange / interval / positionRatio / stopLossPct / emaTrailBufferPct / enableDailyFilter / autoTrade
INSERT INTO `strategy_template`
    (id, code, name, description, category, version, status, risk_profile,
     market_fit_json, interval_fit_json, template_json, exposed_params_json,
     schema_version, is_builtin, create_time, update_time)
VALUES
(1003, 'DUAL_EMA_TRAIL_TREND', '双 EMA 趋势跟随 + EMA_TRAIL 止损',
 '经典均线趋势策略，DualEmaTrend 判入场方向（EMA21/EMA55 排列 + close 站上快线），EMA21 极值动态止损（trailing 只朝有利方向）+ 固定 % 止损兜底。\n\n【组件】DualEmaTrend 指标（EMA21/EMA55 + close 位置确认，自带方向判定）；1D EMA[55] 硬过滤（大趋势锁方向，可关）；EMA_TRAIL 动态止损（EMA21 最近 20 根 K 线极值 ± buffer，只朝有利方向移动）；固定 stopLossPct 兜底（急跌兜底，触发在 10 秒内）。\n\n【推荐用法】1h / 4h 有趋势品种。BTCUSDT / ETHUSDT 优先。若 4h 入场，建议保留 1D EMA filter 避免逆势。',
 'TREND', 1, 'PUBLISHED', 'MEDIUM',
 JSON_ARRAY('BTCUSDT','ETHUSDT','SOLUSDT'),
 JSON_ARRAY('1h','4h'),
 JSON_OBJECT(
   'schemaVersion', 1,
   'indicators', JSON_ARRAY(
     JSON_OBJECT(
       'indicatorType', 'DUAL_EMA_TREND',
       'params', JSON_OBJECT('fastPeriod', 21, 'slowPeriod', 55, 'requirePriceConfirm', true),
       'weight', 60
     ),
     JSON_OBJECT(
       'indicatorType', 'EMA',
       'interval', '1D',
       'params', JSON_OBJECT('period', 55),
       'weight', 30,
       'hardFilter', true
     ),
     JSON_OBJECT(
       'indicatorType', 'RSI',
       'params', JSON_OBJECT('period', 14),
       'weight', 20,
       'hardFilter', true,
       'filterConditions', JSON_ARRAY(
         JSON_OBJECT('field','rsi14','op','LT','threshold',75),
         JSON_OBJECT('field','rsi14','op','GT','threshold',25)
       )
     )
   ),
   'stopLossPct',  0.02,
   'takeProfitPct', 0.06,
   'emaTrailPeriod', 21,
   'emaTrailLookback', 20,
   'emaTrailBufferPct', 0.5,
   'emaTrailingEnabled', 1,
   'atrInterval', '15m',
   'positionRatio', 0.2,
   'feeRate', 0.001,
   'autoTrade', 0,
   'minSignalStrength', 60,
   'maxHoldingBars', 300
 ),
 JSON_ARRAY(
   JSON_OBJECT('userKey','symbol','label','交易对','labelEn','Symbol','type','string','required',true,'templatePath','$strategy.symbol'),
   JSON_OBJECT('userKey','exchange','label','交易所','labelEn','Exchange','type','string','required',true,'default','binance','templatePath','$strategy.exchange'),
   JSON_OBJECT('userKey','interval','label','入场周期','labelEn','Interval','type','enum','options',JSON_ARRAY('1h','4h'),'default','1h','templatePath','$strategy.interval'),
   JSON_OBJECT('userKey','positionRatio','label','仓位比例','labelEn','Position Size','type','number','min',0.01,'max',1,'step',0.01,'default',0.2,'templatePath','positionRatio'),
   JSON_OBJECT('userKey','stopLossPct','label','固定止损比例（兜底）','labelEn','Fixed SL %','type','number','min',0.005,'max',0.1,'step',0.001,'default',0.02,'templatePath','stopLossPct'),
   JSON_OBJECT('userKey','takeProfitPct','label','固定止盈比例','labelEn','TP %','type','number','min',0.01,'max',0.2,'step',0.001,'default',0.06,'templatePath','takeProfitPct'),
   JSON_OBJECT('userKey','emaTrailBufferPct','label','EMA_TRAIL 缓冲百分比','labelEn','EMA_TRAIL Buffer %','type','number','min',0.1,'max',3.0,'step',0.1,'default',0.5,'description','LONG 止损=min(EMA21)×(1-buffer%)。数值越大止损越宽松，被扫概率越低。','templatePath','emaTrailBufferPct'),
   JSON_OBJECT('userKey','autoTrade','label','自动交易','labelEn','Auto Trade','type','bool','default',0,'templatePath','autoTrade')
 ),
 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
   description = VALUES(description),
   template_json = VALUES(template_json),
   exposed_params_json = VALUES(exposed_params_json),
   update_time = NOW();
