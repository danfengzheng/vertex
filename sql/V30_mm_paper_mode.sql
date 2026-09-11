-- ============================================
-- V30_mm_paper_mode.sql
-- grid_strategy 加 execution_mode + paper 参数
--   PAPER  —— 用真实市场数据模拟成交（无风险）
--   LIVE   —— 真实下单
-- 幂等
-- ============================================

ALTER TABLE `grid_strategy`
    ADD COLUMN `execution_mode`         VARCHAR(16)  NOT NULL DEFAULT 'PAPER'
        COMMENT 'PAPER (模拟) / LIVE (真实下单)',
    ADD COLUMN `paper_queue_loss_factor` DECIMAL(4,2) DEFAULT 0.50
        COMMENT '成交模拟：仅填充 real trade qty 的 X% 模拟排队损失（1.0=乐观，0.3=悲观）',
    ADD COLUMN `paper_gtx_reject_rate`  DECIMAL(4,2) DEFAULT 0.05
        COMMENT '模拟 GTX 拒绝率（0-1）',
    ADD COLUMN `paper_smart_pricing_enabled` TINYINT DEFAULT 1
        COMMENT 'PAPER + LIVE 都可用：SmartPricePlacer 根据 depth 调整挂单价',
    ADD COLUMN `paper_min_liquidity_qty` DECIMAL(20,8) DEFAULT 2.0
        COMMENT 'SmartPricePlacer 视为薄簿位的最少排队量（BTC 建议 0.5 / ETH 建议 2-5）';
