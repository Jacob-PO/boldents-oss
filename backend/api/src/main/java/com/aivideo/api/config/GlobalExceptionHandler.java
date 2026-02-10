package com.aivideo.api.config;

import com.aivideo.common.dto.ApiResponse;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 전역 예외 처리기
 * - 모든 예외를 일관된 형식으로 처리
 * - 상세 로그 기록 (요청 ID, 타임스탬프, 스택 트레이스)
 * - 사용자 친화적 에러 메시지 반환
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * ApiException 처리 - 비즈니스 로직 예외
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e, WebRequest request) {
        String requestId = generateRequestId();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        ErrorCode errorCode = e.getErrorCode();

        // 상세 로그 기록
        log.error("=== API Exception ===");
        log.error("Request ID: {}", requestId);
        log.error("Timestamp: {}", timestamp);
        log.error("Error Code: {} ({})", errorCode.getCode(), errorCode.name());
        log.error("Message: {}", e.getMessage());
        log.error("Request URI: {}", request.getDescription(false));
        log.error("Stack Trace: ", e);
        log.error("=====================");

        // 사용자에게 반환할 메시지 구성
        String userMessage = buildUserMessage(errorCode, e.getMessage(), requestId);

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, userMessage));
    }

    /**
     * RuntimeException 처리 - 영상 생성 관련 예외
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e, WebRequest request) {
        String requestId = generateRequestId();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // 상세 로그 기록
        log.error("=== Runtime Exception ===");
        log.error("Request ID: {}", requestId);
        log.error("Timestamp: {}", timestamp);
        log.error("Exception Type: {}", e.getClass().getSimpleName());
        log.error("Message: {}", e.getMessage());
        log.error("Request URI: {}", request.getDescription(false));
        log.error("Stack Trace: ", e);
        log.error("=========================");

        // 에러 메시지 분석하여 적절한 ErrorCode 매핑
        ErrorCode errorCode = mapExceptionToErrorCode(e);
        String userMessage = buildUserMessageFromException(e, errorCode, requestId);

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, userMessage));
    }

    /**
     * 일반 Exception 처리 - 예상치 못한 예외
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, WebRequest request) {
        String requestId = generateRequestId();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // 상세 로그 기록
        log.error("=== Unexpected Exception ===");
        log.error("Request ID: {}", requestId);
        log.error("Timestamp: {}", timestamp);
        log.error("Exception Type: {}", e.getClass().getName());
        log.error("Message: {}", e.getMessage());
        log.error("Request URI: {}", request.getDescription(false));
        log.error("Stack Trace: ", e);
        log.error("============================");

        String userMessage = String.format(
            "서버 오류가 발생했습니다. [요청 ID: %s] 문제가 지속되면 관리자에게 문의해주세요.",
            requestId
        );

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR, userMessage));
    }

    /**
     * 요청 ID 생성 (오류 추적용)
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 사용자 메시지 구성
     */
    private String buildUserMessage(ErrorCode errorCode, String message, String requestId) {
        if (message != null && !message.equals(errorCode.getMessage())) {
            return String.format("%s [%s]", message, requestId);
        }
        return String.format("%s [%s]", errorCode.getMessage(), requestId);
    }

    /**
     * RuntimeException을 ErrorCode로 매핑
     */
    private ErrorCode mapExceptionToErrorCode(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // 오프닝 영상 관련
        if (message.contains("opening") || message.contains("오프닝")) {
            if (message.contains("timeout") || message.contains("timed out")) {
                return ErrorCode.VIDEO_OPENING_TIMEOUT;
            }
            if (message.contains("empty") || message.contains("비어")) {
                return ErrorCode.VIDEO_OPENING_EMPTY;
            }
            return ErrorCode.VIDEO_OPENING_GENERATION_FAILED;
        }

        // 이미지 관련
        if (message.contains("image") || message.contains("이미지")) {
            if (message.contains("safety") || message.contains("안전")) {
                return ErrorCode.IMAGE_SAFETY_BLOCKED;
            }
            if (message.contains("overload") || message.contains("503")) {
                return ErrorCode.IMAGE_API_OVERLOADED;
            }
            if (message.contains("no image data") || message.contains("no data")) {
                return ErrorCode.IMAGE_NO_DATA;
            }
            return ErrorCode.IMAGE_GENERATION_FAILED;
        }

        // TTS 관련
        if (message.contains("tts") || message.contains("음성") || message.contains("audio")) {
            return ErrorCode.TTS_GENERATION_FAILED;
        }

        // FFmpeg 관련
        if (message.contains("ffmpeg")) {
            if (message.contains("concat") || message.contains("합성")) {
                return ErrorCode.VIDEO_COMPOSITION_FAILED;
            }
            if (message.contains("subtitle") || message.contains("자막")) {
                return ErrorCode.VIDEO_SUBTITLE_FAILED;
            }
            return ErrorCode.VIDEO_FFMPEG_FAILED;
        }

        // 시나리오 관련
        if (message.contains("scenario") || message.contains("시나리오")) {
            if (message.contains("parse") || message.contains("json")) {
                return ErrorCode.SCENARIO_PARSING_FAILED;
            }
            if (message.contains("opening") || message.contains("오프닝")) {
                return ErrorCode.SCENARIO_MISSING_OPENING;
            }
            if (message.contains("narration") || message.contains("나레이션")) {
                return ErrorCode.SCENARIO_MISSING_NARRATION;
            }
            return ErrorCode.SCENARIO_GENERATION_FAILED;
        }

        // AI API 관련
        if (message.contains("api") || message.contains("gemini") || message.contains("veo")) {
            if (message.contains("quota") || message.contains("할당량")) {
                return ErrorCode.AI_QUOTA_EXCEEDED;
            }
            if (message.contains("timeout") || message.contains("timed out")) {
                return ErrorCode.AI_API_TIMEOUT;
            }
            if (message.contains("unavailable") || message.contains("503")) {
                return ErrorCode.AI_SERVICE_UNAVAILABLE;
            }
            if (message.contains("key") || message.contains("unauthorized") || message.contains("401")) {
                return ErrorCode.AI_API_KEY_INVALID;
            }
            if (message.contains("safety") || message.contains("blocked")) {
                return ErrorCode.AI_SAFETY_FILTER;
            }
        }

        // 씬 관련
        if (message.contains("scene") || message.contains("씬")) {
            if (message.contains("합성") || message.contains("없습니다")) {
                return ErrorCode.VIDEO_COMPOSITION_NO_SCENES;
            }
            return ErrorCode.VIDEO_SCENE_GENERATION_FAILED;
        }

        // 영상 관련
        if (message.contains("video") || message.contains("영상")) {
            return ErrorCode.VIDEO_GENERATION_FAILED;
        }

        return ErrorCode.INTERNAL_SERVER_ERROR;
    }

    /**
     * 예외로부터 사용자 친화적 메시지 생성
     */
    private String buildUserMessageFromException(RuntimeException e, ErrorCode errorCode, String requestId) {
        // 기술적 메시지를 사용자 친화적으로 변환
        String userFriendlyMessage = switch (errorCode) {
            case VIDEO_OPENING_TIMEOUT -> "오프닝 영상 생성에 시간이 너무 오래 걸렸습니다. 네트워크 상태를 확인 후 다시 시도해주세요.";
            case VIDEO_OPENING_EMPTY -> "오프닝 영상이 생성되지 않았습니다. 시나리오를 확인 후 다시 시도해주세요.";
            case VIDEO_OPENING_GENERATION_FAILED -> "오프닝 영상 생성에 실패했습니다. 프롬프트를 수정하거나 다시 시도해주세요.";
            case IMAGE_SAFETY_BLOCKED -> "콘텐츠 정책으로 인해 이미지를 생성할 수 없습니다. 시나리오 내용을 수정해주세요.";
            case IMAGE_API_OVERLOADED -> "이미지 생성 서비스가 혼잡합니다. 1-2분 후 다시 시도해주세요.";
            case TTS_GENERATION_FAILED -> "음성 생성에 실패했습니다. 나레이션 텍스트를 확인해주세요.";
            case VIDEO_FFMPEG_FAILED -> "영상 처리에 실패했습니다. 다시 시도해주세요.";
            case VIDEO_COMPOSITION_NO_SCENES -> "합성할 씬이 없습니다. 씬 생성을 먼저 완료해주세요.";
            case AI_QUOTA_EXCEEDED -> "AI 서비스 할당량을 초과했습니다. 잠시 후 다시 시도해주세요.";
            case AI_API_TIMEOUT -> "AI 서비스 응답이 지연되고 있습니다. 다시 시도해주세요.";
            case AI_SERVICE_UNAVAILABLE -> "AI 서비스에 일시적인 문제가 있습니다. 잠시 후 다시 시도해주세요.";
            case AI_API_KEY_INVALID -> "API 키가 유효하지 않습니다. 설정에서 API 키를 확인해주세요.";
            case AI_SAFETY_FILTER -> "콘텐츠 안전 정책에 의해 요청이 거부되었습니다. 시나리오 내용을 수정해주세요.";
            case SCENARIO_PARSING_FAILED -> "시나리오 생성 중 오류가 발생했습니다. 다시 시도해주세요.";
            case SCENARIO_MISSING_OPENING -> "오프닝 씬이 필요합니다. 시나리오를 다시 생성해주세요.";
            case SCENARIO_MISSING_NARRATION -> "나레이션이 누락되었습니다. 시나리오를 다시 생성해주세요.";
            default -> errorCode.getMessage();
        };

        return String.format("%s [요청 ID: %s]", userFriendlyMessage, requestId);
    }
}
