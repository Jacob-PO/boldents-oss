package com.aivideo.api.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 심플한 채팅 API DTO
 */
public class ChatDto {

    /**
     * 채팅 시작 요청
     * v2.9.134: creatorId 통합 (genreId → creatorId)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartRequest {
        private String prompt;     // 초기 프롬프트 (옵션)
        private Long creatorId;    // v2.9.134: 크리에이터 ID (필수)
    }

    /**
     * 메시지 전송 요청
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageRequest {
        private String message;
    }

    /**
     * 채팅 응답 (시작/메시지 공통)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        private Long chatId;
        private String aiMessage;
        private Stage stage;
        private boolean canGenerateScenario;  // 시나리오 생성 가능 여부
    }

    /**
     * 채팅 목록 요약 (Sidebar용)
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatSummary {
        private Long chatId;
        private String initialPrompt;
        private Stage stage;
        private Integer messageCount;
        private LocalDateTime createdAt;
    }

    /**
     * 채팅 상세 조회 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatDetail {
        private Long chatId;
        private Stage stage;
        private boolean canGenerateScenario;  // 시나리오 생성 가능 여부
        private List<Message> messages;
        private ContentStatus contentStatus;  // 생성된 콘텐츠 상태
        private LocalDateTime createdAt;
        private Long creatorId;               // v2.9.134: 선택된 크리에이터 ID (페이지 새로고침 시 복원용)
        private String creatorName;           // v2.9.134: 크리에이터 이름
        private String referenceImageUrl;     // v2.9.84: 참조 이미지 presigned URL (채팅 페이지에서 표시용)
    }

    /**
     * 메시지
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;     // "user" or "assistant"
        private String content;
        private String messageType;  // v2.9.27: VIDEO_RESULT, THUMBNAIL_RESULT 등
        private String metadata;     // v2.9.27: JSON 메타데이터 (URL, 제목 등)
        private LocalDateTime createdAt;
    }

    /**
     * 콘텐츠 생성 상태
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentStatus {
        private boolean scenarioReady;    // 시나리오 다운로드 가능
        private boolean imagesReady;      // 이미지 다운로드 가능
        private boolean audioReady;       // 오디오 다운로드 가능
        private boolean videoReady;       // 영상 다운로드 가능

        private String scenarioTitle;     // 시나리오 제목
        private Integer slideCount;       // 슬라이드 개수
        private Integer imageCount;       // 생성된 이미지 개수
        private Integer audioDuration;    // 오디오 총 길이 (초)
        private Integer videoDuration;    // 영상 총 길이 (초)

        // 진행 중 상태
        private String currentProcess;    // 현재 진행 중인 작업 (null이면 대기 중)
        private Integer progress;         // 진행률 (0-100)
        private String progressMessage;   // 진행 메시지
    }

    /**
     * v2.9.150: 사용자에게 연결된 크리에이터 정보
     * 계정에 1:1로 매핑된 크리에이터 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedCreatorInfo {
        private Long creatorId;
        private String creatorCode;
        private String creatorName;
        private String nationCode;       // v2.9.174: 국가 코드 (KR, JP, US 등)
        private String description;
        private String placeholderText;
        private String tierCode;
        private Boolean allowImageUpload;
    }

    /**
     * 채팅 단계 (v2.9.0: 실패/재생성 상태 추가)
     */
    public enum Stage {
        CHATTING,            // 대화 중
        SCENARIO_READY,      // 시나리오 생성 가능
        SCENARIO_GENERATING, // v2.9.75: 시나리오 생성 중
        SCENARIO_DONE,       // 시나리오 완료
        PREVIEWS_GENERATING, // v2.7.2: 씬 프리뷰 생성 중
        PREVIEWS_DONE,       // v2.5.0: 씬 프리뷰 완료 (나레이션 편집 가능)
        SCENES_GENERATING,   // v2.7.2: 씬 생성 중 (레거시)
        SCENES_REVIEW,       // v2.7.2: 씬 검토 중
        SCENE_REGENERATING,  // v2.9.0: 개별 씬 재생성 중
        TTS_GENERATING,      // v2.7.2: TTS 생성 중
        TTS_DONE,            // v2.5.0: TTS/자막 완료
        TTS_PARTIAL_FAILED,  // v2.9.0: TTS 일부 실패 (재시도 필요)
        IMAGES_GENERATING,   // v2.7.2: 이미지 생성 중
        IMAGES_DONE,         // 이미지 완료
        AUDIO_GENERATING,    // v2.7.2: 오디오 생성 중
        AUDIO_DONE,          // 오디오 완료
        VIDEO_GENERATING,    // v2.7.2: 영상 생성 중
        VIDEO_FAILED,        // v2.9.0: 영상 합성 실패 (재시도 가능)
        VIDEO_DONE           // 영상 완료
    }
}
