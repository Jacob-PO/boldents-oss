package com.aivideo.api.service.scenario;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.common.enums.ContentType;
import com.aivideo.common.enums.QualityTier;

/**
 * 시나리오 생성 서비스
 * Gemini API를 활용하여 사용자 프롬프트를 영상 시나리오로 변환
 */
public interface ScenarioGeneratorService {

    /**
     * 시나리오 생성 (장르 기반 + 진행 상황 추적)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param prompt 사용자 프롬프트
     * @param tier 품질 티어
     * @param contentType 콘텐츠 유형
     * @param slideCount 슬라이드 수 (1-10)
     * @param creatorId 장르 ID (null이면 기본 장르 사용)
     * @param chatId 채팅 ID (진행 상황 추적용)
     * @return 생성된 시나리오 정보
     */
    VideoDto.ScenarioInfo generateScenario(Long userNo, String prompt, QualityTier tier, ContentType contentType, int slideCount, Long creatorId, Long chatId);

    /**
     * v2.9.84: 시나리오 생성 (참조 이미지 분석 결과 포함)
     * 참조 이미지의 캐릭터/스타일/분위기 정보를 시나리오에 반영
     *
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param prompt 사용자 프롬프트
     * @param tier 품질 티어
     * @param contentType 콘텐츠 유형
     * @param slideCount 슬라이드 수 (1-10)
     * @param creatorId 장르 ID (null이면 기본 장르 사용)
     * @param chatId 채팅 ID (진행 상황 추적용)
     * @param referenceImageAnalysis 참조 이미지 AI 분석 결과 (JSON)
     * @return 생성된 시나리오 정보
     */
    VideoDto.ScenarioInfo generateScenarioWithReferenceImage(
            Long userNo, String prompt, QualityTier tier, ContentType contentType,
            int slideCount, Long creatorId, Long chatId, String referenceImageAnalysis);
}
