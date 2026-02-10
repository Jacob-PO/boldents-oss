package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 크리에이터 프롬프트 Base 템플릿 엔티티
 *
 * 2단계 프롬프트 아키텍처 (v2.9.152)
 * - Layer 1: creator_prompt_base (이 테이블 - 백엔드 전용 Base 템플릿)
 * - Layer 2: creator_prompts (크리에이터별 콘텐츠)
 *
 * 특징:
 * - 100% 플레이스홀더로 구성된 XML 템플릿
 * - 사용자 수정 불가 (백엔드 전용)
 * - Gemini 3 최적화 XML 태그 형식
 *
 * 프롬프트 타입 (7개):
 * - SCENARIO: 시나리오 생성
 * - IMAGE_STYLE: 이미지 생성
 * - OPENING_VIDEO: 오프닝 영상
 * - TTS_INSTRUCTION: TTS 음성 지시
 * - NARRATION_EXPAND: 나레이션 확장
 * - THUMBNAIL: 썸네일 생성
 * - SAFETY: 안전 폴백
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorPromptBase {

    // ===== PK =====
    private Long baseId;

    // ===== 프롬프트 타입 =====
    private String promptType;  // SCENARIO, IMAGE_STYLE, OPENING_VIDEO, TTS_INSTRUCTION, NARRATION_EXPAND, THUMBNAIL, SAFETY

    // ===== Base 템플릿 =====
    private String baseTemplate;  // 플레이스홀더로 구성된 XML 템플릿

    // ===== 메타데이터 =====
    private String description;  // 프롬프트 설명
    private String requiredPlaceholders;  // 필수 플레이스홀더 목록 (JSON)
    private Integer version;  // 템플릿 버전
    private Boolean isActive;  // 활성화 여부

    // ===== 타임스탬프 =====
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
