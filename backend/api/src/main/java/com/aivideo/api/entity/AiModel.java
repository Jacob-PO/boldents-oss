package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 모델 티어 엔티티
 * v2.9.120: 장르별 AI 모델 설정을 티어로 그룹화
 * 티어: BASIC, PRO, ULTRA
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModel {
    private Long tierId;
    private String tierCode;        // BASIC, PRO, ULTRA
    private String tierName;        // 기본, 프로, 울트라
    private String description;

    // Primary 모델
    private String imageModel;              // 이미지 생성 모델
    private String videoModel;              // 영상 생성 모델
    private String ttsModel;                // TTS 생성 모델
    private String scenarioModel;           // 시나리오 생성 모델

    // Fallback 모델
    private String fallbackImageModel;      // 이미지 폴백 모델
    private String fallbackVideoModel;      // 영상 폴백 모델
    private String fallbackTtsModel;        // TTS 폴백 모델
    private String fallbackScenarioModel;   // 시나리오 폴백 모델

    private Integer displayOrder;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
