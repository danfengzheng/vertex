package com.vertex.model.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 模块动态配置（单行表，id=1）。
 * <p>
 * 与 {@code AiProperties}（yaml，重启期读的基础设施参数：{@code enabled}
 * bean 级安装开关 + 线程池尺寸）分工：
 * 本实体存放**运行时可热切换**的业务参数，UI 可编辑，改后 5s 内所有 AI 调用生效。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_config")
public class AiConfig implements Serializable {

    /** 固定 1（单行表）*/
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 总开关（可热切换：0=关，AI 调用降级 no-op；1=开）*/
    private Integer enabled;

    /** Provider 选择：gemini / deepseek */
    private String provider;

    /** 自由文本输出语言（BCP-47：zh-CN / en / ja / ...）*/
    private String language;

    // ─── Gemini ────────────────────────────────────────
    private String geminiApiKey;
    private String geminiModel;
    private String geminiBaseUrl;
    private Integer geminiTimeoutSeconds;
    private Integer geminiMaxRetry;

    // ─── DeepSeek ──────────────────────────────────────
    private String deepseekApiKey;
    private String deepseekModel;
    private String deepseekBaseUrl;
    private Integer deepseekTimeoutSeconds;
    private Integer deepseekMaxRetry;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long updateBy;
}
