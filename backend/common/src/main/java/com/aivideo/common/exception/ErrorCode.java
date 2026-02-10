package com.aivideo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "서버 내부 오류가 발생했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "C002", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C003", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C004", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "C005", "리소스를 찾을 수 없습니다."),

    // Video Generation - 시나리오
    SCENARIO_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V001", "시나리오 생성에 실패했습니다."),
    SCENARIO_PARSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V011", "시나리오 JSON 파싱에 실패했습니다. 다시 시도해주세요."),
    SCENARIO_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "V012", "시나리오 형식이 올바르지 않습니다."),
    SCENARIO_MISSING_OPENING(HttpStatus.BAD_REQUEST, "V013", "오프닝 씬이 필요합니다. 시나리오를 다시 생성해주세요."),
    SCENARIO_MISSING_NARRATION(HttpStatus.BAD_REQUEST, "V014", "나레이션이 없습니다. 시나리오를 다시 생성해주세요."),

    // Video Generation - 영상
    VIDEO_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V002", "영상 생성에 실패했습니다."),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "V006", "영상을 찾을 수 없습니다."),
    VIDEO_OPENING_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V021", "오프닝 영상 생성에 실패했습니다."),
    VIDEO_OPENING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "V022", "오프닝 영상 생성 시간이 초과되었습니다. 다시 시도해주세요."),
    VIDEO_OPENING_EMPTY(HttpStatus.INTERNAL_SERVER_ERROR, "V023", "오프닝 영상 파일이 비어있습니다."),
    VIDEO_SCENE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V024", "씬 영상 생성에 실패했습니다."),

    // Video Generation - 이미지
    IMAGE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V003", "이미지 생성에 실패했습니다."),
    IMAGE_SAFETY_BLOCKED(HttpStatus.BAD_REQUEST, "V031", "안전 정책으로 인해 이미지를 생성할 수 없습니다. 프롬프트를 수정해주세요."),
    IMAGE_API_OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "V032", "이미지 생성 서비스가 혼잡합니다. 잠시 후 다시 시도해주세요."),
    IMAGE_NO_DATA(HttpStatus.INTERNAL_SERVER_ERROR, "V033", "이미지 데이터를 받지 못했습니다."),

    // Video Generation - TTS
    TTS_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V004", "음성 생성에 실패했습니다."),
    TTS_TEXT_TOO_LONG(HttpStatus.BAD_REQUEST, "V041", "나레이션 텍스트가 너무 깁니다."),
    TTS_INVALID_VOICE(HttpStatus.BAD_REQUEST, "V042", "지원하지 않는 음성입니다."),

    // Video Generation - 합성
    VIDEO_COMPOSITION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V005", "영상 합성에 실패했습니다."),
    VIDEO_COMPOSITION_NO_SCENES(HttpStatus.BAD_REQUEST, "V051", "합성할 씬 영상이 없습니다."),
    VIDEO_FFMPEG_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V052", "FFmpeg 처리에 실패했습니다."),
    VIDEO_SUBTITLE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V053", "자막 합성에 실패했습니다."),

    // AI Service
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A001", "AI 서비스를 사용할 수 없습니다."),
    AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "A002", "AI 서비스 할당량을 초과했습니다."),
    AI_API_KEY_INVALID(HttpStatus.UNAUTHORIZED, "A003", "API 키가 유효하지 않습니다. 설정을 확인해주세요."),
    AI_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "A004", "AI 서비스 응답 시간이 초과되었습니다."),
    AI_SAFETY_FILTER(HttpStatus.BAD_REQUEST, "A005", "콘텐츠 안전 정책에 의해 요청이 거부되었습니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U001", "사용자를 찾을 수 없습니다."),
    INSUFFICIENT_CREDITS(HttpStatus.PAYMENT_REQUIRED, "U002", "크레딧이 부족합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "U003", "아이디 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "U004", "이미 사용 중인 아이디입니다."),

    // Conversation
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "CV001", "대화를 찾을 수 없습니다."),
    CONVERSATION_ALREADY_COMPLETED(HttpStatus.BAD_REQUEST, "CV002", "이미 완료된 대화입니다."),
    CONVERSATION_UNAUTHORIZED(HttpStatus.FORBIDDEN, "CV003", "해당 대화에 접근할 권한이 없습니다."),
    MAX_ACTIVE_CONVERSATIONS(HttpStatus.TOO_MANY_REQUESTS, "CV004", "진행 중인 대화가 이미 있습니다."),
    CONTENT_GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "CV005", "다른 영상이 생성 중입니다. 완료 후 다시 시도해주세요."),

    // Access
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AC001", "접근이 거부되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
