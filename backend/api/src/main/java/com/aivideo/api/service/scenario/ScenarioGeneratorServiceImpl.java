package com.aivideo.api.service.scenario;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.entity.Conversation;
import com.aivideo.api.mapper.ConversationMapper;
import com.aivideo.api.service.ContentService;
import com.aivideo.common.enums.ContentType;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 시나리오 생성 서비스 - Gemini API 연동
 *
 * 1시간 오디오북 드라마 시나리오 생성:
 * - 30개 슬라이드, 각 120초 = 3600초 (1시간)
 * - 완전한 스토리 아크와 긴 나레이션
 * - Identity Block으로 캐릭터 일관성 유지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioGeneratorServiceImpl implements ScenarioGeneratorService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final com.aivideo.api.service.ApiKeyService apiKeyService;
    private final com.aivideo.api.service.CreatorConfigService genreConfigService;
    private final ConversationMapper conversationMapper;  // v2.9.97: 나레이션 확장 시 사용자 입력 조회용

    // v2.9.62: 레거시 application.yml 모델 설정 - DB 모델이 우선
    @Value("${ai.tier.standard.scenario-model:gemini-3-flash}")
    private String standardModel;

    @Value("${ai.tier.premium.scenario-model:gemini-3-pro}")
    private String premiumModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final int OPENING_DURATION = 8;   // 오프닝 영상 8초 (Veo 3.1 API 고정)
    private static final int SLIDE_DURATION = 10;    // 슬라이드 초기값 (실제 길이는 TTS에 따라 동적으로 변함)
    // 슬라이드 수는 영상 길이에 따라 동적 계산: 30초=3장, 1분=6장, 10분=20장
    // 슬라이드 실제 길이: TTS 오디오 길이에 따라 자동 조절 (최소 3초, 최대 30초)

    // v2.9.98: 나레이션 설정은 DB에서 관리 (genres 테이블의 opening_narration_length, slide_narration_length, narration_expand_length)
    // v2.9.143: 모든 슬라이드에서 나레이션 확장 실행 (1장이라도 충분한 길이가 필요함)
    private static final int NARRATION_EXPAND_THRESHOLD = 0;      // 나레이션 확장 기준 (0 = 항상 확장)

    // v2.5.7: 랜덤 배열 모두 제거
    // AI가 시나리오 생성 시 characterBlock을 직접 생성하고,
    // 이 characterBlock을 모든 이미지/영상 프롬프트에 일관되게 사용

    @Override
    public VideoDto.ScenarioInfo generateScenario(Long userNo, String prompt, QualityTier tier, ContentType contentType, int slideCount, Long creatorId, Long chatId) {
        // v2.9.75: 진행 상황 추적 포함 버전
        slideCount = Math.min(10, Math.max(1, slideCount));

        // v2.9.62: DB에서 장르별 시나리오 모델 조회 (기본: gemini-3-pro)
        String primaryModel = genreConfigService.getScenarioModel(creatorId);
        String fallbackModel = genreConfigService.getFallbackScenarioModel(creatorId);

        log.info("[v2.9.75] Generating scenario with progress - primaryModel: {}, slideCount: {}, chatId: {}",
            primaryModel, slideCount, chatId);

        // v2.9.75: 진행 상황 초기화 (BASE_SCENARIO 단계)
        if (chatId != null) {
            ContentService.updateScenarioProgress(chatId, "BASE_SCENARIO", 0, slideCount, "기본 시나리오 생성 중...");
        }

        // 사용자 API 키 조회
        String apiKey = apiKeyService.getServiceApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 설정되지 않았습니다. 마이페이지에서 Google API 키를 등록해주세요.");
        }

        // 오프닝 나레이션 길이 한 번 조회 (buildSystemPromptForGenre, parseGeminiResponse에서 공유)
        int openingNarrationLength = genreConfigService.getOpeningNarrationLength(creatorId);

        // 시스템 프롬프트 생성 (장르 기반) - v2.9.154: prompt 파라미터 추가
        String systemPrompt = buildSystemPromptForGenre(slideCount, creatorId, prompt, openingNarrationLength);
        String userPrompt = buildUserPrompt(prompt, slideCount, creatorId);

        // v2.9.88: 기본 모델로 시나리오 생성 + JSON 구조 오류 시 재시도
        final int MAX_RETRIES = 3;
        VideoDto.ScenarioInfo scenario = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES && scenario == null; attempt++) {
            String modelToUse = (attempt == 1) ? primaryModel : fallbackModel;

            try {
                log.info("[v2.9.88] Scenario generation attempt {}/{} with model: {}, chatId: {}", attempt, MAX_RETRIES, modelToUse, chatId);
                scenario = callGeminiApi(apiKey, modelToUse, systemPrompt, userPrompt, creatorId, openingNarrationLength);
                log.info("[v2.9.88] Attempt {}/{} succeeded with model: {}", attempt, MAX_RETRIES, modelToUse);
            } catch (Exception ex) {
                lastException = ex;
                log.warn("[v2.9.88] Attempt {}/{} failed with model {}: {}",
                    attempt, MAX_RETRIES, modelToUse, ex.getMessage());

                if (attempt < MAX_RETRIES) {
                    // JSON 구조 오류인 경우 짧은 대기 후 재시도
                    if (ex.getMessage().contains("videoPrompt") || ex.getMessage().contains("JSON") || ex.getMessage().contains("opening")) {
                        try {
                            log.info("[v2.9.88] JSON structure error detected, retrying in 2 seconds...");
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } else if (ex.getMessage().contains("503") || ex.getMessage().contains("overload")) {
                        // 503 오류인 경우 더 긴 대기
                        try {
                            log.info("[v2.9.88] 503 error detected, retrying in 5 seconds...");
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        if (scenario == null) {
            log.error("[v2.9.88] All {} attempts failed for chatId: {}. Last error: {}", MAX_RETRIES, chatId, lastException != null ? lastException.getMessage() : "unknown");
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED,
                "시나리오 생성 실패 (재시도 " + MAX_RETRIES + "회 모두 실패): " + (lastException != null ? lastException.getMessage() : "알 수 없는 오류"));
        }

        // v2.9.143: 모든 슬라이드에서 나레이션 확장 실행
        if (slideCount > NARRATION_EXPAND_THRESHOLD) {
            String narrationExpandModel = genreConfigService.getNarrationExpandModel(creatorId);
            log.info("[v2.9.143] Narration expansion triggered: {} slides, chatId: {}", slideCount, chatId);

            // v2.9.75: 나레이션 확장 단계 시작 알림
            if (chatId != null) {
                ContentService.updateScenarioProgress(chatId, "NARRATION_EXPAND", 0, slideCount, "나레이션 확장 중...");
            }

            scenario = expandNarrations(apiKey, narrationExpandModel, scenario, creatorId, chatId);
        }

        return scenario;
    }

    /**
     * v2.9.84: 참조 이미지 분석 결과를 포함한 시나리오 생성
     * 참조 이미지의 캐릭터/스타일/분위기 정보를 시나리오에 반영
     */
    @Override
    public VideoDto.ScenarioInfo generateScenarioWithReferenceImage(
            Long userNo, String prompt, QualityTier tier, ContentType contentType,
            int slideCount, Long creatorId, Long chatId, String referenceImageAnalysis) {

        log.info("[v2.9.84] Generating scenario with reference image - userNo: {}, hasImageAnalysis: {}",
                userNo, referenceImageAnalysis != null && !referenceImageAnalysis.equals("{}"));

        // 참조 이미지 분석 결과가 있으면 프롬프트에 추가
        String enhancedPrompt = prompt;
        if (referenceImageAnalysis != null && !referenceImageAnalysis.isEmpty() && !referenceImageAnalysis.equals("{}")) {
            enhancedPrompt = appendReferenceImageToPrompt(prompt, referenceImageAnalysis);
            log.info("[v2.9.84] Enhanced prompt with reference image analysis, original length: {}, enhanced length: {}",
                    prompt != null ? prompt.length() : 0, enhancedPrompt.length());
        }

        // 기존 메서드로 시나리오 생성
        return generateScenario(userNo, enhancedPrompt, tier, contentType, slideCount, creatorId, chatId);
    }

    /**
     * v2.9.84: 참조 이미지 분석 결과를 프롬프트에 추가
     * 캐릭터 외모, 스타일, 분위기 정보를 시나리오 생성에 활용
     */
    private String appendReferenceImageToPrompt(String originalPrompt, String referenceImageAnalysis) {
        StringBuilder enhanced = new StringBuilder();

        if (originalPrompt != null && !originalPrompt.isEmpty()) {
            enhanced.append(originalPrompt);
        }

        enhanced.append("\n\n");

        try {
            JsonNode analysis = objectMapper.readTree(referenceImageAnalysis);

            // v2.9.85: REVIEW 장르 상품 분석인지 확인 (productName 필드 존재 여부로 판단)
            boolean isProductAnalysis = analysis.has("productName");

            if (isProductAnalysis) {
                // ===== 상품 분석 결과 (REVIEW 장르) =====
                enhanced.append("===== 🛍️ 상품 이미지 분석 결과 (PRODUCT IMAGE ANALYSIS) =====\n");
                enhanced.append("🚨🚨🚨 CRITICAL: 이 상품 정보는 사용자가 업로드한 상품 이미지를 AI가 분석한 결과입니다.\n");
                enhanced.append("🚨🚨🚨 모든 imagePrompt, videoPrompt에서 이 **정확한 상품**이 등장해야 합니다!\n\n");

                // 상품명
                if (analysis.has("productName") && !analysis.get("productName").asText().isEmpty()) {
                    enhanced.append("【🏷️ PRODUCT NAME (상품명)】\n");
                    enhanced.append(analysis.get("productName").asText()).append("\n");
                    enhanced.append("→ 이 상품을 리뷰하는 콘텐츠를 생성합니다.\n\n");
                }

                // 상품 카테고리
                if (analysis.has("productCategory") && !analysis.get("productCategory").asText().isEmpty()) {
                    enhanced.append("【📂 PRODUCT CATEGORY (상품 카테고리)】\n");
                    enhanced.append(analysis.get("productCategory").asText()).append("\n\n");
                }

                // 상품 특징
                if (analysis.has("productFeatures") && !analysis.get("productFeatures").asText().isEmpty()) {
                    enhanced.append("【✨ PRODUCT FEATURES (상품 특징)】\n");
                    enhanced.append(analysis.get("productFeatures").asText()).append("\n\n");
                }

                // 상품 외관
                if (analysis.has("productAppearance") && !analysis.get("productAppearance").asText().isEmpty()) {
                    enhanced.append("【👁️ PRODUCT APPEARANCE (상품 외관) - CRITICAL】\n");
                    enhanced.append(analysis.get("productAppearance").asText()).append("\n");
                    enhanced.append("→ ⚠️ imagePrompt에서 이 외관을 100% 정확하게 묘사해야 합니다!\n\n");
                }

                // 상품 색상
                if (analysis.has("productColors") && !analysis.get("productColors").asText().isEmpty()) {
                    enhanced.append("【🎨 PRODUCT COLORS (상품 색상) - CRITICAL】\n");
                    enhanced.append(analysis.get("productColors").asText()).append("\n");
                    enhanced.append("→ ⚠️ 생성되는 상품 이미지가 이 색상과 정확히 일치해야 합니다!\n\n");
                }

                // 포장
                if (analysis.has("packaging") && !analysis.get("packaging").asText().isEmpty()) {
                    enhanced.append("【📦 PACKAGING (포장)】\n");
                    enhanced.append(analysis.get("packaging").asText()).append("\n\n");
                }

                // 사용 맥락
                if (analysis.has("productContext") && !analysis.get("productContext").asText().isEmpty()) {
                    enhanced.append("【🖐️ PRODUCT CONTEXT (사용 맥락)】\n");
                    enhanced.append(analysis.get("productContext").asText()).append("\n");
                    enhanced.append("→ 리뷰어가 이 상품을 사용하는 장면을 이 맥락으로 생성하세요.\n\n");
                }

                enhanced.append("===== END PRODUCT IMAGE ANALYSIS =====\n");
                enhanced.append("🚨🚨🚨 절대 규칙: 위 상품과 다른 상품이 생성되면 안 됩니다!\n");
                enhanced.append("🚨🚨🚨 productFromReference 필드에 위 상품 정보를 그대로 넣으세요!\n");

            } else {
                // ===== 캐릭터/스타일 분석 결과 (일반 장르) =====
                enhanced.append("===== 🖼️ 참조 이미지 분석 결과 (REFERENCE IMAGE ANALYSIS) =====\n");
                enhanced.append("아래 정보는 사용자가 제공한 참조 이미지를 AI가 분석한 결과입니다.\n");
                enhanced.append("시나리오의 캐릭터, 스타일, 분위기를 이 참조 이미지와 일관되게 만들어주세요.\n\n");

                // 캐릭터 정보
                if (analysis.has("characterDescription") && !analysis.get("characterDescription").asText().isEmpty()) {
                    enhanced.append("【CHARACTER (캐릭터 참조)】\n");
                    enhanced.append(analysis.get("characterDescription").asText()).append("\n");
                    enhanced.append("→ characterBlock을 이 참조 이미지의 캐릭터와 유사하게 작성하세요.\n\n");
                }

                // 스타일 정보
                if (analysis.has("styleDescription") && !analysis.get("styleDescription").asText().isEmpty()) {
                    enhanced.append("【VISUAL STYLE (시각적 스타일 참조)】\n");
                    enhanced.append(analysis.get("styleDescription").asText()).append("\n");
                    enhanced.append("→ imagePrompt에 이 스타일을 반영하세요.\n\n");
                }

                // 의상 정보
                if (analysis.has("outfitDescription") && !analysis.get("outfitDescription").asText().isEmpty()) {
                    enhanced.append("【OUTFIT (의상 참조)】\n");
                    enhanced.append(analysis.get("outfitDescription").asText()).append("\n\n");
                }

                // 배경/환경 정보
                if (analysis.has("settingDescription") && !analysis.get("settingDescription").asText().isEmpty()) {
                    enhanced.append("【SETTING (배경 참조)】\n");
                    enhanced.append(analysis.get("settingDescription").asText()).append("\n\n");
                }

                // 색상 팔레트
                if (analysis.has("colorPalette") && !analysis.get("colorPalette").asText().isEmpty()) {
                    enhanced.append("【COLOR PALETTE】\n");
                    enhanced.append(analysis.get("colorPalette").asText()).append("\n\n");
                }

                // 분위기
                if (analysis.has("moodAtmosphere") && !analysis.get("moodAtmosphere").asText().isEmpty()) {
                    enhanced.append("【MOOD/ATMOSPHERE】\n");
                    enhanced.append(analysis.get("moodAtmosphere").asText()).append("\n\n");
                }

                enhanced.append("===== END REFERENCE IMAGE ANALYSIS =====\n");
                enhanced.append("⚠️ 위 참조 이미지의 캐릭터와 스타일을 시나리오에 반드시 반영하세요!\n");
            }

        } catch (Exception e) {
            log.warn("[v2.9.85] Failed to parse reference image analysis JSON, using raw: {}", e.getMessage());
            enhanced.append("===== 참조 이미지 분석 결과 =====\n");
            enhanced.append(referenceImageAnalysis).append("\n\n");
            enhanced.append("===== END =====\n");
        }

        return enhanced.toString();
    }

    /**
     * v2.9.62: Gemini API 호출 공통 메서드
     * 폴백 로직을 위해 API 호출 부분을 분리
     */
    private VideoDto.ScenarioInfo callGeminiApi(String apiKey, String model, String systemPrompt, String userPrompt, Long creatorId, int openingNarrationLength) throws Exception {
        String apiUrl = String.format(GEMINI_API_URL, model);

        // v2.9.70: 긴 출력을 위해 maxOutputTokens 명시적 설정
        // - thinkingConfig 제거: thinking 토큰이 출력 예산을 소비하여 나레이션 길이 제한 발생
        // - maxOutputTokens: 65536 설정 (20 슬라이드 × 3000자 = 60,000자 지원)
        // 참고: https://discuss.ai.google.dev/t/gemini-3-output-limited-to-4k-tokens-instead-of-65k/114011
        Map<String, Object> generationConfig = Map.of(
            "temperature", 0.8,
            "topP", 0.95,
            "topK", 40,
            "responseMimeType", "application/json",
            "maxOutputTokens", 65536
        );

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(
                    Map.of("text", systemPrompt + "\n\n" + userPrompt)
                ))
            ),
            "generationConfig", generationConfig
        );

        WebClient webClient = webClientBuilder.build();
        String response = webClient.post()
                .uri(apiUrl)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        log.debug("Gemini API response received (model: {}), length: {}", model, response != null ? response.length() : 0);

        return parseGeminiResponse(response, SLIDE_DURATION, creatorId, openingNarrationLength);
    }

    /**
     * 장르 기반 시스템 프롬프트 생성 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.154: userInput 파라미터 추가 - Base 템플릿의 {{USER_INPUT}} 치환용
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    private String buildSystemPromptForGenre(int slideCount, Long creatorId, String userInput, int openingNarrationLength) {
        // DB에서 장르별 시스템 프롬프트 조회 (필수)
        String dbPrompt = genreConfigService.getScenarioSystemPrompt(creatorId);

        // v2.9.0: 현재 연도 동적 주입 (경제 콘텐츠 시점 정확성)
        int currentYear = LocalDate.now().getYear();

        // v2.9.100: 슬라이드 나레이션 길이 DB에서 조회
        int slideNarrationLength = genreConfigService.getSlideNarrationLength(creatorId);

        log.info("Using DB prompt for creatorId: {}, SCENARIO_SYSTEM (length: {}), currentYear: {}, slideNarrationLength: {}, openingNarrationLength: {}",
            creatorId, dbPrompt.length(), currentYear, slideNarrationLength, openingNarrationLength);

        // DB 프롬프트에 슬라이드 수, 연도, 사용자 입력 등 동적 값 주입
        // v2.9.154: {{USER_INPUT}} 치환 추가 (Base 템플릿의 <task> 섹션에서 사용)
        // v2.9.175: {{OPENING_NARRATION_MAX}}, {{SLIDE_NARRATION_MAX}} 치환 추가 (최대 길이 130%)
        int openingNarrationMax = (int) (openingNarrationLength * 1.3);
        int slideNarrationMax = (int) (slideNarrationLength * 1.3);
        return dbPrompt
            .replace("{{SLIDE_COUNT}}", String.valueOf(slideCount))
            .replace("{{CURRENT_YEAR}}", String.valueOf(currentYear))
            .replace("{{SLIDE_NARRATION_LENGTH}}", String.valueOf(slideNarrationLength))
            .replace("{{SLIDE_NARRATION_MAX}}", String.valueOf(slideNarrationMax))
            .replace("{{OPENING_NARRATION_LENGTH}}", String.valueOf(openingNarrationLength))
            .replace("{{OPENING_NARRATION_MAX}}", String.valueOf(openingNarrationMax))
            .replace("{{USER_INPUT}}", userInput != null ? userInput : "");
    }

    // v2.9.73: duration 관련 레거시 메서드 모두 삭제됨
    // calculateSlideCountFromMinutes(), parseTargetDuration(), calculateSlideCount()
    // 이제 slideCount가 프론트엔드에서 직접 전달됨 (1-10장)

    // v2.9.9: 레거시 하드코딩 프롬프트 완전 제거 - 모든 프롬프트는 DB에서 관리
    // buildAudiobookDramaSystemPrompt(), buildDefaultCharacterBlock(),
    // buildDefaultSystemPrompt(), buildDefaultStoryStructure() 모두 삭제됨
    // 이제 모든 시나리오 프롬프트는 genre_prompts 테이블의 SCENARIO_SYSTEM 타입에서 조회

    // ===== 아래는 buildUserPrompt() - 사용자 입력을 포맷팅하는 역할만 수행 =====
    // (시스템 프롬프트는 DB에서 가져오고, 사용자 프롬프트만 여기서 생성)

    // ⚠️ v2.9.9: 이전에 여기에 있던 ~750줄의 레거시 하드코딩 프롬프트가 완전히 삭제되었습니다.
    // 모든 시나리오 시스템 프롬프트는 DB의 genre_prompts.SCENARIO_SYSTEM에서 가져옵니다.
    // ===== v2.9.73: 모든 사용자 프롬프트는 DB에서 로드 =====
    // 하드코딩 제거 - DB의 SCENARIO_USER_TEMPLATE 프롬프트 사용
    // 플레이스홀더: {{USER_INPUT}}, {{SLIDE_COUNT}}, {{CURRENT_YEAR}}, {{PREV_YEAR}}, {{NEXT_YEAR}}

    /**
     * v2.9.73: 사용자 프롬프트 생성 (100% DB 기반)
     * 모든 장르의 사용자 프롬프트 템플릿은 DB에서 로드하여 플레이스홀더 치환
     *
     * @param userPrompt 사용자 입력 (해시태그 + 채팅 내용)
     * @param slideCount 슬라이드 수 (1-10)
     * @param creatorId 장르 ID
     * @return DB 템플릿 기반 사용자 프롬프트
     */
    private String buildUserPrompt(String userPrompt, int slideCount, Long creatorId) {
        int currentYear = LocalDate.now().getYear();
        int estimatedMinutes = slideCount * 5;  // 슬라이드당 약 5분

        // v2.9.73: DB에서 장르별 사용자 프롬프트 템플릿 로드 + 플레이스홀더 치환
        return genreConfigService.buildScenarioUserPrompt(
            creatorId,
            userPrompt,
            slideCount,
            estimatedMinutes,
            currentYear
        );
    }

    // ===== 레거시 메서드 삭제됨 =====
    // v2.9.9: buildDefaultStoryStructure(), buildDefaultSystemPrompt(), buildDefaultCharacterBlock(), buildAudiobookDramaSystemPrompt()
    // v2.9.13: buildFinanceUserPrompt() - DB의 SCENARIO_USER_TEMPLATE 프롬프트로 대체

    /**
     * Gemini API 응답 파싱
     * v2.9.102: creatorId 파라미터 추가 - 고정 캐릭터 characterBlock 적용
     */
    private VideoDto.ScenarioInfo parseGeminiResponse(String response, int slideDuration, Long creatorId, int openingNarrationLength) throws Exception {
        JsonNode root = objectMapper.readTree(response);

        // candidates[0].content.parts[0].text 추출
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            log.error("No candidates in Gemini response");
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED, "Gemini API 응답이 비어있습니다.");
        }

        String jsonText = candidates.get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();

        log.info("Extracted scenario JSON length: {}", jsonText.length());

        // JSON 전처리 - Gemini가 마크다운 코드블록으로 감쌀 수 있음
        String cleanedJson = jsonText.trim();
        if (cleanedJson.startsWith("```json")) {
            cleanedJson = cleanedJson.substring(7); // "```json" 제거
        } else if (cleanedJson.startsWith("```")) {
            cleanedJson = cleanedJson.substring(3); // "```" 제거
        }
        if (cleanedJson.endsWith("```")) {
            cleanedJson = cleanedJson.substring(0, cleanedJson.length() - 3); // "```" 제거
        }
        cleanedJson = cleanedJson.trim();

        log.debug("Cleaned JSON preview (first 500 chars): {}",
                cleanedJson.substring(0, Math.min(500, cleanedJson.length())));

        // JSON 파싱
        JsonNode scenarioNode;
        try {
            scenarioNode = objectMapper.readTree(cleanedJson);

            // v2.9.137: AI가 배열([])로 응답한 경우 첫 번째 요소 사용
            if (scenarioNode.isArray() && scenarioNode.size() > 0) {
                log.warn("[Scenario] AI가 배열로 응답함. 첫 번째 요소 사용. 배열 크기: {}", scenarioNode.size());
                scenarioNode = scenarioNode.get(0);
            } else if (scenarioNode.isArray() && scenarioNode.size() == 0) {
                log.error("[Scenario] AI가 빈 배열([])로 응답함");
                throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED,
                        "AI가 빈 배열을 반환했습니다. 시나리오를 다시 생성해주세요.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception parseEx) {
            log.error("JSON parsing failed. First 1000 chars: {}",
                    cleanedJson.substring(0, Math.min(1000, cleanedJson.length())));
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED,
                    "시나리오 JSON 파싱 실패: " + parseEx.getMessage());
        }

        // 오프닝 파싱 + ⚠️ 필수 검증 (폴백 절대 금지!)
        JsonNode openingNode = scenarioNode.path("opening");
        String videoPrompt = openingNode.path("videoPrompt").asText("").trim();
        String openingNarration = openingNode.path("narration").asText("").trim();

        // ⚠️ 오프닝 videoPrompt 필수 검증
        if (videoPrompt.isEmpty()) {
            // v2.9.136: 디버깅을 위해 전체 JSON 구조 로깅
            log.error("[Scenario] ❌ CRITICAL: AI가 opening.videoPrompt를 생성하지 않았습니다!");
            log.error("[Scenario] openingNode: {}", openingNode.toString());
            List<String> keys = new ArrayList<>();
            scenarioNode.fieldNames().forEachRemaining(keys::add);
            log.error("[Scenario] scenarioNode keys: {}", keys);
            log.error("[Scenario] Full JSON (first 2000 chars): {}",
                cleanedJson.substring(0, Math.min(2000, cleanedJson.length())));
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED,
                "AI가 오프닝 영상 프롬프트(videoPrompt)를 생성하지 않았습니다. 시나리오를 다시 생성해주세요.");
        }

        // ⚠️ 오프닝 narration 필수 검증
        if (openingNarration.isEmpty()) {
            log.error("[Scenario] ❌ CRITICAL: AI가 opening.narration을 생성하지 않았습니다! openingNode: {}", openingNode.toString());
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED,
                "AI가 오프닝 나레이션을 생성하지 않았습니다. 시나리오를 다시 생성해주세요.");
        }

        // v2.9.108: 오프닝 나레이션 길이 검증 - 파라미터로 전달받은 값 사용
        int openingNarrationMin = (int) (openingNarrationLength * 0.7);  // 70%를 최소값으로
        int openingNarrationMax = (int) (openingNarrationLength * 1.3);  // 130%를 최대값으로

        if (openingNarration.length() < openingNarrationMin) {
            log.warn("[Scenario] ⚠️ 오프닝 나레이션이 {}자로 권장({}자의 70%={}) 미만. AI 결과 존중.",
                openingNarration.length(), openingNarrationLength, openingNarrationMin);
        } else if (openingNarration.length() > openingNarrationMax) {
            log.warn("[Scenario] ⚠️ 오프닝 나레이션이 {}자로 권장({}자의 130%={}) 초과. AI 결과 존중. 내용: {}",
                openingNarration.length(), openingNarrationLength, openingNarrationMax, openingNarration);
        }

        log.info("[Scenario] ✅ Opening validated - videoPrompt length: {}, narration length: {}",
            videoPrompt.length(), openingNarration.length());

        // v2.9.143: AI 생성 프롬프트에 크리에이터 정보 플레이스홀더 치환
        // {{CREATOR_NAME}}, {{YOUTUBE_CHANNEL}} 등이 있으면 실제 값으로 대체
        String injectedVideoPrompt = genreConfigService.injectFixedCharacterValues(videoPrompt, creatorId);
        if (!injectedVideoPrompt.equals(videoPrompt)) {
            log.info("[v2.9.143] ✅ Opening videoPrompt 플레이스홀더 치환 완료");
        }

        VideoDto.OpeningScene opening = VideoDto.OpeningScene.builder()
                .videoPrompt(injectedVideoPrompt)
                .narration(openingNarration)
                .durationSeconds(OPENING_DURATION)
                .build();

        // 슬라이드 파싱
        List<VideoDto.SlideScene> slides = new ArrayList<>();
        JsonNode slidesNode = scenarioNode.path("slides");

        if (slidesNode.isArray()) {
            for (int i = 0; i < slidesNode.size(); i++) {
                JsonNode slideNode = slidesNode.get(i);

                // v2.9.143: AI 생성 imagePrompt에 크리에이터 정보 플레이스홀더 치환
                String rawImagePrompt = slideNode.path("imagePrompt").asText("");
                String injectedImagePrompt = genreConfigService.injectFixedCharacterValues(rawImagePrompt, creatorId);
                if (!injectedImagePrompt.equals(rawImagePrompt)) {
                    log.debug("[v2.9.143] ✅ Slide {} imagePrompt 플레이스홀더 치환 완료", i + 1);
                }

                slides.add(VideoDto.SlideScene.builder()
                        .order(i + 1)
                        .imagePrompt(injectedImagePrompt)
                        .narration(slideNode.path("narration").asText(""))
                        .durationSeconds(slideNode.path("duration").asInt(slideDuration))
                        .transition(slideNode.path("transition").asText("fade"))
                        .build());
            }
        }

        log.info("Parsed scenario: {} slides, opening: {}s", slides.size(), OPENING_DURATION);

        // 전체 나레이션 조합
        StringBuilder fullNarration = new StringBuilder();
        fullNarration.append(opening.getNarration()).append(" ");
        for (VideoDto.SlideScene slide : slides) {
            fullNarration.append(slide.getNarration()).append(" ");
        }

        // ⚠️ v2.9.133: 버추얼 크리에이터 characterBlock 적용 (creator_prompts.character_block_full 컬럼)
        // 크리에이터에 characterBlockFull이 설정되어 있으면 AI 생성 characterBlock 대신 DB 값 사용
        String characterBlock;
        String dbCharacterBlock = genreConfigService.getCharacterBlockFull(creatorId);

        if (dbCharacterBlock != null && !dbCharacterBlock.trim().isEmpty()) {
            // 버추얼 크리에이터 - DB에서 가져온 characterBlockFull 적용
            characterBlock = dbCharacterBlock;
            log.info("✅ [v2.9.133] 버추얼 크리에이터 characterBlockFull 적용: creatorId={}, {} chars",
                creatorId, characterBlock.length());
        } else {
            // AI 생성 characterBlock 사용 (기존 로직)
            characterBlock = scenarioNode.path("characterBlock").asText("");
            if (characterBlock == null || characterBlock.trim().isEmpty()) {
                log.warn("⚠️ [캐릭터 일관성 경고] AI가 characterBlock을 생성하지 않았습니다!");
                log.warn("⚠️ 모든 이미지/영상에서 캐릭터가 다르게 나올 수 있습니다.");
                log.warn("⚠️ 프롬프트를 확인하고 AI가 characterBlock을 생성하도록 유도해야 합니다.");
            } else {
                log.info("✅ AI characterBlock 생성 완료: {} chars", characterBlock.length());
                if (characterBlock.length() < 100) {
                    log.warn("⚠️ characterBlock이 너무 짧습니다 ({} chars). 최소 200자 이상 권장.", characterBlock.length());
                }
            }
        }

        return VideoDto.ScenarioInfo.builder()
                .title(scenarioNode.path("title").asText("Untitled"))
                .description(scenarioNode.path("hook").asText(""))
                .opening(opening)
                .slides(slides)
                .fullNarration(fullNarration.toString().trim())
                .characterBlock(characterBlock)
                .build();
    }

    // ===== v2.9.72: 나레이션 확장 (Pro로 시나리오 → Flash로 나레이션 확장) =====

    /**
     * v2.9.94: 모든 슬라이드의 나레이션을 확장
     *
     * 전략:
     * 1. Pro가 생성한 전체 시나리오를 컨텍스트로 제공
     * 2. 각 슬라이드마다 폴백 시나리오 모델을 호출하여 나레이션을 2000-2500자로 확장
     * 3. 스토리 일관성 유지 (전체 시나리오 + 이전/다음 슬라이드 정보 제공)
     * 4. v2.9.75: chatId가 있으면 진행 상황 업데이트
     * 5. v2.9.94: 1500자 이상이면 확장하지 않음
     */
    private VideoDto.ScenarioInfo expandNarrations(
            String apiKey, String flashModel,
            VideoDto.ScenarioInfo scenario, Long creatorId, Long chatId
    ) {
        int totalSlides = scenario.getSlides().size();
        log.info("[v2.9.97] Starting narration expansion for {} slides, chatId: {}", totalSlides, chatId);

        List<VideoDto.SlideScene> expandedSlides = new ArrayList<>();
        List<VideoDto.SlideScene> originalSlides = scenario.getSlides();

        // v2.9.97: 사용자 원본 입력 조회 (나레이션 확장 시 컨텍스트로 활용)
        String userInput = null;
        if (chatId != null) {
            try {
                Conversation conversation = conversationMapper.findById(chatId).orElse(null);
                if (conversation != null && conversation.getInitialPrompt() != null) {
                    userInput = conversation.getInitialPrompt();
                    log.info("[v2.9.97] User input loaded for narration expansion: {}자", userInput.length());
                }
            } catch (Exception e) {
                log.warn("[v2.9.97] Failed to load user input for chatId {}: {}", chatId, e.getMessage());
            }
        }

        // 전체 시나리오 요약 생성 (Flash에게 컨텍스트 제공)
        String scenarioContext = buildScenarioContext(scenario);

        for (int i = 0; i < originalSlides.size(); i++) {
            VideoDto.SlideScene slide = originalSlides.get(i);
            int currentNarrationLength = slide.getNarration() != null ? slide.getNarration().length() : 0;

            // v2.9.75: 진행 상황 업데이트
            if (chatId != null) {
                ContentService.updateScenarioProgress(chatId, "NARRATION_EXPAND", i, totalSlides,
                    String.format("슬라이드 %d/%d 나레이션 확장 중...", i + 1, totalSlides));
            }

            // v2.9.98: 나레이션 확장 기준 길이를 DB에서 조회
            int narrationExpandLength = genreConfigService.getNarrationExpandLength(creatorId);

            // 나레이션이 이미 충분히 길면 확장 불필요
            if (currentNarrationLength >= narrationExpandLength) {
                log.info("[v2.9.98] Slide {} already has {}자 (기준: {}자), keeping as is", i + 1, currentNarrationLength, narrationExpandLength);
                expandedSlides.add(slide);
                continue;
            }

            // v2.9.111: 이전/다음 슬라이드 정보 추출 (전체 나레이션 + 슬라이드 번호 포함)
            // 기존 300자 제한 → 전체 나레이션으로 변경하여 AI가 중복 방지 가능
            String previousSlideInfo = (i > 0) ? buildSlideInfoFull(originalSlides.get(i - 1), i) : null;
            String nextSlideInfo = (i < originalSlides.size() - 1) ? buildSlideInfoFull(originalSlides.get(i + 1), i + 2) : null;

            // v2.9.100: Flash로 나레이션 확장 (503 재시도 로직 추가)
            String expandedNarration = null;
            int maxRetries = 3;
            long retryDelayMs = 3000; // 재시도 간격 3초 시작

            // v2.9.100: 첫 시도 전 3초 대기 (API 부하 방지)
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    expandedNarration = expandSingleNarration(
                        apiKey, flashModel, scenario, slide, i + 1,
                        scenarioContext, previousSlideInfo, nextSlideInfo, creatorId, userInput
                    );

                    log.info("[v2.9.100] Slide {} narration expanded: {}자 → {}자 (attempt {})",
                        i + 1, currentNarrationLength, expandedNarration.length(), attempt);
                    break; // 성공 시 루프 탈출

                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    boolean is503 = errorMsg != null && errorMsg.contains("503");

                    if (is503 && attempt < maxRetries) {
                        log.warn("[v2.9.100] Slide {} narration expansion failed (attempt {}/{}): 503 error, retrying in {}ms...",
                            i + 1, attempt, maxRetries, retryDelayMs);
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        retryDelayMs *= 2; // 지수 백오프: 3초 → 6초 → 12초
                    } else {
                        log.error("[v2.9.100] Slide {} narration expansion failed (attempt {}/{}): {}",
                            i + 1, attempt, maxRetries, errorMsg);
                    }
                }
            }

            if (expandedNarration != null) {
                expandedSlides.add(VideoDto.SlideScene.builder()
                    .order(slide.getOrder())
                    .imagePrompt(slide.getImagePrompt())
                    .narration(expandedNarration)
                    .durationSeconds(slide.getDurationSeconds())
                    .transition(slide.getTransition())
                    .build());
            } else {
                // 모든 재시도 실패 시 원본 유지
                log.warn("[v2.9.100] Slide {} keeping original narration ({}자) after {} retries failed",
                    i + 1, currentNarrationLength, maxRetries);
                expandedSlides.add(slide);
            }
        }

        // 전체 나레이션 재조합
        StringBuilder fullNarration = new StringBuilder();
        fullNarration.append(scenario.getOpening().getNarration()).append(" ");
        for (VideoDto.SlideScene slide : expandedSlides) {
            fullNarration.append(slide.getNarration()).append(" ");
        }

        log.info("[v2.9.72] Narration expansion completed. Total narration: {}자", fullNarration.length());

        return VideoDto.ScenarioInfo.builder()
            .title(scenario.getTitle())
            .description(scenario.getDescription())
            .opening(scenario.getOpening())
            .slides(expandedSlides)
            .fullNarration(fullNarration.toString().trim())
            .characterBlock(scenario.getCharacterBlock())
            .build();
    }

    /**
     * v2.9.97: 단일 슬라이드 나레이션 확장 (userInput 추가)
     */
    private String expandSingleNarration(
            String apiKey, String flashModel,
            VideoDto.ScenarioInfo scenario, VideoDto.SlideScene slide, int slideNumber,
            String scenarioContext, String previousSlideInfo, String nextSlideInfo,
            Long creatorId, String userInput
    ) throws Exception {
        String apiUrl = String.format(GEMINI_API_URL, flashModel);

        // v2.9.98: DB에서 나레이션 확장 프롬프트 조회 및 플레이스홀더 치환
        int expandLength = genreConfigService.getNarrationExpandLength(creatorId);
        String expandPrompt = genreConfigService.buildNarrationExpandPrompt(
            creatorId,
            expandLength,
            userInput,
            scenario.getTitle(),
            scenario.getDescription(),
            scenario.getSlides().size(),
            scenarioContext,
            previousSlideInfo,
            slideNumber,
            slide.getImagePrompt(),
            slide.getNarration(),
            nextSlideInfo
        );

        Map<String, Object> generationConfig = Map.of(
            "temperature", 0.8,
            "topP", 0.95,
            "topK", 40,
            "maxOutputTokens", 16384  // 나레이션 하나에 충분한 토큰
        );

        // v2.9.159: Google Search Grounding 삭제 (사용하지 않음)
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", expandPrompt)))
            ),
            "generationConfig", generationConfig
        );

        WebClient webClient = webClientBuilder.build();
        String response = webClient.post()
                .uri(apiUrl)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // 응답에서 나레이션 텍스트만 추출
        return parseNarrationResponse(response);
    }

    /**
     * v2.9.72: 전체 시나리오 컨텍스트 생성
     */
    private String buildScenarioContext(VideoDto.ScenarioInfo scenario) {
        StringBuilder sb = new StringBuilder();

        sb.append("오프닝: ").append(scenario.getOpening().getNarration()).append("\n\n");

        for (int i = 0; i < scenario.getSlides().size(); i++) {
            VideoDto.SlideScene slide = scenario.getSlides().get(i);
            String narrationPreview = slide.getNarration();
            if (narrationPreview != null && narrationPreview.length() > 200) {
                narrationPreview = narrationPreview.substring(0, 200) + "...";
            }
            sb.append("슬라이드 ").append(i + 1).append(": ").append(narrationPreview).append("\n");
        }

        return sb.toString();
    }

    /**
     * v2.9.111: 슬라이드 전체 정보 (나레이션 전체 + 슬라이드 번호)
     * 중복 방지를 위해 이전 슬라이드 전체 내용을 AI에게 전달
     * 300자 제한을 제거하여 AI가 전체 맥락을 파악하고 중복을 방지할 수 있도록 함
     */
    private String buildSlideInfoFull(VideoDto.SlideScene slide, int slideNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append("[슬라이드 ").append(slideNumber).append("번]\n");
        sb.append("이미지: ").append(slide.getImagePrompt()).append("\n");
        sb.append("나레이션 전체:\n").append(slide.getNarration());  // 전체 나레이션 (잘림 없음)
        return sb.toString();
    }

    /**
     * v2.9.73: 나레이션 응답 파싱 (순수 텍스트 추출 + 메타 텍스트 제거)
     */
    private String parseNarrationResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode candidates = root.path("candidates");

        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new ApiException(ErrorCode.SCENARIO_GENERATION_FAILED, "나레이션 확장 응답이 비어있습니다.");
        }

        String narration = candidates.get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();

        // 마크다운 코드블록 제거
        narration = narration.trim();
        if (narration.startsWith("```")) {
            int endIndex = narration.indexOf("```", 3);
            if (endIndex > 0) {
                narration = narration.substring(narration.indexOf('\n') + 1, endIndex).trim();
            }
        }

        // v2.9.73: 메타 텍스트 제거 (TTS가 읽으면 안 되는 텍스트)
        narration = cleanNarrationMetaText(narration);

        // v2.9.77: truncation 로직 제거 - 프롬프트로만 길이 제어
        log.debug("[v2.9.77] Parsed narration length: {}자", narration.length());
        return narration;
    }

    /**
     * v2.9.117: 나레이션에서 메타 텍스트 제거
     * TTS가 읽으면 안 되는 구조적 텍스트를 제거
     * AI가 thinking process를 출력한 경우도 처리
     */
    private String cleanNarrationMetaText(String narration) {
        if (narration == null || narration.isEmpty()) {
            return narration;
        }

        String original = narration;

        // v2.9.117: AI thinking process 감지 및 한국어 나레이션만 추출
        // AI가 메타 지시문을 출력한 경우 (영어 키워드로 시작하면 문제 있음)
        if (narration.trim().startsWith("thoughtful") ||
            narration.contains("*   Topic:") ||
            narration.contains("Current Slide:") ||
            narration.contains("*Drafting content:*") ||
            narration.contains("Character count check:")) {

            log.warn("[v2.9.117] AI thinking process detected in narration! Attempting to extract actual content.");

            // 한국어 나레이션만 추출 (한글이 50% 이상인 연속 줄들)
            StringBuilder extracted = new StringBuilder();
            String[] lines = narration.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // 영어 메타 패턴 건너뛰기
                if (trimmed.startsWith("*") ||
                    trimmed.startsWith("Topic:") ||
                    trimmed.startsWith("Current") ||
                    trimmed.startsWith("Previous") ||
                    trimmed.startsWith("No ") ||
                    trimmed.startsWith("Must ") ||
                    trimmed.startsWith("Length:") ||
                    trimmed.startsWith("Tone:") ||
                    trimmed.startsWith("Output:") ||
                    trimmed.contains("Character count") ||
                    trimmed.equals("thoughtful") ||
                    trimmed.matches("^[A-Za-z\\s]+:.*") ||
                    trimmed.matches("^\\(.*\\)$")) {
                    continue;
                }

                // 한글이 30% 이상 포함된 줄만 추가
                long koreanCount = trimmed.chars()
                    .filter(c -> (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E))
                    .count();
                double koreanRatio = (double) koreanCount / trimmed.length();

                if (koreanRatio >= 0.3) {
                    if (extracted.length() > 0) {
                        extracted.append("\n");
                    }
                    extracted.append(trimmed);
                }
            }

            if (extracted.length() > 100) {
                narration = extracted.toString();
                log.info("[v2.9.117] Extracted Korean narration from AI thinking: {}자 → {}자",
                    original.length(), narration.length());
            }
        }

        // 기존 메타 텍스트 패턴 제거 (줄 시작 기준)
        String[] metaPatterns = {
            "(?m)^오프닝:\\s*",
            "(?m)^슬라이드\\s*\\d+[:\\.]\\s*",
            "(?m)^\\d+\\.\\s+",
            "(?m)^\\[.*?\\]\\s*",
            "(?m)^【.*?】\\s*",
            "(?m)^=+.*?=+\\s*",
            "(?m)^---+\\s*",
            "(?m)^\\*\\*.*?\\*\\*:\\s*",
            // v2.9.117: 영어 메타 패턴 추가
            "(?m)^\\*\\s+.*?:\\s*$",
            "(?m)^\\(.*?\\)\\s*$"
        };

        String cleaned = narration;
        for (String pattern : metaPatterns) {
            cleaned = cleaned.replaceAll(pattern, "");
        }

        // 연속 줄바꿈 정리
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

        if (!cleaned.equals(original)) {
            log.info("[v2.9.117] Cleaned meta text from narration: {}자 → {}자", original.length(), cleaned.length());
        }

        return cleaned;
    }
}
