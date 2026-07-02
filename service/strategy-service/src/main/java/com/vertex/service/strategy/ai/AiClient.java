package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSONObject;

/**
 * AI 提供商统一抽象。
 * <p>
 * 由 {@link AiAnalysisService} 通过 {@code @Autowired(required=false)} 注入；
 * 通过 {@code @ConditionalOnProperty} 在多个实现间二选一：
 * <ul>
 *   <li>{@link GeminiClient}: vertex.ai.provider=gemini（默认）</li>
 *   <li>{@link DeepSeekClient}: vertex.ai.provider=deepseek</li>
 * </ul>
 * 未启用任何 provider 时，本接口实现不存在 → AiAnalysisService 降级为 no-op。
 * </p>
 */
public interface AiClient {

    /**
     * 调用 LLM 生成符合 schema 的结构化 JSON（单 prompt 版本，兼容老调用）。
     *
     * @param prompt         完整 prompt 文本
     * @param responseSchema 期望的输出 JSON Schema（Gemini-style OBJECT/STRING/...）；
     *                       实现可根据 provider 转译（OpenAI 兼容协议会用 response_format
     *                       而非 schema）
     * @return 解析后的 JSONObject
     * @throws AiException 任何失败
     */
    JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException;

    /**
     * 双 prompt 版本：将 systemPrompt 与 userPrompt 分开传递，供 provider 走
     * 原生的 system message 通道（DeepSeek/OpenAI 用 role=system；Gemini 用 systemInstruction），
     * 让语言 / 角色指令的权重高于业务上下文，避免锚定英文。
     * <p>
     * 默认实现是简单拼接后走单 prompt 版本，对不支持 system message 的自定义 provider 仍能工作。
     * DeepSeekClient / GeminiClient 会各自 override。
     * </p>
     */
    default JSONObject generateJson(String systemPrompt, String userPrompt, JSONObject responseSchema)
            throws AiException {
        StringBuilder sb = new StringBuilder(
                (systemPrompt == null ? 0 : systemPrompt.length())
                        + (userPrompt == null ? 0 : userPrompt.length()) + 8);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append(systemPrompt).append("\n\n");
        }
        if (userPrompt != null) {
            sb.append(userPrompt);
        }
        return generateJson(sb.toString(), responseSchema);
    }

    /** 当前 provider 的标识，如 "gemini" / "deepseek" */
    String providerName();

    /** 当前实际使用的模型名（用于日志与 status 接口暴露）*/
    String currentModel();
}
