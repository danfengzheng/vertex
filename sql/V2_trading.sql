-- ============================================================
-- Vertex 自动交易模块 DDL
-- Version: 2.0
-- ============================================================

-- 交易所账户（存储加密后的 API 密钥）
CREATE TABLE IF NOT EXISTS trd_exchange_account (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(100) NOT NULL COMMENT '账户名称',
    exchange VARCHAR(50) NOT NULL DEFAULT 'binance' COMMENT '交易所',
    api_key TEXT NOT NULL COMMENT 'API Key (AES-GCM加密)',
    api_secret TEXT NOT NULL COMMENT 'API Secret (AES-GCM加密)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易所账户';

-- 交易订单
CREATE TABLE IF NOT EXISTS trd_order (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT DEFAULT NULL COMMENT '关联策略ID',
    account_id BIGINT NOT NULL COMMENT '关联账户ID',
    signal_id BIGINT DEFAULT NULL COMMENT '关联信号ID',
    exchange VARCHAR(50) NOT NULL COMMENT '交易所',
    symbol VARCHAR(50) NOT NULL COMMENT '交易对',
    side VARCHAR(10) NOT NULL COMMENT 'BUY/SELL',
    order_type VARCHAR(20) NOT NULL DEFAULT 'MARKET' COMMENT '订单类型: MARKET/LIMIT',
    quantity DECIMAL(30,10) NOT NULL COMMENT '下单数量',
    price DECIMAL(30,10) DEFAULT NULL COMMENT '委托价格(LIMIT)',
    filled_quantity DECIMAL(30,10) DEFAULT 0 COMMENT '成交数量',
    filled_price DECIMAL(30,10) DEFAULT NULL COMMENT '成交均价',
    fee DECIMAL(30,10) DEFAULT 0 COMMENT '手续费',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态',
    trade_mode VARCHAR(10) NOT NULL COMMENT 'LIVE/PAPER',
    exchange_order_id VARCHAR(100) DEFAULT NULL COMMENT '交易所订单号',
    error_msg VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_strategy_id (strategy_id),
    KEY idx_account_id (account_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单';

-- 持仓
CREATE TABLE IF NOT EXISTS trd_position (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT NOT NULL COMMENT '关联策略ID',
    account_id BIGINT NOT NULL COMMENT '关联账户ID',
    exchange VARCHAR(50) NOT NULL COMMENT '交易所',
    symbol VARCHAR(50) NOT NULL COMMENT '交易对',
    side VARCHAR(10) NOT NULL COMMENT 'LONG/SHORT',
    quantity DECIMAL(30,10) NOT NULL DEFAULT 0 COMMENT '持仓数量',
    entry_price DECIMAL(30,10) NOT NULL COMMENT '开仓均价',
    current_price DECIMAL(30,10) DEFAULT NULL COMMENT '当前价格',
    unrealized_pnl DECIMAL(30,10) DEFAULT 0 COMMENT '未实现盈亏',
    realized_pnl DECIMAL(30,10) DEFAULT 0 COMMENT '已实现盈亏',
    stop_loss DECIMAL(30,10) DEFAULT NULL COMMENT '止损价',
    take_profit DECIMAL(30,10) DEFAULT NULL COMMENT '止盈价',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    trade_mode VARCHAR(10) NOT NULL COMMENT 'LIVE/PAPER',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_strategy_id (strategy_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓';

-- 策略表增加交易配置字段
ALTER TABLE stg_strategy
    ADD COLUMN auto_trade TINYINT NOT NULL DEFAULT 0 COMMENT '是否开启自动交易 0-否 1-是' AFTER enabled,
    ADD COLUMN trade_mode VARCHAR(10) DEFAULT NULL COMMENT '交易模式: AUTO/MANUAL' AFTER auto_trade,
    ADD COLUMN execution_mode VARCHAR(10) DEFAULT 'PAPER' COMMENT '执行模式: LIVE/PAPER' AFTER trade_mode,
    ADD COLUMN account_id BIGINT DEFAULT NULL COMMENT '关联交易账户ID' AFTER execution_mode,
    ADD COLUMN trade_quantity DECIMAL(30,10) DEFAULT NULL COMMENT '每次交易数量' AFTER account_id,
    ADD COLUMN stop_loss_pct DECIMAL(5,2) DEFAULT NULL COMMENT '止损百分比' AFTER trade_quantity,
    ADD COLUMN take_profit_pct DECIMAL(5,2) DEFAULT NULL COMMENT '止盈百分比' AFTER stop_loss_pct;
