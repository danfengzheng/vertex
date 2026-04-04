-- V13: 扩展币对相关字段长度
--
-- 问题：Binance 存在 base_asset 超过 10 字符的标的，如 1000FLOKI、100000AIDOGE 等，
--       导致同步时 Data too long for column 'base_asset' 错误。
--
-- trd_symbol
--   symbol:      VARCHAR(20)  → VARCHAR(30)   （COINM 交割合约如 BTCUSD_250328 已达 13 位）
--   base_asset:  VARCHAR(10)  → VARCHAR(30)
--   quote_asset: VARCHAR(10)  → VARCHAR(20)
--
-- trd_exchange_symbol
--   symbol:          VARCHAR(20) → VARCHAR(30)
--   exchange_symbol: VARCHAR(30) → VARCHAR(40)  （预留 OKX 等格式余量）

ALTER TABLE trd_symbol
    MODIFY COLUMN symbol      VARCHAR(30)  NOT NULL COMMENT '平台通用标识，如 ETHUSDT',
    MODIFY COLUMN base_asset  VARCHAR(30)  NOT NULL COMMENT '标的资产，如 ETH',
    MODIFY COLUMN quote_asset VARCHAR(20)  NOT NULL COMMENT '计价资产，如 USDT';

ALTER TABLE trd_exchange_symbol
    MODIFY COLUMN symbol          VARCHAR(30)  NOT NULL COMMENT '平台通用标识，关联 trd_symbol.symbol',
    MODIFY COLUMN exchange_symbol VARCHAR(40)  NOT NULL COMMENT '交易所实际标识';
