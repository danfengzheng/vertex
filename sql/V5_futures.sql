-- V5: 合约交易支持
-- 新增 market_type 字段（SPOT/USDM/COINM）到账户、订单、持仓
-- 新增 leverage、margin_type 字段到策略和持仓

-- 交易账户：新增合约类型
ALTER TABLE trd_exchange_account
    ADD COLUMN market_type VARCHAR(10) NOT NULL DEFAULT 'SPOT' COMMENT 'SPOT/USDM/COINM';

-- 策略：新增合约参数
ALTER TABLE stg_strategy
    ADD COLUMN leverage    INT NOT NULL DEFAULT 1       COMMENT '合约杠杆倍数（1-125）',
    ADD COLUMN margin_type VARCHAR(10) DEFAULT 'ISOLATED' COMMENT 'ISOLATED/CROSS';

-- 订单：记录所属市场类型
ALTER TABLE trd_order
    ADD COLUMN market_type VARCHAR(10) DEFAULT 'SPOT' COMMENT 'SPOT/USDM/COINM';

-- 持仓：新增合约专用字段
ALTER TABLE trd_position
    ADD COLUMN market_type       VARCHAR(10)    DEFAULT 'SPOT'     COMMENT 'SPOT/USDM/COINM',
    ADD COLUMN leverage          INT            DEFAULT 1          COMMENT '实际使用的杠杆倍数',
    ADD COLUMN margin_type       VARCHAR(10)    DEFAULT 'ISOLATED' COMMENT 'ISOLATED/CROSS',
    ADD COLUMN liquidation_price DECIMAL(30,10)                    COMMENT '强平价格（合约专用）',
    ADD COLUMN funding_rate      DECIMAL(20,8)                     COMMENT '最新资金费率（合约专用）';
