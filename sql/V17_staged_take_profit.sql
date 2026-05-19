-- V17: 分阶段止盈（固定 3 档）+ 保本上移联动
--
-- 启用规则：size1 > 0 即视为启用分阶段止盈，此时 take_profit_pct / atr_take_profit_multiplier 被忽略。
-- 末档行为：Σ size_i = 100% → 末档扫尾平剩余全部；Σ size_i < 100% → 末档按 size 部分平，剩余作为 runner，
--          由现有止损族（固定止损 / SuperTrend / 峰值回撤 / 移动 ATR / 反向信号）接管退出。
-- 保本上移：触发指定档后将止损上移到入场价，与移动 ATR 止损互斥（在 StrategyServiceImpl 中校验）。

ALTER TABLE stg_strategy
    ADD COLUMN take_profit_pct1 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第1档触发价百分比（多+X%/空-X%）',
    ADD COLUMN take_profit_size1 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第1档平仓比例（0-100，占initialQuantity）',
    ADD COLUMN take_profit_pct2 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第2档触发价百分比（可选）',
    ADD COLUMN take_profit_size2 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第2档平仓比例（可选）',
    ADD COLUMN take_profit_pct3 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第3档触发价百分比（可选）',
    ADD COLUMN take_profit_size3 DECIMAL(10,4) NULL
        COMMENT '分阶段止盈第3档平仓比例（可选）',
    ADD COLUMN move_stop_to_breakeven_after_stage TINYINT NULL
        COMMENT '触发指定档（1/2/3）后将止损上移到入场价（保本退出），0/null=不启用';

ALTER TABLE trd_position
    ADD COLUMN take_profit_stage TINYINT NOT NULL DEFAULT 0
        COMMENT '已触发的止盈阶段计数（0/1/2/3），CAS推进防重复触发',
    ADD COLUMN initial_quantity DECIMAL(20,8) NULL
        COMMENT '持仓建立时原始数量，分阶段止盈每档平仓量基准';

-- 历史持仓回填：initial_quantity 取当前 quantity 作为基线，
-- 防止历史持仓启用分阶段后第一次部分平仓按"剩余量"误算比例。
UPDATE trd_position
SET initial_quantity = quantity
WHERE initial_quantity IS NULL;
