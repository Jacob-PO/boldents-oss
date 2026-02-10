package com.aivideo.api.service.video;

import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.service.subtitle.SubtitleService;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.aivideo.api.util.ProcessExecutor;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoCreatorServiceImpl implements VideoCreatorService {

    private final SubtitleService subtitleService;
    private final com.aivideo.api.service.ApiKeyService apiKeyService;
    private final com.aivideo.api.service.CreatorConfigService genreConfigService;
    private final com.aivideo.api.mapper.VideoFormatMapper videoFormatMapper;  // v2.9.25: 포맷 조회
    // v2.9.11: Bean 주입으로 변경 (HttpClientConfig에서 관리)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.tier.standard.video-resolution}")
    private String standardResolution;

    @Value("${ai.tier.premium.video-resolution}")
    private String premiumResolution;

    // 스레드 로컬 변수로 현재 요청의 API 키 저장 (유저 API 키 사용)
    private final ThreadLocal<String> currentApiKey = new ThreadLocal<>();
    // v2.9.25: 현재 영상 포맷 ID 저장
    private static final ThreadLocal<Long> currentFormatId = new ThreadLocal<>();

    // v2.9.13: 파일 정리용 스케줄러 (데몬 스레드로 JVM 종료 지연 방지)
    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init() {
        this.cleanupExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "AIVideo-FileCleanup");
            t.setDaemon(true);  // 데몬 스레드로 설정
            return t;
        });
        log.info("VideoCreatorService 파일 정리 스케줄러 초기화 완료");
    }

    @PreDestroy
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("VideoCreatorService 파일 정리 스케줄러 종료");
        }
    }

    // 작업 디렉토리
    private static final String WORK_DIR = "/tmp/aivideo";

    // FFmpeg 설정
    private static final int VIDEO_WIDTH = 1920;
    private static final int VIDEO_HEIGHT = 1080;
    private static final int VIDEO_FPS = 30;
    private static final String VIDEO_CODEC = "libx264";
    private static final String AUDIO_CODEC = "aac";

    // Veo 3.1 API 엔드포인트 (predictLongRunning 메서드 사용)
    // 참고: https://ai.google.dev/gemini-api/docs/video
    private static final String VEO_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:predictLongRunning";
    private static final String VEO_OPERATION_URL = "https://generativelanguage.googleapis.com/v1beta/%s";

    // 오프닝 영상 저장 디렉토리
    private static final String OPENING_DIR = "/tmp/aivideo/opening";

    // ========== v2.9.25: 포맷 관련 헬퍼 메서드 ==========

    /**
     * 현재 영상 포맷 ID 설정 (ContentService에서 호출)
     */
    public void setCurrentFormatId(Long formatId) {
        currentFormatId.set(formatId);
        log.info("[VIDEO] formatId set: {}", formatId);
    }

    /**
     * 현재 포맷의 aspectRatio 조회
     * @return "16:9" 또는 "9:16"
     */
    private String getCurrentAspectRatio() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return "16:9";  // 기본값: 유튜브 일반
        }
        return videoFormatMapper.findById(formatId)
            .map(com.aivideo.api.entity.VideoFormat::getAspectRatio)
            .orElse("16:9");
    }

    /**
     * 현재 포맷의 해상도 (width x height)
     * @return int[] {width, height}
     */
    public int[] getCurrentResolution() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return new int[]{1920, 1080};  // 기본값
        }
        return videoFormatMapper.findById(formatId)
            .map(f -> new int[]{f.getWidth(), f.getHeight()})
            .orElse(new int[]{1920, 1080});
    }

    /**
     * 현재 포맷의 방향 프롬프트
     */
    private String getOrientationPrompt() {
        String aspectRatio = getCurrentAspectRatio();
        if ("9:16".equals(aspectRatio)) {
            return "VERTICAL 9:16 portrait orientation (1080x1920)";
        }
        return "HORIZONTAL 16:9 landscape orientation (1920x1080)";
    }

    // v2.9.162: deprecated 오버로드 3개 삭제 (3/4/5 파라미터 버전)
    // 모든 호출처가 6파라미터 버전만 사용하므로 안전하게 제거

    @Override
    public String generateOpeningVideo(Long userNo, VideoDto.OpeningScene opening, QualityTier tier, String characterBlock, Long creatorId, VideoDto.ScenarioInfo scenarioInfo) {
        // ⚠️ 필수 검증: 오프닝 객체와 videoPrompt가 반드시 있어야 함 (폴백 절대 금지!)
        if (opening == null) {
            log.error("[Veo] ❌ CRITICAL: OpeningScene object is null!");
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "오프닝 씬 정보가 없습니다. 시나리오가 올바르게 생성되지 않았습니다. 시나리오를 다시 생성해주세요.");
        }

        String videoPrompt = opening.getVideoPrompt();
        if (videoPrompt == null || videoPrompt.trim().isEmpty()) {
            log.error("[Veo] ❌ CRITICAL: videoPrompt is null or empty! Opening narration: {}",
                opening.getNarration() != null ? opening.getNarration().substring(0, Math.min(50, opening.getNarration().length())) : "null");
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "오프닝 영상 프롬프트가 없습니다. 시나리오 생성 시 오프닝 videoPrompt가 누락되었습니다. 시나리오를 다시 생성해주세요.");
        }

        log.info("[Veo] ✅ videoPrompt validated - length: {}, first 100 chars: {}",
            videoPrompt.length(), videoPrompt.substring(0, Math.min(100, videoPrompt.length())));

        // v2.8.5: 장르에 따라 characterBlock 검증 (금융 등 비-캐릭터 장르는 선택적)
        boolean isCharacterRequired = isCharacterRequiredForGenre(creatorId);
        boolean hasCharacterBlock = characterBlock != null && !characterBlock.trim().isEmpty();

        if (isCharacterRequired && !hasCharacterBlock) {
            log.error("[Veo] ❌ CRITICAL: characterBlock is null or empty! creatorId: {}", creatorId);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "캐릭터 정보가 없습니다. 시나리오를 다시 생성해주세요. (characterBlock is required for video generation)");
        }
        if (hasCharacterBlock) {
            log.info("[Veo] ✅ characterBlock validated - length: {}, creatorId: {}", characterBlock.length(), creatorId);
        } else {
            log.info("[Veo] creatorId={} - 비-캐릭터 장르, characterBlock 검증 건너뜀", creatorId);
        }

        // 사용자 API 키 조회 및 스레드 로컬에 저장
        String apiKey = apiKeyService.getServiceApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 설정되지 않았습니다. 마이페이지에서 Google API 키를 등록해주세요.");
        }
        currentApiKey.set(apiKey);

        // v2.8.3: 장르별 영상 생성 모델 사용 (DB에서 로드)
        String modelToUse = genreConfigService.getVideoModel(creatorId);
        String resolution = tier == QualityTier.PREMIUM ? premiumResolution : standardResolution;

        log.info("Generating opening video - userNo: {}, model: {} (genre-specific), resolution: {}, duration: {}s, hasCharacterBlock: {}, creatorId: {}, hasScenarioInfo: {}",
                userNo, modelToUse, resolution, opening.getDurationSeconds(),
                characterBlock != null && !characterBlock.isEmpty(), creatorId, scenarioInfo != null);

        try {
            Files.createDirectories(Paths.get(OPENING_DIR));

            // v2.9.77: 장르 기반 프롬프트 강화 - 오프닝 나레이션 + 시나리오 컨텍스트 포함
            // v2.9.84: 참조 이미지 분석 포함
            // v2.9.101: 첫 번째 슬라이드 나레이션 추가 (오프닝→슬라이드1 자연스러운 연결)
            String openingNarration = opening.getNarration();
            String scenarioContext = buildScenarioContext(scenarioInfo);
            String referenceImageAnalysis = (scenarioInfo != null) ? scenarioInfo.getReferenceImageAnalysis() : null;
            String firstSlideNarration = extractFirstSlideNarration(scenarioInfo);

            // v2.9.180: 마지막 슬라이드 덮어쓰기 로직 제거 (v2.9.112 로직 삭제)
            // opening.videoPrompt를 그대로 사용하여 시나리오와 일치하는 오프닝 영상 생성
            // 문제: v2.9.112에서 마지막 슬라이드 imagePrompt로 덮어씌워 시나리오와 불일치하는 영상 생성됨
            String originalVideoPrompt = videoPrompt; // 폴백용 원본 저장
            String promptForVideo = videoPrompt;      // 원본 videoPrompt 그대로 사용
            boolean usedLastSlide = false;            // 항상 false (덮어쓰기 로직 제거)
            log.info("[v2.9.180] 오프닝 영상에 원본 videoPrompt 사용 - creatorId: {}, length: {}",
                    creatorId, promptForVideo != null ? promptForVideo.length() : 0);

            String enhancedPrompt = enhanceVeoPromptWithGenre(promptForVideo, tier, characterBlock, creatorId, openingNarration, scenarioContext, referenceImageAnalysis, firstSlideNarration);
            log.info("[Veo API] Enhanced prompt for opening video (first 500 chars): {}",
                    enhancedPrompt.substring(0, Math.min(500, enhancedPrompt.length())));

            String videoPath = null;

            // v2.9.91: 참조 이미지 추출 (Veo 3.1 최대 3개 지원)
            List<String> referenceImagesBase64 = (scenarioInfo != null) ? scenarioInfo.getReferenceImagesBase64() : null;
            List<String> referenceImagesMimeTypes = (scenarioInfo != null) ? scenarioInfo.getReferenceImagesMimeTypes() : null;
            boolean hasReferenceImages = referenceImagesBase64 != null && !referenceImagesBase64.isEmpty()
                && referenceImagesMimeTypes != null && !referenceImagesMimeTypes.isEmpty();

            if (hasReferenceImages) {
                log.info("[Veo] v2.9.91: Found {} reference images for opening video generation (Veo 3.1 only)",
                        Math.min(3, referenceImagesBase64.size()));
            }

            // v2.9.6: 폴백 로직 - 기본 모델 실패 시 폴백 모델로 재시도
            try {
                // v2.9.91: 참조 이미지가 있고 Veo 3.1 모델인 경우에만 참조 이미지 포함
                if (hasReferenceImages && modelToUse.contains("veo-3.1")) {
                    videoPath = callVeoApiWithReferenceImages(enhancedPrompt, modelToUse, resolution, referenceImagesBase64, referenceImagesMimeTypes);
                } else {
                    videoPath = callVeoApi(enhancedPrompt, modelToUse, resolution);
                }
            } catch (Exception primaryError) {
                log.warn("[Veo] Primary model ({}) failed: {}", modelToUse, primaryError.getMessage());

                // v2.9.168: 콘텐츠 정책 위반 감지 확장 (raiMediaFilteredReasons, celebrity 등)
                String errMsg = primaryError.getMessage() != null ? primaryError.getMessage() : "";
                boolean isContentPolicyError = errMsg.contains("violate") || errMsg.contains("usage guidelines")
                        || errMsg.contains("content policy") || errMsg.contains("celebrity")
                        || errMsg.contains("real people") || errMsg.contains("raiMediaFiltered");

                if (isContentPolicyError) {
                    // v2.9.168: 콘텐츠 정책 위반 시 크리에이터 이름/채널명 제거 후 재시도
                    log.warn("[v2.9.168] Content policy violation detected - sanitizing prompt and retrying");
                    String retryPrompt = usedLastSlide ? originalVideoPrompt : promptForVideo;
                    String fallbackEnhancedPrompt = enhanceVeoPromptWithGenre(retryPrompt, tier, characterBlock, creatorId, openingNarration, scenarioContext, referenceImageAnalysis, firstSlideNarration);
                    // 크리에이터 이름/채널명을 제거하여 celebrity 필터 회피
                    fallbackEnhancedPrompt = sanitizePromptForContentPolicy(fallbackEnhancedPrompt, creatorId);
                    try {
                        videoPath = callVeoApi(fallbackEnhancedPrompt, modelToUse, resolution);
                        log.info("[v2.9.168] Sanitized prompt succeeded!");
                    } catch (Exception promptFallbackError) {
                        log.warn("[v2.9.168] Sanitized prompt also failed: {} - trying fallback model", promptFallbackError.getMessage());
                        String fallbackModel = genreConfigService.getFallbackVideoModel(creatorId);
                        if (fallbackModel != null && !fallbackModel.equals(modelToUse)) {
                            log.info("[Veo] Retrying with fallback model: {}", fallbackModel);
                            videoPath = callVeoApi(fallbackEnhancedPrompt, fallbackModel, resolution);
                            log.info("[Veo] Fallback model ({}) succeeded!", fallbackModel);
                        } else {
                            throw promptFallbackError;
                        }
                    }
                } else {
                    // 폴백 모델 조회 및 재시도 (폴백은 참조 이미지 없이 시도)
                    String fallbackModel = genreConfigService.getFallbackVideoModel(creatorId);
                    if (fallbackModel != null && !fallbackModel.equals(modelToUse)) {
                        log.info("[Veo] Retrying with fallback model: {} (without reference images)", fallbackModel);
                        try {
                            videoPath = callVeoApi(enhancedPrompt, fallbackModel, resolution);
                            log.info("[Veo] Fallback model ({}) succeeded!", fallbackModel);
                        } catch (Exception fallbackError) {
                            log.error("[Veo] Fallback model ({}) also failed: {}", fallbackModel, fallbackError.getMessage());
                            // v2.9.11: 폴백도 실패 시 명확한 에러
                            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                                    "Veo 영상 생성 실패: 기본/폴백 모델 모두 실패");
                        }
                    } else {
                        throw primaryError;
                    }
                }
            }

            // 생성된 파일 검증
            Path path = Paths.get(videoPath);
            if (!Files.exists(path) || Files.size(path) == 0) {
                // v2.9.11: 파일 검증 실패 처리
                throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                        "오프닝 영상 파일이 생성되지 않았습니다");
            }

            log.info("Opening video generated successfully: {} ({} bytes)", videoPath, Files.size(path));
            return videoPath;
        } catch (Exception e) {
            log.error("Opening video generation FAILED: {}", e.getMessage());
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED, "오프닝 영상 생성 실패: " + e.getMessage());
        } finally {
            currentApiKey.remove();
        }
    }

    /**
     * v2.9.77: 시나리오 컨텍스트 빌드 - 전체 스토리 라인을 요약
     * @param scenarioInfo 시나리오 정보 (null 가능)
     * @return 시나리오 컨텍스트 문자열 (없으면 빈 문자열)
     */
    private String buildScenarioContext(VideoDto.ScenarioInfo scenarioInfo) {
        if (scenarioInfo == null) {
            return "";
        }

        StringBuilder context = new StringBuilder();

        // 제목과 설명
        if (scenarioInfo.getTitle() != null && !scenarioInfo.getTitle().isEmpty()) {
            context.append("STORY TITLE: ").append(scenarioInfo.getTitle()).append("\n");
        }
        if (scenarioInfo.getDescription() != null && !scenarioInfo.getDescription().isEmpty()) {
            context.append("DESCRIPTION: ").append(scenarioInfo.getDescription()).append("\n\n");
        }

        // 슬라이드들의 나레이션 요약 (전체 스토리 흐름)
        if (scenarioInfo.getSlides() != null && !scenarioInfo.getSlides().isEmpty()) {
            context.append("=== FULL STORY OUTLINE (").append(scenarioInfo.getSlides().size()).append(" scenes) ===\n");
            for (int i = 0; i < scenarioInfo.getSlides().size(); i++) {
                VideoDto.SlideScene slide = scenarioInfo.getSlides().get(i);
                context.append("Scene ").append(i + 1).append(": ");

                // 나레이션 포함 (최대 300자)
                if (slide.getNarration() != null && !slide.getNarration().isEmpty()) {
                    String narration = slide.getNarration();
                    if (narration.length() > 300) {
                        narration = narration.substring(0, 300) + "...";
                    }
                    context.append(narration);
                }

                // 이미지 프롬프트 핵심만 (최대 100자)
                if (slide.getImagePrompt() != null && !slide.getImagePrompt().isEmpty()) {
                    String prompt = slide.getImagePrompt();
                    if (prompt.length() > 100) {
                        prompt = prompt.substring(0, 100) + "...";
                    }
                    context.append(" [Visual: ").append(prompt).append("]");
                }
                context.append("\n");
            }
        }

        String result = context.toString();
        log.info("[Veo] Built scenario context - length: {}", result.length());
        return result;
    }

    /**
     * v2.9.101: 첫 번째 슬라이드 나레이션 추출
     * 오프닝 영상과 슬라이드 1의 자연스러운 연결을 위해 사용
     */
    private String extractFirstSlideNarration(VideoDto.ScenarioInfo scenarioInfo) {
        if (scenarioInfo == null || scenarioInfo.getSlides() == null || scenarioInfo.getSlides().isEmpty()) {
            return "";
        }
        VideoDto.SlideScene firstSlide = scenarioInfo.getSlides().get(0);
        String narration = firstSlide.getNarration();
        if (narration == null || narration.isEmpty()) {
            return "";
        }
        log.info("[Veo] v2.9.101: Extracted first slide narration - length: {}", narration.length());
        return narration;
    }

    /**
     * v2.8.0: 장르 기반 Veo 프롬프트 강화 (DB 필수 - 하드코딩 폴백 없음)
     * v2.9.77: 오프닝 나레이션 + 시나리오 컨텍스트 추가
     * v2.9.101: 첫 번째 슬라이드 나레이션 추가
     * @throws ApiException DB에 프롬프트가 없으면 예외 발생
     */
    private String enhanceVeoPromptWithGenre(String originalPrompt, QualityTier tier, String characterBlock, Long creatorId, String openingNarration, String scenarioContext, String referenceImageAnalysis, String firstSlideNarration) {
        // DB에서 장르별 오프닝 영상 프롬프트 조회 (필수)
        String genreOpeningPrompt = genreConfigService.getOpeningVideoPrompt(creatorId);

        if (genreOpeningPrompt == null || genreOpeningPrompt.trim().isEmpty()) {
            log.error("[Veo] ❌ OPENING_VIDEO prompt not found in DB for creatorId: {}", creatorId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                "장르 " + creatorId + "의 오프닝 영상 프롬프트가 DB에 없습니다. 관리자에게 문의하세요.");
        }

        log.info("[Veo] Using DB OPENING_VIDEO prompt for creatorId: {} (length: {})", creatorId, genreOpeningPrompt.length());

        // v2.9.117: 하드코딩 프롬프트 완전 제거 - 모든 스타일/카메라 지시는 DB OPENING_VIDEO 프롬프트에서

        // v2.9.25: DB 프롬프트에 시나리오 프롬프트, 캐릭터 블록, 방향 삽입
        // v2.9.77: 오프닝 나레이션 + 시나리오 컨텍스트 추가
        String orientation = getOrientationPrompt();
        String aspectRatio = getCurrentAspectRatio();

        // v2.9.159: 데드 코드 제거
        // - {{CHARACTER_BLOCK}}, {{NEGATIVE_PROMPTS_CHARACTER}}는 getOpeningVideoPrompt() 내부의
        //   composePrompt()에서 이미 치환 완료됨 (Base 템플릿의 <forbidden> 섹션에 포함)
        // - videoPromptBlock도 Base 템플릿의 개별 플레이스홀더로 이미 주입됨
        // - 따라서 여기서 재치환할 필요 없음

        // 런타임 전용 플레이스홀더만 치환 (composePrompt()가 처리하지 않는 값들)
        String result = genreOpeningPrompt
            .replace("{{VIDEO_PROMPT}}", originalPrompt != null ? originalPrompt : "")
            .replace("{{ORIENTATION}}", orientation)
            .replace("{{ASPECT_RATIO}}", aspectRatio)
            .replace("{{OPENING_NARRATION}}", openingNarration != null ? openingNarration : "")
            .replace("{{SCENARIO_CONTEXT}}", scenarioContext != null ? scenarioContext : "")
            .replace("{{FIRST_SLIDE_NARRATION}}", firstSlideNarration != null ? firstSlideNarration : "");

        // v2.9.162: characterBlock 이중 주입 제거
        // - composePrompt()가 Base 템플릿의 {{CHARACTER_BLOCK}} 플레이스홀더를 이미 치환 완료
        // - 여기서 다시 prepend하면 동일 캐릭터 정보가 2번 포함되어 토큰 낭비

        // v2.9.162: 나레이션/시나리오컨텍스트 폴백 삽입 제거
        // - {{OPENING_NARRATION}}, {{SCENARIO_CONTEXT}}는 439-445에서 이미 치환됨
        // - Base 템플릿에 플레이스홀더가 없으면 해당 프롬프트 타입에서 의도적으로 제외한 것
        // - 강제 append는 프롬프트 구조를 파괴하고 토큰 낭비를 유발

        // v2.9.91: 참조 이미지 분석 결과를 프롬프트 상단에 강력히 주입
        // Gemini API의 Veo는 referenceImages 파라미터를 지원하지 않으므로 프롬프트로 전달
        if (referenceImageAnalysis != null && !referenceImageAnalysis.isEmpty() && !referenceImageAnalysis.equals("{}")) {
            String referenceBlock = "\n\n" +
                "╔══════════════════════════════════════════════════════════════════╗\n" +
                "║  🎯 CRITICAL: REFERENCE IMAGE VISUAL GUIDE (HIGHEST PRIORITY)  ║\n" +
                "╚══════════════════════════════════════════════════════════════════╝\n\n" +
                "The user uploaded a reference image. You MUST recreate the following visual elements:\n\n" +
                referenceImageAnalysis + "\n\n" +
                "=== STRICT REQUIREMENTS ===\n" +
                "1. PRODUCT/OBJECT: If the reference shows a product, it MUST appear prominently in the video.\n" +
                "2. STYLE: Match the exact color palette, lighting style, and visual mood.\n" +
                "3. COMPOSITION: Follow similar framing and camera angles.\n" +
                "4. ATMOSPHERE: Recreate the same emotional tone and ambiance.\n" +
                "5. CONSISTENCY: Every frame must reflect the reference image's style.\n\n" +
                "⚠️ DO NOT ignore the reference image. The video MUST look like it belongs to the same visual world.\n";

            // 참조 이미지 가이드를 프롬프트 앞부분에 삽입하여 우선순위 높임
            result = referenceBlock + result;
            log.info("[Veo] v2.9.91: Prepended reference image analysis to prompt (high priority) - length: {}", referenceImageAnalysis.length());
        }

        // v2.9.159: negativePrompts 폴백 블록 제거
        // - composePrompt()가 Base 템플릿의 <forbidden> 섹션에서 {{NEGATIVE_PROMPTS_CHARACTER}}를 이미 치환함
        // - 별도 조회 및 폴백 추가 불필요

        log.info("[Veo] Final enhanced prompt length: {}", result.length());
        return result;
    }

    // v2.8.0: 모든 Veo 프롬프트는 DB에서 로드 (하드코딩 폴백 제거됨)
    // enhanceVeoPrompt() 메서드 삭제 - enhanceVeoPromptWithGenre()로 대체

    /**
     * v2.9.91: Veo 3.1 API 호출 (predictLongRunning 메서드)
     * 참고: https://ai.google.dev/gemini-api/docs/video
     *
     * 공식 문서 기준:
     * - 엔드포인트: https://generativelanguage.googleapis.com/v1beta/models/veo-3.1-generate-preview:predictLongRunning
     * - 인증: x-goog-api-key 헤더 사용
     *
     * 참고: Gemini API의 Veo는 referenceImages 파라미터를 지원하지 않음
     * 참조 이미지 스타일은 referenceImageAnalysis로 프롬프트에 반영됨
     */
    private String callVeoApi(String prompt, String model, String resolution) {
        String apiUrl = String.format(VEO_API_URL, model);
        log.info("[VEO] Calling Veo API: {}", apiUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 공식 문서 기준: x-goog-api-key 헤더 사용
        headers.set("x-goog-api-key", currentApiKey.get());

        // Veo 3.1 API 요청 본문 (predictLongRunning 형식)
        Map<String, Object> requestBody = new HashMap<>();

        // instances - 프롬프트
        requestBody.put("instances", List.of(Map.of("prompt", prompt)));

        // parameters - 비디오 생성 설정
        // 참고: personGeneration 옵션 - "dont_allow", "allow_all" (allow_adult는 지원 안함)
        // v2.9.25: 포맷별 동적 aspectRatio 적용
        String aspectRatio = getCurrentAspectRatio();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("aspectRatio", aspectRatio);
        parameters.put("personGeneration", "allow_all");  // 인물 생성 허용
        parameters.put("sampleCount", 1);
        // durationSeconds는 Veo 3.1에서 8초 고정

        requestBody.put("parameters", parameters);
        log.info("[VEO] Using aspectRatio: {}", aspectRatio);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("Veo 3.1 API request (predictLongRunning): {}", requestJson.substring(0, Math.min(800, requestJson.length())));

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            log.info("Veo 3.1 API response status: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // predictLongRunning은 operation 반환 -> 폴링 필요
                return pollVeoOperation(response.getBody());
            }

            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo API 응답 오류: " + response.getStatusCode());

        } catch (ApiException e) {
            throw e;  // ApiException은 그대로 전파
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // v2.9.11: HTTP 클라이언트 에러 (4xx)
            log.error("[Veo] HTTP 클라이언트 에러 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo API 요청 실패: " + e.getStatusCode());
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // v2.9.11: HTTP 서버 에러 (5xx)
            log.error("[Veo] HTTP 서버 에러 - status: {}", e.getStatusCode());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Veo 서버 오류: " + e.getStatusCode());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // v2.9.11: 네트워크/타임아웃 에러
            log.error("[Veo] 네트워크 에러: {}", e.getMessage());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Veo 서버 연결 실패");
        } catch (Exception e) {
            log.error("[Veo] 예상치 못한 에러: {}", e.getMessage(), e);
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo API 호출 실패: " + e.getMessage());
        }
    }

    /**
     * v2.9.144: 참조 이미지가 있는 경우에도 일반 Veo API 호출
     *
     * 참조 이미지 분석 결과는 이미 프롬프트에 포함되어 있으므로,
     * referenceImages 파라미터 없이 프롬프트 기반으로 영상 생성
     *
     * ⚠️ referenceImages 파라미터는 현재 Gemini API에서 지원되지 않음
     * (Vertex AI 전용 기능으로 추정)
     */
    private String callVeoApiWithReferenceImages(String prompt, String model, String resolution,
                                                  List<String> referenceImagesBase64, List<String> referenceImagesMimeTypes) {
        log.info("[VEO] v2.9.144: Reference images detected ({}개), but using prompt-only approach (referenceImages not supported in Gemini API)",
                referenceImagesBase64 != null ? referenceImagesBase64.size() : 0);

        // 참조 이미지 분석 결과는 이미 프롬프트에 포함되어 있으므로,
        // 일반 callVeoApi 메서드를 사용하여 프롬프트 기반으로 영상 생성
        return callVeoApi(prompt, model, resolution);
    }

    /**
     * Veo 작업 폴링 및 결과 다운로드
     */
    private String pollVeoOperation(String operationResponse) throws Exception {
        JsonNode root = objectMapper.readTree(operationResponse);

        if (!root.has("name")) {
            // v2.9.11: 구체적인 에러 메시지
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo API 응답에 operation name이 없습니다");
        }

        String operationName = root.get("name").asText();
        log.info("Veo operation started: {}", operationName);

        // 최대 5분간 폴링 (10초 간격)
        for (int i = 0; i < 30; i++) {
            Thread.sleep(10000);

            String statusUrl = String.format(VEO_OPERATION_URL, operationName);
            HttpHeaders pollHeaders = new HttpHeaders();
            pollHeaders.set("x-goog-api-key", currentApiKey.get());
            HttpEntity<String> pollEntity = new HttpEntity<>(pollHeaders);
            ResponseEntity<String> statusResponse = restTemplate.exchange(statusUrl, HttpMethod.GET, pollEntity, String.class);

            if (statusResponse.getStatusCode() == HttpStatus.OK && statusResponse.getBody() != null) {
                JsonNode statusNode = objectMapper.readTree(statusResponse.getBody());

                if (statusNode.has("done") && statusNode.get("done").asBoolean()) {
                    log.info("Veo operation completed");
                    return downloadVeoVideo(statusNode);
                }

                log.debug("Veo operation still in progress... ({}/30)", i + 1);
            }
        }

        // v2.9.11: 타임아웃 에러 처리 개선
        throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "Veo 영상 생성 시간 초과 (5분). 잠시 후 다시 시도해주세요.");
    }

    /**
     * Veo 결과 영상 다운로드 (Veo 3.1 응답 형식 지원)
     * 응답 형식:
     * - response.videos[0].gcsUri (Veo 3.1)
     * - response.generateVideoResponse.generatedSamples[0].video.uri (이전 형식)
     */
    private String downloadVeoVideo(JsonNode statusNode) throws Exception {
        log.info("Parsing Veo response for video download...");

        // 에러 확인
        if (statusNode.has("error")) {
            String errorMessage = statusNode.path("error").path("message").asText("Unknown error");
            // v2.9.11: 구체적인 에러 메시지
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo 영상 생성 실패: " + errorMessage);
        }

        String videoUri = null;

        // 형식 1: response.videos[0].gcsUri (Veo 3.1)
        JsonNode videos = statusNode.path("response").path("videos");
        if (videos.isArray() && videos.size() > 0) {
            videoUri = videos.get(0).path("gcsUri").asText(null);
            if (videoUri == null) {
                videoUri = videos.get(0).path("uri").asText(null);
            }
            log.info("Found video URI (format 1): {}", videoUri);
        }

        // 형식 2: response.generateVideoResponse.generatedSamples[0].video.uri
        if (videoUri == null) {
            JsonNode generatedSamples = statusNode.path("response")
                    .path("generateVideoResponse")
                    .path("generatedSamples");
            if (generatedSamples.isArray() && generatedSamples.size() > 0) {
                videoUri = generatedSamples.get(0).path("video").path("uri").asText(null);
                if (videoUri == null) {
                    videoUri = generatedSamples.get(0).path("video").path("gcsUri").asText(null);
                }
                log.info("Found video URI (format 2): {}", videoUri);
            }
        }

        // 형식 3: result.videos[0].video.uri (다른 가능한 형식)
        if (videoUri == null) {
            JsonNode resultVideos = statusNode.path("result").path("videos");
            if (resultVideos.isArray() && resultVideos.size() > 0) {
                videoUri = resultVideos.get(0).path("video").path("uri").asText(null);
                log.info("Found video URI (format 3): {}", videoUri);
            }
        }

        if (videoUri == null || videoUri.isEmpty()) {
            // v2.9.168: raiMediaFilteredReasons 감지 (콘텐츠 정책 필터)
            String raiReason = extractRaiFilteredReason(statusNode);
            if (raiReason != null) {
                log.warn("[v2.9.168] Veo content policy filter detected - raiMediaFilteredReasons: {}", raiReason);
                throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                        "Veo content policy violation: " + raiReason);
            }

            log.error("No video URI found in Veo response. Full response: {}",
                    statusNode.toString().substring(0, Math.min(2000, statusNode.toString().length())));
            // v2.9.11: 구체적인 에러 메시지
            throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                    "Veo 응답에서 영상 URI를 찾을 수 없습니다");
        }

        // 비디오 다운로드
        String videoId = UUID.randomUUID().toString();
        Path videoPath = Paths.get(OPENING_DIR, videoId + ".mp4");

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-goog-api-key", currentApiKey.get());

        log.info("Downloading video from: {}", videoUri);
        ResponseEntity<byte[]> videoResponse = restTemplate.exchange(
                videoUri,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class
        );

        if (videoResponse.getStatusCode() == HttpStatus.OK && videoResponse.getBody() != null) {
            Files.write(videoPath, videoResponse.getBody());
            log.info("Opening video saved: {} ({} bytes)", videoPath, videoResponse.getBody().length);
            return videoPath.toAbsolutePath().toString();
        }

        // v2.9.11: 다운로드 실패 처리
        throw new ApiException(ErrorCode.VIDEO_GENERATION_FAILED,
                "Veo 영상 다운로드 실패: " + videoUri);
    }

    /**
     * v2.9.168: Veo 응답에서 raiMediaFilteredReasons 필드 추출
     * 콘텐츠 정책 필터에 걸린 경우 이유 문자열 반환, 없으면 null
     */
    private String extractRaiFilteredReason(JsonNode statusNode) {
        // response.raiMediaFilteredReasons (직접 필드)
        JsonNode raiReasons = statusNode.path("response").path("raiMediaFilteredReasons");
        if (!raiReasons.isMissingNode() && raiReasons.isTextual()) {
            return raiReasons.asText();
        }
        if (raiReasons.isArray() && raiReasons.size() > 0) {
            return raiReasons.get(0).asText();
        }

        // 전체 응답 문자열에서 raiMediaFilteredReasons 검색 (중첩 위치 대응)
        String fullResponse = statusNode.toString();
        if (fullResponse.contains("raiMediaFilteredReasons")) {
            int idx = fullResponse.indexOf("raiMediaFilteredReasons");
            String snippet = fullResponse.substring(idx, Math.min(idx + 300, fullResponse.length()));
            return snippet;
        }

        return null;
    }

    /**
     * v2.9.168: 콘텐츠 정책 위반 시 크리에이터 이름/채널명을 프롬프트에서 제거
     * Veo API가 실제 인물(celebrity)로 인식하는 이름을 제거하여 필터 회피
     */
    private String sanitizePromptForContentPolicy(String prompt, Long creatorId) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }

        String sanitized = prompt;

        try {
            if (creatorId != null) {
                var creator = genreConfigService.getCreator(creatorId);

                // 크리에이터 이름 제거 (콘텐츠 정책 필터 대응)
                if (creator.getCreatorName() != null && !creator.getCreatorName().isBlank()) {
                    String name = creator.getCreatorName().trim();
                    sanitized = sanitized.replace(name, "a young woman");
                    sanitized = sanitized.replace(name.toLowerCase(), "a young woman");
                    sanitized = sanitized.replace(name.toUpperCase(), "a young woman");
                }

                // Remove YouTube channel name (e.g. "Channel (한글명)" → full + each part)
                if (creator.getYoutubeChannel() != null && !creator.getYoutubeChannel().isBlank()) {
                    String channel = creator.getYoutubeChannel().trim();
                    sanitized = sanitized.replace(channel, "");

                    // Also remove the Korean name inside parentheses separately
                    if (channel.contains("(") && channel.contains(")")) {
                        String koreanPart = channel.substring(channel.indexOf("(") + 1, channel.indexOf(")")).trim();
                        if (!koreanPart.isEmpty()) {
                            sanitized = sanitized.replace(koreanPart, "");
                        }
                        String englishPart = channel.substring(0, channel.indexOf("(")).trim();
                        if (!englishPart.isEmpty()) {
                            sanitized = sanitized.replace(englishPart, "");
                            sanitized = sanitized.replace(englishPart.toLowerCase(), "");
                        }
                    }
                }
            }

            // 연속 공백 정리
            sanitized = sanitized.replaceAll("\\s{2,}", " ").trim();
            log.info("[v2.9.168] Prompt sanitized for content policy (creatorId={})", creatorId);
        } catch (Exception e) {
            log.warn("[v2.9.168] Failed to sanitize prompt: {} - using original", e.getMessage());
            return prompt;
        }

        return sanitized;
    }

    @Override
    public String composeVideo(String openingVideoUrl, List<String> imageUrls, String narrationUrl, VideoDto.ScenarioInfo scenario) {
        log.info("=== VIDEO COMPOSITION START ===");
        log.info("Opening URL: {}", openingVideoUrl);
        log.info("Image URLs ({}개): {}", imageUrls != null ? imageUrls.size() : 0, imageUrls);
        log.info("Narration URL: {}", narrationUrl);

        String jobId = UUID.randomUUID().toString();
        Path workDir = Paths.get(WORK_DIR, jobId);
        boolean success = false;

        try {
            // 1. 작업 디렉토리 생성
            Files.createDirectories(workDir);
            log.info("Created work directory: {}", workDir);

            // 2. 자산 다운로드
            log.info("Step 2: Downloading assets...");

            // 오프닝 영상 (null이면 스킵)
            Path openingPath = workDir.resolve("opening.mp4");
            if (openingVideoUrl != null && !openingVideoUrl.isEmpty()) {
                openingPath = downloadFile(openingVideoUrl, openingPath);
                log.info("Opening file size: {} bytes", Files.size(openingPath));
            } else {
                log.info("Opening video skipped (no URL provided)");
            }

            // 이미지들 다운로드
            List<Path> imagePaths = downloadImages(imageUrls, workDir);
            for (int i = 0; i < imagePaths.size(); i++) {
                log.info("Image {} size: {} bytes", i, Files.exists(imagePaths.get(i)) ? Files.size(imagePaths.get(i)) : 0);
            }

            // 나레이션 (null이면 스킵)
            Path narrationPath = workDir.resolve("narration.mp3");
            if (narrationUrl != null && !narrationUrl.isEmpty()) {
                narrationPath = downloadFile(narrationUrl, narrationPath);
                log.info("Narration file size: {} bytes", Files.size(narrationPath));
            } else {
                log.info("Narration skipped (no URL provided)");
            }

            // 3. 자막 파일 생성
            log.info("Step 3: Generating subtitles...");
            String openingNarration = scenario.getOpening() != null ? scenario.getOpening().getNarration() : "";
            int openingDuration = scenario.getOpening() != null ? scenario.getOpening().getDurationSeconds() : 0;
            String subtitleContent = subtitleService.generateSubtitles(
                    scenario.getSlides(),
                    openingNarration,
                    openingDuration
            );
            Path subtitlePath = workDir.resolve("subtitles.ass");
            Files.writeString(subtitlePath, subtitleContent);
            log.info("Generated subtitle file: {} ({} bytes)", subtitlePath, subtitleContent.length());

            // 4. 이미지 슬라이드 영상 생성
            log.info("Step 4: Creating slideshow...");
            Path slideshowPath = createSlideshow(imagePaths, scenario.getSlides(), workDir);
            log.info("Slideshow created: {} ({} bytes)", slideshowPath, Files.size(slideshowPath));

            // 5. 오프닝 + 슬라이드쇼 합치기
            log.info("Step 5: Combining videos...");
            Path combinedVideoPath = combineVideos(openingPath, slideshowPath, workDir);
            log.info("Combined video: {} ({} bytes)", combinedVideoPath, Files.size(combinedVideoPath));

            // 6. 나레이션 오디오 추가 (나레이션이 있을 때만)
            Path withAudioPath;
            if (Files.exists(narrationPath) && Files.size(narrationPath) > 0) {
                log.info("Step 6: Adding audio...");
                withAudioPath = addAudio(combinedVideoPath, narrationPath, workDir);
                log.info("With audio: {} ({} bytes)", withAudioPath, Files.size(withAudioPath));
            } else {
                log.info("Step 6: Skipping audio (no narration available)");
                withAudioPath = combinedVideoPath;
            }

            // 7. 자막 추가 및 최종 인코딩
            log.info("Step 7: Adding subtitles...");
            Path finalVideoPath = addSubtitles(withAudioPath, subtitlePath, workDir);
            log.info("Final video: {} ({} bytes)", finalVideoPath, Files.size(finalVideoPath));

            // 8. GCS에 업로드 (현재는 로컬 경로 반환)
            String outputUrl = uploadToStorage(finalVideoPath, jobId);

            log.info("=== VIDEO COMPOSITION COMPLETE: {} ===", outputUrl);
            success = true;
            return outputUrl;

        } catch (Exception e) {
            log.error("=== VIDEO COMPOSITION FAILED ===", e);
            log.error("Work directory preserved for debugging: {}", workDir);
            throw new ApiException(ErrorCode.VIDEO_COMPOSITION_FAILED, e);
        } finally {
            // 성공한 경우에만 작업 디렉토리 정리
            if (success) {
                cleanupWorkDir(workDir);
            } else {
                log.warn("Work directory NOT cleaned up due to failure: {}", workDir);
            }
        }
    }

    /**
     * 파일 다운로드 또는 복사 - 실패 시 에러 발생 (폴백 없음)
     */
    private Path downloadFile(String urlOrPath, Path destination) throws IOException {
        log.info("downloadFile called - source: {}, dest: {}", urlOrPath, destination);

        if (urlOrPath == null || urlOrPath.isEmpty()) {
            throw new IOException("파일 경로가 비어있습니다: " + destination.getFileName());
        }

        // 로컬 파일 경로인 경우 (절대경로 또는 /tmp로 시작)
        if (urlOrPath.startsWith("/") || urlOrPath.startsWith("file:")) {
            Path sourcePath = Paths.get(urlOrPath.replace("file:", ""));
            log.info("Local file path detected: {}, exists: {}", sourcePath, Files.exists(sourcePath));

            if (!Files.exists(sourcePath)) {
                throw new IOException("로컬 파일을 찾을 수 없습니다: " + sourcePath);
            }

            long fileSize = Files.size(sourcePath);
            if (fileSize == 0) {
                throw new IOException("로컬 파일이 비어있습니다: " + sourcePath);
            }

            log.info("Copying local file: {} ({} bytes) -> {}", sourcePath, fileSize, destination);
            Files.copy(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        }

        // Mock URL은 에러로 처리
        if (urlOrPath.startsWith("https://storage.googleapis.com/aivideo/")) {
            throw new IOException("Mock URL은 지원되지 않습니다: " + urlOrPath);
        }

        // 실제 URL 다운로드
        log.info("Downloading from URL: {} -> {}", urlOrPath, destination);
        try (InputStream in = new URL(urlOrPath).openStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        // 다운로드 결과 검증
        if (!Files.exists(destination) || Files.size(destination) == 0) {
            throw new IOException("파일 다운로드 실패 또는 파일이 비어있습니다: " + urlOrPath);
        }

        return destination;
    }

    /**
     * 이미지들 다운로드
     */
    private List<Path> downloadImages(List<String> imageUrls, Path workDir) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            Path imagePath = workDir.resolve(String.format("slide_%03d.png", i));
            downloadFile(imageUrls.get(i), imagePath);
            paths.add(imagePath);
        }
        return paths;
    }

    /**
     * 이미지 슬라이드쇼 생성
     */
    private Path createSlideshow(List<Path> imagePaths, List<VideoDto.SlideScene> slides, Path workDir) throws IOException, InterruptedException {
        Path outputPath = workDir.resolve("slideshow.mp4");

        // concat demuxer용 파일 목록 생성
        StringBuilder concatList = new StringBuilder();
        for (int i = 0; i < imagePaths.size(); i++) {
            Path imagePath = imagePaths.get(i);
            // 기본값 10초 (ScenarioGeneratorServiceImpl, SubtitleServiceImpl과 동일)
            int duration = (i < slides.size() && slides.get(i).getDurationSeconds() > 0)
                    ? slides.get(i).getDurationSeconds() : 10;

            concatList.append(String.format("file '%s'%n", imagePath.toAbsolutePath()));
            concatList.append(String.format("duration %d%n", duration));
        }

        // 마지막 프레임 추가 (concat 요구사항)
        if (!imagePaths.isEmpty()) {
            concatList.append(String.format("file '%s'%n", imagePaths.get(imagePaths.size() - 1).toAbsolutePath()));
        }

        Path concatFile = workDir.resolve("concat.txt");
        Files.writeString(concatFile, concatList.toString());

        // FFmpeg로 슬라이드쇼 생성
        List<String> command = List.of(
                "ffmpeg", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.toString(),
                "-vf", String.format("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1",
                        VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_WIDTH, VIDEO_HEIGHT),
                "-c:v", VIDEO_CODEC,
                "-r", String.valueOf(VIDEO_FPS),
                "-pix_fmt", "yuv420p",
                outputPath.toString()
        );

        executeFFmpeg(command, "slideshow creation");
        return outputPath;
    }

    /**
     * 오프닝 + 슬라이드쇼 합치기 (1080p 통일)
     *
     * Veo 오프닝 영상(720p)과 슬라이드쇼(1080p)의 해상도를 맞추기 위해
     * 오프닝 영상을 먼저 1080p로 업스케일한 후 합칩니다.
     */
    private Path combineVideos(Path opening, Path slideshow, Path workDir) throws IOException, InterruptedException {
        Path outputPath = workDir.resolve("combined.mp4");

        // 오프닝 파일이 없거나 비어있으면 슬라이드쇼만 사용
        if (!Files.exists(opening) || Files.size(opening) == 0) {
            log.info("Opening video not available, using slideshow only");
            Files.copy(slideshow, outputPath, StandardCopyOption.REPLACE_EXISTING);
            return outputPath;
        }

        // 1. 오프닝 영상을 1080p로 업스케일
        Path scaledOpening = workDir.resolve("opening_1080p.mp4");
        List<String> scaleCommand = List.of(
                "ffmpeg", "-y",
                "-i", opening.toString(),
                "-vf", String.format("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1",
                        VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_WIDTH, VIDEO_HEIGHT),
                "-c:v", VIDEO_CODEC,
                "-r", String.valueOf(VIDEO_FPS),
                "-pix_fmt", "yuv420p",
                "-c:a", AUDIO_CODEC,
                "-b:a", "192k",
                scaledOpening.toString()
        );
        executeFFmpeg(scaleCommand, "opening upscale to 1080p");
        log.info("Opening video upscaled to 1080p: {} ({} bytes)", scaledOpening, Files.size(scaledOpening));

        // 2. concat 파일 생성 (업스케일된 오프닝 + 슬라이드쇼)
        String concatContent = String.format("file '%s'%nfile '%s'%n",
                scaledOpening.toAbsolutePath(), slideshow.toAbsolutePath());
        Path concatFile = workDir.resolve("videos.txt");
        Files.writeString(concatFile, concatContent);

        // 3. 두 영상 합치기 (같은 해상도/코덱이므로 copy 가능)
        List<String> command = List.of(
                "ffmpeg", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.toString(),
                "-c", "copy",
                outputPath.toString()
        );

        executeFFmpeg(command, "video combination");
        log.info("Videos combined at 1080p: {} ({} bytes)", outputPath, Files.size(outputPath));
        return outputPath;
    }

    /**
     * 오디오 추가
     */
    private Path addAudio(Path video, Path audio, Path workDir) throws IOException, InterruptedException {
        Path outputPath = workDir.resolve("with_audio.mp4");

        List<String> command = List.of(
                "ffmpeg", "-y",
                "-i", video.toString(),
                "-i", audio.toString(),
                "-c:v", "copy",
                "-c:a", AUDIO_CODEC,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-shortest",
                outputPath.toString()
        );

        executeFFmpeg(command, "audio addition");
        return outputPath;
    }

    /**
     * 자막 추가 (ASS 하드코딩)
     * FFmpeg libass 필터 사용 - homebrew-ffmpeg/ffmpeg 설치 필요
     */
    private Path addSubtitles(Path video, Path subtitles, Path workDir) throws IOException, InterruptedException {
        Path outputPath = workDir.resolve("final.mp4");

        // ASS 자막 하드코딩 (한국 드라마 스타일 큰 자막)
        // ass= 필터 사용 (libass 필요)
        List<String> command = List.of(
                "ffmpeg", "-y",
                "-i", video.toString(),
                "-vf", "ass=" + subtitles.toAbsolutePath().toString(),
                "-c:v", VIDEO_CODEC,
                "-c:a", "copy",
                "-preset", "fast",
                "-crf", "23",
                outputPath.toString()
        );

        executeFFmpeg(command, "subtitle burning");

        // 결과 파일 검증
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            // v2.9.11: 구체적인 에러 처리
            throw new ApiException(ErrorCode.VIDEO_COMPOSITION_FAILED,
                    "자막 합성 실패: 출력 파일이 생성되지 않았습니다");
        }

        log.info("Subtitles burned successfully: {} ({} bytes)", outputPath, Files.size(outputPath));
        return outputPath;
    }

    /**
     * FFmpeg 명령어 실행 (v2.9.13: ProcessExecutor 사용)
     */
    private void executeFFmpeg(List<String> command, String taskName) throws IOException, InterruptedException {
        try {
            ProcessExecutor.executeOrThrow(command, "FFmpeg-" + taskName);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ApiException(ErrorCode.VIDEO_COMPOSITION_FAILED,
                    "영상 처리 타임아웃 (" + taskName + ")");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VIDEO_COMPOSITION_FAILED,
                    "영상 처리 실패 (" + taskName + "): " + e.getMessage());
        }
    }

    /**
     * 영상 파일 저장 - 영구 저장소 + 데스크탑 복사
     *
     * 1. 영구 저장 디렉토리에 파일 이동
     * 2. 사용자 데스크탑에 복사
     * 3. 실제 파일 경로 반환 (DB 저장용)
     */
    private String uploadToStorage(Path videoPath, String jobId) {
        try {
            // 1. 영구 저장 디렉토리 설정
            String storageDir = System.getProperty("user.home") + "/aivideo/output";
            Path storagePath = Paths.get(storageDir);
            Files.createDirectories(storagePath);

            // 2. 영구 저장소에 파일 복사
            String fileName = "video_" + jobId + ".mp4";
            Path permanentPath = storagePath.resolve(fileName);
            Files.copy(videoPath, permanentPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Video saved to permanent storage: {} ({} bytes)", permanentPath, Files.size(permanentPath));

            // 3. 데스크탑에 복사
            String desktopDir = System.getProperty("user.home") + "/Desktop";
            Path desktopPath = Paths.get(desktopDir, "AIVideo_" + System.currentTimeMillis() + ".mp4");
            Files.copy(videoPath, desktopPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Video copied to Desktop: {} ({} bytes)", desktopPath, Files.size(desktopPath));

            // 4. 영구 저장소 경로 반환 (DB 저장용)
            return permanentPath.toAbsolutePath().toString();

        } catch (IOException e) {
            log.error("Failed to save video to storage: {}", e.getMessage());
            // 실패해도 원본 경로 반환 (DB에 저장은 해야함)
            return videoPath.toAbsolutePath().toString();
        }
    }

    /**
     * 작업 디렉토리 정리 (v2.9.13: ScheduledExecutorService 사용)
     */
    private void cleanupWorkDir(Path workDir) {
        if (cleanupExecutor == null || cleanupExecutor.isShutdown()) {
            log.warn("Cleanup executor not available, skipping cleanup for: {}", workDir);
            return;
        }

        cleanupExecutor.schedule(() -> {
            try {
                Files.walk(workDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.trace("Failed to delete: {}", path);
                            }
                        });
                log.debug("Cleaned up work directory: {}", workDir);
            } catch (Exception e) {
                log.warn("Failed to cleanup work directory: {}", workDir, e);
            }
        }, 1, TimeUnit.MINUTES);
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
