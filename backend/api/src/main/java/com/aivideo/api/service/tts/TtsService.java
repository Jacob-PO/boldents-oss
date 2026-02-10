package com.aivideo.api.service.tts;

import com.aivideo.common.enums.QualityTier;
import java.util.List;

/**
 * TTS (Text-to-Speech) 서비스
 * 나레이션 음성 생성
 */
public interface TtsService {

    /**
     * 나레이션 생성 (장르별 TTS 지시사항 적용)
     * @param userNo 사용자 번호 (API 키 조회용)
     * @param text 나레이션 텍스트
     * @param tier 품질 티어
     * @param creatorId 장르 ID (TTS_INSTRUCTION 조회용)
     * @return 생성된 오디오 URL
     */
    String generateNarration(Long userNo, String text, QualityTier tier, Long creatorId);

    /**
     * 나레이션 생성 (레거시 - 기본 장르 사용)
     * @deprecated v2.8.2: creatorId를 전달하는 메서드 사용을 권장합니다.
     */
    @Deprecated
    String generateNarration(Long userNo, String text, QualityTier tier);

    /**
     * 오디오 파일의 실제 duration 측정 (초)
     */
    double getAudioDuration(String audioFilePath);

    /**
     * v2.9.3: 오디오 파일에서 음성 구간(speech segments) 감지
     * FFmpeg silencedetect 필터를 사용하여 침묵 구간을 찾고,
     * 그 사이의 음성 구간 시작/끝 시간을 반환
     *
     * @param audioFilePath 오디오 파일 경로
     * @return 음성 구간 리스트 (각 구간은 [시작시간, 끝시간] double 배열)
     */
    List<double[]> detectSpeechSegments(String audioFilePath);

    /**
     * v2.9.170: 오디오 파일에서 문장 경계를 정확히 감지
     * silencedetect의 침묵 duration을 분석하여 가장 긴 N-1개 침묵을 문장 경계로 사용
     * 기존 detectSpeechSegments() + mergeSegmentsToSentences() 조합의 근본적 부정확성 해결
     *
     * @param audioFilePath 오디오 파일 경로
     * @param sentenceCount 나레이션의 문장 수
     * @return 정확히 sentenceCount개의 음성 구간 [시작시간, 끝시간]
     */
    List<double[]> detectSentenceBoundaries(String audioFilePath, int sentenceCount);

    /**
     * v2.9.6: 오디오 템포를 조절하여 목표 길이에 맞춤
     * FFmpeg atempo 필터를 사용하여 오디오 속도 조절
     *
     * 사용 사례: 오프닝 나레이션을 정확히 8초에 맞춤
     *
     * @param audioFilePath 원본 오디오 파일 경로
     * @param targetDurationSeconds 목표 길이 (초)
     * @return 템포 조절된 오디오 파일 경로 (조절 불필요 시 원본 경로 반환)
     */
    String adjustAudioTempo(String audioFilePath, double targetDurationSeconds);
}
