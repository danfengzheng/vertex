package com.vertex.model.vo.system;

import lombok.Data;

/**
 * 用户通知配置 VO
 */
@Data
public class NotifyConfigVO {

    /** 通知渠道 */
    private String channel;

    /** Telegram Chat ID */
    private String chatId;

    /** 是否启用 0-关闭 1-开启 */
    private Integer enabled;
}
