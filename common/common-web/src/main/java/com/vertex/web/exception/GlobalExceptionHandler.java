package com.vertex.web.exception;

import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.web.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 02:39
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    public Result<?> handleBizException(BizException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.error("参数校验失败: {}", message);
        return Result.fail(GlobalError.PARAM_ERROR.getCode(), message);
    }

    /**
     * 数据库异常
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<?> handleDuplicateKeyException(DuplicateKeyException e) {
        log.error("数据库唯一键冲突", e);
        return Result.fail(GlobalError.SYSTEM_ERROR.getCode(), "数据已存在");
    }

    /**
     * 未知异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(GlobalError.SYSTEM_ERROR);
    }
}