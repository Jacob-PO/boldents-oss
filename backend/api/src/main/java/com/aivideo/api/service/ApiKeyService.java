package com.aivideo.api.service;

import com.aivideo.api.entity.AiKey;
import com.aivideo.api.entity.User;
import com.aivideo.api.mapper.AiKeyMapper;
import com.aivideo.api.mapper.UserMapper;
import com.aivideo.api.util.ApiKeyEncryptor;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 키 관리 서비스
 * v2.9.150: 서비스 레벨 다중 API 키 폴백 시스템
 * - 계정별 API 키 → 서비스 레벨 API 키로 전환
 * - 1번 키 실패 → 2번 키 → 3번 키 순서로 폴백
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final AiKeyMapper aiKeyMapper;
    private final UserMapper userMapper;
    private final ApiKeyEncryptor apiKeyEncryptor;
    private final RestTemplate restTemplate;

    // 현재 사용 중인 API 키 캐시 (provider -> aiKeyId)
    private final Map<String, Long> currentKeyCache = new ConcurrentHashMap<>();

    // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 지원을 위한 ThreadLocal
    private static final ThreadLocal<Long> currentUserNo = new ThreadLocal<>();
    private static final String TIER_CUSTOM = "CUSTOM";

    private static final String DEFAULT_PROVIDER = "GOOGLE";
    private static final int MAX_ERROR_COUNT = 3; // 연속 3회 에러 시 다음 키로 전환

    // =========================================================================
    // v2.9.165: CUSTOM 티어 사용자 개인 API 키 지원
    // =========================================================================

    /**
     * 현재 요청의 사용자 설정 (ContentService/ChatService 진입점에서 호출)
     * CUSTOM 티어 사용자는 개인 API 키를 사용하도록 함
     */
    public static void setCurrentUserNo(Long userNo) {
        currentUserNo.set(userNo);
    }

    /**
     * 현재 요청의 사용자 정보 제거 (finally 블록에서 호출)
     */
    public static void clearCurrentUserNo() {
        currentUserNo.remove();
    }

    /**
     * CUSTOM 티어 사용자의 개인 Google API 키 조회
     * @return 복호화된 API 키 (CUSTOM 티어가 아니거나 키가 없으면 null)
     */
    private String getUserCustomApiKey(Long userNo) {
        User user = userMapper.findByUserNo(userNo).orElse(null);
        if (user == null) {
            return null;
        }

        if (!TIER_CUSTOM.equalsIgnoreCase(user.getTier())) {
            return null;
        }

        String encryptedKey = user.getGoogleApiKey();
        if (encryptedKey == null || encryptedKey.isBlank()) {
            log.warn("[ApiKeyService] CUSTOM tier user {} has no google_api_key", userNo);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "CUSTOM 티어 사용자는 Google API 키가 필요합니다. 관리자에게 문의하세요.");
        }

        String decryptedKey = apiKeyEncryptor.decrypt(encryptedKey);
        if (decryptedKey == null || decryptedKey.isEmpty()) {
            log.error("[ApiKeyService] Failed to decrypt google_api_key for CUSTOM tier user {}", userNo);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                "API 키 복호화에 실패했습니다. 관리자에게 문의하세요.");
        }

        log.debug("[ApiKeyService] Using CUSTOM tier user {}'s personal API key", userNo);
        return decryptedKey;
    }

    // =========================================================================
    // v2.9.150: 서비스 레벨 API 키 관리 (신규)
    // =========================================================================

    /**
     * API 키 조회 (복호화)
     * v2.9.165: CUSTOM 티어 사용자는 개인 google_api_key 사용, 그 외는 ai_key 테이블 사용
     */
    public String getServiceApiKey() {
        return getServiceApiKey(DEFAULT_PROVIDER);
    }

    /**
     * API 키 조회 (복호화) - 공급사 지정
     * v2.9.165: CUSTOM 티어 사용자는 개인 키만 사용 (ai_key 테이블 폴백 없음)
     */
    public String getServiceApiKey(String provider) {
        // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 확인
        Long userNo = currentUserNo.get();
        if (userNo != null) {
            String userApiKey = getUserCustomApiKey(userNo);
            if (userApiKey != null) {
                return userApiKey;
            }
        }

        // 기존 서비스 레벨 키 조회
        List<AiKey> activeKeys = aiKeyMapper.findActiveKeysByProvider(provider);

        if (activeKeys == null || activeKeys.isEmpty()) {
            log.error("[ApiKeyService] No active API keys found for provider: {}", provider);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "사용 가능한 API 키가 없습니다. 관리자에게 문의하세요.");
        }

        // 에러 카운트가 낮은 키부터 시도
        for (AiKey aiKey : activeKeys) {
            if (aiKey.getErrorCount() < MAX_ERROR_COUNT) {
                String decryptedKey = apiKeyEncryptor.decrypt(aiKey.getApiKey());
                if (decryptedKey != null && !decryptedKey.isEmpty()) {
                    currentKeyCache.put(provider, aiKey.getAiKeyId());
                    log.debug("[ApiKeyService] Using API key id={} priority={} for provider={}",
                        aiKey.getAiKeyId(), aiKey.getPriority(), provider);
                    return decryptedKey;
                }
            }
        }

        // 모든 키가 에러 카운트 초과인 경우, 첫 번째 키 반환 (에러 카운트 리셋 후)
        AiKey firstKey = activeKeys.get(0);
        aiKeyMapper.resetErrorCount(firstKey.getAiKeyId());
        currentKeyCache.put(provider, firstKey.getAiKeyId());
        log.warn("[ApiKeyService] All keys have high error count. Resetting first key id={}", firstKey.getAiKeyId());
        return apiKeyEncryptor.decrypt(firstKey.getApiKey());
    }

    /**
     * API 호출 성공 시 호출 - 에러 카운트 리셋 및 마지막 사용 시간 업데이트
     */
    @Transactional
    public void markApiKeySuccess() {
        markApiKeySuccess(DEFAULT_PROVIDER);
    }

    @Transactional
    public void markApiKeySuccess(String provider) {
        Long currentKeyId = currentKeyCache.get(provider);
        if (currentKeyId != null) {
            aiKeyMapper.resetErrorCount(currentKeyId);
            aiKeyMapper.updateLastUsed(currentKeyId);
            log.debug("[ApiKeyService] API key id={} marked as success", currentKeyId);
        }
    }

    /**
     * API 호출 실패 시 호출 - 에러 카운트 증가
     * @return 다음 폴백 키가 있으면 true, 없으면 false
     */
    @Transactional
    public boolean markApiKeyFailure() {
        return markApiKeyFailure(DEFAULT_PROVIDER);
    }

    @Transactional
    public boolean markApiKeyFailure(String provider) {
        Long currentKeyId = currentKeyCache.get(provider);
        if (currentKeyId != null) {
            aiKeyMapper.incrementErrorCount(currentKeyId);
            log.warn("[ApiKeyService] API key id={} marked as failure", currentKeyId);

            // 다음 사용 가능한 키가 있는지 확인
            List<AiKey> activeKeys = aiKeyMapper.findActiveKeysByProvider(provider);
            for (AiKey aiKey : activeKeys) {
                if (!aiKey.getAiKeyId().equals(currentKeyId) && aiKey.getErrorCount() < MAX_ERROR_COUNT) {
                    return true; // 폴백 키 존재
                }
            }
        }
        return false; // 폴백 키 없음
    }

    /**
     * 다음 폴백 API 키 조회 (현재 키 제외)
     */
    public String getNextFallbackApiKey() {
        return getNextFallbackApiKey(DEFAULT_PROVIDER);
    }

    public String getNextFallbackApiKey(String provider) {
        Long currentKeyId = currentKeyCache.get(provider);
        List<AiKey> activeKeys = aiKeyMapper.findActiveKeysByProvider(provider);

        for (AiKey aiKey : activeKeys) {
            if (!aiKey.getAiKeyId().equals(currentKeyId) && aiKey.getErrorCount() < MAX_ERROR_COUNT) {
                String decryptedKey = apiKeyEncryptor.decrypt(aiKey.getApiKey());
                if (decryptedKey != null && !decryptedKey.isEmpty()) {
                    currentKeyCache.put(provider, aiKey.getAiKeyId());
                    log.info("[ApiKeyService] Fallback to API key id={} priority={}",
                        aiKey.getAiKeyId(), aiKey.getPriority());
                    return decryptedKey;
                }
            }
        }

        log.error("[ApiKeyService] No fallback API key available for provider: {}", provider);
        return null;
    }

    /**
     * 서비스에 API 키가 등록되어 있는지 확인
     */
    public boolean hasServiceApiKey() {
        return hasServiceApiKey(DEFAULT_PROVIDER);
    }

    public boolean hasServiceApiKey(String provider) {
        List<AiKey> activeKeys = aiKeyMapper.findActiveKeysByProvider(provider);
        return activeKeys != null && !activeKeys.isEmpty();
    }

    // =========================================================================
    // 관리자용: API 키 CRUD
    // =========================================================================

    /**
     * 새 API 키 등록 (관리자용)
     */
    @Transactional
    public AiKey registerApiKey(String provider, String accountEmail, String projectName,
                                 String apiKey, Integer priority) {
        // API 키 테스트
        ApiKeyTestResponse testResult = testApiKey(apiKey);
        if (!testResult.isValid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 유효하지 않습니다: " + testResult.getErrorMessage());
        }

        // 암호화 후 저장
        String encryptedKey = apiKeyEncryptor.encrypt(apiKey);
        AiKey newKey = AiKey.builder()
            .provider(provider != null ? provider : DEFAULT_PROVIDER)
            .accountEmail(accountEmail)
            .projectName(projectName)
            .apiKey(encryptedKey)
            .priority(priority != null ? priority : 1)
            .isActive(true)
            .errorCount(0)
            .build();

        aiKeyMapper.insert(newKey);
        log.info("[ApiKeyService] New API key registered: id={} provider={} priority={}",
            newKey.getAiKeyId(), newKey.getProvider(), newKey.getPriority());
        return newKey;
    }

    /**
     * 모든 API 키 목록 조회 (관리자용)
     */
    public List<AiKey> getAllApiKeys() {
        List<AiKey> keys = aiKeyMapper.findAll();
        // API 키는 마스킹해서 반환
        for (AiKey key : keys) {
            key.setApiKey(apiKeyEncryptor.mask(apiKeyEncryptor.decrypt(key.getApiKey())));
        }
        return keys;
    }

    // =========================================================================
    // API 키 유효성 검증
    // =========================================================================

    /**
     * API 키 유효성 검증 (형식만)
     */
    public boolean validateApiKey(String apiKey) {
        if (apiKey == null || !apiKey.startsWith("AIza")) {
            return false;
        }
        return apiKey.length() >= 30;
    }

    /**
     * API 키 테스트 - Gemini API 호출로 실제 동작 확인
     */
    public ApiKeyTestResponse testApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return ApiKeyTestResponse.failure("API 키가 비어있습니다.");
        }

        String testModel = "gemini-3-flash-preview";
        String apiUrl = String.format(
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
            testModel
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> content = new HashMap<>();
            Map<String, Object> part = new HashMap<>();
            part.put("text", "Say 'API key is valid' in Korean.");
            content.put("parts", List.of(part));
            requestBody.put("contents", List.of(content));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("[ApiKeyService] API key test successful");
                return ApiKeyTestResponse.success(testModel);
            } else {
                return ApiKeyTestResponse.failure("API 응답 오류: " + response.getStatusCode());
            }

        } catch (HttpClientErrorException e) {
            log.warn("[ApiKeyService] API key test failed: {}", e.getMessage());

            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                return ApiKeyTestResponse.failure("잘못된 요청입니다. API 키를 확인해주세요.");
            } else if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ApiKeyTestResponse.failure("API 키가 비활성화되었거나 권한이 없습니다.");
            } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ApiKeyTestResponse.failure("API 키가 유효하지 않습니다.");
            } else {
                return ApiKeyTestResponse.failure("API 오류: " + e.getStatusCode() + " - " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("[ApiKeyService] API key test error", e);
            return ApiKeyTestResponse.failure("테스트 중 오류 발생: " + e.getMessage());
        }
    }

    // =========================================================================
    // DTO
    // =========================================================================

    @Getter
    @Setter
    public static class ApiKeyTestResponse {
        private boolean valid;
        private String errorMessage;
        private String modelInfo;

        public static ApiKeyTestResponse success(String modelInfo) {
            ApiKeyTestResponse response = new ApiKeyTestResponse();
            response.setValid(true);
            response.setModelInfo(modelInfo);
            return response;
        }

        public static ApiKeyTestResponse failure(String errorMessage) {
            ApiKeyTestResponse response = new ApiKeyTestResponse();
            response.setValid(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }
}
