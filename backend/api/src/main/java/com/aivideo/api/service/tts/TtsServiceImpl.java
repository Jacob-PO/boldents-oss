package com.aivideo.api.service.tts;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.entity.ApiRateLimit;
import com.aivideo.api.service.CreatorConfigService;
import com.aivideo.api.service.RateLimitService;
import com.aivideo.api.util.AdaptiveRateLimiter;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.aivideo.api.util.PathValidator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsServiceImpl implements TtsService {

    private final com.aivideo.api.service.ApiKeyService apiKeyService;
    private final com.aivideo.api.service.CreatorConfigService genreConfigService;

    // v2.9.11: Bean 주입으로 변경 (HttpClientConfig에서 관리)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 스레드 로컬 변수로 현재 요청의 API 키 저장
    private final ThreadLocal<String> currentApiKey = new ThreadLocal<>();

    // v2.8.4: 스레드 로컬 변수로 현재 요청의 장르 ID 저장
    private final ThreadLocal<Long> currentCreatorId = new ThreadLocal<>();

    // v2.8.4: Gemini TTS API 엔드포인트 템플릿 (모델은 DB에서 조회)
    private static final String GEMINI_TTS_API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    // 오디오 저장 디렉토리
    private static final String AUDIO_DIR = "/tmp/aivideo/audio";

    // v2.9.13: DB 기반 Rate Limit 서비스
    private final RateLimitService rateLimitService;

    /**
     * v2.9.13: TTS 모델별 AdaptiveRateLimiter 조회
     * DB에서 rate limit 설정을 로드하여 적응형 제어 적용
     */
    private AdaptiveRateLimiter getTtsRateLimiter(String modelName) {
        return rateLimitService.getRateLimiter(modelName);
    }

    // ========== Gemini TTS 음성 설정 (공식 30개 음성) ==========
    // 참조: https://ai.google.dev/gemini-api/docs/speech-generation

    /**
     * v2.9.4: Gemini TTS 전체 음성 목록 (30개)
     * 각 음성의 특성을 기반으로 장르별 최적 음성 선택
     */
    public enum GeminiVoice {
        // === 밝고 생동감 있는 음성 (Bright & Lively) ===
        ZEPHYR("Zephyr", "밝고 경쾌한 톤", "여성", false),
        PUCK("Puck", "명랑하고 대화체 (기본 음성)", "중성", false),
        LEDA("Leda", "청년스럽고 활기찬", "여성", false),
        AOEDE("Aoede", "산뜻하고 음악적인", "여성", false),

        // === 부드럽고 정보제공적 (Soft & Informative) ===
        KORE("Kore", "중립적이고 전문적인 (한국어 최적화)", "여성", true),
        ORUS("Orus", "단호하고 권위있는", "남성", false),
        CHARON("Charon", "깊고 정보제공적인", "남성", false),
        IAPETUS("Iapetus", "명확하고 정확한", "남성", false),

        // === 따뜻하고 감성적 (Warm & Emotional) ===
        SULAFAT("Sulafat", "따뜻하고 감성적인 (오디오북 추천)", "여성", false),
        FENRIR("Fenrir", "따뜻하고 친근한", "남성", false),
        GACRUX("Gacrux", "차분하고 안정적인", "남성", false),

        // === 특수 음성 (Special) ===
        ENCELADUS("Enceladus", "숨소리 포함, 감성적", "여성", false),
        ALGENIB("Algenib", "허스키하고 거친", "남성", false),

        // === 기타 음성 ===
        ACHERNAR("Achernar", "깨끗하고 또렷한", "여성", false),
        ACHIRD("Achird", "중간 톤", "남성", false),
        ALGIEBA("Algieba", "부드러운", "여성", false),
        ALNILAM("Alnilam", "깊은 톤", "남성", false),
        AUTONOE("Autonoe", "자연스러운", "여성", false),
        CALLIRRHOE("Callirrhoe", "우아한", "여성", false),
        DESPINA("Despina", "생기있는", "여성", false),
        ERINOME("Erinome", "섬세한", "여성", false),
        LAOMEDEIA("Laomedeia", "부드러운", "여성", false),
        PULCHERRIMA("Pulcherrima", "아름다운", "여성", false),
        RASALGETHI("Rasalgethi", "강렬한", "남성", false),
        SADACHBIA("Sadachbia", "차분한", "남성", false),
        SADALTAGER("Sadaltager", "안정적인", "남성", false),
        SCHEDAR("Schedar", "명확한", "여성", false),
        UMBRIEL("Umbriel", "신비로운", "중성", false),
        VINDEMIATRIX("Vindemiatrix", "우아한", "여성", false),
        ZUBENELGENUBI("Zubenelgenubi", "독특한", "중성", false);

        private final String name;
        private final String description;
        private final String gender;
        private final boolean koreanOptimized;

        GeminiVoice(String name, String description, String gender, boolean koreanOptimized) {
            this.name = name;
            this.description = description;
            this.gender = gender;
            this.koreanOptimized = koreanOptimized;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getGender() { return gender; }
        public boolean isKoreanOptimized() { return koreanOptimized; }
    }

    /**
     * 나레이션 생성 (장르별 TTS 지시사항 적용)
     * v2.8.2: creatorId로 DB에서 TTS_INSTRUCTION을 로드하여 적용
     */
    @Override
    public String generateNarration(Long userNo, String text, QualityTier tier, Long creatorId) {
        // null/empty 체크
        if (text == null || text.isBlank()) {
            log.warn("TTS 텍스트가 비어있습니다. creatorId: {}", creatorId);
            throw new ApiException(ErrorCode.INVALID_REQUEST, "TTS 텍스트가 비어있습니다.");
        }

        log.info("Generating TTS with Gemini, text length: {}, creatorId: {}", text.length(), creatorId);

        // 사용자 API 키 조회 및 스레드 로컬에 저장
        String apiKey = apiKeyService.getServiceApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 설정되지 않았습니다. 마이페이지에서 Google API 키를 등록해주세요.");
        }
        currentApiKey.set(apiKey);

        // v2.8.4: 장르 ID 저장 (TTS 모델 조회에 사용)
        currentCreatorId.set(creatorId);

        try {
            // 오디오 디렉토리 생성
            Files.createDirectories(Paths.get(AUDIO_DIR));

            // v2.9.14: 장르별 TTS 지시사항 로드 (DB 필수 - 하드코딩 폴백 없음)
            if (creatorId == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "TTS 생성에 creatorId가 필요합니다.");
            }

            // v2.9.128: tts_instruction 프롬프트에 음성/페르소나/지시사항 통합
            // [VOICE] 섹션에서 음성 이름 파싱, 전체 프롬프트를 TTS 지시사항으로 사용
            String ttsInstruction = genreConfigService.getTtsInstruction(creatorId);
            String voiceId = getVoiceForGenre(creatorId);
            log.info("[v2.9.128] TTS 설정 로드 완료: creatorId={}, voice={}, instructionLength={}",
                creatorId, voiceId, ttsInstruction.length());

            // v2.9.76: 4000자 청킹 로직 제거 - API가 자체적으로 처리하도록 함
            // 참고: Gemini TTS API는 공식적인 입력 길이 제한 없음 (출력은 ~5분27초 제한 존재)
            return callGeminiTtsApi(text, tier, voiceId, ttsInstruction);

        } catch (Exception e) {
            log.error("TTS generation failed", e);
            throw new ApiException(ErrorCode.TTS_GENERATION_FAILED, e);
        } finally {
            currentApiKey.remove();
            currentCreatorId.remove();
        }
    }

    /**
     * 나레이션 생성 (레거시 - 기본 장르 사용)
     * @deprecated v2.8.2: creatorId를 전달하는 메서드 사용을 권장합니다.
     */
    /**
     * @deprecated v2.9.12: 장르 ID가 필요합니다. generateNarration(userNo, text, tier, creatorId) 사용
     */
    @Deprecated
    @Override
    public String generateNarration(Long userNo, String text, QualityTier tier) {
        log.error("v2.9.12: generateNarration() without creatorId 호출 금지!");
        throw new UnsupportedOperationException(
            "v2.9.12: 장르 ID가 필요합니다. generateNarration(userNo, text, tier, creatorId)를 사용하세요.");
    }

    /**
     * v2.9.4: 장르별 TTS 음성 선택 (DB 필수)
     * 같은 장르의 모든 콘텐츠는 동일한 음성을 사용하여 일관성 보장
     *
     * @return DB에 설정된 TTS 음성
     */
    private String getVoiceForGenre(Long creatorId) {
        String voice = genreConfigService.getTtsVoice(creatorId);
        log.info("Using TTS voice from DB: creatorId={}, voice={}", creatorId, voice);
        return voice;
    }

    /**
     * v2.9.4: Director 프롬프트 형식 구축 (공식 문서 기반)
     * 참조: https://ai.google.dev/gemini-api/docs/speech-generation
     *
     * 공식 권장 4가지 요소:
     * 1. Audio Profile - 내레이터 캐릭터 정체성
     * 2. Scene - 환경과 감정 설정
     * 3. Director's Notes - Style, Accent, Pacing
     * 4. Transcript - 실제 음성화할 텍스트
     *
     * @param text 실제 나레이션 텍스트
     * @param ttsInstruction DB에서 로드한 장르별 TTS 지시사항
     * @param creatorId 장르 ID
     * @return Director 형식으로 구성된 프롬프트
     */
    /**
     * v2.9.170: Director 프레임을 DB base 템플릿으로 이동
     * Audio Profile, Scene, Director's Notes 프레임은 creator_prompt_base.TTS_INSTRUCTION에 포함
     * {{TRANSCRIPT}} 플레이스홀더만 실제 나레이션 텍스트로 치환
     */
    private String buildDirectorPrompt(String text, String ttsInstruction, Long creatorId) {
        // v2.9.170: ttsInstruction은 composePrompt()로 조합된 전체 Director 프롬프트
        // {{TRANSCRIPT}}만 실제 텍스트로 치환 (114-tts-director-frame-in-base-template.sql 선행 필요)
        String result = ttsInstruction.replace("{{TRANSCRIPT}}", text);
        log.debug("Built Director prompt for creatorId {}: {} chars", creatorId, result.length());
        return result;
    }

    /**
     * Gemini TTS API 호출 with 폴백 모델 지원
     * v2.9.62: 기본 모델 실패 시 폴백 모델로 자동 재시도
     *
     * @param text 변환할 텍스트
     * @param tier 품질 티어
     * @param voiceId TTS 음성 ID
     * @param ttsInstruction TTS 지시사항
     * @return 생성된 오디오 파일 경로
     */
    private String callGeminiTtsApi(String text, QualityTier tier, String voiceId, String ttsInstruction) {
        Long creatorId = currentCreatorId.get();
        String primaryModel = genreConfigService.getTtsModel(creatorId);
        String fallbackModel = genreConfigService.getFallbackTtsModel(creatorId);

        log.info("[v2.9.62] TTS 생성 시작 - creatorId={}, primaryModel={}, fallbackModel={}, voice={}",
                creatorId, primaryModel, fallbackModel, voiceId);

        // 1차 시도 - 기본 모델 (gemini-2.5-pro-preview-tts)
        try {
            log.info("[v2.9.62] 1차 시도 - 기본 모델: {}", primaryModel);
            return callGeminiTtsApiWithModel(text, tier, voiceId, ttsInstruction, primaryModel);
        } catch (Exception primaryEx) {
            log.warn("[v2.9.62] 기본 모델 실패: {} - {}", primaryModel, primaryEx.getMessage());

            // 2차 시도 - 폴백 모델 (gemini-2.5-flash-preview-tts)
            try {
                log.info("[v2.9.62] 2차 시도 - 폴백 모델: {}", fallbackModel);
                String result = callGeminiTtsApiWithModel(text, tier, voiceId, ttsInstruction, fallbackModel);
                log.info("[v2.9.62] ✅ 폴백 모델 성공: {}", fallbackModel);
                return result;
            } catch (Exception fallbackEx) {
                log.error("[v2.9.62] ❌ 폴백 모델도 실패: {} - {}", fallbackModel, fallbackEx.getMessage());
                throw new ApiException(ErrorCode.TTS_GENERATION_FAILED,
                        "TTS 생성 실패 - 기본 모델(" + primaryModel + ")과 폴백 모델(" + fallbackModel + ") 모두 실패. " +
                                "기본: " + primaryEx.getMessage() + ", 폴백: " + fallbackEx.getMessage());
            }
        }
    }

    /**
     * Gemini TTS API 호출 - 특정 모델로 호출
     * v2.8.2: ttsInstruction 파라미터 추가 (장르별 TTS 지시사항)
     * v2.8.4: DB에서 장르별 TTS 모델 조회
     * v2.9.4: Director 프롬프트 형식 적용 (공식 문서 기반)
     * v2.9.13: DB 기반 Rate Limiting + 503/429 재시도 로직 추가
     * v2.9.62: 모델 파라미터 추가 (폴백 지원)
     * v2.9.76: 스트리밍 파싱으로 OOM 방지 (30MB+ 응답 처리)
     *
     * Director 프롬프트 구조 (공식 권장):
     * 1. Audio Profile: 캐릭터 정체성 정의
     * 2. Scene: 물리적 환경과 감정적 분위기
     * 3. Director's Notes: Style, Accent, Pacing
     * 4. Transcript: 실제 음성화할 텍스트
     */
    private String callGeminiTtsApiWithModel(String text, QualityTier tier, String voiceId, String ttsInstruction, String ttsModel) {
        Long creatorId = currentCreatorId.get();
        String apiUrl = String.format(GEMINI_TTS_API_URL_TEMPLATE, ttsModel);
        log.info("[v2.9.76] Using TTS model: creatorId={}, model={}, voice={}, textLength={}",
                creatorId, ttsModel, voiceId, text.length());

        // v2.9.13: DB 기반 Rate Limiter 조회
        AdaptiveRateLimiter rateLimiter = getTtsRateLimiter(ttsModel);
        ApiRateLimit rateLimit = rateLimitService.getRateLimit(ttsModel);
        int maxRetries = rateLimit != null ? rateLimit.getMaxRetries() : 5;

        // v2.9.4: Director 프롬프트 형식 적용 (공식 문서 기반)
        String finalText = buildDirectorPrompt(text, ttsInstruction, creatorId);

        // Gemini TTS API 요청 형식
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", List.of(
            Map.of("parts", List.of(
                Map.of("text", finalText)
            ))
        ));

        // generationConfig - TTS 설정
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseModalities", List.of("AUDIO"));
        generationConfig.put("speechConfig", Map.of(
            "voiceConfig", Map.of(
                "prebuiltVoiceConfig", Map.of(
                    "voiceName", voiceId
                )
            )
        ));
        requestBody.put("generationConfig", generationConfig);

        // v2.9.13: 재시도 로직 with 적응형 Rate Limiting
        int attempt = 0;
        long backoffMs = rateLimit != null ? rateLimit.getInitialBackoffMs() : 5000;
        long maxBackoffMs = rateLimit != null ? rateLimit.getMaxBackoffMs() : 60000;

        while (attempt < maxRetries) {
            attempt++;

            try {
                // v2.9.13: Rate Limit 대기
                rateLimiter.waitIfNeeded();

                String requestJson = objectMapper.writeValueAsString(requestBody);
                log.debug("[TTS] API request attempt {}/{} for voice: {}", attempt, maxRetries, voiceId);

                // v2.9.76: 스트리밍 방식으로 응답 처리 (OOM 방지)
                String audioPath = executeStreamingTtsRequest(apiUrl, requestJson);

                if (audioPath != null) {
                    rateLimiter.recordSuccess();
                    log.info("[v2.9.76] ✅ TTS 스트리밍 파싱 성공: {}", audioPath);
                    return audioPath;
                }

                rateLimiter.recordError();
                throw new ApiException(ErrorCode.TTS_GENERATION_FAILED, "TTS 응답에서 오디오 데이터를 찾을 수 없습니다.");

            } catch (ApiException e) {
                throw e;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    rateLimiter.recordSevereError();
                    log.warn("[TTS] Rate limit (429) hit, attempt {}/{}, backing off {}ms",
                            attempt, maxRetries, backoffMs);

                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(backoffMs);
                            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
                            continue;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ApiException(ErrorCode.TTS_GENERATION_FAILED, "TTS 생성 중단됨");
                        }
                    }
                }

                rateLimiter.recordError();
                log.error("[TTS] HTTP 클라이언트 에러 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new ApiException(ErrorCode.TTS_GENERATION_FAILED,
                        "TTS API 요청 실패: " + e.getStatusCode());

            } catch (org.springframework.web.client.HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503) {
                    rateLimiter.recordSevereError();
                    log.warn("[TTS] Server overloaded (503), attempt {}/{}, backing off {}ms",
                            attempt, maxRetries, backoffMs);

                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(backoffMs);
                            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
                            continue;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ApiException(ErrorCode.TTS_GENERATION_FAILED, "TTS 생성 중단됨");
                        }
                    }
                }

                rateLimiter.recordError();
                log.error("[TTS] HTTP 서버 에러 - status: {}", e.getStatusCode());
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "TTS 서버 오류: " + e.getStatusCode());

            } catch (org.springframework.web.client.ResourceAccessException e) {
                rateLimiter.recordError();
                log.error("[TTS] 네트워크 에러: {}", e.getMessage());
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "TTS 서버 연결 실패");
            } catch (Exception e) {
                rateLimiter.recordError();
                log.error("[TTS] 예상치 못한 에러: {}", e.getMessage(), e);
                throw new ApiException(ErrorCode.TTS_GENERATION_FAILED,
                        "TTS 생성 실패: " + e.getMessage());
            }
        }

        log.error("[TTS] All {} retry attempts failed for model: {}", maxRetries, ttsModel);
        throw new ApiException(ErrorCode.TTS_GENERATION_FAILED,
                "TTS 생성 실패: " + maxRetries + "회 재시도 후에도 실패");
    }

    /**
     * v2.9.76: RestTemplate.execute()로 스트리밍 응답 처리
     *
     * 핵심 원리:
     * 1. ResponseExtractor를 사용하여 InputStream 직접 접근
     * 2. Jackson JsonParser로 스트리밍 JSON 파싱
     * 3. Base64 데이터를 찾아서 바로 파일로 저장
     *
     * 메모리 효율:
     * - 기존: String(30MB) + JsonNode(30MB) + byte[](20MB) = 80MB+
     * - 개선: 스트리밍 버퍼(64KB) + byte[](20MB) = 20MB
     */
    private String executeStreamingTtsRequest(String apiUrl, String requestJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", currentApiKey.get());

        // RequestCallback: 요청 바디 설정
        org.springframework.web.client.RequestCallback requestCallback = request -> {
            request.getHeaders().addAll(headers);
            try (OutputStream os = request.getBody()) {
                os.write(requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };

        // ResponseExtractor: 스트리밍 파싱
        ResponseExtractor<String> responseExtractor = response -> {
            if (response.getStatusCode() != HttpStatus.OK) {
                throw new ApiException(ErrorCode.TTS_GENERATION_FAILED,
                    "TTS API 오류: " + response.getStatusCode());
            }
            return parseGeminiTtsResponseStreaming(response.getBody());
        };

        return restTemplate.execute(apiUrl, HttpMethod.POST, requestCallback, responseExtractor);
    }

    /**
     * v2.9.76: 스트리밍 JSON 파싱으로 Base64 오디오 추출
     *
     * Gemini TTS 응답 구조:
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{
     *         "inlineData": {
     *           "mimeType": "audio/pcm",
     *           "data": "BASE64_AUDIO..."
     *         }
     *       }]
     *     }
     *   }]
     * }
     *
     * Jackson JsonParser를 사용하여 토큰 단위로 파싱하고,
     * "data" 필드를 찾으면 Base64 값을 추출하여 파일로 저장합니다.
     */
    private String parseGeminiTtsResponseStreaming(InputStream inputStream) throws IOException {
        log.info("[v2.9.76] Starting streaming JSON parsing for TTS response");

        String mimeType = "audio/pcm";
        String base64Data = null;

        try (JsonParser parser = objectMapper.getFactory().createParser(inputStream)) {
            String currentFieldName = null;
            boolean inInlineData = false;

            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();

                if (token == JsonToken.FIELD_NAME) {
                    currentFieldName = parser.getCurrentName();

                    // inlineData 객체 진입 감지
                    if ("inlineData".equals(currentFieldName)) {
                        inInlineData = true;
                    }
                }

                // inlineData 내부에서 mimeType과 data 추출
                if (inInlineData && token == JsonToken.VALUE_STRING) {
                    if ("mimeType".equals(currentFieldName)) {
                        mimeType = parser.getText();
                        log.debug("[v2.9.76] Found mimeType: {}", mimeType);
                    } else if ("data".equals(currentFieldName)) {
                        // 핵심: Base64 데이터 추출
                        // JsonParser.getText()는 전체 문자열을 반환하지만,
                        // 이미 스트리밍으로 읽었으므로 JSON 전체를 String으로 보관하지 않음
                        base64Data = parser.getText();
                        log.info("[v2.9.76] Found Base64 data, length: {} chars",
                                base64Data != null ? base64Data.length() : 0);
                        break; // 데이터를 찾으면 파싱 종료
                    }
                }

                // inlineData 객체 종료 감지
                if (inInlineData && token == JsonToken.END_OBJECT && "inlineData".equals(currentFieldName)) {
                    inInlineData = false;
                }
            }
        }

        if (base64Data == null || base64Data.isEmpty()) {
            log.error("[v2.9.76] No audio data found in TTS response");
            throw new RuntimeException("TTS 응답에서 오디오 데이터를 찾을 수 없습니다.");
        }

        // Base64 디코딩 및 파일 저장
        return saveAudioFile(base64Data, mimeType);
    }

    /**
     * Gemini TTS API 응답 파싱
     * @deprecated v2.9.76: 스트리밍 파싱으로 대체됨. parseGeminiTtsResponseStreaming() 사용.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private String parseGeminiTtsResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            log.debug("Gemini TTS API response received");

            // candidates[0].content.parts[0].inlineData.data에서 오디오 데이터 추출
            if (root.has("candidates") && root.get("candidates").isArray()) {
                JsonNode candidates = root.get("candidates");
                if (candidates.size() > 0) {
                    JsonNode candidate = candidates.get(0);
                    if (candidate.has("content") && candidate.get("content").has("parts")) {
                        JsonNode parts = candidate.get("content").get("parts");

                        for (JsonNode part : parts) {
                            if (part.has("inlineData")) {
                                JsonNode inlineData = part.get("inlineData");
                                if (inlineData.has("data")) {
                                    String base64Data = inlineData.get("data").asText();
                                    String mimeType = inlineData.has("mimeType") ?
                                            inlineData.get("mimeType").asText() : "audio/pcm";
                                    return saveAudioFile(base64Data, mimeType);
                                }
                            }
                        }
                    }
                }
            }

            // 에러 응답 확인
            if (root.has("error")) {
                String errorMessage = root.get("error").has("message") ?
                        root.get("error").get("message").asText() : "Unknown error";
                throw new RuntimeException("Gemini TTS API error: " + errorMessage);
            }

            log.error("Unable to parse audio from response");
            throw new RuntimeException("Unable to parse audio from Gemini TTS response");

        } catch (Exception e) {
            log.error("Failed to parse Gemini TTS response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse Gemini TTS response", e);
        }
    }

    /**
     * Base64 오디오를 파일로 저장 (PCM -> MP3 변환)
     */
    private String saveAudioFile(String base64Data, String mimeType) {
        try {
            String audioId = UUID.randomUUID().toString();
            Path pcmPath = Paths.get(AUDIO_DIR, audioId + ".pcm");
            Path mp3Path = Paths.get(AUDIO_DIR, audioId + ".mp3");

            // Base64 디코딩 및 PCM 파일 저장
            byte[] audioBytes = Base64.getDecoder().decode(base64Data);
            Files.write(pcmPath, audioBytes);

            log.info("PCM audio saved: {} (size: {} bytes)", pcmPath, audioBytes.length);

            // FFmpeg로 PCM -> MP3 변환
            // Gemini TTS 출력: 16-bit PCM, 24000 Hz, mono
            boolean converted = convertPcmToMp3(pcmPath.toString(), mp3Path.toString());

            if (converted) {
                // PCM 파일 삭제
                Files.deleteIfExists(pcmPath);
                log.info("MP3 audio saved: {}", mp3Path);
                return mp3Path.toAbsolutePath().toString();
            } else {
                // 변환 실패 시 PCM 파일 반환
                log.warn("PCM to MP3 conversion failed, returning PCM file");
                return pcmPath.toAbsolutePath().toString();
            }

        } catch (IOException e) {
            log.error("Failed to save audio: {}", e.getMessage());
            throw new RuntimeException("Failed to save audio", e);
        }
    }

    /**
     * FFmpeg로 PCM -> MP3 변환
     */
    private boolean convertPcmToMp3(String pcmPath, String mp3Path) {
        try {
            // v2.9.9: 경로 보안 검증
            PathValidator.validateAndGet(pcmPath);
            PathValidator.validateAndGet(mp3Path);

            // Gemini TTS 출력 스펙: 16-bit PCM, 24000 Hz, mono
            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-f", "s16le",        // 16-bit signed little-endian
                    "-ar", "24000",       // 24000 Hz
                    "-ac", "1",           // mono
                    "-i", pcmPath,
                    "-codec:a", "libmp3lame",
                    "-b:a", "192k",
                    mp3Path
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // v2.9.83: 타임아웃 복원 (2분) - 무한 대기 방지
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("PCM to MP3 conversion timeout (2min)");
                return false;
            }
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                log.debug("PCM to MP3 conversion successful");
                return true;
            } else {
                log.error("FFmpeg conversion failed with exit code: {}", exitCode);
                return false;
            }

        } catch (Exception e) {
            log.error("FFmpeg conversion error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 긴 나레이션을 청크로 나눠서 생성
     * v2.8.2: ttsInstruction 파라미터 추가
     * v2.9.0: voiceId 파라미터 추가 (음성 일관성)
     *
     * @deprecated v2.9.76: 청킹 로직 제거됨. API가 자체적으로 긴 텍스트 처리.
     *             Gemini TTS API는 공식적인 입력 길이 제한 없음.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private String generateLongNarration(String text, QualityTier tier, String ttsInstruction, String voiceId) {
        log.info("Generating long narration, splitting into chunks...");

        List<String> chunks = splitTextIntoChunks(text, 3500);
        log.info("Split into {} chunks", chunks.size());

        List<String> audioPaths = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            log.debug("Generating chunk {}/{}", i + 1, chunks.size());
            String chunkAudio = callGeminiTtsApi(chunks.get(i), tier, voiceId, ttsInstruction);
            audioPaths.add(chunkAudio);
        }

        // 모든 오디오 청크를 하나로 합치기
        if (audioPaths.size() == 1) {
            return audioPaths.get(0);
        }

        return mergeAudioFiles(audioPaths);
    }

    /**
     * 텍스트를 청크로 분리 (문장 단위)
     * @deprecated v2.9.76: 청킹 로직 제거됨
     */
    @Deprecated
    @SuppressWarnings("unused")
    private List<String> splitTextIntoChunks(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        String[] sentences = text.split("(?<=[.!?])\\s+");

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
            }
            currentChunk.append(sentence).append(" ");
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 여러 오디오 파일을 하나로 합치기 (FFmpeg)
     * @deprecated v2.9.76: 청킹 로직 제거됨
     */
    @Deprecated
    @SuppressWarnings("unused")
    private String mergeAudioFiles(List<String> audioPaths) {
        try {
            // v2.9.9: 경로 보안 검증
            for (String path : audioPaths) {
                PathValidator.validateAndGet(path);
            }

            String outputId = UUID.randomUUID().toString();
            Path outputPath = Paths.get(AUDIO_DIR, outputId + ".mp3");
            Path listPath = Paths.get(AUDIO_DIR, outputId + "_list.txt");

            // concat 파일 생성
            StringBuilder listContent = new StringBuilder();
            for (String path : audioPaths) {
                listContent.append("file '").append(path).append("'\n");
            }
            Files.writeString(listPath, listContent.toString());

            // FFmpeg로 합치기
            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listPath.toString(),
                    "-c", "copy",
                    outputPath.toString()
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // v2.9.83: 타임아웃 복원 (3분) - 무한 대기 방지
            boolean finished = process.waitFor(3, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("Audio merge timeout (3min)");
                // 임시 파일 정리 후 첫 번째 청크 반환
                Files.deleteIfExists(listPath);
                return audioPaths.get(0);
            }
            int exitCode = process.exitValue();

            // 임시 파일 정리
            Files.deleteIfExists(listPath);
            for (String path : audioPaths) {
                Files.deleteIfExists(Paths.get(path));
            }

            if (exitCode == 0) {
                log.info("Audio files merged: {}", outputPath);
                return outputPath.toAbsolutePath().toString();
            } else {
                log.error("Audio merge failed (exit {}), returning first chunk", exitCode);
                return audioPaths.get(0);
            }

        } catch (Exception e) {
            log.error("Audio merge error: {}", e.getMessage());
            return audioPaths.get(0);
        }
    }

    /**
     * @deprecated v2.9.12: 장르 ID가 필요합니다. generateNarration(userNo, text, tier, creatorId) 사용
     */
    @Deprecated
    public String generateSlideTts(List<VideoDto.SlideScene> slides, QualityTier tier) {
        log.error("v2.9.12: generateSlideTts() 호출 금지! 개별 씬별 TTS 생성으로 변경됨");
        throw new UnsupportedOperationException(
            "v2.9.12: generateSlideTts() 사용 금지. ContentService에서 개별 씬별로 TTS를 생성합니다.");
    }

    /**
     * FFmpeg로 오디오 파일의 실제 duration 측정 (초)
     */
    @Override
    public double getAudioDuration(String audioFilePath) {
        try {
            // v2.9.9: 경로 보안 검증
            PathValidator.validateAndGet(audioFilePath);

            // ffprobe로 오디오 길이 측정
            List<String> command = List.of(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audioFilePath
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            // v2.9.83: 타임아웃 복원 (30초) - 무한 대기 방지
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffprobe audio duration timeout (30s), using default 5s");
                return 5.0;
            }
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                String durationStr = output.toString().trim();
                if (!durationStr.isEmpty()) {
                    double duration = Double.parseDouble(durationStr);
                    log.debug("Audio duration: {}s for {}", duration, audioFilePath);
                    return duration;
                }
            }

            log.warn("Failed to get audio duration (exit {}), using default 5s", exitCode);
            return 5.0;

        } catch (Exception e) {
            log.error("Error getting audio duration: {}", e.getMessage());
            return 5.0;
        }
    }

    /**
     * v2.9.3: 오디오 파일에서 음성 구간(speech segments) 감지
     * FFmpeg silencedetect 필터를 사용하여 침묵 구간을 찾고,
     * 그 사이의 음성 구간 시작/끝 시간을 반환
     *
     * 공식 문서: https://ffmpeg.org/ffmpeg-filters.html#silencedetect
     *
     * 작동 원리:
     * 1. FFmpeg silencedetect로 침묵 구간 감지
     *    - noise=-50dB: 침묵 임계값 (공식 기본값 -60dB보다 약간 높게 설정)
     *    - duration=0.2: TTS 문장 사이 쉼은 보통 0.2~0.5초
     * 2. 침묵 구간 사이의 음성 구간 계산
     * 3. 각 음성 구간의 [시작시간, 끝시간]을 반환
     */
    @Override
    public List<double[]> detectSpeechSegments(String audioFilePath) {
        List<double[]> speechSegments = new ArrayList<>();

        try {
            // v2.9.9: 경로 보안 검증
            PathValidator.validateAndGet(audioFilePath);

            // 먼저 전체 오디오 길이 측정
            double totalDuration = getAudioDuration(audioFilePath);

            // FFmpeg silencedetect 실행 (공식 문서 기반)
            // -af silencedetect=n=-30dB:d=0.1 (v2.9.82: 자막 싱크 최적화 - 민감도 향상)
            //   n=-30dB: 노이즈 레벨 상향 조정 (작은 소리도 음성으로 감지)
            //   d=0.1: 0.1초 짧은 침묵도 감지하여 문장 분리 정밀도 향상
            List<String> command = List.of(
                    "ffmpeg",
                    "-i", audioFilePath,
                    "-af", "silencedetect=n=-30dB:d=0.1",
                    "-f", "null",
                    "-"
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<Double> silenceStarts = new ArrayList<>();
            List<Double> silenceEnds = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 침묵 시작: [silencedetect @ ...] silence_start: 1.234
                    if (line.contains("silence_start:")) {
                        String[] parts = line.split("silence_start:");
                        if (parts.length > 1) {
                            String timeStr = parts[1].trim().split("\\s+")[0];
                            silenceStarts.add(Double.parseDouble(timeStr));
                        }
                    }
                    // 침묵 끝: [silencedetect @ ...] silence_end: 2.345 | silence_duration: 1.111
                    if (line.contains("silence_end:")) {
                        String[] parts = line.split("silence_end:");
                        if (parts.length > 1) {
                            String timeStr = parts[1].trim().split("\\s+")[0];
                            silenceEnds.add(Double.parseDouble(timeStr));
                        }
                    }
                }
            }

            // v2.9.83: 타임아웃 복원 (2분) - 무한 대기 방지
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Silence detection timeout (2min), returning single segment");
                double totalDur = getAudioDuration(audioFilePath);
                speechSegments.add(new double[]{0.0, totalDur});
                return speechSegments;
            }

            log.debug("Silence detection: {} starts, {} ends", silenceStarts.size(), silenceEnds.size());

            // 음성 구간 계산 (침묵 사이의 구간)
            // 첫 번째 음성 구간: 0초부터 첫 침묵 시작까지
            if (!silenceStarts.isEmpty()) {
                if (silenceStarts.get(0) > 0.1) { // 첫 침묵이 0.1초 이후에 시작하면
                    speechSegments.add(new double[]{0.0, silenceStarts.get(0)});
                }
            }

            // 중간 음성 구간: 각 침묵 끝부터 다음 침묵 시작까지
            for (int i = 0; i < silenceEnds.size(); i++) {
                double speechStart = silenceEnds.get(i);
                double speechEnd;

                if (i + 1 < silenceStarts.size()) {
                    speechEnd = silenceStarts.get(i + 1);
                } else {
                    speechEnd = totalDuration;
                }

                // 유효한 음성 구간인지 확인 (최소 0.1초)
                if (speechEnd - speechStart > 0.1) {
                    speechSegments.add(new double[]{speechStart, speechEnd});
                }
            }

            // 침묵이 없는 경우: 전체를 하나의 음성 구간으로
            if (speechSegments.isEmpty()) {
                speechSegments.add(new double[]{0.0, totalDuration});
            }

            log.info("Detected {} speech segments in audio: {}", speechSegments.size(), audioFilePath);
            for (int i = 0; i < speechSegments.size(); i++) {
                log.debug("  Segment {}: {}s - {}s", i + 1,
                        speechSegments.get(i)[0], speechSegments.get(i)[1]);
            }

            return speechSegments;

        } catch (Exception e) {
            log.error("Failed to detect speech segments: {}", e.getMessage());
            // 실패 시 전체 duration을 하나의 구간으로 반환
            double totalDuration = getAudioDuration(audioFilePath);
            speechSegments.add(new double[]{0.0, totalDuration});
            return speechSegments;
        }
    }

    // ========== v2.9.170: 침묵 길이 기반 문장 경계 감지 (Top-K Longest Silences) ==========

    /**
     * 침묵 정보를 담는 내부 클래스
     */
    private static class SilenceInfo {
        double start;    // 침묵 시작 시간
        double end;      // 침묵 끝 시간
        double duration; // 침묵 길이
        double midpoint; // 침묵 중간점 (경계선으로 사용)
    }

    /**
     * v2.9.170: 오디오 파일에서 문장 경계를 정확히 감지
     *
     * 기존 detectSpeechSegments()는 모든 침묵을 동일하게 취급하여 세그먼트 수가 문장 수와 불일치.
     * 이후 mergeSegmentsToSentencesForScene()에서 비례 매핑하여 근본적으로 부정확.
     *
     * 이 메서드는 침묵의 **길이(duration)**를 분석하여:
     * - 문장 사이 쉼 (300~800ms, 길다) vs 단어 사이 쉼 (50~200ms, 짧다)
     * - 가장 긴 N-1개 침묵 = 문장 경계
     * - 정확히 N개 구간 반환
     */
    @Override
    public List<double[]> detectSentenceBoundaries(String audioFilePath, int sentenceCount) {
        List<double[]> result = new ArrayList<>();

        try {
            PathValidator.validateAndGet(audioFilePath);
            double totalDuration = getAudioDuration(audioFilePath);

            // 문장이 1개면 전체를 하나의 구간으로
            if (sentenceCount <= 1) {
                result.add(new double[]{0.0, totalDuration});
                return result;
            }

            // FFmpeg silencedetect 실행 (기존과 동일 파라미터)
            List<String> command = List.of(
                    "ffmpeg",
                    "-i", audioFilePath,
                    "-af", "silencedetect=n=-30dB:d=0.1",
                    "-f", "null",
                    "-"
            );

            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<SilenceInfo> silences = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                double lastSilenceStart = -1;
                while ((line = reader.readLine()) != null) {
                    // silence_start 파싱
                    if (line.contains("silence_start:")) {
                        String[] parts = line.split("silence_start:");
                        if (parts.length > 1) {
                            lastSilenceStart = Double.parseDouble(parts[1].trim().split("\\s+")[0]);
                        }
                    }
                    // silence_end + silence_duration 파싱
                    if (line.contains("silence_end:") && line.contains("silence_duration:")) {
                        try {
                            String afterEnd = line.substring(line.indexOf("silence_end:") + "silence_end:".length()).trim();
                            double silenceEnd = Double.parseDouble(afterEnd.split("[\\s|]+")[0]);

                            String afterDur = line.substring(line.indexOf("silence_duration:") + "silence_duration:".length()).trim();
                            double silenceDuration = Double.parseDouble(afterDur.split("\\s+")[0]);

                            SilenceInfo info = new SilenceInfo();
                            info.end = silenceEnd;
                            info.duration = silenceDuration;
                            info.start = (lastSilenceStart >= 0) ? lastSilenceStart : (silenceEnd - silenceDuration);
                            info.midpoint = info.start + (info.duration / 2.0);

                            silences.add(info);
                            lastSilenceStart = -1;
                        } catch (NumberFormatException e) {
                            log.warn("[v2.9.170] Failed to parse silence info from line: {}", line);
                        }
                    }
                }
            }

            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[v2.9.170] Silence detection timeout, falling back to single segment");
                result.add(new double[]{0.0, totalDuration});
                return result;
            }

            log.info("[v2.9.170] Detected {} silences for {} sentences in audio: {}",
                    silences.size(), sentenceCount, audioFilePath);

            // v2.9.176: Leading silence 감지 및 제거
            // TTS 오디오 시작 부분의 무음은 문장 사이 경계가 아니므로 제거해야 함
            double firstSpeechOnset = 0.0;
            SilenceInfo leadingSilence = null;
            for (SilenceInfo silence : silences) {
                if (silence.start < 0.05) {  // 0.05초 이내에서 시작 = leading silence
                    leadingSilence = silence;
                    firstSpeechOnset = silence.end;
                    log.info("[v2.9.176] Leading silence detected: {}s - {}s → firstSpeechOnset={}s",
                            String.format("%.3f", silence.start),
                            String.format("%.3f", silence.end),
                            String.format("%.3f", firstSpeechOnset));
                    break;
                }
            }
            // Leading silence는 문장 사이 경계가 아니므로 경계 후보에서 제거
            if (leadingSilence != null) {
                silences.remove(leadingSilence);
                log.info("[v2.9.176] Removed leading silence from boundary candidates, {} silences remaining",
                        silences.size());
            }

            // 침묵이 부족한 경우: 가용한 침묵을 전부 경계로 사용
            int boundariesNeeded = sentenceCount - 1;
            if (silences.isEmpty()) {
                // 침묵 0개: 전체를 단일 구간으로 반환 → SubtitleServiceImpl이 글자 수 기반 폴백 처리
                // v2.9.176: firstSpeechOnset 사용 (leading silence 제거 후)
                log.warn("[v2.9.176] No silences detected for {} sentences, returning single segment starting at {}s",
                        sentenceCount, String.format("%.3f", firstSpeechOnset));
                result.add(new double[]{firstSpeechOnset, totalDuration});
                return result;
            }
            if (silences.size() < boundariesNeeded) {
                // 침묵 부족: 있는 침묵을 전부 경계로 사용 (FFmpeg 재실행 없이)
                log.warn("[v2.9.170] Not enough silences ({}) for {} boundaries, using all {} silences",
                        silences.size(), boundariesNeeded, silences.size());
                boundariesNeeded = silences.size();
            }

            // 핵심 알고리즘: Top-K Longest Silences
            // 1. duration 내림차순 정렬하여 가장 긴 침묵 선택
            silences.sort((a, b) -> Double.compare(b.duration, a.duration));
            List<SilenceInfo> topSilences = new ArrayList<>(silences.subList(0, boundariesNeeded));

            // 2. 선택된 침묵들을 시간순 정렬
            topSilences.sort((a, b) -> Double.compare(a.midpoint, b.midpoint));

            // 3. 경계선(midpoint)을 기준으로 구간 생성
            // v2.9.176: 첫 세그먼트는 firstSpeechOnset에서 시작 (leading silence 이후)
            int actualSegmentCount = boundariesNeeded + 1;
            for (int i = 0; i < actualSegmentCount; i++) {
                double segStart = (i == 0) ? firstSpeechOnset : topSilences.get(i - 1).midpoint;
                double segEnd = (i == actualSegmentCount - 1) ? totalDuration : topSilences.get(i).midpoint;
                result.add(new double[]{segStart, segEnd});
            }

            // 로그 출력
            for (int i = 0; i < result.size(); i++) {
                log.debug("[v2.9.170] Sentence {}: {}s - {}s (duration: {}s)",
                        i + 1, String.format("%.2f", result.get(i)[0]),
                        String.format("%.2f", result.get(i)[1]),
                        String.format("%.2f", result.get(i)[1] - result.get(i)[0]));
            }
            if (!topSilences.isEmpty()) {
                log.info("[v2.9.170] Boundary silences duration range: {}s - {}s",
                        String.format("%.3f", topSilences.get(topSilences.size() - 1).duration),
                        String.format("%.3f", topSilences.get(0).duration));
            }

            return result;

        } catch (Exception e) {
            log.error("[v2.9.170] Failed to detect sentence boundaries: {}", e.getMessage());
            double totalDuration = getAudioDuration(audioFilePath);
            result.add(new double[]{0.0, totalDuration});
            return result;
        }
    }

    /**
     * v2.9.6: 오디오 템포를 조절하여 목표 길이에 맞춤
     * FFmpeg atempo 필터를 사용하여 오디오 속도 조절
     *
     * atempo 필터 제한: 0.5 ~ 2.0 범위
     * - 2.0: 2배속 (절반 길이)
     * - 0.5: 0.5배속 (2배 길이)
     *
     * 사용 사례: 오프닝 나레이션을 정확히 8초에 맞춤
     *
     * @param audioFilePath 원본 오디오 파일 경로
     * @param targetDurationSeconds 목표 길이 (초)
     * @return 템포 조절된 오디오 파일 경로 (조절 불필요 시 원본 경로 반환)
     */
    @Override
    public String adjustAudioTempo(String audioFilePath, double targetDurationSeconds) {
        try {
            // v2.9.9: 경로 보안 검증
            PathValidator.validateAndGet(audioFilePath);

            double actualDuration = getAudioDuration(audioFilePath);

            // 이미 목표 길이에 가까우면 (±0.3초 허용) 원본 반환
            if (Math.abs(actualDuration - targetDurationSeconds) < 0.3) {
                log.info("[TTS Tempo] Audio already at target duration: actual={}s, target={}s",
                        String.format("%.2f", actualDuration), targetDurationSeconds);
                return audioFilePath;
            }

            // 템포 계산: 실제 길이 / 목표 길이
            // 예: 10초 오디오를 8초로 → tempo = 10/8 = 1.25 (25% 빠르게)
            // 예: 6초 오디오를 8초로 → tempo = 6/8 = 0.75 (25% 느리게)
            double tempo = actualDuration / targetDurationSeconds;

            // atempo 필터 제한 범위: 0.5 ~ 2.0
            if (tempo < 0.5) {
                log.warn("[TTS Tempo] Tempo too slow ({}), clamping to 0.5", String.format("%.2f", tempo));
                tempo = 0.5;
            } else if (tempo > 2.0) {
                log.warn("[TTS Tempo] Tempo too fast ({}), clamping to 2.0", String.format("%.2f", tempo));
                tempo = 2.0;
            }

            log.info("[TTS Tempo] Adjusting: actual={}s → target={}s, tempo={}",
                    String.format("%.2f", actualDuration),
                    targetDurationSeconds,
                    String.format("%.2f", tempo));

            // 출력 파일 경로 생성
            String outputId = UUID.randomUUID().toString();
            Path outputPath = Paths.get(AUDIO_DIR, outputId + "_tempo.mp3");

            // FFmpeg atempo 필터로 템포 조절
            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-i", audioFilePath,
                    "-af", "atempo=" + String.format("%.4f", tempo),
                    "-c:a", "libmp3lame",
                    "-b:a", "192k",
                    outputPath.toString()
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 로그 출력
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[FFmpeg atempo] {}", line);
                }
            }

            // v2.9.83: 타임아웃 복원 (2분) - 무한 대기 방지
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("[TTS Tempo] FFmpeg timeout (2min)");
                return audioFilePath; // 실패 시 원본 반환
            }
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                // 결과 확인
                double adjustedDuration = getAudioDuration(outputPath.toString());
                log.info("[TTS Tempo] Adjusted successfully: {}s -> {}s (target: {}s)",
                        String.format("%.2f", actualDuration),
                        String.format("%.2f", adjustedDuration),
                        targetDurationSeconds);

                // 원본 파일 삭제하지 않음 (다른 곳에서 사용 가능)
                return outputPath.toAbsolutePath().toString();
            } else {
                log.error("[TTS Tempo] FFmpeg failed with exit code: {}", exitCode);
                return audioFilePath; // 실패 시 원본 반환
            }

        } catch (Exception e) {
            log.error("[TTS Tempo] Failed to adjust tempo: {}", e.getMessage());
            return audioFilePath; // 실패 시 원본 반환
        }
    }
}
