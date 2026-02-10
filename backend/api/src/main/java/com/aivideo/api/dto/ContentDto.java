package com.aivideo.api.dto;

import lombok.*;

import java.util.List;

/**
 * 콘텐츠 생성/다운로드 API DTO
 */
public class ContentDto {

    // ========== 시나리오 ==========

    /**
     * 시나리오 생성 요청
     * v2.4.0: 해시태그 다중 선택 지원
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioRequest {
        // v2.4.0: 해시태그 다중 선택
        private List<String> hashtagIds;  // 선택된 해시태그 ID 목록 (다중 선택)

        // v2.9.134: 크리에이터 기반 콘텐츠 생성
        private Long creatorId;  // 크리에이터 ID (null이면 기본 크리에이터=1 사용)

        // v2.9.25: 영상 포맷 선택
        private Long formatId;  // 포맷 ID (null이면 기본 포맷=1 사용)

        // v2.9.161: 자막 템플릿 선택
        private Long videoSubtitleId;  // 자막 템플릿 ID (null이면 기본 자막=1 사용)

        // v2.9.161: 자막 글자 크기 (1=small, 2=medium, 3=large, 기본값: 3)
        private Integer fontSizeLevel;

        // v2.9.167: 자막 위치 (1=하단, 2=중앙, 3=상단, 기본값: 1)
        private Integer subtitlePosition;

        // v2.9.174: 폰트 ID (null이면 기본 폰트=1 사용)
        private Long fontId;

        // v2.9.168: 썸네일 디자인 ID (null이면 기본 CLASSIC)
        private Long thumbnailId;

        // v2.9.73: 슬라이드 수 직접 선택 (1-10장, 슬라이드당 ~5분)
        private Integer slideCount;  // 슬라이드 수 (null이면 기본값 6)
    }

    /**
     * v2.9.75: 시나리오 생성 진행 상황 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioProgressResponse {
        private Long chatId;
        private String status;           // "idle", "generating", "expanding", "completed", "failed"
        private String phase;            // "INIT", "BASE_SCENARIO", "NARRATION_EXPAND"
        private Integer totalSlides;     // 전체 슬라이드 수
        private Integer completedSlides; // 완료된 슬라이드 수 (나레이션 확장)
        private Integer progress;        // 진행률 (0-100)
        private String message;          // 상태 메시지
    }

    /**
     * 시나리오 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioResponse {
        private Long chatId;
        private String title;
        private String description;
        private String summary;           // 고객용 시나리오 요약 (채팅에 표시)
        private String hook;              // 후킹 멘트
        private List<SlideInfo> slides;
        private Integer estimatedDuration;  // 예상 영상 길이 (초)
        private boolean downloadReady;
        private boolean hasOpening;       // 오프닝 영상 프롬프트 포함 여부
        private OpeningInfo opening;      // 오프닝 영상 상세 정보
    }

    /**
     * 오프닝 영상 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpeningInfo {
        private String narration;         // 오프닝 나레이션 (한국어)
        private String videoPrompt;       // Veo 3.1용 영상 생성 프롬프트 (영어)
        private Integer durationSeconds;  // 길이 (8초 - Veo API 고정)
    }

    /**
     * 슬라이드 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlideInfo {
        private Integer order;
        private String narration;
        private String imagePrompt;
        private Integer durationSeconds;
    }

    // ========== 이미지 ==========

    /**
     * 이미지 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImagesResponse {
        private Long chatId;
        private Integer totalCount;
        private Integer completedCount;
        private List<ImageInfo> images;
        private boolean downloadReady;
        private String progressMessage;
    }

    /**
     * 이미지 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageInfo {
        private Integer slideOrder;
        private String status;      // "completed", "pending", "failed"
        private String filePath;    // 완료 시 파일 경로
        private String errorMessage; // 실패 시 에러 메시지
    }

    // ========== 오디오 (TTS) ==========

    /**
     * 오디오 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioResponse {
        private Long chatId;
        private Integer totalCount;
        private Integer completedCount;
        private List<AudioInfo> audios;
        private boolean downloadReady;
        private Integer totalDuration;  // 총 길이 (초)
        private String progressMessage;
    }

    /**
     * 오디오 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioInfo {
        private Integer slideOrder;
        private String status;
        private String filePath;
        private Integer durationSeconds;
        private String errorMessage;
    }

    // ========== 영상 ==========

    /**
     * 영상 합성 요청
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoRequest {
        private boolean includeSubtitle = true;   // 자막 포함 여부
        private boolean includeOpening = true;    // 오프닝 포함 여부 (필수 - 8초 Veo 영상)
    }

    /**
     * 영상 합성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoResponse {
        private Long chatId;
        private String status;        // "processing", "completed", "failed"
        private Integer progress;     // 진행률 (0-100)
        private String progressMessage;
        private boolean downloadReady;
        private Integer durationSeconds;
        private String filePath;
    }

    // ========== 공통 ==========

    /**
     * 진행 상태 조회 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressResponse {
        private Long chatId;
        private String processType;   // "scenario", "images", "audio", "video"
        private String status;        // "idle", "processing", "completed", "failed"
        private Integer progress;     // 진행률 (0-100)
        private String message;       // 상태 메시지
        private Integer currentIndex; // 현재 처리 중인 항목
        private Integer totalCount;   // 전체 항목 수
    }

    /**
     * 다운로드 정보 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DownloadInfo {
        private String filename;
        private String contentType;
        private Long fileSize;
        private String downloadUrl;   // 직접 다운로드 URL (옵션)
        private java.time.LocalDateTime presignedUrlExpiresAt;  // v2.9.38: presigned URL 만료 시간
    }

    // ========== v2.4.0 개별 씬 영상 생성 지원 ==========

    /**
     * 씬 생성 요청 (이미지 + 오프닝 영상 동시 생성)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenesGenerateRequest {
        private boolean includeSubtitle = true;   // 자막 포함 여부
    }

    /**
     * 씬 생성 응답 (개별 씬 영상 생성 결과)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenesGenerateResponse {
        private Long chatId;
        private String status;           // "processing", "completed", "failed"
        private Integer totalCount;      // 전체 씬 수
        private Integer completedCount;  // 완료된 씬 수
        private String progressMessage;
        private List<SceneInfo> scenes;  // 각 씬의 상세 정보
    }

    /**
     * 개별 씬 정보 (v2.4.0)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneInfo {
        private Long sceneId;
        private Integer sceneOrder;
        private String sceneType;         // OPENING, SLIDE
        private String title;
        private String narration;         // 나레이션 텍스트
        private String prompt;            // 이미지/영상 생성 프롬프트

        // 미디어 URL
        private String imageUrl;          // 생성된 이미지 (슬라이드) 또는 null (오프닝)
        private String audioUrl;          // TTS 오디오 URL
        private String subtitleUrl;       // ASS 자막 파일 URL
        private String sceneVideoUrl;     // 개별 씬 영상 URL

        // 상태 정보
        private String sceneStatus;       // PENDING, GENERATING, COMPLETED, FAILED, REGENERATING
        private Integer regenerateCount;  // 재생성 횟수
        private String userFeedback;      // 사용자 수정 요청 내용

        private Integer durationSeconds;  // 씬 길이 (초)
        private String errorMessage;      // 실패 시 에러 메시지
    }

    /**
     * 씬 검토 응답 (모든 씬 상태 조회)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenesReviewResponse {
        private Long chatId;
        private String status;           // "pending", "all_completed", "has_failed"
        private Integer totalCount;
        private Integer completedCount;
        private Integer failedCount;
        private List<SceneInfo> scenes;
        private boolean canProceedToFinal; // 최종 영상 생성 가능 여부
        private String message;
    }

    /**
     * 특정 씬 재생성 요청
     * v2.6.1: mediaOnly 옵션 추가 - 이미지/영상만 재생성 (TTS/자막 유지)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneRegenerateRequest {
        private Long sceneId;             // 재생성할 씬 ID
        private String userFeedback;      // 사용자 수정 요청 내용 (선택)
        private String newPrompt;         // 새 프롬프트 (선택 - 없으면 기존 프롬프트 재사용)
        private Boolean mediaOnly;        // v2.6.1: true면 이미지/영상만 재생성 (TTS/자막 건드리지 않음)
    }

    /**
     * 씬 재생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneRegenerateResponse {
        private Long chatId;
        private Long sceneId;
        private String status;            // "processing", "completed", "failed"
        private String message;
        private SceneInfo scene;          // 재생성된 씬 정보
    }

    /**
     * 씬 ZIP 다운로드 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenesZipInfo {
        private Long chatId;
        private String filename;          // ZIP 파일명
        private Long fileSize;
        private Integer sceneCount;       // 포함된 씬 수
        private List<String> includedFiles; // 포함된 파일 목록
        private String downloadUrl;
    }

    /**
     * 최종 영상 생성 요청 (모든 씬 합성)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalVideoRequest {
        private boolean includeTransitions = true;  // 씬 간 전환 효과 포함
    }

    /**
     * 최종 영상 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalVideoResponse {
        private Long chatId;
        private String status;            // "processing", "completed", "failed"
        private Integer progress;         // 진행률 (0-100)
        private String progressMessage;
        private boolean downloadReady;
        private Integer durationSeconds;  // 총 영상 길이
        private String filePath;          // 최종 영상 경로
        private Integer sceneCount;       // 합성된 씬 수
    }

    // ========== v2.5.0 씬 프리뷰 및 나레이션 편집 ==========

    /**
     * 씬 프리뷰 생성 요청 (이미지/영상만 먼저 생성)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ScenePreviewRequest {
        // 현재는 추가 옵션 없음 - 확장성을 위해 유지
    }

    /**
     * 씬 프리뷰 응답 (이미지 + 나레이션 텍스트, TTS 미생성 상태)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenePreviewResponse {
        private Long chatId;
        private String status;             // "processing", "completed", "failed"
        private Integer totalCount;
        private Integer completedCount;
        private String progressMessage;
        private List<ScenePreviewInfo> previews;  // 프리뷰 정보 (이미지 + 편집 가능한 나레이션)
        private String aspectRatio;        // v2.9.25: 영상 포맷 비율 ("16:9" 또는 "9:16")
    }

    /**
     * 개별 씬 프리뷰 정보 (나레이션 편집 전)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenePreviewInfo {
        private Long sceneId;
        private Integer sceneOrder;
        private String sceneType;          // OPENING, SLIDE
        private String title;

        // 미디어 (이미지/영상만)
        private String mediaUrl;           // 이미지 URL (슬라이드) 또는 영상 URL (오프닝)
        private String mediaType;          // "image" 또는 "video"

        // v2.6.0: 합성된 개별 씬 영상 (TTS+자막 포함)
        private String sceneVideoUrl;      // 합성된 씬 영상 URL (COMPLETED 상태일 때)

        // 편집 가능한 텍스트
        private String narration;          // 현재 나레이션 (편집 가능)
        private boolean isEdited;          // 사용자가 편집했는지 여부

        // 상태
        private String previewStatus;      // PENDING, MEDIA_READY, TTS_READY, COMPLETED
        private String errorMessage;
    }

    /**
     * 씬 나레이션 편집 요청
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneNarrationEditRequest {
        private Long sceneId;              // 편집할 씬 ID
        private String newNarration;       // 수정된 나레이션 텍스트
    }

    /**
     * 씬 나레이션 편집 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneNarrationEditResponse {
        private Long chatId;
        private Long sceneId;
        private String status;             // "success", "failed"
        private String oldNarration;       // 이전 나레이션
        private String newNarration;       // 수정된 나레이션
        private String message;
    }

    /**
     * TTS/자막 생성 요청 (나레이션 편집 완료 후)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneAudioGenerateRequest {
        private List<Long> sceneIds;       // 생성할 씬 ID 목록 (null이면 전체)
        private boolean includeSubtitle = true;
    }

    /**
     * TTS/자막 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneAudioGenerateResponse {
        private Long chatId;
        private String status;             // "processing", "completed", "failed"
        private Integer totalCount;
        private Integer completedCount;
        private String progressMessage;
        private List<SceneAudioInfo> audioInfos;
    }

    /**
     * 개별 씬 오디오 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneAudioInfo {
        private Long sceneId;
        private Integer sceneOrder;
        private String audioUrl;           // TTS 오디오 URL
        private String subtitleUrl;        // ASS 자막 URL
        private Integer durationSeconds;   // 오디오 길이
        private String status;             // "pending", "completed", "failed"
        private String errorMessage;
    }

    // ========== v2.6.0 부분 실패 복구 시스템 ==========

    /**
     * 실패한 씬 재시도 요청 (다중 씬 지원)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedScenesRetryRequest {
        private List<Long> sceneIds;           // 재시도할 씬 ID 목록 (null이면 모든 실패 씬)
        private boolean retryMediaOnly = true; // 미디어만 재시도 (TTS 제외)
    }

    /**
     * 실패한 씬 재시도 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedScenesRetryResponse {
        private Long chatId;
        private String status;                 // "processing", "completed", "no_failed_scenes"
        private Integer totalFailedCount;      // 실패한 씬 총 개수
        private Integer retryingCount;         // 재시도 중인 씬 개수
        private List<FailedSceneInfo> failedScenes;  // 실패 씬 정보
        private String message;
    }

    /**
     * 실패 씬 상세 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedSceneInfo {
        private Long sceneId;
        private Integer sceneOrder;
        private String sceneType;
        private String failedAt;               // "MEDIA", "TTS", "SUBTITLE", "VIDEO"
        private String errorMessage;
        private Integer retryCount;            // 재시도 횟수
        private boolean isRetrying;            // 현재 재시도 중인지
    }

    /**
     * 진행 상태 체크포인트 (서버 재시작 복구용)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessCheckpoint {
        private Long chatId;
        private String processType;            // "scene_preview", "scene_audio", "final_video"
        private String status;                 // "processing", "completed", "failed", "paused"
        private Integer totalCount;
        private Integer completedCount;
        private Integer failedCount;
        private List<Long> completedSceneIds;  // 완료된 씬 ID 목록
        private List<Long> failedSceneIds;     // 실패한 씬 ID 목록
        private String lastUpdated;            // ISO 8601 형식
        private boolean canResume;             // 재개 가능 여부
    }

    /**
     * 프로세스 재개 요청
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessResumeRequest {
        private boolean skipFailed = false;    // 실패 씬 스킵하고 계속 진행
    }

    /**
     * 프로세스 재개 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessResumeResponse {
        private Long chatId;
        private String status;
        private Integer resumedFromIndex;      // 재개 시작 인덱스
        private Integer remainingCount;        // 남은 씬 수
        private String message;
    }

    // ========== 썸네일 ==========

    /**
     * 썸네일 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThumbnailResponse {
        private Long chatId;
        private String thumbnailUrl;
        private String title;       // 영상 제목 (원본)
        private String catchphrase; // 썸네일용 캐치프레이즈
        private String youtubeTitle; // 유튜브용 최적화 제목
        private String youtubeDescription; // 유튜브용 설명글 (해시태그 포함)
        private String status;      // v2.9.84: GENERATING, COMPLETED, NOT_FOUND
    }
}
