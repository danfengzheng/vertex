package com.vertex.common.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ErrorCode
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 02:38
 */
public interface ErrorCode {
    public Integer getCode();
    public String getMessage();
}