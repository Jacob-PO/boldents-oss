package com.aivideo.api.service.reference;

import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.aivideo.api.service.storage.StorageService;
import com.aivideo.api.service.CreatorConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * v2.9.84: 참조 이미지 서비스 구현체
 * v2.9.149: 티어별 시나리오 모델 사용 (ai_model 테이블 기반)
 * v2.9.150: 분석 프롬프트 DB 기반 관리 (creator_prompts.reference_image_analysis)
 *
 * 담당 기능:
 * 1. 이미지 검증 (타입, 크기)
 * 2. S3 업로드 (스트리밍 방식)
 * 3. Gemini 멀티모달 분석 (캐릭터/스타일 추출)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceImageServiceImpl implements ReferenceImageService {

    private final StorageService storageService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CreatorConfigService creatorConfigService;  // v2.9.149: 티어별 모델 조회

    // 허용 MIME 타입
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    // 최대 파일 크기 (10MB)
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // v2.9.149: Gemini API URL 템플릿 (티어별 모델 동적 적용)
    private static final String GEMINI_API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    @Override
    public void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "이미지 파일이 비어있습니다.");
        }

        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않는 이미지 형식입니다. (JPEG, PNG, WebP, GIF 지원)");
        }

        if (image.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "이미지 크기는 10MB를 초과할 수 없습니다.");
        }

        log.info("[ReferenceImage] Validation passed - type: {}, size: {} bytes",
                contentType, image.getSize());
    }

    @Override
    public String uploadImage(Long chatId, MultipartFile image) {
        validateImage(image);

        // 안전한 파일명 생성
        String originalFilename = image.getOriginalFilename();
        String safeFilename = sanitizeFilename(originalFilename);

        // v2.9.89: S3 key 생성: content/{chatId}/references/{timestamp}_{filename}
        String key = String.format("content/%d/references/%d_%s",
                chatId, System.currentTimeMillis(), safeFilename);

        try (InputStream inputStream = image.getInputStream()) {
            storageService.upload(key, inputStream, image.getContentType(), image.getSize());
            log.info("[ReferenceImage] v2.9.89 Uploaded to S3 - chatId: {}, key: {}", chatId, key);
            return key;
        } catch (IOException e) {
            log.error("[ReferenceImage] Upload failed - chatId: {}", chatId, e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "참조 이미지 업로드에 실패했습니다: " + e.getMessage());
        }
    }

    @Override
    public String analyzeImage(String apiKey, byte[] imageBytes, String mimeType, String userPrompt, Long creatorId) {
        log.info("[ReferenceImage] Starting analysis - size: {} bytes, mimeType: {}, creatorId: {}",
                imageBytes.length, mimeType, creatorId);

        String base64Image = encodeToBase64(imageBytes);

        // DB에서 분석 프롬프트 조회, 없으면 폴백 사용
        String analysisPrompt;
        String dbPrompt = creatorConfigService.buildReferenceImageAnalysisPrompt(creatorId, userPrompt);
        if (dbPrompt != null) {
            analysisPrompt = dbPrompt;
            log.info("[ReferenceImage] Using DB analysis prompt for creatorId: {}", creatorId);
        } else if (isReviewCreator(creatorId)) {
            analysisPrompt = buildProductAnalysisPromptFallback(userPrompt);
            log.info("[ReferenceImage] Using PRODUCT analysis prompt fallback for REVIEW creator");
        } else {
            analysisPrompt = buildCharacterAnalysisPrompt(userPrompt);
            log.info("[ReferenceImage] Using CHARACTER analysis prompt for genre: {}", creatorId);
        }

        // Gemini API 요청 구성 (멀티모달)
        Map<String, Object> requestBody = buildGeminiRequest(analysisPrompt, base64Image, mimeType);

        // v2.9.149: 티어별 시나리오 모델 조회
        String scenarioModel = creatorConfigService.getScenarioModel(creatorId);
        String apiUrl = String.format(GEMINI_API_URL_TEMPLATE, scenarioModel) + "?key=" + apiKey;
        log.info("[ReferenceImage] v2.9.149 Using scenario model: {} for creatorId: {}", scenarioModel, creatorId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String analysisResult = parseGeminiResponse(response.getBody());
                // v2.9.149: 프롬프트에서 브랜드명 요청을 제거했으므로 sanitize 불필요
                log.info("[ReferenceImage] v2.9.149 Analysis completed - result length: {} chars",
                        analysisResult.length());
                return analysisResult;
            }

            log.warn("[ReferenceImage] Analysis returned non-OK status: {}", response.getStatusCode());
            return "{}";

        } catch (Exception e) {
            log.error("[ReferenceImage] Analysis failed: {}", e.getMessage(), e);
            // 분석 실패 시 빈 JSON 반환 (콘텐츠 생성은 계속 진행)
            return "{}";
        }
    }

    @Override
    public String encodeToBase64(byte[] imageBytes) {
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    @Override
    public byte[] downloadImage(String s3Key) {
        if (s3Key == null || s3Key.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "S3 key가 비어있습니다.");
        }
        return storageService.download(s3Key);
    }

    @Override
    public String generatePresignedUrl(String s3Key) {
        if (s3Key == null || s3Key.isEmpty()) {
            return null;
        }
        return storageService.generatePresignedUrl(s3Key);
    }

    /**
     * 파일명 안전 처리 (경로 조작 방지)
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "image";
        }
        // 경로 구분자 제거, 특수문자 제거
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * v2.9.85: 캐릭터/스타일 분석 프롬프트 (ADULT, FINANCE 등 일반 장르용)
     */
    private String buildCharacterAnalysisPrompt(String userPrompt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this reference image for AI video content generation.\n\n");

        if (userPrompt != null && !userPrompt.isEmpty()) {
            prompt.append("User's content idea: ").append(userPrompt).append("\n\n");
        }

        prompt.append("""
                Extract and describe in English for AI image/video generation:

                1. CHARACTER: If a person is visible, describe in detail:
                   - Gender, estimated age, ethnicity
                   - Hair (color, style, length)
                   - Eyes (color, shape)
                   - Face shape, skin tone
                   - Body type, height impression
                   - Any distinctive features

                2. STYLE: Visual and artistic style:
                   - Photography style (cinematic, portrait, fashion, etc.)
                   - Lighting (natural, studio, dramatic, soft, etc.)
                   - Color grading and tones
                   - Overall aesthetic

                3. OUTFIT: If clothing is visible:
                   - Clothing type and style
                   - Colors and patterns
                   - Accessories

                4. SETTING: Background and environment:
                   - Location type (indoor/outdoor, specific setting)
                   - Atmosphere and mood
                   - Key visual elements

                5. COLOR_PALETTE: Main colors in the image (list 3-5 colors)

                6. MOOD: Overall mood and atmosphere (1-2 words)

                Output as valid JSON only (no markdown, no explanation):
                {
                  "characterDescription": "...",
                  "styleDescription": "...",
                  "outfitDescription": "...",
                  "settingDescription": "...",
                  "colorPalette": "...",
                  "moodAtmosphere": "..."
                }
                """);

        return prompt.toString();
    }

    /**
     * v2.9.150: 상품 분석 프롬프트 폴백 (DB에 프롬프트가 없을 때 사용)
     *
     * 🚨 CRITICAL: 이 프롬프트는 상품 리뷰 콘텐츠 생성의 핵심입니다.
     * 참조 이미지에서 상품 정보를 정확히 추출하여 동일한 상품이 콘텐츠에 등장하도록 합니다.
     *
     * v2.9.149: 더 디테일한 시각적 분석 추가 (영상 재현을 위한 상세 정보)
     * v2.9.150: DB 프롬프트 우선, 이 메서드는 폴백으로만 사용
     */
    private String buildProductAnalysisPromptFallback(String userPrompt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                🎬 ULTRA-DETAILED PRODUCT ANALYSIS FOR VIDEO GENERATION

                You are analyzing a PRODUCT IMAGE for AI video generation.
                Your analysis will be used DIRECTLY in video prompts, so be EXTREMELY DETAILED.
                The video must show the EXACT SAME product with identical visual characteristics.

                ⚠️ CRITICAL: NO BRAND NAMES, NO MODEL NUMBERS - use only visual descriptions!

                """);

        if (userPrompt != null && !userPrompt.isEmpty()) {
            prompt.append("User's product description: ").append(userPrompt).append("\n\n");
        }

        prompt.append("""
                EXTRACT EVERY VISUAL DETAIL:

                1. PRODUCT_TYPE (NO brands/models):
                   - Generic category: "flagship smartphone", "wireless earbuds", "skincare serum"
                   - Key function: "with stylus pen support", "noise-canceling", "anti-aging"

                2. EXACT_SHAPE:
                   - 3D form: rectangular slab, cylindrical tube, spherical jar, teardrop bottle
                   - Proportions: height-to-width ratio, thickness
                   - Edge style: rounded corners, sharp edges, beveled, curved

                3. DIMENSIONS_AND_SCALE:
                   - Relative size: palm-sized, finger-tip sized, hand-length
                   - Thickness: ultra-thin, slim, chunky, bulky
                   - Weight impression: lightweight, substantial, heavy-looking

                4. MATERIAL_AND_TEXTURE:
                   - Surface material: brushed aluminum, frosted glass, smooth plastic, leather
                   - Texture feel: silky matte, mirror glossy, soft-touch, textured grip
                   - Transparency: opaque, translucent, transparent, tinted

                5. COLOR_PALETTE (be VERY specific):
                   - Primary: exact shade (not "black" but "jet black with subtle blue undertone")
                   - Secondary: accent colors and where they appear
                   - Finish: metallic shimmer, pearlescent, holographic, matte, glossy
                   - Gradients: if colors blend or transition

                6. DISTINCTIVE_FEATURES:
                   - Buttons/controls: location, shape, color
                   - Ports/openings: position, size
                   - Display/screen: size relative to body, bezel thickness
                   - Decorative elements: lines, patterns, embossing

                7. LIGHTING_IN_IMAGE:
                   - Light direction: from top-left, front, diffused
                   - Highlights: where light reflects brightest
                   - Shadows: shadow direction and softness
                   - Overall mood: bright and clean, dramatic, soft and warm

                8. CAMERA_ANGLE:
                   - Perspective: straight-on, 45-degree angle, top-down, low angle
                   - Distance: close-up macro, medium shot, full product view
                   - Focus: what's sharp vs blurred

                9. BACKGROUND_AND_SETTING:
                   - Background color/type: white studio, marble surface, wooden desk
                   - Props if any: plants, accessories, hands
                   - Environment feel: minimalist, lifestyle, professional

                10. COMPOSITION:
                    - Product position: centered, rule-of-thirds, floating
                    - Negative space: how much empty space around product
                    - If hands visible: how they hold/interact with product

                Output as valid JSON only (no markdown):
                {
                  "productType": "Generic type with key function (NO brand names)",
                  "exactShape": "3D form, proportions, edge style in detail",
                  "dimensions": "Relative size, thickness, weight impression",
                  "material": "Surface material, texture feel, transparency level",
                  "colors": "Primary: [exact shade], Secondary: [accents], Finish: [type]",
                  "distinctiveFeatures": "Buttons, ports, display, decorative elements",
                  "lighting": "Light direction, highlights, shadows, mood",
                  "cameraAngle": "Perspective, distance, focus area",
                  "background": "Background type, props, environment feel",
                  "composition": "Product position, negative space, hand interaction if any",
                  "videoPromptSuggestion": "One-paragraph description combining all above for video generation"
                }

                🚨 RULES:
                - NO brand names (Samsung, Apple, Sony, etc.)
                - NO model numbers (S26, iPhone 16, etc.)
                - Be EXTREMELY specific about visual details
                - The "videoPromptSuggestion" should be a ready-to-use prompt for video generation
                """);

        return prompt.toString();
    }

    /**
     * Gemini API 멀티모달 요청 구성
     */
    private Map<String, Object> buildGeminiRequest(String prompt, String base64Image, String mimeType) {
        Map<String, Object> requestBody = new HashMap<>();

        // contents - 텍스트 + 이미지
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();

        // parts 배열: 텍스트 + 이미지
        List<Map<String, Object>> parts = new ArrayList<>();

        // 텍스트 파트
        parts.add(Map.of("text", prompt));

        // 이미지 파트 (inlineData)
        parts.add(Map.of("inlineData", Map.of(
                "mimeType", mimeType,
                "data", base64Image
        )));

        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // generationConfig
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.4);
        requestBody.put("generationConfig", generationConfig);

        return requestBody;
    }

    /**
     * Gemini 응답 파싱
     */
    private String parseGeminiResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");

            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.path("content");
                JsonNode parts = content.path("parts");

                if (parts.isArray() && !parts.isEmpty()) {
                    JsonNode textNode = parts.get(0).path("text");
                    if (!textNode.isMissingNode()) {
                        String text = textNode.asText();
                        // JSON 유효성 검증
                        objectMapper.readTree(text);
                        return text;
                    }
                }
            }

            log.warn("[ReferenceImage] Could not parse analysis result");
            return "{}";

        } catch (Exception e) {
            log.error("[ReferenceImage] Failed to parse Gemini response: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Check if the creator is a REVIEW-type creator by creatorCode.
     */
    private boolean isReviewCreator(Long creatorId) {
        if (creatorId == null) return false;
        try {
            return "REVIEW".equals(creatorConfigService.getCreator(creatorId).getCreatorCode());
        } catch (Exception e) {
            return false;
        }
    }
}
