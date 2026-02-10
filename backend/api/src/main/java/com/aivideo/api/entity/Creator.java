package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 크리에이터 엔티티
 * 멀티 크리에이터 플랫폼 지원을 위한 크리에이터 정보
 *
 * 컬럼 순서: 중요도와 프롬프트 작성 순서에 맞게 정렬
 *
 * [PK]
 * [기본 정보] - 크리에이터 핵심 정보
 * [UI 설정] - 홈 화면 표시 정보
 * [시스템 설정] - 내부 시스템 설정
 * [메타데이터]
 *
 * v2.9.142: 컬럼 순서 재배치 (중요도/작성순서 기준)
 * v2.9.142: creator_birth, youtube_channel 필드 추가
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Creator {

    // ===== [PK] =====
    private Long creatorId;

    // ===== [기본 정보] - 크리에이터 핵심 정보 =====
    private String creatorCode;       // REVIEW, FORTUNE 등 (고유 코드)
    private String creatorName;       // 크리에이터 이름
    private String creatorBirth;      // 크리에이터 생년월일 (예: 2003-09-15)
    private String youtubeChannel;    // YouTube channel name

    // ===== [UI 설정] - 홈 화면 표시 정보 =====
    private String description;       // 크리에이터 설명 (홈 화면 노출)
    private String placeholderText;   // 홈 화면 입력창 플레이스홀더

    // ===== [시스템 설정] - 내부 시스템 설정 =====
    private Boolean isActive;         // 활성화 여부 (홈 노출 여부 겸용)
    private Long modelTierId;         // AI 모델 티어 FK - ai_model.tier_id
    private String nationCode;        // 국가 코드 FK → creator_nation (v2.9.174)

    // ===== [메타데이터] =====
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
