-- ============================================================================
-- V37: mm-service V1 简化版 —— 加 side / grid_mode / lower / upper / grid_count 字段
--
-- 目的：支持币安风格网格（等差/等比 + 单侧/双向 + 增量挂单 + 预览）
-- 兼容：所有 V2 高阶字段保留，V1 只用本次新增字段
-- 说明：文档见 service/mm-service/DESIGN_V1_SIMPLE.md
-- ============================================================================

-- ─── 1. 交易方向 ────────────────────────────────────────────────────────────
ALTER TABLE grid_strategy
    ADD COLUMN side ENUM('LONG_ONLY', 'SHORT_ONLY', 'BOTH')
        NOT NULL DEFAULT 'BOTH'
        COMMENT '交易方向：只做多 / 只做空 / 双向'
        AFTER symbol;

-- ─── 2. 价格分布模式 ───────────────────────────────────────────────────────
ALTER TABLE grid_strategy
    ADD COLUMN grid_mode ENUM('ARITHMETIC', 'GEOMETRIC')
        NOT NULL DEFAULT 'ARITHMETIC'
        COMMENT '价格分布：等差(固定金额) / 等比(固定百分比)'
        AFTER side;

-- ─── 3. 区间与格数 ─────────────────────────────────────────────────────────
ALTER TABLE grid_strategy
    ADD COLUMN lower_price DECIMAL(20, 8) DEFAULT NULL
        COMMENT '区间下限价'
        AFTER grid_mode,
    ADD COLUMN upper_price DECIMAL(20, 8) DEFAULT NULL
        COMMENT '区间上限价'
        AFTER lower_price,
    ADD COLUMN grid_count INT DEFAULT NULL
        COMMENT '网格数量（价位数 = count + 1）'
        AFTER upper_price;

-- ─── 4. 唯一约束（一个账户每个 symbol 只能有一个 BOTH，或最多一 LONG+一 SHORT）─
-- 用触发器 or Service 层校验（DB 层 ENUM 无法做互斥约束）
-- 这里只加常规索引，方便查询
ALTER TABLE grid_strategy
    ADD INDEX idx_symbol_side (exchange, symbol, account_id, side);

-- ─── 5. 对已有数据的默认值（不破坏 V2 已跑策略）─────────────────────────────
UPDATE grid_strategy
   SET side = 'BOTH',
       grid_mode = 'ARITHMETIC'
 WHERE side IS NULL OR grid_mode IS NULL;

-- ─── 6. 校验 SQL（迁移完可执行确认）─────────────────────────────────────────
-- SELECT id, name, symbol, side, grid_mode, lower_price, upper_price, grid_count
--   FROM grid_strategy;
