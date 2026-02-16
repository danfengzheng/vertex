package com.vertex.admin.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.vertex")
@MapperScan("com.vertex.service.*.mapper")
@EnableScheduling
public class VertexApplication {

	public static void main(String[] args) {
		SpringApplication.run(VertexApplication.class, args);
	}

}
