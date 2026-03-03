package com.vertex.model.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户通知配置实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notify_config")
public class NotifyConfig extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 通知渠道（当前仅 TELEGRAM） */
    private String channel;

    /** Telegram Chat ID */
    private String chatId;

    /** 是否启用 0-关闭 1-开启 */
    private Integer enabled;
}
