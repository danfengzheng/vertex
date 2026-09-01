package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略模板定义。
 * <p>
 * template_json 存完整策略骨架（指标、权重、止损参数等），
 * exposed_params_json 存暴露给用户的 3-8 个参数规格。
 * 用户"从模板创建策略"时：拿 template_json + 用户填的暴露参数值 →
 * {@code StrategyTemplateParser.build()} 生成完整 Strategy 实体。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("strategy_template")
public class StrategyTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 唯一 code，英文（如 DUAL_EMA_MACD_TREND） */
    private String code;
    private String name;

    /** 允许清空 → ALWAYS，避免 MyBatis-Plus 默认 NOT_NULL 策略跳过 null 更新 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    /** TREND / RANGE / BREAKOUT / DCA / CUSTOM */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String category;

    private Integer version;
    /** DRAFT / PUBLISHED / DEPRECATED */
    private String status;
    /** LOW / MEDIUM / HIGH */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String riskProfile;

    /** JSON 数组字符串：["BTCUSDT","ETHUSDT"] */
    @TableField(value = "market_fit_json", updateStrategy = FieldStrategy.ALWAYS)
    private String marketFitJson;

    /** JSON 数组字符串：["5m","15m","1h"] */
    @TableField(value = "interval_fit_json", updateStrategy = FieldStrategy.ALWAYS)
    private String intervalFitJson;

    /** JSON 对象字符串：完整策略骨架 */
    @TableField("template_json")
    private String templateJson;

    /** JSON 数组字符串：暴露参数规格 */
    @TableField("exposed_params_json")
    private String exposedParamsJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String minEngineVersion;

    private Integer schemaVersion;

    /** 1=官方内置（UI 只读可 fork），0=用户自建 */
    private Integer isBuiltin;
}
