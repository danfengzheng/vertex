-- ============================================
-- V23_ai_deepseek_thinking.sql
-- 给 ai_config 加两个 DeepSeek 思考模式相关字段：
--   deepseek_thinking_enabled   NULL=用模型默认 / 0=显式关闭（快）/ 1=显式开启（慢，深度推理）
--   deepseek_reasoning_effort   NULL/'low'/'medium'/'high'（仅 thinking=enabled 时生效）
-- 对应 DeepSeek OpenAI 兼容 API 的两个可选字段：
--   thinking: {"type": "enabled"|"disabled"}
--   reasoning_effort: "low"|"medium"|"high"
-- 默认 thinking=disabled，量化信号分析用非思考模式，响应 3s vs 30s，成本降 10x+。
-- 幂等：可重复执行。
-- ============================================

ALTER TABLE `ai_config`
    ADD COLUMN  `deepseek_thinking_enabled` TINYINT DEFAULT 0
        COMMENT '思考模式：NULL=模型默认 / 0=显式关闭 / 1=显式开启' AFTER `deepseek_max_retry`;

ALTER TABLE `ai_config`
    ADD COLUMN  `deepseek_reasoning_effort` VARCHAR(16) DEFAULT NULL
        COMMENT '推理强度 low/medium/high；仅 thinking=enabled 时生效' AFTER `deepseek_thinking_enabled`;

-- 老行默认关思考（如果之前跑过 V22 建过默认行）
UPDATE `ai_config`
SET `deepseek_thinking_enabled` = 0
WHERE `id` = 1 AND `deepseek_thinking_enabled` IS NULL;
