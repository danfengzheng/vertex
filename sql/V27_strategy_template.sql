-- ============================================
-- V27_strategy_template.sql
-- 策略模板层：
--   strategy_template          —— 模板定义（骨架 JSON + 暴露参数规格 + 元数据）
--   strategy_template_history  —— 每次发布快照（回滚 + 审计）
--   stg_strategy 追加 3 列     —— 记录策略源自哪个模板 + 用户当时填的参数
--
-- 模板体系：
--   * template_json      —— 完整策略结构（indicators / stopLoss / 参数）
--   * exposed_params_json—— 暴露给普通用户的 3-8 个参数规格（label/type/min/max/default/templatePath）
--   * schemaVersion      —— template_json 结构版本（用于后续 schema 演进迁移）
--   * status             —— DRAFT / PUBLISHED / DEPRECATED
--   * is_builtin=1       —— 官方内置模板，UI 不允许直接编辑（可 fork）
--
-- 幂等：可重复执行。
-- ============================================

-- 1) 模板主表
CREATE TABLE IF NOT EXISTS `strategy_template` (
    `id`                    BIGINT      NOT NULL PRIMARY KEY,
    `code`                  VARCHAR(64) NOT NULL UNIQUE COMMENT '唯一标识（英文，如 DUAL_EMA_PULLBACK）',
    `name`                  VARCHAR(128) NOT NULL,
    `description`           TEXT,
    `category`              VARCHAR(32) DEFAULT 'CUSTOM' COMMENT 'TREND / RANGE / BREAKOUT / DCA / CUSTOM',
    `version`               INT         NOT NULL DEFAULT 1,
    `status`                VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / DEPRECATED',
    `risk_profile`          VARCHAR(16) DEFAULT 'MEDIUM' COMMENT 'LOW / MEDIUM / HIGH',
    `market_fit_json`       JSON        DEFAULT NULL COMMENT '建议品种数组 ["BTCUSDT","ETHUSDT"]',
    `interval_fit_json`     JSON        DEFAULT NULL COMMENT '建议入场周期数组 ["5m","15m","1h"]',
    `template_json`         JSON        NOT NULL COMMENT '完整策略骨架（indicators/stopLoss/等）',
    `exposed_params_json`   JSON        NOT NULL COMMENT '暴露参数规格',
    `min_engine_version`    VARCHAR(16) DEFAULT NULL COMMENT '需要的最低引擎版本，可选',
    `schema_version`        INT         NOT NULL DEFAULT 1 COMMENT 'template_json 结构版本',
    `is_builtin`            TINYINT     NOT NULL DEFAULT 0 COMMENT '1=官方内置（UI 只读，可 fork）；0=用户自建',
    `create_by`             BIGINT,
    `create_time`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`             BIGINT,
    `update_time`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT     NOT NULL DEFAULT 0,
    KEY `idx_status_category` (`status`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='策略模板定义';

-- 2) 模板发布历史（快照）
CREATE TABLE IF NOT EXISTS `strategy_template_history` (
    `id`                    BIGINT      NOT NULL PRIMARY KEY,
    `template_id`           BIGINT      NOT NULL,
    `version`               INT         NOT NULL,
    `template_json_snapshot` JSON       NOT NULL,
    `exposed_params_snapshot` JSON      NOT NULL,
    `change_note`           VARCHAR(500) DEFAULT NULL,
    `changed_by`            BIGINT,
    `changed_at`            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_template_version` (`template_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='策略模板发布历史（每次 publish 一份快照）';

-- 3) stg_strategy 增加 provenance 3 列（幂等：字段存在时 IF NOT EXISTS 略过，MySQL 8.0.29+）
ALTER TABLE `stg_strategy`
    ADD COLUMN `source_template_id`      BIGINT     DEFAULT NULL COMMENT '源模板 id（null=手动创建）',
    ADD COLUMN `source_template_version` INT        DEFAULT NULL COMMENT '源模板版本',
    ADD COLUMN `template_user_params_json` JSON     DEFAULT NULL COMMENT '创建时用户填入的暴露参数值';

-- 4) 内置模板 #1：DUAL_EMA_MACD_TREND（使用现有指标；不依赖尚未实现的 DUAL_EMA_TREND / EMA_TRAIL / 回踩）
--    组合：EMA21（快）× EMA55（慢）× MACD 金叉/死叉 × RSI 过滤 × ATR 移动止损
--    暴露：symbol / interval / positionRatio / stopLossPct / feeRate / enableTrendFilter
INSERT INTO `strategy_template`
    (id, code, name, description, category, version, status, risk_profile,
     market_fit_json, interval_fit_json, template_json, exposed_params_json,
     schema_version, is_builtin, create_time, update_time)
VALUES
(1001, 'DUAL_EMA_MACD_TREND', '双 EMA + MACD 趋势跟随',
 '经典均线 + MACD 趋势策略。EMA21/EMA55 判断中期方向，MACD 确认动量，RSI 过滤超买超卖。适合 15m/1h/4h 有明确趋势的品种。\n\n【速度提示】入场按主周期（interval）evaluate，止损分两层：固定 % 止损 + ATR 止损（ATR 用 atrInterval 独立配置，默认 15m 更灵敏）。反向指标出场 (exitOnOppositeVoteRatio) 未启用 —— 若选 4h 入场，反向信号 auto-close 会最多延迟 4h；追求快速止盈请调宽 stopLossPct 并调紧 atrInterval。',
 'TREND', 1, 'PUBLISHED', 'MEDIUM',
 JSON_ARRAY('BTCUSDT','ETHUSDT','SOLUSDT','BNBUSDT'),
 JSON_ARRAY('15m','1h','4h'),
 JSON_OBJECT(
   'schemaVersion', 1,
   'indicators', JSON_ARRAY(
     JSON_OBJECT(
       'indicatorType', 'EMA',
       'params', JSON_OBJECT('period', 21),
       'weight', 30
     ),
     JSON_OBJECT(
       'indicatorType', 'EMA',
       'params', JSON_OBJECT('period', 55),
       'weight', 30
     ),
     JSON_OBJECT(
       'indicatorType', 'MACD',
       'params', JSON_OBJECT('fast', 12, 'slow', 26, 'signal', 9),
       'weight', 40
     ),
     JSON_OBJECT(
       'indicatorType', 'RSI',
       'params', JSON_OBJECT('period', 14),
       'weight', 20,
       'hardFilter', true,
       'filterConditions', JSON_ARRAY(
         JSON_OBJECT('field','rsi14','op','LT','threshold',75)
       )
     )
   ),
   'stopLossPct',  0.02,
   'takeProfitPct', 0.05,
   'atrStopMultiplier', 2.0,
   'atrTakeProfitMultiplier', 3.5,
   'atrInterval', '15m',
   'positionRatio', 0.2,
   'feeRate', 0.001,
   'autoTrade', 0,
   'minSignalStrength', 60,
   'maxHoldingBars', 200
 ),
 JSON_ARRAY(
   JSON_OBJECT('userKey','symbol','label','交易对','labelEn','Symbol','type','string','required',true,'templatePath','$strategy.symbol'),
   JSON_OBJECT('userKey','exchange','label','交易所','labelEn','Exchange','type','string','required',true,'default','binance','templatePath','$strategy.exchange'),
   JSON_OBJECT('userKey','interval','label','入场周期','labelEn','Interval','type','enum','options',JSON_ARRAY('15m','1h','4h'),'default','1h','templatePath','$strategy.interval'),
   JSON_OBJECT('userKey','positionRatio','label','仓位比例','labelEn','Position Size','type','number','min',0.01,'max',1,'step',0.01,'default',0.2,'templatePath','positionRatio'),
   JSON_OBJECT('userKey','stopLossPct','label','固定止损比例','labelEn','Stop Loss %','type','number','min',0.005,'max',0.1,'step',0.001,'default',0.02,'templatePath','stopLossPct'),
   JSON_OBJECT('userKey','takeProfitPct','label','固定止盈比例','labelEn','Take Profit %','type','number','min',0.01,'max',0.2,'step',0.001,'default',0.05,'templatePath','takeProfitPct'),
   JSON_OBJECT('userKey','atrInterval','label','止损ATR周期','labelEn','ATR SL Interval','type','enum','options',JSON_ARRAY('5m','15m','1h','4h'),'default','15m','description','ATR 止损/止盈用的 K 线周期。独立于入场周期，越小越灵敏。建议：入场 1h→ATR 15m；入场 4h→ATR 15m 或 1h','templatePath','atrInterval'),
   JSON_OBJECT('userKey','feeRate','label','手续费率','labelEn','Fee Rate','type','number','min',0,'max',0.01,'step',0.0001,'default',0.001,'templatePath','feeRate'),
   JSON_OBJECT('userKey','autoTrade','label','自动交易','labelEn','Auto Trade','type','bool','default',0,'templatePath','autoTrade')
 ),
 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
   description = VALUES(description),
   template_json = VALUES(template_json),
   exposed_params_json = VALUES(exposed_params_json),
   update_time = NOW();

-- 5) 内置模板 #2：RSI_MEAN_REVERSION（超卖反弹）
INSERT INTO `strategy_template`
    (id, code, name, description, category, version, status, risk_profile,
     market_fit_json, interval_fit_json, template_json, exposed_params_json,
     schema_version, is_builtin, create_time, update_time)
VALUES
(1002, 'RSI_MEAN_REVERSION', 'RSI 均值回归',
 '经典震荡策略：RSI 超卖买入、超买卖出。适合 BTC/ETH 高流动性、无明显趋势的震荡阶段。禁止趋势市使用。',
 'RANGE', 1, 'PUBLISHED', 'LOW',
 JSON_ARRAY('BTCUSDT','ETHUSDT'),
 JSON_ARRAY('5m','15m','1h'),
 JSON_OBJECT(
   'schemaVersion', 1,
   'indicators', JSON_ARRAY(
     JSON_OBJECT(
       'indicatorType', 'RSI',
       'params', JSON_OBJECT('period', 14),
       'weight', 60,
       'buyConditions',  JSON_ARRAY(JSON_OBJECT('field','rsi14','op','LT','threshold',30)),
       'sellConditions', JSON_ARRAY(JSON_OBJECT('field','rsi14','op','GT','threshold',70))
     ),
     JSON_OBJECT(
       'indicatorType', 'BOLL',
       'params', JSON_OBJECT('period', 20, 'stdDev', 2.0),
       'weight', 40
     )
   ),
   'stopLossPct',  0.015,
   'takeProfitPct', 0.025,
   'exitOnOppositeVoteRatio', 0.5,
   'positionRatio', 0.15,
   'feeRate', 0.001,
   'autoTrade', 0,
   'minSignalStrength', 60,
   'maxHoldingBars', 100
 ),
 JSON_ARRAY(
   JSON_OBJECT('userKey','symbol','label','交易对','labelEn','Symbol','type','string','required',true,'templatePath','$strategy.symbol'),
   JSON_OBJECT('userKey','exchange','label','交易所','labelEn','Exchange','type','string','required',true,'default','binance','templatePath','$strategy.exchange'),
   JSON_OBJECT('userKey','interval','label','入场周期','labelEn','Interval','type','enum','options',JSON_ARRAY('5m','15m','1h'),'default','15m','templatePath','$strategy.interval'),
   JSON_OBJECT('userKey','positionRatio','label','仓位比例','labelEn','Position Size','type','number','min',0.01,'max',1,'step',0.01,'default',0.15,'templatePath','positionRatio'),
   JSON_OBJECT('userKey','stopLossPct','label','止损比例','labelEn','Stop Loss %','type','number','min',0.005,'max',0.05,'step',0.001,'default',0.015,'templatePath','stopLossPct'),
   JSON_OBJECT('userKey','takeProfitPct','label','止盈比例','labelEn','Take Profit %','type','number','min',0.01,'max',0.1,'step',0.001,'default',0.025,'templatePath','takeProfitPct'),
   JSON_OBJECT('userKey','autoTrade','label','自动交易','labelEn','Auto Trade','type','bool','default',0,'templatePath','autoTrade')
 ),
 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
   description = VALUES(description),
   template_json = VALUES(template_json),
   exposed_params_json = VALUES(exposed_params_json),
   update_time = NOW();

-- 6) sys_menu：在「策略管理」组下加「策略模板」（id=34，parent=3）
INSERT INTO `sys_menu`
    (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
    (34, 3, '策略模板', 'text.strategy.templateTitle', '/strategy/template',
     'StrategyTemplateList', 'AppstoreAddOutlined', 1, 'strategy:template', 4, 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    parent_id   = VALUES(parent_id),
    name        = VALUES(name),
    i18n_key    = VALUES(i18n_key),
    path        = VALUES(path),
    component   = VALUES(component),
    icon        = VALUES(icon),
    `type`      = VALUES(`type`),
    permission  = VALUES(permission),
    sort        = VALUES(sort),
    status      = VALUES(status),
    deleted     = 0,
    update_time = NOW();

-- 7) 绑定到 administrator 角色
INSERT IGNORE INTO `sys_role_menu`
    (id, role_id, menu_id, create_by, create_time, update_by, update_time, deleted)
SELECT
    9000000000 + ROW_NUMBER() OVER (ORDER BY r.id) + 300 AS id,
    r.id AS role_id,
    34   AS menu_id,
    1, NOW(), 1, NOW(), 0
FROM `sys_role` r
WHERE r.code = 'administrator' AND r.deleted = 0;

-- 8) 兼容旧版本：软删除恢复
UPDATE `sys_menu` SET deleted = 0, update_time = NOW() WHERE id = 34 AND deleted = 1;
UPDATE `sys_role_menu` SET deleted = 0, update_time = NOW() WHERE menu_id = 34 AND deleted = 1;
