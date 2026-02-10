package com.aivideo.api.service.subtitle;

import com.aivideo.api.dto.VideoDto;

import java.util.List;

/**
 * 자막 생성 서비스 인터페이스
 * 한국 드라마 스타일 큰 자막 생성
 */
public interface SubtitleService {

    /**
     * 슬라이드별 나레이션에서 ASS 자막 파일 생성
     *
     * @param slides      슬라이드 목록 (나레이션, 시간 정보 포함)
     * @param openingText 오프닝 나레이션 텍스트
     * @param openingDuration 오프닝 영상 길이 (초)
     * @return ASS 형식 자막 파일 내용
     */
    String generateSubtitles(List<VideoDto.SlideScene> slides, String openingText, int openingDuration);

    /**
     * 단일 텍스트에서 자막 라인 생성
     *
     * @param text      자막 텍스트
     * @param startTime 시작 시간 (초)
     * @param endTime   종료 시간 (초)
     * @return ASS 형식 자막 라인
     */
    String generateSubtitleLine(String text, double startTime, double endTime);

    /**
     * SRT 형식으로 자막 생성 (호환성용)
     *
     * @param slides      슬라이드 목록
     * @param openingText 오프닝 나레이션
     * @param openingDuration 오프닝 영상 길이 (초)
     * @return SRT 형식 자막 파일 내용
     */
    String generateSrtSubtitles(List<VideoDto.SlideScene> slides, String openingText, int openingDuration);

    /**
     * v2.9.175: 개별 씬용 ASS 자막 생성 (음성 구간 기반 타이밍 + 자막 템플릿 + 폰트/언어 지원)
     * ContentService에서 분리된 핵심 자막 생성 메서드
     *
     * @param narration           나레이션 텍스트
     * @param duration            씬 길이 (초, Math.ceil 정수)
     * @param speechSegments      음성 구간 리스트 (null이면 글자 수 기반 폴백)
     * @param formatId            영상 포맷 ID (해상도 결정용, null이면 기본 16:9)
     * @param videoSubtitleId     자막 템플릿 ID (null이면 기본 자막=1 사용)
     * @param fontSizeLevel       자막 글자 크기 (1=small, 2=medium, 3=large, null이면 3=large)
     * @param subtitlePosition    자막 위치 (1=하단, 2=중앙, 3=상단, null이면 1=하단)
     * @param fontId              폰트 ID (v2.9.174, null이면 템플릿 기본 폰트 사용)
     * @param creatorId           크리에이터 ID (v2.9.174, 언어별 자막 설정용)
     * @param actualAudioDuration 실제 TTS 오디오 길이 (초, v2.9.175, null이면 duration 사용)
     * @return ASS 형식 자막 내용
     */
    String generateSceneSubtitle(String narration, Integer duration, List<double[]> speechSegments, Long formatId, Long videoSubtitleId, Integer fontSizeLevel, Integer subtitlePosition, Long fontId, Long creatorId, Double actualAudioDuration);
}
