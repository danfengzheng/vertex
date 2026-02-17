package com.vertex.admin.web.config;

import java.util.Base64;

/**
 * SecurityConfig
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/14 00:07
 */
public class SecurityConfig {

    public static void main(String[] args) {
        String s = Base64.getEncoder().encodeToString("zx123456".getBytes());
        System.out.println(s);
    }
}
