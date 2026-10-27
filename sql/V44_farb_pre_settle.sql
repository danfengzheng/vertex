-- ============================================================================
-- V44: 资金费套利 - 预结算负 rate 检测（在 Binance 推送的预估下次结算 rate 转负时提前平）
-- ============================================================================

ALTER TABLE farb_strategy
    ADD COLUMN pre_settle_enabled      TINYINT      NOT NULL DEFAULT 1
        COMMENT '预结算负 rate 检测开关（0=关；1=开，默认）',
    ADD COLUMN pre_settle_imminent_min INT          NOT NULL DEFAULT 10
        COMMENT '临近结算窗口（分钟）：此范围内只要预估 rate < 0 就平',
    ADD COLUMN pre_settle_strong_min   INT          NOT NULL DEFAULT 60
        COMMENT '中距结算窗口（分钟）：此范围内若预估 rate ≤ strongBps 就平',
    ADD COLUMN pre_settle_strong_bps   DECIMAL(6,2) NOT NULL DEFAULT -3.00
        COMMENT '中距触发阈值（bps，负数）：strong 窗口内达到此值才触发';
