package com.aivideo.api.service.image;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.common.enums.QualityTier;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 이미지 생성 서비스
 * Gemini Image API를 활용하여 슬라이드 이미지 생성
 */
public interface ImageGeneratorService {

    /**
     * v2.5.8: 시나리오 컨텍스트 - 전체 스토리 일관성을 위한 정보
     * 모든 이미지/영상 생성 시 이 정보가 포함되어야 함
     * v2.9.84: 참조 이미지 멀티모달 지원 추가
     */
    @Getter
    @Builder
    class ScenarioContext {
        private String title;           // 시나리오 제목
        private String hook;            // 후킹 멘트 (스토리 요약)
        private String characterBlock;  // 캐릭터 Identity Block
        private int totalSlides;        // 전체 슬라이드 수
        private String storyOutline;    // 전체 스토리 개요 (각 씬 나레이션 요약)

        // v2.9.90: 참조 이미지 멀티모달 지원 (다중 이미지)
        private List<String> referenceImagesBase64;    // 참조 이미지 Base64 인코딩 데이터 리스트
        private List<String> referenceImagesMimeTypes; // 참조 이미지 MIME 타입 리스트
        private String referenceImageAnalysis;  // 참조 이미지 AI 분석 결과 (JSON)
    }

    /**
     * 슬라이드 이미지 생성 (캐릭터 Identity Block 포함)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param slides 슬라이드 씬 리스트
     * @param tier 품질 티어
     * @param characterBlock 캐릭터 Identity Block (일관된 캐릭터 생성용)
     * @return 생성된 이미지 URL 리스트
     * @deprecated Use generateImages with ScenarioContext instead
     */
    @Deprecated
    List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, String characterBlock);

    /**
     * v2.5.8: 슬라이드 이미지 생성 (전체 시나리오 컨텍스트 포함)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param slides 슬라이드 씬 리스트
     * @param tier 품질 티어
     * @param context 시나리오 컨텍스트 (전체 스토리 정보)
     * @return 생성된 이미지 URL 리스트
     */
    default List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, ScenarioContext context) {
        // 기본 구현: 기존 메서드 호출 (하위 호환성)
        return generateImages(userNo, slides, tier, context != null ? context.getCharacterBlock() : null);
    }

    /**
     * v2.8.0: 슬라이드 이미지 생성 (장르 기반)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param slides 슬라이드 씬 리스트
     * @param tier 품질 티어
     * @param context 시나리오 컨텍스트 (전체 스토리 정보)
     * @param creatorId 장르 ID (null이면 기본 장르 사용)
     * @return 생성된 이미지 URL 리스트
     */
    default List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, ScenarioContext context, Long creatorId) {
        // 기본 구현: 기존 메서드 호출 (하위 호환성)
        return generateImages(userNo, slides, tier, context);
    }

    /**
     * v2.9.38: 영상 포맷 ID 설정 (썸네일 생성 시 aspect ratio 결정용)
     * @param formatId 영상 포맷 ID (1=유튜브 일반 16:9, 2=유튜브 쇼츠 9:16 등)
     */
    void setCurrentFormatId(Long formatId);

    /**
     * v2.9.63: 썸네일 모드 활성화 (고급 모델 사용)
     * 썸네일은 장르와 관계없이 Premium 모델로 고해상도(2K) 이미지 생성
     */
    void enableThumbnailMode();

    /**
     * v2.9.63: 썸네일 모드 비활성화 (장르별 모델 복귀)
     */
    void disableThumbnailMode();
}
