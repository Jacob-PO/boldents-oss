package com.aivideo.api.util;

import lombok.extern.slf4j.Slf4j;

/**
 * URL 무결성 검증 유틸리티 (v2.6.1)
 * - DB에 저장되는 URL이 유효한 형식인지 검증
 * - ERROR: 등 잘못된 데이터 저장 방지
 */
@Slf4j
public class UrlValidator {

    // 유효한 URL 프리픽스 목록
    private static final String[] VALID_PREFIXES = {
            "http://",
            "https://",
            "content/",      // S3 key
            "/tmp/",         // 로컬 임시 경로
            "/var/",         // 로컬 경로
            "/home/",        // 로컬 경로
            "/Users/"        // macOS 로컬 경로
    };

    // 무효한 URL 프리픽스 목록 (에러 메시지 등)
    private static final String[] INVALID_PREFIXES = {
            "ERROR:",
            "error:",
            "FAILED:",
            "failed:",
            "Exception:",
            "null"
    };

    /**
     * URL이 유효한 형식인지 검증
     * @param url 검증할 URL
     * @return 유효하면 true, 무효하면 false
     */
    public static boolean isValid(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String trimmedUrl = url.trim();

        // 무효한 프리픽스 검사
        for (String invalidPrefix : INVALID_PREFIXES) {
            if (trimmedUrl.startsWith(invalidPrefix)) {
                log.warn("[UrlValidator] Invalid URL detected (starts with '{}'): {}",
                        invalidPrefix, truncate(trimmedUrl, 100));
                return false;
            }
        }

        // 유효한 프리픽스 검사
        for (String validPrefix : VALID_PREFIXES) {
            if (trimmedUrl.startsWith(validPrefix)) {
                return true;
            }
        }

        // 알 수 없는 형식 경고
        log.warn("[UrlValidator] Unknown URL format: {}", truncate(trimmedUrl, 100));
        return false;
    }

    /**
     * URL이 유효하면 반환, 무효하면 null 반환
     * @param url 검증할 URL
     * @return 유효한 URL 또는 null
     */
    public static String validateOrNull(String url) {
        return isValid(url) ? url : null;
    }

    /**
     * URL이 유효하면 반환, 무효하면 예외 발생
     * @param url 검증할 URL
     * @param fieldName 필드명 (에러 메시지용)
     * @return 유효한 URL
     * @throws IllegalArgumentException URL이 무효한 경우
     */
    public static String validateOrThrow(String url, String fieldName) {
        if (!isValid(url)) {
            throw new IllegalArgumentException(
                    String.format("Invalid %s: %s", fieldName, truncate(url, 100)));
        }
        return url;
    }

    /**
     * URL이 S3 key 형식인지 확인
     * @param url 검증할 URL
     * @return S3 key이면 true
     */
    public static boolean isS3Key(String url) {
        return url != null && url.startsWith("content/");
    }

    /**
     * URL이 presigned URL인지 확인 (만료될 수 있는 URL)
     * @param url 검증할 URL
     * @return presigned URL이면 true
     */
    public static boolean isPresignedUrl(String url) {
        return url != null && url.contains("X-Amz-");
    }

    /**
     * 문자열 자르기 (로깅용)
     */
    private static String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
