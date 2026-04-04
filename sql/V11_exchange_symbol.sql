-- V11: 币对元数据
-- trd_symbol:          平台通用标识（如 ETHUSDT），策略/信号/持仓均引用此值
-- trd_exchange_symbol: 交易所+类型 到 exchange_symbol 的映射（如 binance+USDM → ETHUSDT）

CREATE TABLE trd_symbol
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    symbol      VARCHAR(30)  NOT NULL COMMENT '平台通用标识，如 ETHUSDT',
    base_asset  VARCHAR(30)  NOT NULL COMMENT '标的资产，如 ETH',
    quote_asset VARCHAR(20)  NOT NULL COMMENT '计价资产，如 USDT',
    create_by   BIGINT                DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT                DEFAULT NULL,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol (symbol)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='平台通用币对表';

CREATE TABLE trd_exchange_symbol
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    symbol          VARCHAR(30)  NOT NULL COMMENT '平台通用标识，关联 trd_symbol.symbol',
    exchange        VARCHAR(20)  NOT NULL COMMENT '交易所代码，如 binance',
    market_type     VARCHAR(10)  NOT NULL COMMENT '市场类型：SPOT/USDM/COINM',
    exchange_symbol VARCHAR(40)  NOT NULL COMMENT '交易所实际标识，如 Binance=ETHUSDT / OKX=ETH-USDT-SWAP',
    status          VARCHAR(10)  NOT NULL DEFAULT 'TRADING' COMMENT '状态：TRADING/BREAK 等',
    min_notional    DECIMAL(20, 8)        DEFAULT NULL COMMENT '最小名义价值',
    lot_size        DECIMAL(20, 8)        DEFAULT NULL COMMENT '数量步长（stepSize）',
    tick_size       DECIMAL(20, 8)        DEFAULT NULL COMMENT '价格步长（tickSize）',
    sync_time       DATETIME              DEFAULT NULL COMMENT '最后从交易所同步时间',
    create_by       BIGINT                DEFAULT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT                DEFAULT NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exchange_symbol_type (exchange, symbol, market_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交易所币对映射表';
