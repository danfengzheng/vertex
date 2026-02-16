package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交易账户 VO（不含密钥）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeAccountVO implements Serializable {

    private Long id;
    private String name;
    private String exchange;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
