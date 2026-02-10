package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 크리에이터별 길이 설정 엔티티
 *
 * v2.9.155: 길이 관련 설정을 별도 테이블에서 중앙 관리
 * - 하드코딩 완전 제거
 * - creator_prompt_base에서 플레이스홀더로 참조
 *
 * 포함 필드:
 * - 썸네일 후킹문구 글자 수
 * - 유튜브 제목/설명 글자 수
 * - 오프닝/슬라이드 나레이션 글자 수
 * - 나레이션 확장 글자 수
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorPromptLength {

    // ===== PK =====
    private Long creatorId;

    // ===== 썸네일 관련 =====
    private Integer thumbnailHookLength;            // 썸네일 후킹문구 글자 수

    // ===== 유튜브 메타데이터 관련 =====
    private Integer youtubeTitleMinLength;          // 유튜브 제목 최소 글자 수
    private Integer youtubeTitleMaxLength;          // 유튜브 제목 최대 글자 수
    private Integer youtubeDescriptionMinLength;    // 유튜브 설명 최소 글자 수
    private Integer youtubeDescriptionMaxLength;    // 유튜브 설명 최대 글자 수

    // ===== 나레이션 관련 =====
    private Integer openingNarrationLength;         // 오프닝 나레이션 글자 수
    private Integer slideNarrationLength;           // 슬라이드 나레이션 글자 수
    private Integer narrationExpandLength;          // 나레이션 확장 시 목표 글자 수

    // ===== 메타데이터 =====
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
