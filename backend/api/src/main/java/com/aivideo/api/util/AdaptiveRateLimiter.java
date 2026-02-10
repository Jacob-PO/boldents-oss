package com.aivideo.api.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2.9.13: 적응형 Rate Limiter
 *
 * 콘텐츠 생산 파이프라인의 핵심 기술:
 * - API 부하를 완벽하게 제어하면서 최대 생산성 달성
 * - 429/503 에러 시 자동으로 딜레이 증가
 * - 연속 성공 시 점진적으로 딜레이 감소
 * - 각 API 타입별 독립적인 딜레이 관리
 *
 * 사용 예:
 * ```java
 * AdaptiveRateLimiter imageLimiter = new AdaptiveRateLimiter(
 *     "Image", 3000, 2000, 15000, 0.9, 1.5, 3
 * );
 *
 * // API 호출 전 대기
 * imageLimiter.waitIfNeeded();
 *
 * try {
 *     // API 호출
 *     result = callApi();
 *     imageLimiter.recordSuccess();
 * } catch (RateLimitException e) {
 *     imageLimiter.recordError();
 *     throw e;
 * }
 * ```
 */
@Slf4j
public class AdaptiveRateLimiter {

    private final String name;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final double successDecreaseRatio;
    private final double errorIncreaseRatio;
    private final int successStreakForDecrease;

    // 현재 적용 중인 딜레이 (밀리초)
    private final AtomicLong currentDelayMs;

    // 마지막 API 호출 시간
    private final AtomicLong lastCallTimeMs = new AtomicLong(0);

    // 연속 성공 카운터
    private final AtomicInteger successStreak = new AtomicInteger(0);

    // 통계
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalWaitTimeMs = new AtomicLong(0);

    /**
     * 적응형 Rate Limiter 생성
     *
     * @param name API 이름 (로깅용)
     * @param initialDelayMs 초기 딜레이 (밀리초)
     * @param minDelayMs 최소 딜레이 (하한)
     * @param maxDelayMs 최대 딜레이 (상한)
     * @param successDecreaseRatio 성공 시 감소 비율 (예: 0.9 = 10% 감소)
     * @param errorIncreaseRatio 에러 시 증가 비율 (예: 1.5 = 50% 증가)
     * @param successStreakForDecrease 딜레이 감소에 필요한 연속 성공 횟수
     */
    public AdaptiveRateLimiter(
            String name,
            long initialDelayMs,
            long minDelayMs,
            long maxDelayMs,
            double successDecreaseRatio,
            double errorIncreaseRatio,
            int successStreakForDecrease
    ) {
        this.name = name;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.successDecreaseRatio = successDecreaseRatio;
        this.errorIncreaseRatio = errorIncreaseRatio;
        this.successStreakForDecrease = successStreakForDecrease;
        this.currentDelayMs = new AtomicLong(initialDelayMs);

        // v2.9.37: 첫 번째 요청도 딜레이 적용 (Rate Limit 예방)
        // lastCallTimeMs를 initialDelayMs만큼 과거로 설정하여
        // 첫 요청 시에도 waitIfNeeded()가 정상 동작하도록 함
        this.lastCallTimeMs.set(System.currentTimeMillis() - initialDelayMs);

        log.info("[{}RateLimiter] Initialized - delay: {}ms, range: {}ms-{}ms",
                name, initialDelayMs, minDelayMs, maxDelayMs);
    }

    /**
     * 필요 시 대기 (마지막 호출로부터 딜레이만큼)
     * 스레드 안전하게 대기 시간 계산
     *
     * v2.9.37: Rate Limit 예방을 위해 모든 요청에 딜레이 적용
     */
    public void waitIfNeeded() {
        long now = System.currentTimeMillis();
        long lastCall = lastCallTimeMs.get();
        long delay = currentDelayMs.get();
        long elapsed = now - lastCall;

        // v2.9.37: lastCall > 0 조건 제거 - 첫 요청도 딜레이 적용
        if (elapsed < delay) {
            long waitTime = delay - elapsed;
            try {
                log.debug("[{}RateLimiter] Waiting {}ms (delay: {}ms, elapsed: {}ms)",
                        name, waitTime, delay, elapsed);
                Thread.sleep(waitTime);
                totalWaitTimeMs.addAndGet(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}RateLimiter] Wait interrupted", name);
            }
        }

