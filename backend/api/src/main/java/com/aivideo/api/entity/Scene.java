package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 장면 엔티티
 * 시나리오의 개별 장면 정보를 저장
 *
 * v2.4.0: 개별 씬 영상 생성 지원을 위한 필드 추가
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scene {
    private Long sceneId;
    private Long videoId;
    private String sceneType;         // OPENING, SLIDE
    private Integer sceneOrder;       // 장면 순서
    private String title;
    private String narration;         // 나레이션 텍스트
    private String prompt;            // 이미지/영상 생성 프롬프트
    private Integer duration;         // 장면 길이 (초)
    private String cameraMovement;    // 카메라 움직임
    private String lightingPreset;    // 조명 프리셋
    private String framingType;       // 프레이밍 타입
    private String transitionType;    // 전환 효과
    private String mediaUrl;          // 생성된 미디어 URL (이미지 또는 영상)
    private String mediaStatus;       // 미디어 상태 (PENDING, GENERATING, COMPLETED, FAILED)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ========== v2.4.0 추가 필드 - 개별 씬 영상 생성 지원 ==========

    private String imageUrl;          // 생성된 이미지 경로 (슬라이드용)
    private String audioUrl;          // 생성된 TTS 오디오 경로
    private String subtitleUrl;       // 생성된 자막 파일 경로 (ASS)
    private String sceneVideoUrl;     // 1차 합성된 개별 씬 영상 경로

    private String sceneStatus;       // 씬 상태: PENDING, GENERATING, COMPLETED, FAILED, REGENERATING
    private Integer regenerateCount;  // 재생성 횟수 (기본 0)
    private String userFeedback;      // 사용자 수정 요청 내용

    /**
     * 씬이 완료되었는지 확인
     */
    public boolean isCompleted() {
        return "COMPLETED".equals(sceneStatus);
    }

    /**
     * 씬이 재생성 중인지 확인
     */
    public boolean isRegenerating() {
        return "REGENERATING".equals(sceneStatus);
    }

    /**
     * 파일명 생성 (순서 기반)
     * 예: scene_00_opening.mp4, scene_01.mp4
     */
    public String getSceneFileName() {
        if ("OPENING".equals(sceneType)) {
            return String.format("scene_%02d_opening.mp4", sceneOrder);
        }
        return String.format("scene_%02d.mp4", sceneOrder);
    }
}
