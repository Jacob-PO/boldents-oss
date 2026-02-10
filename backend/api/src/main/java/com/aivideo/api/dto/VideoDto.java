package com.aivideo.api.dto;

import com.aivideo.common.enums.ContentType;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.enums.VideoStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

public class VideoDto {

    /**
     * 영상 생성 요청
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReqCreate {
        private String prompt;           // 사용자 프롬프트
        private QualityTier tier;        // 품질 티어 (STANDARD/PREMIUM)
        private ContentType contentType; // 콘텐츠 유형 (기본: YOUTUBE_SCENARIO)
        private String language;         // 언어 (ko, en, ja 등)
    }

    /**
     * 영상 생성 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResCreate {
        private Long videoId;
        private String jobId;           // 비동기 작업 ID
        private VideoStatus status;
        private String message;
    }

    /**
     * 영상 상태 조회 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResStatus {
        private Long videoId;
        private VideoStatus status;
        private int progress;           // 진행률 (0-100)
        private String currentStep;     // 현재 단계 코드 (OPENING, IMAGES, TTS, COMPOSING 등)
        private String stepDescription; // 사용자 친화적 단계 설명 (예: "이미지 생성 중 (5/20)")
        private String stepDetail;      // 세부 진행 상황 (예: "캐릭터의 첫 만남 장면을 그리고 있어요")
        private Integer currentIndex;   // 현재 처리 중인 항목 인덱스
        private Integer totalCount;     // 총 처리해야 할 항목 수
        private String videoUrl;        // 완료 시 영상 URL
        private String thumbnailUrl;    // 썸네일 URL
        private String errorMessage;    // 에러 메시지 (실패 시)
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
    }

    /**
     * 캐릭터 Identity Block - 일관된 캐릭터 생성을 위한 핵심 정보
     * PROMPT_ENGINEERING_GUIDE.md 참조
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterIdentity {
        private String name;            // 캐릭터 이름 (예: "수아", "민준")
        private String role;            // 역할 (예: "여주인공", "재벌 2세")
        private String age;             // 나이대 (예: "20대 후반")
        private String gender;          // 성별

        // Physical Traits (물리적 특성) - 절대 변하지 않는 요소
        private String faceShape;       // 얼굴형 (예: "V라인 갸름한 얼굴")
        private String skinTone;        // 피부톤 (예: "하얀 도자기 피부")
        private String eyeShape;        // 눈 모양 (예: "크고 또렷한 쌍커풀 눈")
        private String hairStyle;       // 헤어스타일 (예: "긴 웨이브 검은 머리")
        private String bodyType;        // 체형 (예: "슬림하고 키가 큰 체형")

        // Signature Look (시그니처 룩)
        private String fashionStyle;    // 패션 스타일 (예: "고급 명품 드레스")
        private String makeup;          // 메이크업 (예: "진한 스모키 아이, 레드립")
        private String accessories;     // 액세서리 (예: "다이아몬드 귀걸이")

        // Distinguishing Features (구별되는 특징)
        private String uniqueFeature;   // 고유 특징 (예: "왼쪽 볼의 점")
        private String expression;      // 기본 표정 (예: "차가운 눈빛")
        private String aura;            // 분위기 (예: "고혹적이고 신비로운")

        // Identity Block 프롬프트로 변환
        public String toIdentityBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("[CHARACTER: ").append(name).append("]\n");
            sb.append("Korean woman, ").append(age).append(", ");
            sb.append(faceShape).append(", ");
            sb.append(skinTone).append(", ");
            sb.append(eyeShape).append(", ");
            sb.append(hairStyle).append(", ");
            sb.append(bodyType).append(".\n");
            sb.append("Wearing ").append(fashionStyle);
            if (accessories != null && !accessories.isEmpty()) {
                sb.append(", ").append(accessories);
            }
            sb.append(".\n");
            sb.append(makeup).append(".\n");
            if (uniqueFeature != null && !uniqueFeature.isEmpty()) {
                sb.append("Distinguished by ").append(uniqueFeature).append(".\n");
            }
            sb.append(expression).append(", ").append(aura).append(" aura.");
            return sb.toString();
        }
    }

    /**
     * 시나리오 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioInfo {
        private Long scenarioId;        // 시나리오 ID
        private String title;           // 영상 제목
        private String description;     // 영상 설명
        private String characterBlock;  // 캐릭터 Identity Block (프롬프트용 텍스트)
        private List<CharacterIdentity> characters; // 등장인물 Identity Block (구조화된 데이터)
        private OpeningScene opening;   // 오프닝 씬 (8초 영상 - Veo 3.1 API 고정)
        private List<SlideScene> slides; // 이미지 슬라이드 씬들
        private String fullNarration;   // 전체 나레이션 텍스트
        private BgmOption bgm;          // 배경음악 옵션

        // v2.9.84: 참조 이미지 분석 결과 (JSON) - 멀티모달 콘텐츠 생성용
        private String referenceImageAnalysis;

        // v2.9.90: 참조 이미지 멀티모달 지원 (Veo 3.1 최대 3장, Gemini Image 최대 14장)
        private List<String> referenceImagesBase64;    // 참조 이미지 Base64 인코딩 데이터 리스트
        private List<String> referenceImagesMimeTypes; // 참조 이미지 MIME 타입 리스트
    }

    /**
     * 시나리오 수정 요청
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReqUpdateScenario {
        private String title;           // 수정할 제목
        private OpeningScene opening;   // 수정할 오프닝 씬
        private List<SlideScene> slides; // 수정할 슬라이드들
        private BgmOption bgm;          // 배경음악 옵션
    }

    /**
     * 배경음악 옵션
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BgmOption {
        private boolean enabled;        // BGM 사용 여부
        private String bgmUrl;          // 사용자 업로드 BGM URL (옵션)
        private String bgmPreset;       // 프리셋 BGM 선택 (calm, upbeat, dramatic 등)
        private double volume;          // 볼륨 (0.0 ~ 1.0, 기본 0.3)
    }

    /**
     * 오프닝 씬 (8초 동영상 - Veo 3.1 API 고정)
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpeningScene {
        private String videoPrompt;     // Veo 생성용 프롬프트
        private String narration;       // 나레이션 텍스트
        private int durationSeconds;    // 길이 (8초 - Veo API 고정)
    }

    /**
     * 슬라이드 씬 (이미지)
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlideScene {
        private int order;              // 순서
        private String imagePrompt;     // Imagen 생성용 프롬프트
        private String narration;       // 나레이션 텍스트
        private int durationSeconds;    // 표시 시간 (TTS 오디오 길이로 자동 설정)
        private String transition;      // 트랜지션 효과 (fade, slide 등)
    }

    /**
     * 영상 목록 조회 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResVideoItem {
        private Long videoId;
        private String title;
        private String thumbnailUrl;
        private VideoStatus status;
        private QualityTier tier;
        private ContentType contentType;
        private int durationSeconds;
        private LocalDateTime createdAt;
    }
}
