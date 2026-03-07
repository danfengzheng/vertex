-- V8: 多阶段移动ATR止损支持
-- 初始止损 → 保本 → 追踪三阶段状态机

ALTER TABLE stg_strategy
    ADD COLUMN initial_stop_multiplier         DECIMAL(10, 4) NULL COMMENT '初始止损倍数（N×ATR），设置后覆盖固定止损和ATR止损',
    ADD COLUMN breakeven_activation_multiplier DECIMAL(10, 4) NULL COMMENT '保本激活倍数（N×ATR），达到时将止损移至开仓价',
    ADD COLUMN trailing_activation_multiplier  DECIMAL(10, 4) NULL COMMENT '追踪激活倍数（N×ATR），超过时启动追踪止损逻辑',
    ADD COLUMN trailing_distance_multiplier    DECIMAL(10, 4) NULL COMMENT '追踪距离倍数（N×ATR），止损始终跟随最优价';

ALTER TABLE trd_position
    ADD COLUMN stop_loss_stage VARCHAR(20) NULL COMMENT '止损阶段: INITIAL/BREAKEVEN/TRAILING',
    ADD COLUMN highest_price   DECIMAL(30, 10) NULL COMMENT '多仓追踪最高价',
    ADD COLUMN lowest_price    DECIMAL(30, 10) NULL COMMENT '空仓追踪最低价';
