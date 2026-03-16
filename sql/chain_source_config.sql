-- 链上数据源配置表（山寨币筛选模块运行时开关）
-- 支持在 UI 中动态开关 bnb_alpha / bnb_trending 并调整过滤阈值

CREATE TABLE IF NOT EXISTS `chn_source_config` (
  `id`                        BIGINT        NOT NULL COMMENT '主键',
  `source_id`                 VARCHAR(32)   NOT NULL COMMENT '数据源标识：bnb_alpha | bnb_trending',
  `source_name`               VARCHAR(64)   NOT NULL COMMENT '数据源显示名称',
  `enabled`                   TINYINT       NOT NULL DEFAULT 0 COMMENT '是否启用 0-禁用 1-启用',
  `min_market_cap_usd`        DOUBLE        NULL     COMMENT '最低市值 USD 过滤阈值',
  `max_market_cap_usd`        DOUBLE        NULL     COMMENT '最高市值 USD 过滤阈值（null=不限）',
  `min_liquidity_usd`         DOUBLE        NULL     COMMENT '最低流动性 USD 过滤阈值',
  `min_volume_liquidity_ratio` DOUBLE       NULL     COMMENT '最低成交量/流动性换手率（bnb_trending）',
  `min_price_change_1h_pct`   DOUBLE        NULL     COMMENT '1h价格最低涨幅（bnb_trending）',
  `page_size`                 INT           NULL     COMMENT '每次 API 拉取数量（bnb_alpha）',
  `scan_limit`                INT           NULL     COMMENT '每次扫描最多处理代币数（bnb_trending）',
  `create_by`                 BIGINT        NULL     COMMENT '创建者',
  `create_time`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`                 BIGINT        NULL     COMMENT '更新者',
  `update_time`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`                   TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_id` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='链上数据源运行时配置';

-- 初始化默认配置（与 application.yaml 默认值一致）
INSERT INTO `chn_source_config`
    (`id`, `source_id`, `source_name`, `enabled`,
     `min_market_cap_usd`, `max_market_cap_usd`, `min_liquidity_usd`,
     `min_volume_liquidity_ratio`, `min_price_change_1h_pct`,
     `page_size`, `scan_limit`)
VALUES
    (1680000000001, 'bnb_alpha', 'Binance Alpha 筛选', 0,
     500000.0, NULL, 10000.0,
     NULL, NULL,
     50, NULL),
    (1680000000002, 'bnb_trending', 'BSC DEX 趋势筛选', 0,
     100000.0, 50000000.0, 5000.0,
     0.3, 0.0,
     NULL, 50)
ON DUPLICATE KEY UPDATE `source_id` = `source_id`;

-- 新增菜单项：筛选配置（挂载在链上分析 parent_id=5 下）
INSERT INTO `sys_menu` (id, parent_id, name, i18n_key, path, component, icon, `type`, sort, status, deleted, create_time, update_time)
VALUES
    (53, 5, '筛选配置', 'text.chain.sourceConfigTitle', '/chain/source-config', 'SourceConfig', 'SettingOutlined', 1, 3, 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name        = VALUES(name),
    i18n_key    = VALUES(i18n_key),
    path        = VALUES(path),
    component   = VALUES(component),
    icon        = VALUES(icon),
    `type`      = VALUES(`type`),
    sort        = VALUES(sort),
    status      = VALUES(status),
    update_time = NOW();
