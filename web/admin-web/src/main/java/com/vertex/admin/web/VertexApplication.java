package com.vertex.admin.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Vertex 入口。
 * <p>
 * <b>ComponentScan 排除策略</b>：
 * <ul>
 *   <li>{@code com.vertex.service.mm.*} —— mm-service 组件由 {@code MmAutoConfiguration}
 *       在 {@code vertex.mm.enabled=true} 时通过自己的 {@code @ComponentScan} 加载。
 *       此处必须排除，否则顶层扫描会拉起 mm-service 的 {@code @Component} 但
 *       {@code MmProperties} 却因 {@code @ConditionalOnProperty} 未注册 → bean 缺失启动失败。</li>
 * </ul>
 * <p>
 * <b>为什么用 {@code @ComponentScan} 而不是 {@code @SpringBootApplication(excludeFilters=...)}</b>：
 * {@code @SpringBootApplication} 不支持 {@code excludeFilters} 属性。此时应单独
 * 声明 {@code @ComponentScan}，其配置会**覆盖** {@code @SpringBootApplication} 隐式的默认扫描
 * （Spring 官方文档已说明）。
 * </p>
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.vertex",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.vertex\\.service\\.mm\\..*"
        )
)
@MapperScan("com.vertex.service.*.mapper")
@EnableScheduling
public class VertexApplication {

	public static void main(String[] args) {
		SpringApplication.run(VertexApplication.class, args);
	}

}
