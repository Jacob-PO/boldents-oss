package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * v2.9.13: API 배치 처리 설정 엔티티
 *
 * 이미지/TTS 등 배치 처리 관련 설정을 DB에서 관리
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiBatchSettings {

    private Long settingId;

    // API 타입
    private String apiType;           // IMAGE, TTS 등

    // 배치 설정
    private Integer batchSize;        // 배치 크기
    private Integer batchDelayMs;     // 배치 간 딜레이 (밀리초)
    private Integer maxConsecutiveErrors;  // 연속 에러 허용 횟수
    private Integer errorBackoffMs;   // 에러 시 백오프 (밀리초)

    // 메타데이터
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
