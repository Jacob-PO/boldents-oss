package com.aivideo.api.service;

import com.aivideo.api.dto.ContentDto;
import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.entity.Conversation;
import com.aivideo.api.entity.ConversationMessage;
import com.aivideo.api.entity.Scenario;
import com.aivideo.api.entity.Scene;
import com.aivideo.api.entity.Video;
import com.aivideo.api.entity.VideoThumbnail;
import com.aivideo.api.mapper.ConversationMapper;
import com.aivideo.api.mapper.ConversationMessageMapper;
import com.aivideo.api.mapper.ScenarioMapper;
import com.aivideo.api.mapper.SceneMapper;
import com.aivideo.api.mapper.VideoMapper;
import com.aivideo.api.mapper.VideoThumbnailMapper;
import com.aivideo.api.service.image.ImageGeneratorService;
import com.aivideo.api.service.image.ImageGeneratorService.ScenarioContext;
import com.aivideo.api.service.reference.ReferenceImageService;
import com.aivideo.api.service.storage.StorageService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ThumbnailService {

    private final VideoMapper videoMapper;
    private final ScenarioMapper scenarioMapper;
    private final SceneMapper sceneMapper;
    private final ContentService contentService;  // v2.9.47: 기존 영상에 썸네일 append
    private final ConversationMessageMapper messageMapper;  // v2.9.27: 채팅 메시지 저장
    private final ConversationMapper conversationMapper;     // v2.9.27: conversation 조회
    private final ImageGeneratorService imageGeneratorService;
    private final ApiKeyService apiKeyService;
    private final StorageService storageService;
    private final CreatorConfigService genreConfigService;  // v2.9.27
    private final com.aivideo.api.mapper.VideoFormatMapper videoFormatMapper;  // v2.9.38: 영상 포맷 조회
    private final ReferenceImageService referenceImageService;  // v2.9.90: 참조 이미지 멀티모달 지원
    private final VideoThumbnailMapper videoThumbnailMapper;  // v2.9.165: 썸네일 스타일 선택
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    // v2.9.174: 멀티폰트 캐시 (fontFileName → Font)
    private final ConcurrentHashMap<String, Font> fontCache = new ConcurrentHashMap<>();
    private static final String DEFAULT_FONT_FILE = "SUIT-Bold.ttf";

    @Value("${ai.tier.standard.scenario-model:gemini-3-flash-preview}")
    private String textModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final String THUMBNAIL_DIR = "/tmp/aivideo/thumbnails";

    // v2.9.47: 생성자 with @Lazy ContentService to avoid circular dependency
    // v2.9.90: ReferenceImageService 추가 (참조 이미지 멀티모달 지원)
    // v2.9.165: VideoThumbnailMapper 추가 (썸네일 디자인 스타일 선택)
    public ThumbnailService(
            VideoMapper videoMapper,
            ScenarioMapper scenarioMapper,
            SceneMapper sceneMapper,
            @org.springframework.context.annotation.Lazy ContentService contentService,
            ConversationMessageMapper messageMapper,
            ConversationMapper conversationMapper,
            ImageGeneratorService imageGeneratorService,
            ApiKeyService apiKeyService,
            StorageService storageService,
            CreatorConfigService genreConfigService,
            com.aivideo.api.mapper.VideoFormatMapper videoFormatMapper,
            ReferenceImageService referenceImageService,
            VideoThumbnailMapper videoThumbnailMapper,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.videoMapper = videoMapper;
        this.scenarioMapper = scenarioMapper;
        this.sceneMapper = sceneMapper;
        this.contentService = contentService;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.imageGeneratorService = imageGeneratorService;
        this.apiKeyService = apiKeyService;
        this.storageService = storageService;
        this.genreConfigService = genreConfigService;
        this.videoFormatMapper = videoFormatMapper;
        this.referenceImageService = referenceImageService;
        this.videoThumbnailMapper = videoThumbnailMapper;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * v2.9.165: thumbnailId 없이 호출 시 기본 스타일 사용 (하위 호환)
     */
    public ContentDto.ThumbnailResponse generateThumbnail(Long userNo, Long chatId) {
        return generateThumbnail(userNo, chatId, null);
    }

    /**
     * v2.9.165: 썸네일 디자인 스타일 선택 지원
     * @param thumbnailId null이면 is_default=TRUE 스타일 사용
     */
    public ContentDto.ThumbnailResponse generateThumbnail(Long userNo, Long chatId, Long thumbnailId) {
        log.info("[v2.9.165] Start generation for chat: {}, thumbnailId: {}", chatId, thumbnailId);

        // 1. Validate and Fetch Context (v2.9.59: SELECT FOR UPDATE 제거 - Lock Wait 문제 완전 해결)
        // - v2.9.59로 썸네일 씬 생성 시점이 최종 합성 직전으로 이동되어 동시 실행 충돌 없음
        // - 긴 트랜잭션(15초) 제거하여 다른 트랜잭션과의 충돌 원천 차단
        List<Video> videos = videoMapper.findByConversationId(chatId);
        Video video = videos.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video not found"));

        if (!video.getUserNo().equals(userNo)) {
            throw new ApiException(ErrorCode.CONVERSATION_UNAUTHORIZED);
        }

        // v2.9.59: 썸네일 중복 생성 방지 (프론트엔드 버튼 비활성화로 1차 방어)
        if (video.getThumbnailUrl() != null && !video.getThumbnailUrl().isEmpty()) {
            log.warn("[v2.9.59] Thumbnail already exists for videoId={}: {}", video.getVideoId(), video.getThumbnailUrl());
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "이 영상의 썸네일이 이미 생성되었습니다. 한 영상당 썸네일은 한 번만 생성할 수 있습니다.");
        }
        log.info("[v2.9.59] No existing thumbnail, proceeding with generation");

        Scenario scenario = scenarioMapper.findByVideoId(video.getVideoId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Scenario not found"));

        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());
        
        // 2. Generate Prompt and Text via Gemini
        ThumbnailPromptInfo promptInfo = generateThumbnailPrompt(userNo, video, scenario, scenes);
        log.info("[Thumbnail] Generated Prompt Info: {}", promptInfo);

        // 3. Generate Image
        // v2.9.38: 영상 포맷에 맞춰 썸네일 생성 (세로형은 9:16, 가로형은 16:9)
        // v2.9.63: 썸네일은 장르 무관 Premium 모델 사용 (고해상도 2K)
        Long formatId = video.getFormatId() != null ? video.getFormatId() : 1L;  // 기본값: YOUTUBE_STANDARD
        imageGeneratorService.setCurrentFormatId(formatId);
        imageGeneratorService.enableThumbnailMode();  // v2.9.63: 고급 모델 사용
        log.info("[Thumbnail] v2.9.63 Using format ID: {}, thumbnail mode ENABLED (premium model)", formatId);

        String imagePath;
        try {
            // v2.9.178: 사용자 커스텀 API 키 사용 (user type이 custom인 경우)
            ApiKeyService.setCurrentUserNo(userNo);
            ScenarioContext context = buildScenarioContext(video, scenario, scenes);
            imagePath = generateThumbnailImage(userNo, context, video.getCreatorId(), promptInfo.imagePrompt);
        } finally {
            ApiKeyService.clearCurrentUserNo();  // v2.9.178: 사용자 컨텍스트 정리
            imageGeneratorService.disableThumbnailMode();  // v2.9.63: 반드시 해제
        }

        // 4. v2.9.165: 썸네일 스타일 조회 (thumbnailId가 null이면 기본 스타일)
        VideoThumbnail style = resolveThumbnailStyle(thumbnailId);
        log.info("[v2.9.165] Using thumbnail style: {} ({})", style.getStyleName(), style.getStyleCode());

        // v2.9.174: fontId에서 폰트 파일명 조회 (자막과 동일한 폰트 적용)
        String fontFileName = DEFAULT_FONT_FILE;
        Long fontId = video.getFontId();
        if (fontId != null) {
            com.aivideo.api.entity.VideoFont videoFont = genreConfigService.getFont(fontId);
            if (videoFont != null) {
                fontFileName = videoFont.getFontFileName();
                log.info("[v2.9.174] Thumbnail font: fontId={} -> fileName='{}'", fontId, fontFileName);
            } else {
                log.warn("[v2.9.174] Font not found for fontId={}, using default '{}'", fontId, DEFAULT_FONT_FILE);
            }
        }

        // 4. Overlay Text (v2.9.38: formatId 전달, v2.9.165: style 전달, v2.9.174: fontFileName 전달)
        String finalThumbnailPath = overlayTextOnImage(imagePath, promptInfo.thumbnailText, formatId, style, fontFileName);

        // 5. Upload/Save (v2.9.55: S3 key 반환)
        String s3Key = uploadThumbnail(chatId, finalThumbnailPath);

        // 6. Update video record with S3 key (v2.9.59: 짧은 트랜잭션으로 DB만 업데이트)
        // - 이미지 생성/S3 업로드는 트랜잭션 밖에서 실행 (15초)
        // - DB 업데이트만 별도 트랜잭션 (0.1초) → Lock 시간 최소화
        updateThumbnailUrlInTransaction(video.getVideoId(), s3Key);

        // 7. Generate presigned URL for frontend (v2.9.55)
        String presignedUrl = storageService.generatePresignedUrl(s3Key);
        log.info("[v2.9.55] Generated presigned URL for frontend: {}", presignedUrl.substring(0, Math.min(80, presignedUrl.length())) + "...");

        // 8. Save thumbnail result as chat message (v2.9.55: presigned URL in metadata for frontend)
        try {
            String metadata = objectMapper.writeValueAsString(Map.of(
                "thumbnailUrl", presignedUrl,  // v2.9.55: presigned URL (Frontend display용)
                "youtubeTitle", promptInfo.youtubeTitle,
                "youtubeDescription", promptInfo.youtubeDescription,
                "catchphrase", promptInfo.thumbnailText
            ));

            ConversationMessage message = ConversationMessage.builder()
                .conversationId(chatId)
                .role("assistant")
                .content("유튜브 썸네일이 생성되었습니다!")
                .messageType("THUMBNAIL_RESULT")
                .metadata(metadata)
                .build();

            messageMapper.insert(message);
            log.info("[Thumbnail] Saved thumbnail result message for chat: {}", chatId);
        } catch (Exception e) {
            log.error("[Thumbnail] Failed to save message", e);
            // 메시지 저장 실패해도 썸네일은 반환
        }

        return ContentDto.ThumbnailResponse.builder()
                .chatId(chatId)
                .thumbnailUrl(presignedUrl)  // v2.9.55: presigned URL (Frontend display용)
                .title(video.getTitle())
                .catchphrase(promptInfo.thumbnailText)
                .youtubeTitle(promptInfo.youtubeTitle)
                .youtubeDescription(promptInfo.youtubeDescription)
                .build();
    }

    /**
     * v2.9.165: thumbnailId로 스타일 조회. null이면 기본 스타일 사용.
     */
    private VideoThumbnail resolveThumbnailStyle(Long thumbnailId) {
        if (thumbnailId != null) {
            VideoThumbnail style = videoThumbnailMapper.findById(thumbnailId);
            if (style != null) {
                return style;
            }
            log.warn("[v2.9.165] Thumbnail style not found for id={}, falling back to default", thumbnailId);
        }
        VideoThumbnail defaultStyle = videoThumbnailMapper.findDefault();
        if (defaultStyle != null) {
            return defaultStyle;
        }
        // DB에 스타일이 하나도 없으면 코드 기본값 (기존 CLASSIC 스타일)
        log.warn("[v2.9.165] No default thumbnail style in DB, using built-in CLASSIC");
        return VideoThumbnail.builder()
                .thumbnailId(0L)
                .styleCode("CLASSIC")
                .styleName("클래식")
                .borderEnabled(false)
                .gradientEnabled(false)
                .textLine1Color("#FFE600")
                .textLine2Color("#FFE600")
                .outlineColor("#000000")
                .outlineThickness(10)
                .shadowEnabled(true)
                .shadowColor("#000000")
                .shadowOpacity(150)
                .shadowOffsetX(5)
                .shadowOffsetY(5)
                .build();
    }

    /**
     * v2.9.59: DB만 업데이트하는 짧은 트랜잭션 메서드
     * - 이미지 생성/S3 업로드는 트랜잭션 밖에서 실행 (15초)
     * - DB 업데이트만 별도 트랜잭션 (< 0.1초) → Lock 시간 최소화
     * - Lock Wait Timeout 문제 완전 해결
     */
    @Transactional
    private void updateThumbnailUrlInTransaction(Long videoId, String s3Key) {
        try {
            // v2.9.172: 원자적 업데이트 - thumbnail_url이 NULL일 때만 저장 (레이스 컨디션 방지)
            int rowsAffected = videoMapper.updateThumbnailUrlIfNull(videoId, s3Key);
            if (rowsAffected == 0) {
                log.warn("[v2.9.172] Thumbnail already exists for videoId={}, skipping update (race condition prevented)", videoId);
                return;
            }
            log.info("[v2.9.172] ✅ Updated video.thumbnailUrl for videoId={}: {}", videoId, s3Key);
        } catch (Exception e) {
            log.error("[v2.9.59] ❌ Failed to update video.thumbnailUrl", e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                "썸네일 URL 저장 실패. 최종 영상에 포함되지 않을 수 있습니다.");
        }
    }

    /**
     * v2.9.90: ScenarioContext 빌드 - 참조 이미지 멀티모달 지원 추가
     * Gemini 3 Pro Image의 최대 14개 참조 이미지 기능 활용
     */
    private ScenarioContext buildScenarioContext(Video video, Scenario scenario, List<Scene> scenes) {
        StringBuilder storyOutline = new StringBuilder();
        int slideCount = 0;
        for (Scene scene : scenes) {
            if ("SLIDE".equals(scene.getSceneType())) {
                slideCount++;
                storyOutline.append("[SCENE ").append(scene.getSceneOrder()).append("] ")
                        .append(scene.getNarration()).append("\n\n");
            } else if ("OPENING".equals(scene.getSceneType())) {
                storyOutline.append("[OPENING] ").append(scene.getNarration()).append("\n\n");
            }
        }

        // v2.9.90: 참조 이미지 로드 (Gemini 3 Pro Image 멀티모달 지원)
        List<String> referenceImagesBase64 = new ArrayList<>();
        List<String> referenceImagesMimeTypes = new ArrayList<>();
        String referenceImageAnalysis = null;

        if (video.getConversationId() != null) {
            Conversation conversation = conversationMapper.findById(video.getConversationId()).orElse(null);
            if (conversation != null && conversation.getReferenceImageUrl() != null && !conversation.getReferenceImageUrl().isEmpty()) {
                // 쉼표로 구분된 다중 이미지 URL 처리
                String fullReferenceUrl = conversation.getReferenceImageUrl();
                String[] imageUrls = fullReferenceUrl.split(",");

                log.info("[Thumbnail] v2.9.90: Loading {} reference images for multimodal - videoId: {}",
                        imageUrls.length, video.getVideoId());

                for (String s3Key : imageUrls) {
                    s3Key = s3Key.trim();
                    if (s3Key.isEmpty()) continue;

                    try {
                        byte[] imageBytes = referenceImageService.downloadImage(s3Key);
                        if (imageBytes != null && imageBytes.length > 0) {
                            String base64 = referenceImageService.encodeToBase64(imageBytes);
                            referenceImagesBase64.add(base64);

                            // MIME 타입 추출 (확장자로 추정)
                            String mimeType;
                            String lowerKey = s3Key.toLowerCase();
                            if (lowerKey.endsWith(".png")) {
                                mimeType = "image/png";
                            } else if (lowerKey.endsWith(".webp")) {
                                mimeType = "image/webp";
                            } else if (lowerKey.endsWith(".gif")) {
                                mimeType = "image/gif";
                            } else {
                                mimeType = "image/jpeg";  // 기본값
                            }
                            referenceImagesMimeTypes.add(mimeType);

                            log.info("[Thumbnail] v2.9.90: Loaded reference image - s3Key: {}, mimeType: {}, size: {} bytes",
                                    s3Key, mimeType, imageBytes.length);
                        }
                    } catch (Exception e) {
                        log.warn("[Thumbnail] v2.9.90: Failed to load reference image {}: {}", s3Key, e.getMessage());
                    }
                }

                referenceImageAnalysis = conversation.getReferenceImageAnalysis();
                log.info("[Thumbnail] v2.9.90: Total {} reference images loaded for thumbnail, hasAnalysis: {}",
                        referenceImagesBase64.size(), referenceImageAnalysis != null);
            }
        }

        return ScenarioContext.builder()
                .title(video.getTitle())
                .hook(video.getDescription())
                .characterBlock(scenario.getCharacterBlock())
                .totalSlides(slideCount)
                .storyOutline(storyOutline.toString())
                // v2.9.90: 참조 이미지 멀티모달 지원
                .referenceImagesBase64(referenceImagesBase64.isEmpty() ? null : referenceImagesBase64)
                .referenceImagesMimeTypes(referenceImagesMimeTypes.isEmpty() ? null : referenceImagesMimeTypes)
                .referenceImageAnalysis(referenceImageAnalysis)
                .build();
    }

    private ThumbnailPromptInfo generateThumbnailPrompt(Long userNo, Video video, Scenario scenario, List<Scene> scenes) {
        // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 지원
        ApiKeyService.setCurrentUserNo(userNo);
        String apiKey;
        try {
            apiKey = apiKeyService.getServiceApiKey();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
        String apiUrl = String.format(GEMINI_API_URL, textModel);

        // 1. Build detailed story context
        StringBuilder storyOutline = new StringBuilder();
        String highlightNarration = "";

        for (Scene scene : scenes) {
            String narration = scene.getNarration();
            if (narration != null && !narration.isEmpty()) {
                storyOutline.append("[SCENE ").append(scene.getSceneOrder()).append("] ")
                        .append(narration).append("\n");

                if (highlightNarration.isEmpty() && (narration.contains("!") || narration.contains("...") || narration.length() > 50)) {
                     highlightNarration = narration;
                }
            }
        }

        if (highlightNarration.isEmpty() && !scenes.isEmpty()) {
            highlightNarration = scenes.get(scenes.size() - 1).getNarration();
        }

        // 2. Get genre-specific thumbnail prompt from DB (v2.9.27)
        Long creatorId = video.getCreatorId();
        String systemPrompt = genreConfigService.getThumbnailPrompt(creatorId);

        String characterInfo = (scenario.getCharacterBlock() != null && !scenario.getCharacterBlock().isBlank())
                ? "\n\n=== CHARACTER IDENTITY BLOCK ===\n" + scenario.getCharacterBlock()
                : "";

        // v2.9.117: 하드코딩 프롬프트 완전 제거 - 모든 스타일/지시는 DB THUMBNAIL 프롬프트에서
        String userPrompt = String.format("""
                Title: %s
                Description: %s
                Genre ID: %d
                %s

                === FULL STORY OUTLINE ===
                %s

                === HIGHLIGHT SCENE NARRATION (Visual Focus) ===
                %s
                """,
                video.getTitle(),
                video.getDescription(),
                creatorId,
                characterInfo,
                storyOutline.toString(),
                highlightNarration);

        Map<String, Object> requestBody;
        {
            requestBody = Map.of(
                "contents", List.of(
                    Map.of("role", "user", "parts", List.of(
                        Map.of("text", systemPrompt + "\n\n" + userPrompt)
                    ))
                ),
                "generationConfig", Map.of(
                    "responseMimeType", "application/json"
                )
            );
        }

        try {
            String response = webClientBuilder.build().post()
                    .uri(apiUrl)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String jsonText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            // v2.9.181: JSON 마크다운 정리 로직 강화
            // 문제: substring(7) 후 개행문자(\n)가 남아서 JSON 파싱 실패 → youtubeTitle/youtubeDescription 빈값
            // 해결: trim()을 앞뒤로 추가하여 개행문자 완전 제거
            log.debug("[v2.9.181] AI raw response before cleanup (first 200 chars): {}",
                    jsonText.substring(0, Math.min(200, jsonText.length())));

            jsonText = jsonText.trim();
            if (jsonText.startsWith("```json")) {
                jsonText = jsonText.substring(7);
            }
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(3);
            }
            if (jsonText.endsWith("```")) {
                jsonText = jsonText.substring(0, jsonText.length() - 3);
            }
            jsonText = jsonText.trim();  // 개행문자 완전 제거

            log.debug("[v2.9.181] JSON after cleanup (first 200 chars): {}",
                    jsonText.substring(0, Math.min(200, jsonText.length())));

            JsonNode result = objectMapper.readTree(jsonText);

            // v2.9.99: 시나리오 기반 폴백 값 (AI가 필드를 생성하지 않은 경우)
            // 제목 전체 사용 - 반응형 폰트가 길이에 맞춰 자동 조절하여 좌우 90% 채움
            String thumbnailText = result.path("thumbnailText").asText("");
            if (thumbnailText.isBlank() || thumbnailText.length() < 5) {
                thumbnailText = video.getTitle();
                log.warn("[v2.9.99] thumbnailText empty, using full title ({}chars): {}", thumbnailText.length(), thumbnailText);
            }

            String youtubeTitle = result.path("youtubeTitle").asText("");
            if (youtubeTitle.isBlank()) {
                youtubeTitle = video.getTitle();
                log.warn("[v2.9.64] youtubeTitle empty, using video title");
            }

            String youtubeDesc = result.path("youtubeDescription").asText("");
            if (youtubeDesc.isBlank()) {
                youtubeDesc = video.getDescription();
                log.warn("[v2.9.64] youtubeDescription empty, using video description");
            }

            // v2.9.117: 하드코딩 프롬프트 완전 제거 - DB의 thumbnail_style_prompt 사용
            // imagePrompt = 캐릭터 썸네일 스타일 + 시나리오 제목/설명
            String imagePrompt = buildThumbnailImagePrompt(creatorId, video, highlightNarration);
            log.info("[v2.9.117] Thumbnail imagePrompt from DB: {}", imagePrompt.substring(0, Math.min(100, imagePrompt.length())) + "...");

            return new ThumbnailPromptInfo(
                    thumbnailText,
                    imagePrompt,
                    youtubeTitle,
                    youtubeDesc
            );

        } catch (Exception e) {
            log.error("Failed to generate thumbnail prompt", e);
            // v2.9.117: 하드코딩 프롬프트 완전 제거 - DB의 thumbnail_style_prompt 사용
            String fallbackText = video.getTitle();
            String fallbackImagePrompt = buildThumbnailImagePrompt(creatorId, video, highlightNarration);
            log.warn("[v2.9.117] Using fallback with DB thumbnail style (title {}chars): {}", fallbackText.length(), fallbackText);
            return new ThumbnailPromptInfo(
                fallbackText,
                fallbackImagePrompt,
                video.getTitle(),
                video.getDescription()
            );
        }
    }

    /**
     * v2.9.117: 하드코딩 프롬프트 완전 제거 - DB의 thumbnail_style_prompt 사용
     * 캐릭터 썸네일 스타일 프롬프트 + 시나리오 컨텍스트로 imagePrompt 생성
     */
    private String buildThumbnailImagePrompt(Long creatorId, Video video, String highlightNarration) {
        // 1. DB에서 캐릭터의 thumbnail_style_prompt 가져오기
        String thumbnailStyle = genreConfigService.getFixedCharacterThumbnailStyle(creatorId);

        // 2. 시나리오 컨텍스트와 결합
        StringBuilder prompt = new StringBuilder();

        if (thumbnailStyle != null && !thumbnailStyle.isBlank()) {
            prompt.append(thumbnailStyle).append("\n\n");
        }

        prompt.append("【SCENE CONTEXT】\n");
        prompt.append("Title: ").append(video.getTitle()).append("\n");

        if (highlightNarration != null && !highlightNarration.isBlank()) {
            prompt.append("Key Scene: ").append(highlightNarration).append("\n");
        }

        return prompt.toString();
    }

    private String generateThumbnailImage(Long userNo, ScenarioContext context, Long creatorId, String prompt) {
        VideoDto.SlideScene dummySlide = VideoDto.SlideScene.builder()
                .imagePrompt(prompt)
                .narration("Thumbnail Background")
                .build();
        
        List<String> images = imageGeneratorService.generateImages(userNo, Collections.singletonList(dummySlide), QualityTier.PREMIUM, context, creatorId);
        if (images.isEmpty() || images.get(0).startsWith("ERROR")) {
            throw new ApiException(ErrorCode.IMAGE_GENERATION_FAILED, "Failed to generate thumbnail image");
        }
        return images.get(0);
    }

    /**
     * v2.9.174: 멀티폰트 지원 - fontFileName으로 폰트 로드 (캐시 적용)
     */
    private Font loadFont(float size, String fontFileName) {
        if (fontFileName == null || fontFileName.isBlank()) {
            fontFileName = DEFAULT_FONT_FILE;
        }

        Font baseFont = fontCache.get(fontFileName);
        if (baseFont != null) {
            return baseFont.deriveFont(size);
        }

        try {
            String fontPath = "fonts/" + fontFileName;
            var inputStream = getClass().getClassLoader().getResourceAsStream(fontPath);
            if (inputStream == null) {
                log.warn("[v2.9.174] Font file not found at {}, falling back to SansSerif", fontPath);
                return new Font("SansSerif", Font.BOLD, (int) size);
            }
            Font newFont = Font.createFont(Font.TRUETYPE_FONT, inputStream);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(newFont);
            fontCache.put(fontFileName, newFont);
            log.info("[v2.9.174] Font loaded and cached: {}", fontFileName);

            return newFont.deriveFont(size);
        } catch (FontFormatException | IOException e) {
            log.error("[v2.9.174] Failed to load font: {}", fontFileName, e);
            return new Font("SansSerif", Font.BOLD, (int) size);
        }
    }

    /** v2.9.174 하위호환: fontFileName 없이 호출 시 기본 폰트 사용 */
    private Font loadFont(float size) {
        return loadFont(size, DEFAULT_FONT_FILE);
    }

    private String overlayTextOnImage(String imagePath, String text, Long formatId, VideoThumbnail style, String fontFileName) {
        try {
            BufferedImage image = ImageIO.read(new File(imagePath));
            Graphics2D g2d = image.createGraphics();

            // 1. High Quality Rendering Hints
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // v2.9.165: 테두리 렌더링
            if (Boolean.TRUE.equals(style.getBorderEnabled())) {
                int bw = style.getBorderWidth() != null ? style.getBorderWidth() : 12;
                g2d.setColor(Color.decode(style.getBorderColor() != null ? style.getBorderColor() : "#8B5CF6"));
                g2d.setStroke(new BasicStroke(bw));
                g2d.drawRect(bw / 2, bw / 2, image.getWidth() - bw, image.getHeight() - bw);
                log.info("[v2.9.165] Border drawn: color={}, width={}", style.getBorderColor(), bw);
            }

            // v2.9.165: 하단 그라데이션 렌더링
            if (Boolean.TRUE.equals(style.getGradientEnabled())) {
                double heightRatio = style.getGradientHeightRatio() != null ? style.getGradientHeightRatio().doubleValue() : 0.40;
                double opacity = style.getGradientOpacity() != null ? style.getGradientOpacity().doubleValue() : 0.85;
                int gradientH = (int) (image.getHeight() * heightRatio);
                int gradStartY = image.getHeight() - gradientH;
                Color gradColor = Color.decode(style.getGradientColor() != null ? style.getGradientColor() : "#000000");
                GradientPaint gp = new GradientPaint(
                        0, gradStartY, new Color(gradColor.getRed(), gradColor.getGreen(), gradColor.getBlue(), 0),
                        0, image.getHeight(), new Color(gradColor.getRed(), gradColor.getGreen(), gradColor.getBlue(), (int) (255 * opacity))
                );
                g2d.setPaint(gp);
                g2d.fillRect(0, gradStartY, image.getWidth(), gradientH);
                log.info("[v2.9.165] Gradient drawn: color={}, heightRatio={}, opacity={}", style.getGradientColor(), heightRatio, opacity);
            }

            // 2. v2.9.99: 텍스트 2줄 자동 분리 (반응형 - 글자수 무관하게 좌우 90% 채움)
            // - " / "가 있으면 그걸로 분리
            // - 20자 이상이고 " / "가 없으면 자연스러운 구분점(: 또는 중간 공백)으로 분리
            String[] lines;
            if (text.contains(" / ")) {
                lines = text.split(" / ", 2);
            } else if (text.length() > 20) {
                // 20자 이상이면 자동 2줄 분리
                int splitPoint = -1;
                // 1순위: ":"로 분리
                int colonIdx = text.indexOf(':');
                if (colonIdx > 0 && colonIdx < text.length() - 1) {
                    splitPoint = colonIdx + 1;
                }
                // 2순위: 중간 지점 근처의 공백으로 분리
                if (splitPoint == -1) {
                    int midPoint = text.length() / 2;
                    int searchRange = Math.min(10, text.length() / 4);
                    for (int i = 0; i <= searchRange; i++) {
                        int checkRight = midPoint + i;
                        int checkLeft = midPoint - i;
                        if (checkRight < text.length() && text.charAt(checkRight) == ' ') {
                            splitPoint = checkRight + 1;
                            break;
                        }
                        if (checkLeft > 0 && text.charAt(checkLeft) == ' ') {
                            splitPoint = checkLeft + 1;
                            break;
                        }
                    }
                }
                if (splitPoint > 0 && splitPoint < text.length()) {
                    lines = new String[]{text.substring(0, splitPoint).trim(), text.substring(splitPoint).trim()};
                    log.info("[v2.9.99] Auto-split long text at position {}: ['{}', '{}']", splitPoint, lines[0], lines[1]);
                } else {
                    lines = new String[]{text};
                }
            } else {
                lines = new String[]{text};
            }

            // 3. Calculate responsive font size to FILL width (90% of image width)
            // v2.9.61: 비례 계산으로 변경 (줄이기만 하던 로직 → 90% 채우도록 키우기/줄이기)
            float targetWidth = image.getWidth() * 0.90f; // 좌우 5% 여백

            // 가장 긴 줄을 찾아서 그 줄 기준으로 폰트 크기 조정
            String longestLine = lines[0];
            for (String line : lines) {
                if (line.trim().length() > longestLine.length()) {
                    longestLine = line.trim();
                }
            }

            // 기준 폰트 크기로 텍스트 너비 측정
            float baseFontSize = 100f;
            Font baseFont = loadFont(baseFontSize, fontFileName);
            g2d.setFont(baseFont);
            FontMetrics baseMetrics = g2d.getFontMetrics();
            float baseTextWidth = baseMetrics.stringWidth(longestLine);

            // 비례 계산: 텍스트가 targetWidth를 정확히 채우도록 폰트 크기 계산
            float fontSize;
            if (baseTextWidth > 0) {
                fontSize = baseFontSize * (targetWidth / baseTextWidth);
            } else {
                fontSize = 100f; // 텍스트가 비어있으면 기본값
            }

            // v2.9.63: 16:9 작은 이미지에서도 텍스트가 90%를 채우도록 수정
            // - 최소값: 이미지 너비의 5% (768px → 38f, 1920px → 96f)
            // - 최대값: 500f (매우 큰 이미지용)
            // - 기존 60f 고정 최소값은 작은 이미지에서 90%를 못 채우는 문제가 있었음
            float minFontSize = image.getWidth() * 0.05f;  // 이미지 너비에 비례
            float maxFontSize = 500f;
            fontSize = Math.max(minFontSize, Math.min(maxFontSize, fontSize));

            Font font = loadFont(fontSize, fontFileName);
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics();

            log.info("[Thumbnail] v2.9.63 Responsive font: longestLine='{}', baseWidth={}, targetWidth={}, minFont={}, fontSize={}",
                    longestLine, (int)baseTextWidth, (int)targetWidth, (int)minFontSize, (int)fontSize);

            // 최종 폰트 크기로 렌더링 준비
            int lineHeight = metrics.getHeight();
            int totalHeight = lineHeight * lines.length;

            // v2.9.38: 포맷별 텍스트 위치 조정
            // - 일반 영상(16:9, formatId=1): 하단 100px 위 (기존)
            // - 쇼츠(9:16, formatId=2,3,4): 하단 450px 위 (유튜브 UI 회피, 화면 중앙 근처)
            int bottomPadding;
            boolean isShorts = formatId != null && formatId >= 2L; // 2=YOUTUBE_SHORTS, 3=INSTAGRAM_REELS, 4=TIKTOK
            if (isShorts) {
                bottomPadding = 450; // 쇼츠: 하단에서 450px 위 (1920px 기준 약 23% 위)
                log.info("[Thumbnail] Shorts format detected (formatId={}), text position: {}px from bottom", formatId, bottomPadding);
            } else {
                bottomPadding = 100; // 일반 영상: 하단에서 100px 위 (기존)
                log.info("[Thumbnail] Standard format (formatId={}), text position: {}px from bottom", formatId, bottomPadding);
            }
            int startY = image.getHeight() - bottomPadding - totalHeight;

            log.info("[Thumbnail] Responsive font size: {}f (longest line: '{}', width: {}px / target: {}px)",
                    fontSize, longestLine, metrics.stringWidth(longestLine), (int)targetWidth);

            // 4. Draw each line (LEFT ALIGNED)
            // v2.9.165: 스타일 기반 텍스트 렌더링
            String line1Color = style.getTextLine1Color() != null ? style.getTextLine1Color() : "#FFE600";
            String line2Color = style.getTextLine2Color() != null ? style.getTextLine2Color() : "#FFE600";
            String outlineColorHex = style.getOutlineColor() != null ? style.getOutlineColor() : "#000000";
            int thickness = style.getOutlineThickness() != null ? style.getOutlineThickness() : 10;
            boolean shadowOn = !Boolean.FALSE.equals(style.getShadowEnabled());
            String shadowColorHex = style.getShadowColor() != null ? style.getShadowColor() : "#000000";
            int shadowAlpha = style.getShadowOpacity() != null ? style.getShadowOpacity() : 150;
            int shadowOx = style.getShadowOffsetX() != null ? style.getShadowOffsetX() : 5;
            int shadowOy = style.getShadowOffsetY() != null ? style.getShadowOffsetY() : 5;

            int leftMargin = (int)(image.getWidth() * 0.05f); // 좌측 5% 여백
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                int x = leftMargin; // 좌측 정렬
                int y = startY + (i + 1) * lineHeight;

                // 4-1. Draw Drop Shadow
                if (shadowOn) {
                    Color sc = Color.decode(shadowColorHex);
                    g2d.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), shadowAlpha));
                    g2d.drawString(line, x + shadowOx, y + shadowOy);
                }

                // 4-2. Draw Thick Outline (Multiple offsets for smooth border)
                g2d.setColor(Color.decode(outlineColorHex));
                for (int dx = -thickness; dx <= thickness; dx++) {
                    for (int dy = -thickness; dy <= thickness; dy++) {
                        if (dx*dx + dy*dy <= thickness*thickness) {
                            g2d.drawString(line, x + dx, y + dy);
                        }
                    }
                }

                // 4-3. Draw Main Text (v2.9.165: 줄별 색상 적용)
                Color textColor = Color.decode(i == 0 ? line1Color : line2Color);
                g2d.setColor(textColor);
                g2d.drawString(line, x, y);
            }

            g2d.dispose();

            String outputFilename = "thumb_" + UUID.randomUUID() + ".png";
            Path outputPath = Paths.get(THUMBNAIL_DIR, outputFilename);
            Files.createDirectories(outputPath.getParent());
            ImageIO.write(image, "png", outputPath.toFile());
            
            log.info("[Thumbnail] Professional text overlay complete: {}", outputPath);
            return outputPath.toString();

        } catch (IOException e) {
            log.error("Failed to overlay professional text", e);
            return imagePath;
        }
    }

    private String uploadThumbnail(Long chatId, String localPath) {
        // v2.9.55: S3 key 반환 (presigned URL 만료 문제 해결 - 썸네일 씬 생성 시 사용)
        if (!storageService.isEnabled()) {
            log.error("[v2.9.48] S3 storage is disabled. Cannot upload thumbnail.");
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                "S3 스토리지가 비활성화되어 썸네일을 업로드할 수 없습니다.");
        }

        try {
            // v2.9.89: 채팅별 폴더 구조로 변경
            String s3Key = "content/" + chatId + "/thumbnails/" + System.currentTimeMillis() + ".png";

            // v2.9.53: 스트리밍 업로드 (메모리 절약)
            Path localFilePath = Paths.get(localPath);
            try (InputStream inputStream = Files.newInputStream(localFilePath)) {
                long fileSize = Files.size(localFilePath);
                storageService.upload(s3Key, inputStream, "image/png", fileSize);
                log.info("[v2.9.55] Uploaded to S3 (streaming): {} ({} bytes)", s3Key, fileSize);
            }

            // v2.9.55: S3 key 반환 (presigned URL 아님!)
            log.info("[v2.9.55] Returning S3 key for thumbnail: {}", s3Key);
            return s3Key;
        } catch (IOException e) {
            log.error("[v2.9.55] Failed to upload thumbnail to S3", e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                "썸네일 S3 업로드 실패: " + e.getMessage());
        }
    }

    private record ThumbnailPromptInfo(String thumbnailText, String imagePrompt, String youtubeTitle, String youtubeDescription) {}

    /**
     * v2.9.84: 썸네일 조회 (비동기 생성 결과 확인용)
     * - 썸네일이 아직 생성 중이면 status=GENERATING
     * - 썸네일이 완료되면 status=COMPLETED + presignedUrl
     */
    public ContentDto.ThumbnailResponse getThumbnail(Long userNo, Long chatId) {
        log.info("[v2.9.84] Get thumbnail for chatId: {}", chatId);

        List<Video> videos = videoMapper.findByConversationId(chatId);
        Video video = videos.stream().findFirst().orElse(null);

        if (video == null) {
            return ContentDto.ThumbnailResponse.builder()
                    .chatId(chatId)
                    .status("NOT_FOUND")
                    .build();
        }

        if (!video.getUserNo().equals(userNo)) {
            throw new ApiException(ErrorCode.CONVERSATION_UNAUTHORIZED);
        }

        String thumbnailUrl = video.getThumbnailUrl();
        if (thumbnailUrl == null || thumbnailUrl.isEmpty()) {
            return ContentDto.ThumbnailResponse.builder()
                    .chatId(chatId)
                    .status("GENERATING")
                    .build();
        }

        // S3 key를 presigned URL로 변환
        String presignedUrl = storageService.generatePresignedUrl(thumbnailUrl);

        // 채팅 메시지에서 유튜브 제목/설명 조회
        String youtubeTitle = video.getTitle();
        String youtubeDescription = video.getDescription();
        String catchphrase = "";

        try {
            List<ConversationMessage> messages = messageMapper.findByConversationId(chatId);
            for (ConversationMessage msg : messages) {
                if ("THUMBNAIL_RESULT".equals(msg.getMessageType()) && msg.getMetadata() != null) {
                    JsonNode metadata = objectMapper.readTree(msg.getMetadata());
                    youtubeTitle = metadata.path("youtubeTitle").asText(youtubeTitle);
                    youtubeDescription = metadata.path("youtubeDescription").asText(youtubeDescription);
                    catchphrase = metadata.path("catchphrase").asText("");
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[v2.9.84] Failed to parse thumbnail metadata", e);
        }

        return ContentDto.ThumbnailResponse.builder()
                .chatId(chatId)
                .thumbnailUrl(presignedUrl)
                .title(video.getTitle())
                .catchphrase(catchphrase)
                .youtubeTitle(youtubeTitle)
                .youtubeDescription(youtubeDescription)
                .status("COMPLETED")
                .build();
    }
}
