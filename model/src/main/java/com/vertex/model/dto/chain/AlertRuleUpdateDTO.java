package com.vertex.model.dto.chain;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 更新告警规则请求 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AlertRuleUpdateDTO extends AlertRuleCreateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;
}
