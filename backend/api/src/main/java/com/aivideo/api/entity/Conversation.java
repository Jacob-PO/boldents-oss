package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 시나리오 생성 대화 세션 엔티티
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {
    private Long conversationId;
    private Long userNo;
    private Long creatorId;     // 크리에이터 ID (v2.9.134: genreId → creatorId 통합)
    private Long videoId;

    // 대화 메타데이터
    private String initialPrompt;
    private String referenceImageUrl;       // v2.9.84: 참조 이미지 S3 key
    private String referenceImageAnalysis;  // v2.9.84: 참조 이미지 AI 분석 결과 (JSON)
    private String contentType;  // YOUTUBE_SCENARIO, SHORTS, PRESENTATION
    private String qualityTier;  // STANDARD, PREMIUM
    private Integer videoDuration;  // 영상 길이 (분): 5, 10, 15

    // 대화 상태
    private String status;  // ACTIVE, COMPLETED, ABANDONED, SCENARIO_GENERATED
    private String currentStep;  // INFO_GATHERING, CONCEPT_PREVIEW, CHARACTER_PREVIEW, OPENING_PREVIEW, STYLE_PREVIEW, SCENARIO_PREVIEW, GENERATING
    private Integer questionCount;
    private Integer totalMessages;

    // 타임스탬프
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
