package com.vertex.common.core.exception;

import com.vertex.common.core.ErrorCode;
import lombok.Data;
import lombok.Getter;

/**
 * BizException
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 02:38
 */
public class BizException extends RuntimeException {
    @Getter
    private Integer code;
    @Getter
    private String message;

    public BizException(String message) {
        super(message);
        this.code = 500;
        this.message = message;
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }
}
