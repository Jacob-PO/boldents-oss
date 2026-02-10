package com.aivideo.api.service;

import com.aivideo.api.dto.ChatDto;
import com.aivideo.api.entity.Conversation;
import com.aivideo.api.entity.ConversationMessage;
import com.aivideo.api.mapper.ConversationMapper;
import com.aivideo.api.mapper.ConversationMessageMapper;
import com.aivideo.api.mapper.UserMapper;
import com.aivideo.api.service.reference.ReferenceImageService;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final UserMapper userMapper;  // v2.9.150: 사용자-크리에이터 연결 조회
    private final ApiKeyService apiKeyService;
    private final CreatorConfigService genreConfigService;
    private final ReferenceImageService referenceImageService;  // v2.9.84: 참조 이미지 서비스
    // v2.9.11: Bean 주입으로 변경 (HttpClientConfig에서 관리)
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.tier.premium.scenario-model:gemini-3-flash-preview}")
    private String chatModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /**
     * 새 채팅 시작
     * v2.9.150: 계정에 연결된 크리에이터만 사용 (1:1 매핑 강제)
     */
    @Transactional
    public ChatDto.ChatResponse startChat(Long userNo, ChatDto.StartRequest request) {
        // v2.9.150: 계정에 연결된 크리에이터 강제 사용 (request.creatorId 무시)
        Long creatorId = userMapper.findCreatorIdByUserNo(userNo);
        if (creatorId == null) {
            log.error("[ChatService] User {} has no linked creator", userNo);
            throw new ApiException(ErrorCode.INVALID_REQUEST, "계정에 연결된 크리에이터가 없습니다. 관리자에게 문의하세요.");
        }
        log.info("[ChatService] Start chat - userNo: {}, linkedCreatorId: {}", userNo, creatorId);

        // v2.9.40: 프리뷰 생성 중 이후 단계의 콘텐츠가 있는지 확인
        Optional<Conversation> inProgressConversation = conversationMapper.findInProgressConversationByUserNo(userNo);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            String currentStep = inProgressConversation.get().getCurrentStep();
            log.warn("[ChatService] User {} has content generation in progress: chatId={}, step={}", userNo, inProgressChatId, currentStep);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "이전 채팅(#" + inProgressChatId + ")에서 영상 생성이 진행 중입니다. 완료 후 새 콘텐츠를 시작할 수 있습니다.");
        }

        // 대화 생성 (Builder 패턴 사용) - v2.9.134: creatorId 추가
        Conversation conversation = Conversation.builder()
                .userNo(userNo)
                .creatorId(creatorId)  // v2.9.134: 크리에이터 ID 저장
                .status("ACTIVE")
                .currentStep("CHATTING")
                .initialPrompt(request != null ? request.getPrompt() : null)
                .contentType("YOUTUBE_SCENARIO")
                .qualityTier("PREMIUM")
                .questionCount(0)
                .totalMessages(0)
                .build();

        conversationMapper.insert(conversation);
        Long chatId = conversation.getConversationId();
        log.info("[ChatService] Created chat: {}", chatId);

        // 사용자 초기 프롬프트 저장
        if (request != null && request.getPrompt() != null && !request.getPrompt().isEmpty()) {
            saveMessage(chatId, "user", request.getPrompt());
        }

        // 초기 AI 메시지 생성
        String welcomeMessage = generateWelcomeMessage(userNo, request != null ? request.getPrompt() : null);

        // AI 메시지 저장
        saveMessage(chatId, "assistant", welcomeMessage);

        return ChatDto.ChatResponse.builder()
                .chatId(chatId)
                .aiMessage(welcomeMessage)
                .stage(ChatDto.Stage.CHATTING)
                .canGenerateScenario(false)
                .build();
    }

    /**
     * v2.9.84: 참조 이미지와 함께 새 채팅 시작
     * v2.9.150: 계정에 연결된 크리에이터만 사용 (파라미터 creatorId 무시)
     */
    @Transactional
    public ChatDto.ChatResponse startChatWithImage(Long userNo, String prompt, MultipartFile image, Long ignoredCreatorId) {
        // v2.9.150: 계정에 연결된 크리에이터 강제 사용
        Long creatorId = userMapper.findCreatorIdByUserNo(userNo);
        if (creatorId == null) {
            log.error("[ChatService] User {} has no linked creator", userNo);
            throw new ApiException(ErrorCode.INVALID_REQUEST, "계정에 연결된 크리에이터가 없습니다. 관리자에게 문의하세요.");
        }
        log.info("[ChatService] Start chat with image - userNo: {}, hasImage: {}, linkedCreatorId: {}",
                userNo, image != null && !image.isEmpty(), creatorId);

        // v2.9.40: 진행 중인 콘텐츠 확인
        Optional<Conversation> inProgressConversation = conversationMapper.findInProgressConversationByUserNo(userNo);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            String currentStep = inProgressConversation.get().getCurrentStep();
            log.warn("[ChatService] User {} has content generation in progress: chatId={}, step={}", userNo, inProgressChatId, currentStep);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "이전 채팅(#" + inProgressChatId + ")에서 영상 생성이 진행 중입니다. 완료 후 새 콘텐츠를 시작할 수 있습니다.");
        }

        // v2.9.89: 대화 먼저 생성 (chatId 확보)
        Conversation conversation = Conversation.builder()
                .userNo(userNo)
                .creatorId(creatorId)  // v2.9.134: genreId → creatorId
                .status("ACTIVE")
                .currentStep("CHATTING")
                .initialPrompt(prompt)
                .contentType("YOUTUBE_SCENARIO")
                .qualityTier("PREMIUM")
                .questionCount(0)
                .totalMessages(0)
                .build();

        conversationMapper.insert(conversation);
        Long chatId = conversation.getConversationId();
        log.info("[ChatService] v2.9.89 Created chat first: chatId={}", chatId);

        String referenceImageUrl = null;
        String referenceImageAnalysis = null;

        // v2.9.89: 이미지 처리 (chatId 사용)
        if (image != null && !image.isEmpty()) {
            try {
                // 1. 이미지 검증 및 S3 업로드 (content/{chatId}/references/ 경로)
                referenceImageUrl = referenceImageService.uploadImage(chatId, image);
                log.info("[ChatService] v2.9.89 Reference image uploaded: {}", referenceImageUrl);

                // 2. Gemini 멀티모달로 이미지 분석 (v2.9.85: 장르별 분석 프롬프트)
                // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 지원
                ApiKeyService.setCurrentUserNo(userNo);
                try {
                    String apiKey = apiKeyService.getServiceApiKey();
                    if (apiKey != null && !apiKey.isEmpty()) {
                        byte[] imageBytes = image.getBytes();
                        String mimeType = image.getContentType();
                        referenceImageAnalysis = referenceImageService.analyzeImage(apiKey, imageBytes, mimeType, prompt, creatorId);
                        log.info("[ChatService] Reference image analyzed for creator {}, result length: {}",
                                creatorId, referenceImageAnalysis != null ? referenceImageAnalysis.length() : 0);
                    } else {
                        log.warn("[ChatService] No API key, skipping image analysis");
                    }
                } finally {
                    ApiKeyService.clearCurrentUserNo();
                }

                // v2.9.89: 참조 이미지 정보 업데이트
                conversationMapper.updateReferenceImage(chatId, referenceImageUrl, referenceImageAnalysis);
            } catch (IOException e) {
                log.error("[ChatService] Failed to process reference image: {}", e.getMessage());
                throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "참조 이미지 처리 실패: " + e.getMessage());
            }
        }

        log.info("[ChatService] Created chat with image: chatId={}, hasReferenceImage={}", chatId, referenceImageUrl != null);

        // 사용자 초기 프롬프트 저장
        if (prompt != null && !prompt.isEmpty()) {
            saveMessage(chatId, "user", prompt);
        }

        // 참조 이미지 정보가 있으면 시스템 메시지로 기록 (v2.9.134: 크리에이터별 메시지)
        if (referenceImageUrl != null) {
            String imageInfoMessage;
            boolean isReviewCreator = isReviewCreatorCode(creatorId);

            if (isReviewCreator) {
                imageInfoMessage = "📷 상품 이미지가 업로드되었습니다. 이 상품을 리뷰하는 콘텐츠를 생성합니다.";
                if (referenceImageAnalysis != null && !referenceImageAnalysis.equals("{}")) {
                    imageInfoMessage += "\n\n📋 상품 분석 완료: 상품명, 카테고리, 특징, 외관, 색상 정보가 추출되었습니다.";
                }
            } else {
                imageInfoMessage = "📷 참조 이미지가 업로드되었습니다. 이 이미지의 스타일과 캐릭터를 기반으로 콘텐츠를 생성합니다.";
                if (referenceImageAnalysis != null && !referenceImageAnalysis.equals("{}")) {
                    imageInfoMessage += "\n\n📋 이미지 분석 완료: 캐릭터, 스타일, 색감 정보가 추출되었습니다.";
                }
            }
            saveMessage(chatId, "assistant", imageInfoMessage);
        }

        // 초기 AI 메시지 생성
        String welcomeMessage = generateWelcomeMessageWithImage(userNo, prompt, referenceImageAnalysis);
        saveMessage(chatId, "assistant", welcomeMessage);

        return ChatDto.ChatResponse.builder()
                .chatId(chatId)
                .aiMessage(welcomeMessage)
                .stage(ChatDto.Stage.CHATTING)
                .canGenerateScenario(false)
                .build();
    }

    /**
     * v2.9.86: 참조 이미지 최대 5장과 함께 새 채팅 시작
     * v2.9.150: 계정에 연결된 크리에이터만 사용 (파라미터 creatorId 무시)
     */
    @Transactional
    public ChatDto.ChatResponse startChatWithImages(Long userNo, String prompt, List<MultipartFile> images, Long ignoredCreatorId) {
        // v2.9.150: 계정에 연결된 크리에이터 강제 사용
        Long creatorId = userMapper.findCreatorIdByUserNo(userNo);
        if (creatorId == null) {
            log.error("[ChatService] User {} has no linked creator", userNo);
            throw new ApiException(ErrorCode.INVALID_REQUEST, "계정에 연결된 크리에이터가 없습니다. 관리자에게 문의하세요.");
        }

        // 유효한 이미지만 필터링
        List<MultipartFile> validImages = (images != null)
                ? images.stream().filter(img -> img != null && !img.isEmpty()).toList()
                : List.of();

        log.info("[ChatService] v2.9.150 Start chat with {} images - userNo: {}, linkedCreatorId: {}",
                validImages.size(), userNo, creatorId);

        // 최대 1장 제한
        if (validImages.size() > 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "참조 이미지는 최대 1장까지 업로드 가능합니다.");
        }

        // 이미지가 없으면 기존 startChat 호출
        if (validImages.isEmpty()) {
            log.info("[ChatService] No images, falling back to startChat");
            ChatDto.StartRequest request = new ChatDto.StartRequest();
            request.setPrompt(prompt);
            request.setCreatorId(creatorId);  // v2.9.134: genreId → creatorId
            return startChat(userNo, request);
        }

        // v2.9.40: 진행 중인 콘텐츠 확인
        Optional<Conversation> inProgressConversation = conversationMapper.findInProgressConversationByUserNo(userNo);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            String currentStep = inProgressConversation.get().getCurrentStep();
            log.warn("[ChatService] User {} has content generation in progress: chatId={}, step={}", userNo, inProgressChatId, currentStep);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "이전 채팅(#" + inProgressChatId + ")에서 영상 생성이 진행 중입니다. 완료 후 새 콘텐츠를 시작할 수 있습니다.");
        }

        // v2.9.89: 대화 먼저 생성 (chatId 확보)
        Conversation conversation = Conversation.builder()
                .userNo(userNo)
                .creatorId(creatorId)  // v2.9.134: genreId → creatorId
                .status("ACTIVE")
                .currentStep("CHATTING")
                .initialPrompt(prompt)
                .contentType("YOUTUBE_SCENARIO")
                .qualityTier("PREMIUM")
                .questionCount(0)
                .totalMessages(0)
                .build();

        conversationMapper.insert(conversation);
        Long chatId = conversation.getConversationId();
        log.info("[ChatService] v2.9.89 Created chat first: chatId={}", chatId);

        List<String> imageUrls = new ArrayList<>();
        List<String> imageAnalyses = new ArrayList<>();

        // v2.9.89: 각 이미지 처리 (chatId 사용)
        // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 지원
        ApiKeyService.setCurrentUserNo(userNo);
        String apiKey;
        try {
            apiKey = apiKeyService.getServiceApiKey();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
        for (int i = 0; i < validImages.size(); i++) {
            MultipartFile image = validImages.get(i);
            try {
                // 1. 이미지 검증 및 S3 업로드 (content/{chatId}/references/ 경로)
                String imageUrl = referenceImageService.uploadImage(chatId, image);
                imageUrls.add(imageUrl);
                log.info("[ChatService] v2.9.89 Image {}/{} uploaded: {}", i + 1, validImages.size(), imageUrl);

                // 2. Gemini 멀티모달로 이미지 분석
                if (apiKey != null && !apiKey.isEmpty()) {
                    byte[] imageBytes = image.getBytes();
                    String mimeType = image.getContentType();
                    String analysis = referenceImageService.analyzeImage(apiKey, imageBytes, mimeType, prompt, creatorId);  // v2.9.134
                    if (analysis != null && !analysis.equals("{}")) {
                        imageAnalyses.add(analysis);
                    }
                    log.info("[ChatService] v2.9.89 Image {}/{} analyzed, result length: {}",
                            i + 1, validImages.size(), analysis != null ? analysis.length() : 0);
                }
            } catch (IOException e) {
                log.error("[ChatService] v2.9.89 Failed to process image {}: {}", i + 1, e.getMessage());
                // 개별 이미지 실패 시 계속 진행 (다른 이미지는 처리)
            }
        }

        // URL들을 쉼표로 구분하여 저장 (DB 컬럼 재활용)
        String referenceImageUrl = String.join(",", imageUrls);

        // 분석 결과들을 JSON 배열로 합침
        String referenceImageAnalysis = mergeImageAnalyses(imageAnalyses);

        log.info("[ChatService] v2.9.89 Total: {} images uploaded, {} analyses merged",
                imageUrls.size(), imageAnalyses.size());

        // v2.9.89: 참조 이미지 정보 업데이트
        conversationMapper.updateReferenceImage(chatId, referenceImageUrl, referenceImageAnalysis);
        log.info("[ChatService] v2.9.89 Created chat with {} images: chatId={}", imageUrls.size(), chatId);

        // 사용자 초기 프롬프트 저장
        if (prompt != null && !prompt.isEmpty()) {
            saveMessage(chatId, "user", prompt);
        }

        // 참조 이미지 정보 메시지 저장
        if (!imageUrls.isEmpty()) {
            String imageInfoMessage = buildImageUploadMessage(imageUrls.size(), creatorId, !imageAnalyses.isEmpty());  // v2.9.134
            saveMessage(chatId, "assistant", imageInfoMessage);
        }

        // 초기 AI 메시지 생성
        String welcomeMessage = generateWelcomeMessageWithImages(userNo, prompt, imageUrls.size(), referenceImageAnalysis);
        saveMessage(chatId, "assistant", welcomeMessage);

        return ChatDto.ChatResponse.builder()
                .chatId(chatId)
                .aiMessage(welcomeMessage)
                .stage(ChatDto.Stage.CHATTING)
                .canGenerateScenario(false)
                .build();
    }

    /**
     * v2.9.86: 여러 이미지 분석 결과를 하나로 합침
     */
    private String mergeImageAnalyses(List<String> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return "{}";
        }

        if (analyses.size() == 1) {
            return analyses.get(0);
        }

        try {
            // 여러 분석 결과를 하나의 JSON 객체로 합침
            Map<String, Object> merged = new HashMap<>();

            for (int i = 0; i < analyses.size(); i++) {
                JsonNode node = objectMapper.readTree(analyses.get(i));
                String prefix = "image" + (i + 1) + "_";

                // 각 필드에 이미지 번호 접두사 추가
                Iterator<String> fieldNames = node.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    merged.put(prefix + fieldName, node.get(fieldName).asText());
                }
            }

            merged.put("totalImages", analyses.size());

            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.error("[ChatService] v2.9.86 Failed to merge analyses: {}", e.getMessage());
            // 실패 시 첫 번째 분석 결과만 반환
            return analyses.get(0);
        }
    }

    /**
     * v2.9.86: 이미지 업로드 완료 메시지 생성
     * v2.9.134: creatorId (genreId → creatorId 통합)
     */
    private String buildImageUploadMessage(int imageCount, Long creatorId, boolean hasAnalysis) {
        boolean isReviewCreator = isReviewCreatorCode(creatorId);

        StringBuilder sb = new StringBuilder();
        sb.append("📷 ").append(imageCount).append("장의 ");

        if (isReviewCreator) {
            sb.append("상품 이미지가 업로드되었습니다. 이 상품들을 리뷰하는 콘텐츠를 생성합니다.");
            if (hasAnalysis) {
                sb.append("\n\n📋 상품 분석 완료: 상품명, 카테고리, 특징, 외관, 색상 정보가 추출되었습니다.");
            }
        } else {
            sb.append("참조 이미지가 업로드되었습니다. 이 이미지들의 스타일과 캐릭터를 기반으로 콘텐츠를 생성합니다.");
            if (hasAnalysis) {
                sb.append("\n\n📋 이미지 분석 완료: 캐릭터, 스타일, 색감 정보가 추출되었습니다.");
            }
        }

        return sb.toString();
    }

    /**
     * v2.9.86: 여러 이미지 정보를 포함한 환영 메시지 생성
     */
    private String generateWelcomeMessageWithImages(Long userNo, String prompt, int imageCount, String imageAnalysis) {
        StringBuilder welcomeBuilder = new StringBuilder();

        if (prompt != null && !prompt.isEmpty()) {
            welcomeBuilder.append("'").append(prompt).append("'에 대한 영상을 만들어 드릴게요.\n\n");
        } else {
            welcomeBuilder.append("안녕하세요! 업로드하신 ").append(imageCount).append("장의 이미지를 분석했어요.\n\n");
        }

        if (imageAnalysis != null && !imageAnalysis.equals("{}")) {
            try {
                JsonNode analysis = objectMapper.readTree(imageAnalysis);

                welcomeBuilder.append("📷 **참조 이미지 분석 결과** (").append(imageCount).append("장)\n");

                // totalImages 필드가 있으면 여러 이미지 분석 결과
                if (analysis.has("totalImages")) {
                    int total = analysis.get("totalImages").asInt();
                    for (int i = 1; i <= total; i++) {
                        String prefix = "image" + i + "_";
                        welcomeBuilder.append("\n**이미지 ").append(i).append("**\n");

                        if (analysis.has(prefix + "characterDescription")) {
                            welcomeBuilder.append("• 캐릭터: ").append(truncateText(analysis.get(prefix + "characterDescription").asText(), 40)).append("\n");
                        }
                        if (analysis.has(prefix + "productName")) {
                            welcomeBuilder.append("• 상품: ").append(truncateText(analysis.get(prefix + "productName").asText(), 40)).append("\n");
                        }
                        if (analysis.has(prefix + "moodAtmosphere")) {
                            welcomeBuilder.append("• 분위기: ").append(analysis.get(prefix + "moodAtmosphere").asText()).append("\n");
                        }
                    }
                } else {
                    // 단일 이미지 분석 결과 (기존 형식)
                    if (analysis.has("characterDescription")) {
                        welcomeBuilder.append("• 캐릭터: ").append(truncateText(analysis.get("characterDescription").asText(), 50)).append("\n");
                    }
                    if (analysis.has("productName")) {
                        welcomeBuilder.append("• 상품: ").append(truncateText(analysis.get("productName").asText(), 50)).append("\n");
                    }
                    if (analysis.has("styleDescription")) {
                        welcomeBuilder.append("• 스타일: ").append(truncateText(analysis.get("styleDescription").asText(), 50)).append("\n");
                    }
                    if (analysis.has("moodAtmosphere")) {
                        welcomeBuilder.append("• 분위기: ").append(analysis.get("moodAtmosphere").asText()).append("\n");
                    }
                }

                welcomeBuilder.append("\n이 이미지들의 느낌을 살려서 영상을 만들게요. 더 추가하고 싶은 내용이 있으시면 말씀해주세요!");

                return welcomeBuilder.toString();
            } catch (Exception e) {
                log.warn("[ChatService] Failed to parse image analysis: {}", e.getMessage());
            }
        }

        // 이미지 분석이 없거나 파싱 실패 시 기본 메시지
        if (prompt != null && !prompt.isEmpty()) {
            return "안녕하세요! '" + prompt + "'에 대한 영상을 만들어 드릴게요. 업로드하신 " + imageCount + "장의 이미지를 참고하여 비슷한 스타일로 제작하겠습니다.";
        }
        return "안녕하세요! 어떤 영상을 만들어 드릴까요? 업로드하신 " + imageCount + "장의 이미지를 참고하여 비슷한 스타일로 제작해 드리겠습니다.";
    }

    /**
     * v2.9.84: 참조 이미지 정보를 포함한 환영 메시지 생성
     */
    private String generateWelcomeMessageWithImage(Long userNo, String prompt, String imageAnalysis) {
        if (imageAnalysis != null && !imageAnalysis.equals("{}")) {
            // 이미지 분석 결과가 있으면 이를 반영한 메시지 생성
            try {
                JsonNode analysis = objectMapper.readTree(imageAnalysis);
                StringBuilder welcomeBuilder = new StringBuilder();

                if (prompt != null && !prompt.isEmpty()) {
                    welcomeBuilder.append("'").append(prompt).append("'에 대한 영상을 만들어 드릴게요.\n\n");
                } else {
                    welcomeBuilder.append("안녕하세요! 업로드하신 이미지를 분석했어요.\n\n");
                }

                welcomeBuilder.append("📷 **참조 이미지 분석 결과**\n");

                if (analysis.has("characterDescription")) {
                    welcomeBuilder.append("• 캐릭터: ").append(truncateText(analysis.get("characterDescription").asText(), 50)).append("\n");
                }
                if (analysis.has("styleDescription")) {
                    welcomeBuilder.append("• 스타일: ").append(truncateText(analysis.get("styleDescription").asText(), 50)).append("\n");
                }
                if (analysis.has("moodAtmosphere")) {
                    welcomeBuilder.append("• 분위기: ").append(analysis.get("moodAtmosphere").asText()).append("\n");
                }

                welcomeBuilder.append("\n이 이미지의 느낌을 살려서 영상을 만들게요. 더 추가하고 싶은 내용이 있으시면 말씀해주세요!");

                return welcomeBuilder.toString();
            } catch (Exception e) {
                log.warn("[ChatService] Failed to parse image analysis: {}", e.getMessage());
            }
        }

        // 이미지 분석이 없거나 파싱 실패 시 기본 메시지
        if (prompt != null && !prompt.isEmpty()) {
            return "안녕하세요! '" + prompt + "'에 대한 영상을 만들어 드릴게요. 업로드하신 이미지를 참고하여 비슷한 스타일로 제작하겠습니다. 더 자세한 내용을 알려주시겠어요?";
        }
        return "안녕하세요! 어떤 영상을 만들어 드릴까요? 업로드하신 이미지를 참고하여 비슷한 스타일로 제작해 드리겠습니다.";
    }

    /**
     * 텍스트 길이 제한 헬퍼
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 메시지 전송
     */
    @Transactional
    public ChatDto.ChatResponse sendMessage(Long userNo, Long chatId, ChatDto.MessageRequest request) {
        Conversation conversation = validateConversation(userNo, chatId);

        // 초기 프롬프트가 없으면 첫 메시지로 설정
        if (conversation.getInitialPrompt() == null || conversation.getInitialPrompt().isEmpty()) {
            String prompt = request.getMessage().length() > 100
                    ? request.getMessage().substring(0, 100)
                    : request.getMessage();
            conversationMapper.updateInitialPrompt(chatId, prompt);
        }

        // 사용자 메시지 저장
        saveMessage(chatId, "user", request.getMessage());

        // 대화 히스토리 조회
        List<ConversationMessage> history = messageMapper.findByConversationId(chatId);

        // AI 응답 생성
        String aiResponse = callGeminiChat(userNo, history, request.getMessage());

        // AI 메시지 저장
        saveMessage(chatId, "assistant", aiResponse);

        // 시나리오 생성 가능 여부 확인 (3회 이상 대화 후)
        boolean canGenerate = history.size() >= 4; // user 2회 + ai 2회 이상

        // 대화에서 "영상을 만들까요?" 등의 패턴 확인
        if (aiResponse.contains("영상을 만들") || aiResponse.contains("시나리오를 생성") ||
            aiResponse.contains("만들어 드릴") || aiResponse.contains("시작할까요")) {
            canGenerate = true;
            conversationMapper.updateCurrentStep(chatId, "SCENARIO_READY");
        }

        ChatDto.Stage stage = canGenerate ? ChatDto.Stage.SCENARIO_READY : ChatDto.Stage.CHATTING;

        return ChatDto.ChatResponse.builder()
                .chatId(chatId)
                .aiMessage(aiResponse)
                .stage(stage)
                .canGenerateScenario(canGenerate)
                .build();
    }

    /**
     * 채팅 상세 조회
     */
    public ChatDto.ChatDetail getChatDetail(Long userNo, Long chatId) {
        Conversation conversation = validateConversation(userNo, chatId);
        List<ConversationMessage> messages = messageMapper.findByConversationId(chatId);

        // 콘텐츠 상태 조회
        ChatDto.ContentStatus contentStatus = getContentStatus(conversation);

        boolean canGenerate = "SCENARIO_READY".equals(conversation.getCurrentStep());

        // v2.9.134: 크리에이터 정보 조회 (페이지 새로고침 시 복원용)
        Long creatorId = conversation.getCreatorId();
        String creatorName = null;
        if (creatorId != null) {
            try {
                creatorName = genreConfigService.getCreator(creatorId).getCreatorName();
            } catch (Exception e) {
                log.warn("크리에이터 정보 조회 실패: creatorId={}", creatorId);
            }
        }

        // v2.9.86: 참조 이미지 presigned URL 생성 (여러 이미지 지원)
        String referenceImageUrl = null;
        if (conversation.getReferenceImageUrl() != null && !conversation.getReferenceImageUrl().isEmpty()) {
            try {
                String rawUrls = conversation.getReferenceImageUrl();
                // 쉼표로 구분된 여러 URL 처리
                if (rawUrls.contains(",")) {
                    String[] s3Keys = rawUrls.split(",");
                    List<String> presignedUrls = new ArrayList<>();
                    for (String s3Key : s3Keys) {
                        String trimmedKey = s3Key.trim();
                        if (!trimmedKey.isEmpty()) {
                            String presigned = referenceImageService.generatePresignedUrl(trimmedKey);
                            if (presigned != null) {
                                presignedUrls.add(presigned);
                            }
                        }
                    }
                    referenceImageUrl = String.join(",", presignedUrls);
                    log.info("[ChatService] v2.9.86 Generated {} presigned URLs for chat {}", presignedUrls.size(), chatId);
                } else {
                    // 단일 이미지
                    referenceImageUrl = referenceImageService.generatePresignedUrl(rawUrls);
                }
            } catch (Exception e) {
                log.warn("참조 이미지 URL 생성 실패: {}", e.getMessage());
            }
        }

        return ChatDto.ChatDetail.builder()
                .chatId(chatId)
                .stage(parseStage(conversation.getCurrentStep()))
                .canGenerateScenario(canGenerate)
                .messages(messages.stream()
                        .map(m -> ChatDto.Message.builder()
                                .role(m.getRole())
                                .content(m.getContent())
                                .messageType(m.getMessageType())  // v2.9.27
                                .metadata(m.getMetadata())        // v2.9.27
                                .createdAt(m.getCreatedAt())
                                .build())
                        .collect(Collectors.toList()))
                .contentStatus(contentStatus)
                .createdAt(conversation.getCreatedAt())
                .creatorId(creatorId)      // v2.9.134: genreId → creatorId
                .creatorName(creatorName)  // v2.9.134: genreName → creatorName
                .referenceImageUrl(referenceImageUrl)  // v2.9.84
                .build();
    }

    /**
     * 채팅 목록 조회
     */
    public List<ChatDto.ChatSummary> getChatList(Long userNo) {
        List<Conversation> conversations = conversationMapper.findAllByUserNo(userNo);
        return conversations.stream()
                .map(c -> ChatDto.ChatSummary.builder()
                        .chatId(c.getConversationId())
                        .initialPrompt(c.getInitialPrompt() != null ? c.getInitialPrompt() : "새 대화")
                        .stage(parseStage(c.getCurrentStep()))
                        .messageCount(c.getTotalMessages() != null ? c.getTotalMessages() : 0)
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 채팅 삭제
     */
    @Transactional
    public void deleteChat(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);
        messageMapper.deleteByConversationId(chatId);
        conversationMapper.delete(chatId);
    }

    // ========== Private Methods ==========

    private Conversation validateConversation(Long userNo, Long chatId) {
        Conversation conversation = conversationMapper.findById(chatId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND));

        if (!conversation.getUserNo().equals(userNo)) {
            throw new ApiException(ErrorCode.CONVERSATION_UNAUTHORIZED);
        }

        return conversation;
    }

    private void saveMessage(Long chatId, String role, String content) {
        ConversationMessage message = ConversationMessage.builder()
                .conversationId(chatId)
                .role(role)
                .content(content)
                .messageType("user".equals(role) ? "USER_RESPONSE" : "AI_QUESTION")
                .build();
        messageMapper.insert(message);
        conversationMapper.incrementTotalMessages(chatId);
    }

    private String generateWelcomeMessage(Long userNo, String prompt) {
        if (prompt != null && !prompt.isEmpty()) {
            return callGeminiForWelcome(userNo, prompt);
        }
        return "안녕하세요! 어떤 영상을 만들어 드릴까요? 원하시는 스토리나 주제를 자유롭게 말씀해 주세요.";
    }

    private String callGeminiForWelcome(Long userNo, String prompt) {
        try {
            List<ConversationMessage> history = new ArrayList<>();
            return callGeminiChat(userNo, history, prompt);
        } catch (Exception e) {
            log.error("[ChatService] Failed to generate welcome: {}", e.getMessage());
            return "안녕하세요! '" + prompt + "'에 대한 영상을 만들어 드릴게요. 더 자세한 내용을 알려주시겠어요?";
        }
    }

    private String callGeminiChat(Long userNo, List<ConversationMessage> history, String userMessage) {
        // v2.9.165: CUSTOM 티어 사용자의 개인 API 키 지원
        ApiKeyService.setCurrentUserNo(userNo);
        try {
        // 사용자 API 키 조회
        String apiKey = apiKeyService.getServiceApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키가 설정되지 않았습니다. 마이페이지에서 Google API 키를 등록해주세요.");
        }

        // v2.9.11: API 키를 URL 대신 Header로 전달 (보안 강화)
        String apiUrl = String.format(GEMINI_API_URL, chatModel);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        // 시스템 프롬프트
        String systemPrompt = buildSystemPrompt();

        // 대화 내용 구성
        List<Map<String, Object>> contents = new ArrayList<>();

        // 시스템 프롬프트
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", systemPrompt))
        ));
        contents.add(Map.of(
                "role", "model",
                "parts", List.of(Map.of("text", "네, 알겠습니다. 말씀해 주세요."))
        ));

        // 대화 히스토리
        for (ConversationMessage msg : history) {
            String role = "user".equals(msg.getRole()) ? "user" : "model";
            contents.add(Map.of(
                    "role", role,
                    "parts", List.of(Map.of("text", msg.getContent()))
            ));
        }

        // 현재 메시지
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        ));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", contents);
        requestBody.put("generationConfig", Map.of(
                "temperature", 0.9,
                "maxOutputTokens", 2000
        ));

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("candidates") && root.get("candidates").size() > 0) {
                    return root.get("candidates").get(0)
                            .get("content").get("parts").get(0)
                            .get("text").asText();
                }
            }

            return "죄송합니다. 응답을 생성하는 데 문제가 발생했습니다.";

        } catch (Exception e) {
            log.error("[ChatService] Gemini API error: {}", e.getMessage());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 서비스 오류: " + e.getMessage());
        }
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    private String buildSystemPrompt() {
        return """
            You are a professional AI video content writer.
            You chat with the user to collaboratively create compelling video stories.

            ## Your Role
            - Develop the user's ideas into engaging stories
            - Ask about characters, settings, and conflict structure to flesh out details
            - When enough information is gathered, suggest generating a scenario

            ## Conversation Guide
            1. First message: Understand what kind of story the user wants
            2. Characters: Protagonist appearance, personality, background
            3. Relationships: Connections and conflicts between characters
            4. Mood: Desired genre and tone
            5. Confirmation: When sufficient info is gathered, ask "Shall I create a scenario with this?"

            ## Important Rules
            - Use a natural, friendly conversational tone
            - Don't ask too many questions at once (1-2 at a time)
            - Respect and build upon the user's ideas
            - Suggest scenario generation when enough info (characters, relationships, setting) is gathered
            """;
    }

    /**
     * Check if the creator is a REVIEW-type creator by creatorCode.
     */
    private boolean isReviewCreatorCode(Long creatorId) {
        if (creatorId == null) return false;
        try {
            return "REVIEW".equals(genreConfigService.getCreator(creatorId).getCreatorCode());
        } catch (Exception e) {
            return false;
        }
    }

    private ChatDto.ContentStatus getContentStatus(Conversation conversation) {
        String step = conversation.getCurrentStep();

        return ChatDto.ContentStatus.builder()
                .scenarioReady("SCENARIO_DONE".equals(step) || "PREVIEWS_DONE".equals(step) ||
                              "TTS_DONE".equals(step) || "IMAGES_DONE".equals(step) ||
                              "AUDIO_DONE".equals(step) || "VIDEO_DONE".equals(step))
                .imagesReady("PREVIEWS_DONE".equals(step) || "TTS_DONE".equals(step) ||
                            "IMAGES_DONE".equals(step) || "AUDIO_DONE".equals(step) || "VIDEO_DONE".equals(step))
                .audioReady("TTS_DONE".equals(step) || "AUDIO_DONE".equals(step) || "VIDEO_DONE".equals(step))
                .videoReady("VIDEO_DONE".equals(step))
                .build();
    }

    /**
     * v2.9.0: DB의 currentStep을 Stage enum으로 변환
     * 모든 가능한 currentStep 값을 처리하여 상태 리셋 방지
     * 실패/재생성 상태 추가
     */
    private ChatDto.Stage parseStage(String step) {
        if (step == null) return ChatDto.Stage.CHATTING;
        return switch (step) {
            case "SCENARIO_READY" -> ChatDto.Stage.SCENARIO_READY;
            case "SCENARIO_GENERATING" -> ChatDto.Stage.SCENARIO_GENERATING;  // v2.9.75
            case "SCENARIO_DONE" -> ChatDto.Stage.SCENARIO_DONE;
            // 씬 프리뷰 관련 상태
            case "PREVIEWS_GENERATING" -> ChatDto.Stage.PREVIEWS_GENERATING;
            case "PREVIEWS_DONE" -> ChatDto.Stage.PREVIEWS_DONE;
            case "SCENES_GENERATING" -> ChatDto.Stage.SCENES_GENERATING;
            case "SCENES_REVIEW" -> ChatDto.Stage.SCENES_REVIEW;
            // v2.9.0: 씬 재생성 상태
            case "SCENE_REGENERATING" -> ChatDto.Stage.SCENE_REGENERATING;
            // TTS 관련 상태
            case "TTS_GENERATING" -> ChatDto.Stage.TTS_GENERATING;
            case "TTS_DONE" -> ChatDto.Stage.TTS_DONE;
            // v2.9.0: TTS 부분 실패 상태
            case "TTS_PARTIAL_FAILED" -> ChatDto.Stage.TTS_PARTIAL_FAILED;
            // 이미지/오디오 관련 상태
            case "IMAGES_GENERATING" -> ChatDto.Stage.IMAGES_GENERATING;
            case "IMAGES_DONE" -> ChatDto.Stage.IMAGES_DONE;
            case "AUDIO_GENERATING" -> ChatDto.Stage.AUDIO_GENERATING;
            case "AUDIO_DONE" -> ChatDto.Stage.AUDIO_DONE;
            // 영상 관련 상태
            case "VIDEO_GENERATING" -> ChatDto.Stage.VIDEO_GENERATING;
            // v2.9.0: 영상 합성 실패 상태
            case "VIDEO_FAILED" -> ChatDto.Stage.VIDEO_FAILED;
            case "VIDEO_DONE" -> ChatDto.Stage.VIDEO_DONE;
            default -> ChatDto.Stage.CHATTING;
        };
    }

    /**
     * v2.9.150: 사용자에게 연결된 크리에이터 정보 조회
     * 계정에 1:1로 매핑된 크리에이터 정보를 반환
     */
    public ChatDto.LinkedCreatorInfo getLinkedCreator(Long userNo) {
        Long creatorId = userMapper.findCreatorIdByUserNo(userNo);
        if (creatorId == null) {
            log.warn("[ChatService] User {} has no linked creator", userNo);
            return null;
        }

        try {
            var creator = genreConfigService.getCreator(creatorId);
            String tierCode = genreConfigService.getTierCode(creatorId);
            boolean allowImageUpload = "ULTRA".equals(tierCode);

            return ChatDto.LinkedCreatorInfo.builder()
                    .creatorId(creator.getCreatorId())
                    .creatorCode(creator.getCreatorCode())
                    .creatorName(creator.getCreatorName())
                    .nationCode(creator.getNationCode())  // v2.9.174: 국가 코드
                    .description(creator.getDescription())
                    .placeholderText(creator.getPlaceholderText())
                    .tierCode(tierCode)
                    .allowImageUpload(allowImageUpload)
                    .build();
        } catch (Exception e) {
            log.error("[ChatService] Failed to get creator info for creatorId: {}", creatorId, e);
            return null;
        }
    }
}
