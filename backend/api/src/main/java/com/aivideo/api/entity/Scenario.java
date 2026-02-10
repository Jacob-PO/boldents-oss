package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 시나리오 엔티티
 * 생성된 시나리오 정보를 저장
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scenario {
    private Long scenarioId;
    private Long videoId;
    private String scenarioJson;      // 전체 시나리오 JSON
    private String openingPrompt;     // 오프닝 영상 프롬프트
    private String negativePrompt;    // 네거티브 프롬프트
    private String characterBlock;    // 캐릭터 블록 JSON
    private String timedSegments;     // 타임드 세그먼트 JSON
    private Integer version;          // 버전 (수정 시 증가)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
