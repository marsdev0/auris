// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.crypto;

import com.mars.auris.ai.config.ByokProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * BYOK API Key 加解密组件。AES-256-GCM,密文落库格式: IV(12B) + 密文 + tag(16B)
 *
 * @author geyan
 * @date 2026/10/10
 */
@Component
public class ApiKeyCipher {

    private static final int IV_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec keySpec;

    private final SecureRandom secureRandom = new SecureRandom();

    private final int keyVersion;

    public ApiKeyCipher(ByokProperties properties) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.getMasterKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("auris.byok.master-key is not valid Base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("auris.byok.master-key must be 32 bytes (AES-256) Base64-encoded");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
        this.keyVersion = properties.getKeyVersion();
    }

    /**
     * 加密明文 apiKey,返回 IV + 密文 + tag
     */
    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(cipherText, 0, out, IV_LENGTH, cipherText.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("apiKey encryption failed", e);
        }
    }

    /**
     * 解密 encrypt() 产出的 IV + 密文 + tag
     */
    public String decrypt(byte[] ivAndCipherText) {
        try {
            byte[] iv = Arrays.copyOfRange(ivAndCipherText, 0, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(ivAndCipherText, IV_LENGTH, ivAndCipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("apiKey decryption failed", e);
        }
    }

    /**
     * 当前主密钥版本,落库 key_version 用
     */
    public int currentVersion() {
        return keyVersion;
    }
}
