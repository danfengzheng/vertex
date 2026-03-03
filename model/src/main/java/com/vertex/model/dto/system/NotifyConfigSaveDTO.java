package com.vertex.model.dto.system;

import lombok.Data;

/**
 * 保存用户通知配置 DTO
 */
@Data
public class NotifyConfigSaveDTO {

    /** Telegram Chat ID（个人或群组） */
    private String chatId;

    /** 是否启用 0-关闭 1-开启 */
    private Integer enabled;
}
