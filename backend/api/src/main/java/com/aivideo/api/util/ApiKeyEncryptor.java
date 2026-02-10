package com.aivideo.api.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API 키 암호화/복호화 유틸리티
 * AES-256-GCM 암호화 사용
 * v2.9.13: 암호화 키 환경변수 필수화
 */
@Slf4j
@Component
public class ApiKeyEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int MIN_KEY_LENGTH = 32;

    // v2.9.13: 기본값 제거 - 환경변수 필수
    @Value("${api-key.encryption-secret:}")
    private String encryptionSecret;

    private static final String DEV_DEFAULT_KEY = "aivideo-dev-encryption-key-32chars";

    @PostConstruct
    public void validateKey() {
        if (encryptionSecret == null || encryptionSecret.isBlank()) {
            log.error("[ApiKeyEncryptor] API_KEY_ENCRYPTION_SECRET 환경변수가 설정되지 않았습니다!");
            throw new IllegalStateException(
                    "API_KEY_ENCRYPTION_SECRET 환경변수를 설정해주세요 (최소 32자)");
        }
        if (encryptionSecret.length() < MIN_KEY_LENGTH) {
            log.error("[ApiKeyEncryptor] 암호화 키가 너무 짧습니다: {} < {}",
                    encryptionSecret.length(), MIN_KEY_LENGTH);
            throw new IllegalStateException(
                    "API_KEY_ENCRYPTION_SECRET는 최소 " + MIN_KEY_LENGTH + "자 이상이어야 합니다");
        }

        // v2.9.13: 개발용 기본키 사용 시 경고
        if (DEV_DEFAULT_KEY.equals(encryptionSecret)) {
            log.warn("========================================");
            log.warn("[ApiKeyEncryptor] ⚠️ 개발용 기본 암호화 키 사용 중!");
            log.warn("[ApiKeyEncryptor] 프로덕션에서는 API_KEY_ENCRYPTION_SECRET 환경변수를 설정하세요.");
            log.warn("========================================");
        } else {
            log.info("[ApiKeyEncryptor] 암호화 키 검증 완료 (길이: {}자)", encryptionSecret.length());
        }
    }

    /**
     * API 키 암호화
     */
    public String encrypt(String plainApiKey) {
        if (plainApiKey == null || plainApiKey.isEmpty()) {
            return null;
        }

        try {
            // 32바이트 키 생성 (AES-256)
            byte[] keyBytes = normalizeKey(encryptionSecret);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            // IV 생성
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            // 암호화
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
            byte[] encryptedBytes = cipher.doFinal(plainApiKey.getBytes(StandardCharsets.UTF_8));

            // IV + 암호문 결합 후 Base64 인코딩
            byte[] combined = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            log.error("[ApiKeyEncryptor] Encryption failed: {}", e.getMessage());
            throw new RuntimeException("API 키 암호화 실패", e);
        }
    }

    /**
     * API 키 복호화
     * v2.9.13: 복호화 실패 시 null 반환 (암호화 키 변경으로 인한 레거시 데이터 처리)
     */
    public String decrypt(String encryptedApiKey) {
        if (encryptedApiKey == null || encryptedApiKey.isEmpty()) {
            return null;
        }

        try {
            // Base64 디코딩
            byte[] combined = Base64.getDecoder().decode(encryptedApiKey);

            // IV와 암호문 분리
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);

            // 32바이트 키 생성
            byte[] keyBytes = normalizeKey(encryptionSecret);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            // 복호화
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            // v2.9.13: 복호화 실패 시 null 반환 (사용자에게 API 키 재등록 유도)
            log.warn("[ApiKeyEncryptor] Decryption failed (key mismatch?) - user should re-register API key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 키를 32바이트로 정규화 (AES-256)
     */
    private byte[] normalizeKey(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] normalizedKey = new byte[32];

        if (keyBytes.length >= 32) {
            System.arraycopy(keyBytes, 0, normalizedKey, 0, 32);
        } else {
            System.arraycopy(keyBytes, 0, normalizedKey, 0, keyBytes.length);
            // 나머지는 0으로 패딩
        }

        return normalizedKey;
    }

    /**
     * API 키 마스킹 (표시용)
     * 예: AIzaSy...8g -> AIza****8g
     */
    public String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 2);
    }
}
