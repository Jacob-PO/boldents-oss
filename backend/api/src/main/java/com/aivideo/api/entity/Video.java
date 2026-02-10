package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Video {
    private Long videoId;
    private Long userNo;
    private Long creatorId;     // 크리에이터 ID (v2.9.134: genreId → creatorId 통합)
    private Long formatId;      // 영상 포맷 ID (v2.9.25, 기본값: 1 = 유튜브 일반)
    private Long videoSubtitleId; // 자막 템플릿 ID (v2.9.161, 기본값: 1 = 기본 자막)
    private Integer fontSizeLevel; // 자막 글자 크기 (v2.9.161, 1=small, 2=medium, 3=large, 기본값: 3)
    private Integer subtitlePosition; // 자막 위치 (v2.9.167, 1=하단, 2=중앙, 3=상단, 기본값: 1)
    private Long thumbnailId; // 썸네일 디자인 ID (v2.9.168, null이면 기본 CLASSIC)
    private Long fontId;      // 폰트 ID FK → video_font (v2.9.174, 기본값: 1 = SUIT-Bold)
    private Long conversationId;
    private String title;
    private String description;
    private String prompt;
    private String contentType;
    private String qualityTier;
    private String hookType;
    private String status;
    private Integer progress;
    private String currentStep;
    private Integer duration;
    private String thumbnailUrl;
    private String videoUrl;
    private String openingUrl;          // 오프닝 영상 URL
    private String finalVideoUrl;       // 최종 합성 영상 URL
    private LocalDateTime presignedUrlExpiresAt;  // v2.9.38: S3 presigned URL 만료 시간 (3시간)
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime deletedAt;
}
