-- =======================================================
-- Chain Analysis Tables  (prefix: chn_)
-- 多链新币分析模块表结构
-- =======================================================

-- -----------------------------------------------------------
-- chn_token: 发现的新代币
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `chn_token`;
CREATE TABLE `chn_token` (
    `id`               BIGINT        NOT NULL                    COMMENT '雪花ID',
    `chain`            VARCHAR(20)   NOT NULL                    COMMENT '链标识 BNB/SOLANA',
    `contract_address` VARCHAR(100)  NOT NULL                    COMMENT '合约地址/Mint地址',
    `symbol`           VARCHAR(50)   DEFAULT NULL                COMMENT '代币符号',
    `name`             VARCHAR(200)  DEFAULT NULL                COMMENT '代币名称',
    `decimals`         INT           DEFAULT NULL                COMMENT '精度',
    `total_supply`     VARCHAR(100)  DEFAULT NULL                COMMENT '总供应量',
    `deployer_address` VARCHAR(100)  DEFAULT NULL                COMMENT '部署者地址',
    `deploy_block`     BIGINT        DEFAULT NULL                COMMENT '部署区块号',
    `deploy_time`      BIGINT        DEFAULT NULL                COMMENT '部署时间戳(ms)',
    `score`            INT           DEFAULT 0                   COMMENT '综合评分 0-100',
    `score_onchain`    INT           DEFAULT 0                   COMMENT '链上维度分 0-30',
    `score_market`     INT           DEFAULT 0                   COMMENT '市场维度分 0-40',
    `score_tokenomics` INT           DEFAULT 0                   COMMENT '代币经济分 0-20',
    `score_novelty`    INT           DEFAULT 0                   COMMENT '新颖度分 0-10',
    `status`           VARCHAR(20)   DEFAULT 'PENDING'           COMMENT '状态 PENDING/SCORED/ALERTED/IGNORED',
    `alerted`          TINYINT       DEFAULT 0                   COMMENT '是否已告警 0-否 1-是',
    `alert_score`      INT           DEFAULT NULL                COMMENT '触发告警的分值',
    `pair_address`     VARCHAR(100)  DEFAULT NULL                COMMENT 'DEX交易对地址',
    `quote_token`      VARCHAR(50)   DEFAULT NULL                COMMENT '计价代币 WBNB/USDT/SOL',
    `raw_meta`         LONGTEXT      DEFAULT NULL                COMMENT '原始元数据JSON',
    `create_by`        BIGINT        DEFAULT NULL                COMMENT '创建者',
    `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    `update_by`        BIGINT        DEFAULT NULL                COMMENT '更新者',
    `update_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT       DEFAULT 0                   COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chain_address` (`chain`, `contract_address`, `deleted`),
    KEY `idx_chain`        (`chain`),
    KEY `idx_score`        (`score`),
    KEY `idx_status`       (`status`),
    KEY `idx_deploy_time`  (`deploy_time`),
    KEY `idx_create_time`  (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='链上新发现代币';


-- -----------------------------------------------------------
-- chn_token_metrics: 代币指标时序快照
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `chn_token_metrics`;
CREATE TABLE `chn_token_metrics` (
    `id`                   BIGINT          NOT NULL                   COMMENT '雪花ID',
    `token_id`             BIGINT          NOT NULL                   COMMENT '关联 chn_token.id',
    `snapshot_time`        BIGINT          NOT NULL                   COMMENT '快照时间戳(ms)',
    -- 链上指标
    `holder_count`         INT             DEFAULT NULL               COMMENT '持有者数量',
    `tx_count_1h`          INT             DEFAULT NULL               COMMENT '最近1小时交易数',
    `lp_add_count`         INT             DEFAULT NULL               COMMENT '流动性添加次数',
    `liquidity_locked`     TINYINT         DEFAULT 0                  COMMENT '流动性是否锁定 0/1',
    `contract_verified`    TINYINT         DEFAULT 0                  COMMENT '合约是否已验证 0/1',
    -- 市场指标
    `price_usd`            DECIMAL(30,18)  DEFAULT NULL               COMMENT 'USD价格',
    `market_cap_usd`       DECIMAL(24,4)   DEFAULT NULL               COMMENT 'USD市值',
    `liquidity_usd`        DECIMAL(24,4)   DEFAULT NULL               COMMENT 'USD流动性',
    `volume_24h_usd`       DECIMAL(24,4)   DEFAULT NULL               COMMENT '24h USD成交量',
    `price_change_1h_pct`  DECIMAL(10,4)   DEFAULT NULL               COMMENT '1h价格变化%',
    `price_change_24h_pct` DECIMAL(10,4)   DEFAULT NULL               COMMENT '24h价格变化%',
    `buy_pressure_1h`      DECIMAL(10,4)   DEFAULT NULL               COMMENT '1h买入压力比例 0-100',
    -- 代币经济指标
    `top10_holder_pct`     DECIMAL(10,4)   DEFAULT NULL               COMMENT 'Top10持仓集中度%',
    `deployer_holding_pct` DECIMAL(10,4)   DEFAULT NULL               COMMENT '部署者持仓%',
    `lp_pool_pct`          DECIMAL(10,4)   DEFAULT NULL               COMMENT 'LP池占供应%',
    -- 新颖度指标
    `age_minutes`          INT             DEFAULT NULL               COMMENT '距部署分钟数',
    `pump_fun_listed`      TINYINT         DEFAULT 0                  COMMENT '是否在Pump.fun上市 0/1',
    -- 审计字段
    `create_by`            BIGINT          DEFAULT NULL               COMMENT '创建者',
    `create_time`          DATETIME        DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_by`            BIGINT          DEFAULT NULL               COMMENT '更新者',
    `update_time`          DATETIME        DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              TINYINT         DEFAULT 0                  COMMENT '删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_token_id`       (`token_id`),
    KEY `idx_snapshot_time`  (`snapshot_time`),
    KEY `idx_token_snapshot` (`token_id`, `snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='链上代币指标快照';


-- -----------------------------------------------------------
-- chn_alert_rule: 可配置告警规则
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `chn_alert_rule`;
CREATE TABLE `chn_alert_rule` (
    `id`                        BIGINT         NOT NULL                   COMMENT '雪花ID',
    `name`                      VARCHAR(100)   NOT NULL                   COMMENT '规则名称',
    `chain`                     VARCHAR(20)    DEFAULT 'ALL'              COMMENT '目标链 BNB/SOLANA/ALL',
    `min_score`                 INT            DEFAULT 60                 COMMENT '最低综合分 0-100',
    `min_market_cap_usd`        DECIMAL(24,4)  DEFAULT NULL               COMMENT '最低市值USD',
    `min_liquidity_usd`         DECIMAL(24,4)  DEFAULT NULL               COMMENT '最低流动性USD',
    `min_holder_count`          INT            DEFAULT NULL               COMMENT '最低持有者数',
    `max_top10_holder_pct`      DECIMAL(10,4)  DEFAULT NULL               COMMENT 'Top10最高集中度%',
    `notify_channels`           VARCHAR(200)   DEFAULT '["telegram"]'     COMMENT '通知渠道JSON数组',
    `require_liquidity_locked`  TINYINT        DEFAULT NULL               COMMENT '是否要求锁流动性 NULL=不限',
    `enabled`                   TINYINT        DEFAULT 1                  COMMENT '是否启用 0-禁用 1-启用',
    `description`               VARCHAR(500)   DEFAULT NULL               COMMENT '描述',
    `create_by`                 BIGINT         DEFAULT NULL               COMMENT '创建者',
    `create_time`               DATETIME       DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_by`                 BIGINT         DEFAULT NULL               COMMENT '更新者',
    `update_time`               DATETIME       DEFAULT CURRENT_TIMESTAMP
                                               ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                   TINYINT        DEFAULT 0                  COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`, `deleted`),
    KEY `idx_chain`   (`chain`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='链上告警规则';
