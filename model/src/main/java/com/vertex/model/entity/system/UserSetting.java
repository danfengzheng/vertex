package com.vertex.model.entity.system;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 用户个人设置实体（per-user 偏好配置）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_setting")
public class UserSetting extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /**
     * 单笔开仓最大使用资金（U/USDT）。
     * <p>
     * 当 PERCENT 仓位计算模式得到的 tradeAmount 超过此值时，截断为此值。
     * &lt;=0 或 NULL 表示不启用该限制（FieldStrategy.ALWAYS 允许前端清空）。
     * </p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxTradeCapital;
}
