package com.vertex.common.core.config;

import com.vertex.common.core.constant.CommonConstant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Token配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vertex.token")
public class TokenProperties {

    /**
     * Token前缀，默认为 "Bearer "
     */
    private String prefix = CommonConstant.TOKEN_PREFIX;

    /**
     * Token请求头，默认为 "Authorization"
     */
    private String header = CommonConstant.TOKEN_HEADER;

}
