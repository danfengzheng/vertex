package com.vertex.admin.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.vertex")
@MapperScan("com.vertex.service.*.mapper")
public class VertexApplication {

	public static void main(String[] args) {
		SpringApplication.run(VertexApplication.class, args);
	}

}
