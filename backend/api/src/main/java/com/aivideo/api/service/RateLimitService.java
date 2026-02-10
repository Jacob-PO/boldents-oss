package com.aivideo.api.service;

import com.aivideo.api.entity.ApiBatchSettings;
import com.aivideo.api.entity.ApiRateLimit;
import com.aivideo.api.mapper.ApiRateLimitMapper;
import com.aivideo.api.util.AdaptiveRateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v2.9.13: API Rate Limit 서비스
 *
 * 핵심 기능:
 * 1. DB에서 Rate Limit 설정 로드 및 캐싱
 * 2. 모델별 AdaptiveRateLimiter 인스턴스 관리
 * 3. 런타임 설정 변경 지원 (재배포 없이)
 *
 * 캐싱 전략:
 * - ConcurrentHashMap 캐시로 DB 조회 최소화
 * - 10분마다 자동 갱신
 * - 수동 갱신 API 제공
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final ApiRateLimitMapper apiRateLimitMapper;

    // 캐시: modelName -> ApiRateLimit
    private final Map<String, ApiRateLimit> rateLimitCache = new ConcurrentHashMap<>();

    // 캐시: apiType -> ApiBatchSettings
    private final Map<String, ApiBatchSettings> batchSettingsCache = new ConcurrentHashMap<>();

    // 모델별 AdaptiveRateLimiter 인스턴스 (싱글톤)
    private final Map<String, AdaptiveRateLimiter> rateLimiters = new ConcurrentHashMap<>();

    // 기본값 (DB 조회 실패 시 폴백)
    private static final int DEFAULT_RPM = 10;
    private static final int DEFAULT_MIN_DELAY_MS = 6000;
    private static final int DEFAULT_MAX_DELAY_MS = 30000;
    private static final int DEFAULT_INITIAL_DELAY_MS = 6000;
    private static final double DEFAULT_SUCCESS_DECREASE_RATIO = 0.9;
    private static final double DEFAULT_ERROR_INCREASE_RATIO = 1.5;
    private static final int DEFAULT_SUCCESS_STREAK = 3;

    @PostConstruct
    public void init() {
        log.info("[RateLimitService] Initializing - loading rate limits from DB...");
        refreshCache();
        log.info("[RateLimitService] Initialized with {} rate limits, {} batch settings",
                rateLimitCache.size(), batchSettingsCache.size());
    }

    /**
     * 10분마다 캐시 자동 갱신
     */
    @Scheduled(fixedRate = 600000)  // 10분
    public void scheduledRefresh() {
        log.debug("[RateLimitService] Scheduled cache refresh");
        refreshCache();
    }

    /**
     * 캐시 수동 갱신 (API로 호출 가능)
     */
    public void refreshCache() {
        try {
            List<ApiRateLimit> allLimits = apiRateLimitMapper.findAllActive();
            for (ApiRateLimit limit : allLimits) {
                rateLimitCache.put(limit.getModelName(), limit);
            }

            List<ApiBatchSettings> allBatchSettings = apiRateLimitMapper.findAllBatchSettings();
            for (ApiBatchSettings settings : allBatchSettings) {
                batchSettingsCache.put(settings.getApiType(), settings);
            }

            log.info("[RateLimitService] Cache refreshed - {} rate limits, {} batch settings",
                    allLimits.size(), allBatchSettings.size());

            // RateLimiter 인스턴스 업데이트
            for (ApiRateLimit limit : allLimits) {
                updateRateLimiter(limit);
            }

        } catch (Exception e) {
            log.error("[RateLimitService] Failed to refresh cache: {}", e.getMessage());
            // 캐시 갱신 실패해도 기존 캐시 유지
        }
    }

    // ========== Rate Limit 조회 ==========

    /**
     * 모델명으로 Rate Limit 설정 조회
     * @param modelName 모델명 (예: gemini-3-pro-image-preview)
     * @return Rate Limit 설정 (없으면 기본값)
     */
    public ApiRateLimit getRateLimit(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return createDefaultRateLimit("unknown");
        }

        ApiRateLimit cached = rateLimitCache.get(modelName);
        if (cached != null) {
            return cached;
        }

        // 캐시 미스: DB에서 조회
        try {
            Optional<ApiRateLimit> fromDb = apiRateLimitMapper.findByModelAndTier(modelName, "TIER_1");
            if (fromDb.isPresent()) {
                rateLimitCache.put(modelName, fromDb.get());
                return fromDb.get();
            }
        } catch (Exception e) {
            log.warn("[RateLimitService] Failed to load rate limit for {}: {}", modelName, e.getMessage());
        }

        // DB에도 없으면 기본값 반환
        log.warn("[RateLimitService] No rate limit found for model: {}, using defaults", modelName);
        return createDefaultRateLimit(modelName);
    }

    /**
     * API 타입과 모델명으로 Rate Limit 조회
     */
    public ApiRateLimit getRateLimitByTypeAndModel(String apiType, String modelName) {
        return getRateLimit(modelName);  // 현재는 모델명 기준으로 조회
    }

    /**
     * 배치 설정 조회
     * @param apiType API 타입 (IMAGE, TTS 등)
     */
    public ApiBatchSettings getBatchSettings(String apiType) {
        if (apiType == null || apiType.isBlank()) {
            return createDefaultBatchSettings("unknown");
        }

        ApiBatchSettings cached = batchSettingsCache.get(apiType);
        if (cached != null) {
            return cached;
        }

        // 캐시 미스: DB에서 조회
        try {
            Optional<ApiBatchSettings> fromDb = apiRateLimitMapper.findBatchSettingsByApiType(apiType);
            if (fromDb.isPresent()) {
                batchSettingsCache.put(apiType, fromDb.get());
                return fromDb.get();
            }
        } catch (Exception e) {
            log.warn("[RateLimitService] Failed to load batch settings for {}: {}", apiType, e.getMessage());
        }

        log.warn("[RateLimitService] No batch settings found for: {}, using defaults", apiType);
        return createDefaultBatchSettings(apiType);
    }

    // ========== AdaptiveRateLimiter 관리 ==========

    /**
     * 모델별 AdaptiveRateLimiter 인스턴스 조회 (싱글톤)
     * @param modelName 모델명
     * @return AdaptiveRateLimiter 인스턴스
     */
    public AdaptiveRateLimiter getRateLimiter(String modelName) {
        return rateLimiters.computeIfAbsent(modelName, this::createRateLimiter);
    }

    /**
     * API 타입으로 AdaptiveRateLimiter 조회 (타입의 기본 모델 사용)
     */
    public AdaptiveRateLimiter getRateLimiterByType(String apiType) {
        try {
            List<ApiRateLimit> limits = apiRateLimitMapper.findByApiType(apiType);
            if (!limits.isEmpty()) {
                return getRateLimiter(limits.get(0).getModelName());
            }
        } catch (Exception e) {
            log.warn("[RateLimitService] Failed to get rate limiter for type {}: {}", apiType, e.getMessage());
        }
        return getRateLimiter(apiType + "-default");
    }

    /**
     * 새 AdaptiveRateLimiter 생성
     */
    private AdaptiveRateLimiter createRateLimiter(String modelName) {
        ApiRateLimit config = getRateLimit(modelName);
        AdaptiveRateLimiter limiter = new AdaptiveRateLimiter(
                modelName,
                config.getInitialDelayMs() != null ? config.getInitialDelayMs() : DEFAULT_INITIAL_DELAY_MS,
                config.getMinDelayMs() != null ? config.getMinDelayMs() : DEFAULT_MIN_DELAY_MS,
                config.getMaxDelayMs() != null ? config.getMaxDelayMs() : DEFAULT_MAX_DELAY_MS,
                config.getSuccessDecreaseRatioAsDouble(),
                config.getErrorIncreaseRatioAsDouble(),
                config.getSuccessStreakForDecrease() != null ? config.getSuccessStreakForDecrease() : DEFAULT_SUCCESS_STREAK
        );
        log.info("[RateLimitService] Created AdaptiveRateLimiter for model: {} (delay: {}ms, rpm: {})",
                modelName, config.getInitialDelayMs(), config.getRpm());
        return limiter;
    }

    /**
     * 기존 RateLimiter 설정 업데이트 (캐시 갱신 시)
     */
    private void updateRateLimiter(ApiRateLimit config) {
        AdaptiveRateLimiter existing = rateLimiters.get(config.getModelName());
        if (existing != null && config.getInitialDelayMs() != null) {
            // 딜레이 설정만 업데이트 (RateLimiter 재생성하지 않음)
            long newDelay = config.getInitialDelayMs();
            if (existing.getCurrentDelayMs() != newDelay) {
                existing.setDelay(newDelay);
                log.info("[RateLimitService] Updated delay for {}: {}ms", config.getModelName(), newDelay);
            }
        }
    }

    // ========== 기본값 생성 ==========

    private ApiRateLimit createDefaultRateLimit(String modelName) {
        return ApiRateLimit.builder()
                .modelName(modelName)
                .tier("TIER_1")
                .rpm(DEFAULT_RPM)
                .minDelayMs(DEFAULT_MIN_DELAY_MS)
                .maxDelayMs(DEFAULT_MAX_DELAY_MS)
                .initialDelayMs(DEFAULT_INITIAL_DELAY_MS)
                .successDecreaseRatio(new java.math.BigDecimal(DEFAULT_SUCCESS_DECREASE_RATIO))
                .errorIncreaseRatio(new java.math.BigDecimal(DEFAULT_ERROR_INCREASE_RATIO))
                .successStreakForDecrease(DEFAULT_SUCCESS_STREAK)
                .maxRetries(5)
                .initialBackoffMs(5000)
                .maxBackoffMs(60000)
                .isActive(true)
                .build();
    }

    private ApiBatchSettings createDefaultBatchSettings(String apiType) {
        return ApiBatchSettings.builder()
                .apiType(apiType)
                .batchSize(5)
                .batchDelayMs(3000)
                .maxConsecutiveErrors(3)
                .errorBackoffMs(30000)
                .isActive(true)
                .build();
    }

    // ========== 통계 ==========

    /**
     * 모든 RateLimiter 통계 출력
     */
    public String getAllStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Rate Limiter Stats ===\n");
        for (Map.Entry<String, AdaptiveRateLimiter> entry : rateLimiters.entrySet()) {
            sb.append(entry.getValue().getStats()).append("\n");
        }
        return sb.toString();
    }
}
