package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v2.9.13: API Rate Limit 설정 엔티티
 *
 * 모든 API Rate Limit을 DB에서 관리
 * - 하드코딩 완전 제거
 * - 런타임 조정 가능
 *
 * 공식 문서 기준 Rate Limit:
 * - TTS: 10 RPM, 100 RPD
 * - Image: 10-25 RPM, 10-15 IPM
 * - Video: 5-10 RPM
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiRateLimit {

    private Long limitId;

    // API 식별
    private String apiType;       // IMAGE, TTS, VIDEO, SCENARIO
    private String modelName;     // gemini-3-pro-image-preview 등
    private String tier;          // FREE, TIER_1, TIER_2

    // Rate Limits
    private Integer rpm;          // Requests Per Minute
    private Integer rpd;          // Requests Per Day (null = 무제한)
    private Integer tpm;          // Tokens Per Minute
    private Integer ipm;          // Images Per Minute

    // Delay 설정 (밀리초)
    private Integer minDelayMs;
    private Integer maxDelayMs;
    private Integer initialDelayMs;

    // 적응형 Rate Limiting 설정
    private BigDecimal successDecreaseRatio;    // 0.90 = 10% 감소
    private BigDecimal errorIncreaseRatio;      // 1.50 = 50% 증가
    private Integer successStreakForDecrease;

    // 재시도 설정
    private Integer maxRetries;
    private Integer initialBackoffMs;
    private Integer maxBackoffMs;

    // 메타데이터
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * RPM을 기반으로 최소 딜레이 계산
     * @return 밀리초 단위 딜레이
     */
    public long calculateMinDelayFromRpm() {
        if (rpm == null || rpm <= 0) {
            return minDelayMs != null ? minDelayMs : 6000;
        }
        return (60 * 1000L) / rpm;  // 60초를 RPM으로 나눔
    }

    /**
     * 적응형 Rate Limiter에 필요한 성공 감소 비율
     */
    public double getSuccessDecreaseRatioAsDouble() {
        return successDecreaseRatio != null ? successDecreaseRatio.doubleValue() : 0.9;
    }

    /**
     * 적응형 Rate Limiter에 필요한 에러 증가 비율
     */
    public double getErrorIncreaseRatioAsDouble() {
        return errorIncreaseRatio != null ? errorIncreaseRatio.doubleValue() : 1.5;
    }
}
