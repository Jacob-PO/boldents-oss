package com.aivideo.api.service.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2.6.0 적응형 Rate Limit 시스템
 *
 * 429/503 응답에 따라 동적으로 딜레이를 조절합니다.
 * - 성공 시: 딜레이 감소 (최소 2초까지)
 * - 429 응답 시: 딜레이 2배 증가 (최대 120초)
 * - 503 응답 시: 딜레이 1.5배 증가 (최대 60초)
 */
@Slf4j
@Component
public class AdaptiveRateLimiter {

    // 서비스별 현재 딜레이 (ms)
    private final Map<String, AtomicLong> currentDelays = new ConcurrentHashMap<>();

    // 서비스별 연속 성공 횟수
    private final Map<String, AtomicInteger> consecutiveSuccesses = new ConcurrentHashMap<>();

    // 서비스별 연속 실패 횟수
    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

    // 기본 설정
    private static final long DEFAULT_INITIAL_DELAY_MS = 5000;     // 5초 시작
    private static final long MIN_DELAY_MS = 2000;                  // 최소 2초
    private static final long MAX_DELAY_429_MS = 120000;            // 429 최대 120초
    private static final long MAX_DELAY_503_MS = 60000;             // 503 최대 60초
    private static final int SUCCESS_THRESHOLD_FOR_DECREASE = 5;    // 5회 연속 성공 시 딜레이 감소

    /**
     * 서비스에 대한 현재 권장 딜레이 가져오기
     */
    public long getCurrentDelay(String serviceName) {
        return currentDelays
                .computeIfAbsent(serviceName, k -> new AtomicLong(DEFAULT_INITIAL_DELAY_MS))
                .get();
    }

    /**
     * 요청 성공 기록 - 딜레이 감소 가능
     */
    public void recordSuccess(String serviceName) {
        consecutiveFailures.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).set(0);

        int successes = consecutiveSuccesses
                .computeIfAbsent(serviceName, k -> new AtomicInteger(0))
                .incrementAndGet();

        // 연속 성공이 임계값 도달 시 딜레이 감소
        if (successes >= SUCCESS_THRESHOLD_FOR_DECREASE) {
            AtomicLong delay = currentDelays.get(serviceName);
            if (delay != null) {
                long newDelay = Math.max(MIN_DELAY_MS, (long) (delay.get() * 0.8)); // 20% 감소
                delay.set(newDelay);
                log.debug("[RateLimit] {} - Success streak, reducing delay to {}ms", serviceName, newDelay);
            }
            consecutiveSuccesses.get(serviceName).set(0); // 리셋
        }
    }

    /**
     * 429 (Rate Limit) 에러 기록 - 딜레이 대폭 증가
     */
    public void recordRateLimitError(String serviceName) {
        consecutiveSuccesses.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).set(0);

        int failures = consecutiveFailures
                .computeIfAbsent(serviceName, k -> new AtomicInteger(0))
                .incrementAndGet();

        AtomicLong delay = currentDelays.computeIfAbsent(serviceName, k -> new AtomicLong(DEFAULT_INITIAL_DELAY_MS));

        // 429 응답: 딜레이 2배 (최대 120초)
        long newDelay = Math.min(MAX_DELAY_429_MS, delay.get() * 2);
        delay.set(newDelay);

        log.warn("[RateLimit] {} - 429 Rate Limit hit (#{} consecutive), increasing delay to {}ms",
                serviceName, failures, newDelay);
    }

    /**
     * 503 (Service Unavailable/Overload) 에러 기록 - 딜레이 중간 증가
     */
    public void recordOverloadError(String serviceName) {
        consecutiveSuccesses.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).set(0);

        int failures = consecutiveFailures
                .computeIfAbsent(serviceName, k -> new AtomicInteger(0))
                .incrementAndGet();

        AtomicLong delay = currentDelays.computeIfAbsent(serviceName, k -> new AtomicLong(DEFAULT_INITIAL_DELAY_MS));

        // 503 응답: 딜레이 1.5배 (최대 60초)
        long newDelay = Math.min(MAX_DELAY_503_MS, (long) (delay.get() * 1.5));
        delay.set(newDelay);

        log.warn("[RateLimit] {} - 503 Overload (#{} consecutive), increasing delay to {}ms",
                serviceName, failures, newDelay);
    }

    /**
     * 일반 에러 기록 (429/503 아닌 경우) - 딜레이 소폭 증가
     */
    public void recordOtherError(String serviceName) {
        consecutiveSuccesses.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).set(0);
        consecutiveFailures.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).incrementAndGet();

        AtomicLong delay = currentDelays.computeIfAbsent(serviceName, k -> new AtomicLong(DEFAULT_INITIAL_DELAY_MS));

        // 일반 에러: 딜레이 1.2배 증가
        long newDelay = Math.min(MAX_DELAY_503_MS, (long) (delay.get() * 1.2));
        delay.set(newDelay);

        log.debug("[RateLimit] {} - Other error, slightly increasing delay to {}ms", serviceName, newDelay);
    }

    /**
     * 서비스 딜레이 초기화 (새 세션 시작 시)
     */
    public void resetDelay(String serviceName) {
        currentDelays.put(serviceName, new AtomicLong(DEFAULT_INITIAL_DELAY_MS));
        consecutiveSuccesses.put(serviceName, new AtomicInteger(0));
        consecutiveFailures.put(serviceName, new AtomicInteger(0));
        log.info("[RateLimit] {} - Delay reset to default {}ms", serviceName, DEFAULT_INITIAL_DELAY_MS);
    }

    /**
     * 현재 상태 정보
     */
    public RateLimitStatus getStatus(String serviceName) {
        long delay = getCurrentDelay(serviceName);
        int successes = consecutiveSuccesses.getOrDefault(serviceName, new AtomicInteger(0)).get();
        int failures = consecutiveFailures.getOrDefault(serviceName, new AtomicInteger(0)).get();

        return new RateLimitStatus(serviceName, delay, successes, failures);
    }

    /**
     * 모든 서비스 상태
     */
    public Map<String, RateLimitStatus> getAllStatuses() {
        Map<String, RateLimitStatus> statuses = new ConcurrentHashMap<>();
        currentDelays.keySet().forEach(service -> statuses.put(service, getStatus(service)));
        return statuses;
    }

    /**
     * Rate Limit 상태 DTO
     */
    public record RateLimitStatus(
            String serviceName,
            long currentDelayMs,
            int consecutiveSuccesses,
            int consecutiveFailures
    ) {}

    // 서비스 이름 상수
    public static final String SERVICE_IMAGE_GENERATION = "image_generation";
    public static final String SERVICE_VIDEO_GENERATION = "video_generation";
    public static final String SERVICE_TTS_GENERATION = "tts_generation";
    public static final String SERVICE_SCENARIO_GENERATION = "scenario_generation";
}
