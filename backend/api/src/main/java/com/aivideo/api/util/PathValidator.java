package com.aivideo.api.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * 파일 경로 및 URL 보안 검증 유틸리티 (v2.9.11)
 * - Path Traversal 공격 방지
 * - Command Injection 방지
 * - 허용된 디렉토리 내에서만 파일 접근 허용
 * - URL XSS/Open Redirect 방지 (v2.9.11)
 */
@Slf4j
public class PathValidator {

    // 허용된 작업 디렉토리 목록
    private static final String[] ALLOWED_DIRECTORIES = {
            "/tmp/aivideo",
            System.getProperty("java.io.tmpdir")
    };

    // 금지된 경로 패턴
    private static final String[] FORBIDDEN_PATTERNS = {
            "..",           // Path traversal
            "//",           // Double slash
            "\0",           // Null byte
            "\n",           // Newline
            "\r",           // Carriage return
            ";",            // Command separator
            "|",            // Pipe
            "&",            // Background/AND
            "$(",           // Command substitution
            "`"             // Backtick command substitution
    };

    /**
     * 경로가 안전한지 검증
     * @param path 검증할 경로
     * @return 안전하면 true
     */
    public static boolean isSafe(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        // 금지된 패턴 검사
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (path.contains(pattern)) {
                log.warn("[PathValidator] Forbidden pattern '{}' in path: {}",
                        pattern, truncate(path, 100));
                return false;
            }
        }

