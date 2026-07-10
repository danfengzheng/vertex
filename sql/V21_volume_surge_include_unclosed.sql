-- ============================================
-- V21_volume_surge_include_unclosed.sql
-- 给 volume_surge_config 加一列 include_unclosed_bar：
--   1（默认）= 判定用「当前未收盘 1H 的累计成交额」直接比对 baseline × 阈值
--             → 小时内实时报警，比等收盘快 30-60min
--   0        = 保持旧行为，只判定已收盘 1H bar（30-60min 事后确认）
-- 幂等：可重复执行。
-- ============================================

-- MySQL 8+: IF NOT EXISTS 需要 8.0.29+；老版本请手工判断
ALTER TABLE `volume_surge_config`
    ADD COLUMN  `include_unclosed_bar` TINYINT NOT NULL DEFAULT 1
        COMMENT '是否用未收盘 1H bar 判定（1=实时，0=收盘）' AFTER `alert_directions`;

-- 老行默认打开新特性（重新执行 UPDATE 时无副作用）
UPDATE `volume_surge_config` SET `include_unclosed_bar` = 1
WHERE `id` = 1 AND `include_unclosed_bar` IS NULL;
