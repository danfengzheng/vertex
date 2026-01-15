package com.vertex.common.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多语言配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vertex.i18n")
public class I18nProperties {

    /**
     * 是否开启多语言key自动拼接，默认为 false
     */
    private Boolean enable = false;

    /**
     * 多语言key连接符，默认为 "."
     */
    private String separator = ".";

}
