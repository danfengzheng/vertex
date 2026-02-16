package com.vertex.common.core.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 加密配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "vertex.crypto")
public class CryptoProperties {

    /** AES-256 密钥，Base64 编码 */
    private String key;
}
