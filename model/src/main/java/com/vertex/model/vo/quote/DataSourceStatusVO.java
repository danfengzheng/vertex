package com.vertex.model.vo.quote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据源状态 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceStatusVO implements Serializable {

    /** 交易所标识 */
    private String exchange;

    /** 是否已连接 */
    private boolean connected;
}