        return true;
    }

    /**
     * 경로가 허용된 디렉토리 내에 있는지 검증 (v2.9.13: symlink 검증 추가)
     * @param path 검증할 경로
     * @return 허용된 디렉토리 내에 있으면 true
     */
    public static boolean isWithinAllowedDirectory(Path path) {
        if (path == null) {
            return false;
        }

        try {
            // 정규화된 절대 경로로 변환
            Path normalizedPath = path.toAbsolutePath().normalize();
            String pathStr = normalizedPath.toString();

            // v2.9.13: 파일이 존재하면 심볼릭 링크 해석 후 재검증
            if (Files.exists(normalizedPath)) {
                try {
                    Path realPath = normalizedPath.toRealPath();
                    String realPathStr = realPath.toString();

                    // 실제 경로도 허용된 디렉토리 내에 있는지 확인
                    boolean realPathAllowed = false;
                    for (String allowedDir : ALLOWED_DIRECTORIES) {
                        if (allowedDir != null && realPathStr.startsWith(allowedDir)) {
                            realPathAllowed = true;
                            break;
                        }
                    }

                    if (!realPathAllowed) {
                        log.warn("[PathValidator] Symlink traversal detected! normalized: {}, real: {}",
                                truncate(pathStr, 100), truncate(realPathStr, 100));
                        return false;
                    }
                } catch (IOException e) {
                    log.warn("[PathValidator] Failed to resolve real path: {}", truncate(pathStr, 100));
                    return false;
                }
            }

            // 정규화된 경로가 허용된 디렉토리 내에 있는지 확인
            for (String allowedDir : ALLOWED_DIRECTORIES) {
                if (allowedDir != null && pathStr.startsWith(allowedDir)) {
                    return true;
                }
            }

            log.warn("[PathValidator] Path outside allowed directories: {}",
                    truncate(pathStr, 100));
            return false;
        } catch (Exception e) {
            log.error("[PathValidator] Failed to validate path: {}", path, e);
            return false;
        }
    }

    /**
     * 경로가 안전하고 허용된 디렉토리 내에 있는지 검증
     * @param pathStr 검증할 경로 문자열
     * @return 안전하고 허용된 경우 true
     */
    public static boolean validate(String pathStr) {
        if (!isSafe(pathStr)) {
            return false;
        }
        return isWithinAllowedDirectory(Paths.get(pathStr));
    }

    /**
     * 경로 검증 후 Path 객체 반환, 실패 시 예외
     * @param pathStr 검증할 경로 문자열
     * @return 검증된 Path 객체
     * @throws SecurityException 검증 실패 시
     */
    public static Path validateAndGet(String pathStr) {
        if (!isSafe(pathStr)) {
            throw new SecurityException("Unsafe path detected: " + truncate(pathStr, 50));
        }

        Path path = Paths.get(pathStr).toAbsolutePath().normalize();

        if (!isWithinAllowedDirectory(path)) {
            throw new SecurityException("Path outside allowed directory: " + truncate(pathStr, 50));
        }

        return path;
    }

    /**
     * FFmpeg 명령어에서 사용할 경로 검증
     * @param path 검증할 Path
     * @return 검증된 경로 문자열
     * @throws SecurityException 검증 실패 시
     */
    public static String validateForFFmpeg(Path path) {
        if (path == null) {
            throw new SecurityException("Path is null");
        }

        String pathStr = path.toAbsolutePath().normalize().toString();

        if (!isSafe(pathStr)) {
            throw new SecurityException("Unsafe path for FFmpeg: " + truncate(pathStr, 50));
        }

        if (!isWithinAllowedDirectory(path)) {
            throw new SecurityException("FFmpeg path outside allowed directory");
        }

        return pathStr;
    }

    /**
     * FFmpeg 명령어 인자 목록 검증 (v2.9.12)
     * - 절대경로 (/로 시작): 허용된 디렉토리 내 검증
     * - 상대경로 (../ 포함): 거부
     * - FFmpeg 옵션 (-로 시작): 통과
     * @param args 명령어 인자 목록
     * @throws SecurityException 위험한 경로 발견 시
     */
    public static void validateCommandArgs(java.util.List<String> args) {
        if (args == null) return;

        for (String arg : args) {
            if (arg == null || arg.isEmpty()) continue;

            // FFmpeg 옵션은 건너뜀 (-i, -c:v, -filter_complex 등)
            if (arg.startsWith("-")) continue;

            // 숫자만 있는 인자 건너뜀 (해상도, 비트레이트 등)
            if (arg.matches("^[0-9:x]+$")) continue;

            // 코덱/포맷 이름 건너뜀 (aac, libx264, mp4 등)
            if (arg.matches("^[a-z0-9_]+$") && !arg.contains("/") && !arg.contains(".")) continue;

            // 상대경로 검사 (../ 포함 시 거부) - 가장 중요!
            if (arg.contains("..")) {
                log.error("[PathValidator] 상대경로 탐색 시도 차단: {}", truncate(arg, 50));
                throw new SecurityException("상대경로 접근 거부: " + truncate(arg, 50));
            }

            // 절대경로 (/로 시작): 허용된 디렉토리 내 검증
            if (arg.startsWith("/") && !arg.equals("/dev/null")) {
                validateAndGet(arg);
            }
        }
    }

    /**
     * 문자열 자르기 (로깅용)
     */
    private static String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    // ========== URL 검증 (v2.9.11) ==========

    // 허용된 URL 스킴
    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    // 허용된 리다이렉트 도메인 (S3, CloudFront 등)
    private static final Set<String> ALLOWED_REDIRECT_DOMAINS = Set.of(
            "s3.amazonaws.com",
            "s3.ap-southeast-2.amazonaws.com",
            "storage.googleapis.com",
            "cloudfront.net"
    );

    // 금지된 URL 패턴 - XSS 방지 (전체 URL 검사)
    private static final String[] FORBIDDEN_XSS_PATTERNS = {
            "javascript:",      // XSS
            "data:",            // XSS
            "vbscript:",        // XSS
            "file:",            // Local file access
            "<script",          // XSS
            "%3Cscript",        // URL-encoded XSS
            "onclick",          // XSS event
            "onerror"           // XSS event
    };

    // 금지된 호스트 패턴 - SSRF 방지 (호스트 부분만 검사)
    private static final String[] FORBIDDEN_HOST_PATTERNS = {
            "localhost",        // SSRF
            "127.0.0.1",        // SSRF
            "0.0.0.0",          // SSRF
            "169.254.",         // AWS metadata SSRF
            "[::1]"             // IPv6 localhost
    };

    // 금지된 호스트 IP 대역 (CIDR 스타일 - 시작 부분 검사)
    private static final String[] FORBIDDEN_HOST_IP_PREFIXES = {
            "10.",              // Internal network Class A
            "192.168.",         // Internal network Class C
            "172.16.", "172.17.", "172.18.", "172.19.",  // Internal network Class B
            "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.",
            "172.28.", "172.29.", "172.30.", "172.31."
    };

    /**
     * URL이 안전한지 검증 (XSS 방지 - 전체 URL 검사)
     * @param urlStr 검증할 URL
     * @return 안전하면 true
     */
    public static boolean isUrlSafe(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return false;
        }

        String lowerUrl = urlStr.toLowerCase();

        // XSS 패턴 검사 (전체 URL)
        for (String pattern : FORBIDDEN_XSS_PATTERNS) {
            if (lowerUrl.contains(pattern.toLowerCase())) {
                log.warn("[PathValidator] Forbidden XSS pattern '{}' detected: {}",
                        pattern, truncate(urlStr, 100));
                return false;
            }
        }

        return true;
    }

    /**
     * 호스트가 안전한지 검증 (SSRF 방지 - 호스트 부분만 검사)
     * @param host 검증할 호스트
     * @return 안전하면 true
     */
    private static boolean isHostSafe(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }

        String lowerHost = host.toLowerCase();

        // 금지된 호스트 패턴 검사
        for (String pattern : FORBIDDEN_HOST_PATTERNS) {
            if (lowerHost.contains(pattern.toLowerCase())) {
                log.warn("[PathValidator] Forbidden SSRF host pattern '{}' detected: {}", pattern, host);
                return false;
            }
        }

        // 내부 IP 대역 검사 (호스트가 IP로 시작하는 경우)
        for (String prefix : FORBIDDEN_HOST_IP_PREFIXES) {
            if (lowerHost.startsWith(prefix)) {
                log.warn("[PathValidator] Forbidden internal IP detected: {}", host);
                return false;
            }
        }

        return true;
    }

    /**
     * 리다이렉트 URL이 허용된 도메인인지 검증 (Open Redirect, SSRF 방지)
     * @param urlStr 검증할 URL
     * @return 허용된 도메인이면 true
     */
    public static boolean isAllowedRedirectUrl(String urlStr) {
        // XSS 패턴 검사
        if (!isUrlSafe(urlStr)) {
            return false;
        }

        try {
            URL url = new URI(urlStr).toURL();
            String scheme = url.getProtocol().toLowerCase();
            String host = url.getHost().toLowerCase();

            // 스킴 검증
            if (!ALLOWED_SCHEMES.contains(scheme)) {
                log.warn("[PathValidator] Disallowed URL scheme: {}", scheme);
                return false;
            }

            // SSRF 방지: 호스트 검증 (내부 IP, localhost 등 차단)
            if (!isHostSafe(host)) {
                return false;
            }

            // 도메인 검증 (정확히 일치하거나 서브도메인)
            for (String allowedDomain : ALLOWED_REDIRECT_DOMAINS) {
                if (host.equals(allowedDomain) || host.endsWith("." + allowedDomain)) {
                    return true;
                }
            }

            log.warn("[PathValidator] URL host not in allowed list: {}", host);
            return false;

        } catch (Exception e) {
            log.warn("[PathValidator] Failed to parse URL: {}", truncate(urlStr, 100), e);
            return false;
        }
    }

    /**
     * 리다이렉트 URL 검증 후 반환, 실패 시 예외
     * @param urlStr 검증할 URL
     * @return 검증된 URL 문자열
     * @throws SecurityException 검증 실패 시
     */
    public static String validateRedirectUrl(String urlStr) {
        if (!isAllowedRedirectUrl(urlStr)) {
            throw new SecurityException("Invalid redirect URL: " + truncate(urlStr, 50));
        }
        return urlStr;
    }
}
