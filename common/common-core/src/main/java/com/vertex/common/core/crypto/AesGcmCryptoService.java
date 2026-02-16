package com.vertex.common.core.crypto;

import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密解密服务
 * <p>
 * 用于交易所 API 密钥的安全存储。
 * 密文格式: Base64(IV[12 bytes] + Ciphertext + AuthTag[16 bytes])
 * </p>
 */
@Slf4j
@Service
public class AesGcmCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;       // 96 bits
    private static final int TAG_LENGTH = 128;     // 128 bits

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCryptoService(CryptoProperties properties) {
        String keyBase64 = properties.getKey();
        if (keyBase64 == null || keyBase64.isBlank()) {
            log.warn("AES crypto key not configured (vertex.crypto.key). Encryption will fail at runtime.");
            this.secretKey = null;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("AES-256 key must be 32 bytes (256 bits), got " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    /**
     * 加密明文
     *
     * @param plaintext 明文字符串
     * @return Base64 编码的密文（IV + ciphertext + tag）
     */
    public String encrypt(String plaintext) {
        if (secretKey == null) {
            throw new BizException(GlobalError.TRADE_ENCRYPTION_ERROR);
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + ciphertext（含 auth tag）
            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            throw new BizException(GlobalError.TRADE_ENCRYPTION_ERROR);
        }
    }

    /**
     * 解密密文
     *
     * @param ciphertext Base64 编码的密文
     * @return 明文字符串
     */
    public String decrypt(String ciphertext) {
        if (secretKey == null) {
            throw new BizException(GlobalError.TRADE_ENCRYPTION_ERROR);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            throw new BizException(GlobalError.TRADE_ENCRYPTION_ERROR);
        }
    }
}