        // 호출 시작 시간 기록
        lastCallTimeMs.set(System.currentTimeMillis());
        totalCalls.incrementAndGet();
    }

    /**
     * API 호출 성공 기록
     * 연속 성공 시 딜레이 감소
     */
    public void recordSuccess() {
        int streak = successStreak.incrementAndGet();

        if (streak >= successStreakForDecrease) {
            long oldDelay = currentDelayMs.get();
            long newDelay = Math.max(minDelayMs, (long) (oldDelay * successDecreaseRatio));

            if (currentDelayMs.compareAndSet(oldDelay, newDelay) && newDelay < oldDelay) {
                log.info("[{}RateLimiter] ✓ Success streak {} - delay decreased: {}ms → {}ms",
                        name, streak, oldDelay, newDelay);
                successStreak.set(0);  // 감소 후 리셋
            }
        }
    }

    /**
     * API 호출 에러 기록 (429/503 등)
     * 즉시 딜레이 증가
     */
    public void recordError() {
        successStreak.set(0);  // 연속 성공 리셋
        totalErrors.incrementAndGet();

        long oldDelay = currentDelayMs.get();
        long newDelay = Math.min(maxDelayMs, (long) (oldDelay * errorIncreaseRatio));

        if (currentDelayMs.compareAndSet(oldDelay, newDelay)) {
            log.warn("[{}RateLimiter] ✗ Error - delay increased: {}ms → {}ms",
                    name, oldDelay, newDelay);
        }
    }

    /**
     * 심각한 에러 (503 오버로드 등)
     * 더 큰 폭으로 딜레이 증가
     */
    public void recordSevereError() {
        successStreak.set(0);
        totalErrors.incrementAndGet();

        long oldDelay = currentDelayMs.get();
        long newDelay = Math.min(maxDelayMs, (long) (oldDelay * errorIncreaseRatio * 1.5));

        if (currentDelayMs.compareAndSet(oldDelay, newDelay)) {
            log.warn("[{}RateLimiter] ✗✗ Severe error - delay increased: {}ms → {}ms",
                    name, oldDelay, newDelay);
        }
    }

    /**
     * 현재 딜레이 조회
     */
    public long getCurrentDelayMs() {
        return currentDelayMs.get();
    }

    /**
     * 통계 문자열 반환
     */
    public String getStats() {
        long calls = totalCalls.get();
        long errors = totalErrors.get();
        double errorRate = calls > 0 ? (double) errors / calls * 100 : 0;
        long avgWait = calls > 0 ? totalWaitTimeMs.get() / calls : 0;

        return String.format(
                "[%sRateLimiter Stats] calls: %d, errors: %d (%.1f%%), currentDelay: %dms, avgWait: %dms",
                name, calls, errors, errorRate, currentDelayMs.get(), avgWait
        );
    }

    /**
     * 딜레이 수동 설정 (테스트/긴급 조치용)
     */
    public void setDelay(long delayMs) {
        long bounded = Math.max(minDelayMs, Math.min(maxDelayMs, delayMs));
        currentDelayMs.set(bounded);
        log.info("[{}RateLimiter] Delay manually set to {}ms", name, bounded);
    }

    /**
     * 통계 리셋
     */
    public void resetStats() {
        totalCalls.set(0);
        totalErrors.set(0);
        totalWaitTimeMs.set(0);
        successStreak.set(0);
        log.info("[{}RateLimiter] Stats reset", name);
    }
}
