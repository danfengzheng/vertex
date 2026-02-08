package com.vertex.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson配置
 * 解决前端JavaScript处理Long类型精度丢失问题
 * 
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/2/8
 */
@Configuration
public class JacksonConfig {

    /**
     * 配置ObjectMapper，将Long类型序列化为字符串
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.build();
        SimpleModule simpleModule = new SimpleModule();
        // 将Long类型序列化为字符串
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        objectMapper.registerModule(simpleModule);
        return objectMapper;
    }
}
