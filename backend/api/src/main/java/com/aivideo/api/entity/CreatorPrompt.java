package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 크리에이터별 프롬프트 엔티티 (Wide Table 구조)
 *
 * 2단계 프롬프트 아키텍처 (v2.9.152)
 * - Layer 1: creator_prompt_base (백엔드 전용 Base 템플릿, XML 형식)
 * - Layer 2: creator_prompts (이 테이블 - 크리에이터별 콘텐츠, 마이페이지 편집 가능)
 *
 * 컬럼 구성:
 * [A] 캐릭터 정의 (다른 모든 프롬프트에서 참조)
 * [B] SCENARIO 세분화 (5개)
 * [C] IMAGE 세분화 (6개) - 기존 image_negative 포함
 * [D] OPENING VIDEO 세분화 (4개)
 * [E] TTS 세분화 (2개)
 * [F] THUMBNAIL 세분화 (4개) - 기존 thumbnail_style_prompt 포함
 * [G] NARRATION EXPAND 세분화 (3개)
 * [H] SAFETY 세분화 (2개)
 * [I] 기타 (1개)
 * [J] 메타데이터
 *
 * v2.9.142: 컬럼 순서 재배치 (프롬프트 작성/연결 순서에 맞게)
 * v2.9.152: 2단계 아키텍처 - 레거시 통합 컬럼 제거, 세분화 컬럼만 유지
 * v2.9.155: 길이 설정값을 creator_prompts_length 테이블로 이동 (중앙 관리)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorPrompt {

    // ===== PK =====
    private Long creatorId;

    // =========================================================================
    // [A] 캐릭터 정의 - 다른 모든 프롬프트에서 참조 (4개)
    // =========================================================================
    private String identityAnchor;              // 얼굴/외모 정의 (Single Source of Truth)
    private String negativePromptsCharacter;    // 캐릭터 네거티브 프롬프트
    private String styleLock;                   // 스타일 고정 프롬프트
    private String characterBlockFull;          // 완전한 캐릭터 블록

    // =========================================================================
    // [B] SCENARIO 세분화 (5개) - v2.9.153: scenarioJsonFormat 제거 (Base 템플릿에서 관리)
    // =========================================================================
    private String scenarioContentRules;        // 콘텐츠 규칙
    private String scenarioVisualRules;         // 비주얼 규칙
    private String scenarioForbidden;           // 금지사항
    private String scenarioChecklist;           // 체크리스트
    private String scenarioUserTemplate;        // 사용자 템플릿

    // =========================================================================
    // [C] IMAGE 세분화 (6개)
    // =========================================================================
    private String imagePhotographyRules;       // 사진 규칙
    private String imageCompositionRules;       // 구도 규칙
    private String imageLightingRules;          // 조명 규칙
    private String imageBackgroundRules;        // 배경 규칙
    private String imageMandatoryElements;      // 필수 요소
    private String imageNegative;               // 이미지 네거티브 프롬프트

    // =========================================================================
    // [D] OPENING VIDEO 세분화 (4개)
    // =========================================================================
    private String openingTimelineStructure;    // 타임라인 구조
    private String openingCameraRules;          // 카메라 워크
    private String openingAudioDesign;          // 오디오 디자인
    private String openingForbidden;            // 오프닝 금지사항

    // =========================================================================
    // [E] TTS 세분화 (2개)
    // =========================================================================
    private String ttsVoiceName;                // 음성 이름 (Aoede, Charon 등)
    private String ttsPersona;                  // 페르소나 + 말투 패턴

    // =========================================================================
    // [F] THUMBNAIL 세분화 (4개)
    // =========================================================================
    private String thumbnailStylePrompt;        // 썸네일 스타일 프롬프트
    private String thumbnailComposition;        // 썸네일 구도
    private String thumbnailTextRules;          // 텍스트 규칙
    private String thumbnailMetadataRules;      // 유튜브 메타데이터

    // =========================================================================
    // [G] NARRATION EXPAND 세분화 (3개)
    // =========================================================================
    private String narrationContinuityRules;    // 연속성 규칙
    private String narrationVoiceRules;         // 목소리/톤 규칙
    private String narrationExpandRules;        // 확장 규칙

    // =========================================================================
    // [H] SAFETY 세분화 (2개)
    // =========================================================================
    private String safetyModificationRules;     // 수정 규칙
    private String safetyFallbackPrompt;        // 폴백 프롬프트

    // =========================================================================
    // [I] 기타 (1개)
    // =========================================================================
    private String referenceImageAnalysis;      // v2.9.150: 참조 이미지 분석 프롬프트

    // v2.9.155: 길이 설정값(openingNarrationLength, slideNarrationLength, narrationExpandLength)은
    // creator_prompts_length 테이블로 이동하여 중앙 관리

    // ===== [J] 메타데이터 =====
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
