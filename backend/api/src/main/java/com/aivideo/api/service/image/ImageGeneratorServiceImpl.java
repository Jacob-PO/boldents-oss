package com.aivideo.api.service.image;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.aivideo.api.entity.ApiBatchSettings;
import com.aivideo.api.entity.ApiRateLimit;
import com.aivideo.api.entity.CreatorPrompt;
import com.aivideo.api.entity.VideoFormat;
import com.aivideo.api.mapper.VideoFormatMapper;
import com.aivideo.api.service.RateLimitService;
import com.aivideo.api.util.AdaptiveRateLimiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGeneratorServiceImpl implements ImageGeneratorService {

    private final com.aivideo.api.service.ApiKeyService apiKeyService;
    private final com.aivideo.api.service.CreatorConfigService genreConfigService;
    private final com.aivideo.api.mapper.VideoFormatMapper videoFormatMapper;  // v2.9.25: 영상 포맷

    // v2.9.13: DB 기반 Rate Limit 서비스 (하드코딩 완전 제거)
    private final RateLimitService rateLimitService;

    // 스레드 로컬 변수로 현재 요청의 장르 ID 저장
    private final ThreadLocal<Long> currentCreatorId = new ThreadLocal<>();

    // v2.9.25: 스레드 로컬 변수로 현재 요청의 포맷 ID 저장
    private final ThreadLocal<Long> currentFormatId = new ThreadLocal<>();

    // v2.9.63: 썸네일용 모델 오버라이드 (v2.9.158: 해당 크리에이터 티어의 모델 사용)
    private final ThreadLocal<Boolean> thumbnailMode = new ThreadLocal<>();

    // Gemini 3 Text API for prompt modification
    @Value("${ai.tier.standard.scenario-model:gemini-3-flash-preview}")
    private String textModel;

    // v2.9.11: Bean 주입으로 변경 (HttpClientConfig에서 관리)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 스레드 로컬 변수로 현재 요청의 API 키 저장
    private final ThreadLocal<String> currentApiKey = new ThreadLocal<>();

    // v2.5.8: 스레드 로컬 변수로 시나리오 컨텍스트 저장 (전체 스토리 일관성 유지)
    private final ThreadLocal<ScenarioContext> currentScenarioContext = new ThreadLocal<>();

    // Gemini Text API 엔드포인트
    private static final String GEMINI_TEXT_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    // 안전 필터 재시도 설정
    private static final int MAX_SAFETY_RETRIES = 3;

    // v2.9.13: 503 오버로드 재시도 설정은 DB에서 관리 (RateLimitService)
    // 아래 상수들은 레거시 호환용으로만 유지 (실제로는 config 사용)

    // v2.8.4: 폴백 모델은 DB에서 조회 (FALLBACK_IMAGE_MODEL 상수 제거)

    /**
     * v2.9.13: 이미지 모델용 AdaptiveRateLimiter 조회 (DB 기반)
     * RateLimitService가 싱글톤 인스턴스를 관리
     */
    private AdaptiveRateLimiter getImageRateLimiter(String modelName) {
        return rateLimitService.getRateLimiter(modelName);
    }

    // v2.9.114: 모든 하드코딩된 배열 삭제 - 카메라/조명/구도 모두 DB의 IMAGE_STYLE에서 관리
    // CAMERA_SHOTS, COMPOSITIONS, LIGHTING_MOODS, EMOTIONAL_DIRECTIONS, CHARACTER_CONFIGURATIONS, DRAMATIC_ACTIONS, SCENE_LOCATIONS
    // 프롬프트는 무조건 DB에서 와야 함!

    // ========== v2.9.13: 배치 처리 설정은 DB에서 관리 ==========
    // 참고: https://ai.google.dev/gemini-api/docs/rate-limits
    // 모든 Rate Limit 설정은 api_rate_limits, api_batch_settings 테이블에서 조회
    // 하드코딩 상수 완전 제거 (BATCH_SIZE, INTRA_BATCH_DELAY_MS, BATCH_DELAY_MS, ERROR_BACKOFF_MS, MAX_CONSECUTIVE_ERRORS)

    // Gemini Image API 엔드포인트
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    // Imagen API 엔드포인트 (predict 사용)
    private static final String IMAGEN_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:predict";

    // 이미지 저장 디렉토리
    private static final String IMAGE_DIR = "/tmp/aivideo/images";

    // ========== Visual Style Context (콘텐츠 일관성 유지 시스템) ==========
    /**
     * 세션 전체에서 일관된 스타일을 유지하기 위한 컨텍스트
     * PROMPT_ENGINEERING_GUIDE.md의 Identity Block + Style Glossary 시스템 적용
     */
    @Getter
    @Builder
    public static class VisualStyleContext {
        private final String sessionId;           // 세션 고유 ID (스타일 일관성 참조용)
        private final String characterBlock;      // 캐릭터 Identity Block
        private final String styleGlossary;       // 스타일 용어집 (색상, 조명, 질감)
        private final String colorPalette;        // 색상 팔레트
        private final String lightingSetup;       // 조명 설정
        private final String moodAtmosphere;      // 분위기/톤
        private final String cameraStyle;         // 카메라 스타일
        private final String negativePrompt;      // 공통 네거티브 프롬프트

        /**
         * v2.8.2: 시나리오 기반 스타일 컨텍스트 생성 (하드코딩 제거)
         * v2.9.25: 포맷별 방향(orientation) 동적 설정
         * 모든 스타일 정보는 시나리오의 characterBlock에서 가져옴
         * 기술적 요소(카메라, 조명)만 기본값 사용
         */
        public static VisualStyleContext createFromScenario(String characterBlock, String negativePrompt, String orientation) {
            // v2.9.117: 하드코딩 스타일 완전 제거 - 모든 스타일은 DB IMAGE_STYLE 프롬프트에서
            // characterBlock과 negativePrompt만 전달, 나머지는 DB 프롬프트에 포함됨
            return VisualStyleContext.builder()
                    .sessionId(UUID.randomUUID().toString().substring(0, 8))
                    .characterBlock(characterBlock)
                    // v2.9.117: 스타일 필드 제거 - DB IMAGE_STYLE에서 로드
                    .styleGlossary("")
                    .colorPalette("")
                    .lightingSetup("")
                    .moodAtmosphere("")
                    .cameraStyle(orientation)  // 방향만 유지 (16:9 또는 9:16)
                    .negativePrompt(negativePrompt)
                    .build();
        }

        /**
         * @deprecated v2.8.2: 하드코딩된 스타일 사용 금지 - createFromScenario() 사용
         */
        @Deprecated
        public static VisualStyleContext createKoreanDramaStyle(String characterBlock) {
            throw new UnsupportedOperationException(
                "v2.8.2: 하드코딩된 스타일 사용 금지! createFromScenario(characterBlock, negativePrompt)를 사용하세요.");
        }

        /**
         * 프롬프트에 적용할 전체 스타일 컨텍스트 문자열 생성
         * v2.9.117: 하드코딩 스타일 제거 - 캐릭터 블록과 방향만 포함
         */
        public String toStylePrefix() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== VISUAL CONSISTENCY CONTEXT [Session: ").append(sessionId).append("] ===\n");
            sb.append("IMPORTANT: Maintain exact visual consistency with all other images in this session.\n\n");

            if (characterBlock != null && !characterBlock.isEmpty()) {
                sb.append("=== CHARACTER IDENTITY (MUST MATCH EXACTLY) ===\n");
                sb.append(characterBlock).append("\n\n");
            }

            // v2.9.117: 스타일 필드는 DB IMAGE_STYLE에서 로드하므로 여기서는 방향만 출력
            if (cameraStyle != null && !cameraStyle.isEmpty()) {
                sb.append("=== ORIENTATION ===\n");
                sb.append(cameraStyle).append("\n\n");
            }

            return sb.toString();
        }

        public String toNegativePromptSuffix() {
            return "\n\n=== STRICT AVOIDANCE ===\n" + negativePrompt;
        }
    }

    @Override
    @Deprecated
    public List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, String characterBlock) {
        // v2.5.8: 이 메서드는 더 이상 사용하면 안 됨! ScenarioContext가 필수!
        log.error("❌ [경고] deprecated된 generateImages(characterBlock) 호출됨! ScenarioContext를 사용해야 합니다.");
        throw new ApiException(ErrorCode.INVALID_REQUEST,
            "이미지 생성에는 전체 시나리오 컨텍스트가 필요합니다. generateImages(ScenarioContext)를 사용해주세요.");
    }

    @Override
    public List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, ScenarioContext context) {
        // 기본 장르(creatorId=null)로 위임
        return generateImages(userNo, slides, tier, context, null);
    }

    @Override
    public List<String> generateImages(Long userNo, List<VideoDto.SlideScene> slides, QualityTier tier, ScenarioContext context, Long creatorId) {
        // v2.5.8: ScenarioContext 필수! 폴백 없음!
        if (context == null) {
            log.error("❌ [치명적 오류] ScenarioContext가 null입니다! DB에서 시나리오를 불러오지 못했습니다.");
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "시나리오 정보가 없습니다. 시나리오를 먼저 생성해주세요. (ScenarioContext is required)");
        }

        // v2.8.5: 장르에 따라 characterBlock 검증 (금융 등 비-캐릭터 장르는 선택적)
        boolean isCharacterRequired = isCharacterRequiredForGenre(creatorId);
        if (isCharacterRequired && (context.getCharacterBlock() == null || context.getCharacterBlock().trim().isEmpty())) {
            log.error("❌ [치명적 오류] characterBlock이 없습니다! 시나리오에서 캐릭터 정보가 생성되지 않았습니다. creatorId: {}", creatorId);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "캐릭터 정보가 없습니다. 시나리오를 다시 생성해주세요. (characterBlock is required)");
        }
        if (!isCharacterRequired) {
            log.info("[IMAGE] creatorId={} - 비-캐릭터 장르, characterBlock 검증 건너뜀", creatorId);
        }

        // 사용자 API 키 조회 및 스레드 로컬에 저장
        String apiKey = apiKeyService.getServiceApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 설정되지 않았습니다. 마이페이지에서 Google API 키를 등록해주세요.");
        }
        currentApiKey.set(apiKey);

        // v2.8.0: 장르 ID 저장 (장르별 이미지 스타일 적용)
        currentCreatorId.set(creatorId);
        log.info("[IMAGE] creatorId set: {}", creatorId);

        // v2.5.8: 시나리오 컨텍스트 저장 (전체 스토리 일관성 유지)
        currentScenarioContext.set(context);
        log.info("[IMAGE] ScenarioContext set - title: {}, totalSlides: {}, characterBlockLength: {}, hasStoryOutline: {}",
                context.getTitle(),
                context.getTotalSlides(),
                context.getCharacterBlock() != null ? context.getCharacterBlock().length() : 0,
                context.getStoryOutline() != null && !context.getStoryOutline().isEmpty());

        try {
            return generateImagesInternal(slides, tier, context.getCharacterBlock());
        } finally {
            currentApiKey.remove();
            currentScenarioContext.remove();
            currentCreatorId.remove();
            currentFormatId.remove();  // v2.9.25
        }
    }

    /**
     * v2.9.25: 영상 포맷 ID 설정 (ContentService에서 호출)
     * 이미지 생성 전에 호출하여 aspectRatio를 결정
     */
    public void setCurrentFormatId(Long formatId) {
        currentFormatId.set(formatId);
        log.info("[IMAGE] formatId set: {}", formatId);
    }

    /**
     * v2.9.63: 썸네일 모드 활성화 (고급 모델 사용)
     * 썸네일은 장르와 관계없이 ADULT(Premium) 모델로 고해상도 이미지 생성
     */
    public void enableThumbnailMode() {
        thumbnailMode.set(true);
        log.info("[IMAGE] v2.9.63 Thumbnail mode ENABLED - will use premium model");
    }

    /**
     * v2.9.63: 썸네일 모드 비활성화 (장르별 모델 복귀)
     */
    public void disableThumbnailMode() {
        thumbnailMode.remove();
        log.info("[IMAGE] v2.9.63 Thumbnail mode DISABLED - reverted to genre-specific model");
    }

    /**
     * v2.9.63: 현재 이미지 모델 반환
     * - 썸네일 모드: ADULT(Premium) 모델 사용 (고해상도 2K 지원)
     * - 일반 모드: 장르별 모델 사용
     */
    private String getEffectiveImageModel(Long creatorId) {
        // v2.9.158: 썸네일 모드에서도 해당 크리에이터 티어의 이미지 모델 사용
        // (기존: PREMIUM_GENRE_ID=1L 하드코딩으로 존재하지 않는 크리에이터 참조 → 에러)
        return genreConfigService.getImageModel(creatorId);
    }

    /**
     * v2.9.25: 현재 포맷의 aspectRatio 반환
     * 포맷이 없으면 기본값 16:9 반환
     */
    private String getCurrentAspectRatio() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return "16:9";  // 기본값: 유튜브 일반
        }
        return videoFormatMapper.findById(formatId)
            .map(VideoFormat::getAspectRatio)
            .orElse("16:9");
    }

    /**
     * v2.9.25: 현재 포맷의 orientation 정보 반환 (프롬프트용)
     * 16:9 → "HORIZONTAL 16:9 landscape orientation (1920x1080)"
     * 9:16 → "VERTICAL 9:16 portrait orientation (1080x1920)"
     */
    private String getOrientationPrompt() {
        String aspectRatio = getCurrentAspectRatio();
        if ("9:16".equals(aspectRatio)) {
            return "VERTICAL 9:16 portrait orientation (1080x1920)";
        }
        return "HORIZONTAL 16:9 landscape orientation (1920x1080)";
    }

    /**
     * v2.9.25: 현재 포맷의 해상도 반환
     */
    private String getCurrentResolution() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return "1920x1080";
        }
        return videoFormatMapper.findById(formatId)
            .map(f -> f.getWidth() + "x" + f.getHeight())
            .orElse("1920x1080");
    }

    private List<String> generateImagesInternal(List<VideoDto.SlideScene> slides, QualityTier tier, String characterBlock) {
        int totalImages = slides.size();

        // v2.9.13: DB에서 배치 설정 조회 (하드코딩 제거)
        ApiBatchSettings batchSettings = rateLimitService.getBatchSettings("IMAGE");
        int batchSize = batchSettings.getBatchSize();
        long batchDelayMs = batchSettings.getBatchDelayMs();
        int totalBatches = (int) Math.ceil((double) totalImages / batchSize);

        log.info("========== [IMAGE BATCH PROCESSING START] ==========");
        log.info("[BATCH] Total images: {}, Batch size: {} (from DB), Total batches: {}",
                totalImages, batchSize, totalBatches);
        log.info("[BATCH] Tier: {}, Has character block: {}",
                tier, characterBlock != null && !characterBlock.isEmpty());

        try {
            // 이미지 디렉토리 생성
            Files.createDirectories(Paths.get(IMAGE_DIR));

            // v2.8.2: DB에서 장르별 Negative 프롬프트 로드 (하드코딩 제거)
            Long creatorId = currentCreatorId.get();
            String negativePrompt = genreConfigService.getImageNegativePrompt(creatorId);
            log.info("[BATCH] Loaded IMAGE_NEGATIVE from DB for creatorId: {}", creatorId);

            // v2.9.161: 버추얼 크리에이터인 경우 DB의 character_block_full 사용
            // 시나리오의 짧은 characterBlock 대신 DB의 7-Layer Facial Anchor System 사용
            String effectiveCharacterBlock = characterBlock;
            if (genreConfigService.hasFixedCharacter(creatorId)) {
                CreatorPrompt prompts = genreConfigService.getCreatorPrompts(creatorId);
                if (prompts != null && prompts.getCharacterBlockFull() != null && !prompts.getCharacterBlockFull().isBlank()) {
                    effectiveCharacterBlock = prompts.getCharacterBlockFull();
                    log.info("[v2.9.161] ✅ 버추얼 크리에이터 감지 (creatorId={}). DB character_block_full 사용 ({}자 → {}자)",
                            creatorId,
                            characterBlock != null ? characterBlock.length() : 0,
                            effectiveCharacterBlock.length());
                }
            }

            // Visual Style Context 생성 (시나리오 기반 - 하드코딩 스타일 제거)
            // v2.9.25: 포맷별 방향 동적 설정
            String orientation = getOrientationPrompt();
            VisualStyleContext styleContext = VisualStyleContext.createFromScenario(effectiveCharacterBlock, negativePrompt, orientation);
            log.info("[BATCH] Created VisualStyleContext with sessionId: {}, orientation: {} (scenario-based)",
                    styleContext.getSessionId(), orientation);

            // 결과 저장 리스트
            List<String> allImagePaths = new ArrayList<>();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // 배치 단위로 분산 처리
            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int startIdx = batchIndex * batchSize;
                int endIdx = Math.min(startIdx + batchSize, totalImages);
                List<VideoDto.SlideScene> batchSlides = slides.subList(startIdx, endIdx);

                log.info("========== [BATCH {}/{}] Processing images {}-{} ==========",
                        batchIndex + 1, totalBatches, startIdx + 1, endIdx);

                // 배치 내 이미지 순차 생성 (rate limit 준수)
                List<String> batchResults = processBatch(
                        batchSlides, tier, styleContext, startIdx, totalImages
                );

                // 결과 집계
                for (String result : batchResults) {
                    allImagePaths.add(result);
                    if (result != null && !result.startsWith("ERROR:")) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                }

                log.info("[BATCH {}/{}] Completed. Success: {}, Failed: {}",
                        batchIndex + 1, totalBatches,
                        batchResults.stream().filter(r -> r != null && !r.startsWith("ERROR:")).count(),
                        batchResults.stream().filter(r -> r == null || r.startsWith("ERROR:")).count());

                // 다음 배치 전 대기 (마지막 배치 제외) - DB 설정 사용
                if (batchIndex < totalBatches - 1) {
                    log.info("[BATCH] Waiting {}ms before next batch (from DB)...", batchDelayMs);
                    Thread.sleep(batchDelayMs);
                }
            }

            log.info("========== [IMAGE BATCH PROCESSING COMPLETE] ==========");
            log.info("[BATCH] Total: {}, Success: {}, Failed: {}",
                    totalImages, successCount.get(), failCount.get());

            // 실패한 이미지가 있으면 재시도하지 않고 플레이스홀더 유지
            // (영상 제작 시 누락된 이미지는 이전 이미지로 대체)
            return allImagePaths;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[BATCH] Image generation interrupted", e);
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED, "이미지 생성이 중단되었습니다.");
        } catch (Exception e) {
            log.error("[BATCH] Image generation failed", e);
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED, e);
        }
    }

    /**
     * 배치 내 이미지들을 순차적으로 생성 (rate limit 준수)
     * v2.9.13: DB 기반 Rate Limit + 적응형 Rate Limiter 적용
     * - AdaptiveRateLimiter로 API 부하 최적 제어
     * - 성공 시 딜레이 감소, 에러 시 딜레이 증가
     * - 연속 에러 초과 시 전체 중단
     */
    private List<String> processBatch(
            List<VideoDto.SlideScene> batchSlides,
            QualityTier tier,
            VisualStyleContext styleContext,
            int globalStartIdx,
            int totalImages
    ) {
        List<String> batchResults = new ArrayList<>();

        // v2.9.13: DB에서 현재 모델의 Rate Limit 설정 조회
        // v2.9.63: 썸네일 모드면 premium 모델, 아니면 장르별 모델
        Long creatorId = currentCreatorId.get();
        String modelName = getEffectiveImageModel(creatorId);
        AdaptiveRateLimiter rateLimiter = getImageRateLimiter(modelName);

        // v2.9.13: DB에서 배치 설정 조회
        ApiBatchSettings batchSettings = rateLimitService.getBatchSettings("IMAGE");
        int maxConsecutiveErrors = batchSettings.getMaxConsecutiveErrors();
        long errorBackoffMs = batchSettings.getErrorBackoffMs();

        int consecutiveErrors = 0;

        for (int i = 0; i < batchSlides.size(); i++) {
            int globalIdx = globalStartIdx + i;
            VideoDto.SlideScene slide = batchSlides.get(i);

            try {
                // v2.9.13: 적응형 Rate Limiter로 대기 (API 부하 최적 제어)
                rateLimiter.waitIfNeeded();

                log.info("[IMAGE {}/{}] Generating with model: {}, currentDelay: {}ms",
                        globalIdx + 1, totalImages, modelName, rateLimiter.getCurrentDelayMs());

                // v2.9.78: 장면 인덱스에 따른 다양한 구도/앵글 적용 + narration 포함
                // narration을 이미지 프롬프트에 포함하여 풍부한 컨텍스트 제공
                String variationPrompt = applySceneVariation(
                        slide.getImagePrompt(),
                        slide.getNarration(),  // ⭐ narration 추가
                        globalIdx,
                        totalImages
                );

                // 안전 필터 재시도 로직이 포함된 이미지 생성
                String imagePath = generateImageWithSafetyRetry(
                        variationPrompt, tier, styleContext, slide.getNarration()
                );

                batchResults.add(imagePath);
                rateLimiter.recordSuccess();  // v2.9.13: 성공 기록 (딜레이 감소 가능)
                consecutiveErrors = 0;
                log.info("[IMAGE {}/{}] Success: {}", globalIdx + 1, totalImages, imagePath);

            } catch (SafetyFilterException e) {
                log.error("[IMAGE {}/{}] Safety filter blocked after {} retries: {}",
                        globalIdx + 1, totalImages, MAX_SAFETY_RETRIES, e.getMessage());
                batchResults.add("ERROR:SAFETY_BLOCKED:" + e.getFinishReason());
                rateLimiter.recordError();  // v2.9.13: 에러 기록 (딜레이 증가)
                consecutiveErrors++;

            } catch (Exception e) {
                log.error("[IMAGE {}/{}] Failed: {}", globalIdx + 1, totalImages, e.getMessage());
                batchResults.add("ERROR:" + e.getMessage());

                // v2.9.13: 503 오버로드는 심각한 에러로 처리
                if (e.getMessage() != null && (e.getMessage().contains("503") || e.getMessage().contains("overload"))) {
                    rateLimiter.recordSevereError();
                } else {
                    rateLimiter.recordError();
                }
                consecutiveErrors++;

                // 연속 에러 시 백오프 대기 (DB 설정 사용)
                if (consecutiveErrors < maxConsecutiveErrors) {
                    log.warn("[IMAGE] Consecutive error #{}, waiting {}ms before retry...",
                            consecutiveErrors, errorBackoffMs);
                    try {
                        Thread.sleep(errorBackoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Image generation interrupted during backoff", ie);
                    }
                }
            }

            // 연속 에러 초과 시 전체 중단
            if (consecutiveErrors >= maxConsecutiveErrors) {
                log.error("[IMAGE] {} consecutive errors! Stopping batch. {}",
                        maxConsecutiveErrors, rateLimiter.getStats());
                throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                        String.format("연속 %d회 이미지 생성 실패. API 상태를 확인해주세요.", maxConsecutiveErrors));
            }
        }

        // v2.9.13: 배치 완료 시 통계 로깅
        log.info("[BATCH] Completed. {}", rateLimiter.getStats());
        return batchResults;
    }

    /**
     * Visual Style Context를 적용하여 이미지 생성
     * 프롬프트 체이닝 기법으로 일관된 스타일 유지
     */
    private String generateImageWithStyleContext(
            String originalPrompt,
            QualityTier tier,
            VisualStyleContext styleContext
    ) {
        // 스타일 컨텍스트 + 원본 프롬프트 + 네거티브 프롬프트 조합
        String enhancedPrompt = styleContext.toStylePrefix() +
                "=== SCENE DESCRIPTION ===\n" + originalPrompt +
                styleContext.toNegativePromptSuffix();

        return generateImageInternal(enhancedPrompt, tier);
    }

    /**
     * 내부 이미지 생성 메서드 (프롬프트가 이미 처리된 경우)
     * 배치 처리에서 VisualStyleContext로 이미 강화된 프롬프트를 사용
     * SafetyFilterException은 즉시 전파 (generateImageWithSafetyRetry에서 처리)
     */
    private String generateImageInternal(String enhancedPrompt, QualityTier tier) {
        // v2.8.3: 장르별 이미지 모델 사용 (DB에서 로드)
        // v2.9.63: 썸네일 모드면 premium 모델, 아니면 장르별 모델
        Long creatorId = currentCreatorId.get();
        String modelToUse = getEffectiveImageModel(creatorId);
        log.debug("[IMAGE] Using model: {}, creatorId: {}, prompt length: {}", modelToUse, creatorId, enhancedPrompt.length());

        // 최대 3회 재시도 (단, SafetyFilterException은 즉시 전파)
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("[IMAGE] Attempt {}/{}", attempt, maxRetries);
                // "SKIP_ENHANCE"를 전달하여 이미 강화된 프롬프트임을 표시
                return callGeminiImageApi(enhancedPrompt, modelToUse, tier, "SKIP_ENHANCE");
            } catch (SafetyFilterException e) {
                // 안전 필터 예외는 재시도하지 않고 즉시 전파
                // generateImageWithSafetyRetry에서 AI로 프롬프트 수정 후 재시도
                log.warn("[IMAGE] Safety filter triggered, propagating for AI-based retry");
                throw e;
            } catch (Exception e) {
                lastException = e;
                log.warn("[IMAGE] Attempt {} failed: {}", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                "이미지 생성 실패 (재시도 " + maxRetries + "회): " +
                        (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    private String generateImage(String prompt, QualityTier tier) {
        return generateImage(prompt, tier, null);
    }

    private String generateImage(String prompt, QualityTier tier, String characterBlock) {
        // v2.8.3: 장르별 이미지 모델 사용 (DB에서 로드)
        // v2.9.63: 썸네일 모드면 premium 모델, 아니면 장르별 모델
        Long creatorId = currentCreatorId.get();
        String modelToUse = getEffectiveImageModel(creatorId);
        log.info("Generating image with model: {}, creatorId: {}, prompt length: {}, hasCharacterBlock: {}",
                modelToUse, creatorId, prompt.length(), characterBlock != null && !characterBlock.isEmpty());

        // 최대 3회 재시도
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Image generation attempt {}/{}", attempt, maxRetries);
                return callGeminiImageApi(prompt, modelToUse, tier, characterBlock);
            } catch (Exception e) {
                lastException = e;
                log.warn("Image generation attempt {} failed: {}", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        // 재시도 전 대기 (점진적 증가)
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("Gemini Image API failed after {} attempts: {}", maxRetries, lastException != null ? lastException.getMessage() : "Unknown error");
        throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED, "이미지 생성 실패: " + (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    /**
     * 이미지 생성 API 호출 (Imagen 3.0 또는 Gemini Image)
     * @param prompt 프롬프트 (이미 강화된 프롬프트 또는 원본)
     * @param model 사용할 모델
     * @param tier 품질 티어
     * @param characterBlock 캐릭터 블록 (null이면 프롬프트가 이미 강화된 것으로 간주)
     */
    private String callGeminiImageApi(String prompt, String model, QualityTier tier, String characterBlock) {
        // Imagen 모델인지 확인
        boolean isImagen = model != null && model.startsWith("imagen");

        // 프롬프트 강화
        String finalPrompt = "SKIP_ENHANCE".equals(characterBlock)
                ? prompt
                : enhancePrompt(prompt, tier, characterBlock);

        if (isImagen) {
            return callImagenApi(finalPrompt, model);
        } else {
            return callGeminiImageApiInternal(finalPrompt, model);
        }
    }

    /**
     * Imagen 3.0 API 호출 (predict 엔드포인트)
     * https://ai.google.dev/gemini-api/docs/imagen
     *
     * 공식 문서 기준:
     * - 엔드포인트: https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict
     * - 인증: x-goog-api-key 헤더 사용
     * - 요청: {"instances": [{"prompt": "..."}], "parameters": {...}}
     */
    private String callImagenApi(String prompt, String model) {
        String apiUrl = String.format(IMAGEN_API_URL, model);
        log.info("[IMAGEN] Calling Imagen API: {}", apiUrl);
        log.info("[IMAGEN] Prompt length: {}", prompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 공식 문서 기준: x-goog-api-key 헤더 사용
        headers.set("x-goog-api-key", currentApiKey.get());

        // Imagen API 요청 본문 (공식 문서 기준)
        Map<String, Object> requestBody = new HashMap<>();

        // instances - 프롬프트 배열
        List<Map<String, String>> instances = new ArrayList<>();
        instances.add(Map.of("prompt", prompt));
        requestBody.put("instances", instances);

        // parameters - 이미지 생성 설정
        // v2.9.25: 포맷에 따른 aspectRatio 적용
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("sampleCount", 1);
        parameters.put("aspectRatio", getCurrentAspectRatio());
        parameters.put("personGeneration", "allow_adult");
        requestBody.put("parameters", parameters);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Imagen API request: {}", requestJson.substring(0, Math.min(500, requestJson.length())));

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseImagenResponse(response.getBody(), prompt);
            }

            // v2.9.11: 명확한 에러 처리
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Imagen API 응답 오류: " + response.getStatusCode());

        } catch (ApiException e) {
            throw e;  // ApiException은 그대로 전파
        } catch (SafetyFilterException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // v2.9.11: HTTP 클라이언트 에러 (4xx)
            log.error("[Imagen] HTTP 클라이언트 에러 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Imagen API 요청 실패: " + e.getStatusCode());
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // v2.9.11: HTTP 서버 에러 (5xx)
            log.error("[Imagen] HTTP 서버 에러 - status: {}", e.getStatusCode());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Imagen 서버 오류: " + e.getStatusCode());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // v2.9.11: 네트워크/타임아웃 에러
            log.error("[Imagen] 네트워크 에러: {}", e.getMessage());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Imagen 서버 연결 실패");
        } catch (Exception e) {
            log.error("[Imagen] 예상치 못한 에러: {}", e.getMessage(), e);
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Imagen API 호출 실패: " + e.getMessage());
        }
    }

    /**
     * Imagen API 응답 파싱
     * 응답 구조: predictions[0].bytesBase64Encoded
     */
    private String parseImagenResponse(String responseBody, String originalPrompt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 에러 응답 확인
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorMessage = error.path("message").asText("Unknown error");
                log.error("Imagen API error: {}", errorMessage);

                if (errorMessage.contains("safety") || errorMessage.contains("blocked")) {
                    throw new SafetyFilterException("Imagen safety filter triggered", originalPrompt, new ArrayList<>());
                }
                throw new RuntimeException("Imagen API error: " + errorMessage);
            }

            // predictions 배열에서 이미지 데이터 추출
            JsonNode predictions = root.path("predictions");
            if (predictions.isArray() && predictions.size() > 0) {
                JsonNode firstPrediction = predictions.get(0);
                String base64Data = firstPrediction.path("bytesBase64Encoded").asText(null);

                if (base64Data != null && !base64Data.isEmpty()) {
                    // Imagen은 PNG 형식으로 반환
                    return saveBase64Image(base64Data, "image/png");
                }
            }

            log.warn("No image data in Imagen response");
            throw new RuntimeException("No image data in Imagen API response");

        } catch (SafetyFilterException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Imagen response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse Imagen response: " + e.getMessage(), e);
        }
    }

    /**
     * Gemini Image API 호출 (generateContent 엔드포인트)
     * 503 오버로드 시 지수 백오프로 재시도, 실패 시 폴백 모델 사용
     */
    private String callGeminiImageApiInternal(String finalPrompt, String model) {
        return callGeminiImageApiWithRetry(finalPrompt, model, false);
    }

    /**
     * 503 오버로드 재시도 로직이 포함된 Gemini Image API 호출
     * v2.9.13: DB 기반 Rate Limit 설정 사용
     * @param finalPrompt 프롬프트
     * @param model 사용할 모델
     * @param isFallback 폴백 모델 사용 여부
     */
    private String callGeminiImageApiWithRetry(String finalPrompt, String model, boolean isFallback) {
        return callGeminiImageApiWithRetry(finalPrompt, model, isFallback, false);
    }

    /**
     * v2.9.153: 503 에러 타입별 분기 처리로 폴백 속도 대폭 개선
     *
     * 에러 타입별 처리 전략:
     * - UNAVAILABLE (모델 글로벌 과부하): API 키 바꿔도 의미 없음 → 폴백 모델 우선
     *   1. 기본 모델 2회 재시도 (3초, 6초)
     *   2. 폴백 모델 2회 재시도
     *   3. (모두 실패 시) API 키 폴백 + 1회씩 재시도
     *
     * - RESOURCE_EXHAUSTED (프로젝트 할당량 초과): API 키 폴백이 효과 있음
     *   1. 기본 모델 2회 재시도 (3초, 6초)
     *   2. API 키 폴백 + 2회 재시도
     *   3. (모두 실패 시) 폴백 모델 2회 재시도
     *
     * 개선 효과: 14분 → 약 30초로 단축
     */
    private String callGeminiImageApiWithRetry(String finalPrompt, String model, boolean isFallback, boolean triedApiKeyFallback) {
        // v2.9.153: 빠른 폴백을 위해 재시도 횟수 축소 (5회 → 2회)
        final int FAST_MAX_RETRIES = 2;
        final long FAST_INITIAL_BACKOFF = 3000;  // 3초
        final long FAST_MAX_BACKOFF = 6000;      // 6초

        int retryCount = 0;
        long backoffMs = FAST_INITIAL_BACKOFF;
        ModelOverloadedException lastOverloadException = null;
        Exception lastException = null;

        while (retryCount <= FAST_MAX_RETRIES) {
            try {
                return executeGeminiImageApiCall(finalPrompt, model);
            } catch (ModelOverloadedException e) {
                retryCount++;
                lastOverloadException = e;
                lastException = e;

                if (retryCount > FAST_MAX_RETRIES) {
                    log.warn("[503 RETRY] Max retries ({}) exceeded for model: {}, overloadType: {}",
                            FAST_MAX_RETRIES, model, e.getOverloadType());
                    break;
                }

                log.warn("[503 RETRY {}/{}] Model {} overloaded (type: {}). Waiting {}ms before retry...",
                        retryCount, FAST_MAX_RETRIES, model, e.getOverloadType(), backoffMs);

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }

                backoffMs = Math.min(backoffMs * 2, FAST_MAX_BACKOFF);
            } catch (SafetyFilterException e) {
                throw e;
            } catch (Exception e) {
                log.error("Gemini Image API call failed: {}", e.getMessage());
                throw new RuntimeException("Gemini Image API call failed: " + e.getMessage(), e);
            }
        }

        // v2.9.153: 에러 타입에 따라 다른 폴백 전략 적용
        OverloadType overloadType = lastOverloadException != null
                ? lastOverloadException.getOverloadType()
                : OverloadType.UNKNOWN;

        log.info("[503 FALLBACK STRATEGY] OverloadType: {}, isFallback: {}, triedApiKeyFallback: {}",
                overloadType, isFallback, triedApiKeyFallback);

        // ========== UNAVAILABLE (모델 글로벌 과부하): 폴백 모델 우선 ==========
        if (overloadType == OverloadType.UNAVAILABLE || overloadType == OverloadType.UNKNOWN) {
            // 1단계: 폴백 모델 먼저 시도 (API 키 폴백보다 우선)
            if (!isFallback) {
                Long creatorId = currentCreatorId.get();
                String fallbackModel = genreConfigService.getFallbackImageModel(creatorId);
                if (!model.equals(fallbackModel)) {
                    log.info("[UNAVAILABLE → FALLBACK MODEL] Trying fallback model: {} (original: {})", fallbackModel, model);
                    try {
                        return callGeminiImageApiWithRetry(finalPrompt, fallbackModel, true, false);
                    } catch (Exception fallbackEx) {
                        log.warn("[UNAVAILABLE → FALLBACK MODEL] Fallback model also failed: {}", fallbackEx.getMessage());
                        // 2단계로 진행
                    }
                }
            }

            // 2단계: 폴백 모델도 실패하면 API 키 폴백 시도 (1회만)
            if (!triedApiKeyFallback) {
                String fallbackApiKey = apiKeyService.getNextFallbackApiKey();
                if (fallbackApiKey != null) {
                    log.info("[UNAVAILABLE → API KEY FALLBACK] Trying different API key (last resort)");
                    currentApiKey.set(fallbackApiKey);
                    try {
                        // 폴백 API 키로 1회만 시도 (재귀 호출 대신 직접 호출)
                        return executeGeminiImageApiCall(finalPrompt, model);
                    } catch (Exception apiKeyFallbackEx) {
                        log.warn("[UNAVAILABLE → API KEY FALLBACK] Different API key also failed: {}", apiKeyFallbackEx.getMessage());
                    }
                }
            }
        }

        // ========== RESOURCE_EXHAUSTED (프로젝트 할당량 초과): API 키 폴백 우선 ==========
        else if (overloadType == OverloadType.RESOURCE_EXHAUSTED) {
            // 1단계: API 키 폴백 먼저 시도
            if (!triedApiKeyFallback) {
                String fallbackApiKey = apiKeyService.getNextFallbackApiKey();
                if (fallbackApiKey != null) {
                    log.info("[RESOURCE_EXHAUSTED → API KEY FALLBACK] Trying different API key for model: {}", model);
                    currentApiKey.set(fallbackApiKey);
                    try {
                        return callGeminiImageApiWithRetry(finalPrompt, model, isFallback, true);
                    } catch (Exception apiKeyFallbackEx) {
                        log.warn("[RESOURCE_EXHAUSTED → API KEY FALLBACK] Different API key also failed: {}", apiKeyFallbackEx.getMessage());
                        // 2단계로 진행
                    }
                }
            }

            // 2단계: API 키 폴백도 실패하면 폴백 모델 시도
            if (!isFallback) {
                Long creatorId = currentCreatorId.get();
                String fallbackModel = genreConfigService.getFallbackImageModel(creatorId);
                if (!model.equals(fallbackModel)) {
                    log.info("[RESOURCE_EXHAUSTED → FALLBACK MODEL] Trying fallback model: {} (original: {})", fallbackModel, model);
                    try {
                        return callGeminiImageApiWithRetry(finalPrompt, fallbackModel, true, false);
                    } catch (Exception fallbackEx) {
                        log.error("[RESOURCE_EXHAUSTED → FALLBACK MODEL] Fallback model also failed: {}", fallbackEx.getMessage());
                    }
                }
            }
        }

        throw new RuntimeException("Gemini Image API failed after all fallback attempts. " +
                "OverloadType: " + overloadType + ", Model: " + model +
                ", Error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    /**
     * 실제 Gemini Image API HTTP 호출
     * 503 오류 시 ModelOverloadedException 발생
     * v2.9.84: 참조 이미지가 있으면 멀티모달 요청 (텍스트 + 이미지)
     */
    private String executeGeminiImageApiCall(String finalPrompt, String model) {
        // v2.9.11: API 키를 URL 대신 Header로 전달 (보안 강화)
        String apiUrl = String.format(GEMINI_API_URL, model);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", currentApiKey.get());

        // Gemini Image API 요청 본문
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();

        // v2.9.84: 참조 이미지 멀티모달 지원
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", finalPrompt));

        // v2.9.90: ScenarioContext에서 다중 참조 이미지 확인
        ScenarioContext context = currentScenarioContext.get();
        if (context != null &&
            context.getReferenceImagesBase64() != null && !context.getReferenceImagesBase64().isEmpty() &&
            context.getReferenceImagesMimeTypes() != null && !context.getReferenceImagesMimeTypes().isEmpty()) {

            List<String> imagesBase64 = context.getReferenceImagesBase64();
            List<String> mimeTypes = context.getReferenceImagesMimeTypes();

            log.info("[IMAGE API] v2.9.90: Adding {} reference images to multimodal request", imagesBase64.size());

            // 모든 참조 이미지를 inlineData로 추가
            for (int i = 0; i < imagesBase64.size() && i < mimeTypes.size(); i++) {
                Map<String, Object> inlineData = new HashMap<>();
                inlineData.put("mimeType", mimeTypes.get(i));
                inlineData.put("data", imagesBase64.get(i));
                parts.add(Map.of("inlineData", inlineData));

                log.info("[IMAGE API] v2.9.90: Added reference image {}/{} - mimeType: {}, base64Length: {}",
                        i + 1, imagesBase64.size(), mimeTypes.get(i), imagesBase64.get(i).length());
            }
        }

        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // generationConfig - 이미지 생성 설정
        // v2.9.16: imageConfig 추가 - aspectRatio, imageSize 명시적 제어
        // v2.9.19: 모델별 imageSize 분기 (gemini-2.5-flash-image는 1024x1024만 지원)
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseModalities", List.of("TEXT", "IMAGE"));

        // gemini-2.5-flash-image는 1024x1024만 지원, 그 외는 2K
        // v2.9.25: 포맷에 따른 aspectRatio 적용
        Map<String, Object> imageConfig = new HashMap<>();
        String aspectRatio = getCurrentAspectRatio();
        Long currentFormat = currentFormatId.get();
        log.info("[IMAGE API] Using aspectRatio: {}, currentFormatId: {}", aspectRatio, currentFormat);
        imageConfig.put("aspectRatio", aspectRatio);
        if (model != null && model.contains("2.5-flash-image")) {
            // gemini-2.5-flash-image: 1024x1024 (1K)
            log.info("[IMAGE] Using 1K resolution for model: {}", model);
        } else {
            // gemini-3-pro-image-preview 등: 2K 지원
            imageConfig.put("imageSize", "2K");
        }
        generationConfig.put("imageConfig", imageConfig);
        requestBody.put("generationConfig", generationConfig);

        // Safety settings
        List<Map<String, String>> safetySettings = List.of(
                Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"),
                Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"),
                Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"),
                Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE")
        );
        requestBody.put("safetySettings", safetySettings);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Gemini Image API request to {}: {}", model, requestJson.substring(0, Math.min(800, requestJson.length())));

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseGeminiImageResponse(response.getBody(), finalPrompt);
            }

            // v2.9.11: 명확한 에러 처리
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Gemini Image API 응답 오류: " + response.getStatusCode());

        } catch (ApiException e) {
            throw e;  // ApiException은 그대로 전파
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // 503 Service Unavailable (모델 오버로드) 처리
            if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                String responseBody = e.getResponseBodyAsString();
                log.warn("[503] Model {} overloaded. Response: {}", model,
                        responseBody.substring(0, Math.min(500, responseBody.length())));

                // v2.9.153: 에러 타입 파싱 (UNAVAILABLE vs RESOURCE_EXHAUSTED)
                OverloadType overloadType = parseOverloadType(responseBody);
                log.info("[503] Overload type detected: {} for model: {}", overloadType, model);

                throw new ModelOverloadedException("Model " + model + " is overloaded", e, overloadType);
            }
            // v2.9.11: 서버 에러 구분
            log.error("[Gemini Image] HTTP 서버 에러 - status: {}", e.getStatusCode());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Gemini Image 서버 오류: " + e.getStatusCode());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // v2.9.11: 클라이언트 에러 (4xx)
            log.error("[Gemini Image] HTTP 클라이언트 에러 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Gemini Image API 요청 실패: " + e.getStatusCode());
        } catch (SafetyFilterException e) {
            throw e;
        } catch (ModelOverloadedException e) {
            throw e;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // v2.9.11: 네트워크/타임아웃 에러
            log.error("[Gemini Image] 네트워크 에러: {}", e.getMessage());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Gemini Image 서버 연결 실패");
        } catch (Exception e) {
            // 응답 본문에 "overloaded" 메시지가 포함된 경우도 처리
            String message = e.getMessage();
            if (message != null && (message.contains("overloaded") || message.contains("503"))) {
                log.warn("[503] Model {} overloaded (from message): {}", model, message);
                // v2.9.153: 메시지에서 에러 타입 파싱
                OverloadType overloadType = parseOverloadType(message);
                throw new ModelOverloadedException("Model " + model + " is overloaded", e, overloadType);
            }
            log.error("[Gemini Image] 예상치 못한 에러: {}", e.getMessage(), e);
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED,
                    "Gemini Image API 호출 실패: " + e.getMessage());
        }
    }

    /**
     * v2.9.153: 503 에러 타입 구분
     * - UNAVAILABLE: 모델 글로벌 과부하 (API 키 바꿔도 의미 없음) → 폴백 모델 우선
     * - RESOURCE_EXHAUSTED: 프로젝트 할당량 초과 → API 키 폴백 우선
     * - UNKNOWN: 알 수 없음 → 기존 로직 유지
     */
    public enum OverloadType {
        UNAVAILABLE,        // 모델 글로벌 과부하
        RESOURCE_EXHAUSTED, // 프로젝트 할당량 초과
        UNKNOWN             // 알 수 없음
    }

    /**
     * 모델 오버로드 예외 (503 에러)
     * v2.9.153: 에러 타입(UNAVAILABLE/RESOURCE_EXHAUSTED) 구분 추가
     */
    private static class ModelOverloadedException extends RuntimeException {
        private final OverloadType overloadType;

        public ModelOverloadedException(String message, Throwable cause, OverloadType overloadType) {
            super(message, cause);
            this.overloadType = overloadType;
        }

        public OverloadType getOverloadType() {
            return overloadType;
        }
    }

    /**
     * v2.9.153: 503 에러 응답에서 오버로드 타입 파싱
     * - "UNAVAILABLE" → 모델 글로벌 과부하 (API 키 바꿔도 의미 없음)
     * - "RESOURCE_EXHAUSTED" → 프로젝트 할당량 초과 (API 키 폴백 의미 있음)
     */
    private OverloadType parseOverloadType(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return OverloadType.UNKNOWN;
        }

        // JSON 응답에서 "status" 필드 확인
        if (responseBody.contains("RESOURCE_EXHAUSTED")) {
            return OverloadType.RESOURCE_EXHAUSTED;
        } else if (responseBody.contains("UNAVAILABLE")) {
            return OverloadType.UNAVAILABLE;
        }

        // 기타 503 메시지
        return OverloadType.UNKNOWN;
    }

    /**
     * v2.8.2: 프롬프트 강화 - 100% 시나리오 + DB 장르 기반 (하드코딩 제거)
     *
     * 핵심 원칙:
     * 1. characterBlock: 시나리오에서 생성된 캐릭터 정보 (100% 사용)
     * 2. originalPrompt: 시나리오에서 생성된 장면 프롬프트 (100% 사용)
     * 3. IMAGE_STYLE: DB에서 장르별 스타일 로드 (하드코딩 없음)
     * 4. IMAGE_NEGATIVE: DB에서 장르별 네거티브 프롬프트 로드 (하드코딩 없음)
     */
    private String enhancePrompt(String originalPrompt, QualityTier tier, String characterBlock) {
        StringBuilder enhanced = new StringBuilder();
        Long creatorId = currentCreatorId.get();

        // ========== 1. IDENTITY BLOCK (시나리오에서 생성된 캐릭터 정보) ==========
        // v2.8.5: 장르에 따라 캐릭터 블록 적용 여부 결정
        boolean isCharacterRequired = isCharacterRequiredForGenre(creatorId);
        boolean hasCharacterBlock = characterBlock != null && !characterBlock.trim().isEmpty();

        if (isCharacterRequired && !hasCharacterBlock) {
            log.error("❌ [치명적 오류] characterBlock이 없습니다! 시나리오를 다시 생성해주세요. creatorId: {}", creatorId);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "캐릭터 정보가 없습니다. 시나리오를 다시 생성해주세요. (characterBlock is required)");
        }

        // v2.9.114: 캐릭터 블록만 추가, 추가 규칙은 DB의 IMAGE_STYLE에서 관리
        if (hasCharacterBlock) {
            enhanced.append("=== CHARACTER IDENTITY ===\n");
            log.debug("캐릭터 블록 적용: {} chars", characterBlock.length());
            enhanced.append(characterBlock.trim());
            enhanced.append("\n\n");
        } else {
            log.info("[enhancePrompt] creatorId={} - 비-캐릭터 장르, characterBlock 건너뜀", creatorId);
        }

        // ========== 2. SCENE DESCRIPTION (시나리오에서 생성된 장면 프롬프트) ==========
        enhanced.append("=== SCENE DESCRIPTION ===\n");
        enhanced.append(originalPrompt);
        enhanced.append("\n\n");

        // ========== 3. 장르별 이미지 스타일 (DB에서 로드 - 하드코딩 없음) ==========
        // v2.9.114: 모든 스타일(photorealism, animation, sexy vibe 등)은 DB의 IMAGE_STYLE에서 관리
        // creatorId는 메서드 상단에서 이미 조회됨
        if (creatorId != null) {
            String genreImageStyle = genreConfigService.getImageStylePrompt(creatorId);
            enhanced.append("=== 【GENRE-SPECIFIC STYLE - FROM DB】 ===\n");
            enhanced.append(genreImageStyle).append("\n\n");
            log.debug("[enhancePrompt] Using DB IMAGE_STYLE for creatorId: {}", creatorId);
        }

        // ========== 4. 기본 기술 요구사항 (포맷/방향만 - 스타일은 DB에서) ==========
        enhanced.append("=== TECHNICAL REQUIREMENTS ===\n");
        enhanced.append(getOrientationPrompt()).append(".\n\n");  // v2.9.25: 포맷별 동적 방향

        // ========== 5. 장르별 네거티브 프롬프트 (DB에서 로드 - 하드코딩 없음) ==========
        if (creatorId != null) {
            String genreNegative = genreConfigService.getImageNegativePrompt(creatorId);
            enhanced.append("=== 【STRICT AVOIDANCE - FROM DB】 ===\n");
            enhanced.append(genreNegative).append("\n");
            log.debug("[enhancePrompt] Using DB IMAGE_NEGATIVE for creatorId: {}", creatorId);
        }

        return enhanced.toString();
    }

    /**
     * Gemini Image API 응답 파싱 (2026년 최신 API 구조)
     * 응답 구조: candidates[0].content.parts[] -> inlineData.data (base64)
     * @param responseBody API 응답 본문
     * @param originalPrompt 원본 프롬프트 (SafetyFilterException에 포함)
     */
    private String parseGeminiImageResponse(String responseBody, String originalPrompt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 전체 응답 구조 로깅 (디버깅용)
            log.info("Gemini Image API response keys: {}", root.fieldNames().hasNext() ?
                    iteratorToString(root.fieldNames()) : "empty");

            // 에러 응답 우선 확인
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorCode = error.has("code") ? error.get("code").asText() : "unknown";
                String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                String errorStatus = error.has("status") ? error.get("status").asText() : "";
                log.error("Gemini API error - code: {}, status: {}, message: {}", errorCode, errorStatus, errorMessage);
                throw new RuntimeException("Gemini API error [" + errorCode + "]: " + errorMessage);
            }

            // candidates 확인
            if (!root.has("candidates") || !root.get("candidates").isArray() || root.get("candidates").size() == 0) {
                // promptFeedback 확인 (안전 필터 차단 시)
                if (root.has("promptFeedback")) {
                    JsonNode feedback = root.get("promptFeedback");
                    String blockReason = feedback.has("blockReason") ? feedback.get("blockReason").asText() : "unknown";
                    log.error("Prompt blocked by safety filter: {}", blockReason);

                    List<SafetyFilterException.SafetyRating> safetyRatings = new ArrayList<>();
                    if (feedback.has("safetyRatings")) {
                        JsonNode ratings = feedback.get("safetyRatings");
                        for (JsonNode rating : ratings) {
                            safetyRatings.add(SafetyFilterException.SafetyRating.fromJsonNode(rating));
                        }
                        log.error("Safety ratings: {}", ratings.toString());
                    }
                    throw new SafetyFilterException(blockReason, originalPrompt, safetyRatings);
                }
                log.error("No candidates in response. Full response: {}",
                        responseBody.substring(0, Math.min(2000, responseBody.length())));
                throw new RuntimeException("No candidates returned from Gemini API");
            }

            JsonNode candidate = root.get("candidates").get(0);

            // finishReason 확인
            if (candidate.has("finishReason")) {
                String finishReason = candidate.get("finishReason").asText();
                log.info("Gemini finishReason: {}", finishReason);

                // SAFETY, IMAGE_SAFETY, BLOCKED 등으로 차단된 경우 SafetyFilterException 발생
                if ("SAFETY".equals(finishReason) || "IMAGE_SAFETY".equals(finishReason) || "BLOCKED".equals(finishReason)) {
                    List<SafetyFilterException.SafetyRating> safetyRatings = new ArrayList<>();
                    if (candidate.has("safetyRatings")) {
                        JsonNode ratings = candidate.get("safetyRatings");
                        for (JsonNode rating : ratings) {
                            safetyRatings.add(SafetyFilterException.SafetyRating.fromJsonNode(rating));
                        }
                        log.error("Content blocked - safetyRatings: {}", ratings.toString());
                    }
                    throw new SafetyFilterException(finishReason, originalPrompt, safetyRatings);
                }
            }

            // content.parts 확인
            if (!candidate.has("content")) {
                log.error("No content in candidate. Candidate: {}", candidate.toString());
                throw new RuntimeException("No content in Gemini response candidate");
            }

            JsonNode content = candidate.get("content");
            if (!content.has("parts") || !content.get("parts").isArray()) {
                log.error("No parts in content. Content: {}", content.toString());
                throw new RuntimeException("No parts in Gemini response content");
            }

            JsonNode parts = content.get("parts");
            log.info("Found {} parts in response", parts.size());

            // parts에서 이미지 데이터 찾기
            for (int i = 0; i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                log.debug("Part {}: keys={}", i, iteratorToString(part.fieldNames()));

                if (part.has("inlineData")) {
                    JsonNode inlineData = part.get("inlineData");

                    if (inlineData.has("data")) {
                        String base64Data = inlineData.get("data").asText();
                        String mimeType = inlineData.has("mimeType") ?
                                inlineData.get("mimeType").asText() : "image/png";

                        log.info("Found image data - mimeType: {}, data length: {} chars",
                                mimeType, base64Data.length());

                        return saveBase64Image(base64Data, mimeType);
                    } else {
                        log.warn("inlineData has no 'data' field. inlineData: {}", inlineData.toString());
                    }
                } else if (part.has("text")) {
                    // 텍스트 응답인 경우 (이미지가 아닌 경우)
                    String text = part.get("text").asText();
                    log.info("Part {} is text (not image): {}", i,
                            text.substring(0, Math.min(200, text.length())));
                }
            }

            // 이미지를 찾지 못한 경우
            log.error("No inlineData found in any part. Full response: {}",
                    responseBody.substring(0, Math.min(3000, responseBody.length())));
            throw new RuntimeException("No image data found in Gemini response - API may have returned text instead of image");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Gemini Image response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse Gemini Image response: " + e.getMessage(), e);
        }
    }

    private String iteratorToString(java.util.Iterator<String> iterator) {
        StringBuilder sb = new StringBuilder("[");
        while (iterator.hasNext()) {
            sb.append(iterator.next());
            if (iterator.hasNext()) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Base64 이미지를 로컬 파일로 저장하고 경로 반환
     */
    private String saveBase64Image(String base64Data, String mimeType) {
        try {
            String imageId = UUID.randomUUID().toString();
            String extension = mimeType.contains("png") ? ".png" : ".jpg";
            Path imagePath = Paths.get(IMAGE_DIR, imageId + extension);

            // Base64 디코딩 및 파일 저장
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            Files.write(imagePath, imageBytes);

            log.info("Image saved: {} (size: {} bytes)", imagePath, imageBytes.length);

            // 로컬 파일 경로 반환 (FFmpeg에서 사용)
            return imagePath.toAbsolutePath().toString();

        } catch (IOException e) {
            log.error("Failed to save image: {}", e.getMessage());
            throw new RuntimeException("Failed to save image", e);
        }
    }

    // ========== 장면 다양성 적용 메서드 ==========

    /**
     * v2.9.78: 각 장면에 시나리오 프롬프트 + narration을 메인으로 사용하고, 기술적 요소만 보강
     *
     * ⚠️ 핵심 원칙:
     * 1. 시나리오의 imagePrompt가 메인 콘텐츠 (랜덤 덮어쓰기 금지!)
     * 2. narration을 추가하여 더 풍부한 장면 컨텍스트 제공
     * 3. 카메라, 조명 등 기술적 요소만 장면별로 다양하게 적용
     * 4. 시나리오 나레이션과 100% 일치하는 이미지 생성
     *
     * @param originalPrompt 시나리오에서 생성된 imagePrompt (반드시 사용!)
     * @param narration 시나리오 나레이션 (장면 디테일 보강용)
     * @param sceneIndex 장면 인덱스 (0부터 시작)
     * @param totalScenes 전체 장면 수
     * @return 기술적 요소가 보강된 프롬프트
     */
    private String applySceneVariation(String originalPrompt, String narration, int sceneIndex, int totalScenes) {
        StringBuilder enhanced = new StringBuilder();

        // v2.9.114: 카메라/조명/구도 하드코딩 삭제 - 모든 기술적 요소도 DB의 IMAGE_STYLE에서 관리

        // v2.5.8: 전체 시나리오 컨텍스트 필수! (폴백 없음)
        ScenarioContext context = currentScenarioContext.get();
        if (context == null) {
            log.error("❌ [치명적 오류] ScenarioContext가 없습니다! DB에서 시나리오를 불러오지 못했습니다.");
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "시나리오 정보가 없습니다. 시나리오를 먼저 생성해주세요. (ScenarioContext is required)");
        }

        // v2.9.160: 참조 이미지 분석 텍스트 포함 (슬라이드 이미지가 참조 이미지를 정확히 반영하도록)
        if (context.getReferenceImageAnalysis() != null && !context.getReferenceImageAnalysis().isEmpty()) {
            enhanced.append("=== REFERENCE PRODUCT IMAGE ANALYSIS ===\n");
            enhanced.append("The user uploaded a reference image of the product. ");
            enhanced.append("You MUST generate an image that accurately reflects the product's appearance described below.\n");
            enhanced.append(context.getReferenceImageAnalysis()).append("\n\n");
            log.info("[SCENE {}] v2.9.160: referenceImageAnalysis added to prompt ({} chars)",
                    sceneIndex + 1, context.getReferenceImageAnalysis().length());
        }

        // v2.9.162: NARRATION 섹션 제거 - imagePrompt가 이미 narration 기반으로 생성됨
        // narration 원문을 중복 추가하면 프롬프트가 ~1500자 불필요하게 늘어나 참조 이미지 영향력 희석
        // 씬 번호만 간단히 표시
        enhanced.append("SCENE: #").append(sceneIndex + 1).append(" of ").append(totalScenes).append("\n\n");

        // imagePrompt (시나리오에서 생성된 프롬프트)
        enhanced.append("=== IMAGE PROMPT ===\n");
        enhanced.append(originalPrompt).append("\n\n");

        // v2.9.114: 모든 하드코딩 삭제 - 카메라/조명/구도/스타일 모두 DB의 IMAGE_STYLE에서 관리

        // v2.8.2: 장르별 이미지 스타일 (DB 필수 - 하드코딩 없음)
        Long creatorId = currentCreatorId.get();
        String genreImageStyle = genreConfigService.getImageStylePrompt(creatorId);  // DB 없으면 예외 발생
        enhanced.append("=== STYLE ===\n");
        enhanced.append(genreImageStyle).append("\n\n");

        // v2.9.162: NEGATIVE 섹션 제거 - IMAGE_STYLE Base 템플릿의 <forbidden> 안에 이미 포함됨
        // 중복 제거로 프롬프트 크기 감소 → 참조 이미지 영향력 강화

        log.info("[SCENE {}] All prompts from DB. creatorId: {}", sceneIndex + 1, creatorId);

        return enhanced.toString();
    }

    // ========== 안전 필터 처리: AI 기반 프롬프트 수정 ==========

    /**
     * AI를 사용하여 안전 필터에 걸린 프롬프트를 수정
     * Gemini를 활용해 원본 의도를 유지하면서 안전한 버전으로 변환
     * v2.6.0: narration 파라미터 추가 - 원본 스토리 맥락 유지
     *
     * @param originalPrompt 원본 프롬프트
     * @param safetyIssue 안전 필터 이슈 설명
     * @param narration 원본 나레이션 (스토리 일관성 유지용)
     * @return 수정된 안전한 프롬프트
     */
    private String modifyPromptForSafety(String originalPrompt, String safetyIssue, String narration) {
        log.info("[SAFETY] Modifying prompt using AI. Issue: {}", safetyIssue);

        // v2.9.11: API 키를 URL 대신 Header로 전달 (보안 강화)
        String apiUrl = String.format(GEMINI_TEXT_API_URL, textModel);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", currentApiKey.get());

        // v2.9.114: Safety Filter 지시문을 DB에서 로드 (하드코딩 완전 제거)
        Long creatorId = currentCreatorId.get();
        String aspectRatio = getCurrentAspectRatio();
        String systemPrompt = genreConfigService.buildSafetyFilterInstruction(creatorId, aspectRatio);

        // v2.9.114: 사용자 프롬프트 구성 (나레이션 + 원본 프롬프트 + 이슈)
        String orientationText = "9:16".equals(aspectRatio) ? "vertical 9:16" : "horizontal 16:9";
        String userPrompt;
        if (narration != null && !narration.isEmpty()) {
            userPrompt = String.format("""
                NARRATION (THIS IS THE SCENE YOU MUST CREATE - DO NOT CHANGE):
                %s

                ORIGINAL PROMPT (blocked, use only as style reference):
                %s

                SAFETY ISSUE: %s

                CREATE A NEW SAFE PROMPT THAT:
                1. Shows EXACTLY the scene described in the narration
                2. Same location, same character, same moment
                3. Uses elegant fashion instead of revealing clothes
                4. Focuses on emotional expression and atmosphere
                5. Photorealistic style, %s
                """, narration, originalPrompt, safetyIssue, orientationText);
        } else {
            userPrompt = String.format("""
                ORIGINAL PROMPT (BLOCKED):
                %s

                SAFETY ISSUE: %s

                Rewrite to be safe while keeping the same scene and story.
                """, originalPrompt, safetyIssue);
        }

        Map<String, Object> requestBody = new HashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();

        // System instruction
        Map<String, Object> systemContent = new HashMap<>();
        systemContent.put("role", "user");
        systemContent.put("parts", List.of(Map.of("text", systemPrompt)));
        contents.add(systemContent);

        // User prompt
        Map<String, Object> userContent = new HashMap<>();
        userContent.put("role", "user");
        userContent.put("parts", List.of(Map.of("text", userPrompt)));
        contents.add(userContent);

        requestBody.put("contents", contents);

        // Generation config
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 2000);
        requestBody.put("generationConfig", generationConfig);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("candidates") && root.get("candidates").size() > 0) {
                    JsonNode candidate = root.get("candidates").get(0);
                    if (candidate.has("content") && candidate.get("content").has("parts")) {
                        String modifiedPrompt = candidate.get("content").get("parts").get(0).get("text").asText();
                        log.info("[SAFETY] Prompt modified successfully. New length: {}", modifiedPrompt.length());
                        return modifiedPrompt.trim();
                    }
                }
            }

            log.warn("[SAFETY] Failed to get modified prompt from AI, using fallback");
            return createFallbackSafePrompt(originalPrompt, narration);

        } catch (Exception e) {
            log.error("[SAFETY] AI prompt modification failed: {}", e.getMessage());
            return createFallbackSafePrompt(originalPrompt, narration);
        }
    }

    /**
     * AI 수정 실패 시 사용할 폴백 안전 프롬프트 생성
     * v2.9.14: 100% DB 기반 - 하드코딩 완전 제거
     *
     * @param originalPrompt 원본 프롬프트 (사용 안함 - 호환성 유지)
     * @param narration 원본 나레이션 (스토리 일관성 유지용)
     * @return 안전한 폴백 프롬프트 (DB에서 로드)
     */
    private String createFallbackSafePrompt(String originalPrompt, String narration) {
        Long creatorId = currentCreatorId.get();
        log.info("[SAFETY FALLBACK] Creating DB-based fallback prompt: creatorId={}", creatorId);

        // v2.9.14: DB에서 장르별 안전 폴백 프롬프트 로드 (하드코딩 완전 제거)
        if (narration != null && !narration.isEmpty()) {
            log.info("[SAFETY FALLBACK] Using narration as source: {}",
                    narration.length() > 50 ? narration.substring(0, 50) + "..." : narration);

            // DB에서 템플릿 로드 + 플레이스홀더 치환
            return genreConfigService.buildImageSafetyFallbackPrompt(creatorId, narration);
        }

        // narration이 없는 경우에도 DB 템플릿 사용 (빈 narration으로)
        log.warn("[SAFETY FALLBACK] No narration available, using DB template with empty narration");
        return genreConfigService.buildImageSafetyFallbackPrompt(creatorId, "");
    }

    /**
     * 안전 필터 재시도와 함께 이미지 생성
     * SafetyFilterException 발생 시 AI로 프롬프트를 수정하고 재시도
     * v2.6.0: narration 파라미터 추가 - safety 재시도 시 원본 스토리 맥락 유지
     *
     * @param originalPrompt 원본 프롬프트
     * @param tier 품질 티어
     * @param styleContext 스타일 컨텍스트
     * @param narration 원본 나레이션 (safety 재시도 시 참조)
     * @return 생성된 이미지 경로
     */
    private String generateImageWithSafetyRetry(
            String originalPrompt,
            QualityTier tier,
            VisualStyleContext styleContext,
            String narration
    ) {
        String currentPrompt = originalPrompt;
        int safetyRetryCount = 0;

        while (safetyRetryCount <= MAX_SAFETY_RETRIES) {
            try {
                // v2.9.162: 스타일 컨텍스트 적용 (toNegativePromptSuffix 제거 - IMAGE_STYLE <forbidden>에 이미 포함)
                String enhancedPrompt = styleContext.toStylePrefix() +
                        "=== SCENE DESCRIPTION ===\n" + currentPrompt;

                return generateImageInternal(enhancedPrompt, tier);

            } catch (SafetyFilterException e) {
                safetyRetryCount++;
                log.warn("[SAFETY RETRY {}/{}] Image blocked: {}",
                        safetyRetryCount, MAX_SAFETY_RETRIES, e.getMessage());

                if (safetyRetryCount > MAX_SAFETY_RETRIES) {
                    log.error("[SAFETY] Max retries exceeded. Giving up on this image.");
                    throw e;
                }

                // AI를 사용해 프롬프트 수정 - v2.6.0: narration 참조하여 스토리 일관성 유지
                String safetyIssue = e.getSafetyIssueDescription();
                currentPrompt = modifyPromptForSafety(currentPrompt, safetyIssue, narration);

                log.info("[SAFETY RETRY {}/{}] Retrying with modified prompt...",
                        safetyRetryCount, MAX_SAFETY_RETRIES);

                // 재시도 전 잠시 대기
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Safety retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException("Failed to generate image after safety retries");
    }

    /**
     * 캐릭터 블록 필수 여부를 DB 기반으로 판단
     * - hasFixedCharacter() = true: 버추얼 크리에이터 → 캐릭터 필수
     * - 그 외: 선택
     */
    private boolean isCharacterRequiredForGenre(Long creatorId) {
        if (creatorId == null) {
            return true;
        }
        return genreConfigService.hasFixedCharacter(creatorId);
    }
}
