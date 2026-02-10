package com.aivideo.api.service.reference;

import org.springframework.web.multipart.MultipartFile;

/**
 * v2.9.84: 참조 이미지 서비스 인터페이스
 *
 * 클린 아키텍처 원칙:
 * - 인터페이스로 추상화하여 구현체 교체 용이
 * - 단일 책임: 참조 이미지 업로드 및 분석만 담당
 */
public interface ReferenceImageService {

    /**
     * 참조 이미지 유효성 검증
     *
     * @param image 업로드된 이미지 파일
     * @throws com.aivideo.api.exception.ApiException 검증 실패 시
     */
    void validateImage(MultipartFile image);

    /**
     * v2.9.89: 참조 이미지 S3 업로드 (채팅별 폴더)
     *
     * @param chatId 채팅 ID
     * @param image 업로드된 이미지 파일
     * @return S3 key (content/{chatId}/references/{timestamp}_{filename})
     */
    String uploadImage(Long chatId, MultipartFile image);

    /**
     * Gemini 멀티모달로 참조 이미지 분석
     * - 일반 장르: 캐릭터 외모, 스타일, 색상 팔레트, 분위기 등 추출
     * - REVIEW 장르: 상품 정보 (이름, 카테고리, 특징, 외관, 색상) 추출
     *
     * @param apiKey 사용자 Gemini API 키
     * @param imageBytes 이미지 바이트 배열
     * @param mimeType 이미지 MIME 타입
     * @param userPrompt 사용자 입력 프롬프트 (컨텍스트 제공)
     * @param creatorId 장르 ID (3=REVIEW인 경우 상품 분석)
     * @return JSON 형식의 분석 결과
     */
    String analyzeImage(String apiKey, byte[] imageBytes, String mimeType, String userPrompt, Long creatorId);

    /**
     * 이미지를 Base64로 인코딩
     *
     * @param imageBytes 이미지 바이트 배열
     * @return Base64 인코딩된 문자열
     */
    String encodeToBase64(byte[] imageBytes);

    /**
     * S3 key로부터 이미지 바이트 다운로드
     *
     * @param s3Key S3 key
     * @return 이미지 바이트 배열
     */
    byte[] downloadImage(String s3Key);

    /**
     * S3 key로부터 presigned URL 생성
     *
     * @param s3Key S3 key
     * @return presigned URL (30분 유효)
     */
    String generatePresignedUrl(String s3Key);
}
