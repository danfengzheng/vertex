-- ============================================================================
-- V43: 资金费套利 - Dual-maker post-only 执行
-- ============================================================================

ALTER TABLE farb_strategy
    ADD COLUMN dual_maker_enabled     TINYINT       NOT NULL DEFAULT 0
        COMMENT '双腿 post-only + 先成即市价对方腿（0=关，全 taker；1=开，fee 节省 30-50%）',
    ADD COLUMN dual_maker_timeout_ms  BIGINT        NULL DEFAULT 5000
        COMMENT 'post-only 挂单最长等待时间(ms)，超时都未成→全 MARKET 兜底',
    ADD COLUMN dual_maker_poll_ms     BIGINT        NULL DEFAULT 300
        COMMENT 'fill 状态轮询间隔(ms)',
    ADD COLUMN spot_maker_fee_rate    DECIMAL(10,6) NULL DEFAULT NULL
        COMMENT '现货 maker 手续费率（NULL=用 spot_fee_rate，等价没启用 maker 优惠）',
    ADD COLUMN perp_maker_fee_rate    DECIMAL(10,6) NULL DEFAULT NULL
        COMMENT '合约 maker 手续费率（NULL=用 perp_fee_rate）';
