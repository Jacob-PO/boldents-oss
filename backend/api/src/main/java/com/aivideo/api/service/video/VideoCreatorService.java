package com.aivideo.api.service.video;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.common.enums.QualityTier;

import java.util.List;

/**
 * 영상 생성 및 합성 서비스
 * Veo API를 활용하여 오프닝 영상 생성
 * FFmpeg를 활용하여 최종 영상 합성
 */
public interface VideoCreatorService {

    /**
     * 오프닝 영상 생성 (8초 - Veo 3.1 API 고정)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param opening 오프닝 씬 정보
     * @param tier 품질 티어
     * @param characterBlock 캐릭터 Identity Block
     * @param creatorId 장르 ID (null이면 기본 장르 사용)
     * @param scenarioInfo 전체 시나리오 정보 (슬라이드 포함)
     * @return 생성된 영상 URL
     */
    String generateOpeningVideo(Long userNo, VideoDto.OpeningScene opening, QualityTier tier, String characterBlock, Long creatorId, VideoDto.ScenarioInfo scenarioInfo);

    /**
     * 최종 영상 합성
     * @param openingVideoUrl 오프닝 영상 URL
     * @param imageUrls 슬라이드 이미지 URL 리스트
     * @param narrationUrl TTS 나레이션 URL
     * @param scenario 시나리오 정보
     * @return 합성된 최종 영상 URL
     */
    String composeVideo(String openingVideoUrl, List<String> imageUrls, String narrationUrl, VideoDto.ScenarioInfo scenario);
}
