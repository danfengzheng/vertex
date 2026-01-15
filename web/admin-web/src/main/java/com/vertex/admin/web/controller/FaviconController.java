package com.vertex.admin.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Favicon控制器
 * 处理浏览器自动请求的 favicon.ico
 */
@RestController
public class FaviconController {

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        // 返回 204 No Content，避免日志中的错误信息
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
