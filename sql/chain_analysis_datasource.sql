-- =======================================================
-- Chain Analysis: 增加数据来源字段 & 山寨币筛选支持
-- 执行条件：chain_analysis_tables.sql 已初始化后追加执行
-- =======================================================

-- chn_token 表追加 data_source 字段
ALTER TABLE `chn_token`
    ADD COLUMN IF NOT EXISTS `data_source` VARCHAR(50) DEFAULT NULL
        COMMENT '数据来源标识: bnb_primary/bnb_alpha/bnb_trending/SOL等'
        AFTER `raw_meta`;

-- 为数据来源字段添加索引（按来源过滤时使用）
ALTER TABLE `chn_token`
    ADD INDEX IF NOT EXISTS `idx_data_source` (`data_source`);
