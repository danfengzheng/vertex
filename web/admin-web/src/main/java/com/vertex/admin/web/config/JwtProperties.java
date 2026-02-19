package com.vertex.admin.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vertex.jwt")
public class JwtProperties {

    /** 签名密钥 */
    private String secret = "vertex-admin-secret-key-change-in-production";

    /** 过期时间（秒） */
    private long expirationSeconds = 86400;
}
