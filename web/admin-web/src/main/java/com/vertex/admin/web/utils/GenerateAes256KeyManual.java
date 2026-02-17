package com.vertex.admin.web.utils;

import javax.crypto.KeyGenerator;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * GenerateAes256KeyManual
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/2/17 03:00
 */
public class GenerateAes256KeyManual {
    public static void main(String[] args) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);                             // ← 這行保證是 256 bit = 32 bytes
        byte[] keyBytes = kg.generateKey().getEncoded();

        System.out.println("長度: " + keyBytes.length + " bytes");   // 一定印 32
        System.out.println("Base64: " + Base64.getEncoder().encodeToString(keyBytes));
    }
}
