-- 策略配置表
DROP TABLE IF EXISTS `stg_strategy`;
CREATE TABLE `stg_strategy` (
    `id` BIGINT NOT NULL COMMENT '策略ID',
    `name` VARCHAR(100) NOT NULL COMMENT '策略名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '策略描述',
    `exchange` VARCHAR(50) NOT NULL COMMENT '交易所',
    `symbol` VARCHAR(50) NOT NULL COMMENT '交易对',
    `interval` VARCHAR(10) NOT NULL COMMENT 'K线周期',
    `indicator_configs` TEXT NOT NULL COMMENT '指标配置JSON',
    `enabled` TINYINT DEFAULT 0 COMMENT '是否启用 0-禁用 1-启用',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`, `deleted`),
    KEY `idx_exchange_symbol` (`exchange`, `symbol`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略配置表';

-- 策略信号表
DROP TABLE IF EXISTS `stg_signal`;
CREATE TABLE `stg_signal` (
    `id` BIGINT NOT NULL COMMENT '信号ID',
    `strategy_id` BIGINT NOT NULL COMMENT '策略ID',
    `strategy_name` VARCHAR(100) DEFAULT NULL COMMENT '策略名称',
    `symbol` VARCHAR(50) NOT NULL COMMENT '交易对',
    `exchange` VARCHAR(50) NOT NULL COMMENT '交易所',
    `interval` VARCHAR(10) NOT NULL COMMENT 'K线周期',
    `signal_type` VARCHAR(20) NOT NULL COMMENT '信号类型 BUY/SELL/NEUTRAL',
    `signal_strength` INT DEFAULT 0 COMMENT '信号强度 0-100',
    `price` DECIMAL(20,8) DEFAULT NULL COMMENT '触发价格',
    `signal_time` BIGINT NOT NULL COMMENT '信号时间（K线时间戳）',
    `indicators` TEXT DEFAULT NULL COMMENT '指标值JSON',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '信号描述',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_strategy_id` (`strategy_id`),
    KEY `idx_exchange_symbol` (`exchange`, `symbol`),
    KEY `idx_signal_type` (`signal_type`),
    KEY `idx_signal_time` (`signal_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略信号表';
