package com.aivideo.api.service.storage;

import java.io.InputStream;

/**
 * 파일 저장소 서비스 인터페이스
 * S3 또는 로컬 파일 시스템을 추상화
 */
public interface StorageService {

    /**
     * 파일 업로드
     * @param key S3 키 (파일 경로)
     * @param data 파일 데이터
     * @param contentType MIME 타입
     * @return 저장된 파일의 URL 또는 키
     */
    String upload(String key, byte[] data, String contentType);

    /**
     * InputStream으로 파일 업로드
     * @param key S3 키 (파일 경로)
     * @param inputStream 파일 스트림
     * @param contentType MIME 타입
     * @param contentLength 파일 크기
     * @return 저장된 파일의 URL 또는 키
     */
    String upload(String key, InputStream inputStream, String contentType, long contentLength);

    /**
     * 파일 다운로드
     * @param key S3 키
     * @return 파일 데이터
     */
    byte[] download(String key);

    /**
     * Presigned URL 생성 (다운로드용)
     * @param key S3 키
     * @return 임시 다운로드 URL (30분 유효)
     */
    String generatePresignedUrl(String key);

    /**
     * 파일 삭제
     * @param key S3 키
     */
    void delete(String key);

    /**
     * 파일 존재 여부 확인
     * @param key S3 키
     * @return 존재 여부
     */
    boolean exists(String key);

    /**
     * S3가 활성화되어 있는지 확인
     * @return 활성화 여부
     */
    boolean isEnabled();
}
