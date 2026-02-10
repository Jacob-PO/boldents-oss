package com.aivideo.api.service;

// HashtagConfig 제거됨 - CreatorConfigService로 대체 (v2.8.0)
import com.aivideo.api.dto.ContentDto;
import com.aivideo.api.dto.VideoDto;
import com.aivideo.api.entity.Conversation;
import com.aivideo.api.entity.ConversationMessage;
import com.aivideo.api.entity.Scenario;
import com.aivideo.api.entity.Scene;
import com.aivideo.api.entity.Video;
import com.aivideo.api.entity.VideoFormat;
import com.aivideo.api.mapper.*;
import com.aivideo.api.service.image.ImageGeneratorService;
import com.aivideo.api.service.image.ImageGeneratorService.ScenarioContext;
import com.aivideo.api.service.scenario.ScenarioGeneratorService;
import com.aivideo.api.service.reference.ReferenceImageService;
import com.aivideo.api.service.storage.StorageService;
import com.aivideo.api.service.subtitle.SubtitleService;
import com.aivideo.api.service.tts.TtsService;
import com.aivideo.api.service.video.VideoCreatorService;
import com.aivideo.common.enums.ContentType;
import com.aivideo.common.enums.QualityTier;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import com.aivideo.api.util.PathValidator;
import com.aivideo.api.util.UrlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final VideoMapper videoMapper;
    private final ScenarioMapper scenarioMapper;
    private final SceneMapper sceneMapper;
    private final SceneUpdateService sceneUpdateService;  // v2.9.21: 독립 트랜잭션으로 Lock Timeout 방지
    private final VideoFormatMapper videoFormatMapper;    // v2.9.25: 영상 포맷 조회
    private final ScenarioGeneratorService scenarioGeneratorService;
    private final ImageGeneratorService imageGeneratorService;
    private final TtsService ttsService;
    private final VideoCreatorService videoCreatorService;
    private final StorageService storageService;
    private final CreatorConfigService genreConfigService;
    private final ThumbnailService thumbnailService;  // v2.9.49: 이미지 생성 후 썸네일 자동 생성
    private final ReferenceImageService referenceImageService;  // v2.9.84: 참조 이미지 서비스
    private final SubtitleService subtitleService;  // v2.9.160: 자막 생성 서비스 (ContentService에서 분리)
    // v2.9.11: Bean 주입으로 변경 (HttpClientConfig에서 관리)
    private final ObjectMapper objectMapper;

    // v2.9.25: 현재 영상 포맷 ID (ThreadLocal)
    private static final ThreadLocal<Long> currentFormatId = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentVideoSubtitleId = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentFontSizeLevel = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentSubtitlePosition = new ThreadLocal<>();
    // v2.9.174: 폰트 ID, 크리에이터 ID (국제화 지원)
    private static final ThreadLocal<Long> currentFontId = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentCreatorId = new ThreadLocal<>();

    // 진행 상태 저장 (메모리)
    private final Map<Long, ProgressInfo> progressStore = new ConcurrentHashMap<>();

    // v2.9.75: 시나리오 생성 진행 상황 저장 (메모리)
    private static final Map<Long, ScenarioProgressInfo> scenarioProgressStore = new ConcurrentHashMap<>();

    // v2.9.22: FFmpeg 병렬 처리용 ExecutorService (t3.micro OOM 방지 - 1개만 실행)
    // 이유: 1GB RAM에서 FFmpeg 2개 동시 실행 시 OOM (exit 137) 발생
    private final ExecutorService ffmpegExecutor = Executors.newFixedThreadPool(1);

    // v2.9.22: 재시도 설정 (TTS/FFmpeg 실패 시 자동 재시도)
    // OOM(exit 137) 감지 시 더 긴 딜레이 적용
    private static final int MAX_TTS_RETRIES = 3;
    private static final int MAX_FFMPEG_RETRIES = 5;  // v2.9.22: 3→5 (OOM 재시도 여유)
    private static final long TTS_RETRY_BASE_DELAY_MS = 2000;    // 2초, 4초, 8초
    private static final long FFMPEG_RETRY_BASE_DELAY_MS = 2000; // v2.9.22: 1초→2초 (2초, 4초, 8초, 16초, 32초)
    private static final long FFMPEG_OOM_EXTRA_DELAY_MS = 5000;  // v2.9.22: OOM 발생 시 추가 5초 대기
    private static final long FFMPEG_PIPELINE_TIMEOUT_SECONDS = 180; // v2.9.84: 파이프라인 대기 타임아웃 (15분→3분)

    // v2.9.170: 문장 분리 정규식 (SubtitleServiceImpl과 동일)
    private static final String SENTENCE_SPLIT_REGEX = "(?<=[.!?。！？])(?![0-9])\\s*";

    /**
     * v2.9.175: SubtitleServiceImpl.splitNarrationIntoSentencesForScene()와 동일한 필터링 로직으로 문장 수 카운트.
     * 근본 원인: 기존 split().length는 "...", "?!" 등 연속 구두점을 개별 문장으로 카운트하여
     * detectSentenceBoundaries()에 잘못된 sentenceCount를 전달 → 자막 싱크 불일치.
     */
    private int countSentences(String narration) {
        if (narration == null || narration.trim().isEmpty()) return 1;
        String[] parts = narration.split(SENTENCE_SPLIT_REGEX);
        int count = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.matches("^[.!?…\\s]+$") && trimmed.length() >= 2) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static final String CONTENT_DIR = "/tmp/aivideo/content";

    // ========== v2.9.25: 포맷 관련 헬퍼 메서드 ==========

    /**
     * 현재 영상 포맷 ID 설정 (다른 서비스에도 전파)
     */
    public void setCurrentFormatId(Long formatId) {
        currentFormatId.set(formatId);
        log.info("[CONTENT] formatId set: {}", formatId);
        // 다른 서비스에도 전파
        if (imageGeneratorService instanceof com.aivideo.api.service.image.ImageGeneratorServiceImpl impl) {
            impl.setCurrentFormatId(formatId);
        }
        if (videoCreatorService instanceof com.aivideo.api.service.video.VideoCreatorServiceImpl impl) {
            impl.setCurrentFormatId(formatId);
        }
        if (ttsService instanceof com.aivideo.api.service.tts.TtsServiceImpl) {
            // TtsService는 자막 서비스 사용 시 전파됨
        }
    }

    /**
     * 현재 포맷의 해상도 조회
     * @return int[] {width, height}
     */
    private int[] getCurrentResolution() {
        Long formatId = currentFormatId.get();
        if (formatId == null) {
            return new int[]{1920, 1080};  // 기본값: 16:9
        }
        return videoFormatMapper.findById(formatId)
            .map(f -> new int[]{f.getWidth(), f.getHeight()})
            .orElse(new int[]{1920, 1080});
    }

    /**
     * 포맷별 스케일 필터 문자열 생성
     */
    private String getScaleFilter() {
        int[] res = getCurrentResolution();
        return String.format("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d",
            res[0], res[1], res[0], res[1]);
    }

    /**
     * v2.9.25: Video의 formatId로 aspectRatio 조회
     */
    private String getAspectRatioByVideo(Video video) {
        if (video == null || video.getFormatId() == null) {
            return "16:9";  // 기본값
        }
        return videoFormatMapper.findById(video.getFormatId())
            .map(VideoFormat::getAspectRatio)
            .orElse("16:9");
    }

    // ========== 시나리오 ==========

    @Transactional
    public ContentDto.ScenarioResponse generateScenario(Long userNo, Long chatId, ContentDto.ScenarioRequest request) {
        // v2.9.165: CUSTOM 티어 사용자 개인 API 키 지원
        ApiKeyService.setCurrentUserNo(userNo);
        try {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        // ⚠️ v2.5.2: 시나리오 재생성 시 이전 데이터 정리 (중요!)
        // v2.9.13: N+1 쿼리 방지 - batch 삭제로 개선 (3N+1 queries → 4 queries)
        List<Video> existingVideos = videoMapper.findByConversationId(chatId);
        if (!existingVideos.isEmpty()) {
            List<Long> videoIds = existingVideos.stream()
                    .map(Video::getVideoId)
                    .toList();

            log.info("[ContentService] Cleaning up {} previous video(s) for conversation: {} (videoIds: {})",
                    existingVideos.size(), chatId, videoIds);

            // 일괄 삭제 (3 queries로 처리)
            sceneMapper.deleteByVideoIdsBatch(videoIds);
            scenarioMapper.deleteByVideoIdsBatch(videoIds);
            videoMapper.deleteByIdsBatch(videoIds);

            // v2.9.0: 로컬 파일 정리 (디스크 공간 절약)
            cleanupLocalContent(chatId);

            log.info("[ContentService] Previous data cleanup completed for conversation: {}", chatId);
        }

        // 원본 사용자 프롬프트 조회 (첫 메시지)
        String originalPrompt = conversation.getInitialPrompt();
        if (originalPrompt == null || originalPrompt.isEmpty()) {
            List<ConversationMessage> messages = messageMapper.findByConversationId(chatId);
            originalPrompt = messages.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .findFirst()
                    .map(ConversationMessage::getContent)
                    .orElse("AI Video");
        }

        // v2.9.73: 슬라이드 수 직접 사용 (1-10장, 기본값 6)
        int slideCount = 6;  // 기본값
        if (request != null && request.getSlideCount() != null && request.getSlideCount() > 0) {
            slideCount = Math.min(10, Math.max(1, request.getSlideCount()));  // 1-10 범위 강제
        }
        log.info("[v2.9.73] Using slideCount: {}", slideCount);

        // v2.9.170: 하드코딩 프롬프트 래퍼 제거
        // DB의 SCENARIO_USER_TEMPLATE이 {{USER_INPUT}}, {{SLIDE_COUNT}} 플레이스홀더로 장르별 래핑 담당
        // originalPrompt를 그대로 시나리오 생성에 전달 (장르별 톤/스타일은 DB 프롬프트가 처리)
        String enhancedPrompt = originalPrompt;

        // v2.8.0: 장르 ID (null이면 기본 장르=1 사용)
        Long creatorId = request.getCreatorId();

        // v2.9.25: 영상 포맷 ID (null이면 기본 포맷=1 사용)
        // v2.9.84: 포맷 제한은 프론트엔드에서 처리 (3장 이하만 쇼츠 선택 가능)
        Long formatId = request.getFormatId();
        if (formatId == null) {
            formatId = 1L;  // 기본값: YOUTUBE_STANDARD (16:9)
        }
        setCurrentFormatId(formatId);  // 다른 서비스에 포맷 ID 전파

        // v2.9.161: 자막 템플릿 ID (null이면 기본 자막=1 사용)
        Long videoSubtitleId = request.getVideoSubtitleId();
        if (videoSubtitleId == null) {
            videoSubtitleId = 1L;  // 기본값: DEFAULT_OUTLINE (기본 자막)
        }
        currentVideoSubtitleId.set(videoSubtitleId);

        // v2.9.161: 자막 글자 크기 (1=small, 2=medium, 3=large, 기본값: 3)
        Integer fontSizeLevel = request.getFontSizeLevel();
        if (fontSizeLevel == null) {
            fontSizeLevel = 3;  // 기본값: large
        }
        currentFontSizeLevel.set(fontSizeLevel);

        // v2.9.167: 자막 위치 (1=하단, 2=중앙, 3=상단, 기본값: 1)
        Integer subtitlePosition = request.getSubtitlePosition();
        if (subtitlePosition == null) {
            subtitlePosition = 1;  // 기본값: 하단
        }
        currentSubtitlePosition.set(subtitlePosition);

        // v2.9.174: 폰트 ID (null이면 기본 폰트=1 사용)
        Long fontId = request.getFontId();
        if (fontId == null) {
            fontId = 1L;  // 기본값: SUIT-Bold
        }
        currentFontId.set(fontId);
        // v2.9.174: 크리에이터 ID (국제화 자막 설정용)
        currentCreatorId.set(creatorId);

        // v2.9.168: 썸네일 디자인 ID (null이면 기본 CLASSIC)
        Long thumbnailId = request.getThumbnailId();

        log.info("[ContentService] Generating scenario for chat: {}, originalPrompt: '{}', slideCount: {}, creatorId: {}, formatId: {}, fontSizeLevel: {}, subtitlePosition: {}, fontId: {}, thumbnailId: {}",
                chatId, originalPrompt, slideCount, creatorId, formatId, fontSizeLevel, subtitlePosition, fontId, thumbnailId);

        // v2.9.75: 시나리오 생성 시작 - 상태 업데이트
        conversationMapper.updateCurrentStep(chatId, "SCENARIO_GENERATING");

        // v2.9.75: 시나리오 진행 상황 초기화
        initScenarioProgress(chatId, slideCount);

        // v2.9.84: 참조 이미지가 있으면 generateScenarioWithReferenceImage 사용
        String referenceImageAnalysis = conversation.getReferenceImageAnalysis();
        boolean hasReferenceImage = referenceImageAnalysis != null
                && !referenceImageAnalysis.isEmpty()
                && !referenceImageAnalysis.equals("{}");

        log.info("[ContentService] v2.9.84: Generating scenario - hasReferenceImage: {}", hasReferenceImage);

        // v2.9.75: 시나리오 생성 (chatId 포함 - 진행 상황 추적)
        VideoDto.ScenarioInfo scenarioInfo;
        try {
            if (hasReferenceImage) {
                // v2.9.84: 참조 이미지 분석 결과를 포함하여 시나리오 생성
                scenarioInfo = scenarioGeneratorService.generateScenarioWithReferenceImage(
                        userNo, enhancedPrompt, QualityTier.PREMIUM, ContentType.YOUTUBE_SCENARIO,
                        slideCount, creatorId, chatId, referenceImageAnalysis
                );
                log.info("[ContentService] v2.9.84: Scenario generated with reference image analysis");
            } else {
                // 기존 방식: 텍스트 프롬프트만으로 시나리오 생성
                scenarioInfo = scenarioGeneratorService.generateScenario(
                        userNo, enhancedPrompt, QualityTier.PREMIUM, ContentType.YOUTUBE_SCENARIO, slideCount, creatorId, chatId
                );
            }
        } catch (Exception e) {
            // v2.9.75: 실패 시 진행 상황 업데이트
            updateScenarioProgress(chatId, "failed", 0, slideCount, "시나리오 생성 실패: " + e.getMessage());
            throw e;
        }

        // Video 엔티티 생성 (시나리오/이미지/오디오/영상 참조용) - v2.8.0: creatorId 추가, v2.9.25: formatId 추가
        Video video = Video.builder()
                .userNo(userNo)
                .creatorId(creatorId)  // v2.8.0: 장르 ID
                .formatId(formatId)  // v2.9.25: 영상 포맷 ID
                .videoSubtitleId(videoSubtitleId)  // v2.9.161: 자막 템플릿 ID
                .fontSizeLevel(fontSizeLevel)  // v2.9.161: 자막 글자 크기 (1=small, 2=medium, 3=large)
                .subtitlePosition(subtitlePosition)  // v2.9.167: 자막 위치 (1=하단, 2=중앙, 3=상단)
                .fontId(fontId)  // v2.9.174: 폰트 ID
                .thumbnailId(thumbnailId)  // v2.9.168: 썸네일 디자인 ID
                .conversationId(chatId)
                .title(scenarioInfo.getTitle())
                .description(scenarioInfo.getDescription())
                .prompt(originalPrompt)
                .contentType("YOUTUBE_SCENARIO")
                .qualityTier("PREMIUM")
                .status("SCENARIO_GENERATED")
                .progress(0)
                .currentStep("SCENARIO")
                .build();
        videoMapper.insert(video);
        Long videoId = video.getVideoId();

        // Conversation에 videoId, creatorId, videoDuration 연결
        conversationMapper.updateVideoId(chatId, videoId);
        if (creatorId != null) {
            conversationMapper.updateCreatorId(chatId, creatorId);
        }
        // v2.9.73: 슬라이드 수 저장 (1-10장)
        conversationMapper.updateVideoDuration(chatId, slideCount);
        log.info("[v2.9.73] Updated slideCount for chat {}: {} slides", chatId, slideCount);

        // 시나리오 JSON 저장
        String scenarioJson = convertScenarioToJson(scenarioInfo);
        Scenario scenario = Scenario.builder()
                .videoId(videoId)
                .scenarioJson(scenarioJson)
                .openingPrompt(scenarioInfo.getOpening() != null ? scenarioInfo.getOpening().getVideoPrompt() : null)
                .characterBlock(wrapAsJson(scenarioInfo.getCharacterBlock()))
                .version(1)
                .build();
        scenarioMapper.insert(scenario);

        // 씬 저장 및 응답 데이터 생성
        List<ContentDto.SlideInfo> slides = new ArrayList<>();
        int totalDuration = 0;

        // 오프닝 씬 저장 (있을 경우)
        if (scenarioInfo.getOpening() != null) {
            Scene openingScene = Scene.builder()
                    .videoId(videoId)
                    .sceneType("OPENING")
                    .sceneOrder(0)
                    .narration(scenarioInfo.getOpening().getNarration())
                    .prompt(scenarioInfo.getOpening().getVideoPrompt())
                    .duration(scenarioInfo.getOpening().getDurationSeconds())
                    .mediaStatus("PENDING")
                    .build();
            sceneMapper.insert(openingScene);
            totalDuration += scenarioInfo.getOpening().getDurationSeconds();
        }

        // 슬라이드 씬 저장
        if (scenarioInfo.getSlides() != null) {
            for (int i = 0; i < scenarioInfo.getSlides().size(); i++) {
                VideoDto.SlideScene slide = scenarioInfo.getSlides().get(i);
                Scene scene = Scene.builder()
                        .videoId(videoId)
                        .sceneType("SLIDE")
                        .sceneOrder(i + 1)
                        .narration(slide.getNarration())
                        .prompt(slide.getImagePrompt())
                        .duration(slide.getDurationSeconds())
                        .transitionType(slide.getTransition())
                        .mediaStatus("PENDING")
                        .build();
                sceneMapper.insert(scene);

                slides.add(ContentDto.SlideInfo.builder()
                        .order(i + 1)
                        .narration(slide.getNarration())
                        .imagePrompt(slide.getImagePrompt())
                        .durationSeconds(slide.getDurationSeconds())
                        .build());

                totalDuration += slide.getDurationSeconds();
            }
        }

        // 시나리오 TXT 파일 생성
        saveScenarioToFile(chatId, scenarioInfo);

        // 대화 상태 업데이트
        conversationMapper.updateCurrentStep(chatId, "SCENARIO_DONE");

        // 고객용 시나리오 요약 생성
        String summary = buildScenarioSummary(scenarioInfo, slides.size(), totalDuration);
        boolean hasOpening = scenarioInfo.getOpening() != null;

        // 오프닝 정보 생성
        ContentDto.OpeningInfo openingInfo = null;
        if (hasOpening) {
            openingInfo = ContentDto.OpeningInfo.builder()
                    .narration(scenarioInfo.getOpening().getNarration())
                    .videoPrompt(scenarioInfo.getOpening().getVideoPrompt())
                    .durationSeconds(scenarioInfo.getOpening().getDurationSeconds())
                    .build();
        }

        // v2.9.75: 시나리오 생성 완료
        int completedSlideCount = slides.size();
        updateScenarioProgress(chatId, "completed", completedSlideCount, completedSlideCount, "시나리오 생성 완료");

        return ContentDto.ScenarioResponse.builder()
                .chatId(chatId)
                .title(scenarioInfo.getTitle())
                .description(scenarioInfo.getDescription())
                .summary(summary)
                .hook(scenarioInfo.getDescription())  // hook은 description과 동일
                .slides(slides)
                .estimatedDuration(totalDuration)
                .downloadReady(true)
                .hasOpening(hasOpening)
                .opening(openingInfo)
                .build();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * 고객용 시나리오 요약 생성
     */
    private String buildScenarioSummary(VideoDto.ScenarioInfo scenarioInfo, int slideCount, int totalDuration) {
        StringBuilder summary = new StringBuilder();

        summary.append("**").append(scenarioInfo.getTitle()).append("**\n\n");

        if (scenarioInfo.getDescription() != null && !scenarioInfo.getDescription().isEmpty()) {
            summary.append("\"").append(scenarioInfo.getDescription()).append("\"\n\n");
        }

        summary.append("---\n\n");
        summary.append("**스토리 구성**\n\n");

        // 슬라이드 나레이션 요약 (처음 3개와 마지막 1개만)
        if (scenarioInfo.getSlides() != null && !scenarioInfo.getSlides().isEmpty()) {
            int total = scenarioInfo.getSlides().size();
            int previewCount = Math.min(3, total);

            for (int i = 0; i < previewCount; i++) {
                VideoDto.SlideScene slide = scenarioInfo.getSlides().get(i);
                String narration = slide.getNarration();
                // 나레이션이 길면 50자로 자르기
                if (narration != null && narration.length() > 50) {
                    narration = narration.substring(0, 50) + "...";
                }
                summary.append(String.format("%d. %s\n", i + 1, narration));
            }

            if (total > 4) {
                summary.append("...\n");
                VideoDto.SlideScene lastSlide = scenarioInfo.getSlides().get(total - 1);
                String lastNarration = lastSlide.getNarration();
                if (lastNarration != null && lastNarration.length() > 50) {
                    lastNarration = lastNarration.substring(0, 50) + "...";
                }
                summary.append(String.format("%d. %s\n", total, lastNarration));
            }
        }

        summary.append("\n---\n\n");
        summary.append(String.format("총 %d개 슬라이드 | 예상 길이 %d분 %d초",
                slideCount, totalDuration / 60, totalDuration % 60));

        return summary.toString();
    }

    /**
     * 스타일 코드를 설명으로 변환
     */
    private String getStyleDescription(String style) {
        return switch (style) {
            case "dramatic" -> "dramatic revenge style";
            case "emotional" -> "emotional melodrama style";
            case "twist" -> "thriller style with shocking twists";
            case "comedy" -> "comedic drama style";
            default -> "default drama style";
        };
    }

    /**
     * 시나리오 조회 (기존 생성된 시나리오)
     */
    public ContentDto.ScenarioResponse getScenario(Long userNo, Long chatId) {
        Conversation conversation = validateConversation(userNo, chatId);

        // 시나리오 생성 여부 확인
        String step = conversation.getCurrentStep();
        if (step == null || "CHATTING".equals(step) || "SCENARIO_READY".equals(step)) {
            return null; // 시나리오가 아직 생성되지 않음
        }

        // Video 조회
        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // Scenario 조회
        Scenario scenario = scenarioMapper.findByVideoId(videoId).orElse(null);
        if (scenario == null) {
            return null;
        }

        // v2.9.11: Scene 조회 최적화 - 한 번만 조회 후 메모리에서 필터링
        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);
        List<Scene> scenes = allScenes.stream()
                .filter(s -> "SLIDE".equals(s.getSceneType()))
                .collect(Collectors.toList());
        Scene openingScene = allScenes.stream()
                .filter(s -> "OPENING".equals(s.getSceneType()))
                .findFirst()
                .orElse(null);

        // SlideInfo 리스트 생성
        List<ContentDto.SlideInfo> slides = scenes.stream()
                .map(s -> ContentDto.SlideInfo.builder()
                        .order(s.getSceneOrder())
                        .narration(s.getNarration())
                        .imagePrompt(s.getPrompt())
                        .durationSeconds(s.getDuration())
                        .build())
                .collect(Collectors.toList());

        // 총 길이 계산
        int totalDuration = scenes.stream()
                .mapToInt(s -> s.getDuration() != null ? s.getDuration() : 0)
                .sum();

        // 오프닝 정보
        ContentDto.OpeningInfo openingInfo = null;
        boolean hasOpening = openingScene != null;
        if (hasOpening) {
            openingInfo = ContentDto.OpeningInfo.builder()
                    .narration(openingScene.getNarration())
                    .videoPrompt(openingScene.getPrompt())
                    .durationSeconds(openingScene.getDuration())
                    .build();
            totalDuration += openingScene.getDuration() != null ? openingScene.getDuration() : 0;
        }

        return ContentDto.ScenarioResponse.builder()
                .chatId(chatId)
                .title(video.getTitle())
                .description(video.getDescription())
                .summary(buildScenarioSummaryFromScenes(video.getTitle(), video.getDescription(), slides, totalDuration))
                .hook(video.getDescription())
                .slides(slides)
                .estimatedDuration(totalDuration)
                .downloadReady(true)
                .hasOpening(hasOpening)
                .opening(openingInfo)
                .build();
    }

    /**
     * Scene 데이터로부터 시나리오 요약 생성
     */
    private String buildScenarioSummaryFromScenes(String title, String description, List<ContentDto.SlideInfo> slides, int totalDuration) {
        StringBuilder summary = new StringBuilder();

        summary.append("**").append(title).append("**\n\n");

        if (description != null && !description.isEmpty()) {
            summary.append("\"").append(description).append("\"\n\n");
        }

        summary.append("---\n\n");
        summary.append("**스토리 구성**\n\n");

        if (slides != null && !slides.isEmpty()) {
            int total = slides.size();
            int previewCount = Math.min(3, total);

            for (int i = 0; i < previewCount; i++) {
                ContentDto.SlideInfo slide = slides.get(i);
                String narration = slide.getNarration();
                if (narration != null && narration.length() > 50) {
                    narration = narration.substring(0, 50) + "...";
                }
                summary.append(String.format("%d. %s\n", i + 1, narration));
            }

            if (total > 4) {
                summary.append("...\n");
                ContentDto.SlideInfo lastSlide = slides.get(total - 1);
                String lastNarration = lastSlide.getNarration();
                if (lastNarration != null && lastNarration.length() > 50) {
                    lastNarration = lastNarration.substring(0, 50) + "...";
                }
                summary.append(String.format("%d. %s\n", total, lastNarration));
            }
        }

        summary.append("\n---\n\n");
        summary.append(String.format("총 %d개 슬라이드 | 예상 길이 %d분 %d초",
                slides != null ? slides.size() : 0, totalDuration / 60, totalDuration % 60));

        return summary.toString();
    }

    public ContentDto.DownloadInfo getScenarioDownload(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // S3에서 다운로드 URL 생성
        if (storageService.isEnabled()) {
            String s3Key = getS3Key(chatId, "scenario.txt");
            if (storageService.exists(s3Key)) {
                String presignedUrl = storageService.generatePresignedUrl(s3Key);
                return ContentDto.DownloadInfo.builder()
                        .filename("scenario_" + chatId + ".txt")
                        .contentType("text/plain; charset=UTF-8")
                        .downloadUrl(presignedUrl)
                        .build();
            }
        }

        // 로컬 파일 폴백
        Path filePath = getScenarioFilePath(chatId);
        if (!Files.exists(filePath)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "시나리오 파일이 존재하지 않습니다.");
        }

        return ContentDto.DownloadInfo.builder()
                .filename("scenario_" + chatId + ".txt")
                .contentType("text/plain; charset=UTF-8")
                .fileSize(filePath.toFile().length())
                .build();
    }

    public Resource getScenarioResource(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);
        Path filePath = getScenarioFilePath(chatId);
        return new FileSystemResource(filePath);
    }

    // ========== 이미지 ==========

    @Transactional
    public ContentDto.ImagesResponse generateImages(Long userNo, Long chatId) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        // Video 조회
        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 씬 조회 (슬라이드만)
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(videoId).stream()
                .filter(s -> "SLIDE".equals(s.getSceneType()))
                .collect(Collectors.toList());

        log.info("[ContentService] Generating images for chat: {}, scenes: {}", chatId, scenes.size());

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("images", scenes.size());
        progressStore.put(chatId, progress);

        // v2.5.8: 이미지 생성 (비동기) - ScenarioContext는 generateImagesAsync 내에서 DB로부터 빌드
        generateImagesAsync(userNo, chatId, videoId, scenes, progress);

        return ContentDto.ImagesResponse.builder()
                .chatId(chatId)
                .totalCount(scenes.size())
                .completedCount(0)
                .downloadReady(false)
                .progressMessage("이미지 생성을 시작합니다...")
                .build();
    }

    @Async
    @Transactional
    protected void generateImagesAsync(Long userNo, Long chatId, Long videoId, List<Scene> scenes, ProgressInfo progress) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path imageDir = getImageDir(chatId);
            Files.createDirectories(imageDir);

            // v2.5.8: DB에서 전체 시나리오 컨텍스트 로드 (필수!)
            ScenarioContext scenarioContext = buildScenarioContext(videoId);

            // v2.8.0: Video에서 creatorId 조회 (필수 - 장르 선택 필수화)
            Video video = videoMapper.findById(videoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + videoId));
            Long creatorId = video.getCreatorId();
            if (creatorId == null) {
                log.warn("[ContentService] ⚠️ creatorId is null for videoId={}. Using default genre 1 (legacy data).", videoId);
                creatorId = 1L;
            }

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[generateImagesAsync] formatId set: {}", formatId);

            // SlideScene 리스트 변환
            List<VideoDto.SlideScene> slides = scenes.stream()
                    .map(s -> VideoDto.SlideScene.builder()
                            .order(s.getSceneOrder())
                            .imagePrompt(s.getPrompt())
                            .narration(s.getNarration())
                            .durationSeconds(s.getDuration())
                            .build())
                    .collect(Collectors.toList());

            // v2.8.0: 이미지 생성 서비스 호출 (ScenarioContext + creatorId 포함)
            List<String> imagePaths = imageGeneratorService.generateImages(userNo, slides, QualityTier.PREMIUM, scenarioContext, creatorId);

            // 생성된 이미지를 콘텐츠 디렉토리로 복사 및 DB 업데이트
            for (int i = 0; i < imagePaths.size() && i < scenes.size(); i++) {
                String srcPath = imagePaths.get(i);
                Scene scene = scenes.get(i);

                if (srcPath != null && !srcPath.startsWith("ERROR")) {
                    Path destPath = imageDir.resolve("slide_" + scene.getSceneOrder() + ".png");
                    Files.copy(Paths.get(srcPath), destPath, StandardCopyOption.REPLACE_EXISTING);

                    // S3에 업로드 (활성화된 경우)
                    if (storageService.isEnabled()) {
                        String s3Key = getS3Key(chatId, "images/slide_" + scene.getSceneOrder() + ".png");
                        byte[] imageData = Files.readAllBytes(destPath);
                        storageService.upload(s3Key, imageData, "image/png");
                        log.debug("Image uploaded to S3: {}", s3Key);
                    }

                    // 씬 미디어 URL 업데이트
                    sceneMapper.updateMediaUrl(scene.getSceneId(), destPath.toString(), "COMPLETED");

                    progress.completedCount++;
                    progress.message = String.format("이미지 생성 중... (%d/%d)", progress.completedCount, progress.totalCount);
                } else {
                    sceneMapper.updateMediaStatus(scene.getSceneId(), "FAILED");
                }
            }

            // 이미지 ZIP 파일을 S3에 업로드
            if (storageService.isEnabled()) {
                Path zipPath = createImagesZipFromScenes(chatId);
                byte[] zipData = Files.readAllBytes(zipPath);
                String zipKey = getS3Key(chatId, "images.zip");
                storageService.upload(zipKey, zipData, "application/zip");
                log.info("Images ZIP uploaded to S3: {}", zipKey);
            }

            progress.status = "completed";
            progress.message = "이미지 생성 완료!";

            // Video 상태 업데이트
            videoMapper.updateProgress(videoId, 40, "IMAGES");

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "IMAGES_DONE");

        } catch (Exception e) {
            log.error("[ContentService] Image generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "이미지 생성 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    public ContentDto.ImagesResponse getImagesProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        if (progress == null || !"images".equals(progress.processType)) {
            return ContentDto.ImagesResponse.builder()
                    .chatId(chatId)
                    .downloadReady(checkImagesReady(chatId))
                    .progressMessage("대기 중")
                    .build();
        }

        return ContentDto.ImagesResponse.builder()
                .chatId(chatId)
                .totalCount(progress.totalCount)
                .completedCount(progress.completedCount)
                .downloadReady("completed".equals(progress.status))
                .progressMessage(progress.message)
                .build();
    }

    public ContentDto.DownloadInfo getImagesDownload(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // S3에서 다운로드 URL 생성
        if (storageService.isEnabled()) {
            String zipKey = getS3Key(chatId, "images.zip");
            if (storageService.exists(zipKey)) {
                String presignedUrl = storageService.generatePresignedUrl(zipKey);
                return ContentDto.DownloadInfo.builder()
                        .filename("images_" + chatId + ".zip")
                        .contentType("application/zip")
                        .downloadUrl(presignedUrl)
                        .build();
            }
        }

        // v2.6.0: Scene 테이블의 image_url 기반으로 ZIP 생성
        Path zipPath = createImagesZipFromScenes(chatId);

        return ContentDto.DownloadInfo.builder()
                .filename("images_" + chatId + ".zip")
                .contentType("application/zip")
                .fileSize(zipPath.toFile().length())
                .build();
    }

    public Resource getImagesResource(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // v2.6.0: Scene 기반 ZIP 생성 후 반환
        Path zipPath = createImagesZipFromScenes(chatId);
        return new FileSystemResource(zipPath);
    }

    // ========== 오디오 ==========

    @Transactional
    public ContentDto.AudioResponse generateAudio(Long userNo, Long chatId) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        // Video 조회
        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 씬 조회 (슬라이드만)
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(videoId).stream()
                .filter(s -> "SLIDE".equals(s.getSceneType()))
                .collect(Collectors.toList());

        // v2.9.55: 예상 총 씬 개수 = 슬라이드 + 썸네일(+1)
        int expectedTotalScenes = scenes.size() + 1;

        log.info("[ContentService] Generating audio for chat: {}, slides: {}, expected total scenes (including thumbnail): {}",
                chatId, scenes.size(), expectedTotalScenes);

        // 진행 상태 초기화 (썸네일 씬 포함)
        ProgressInfo progress = new ProgressInfo("audio", expectedTotalScenes);
        progressStore.put(chatId, progress);

        // 오디오 생성 (비동기) - userNo 전달
        generateAudioAsync(userNo, chatId, videoId, scenes, progress);

        return ContentDto.AudioResponse.builder()
                .chatId(chatId)
                .totalCount(expectedTotalScenes)
                .completedCount(0)
                .downloadReady(false)
                .progressMessage("나레이션 생성을 시작합니다...")
                .build();
    }

    @Async
    @Transactional
    protected void generateAudioAsync(Long userNo, Long chatId, Long videoId, List<Scene> scenes, ProgressInfo progress) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path audioDir = getAudioDir(chatId);
            Files.createDirectories(audioDir);

            // v2.8.2: Video에서 creatorId 조회 (TTS_INSTRUCTION 적용용)
            Video video = videoMapper.findById(videoId).orElse(null);
            Long creatorId = (video != null && video.getCreatorId() != null) ? video.getCreatorId() : CreatorConfigService.DEFAULT_CREATOR_ID;
            log.info("[generateAudioAsync] Using creatorId: {} for TTS_INSTRUCTION", creatorId);

            List<String> audioPaths = new ArrayList<>();
            int totalDuration = 0;

            for (int i = 0; i < scenes.size(); i++) {
                Scene scene = scenes.get(i);
                progress.message = String.format("나레이션 생성 중... (%d/%d)", i + 1, scenes.size());

                // v2.8.2: creatorId 전달하여 TTS_INSTRUCTION 적용
                String audioPath = ttsService.generateNarration(userNo, scene.getNarration(), QualityTier.PREMIUM, creatorId);

                if (audioPath != null) {
                    // v2.9.6: 오프닝 씬은 정확히 8초에 맞춤 (Veo 영상 길이)
                    if ("OPENING".equals(scene.getSceneType())) {
                        double openingDuration = 8.0;
                        String adjustedPath = ttsService.adjustAudioTempo(audioPath, openingDuration);
                        if (adjustedPath != null && !adjustedPath.equals(audioPath)) {
                            log.info("[generateAudioAsync] ✅ Opening TTS tempo adjusted to {}s", openingDuration);
                            audioPath = adjustedPath;
                        }
                    }

                    Path destPath = audioDir.resolve("slide_" + scene.getSceneOrder() + ".mp3");
                    Files.copy(Paths.get(audioPath), destPath, StandardCopyOption.REPLACE_EXISTING);
                    audioPaths.add(destPath.toString());

                    // ⚠️ 핵심: 실제 오디오 길이 측정 및 Scene duration 업데이트
                    double actualDuration = ttsService.getAudioDuration(destPath.toString());
                    int durationSeconds = (int) Math.ceil(actualDuration);
                    // v2.9.10: 오프닝은 8초 고정, 슬라이드는 3-60초 범위 (TTS 길이에 따라 동적)
                    if ("OPENING".equals(scene.getSceneType())) {
                        durationSeconds = 8;
                    } else {
                        durationSeconds = Math.max(3, Math.min(6000, durationSeconds));  // v2.9.76: 180초→6000초 (100분, 나레이션 길이 무제한)
                    }

                    // Scene duration 업데이트 (자막 싱크용)
                    scene.setDuration(durationSeconds);
                    sceneUpdateService.updateDuration(scene.getSceneId(), durationSeconds);

                    totalDuration += durationSeconds;
                    log.info("[TTS SYNC] Scene {} - narration: {}자, audio: {}초 -> duration: {}초",
                            i + 1, scene.getNarration().length(), String.format("%.1f", actualDuration), durationSeconds);
                }

                progress.completedCount++;
            }

            // 전체 오디오 병합
            Path mergedPath = audioDir.resolve("narration_full.mp3");
            if (audioPaths.size() > 1) {
                mergeAudioFiles(audioPaths, mergedPath.toString());
            } else if (audioPaths.size() == 1) {
                // 단일 파일이면 복사
                Files.copy(Paths.get(audioPaths.get(0)), mergedPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // S3에 업로드 (활성화된 경우) - v2.9.8: 스트리밍 업로드로 OOM 방지
            if (storageService.isEnabled() && Files.exists(mergedPath)) {
                String audioKey = getS3Key(chatId, "narration.mp3");
                long fileSize = Files.size(mergedPath);
                try (InputStream is = Files.newInputStream(mergedPath)) {
                    storageService.upload(audioKey, is, "audio/mpeg", fileSize);
                }
                log.info("Audio uploaded to S3: {} ({} bytes)", audioKey, fileSize);
            }

            progress.status = "completed";
            progress.message = "나레이션 생성 완료!";
            progress.totalDuration = totalDuration;

            // Video 상태 업데이트
            videoMapper.updateProgress(videoId, 70, "AUDIO");

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "AUDIO_DONE");

        } catch (Exception e) {
            log.error("[ContentService] Audio generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "나레이션 생성 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    public ContentDto.AudioResponse getAudioProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        if (progress == null || !"audio".equals(progress.processType)) {
            return ContentDto.AudioResponse.builder()
                    .chatId(chatId)
                    .downloadReady(checkAudioReady(chatId))
                    .progressMessage("대기 중")
                    .build();
        }

        return ContentDto.AudioResponse.builder()
                .chatId(chatId)
                .totalCount(progress.totalCount)
                .completedCount(progress.completedCount)
                .downloadReady("completed".equals(progress.status))
                .totalDuration(progress.totalDuration)
                .progressMessage(progress.message)
                .build();
    }

    public ContentDto.DownloadInfo getAudioDownload(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // S3에서 다운로드 URL 생성
        if (storageService.isEnabled()) {
            String audioKey = getS3Key(chatId, "narration.mp3");
            if (storageService.exists(audioKey)) {
                String presignedUrl = storageService.generatePresignedUrl(audioKey);
                return ContentDto.DownloadInfo.builder()
                        .filename("narration_" + chatId + ".mp3")
                        .contentType("audio/mpeg")
                        .downloadUrl(presignedUrl)
                        .build();
            }
        }

        // v2.6.0: Scene 테이블의 audio_url 기반으로 병합 파일 생성
        Path audioPath = createMergedAudioFromScenes(chatId);
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "오디오 파일이 존재하지 않습니다. TTS 생성을 먼저 완료해주세요.");
        }

        return ContentDto.DownloadInfo.builder()
                .filename("narration_" + chatId + ".mp3")
                .contentType("audio/mpeg")
                .fileSize(audioPath.toFile().length())
                .build();
    }

    public Resource getAudioResource(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // v2.6.0: Scene 기반 병합 오디오 생성 후 반환
        Path audioPath = createMergedAudioFromScenes(chatId);
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "오디오 파일이 존재하지 않습니다. TTS 생성을 먼저 완료해주세요.");
        }
        return new FileSystemResource(audioPath);
    }

    // ========== 영상 ==========

    @Transactional
    public ContentDto.VideoResponse generateVideo(Long userNo, Long chatId, ContentDto.VideoRequest request) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.89: 이미 영상이 완료된 경우 재생성 방지
        String currentStep = conversation.getCurrentStep();
        if ("VIDEO_DONE".equals(currentStep)) {
            log.info("[ContentService] v2.9.89: Video already completed for chat: {}, skipping regeneration", chatId);
            return ContentDto.VideoResponse.builder()
                    .chatId(chatId)
                    .status("completed")
                    .progress(100)
                    .progressMessage("이미 영상이 생성되어 있습니다.")
                    .downloadReady(true)
                    .build();
        }

        // v2.9.89: S3에 영상이 이미 존재하는 경우도 체크
        if (storageService.isEnabled()) {
            String videoKey = getS3Key(chatId, "video.mp4");
            if (storageService.exists(videoKey)) {
                log.info("[ContentService] v2.9.89: Video already exists in S3 for chat: {}, updating status", chatId);
                conversationMapper.updateCurrentStep(chatId, "VIDEO_DONE");
                return ContentDto.VideoResponse.builder()
                        .chatId(chatId)
                        .status("completed")
                        .progress(100)
                        .progressMessage("영상이 이미 존재합니다. 상태를 복구했습니다.")
                        .downloadReady(true)
                        .build();
            }
        }

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        // Video 조회
        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        log.info("[ContentService] Generating video for chat: {}", chatId);

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("video", 100);
        progressStore.put(chatId, progress);

        // 영상 생성 (비동기) - userNo 전달하여 사용자 API 키 사용
        boolean includeSubtitle = request != null && request.isIncludeSubtitle();
        generateVideoAsync(userNo, chatId, videoId, includeSubtitle, progress);

        return ContentDto.VideoResponse.builder()
                .chatId(chatId)
                .status("processing")
                .progress(0)
                .downloadReady(false)
                .progressMessage("영상 합성을 시작합니다...")
                .build();
    }

    @Async
    @Transactional
    protected void generateVideoAsync(Long userNo, Long chatId, Long videoId, boolean includeSubtitle, ProgressInfo progress) {
        try {
            // v2.9.92: VIDEO_GENERATING을 비동기 메서드 시작 시 설정
            conversationMapper.updateCurrentStep(chatId, "VIDEO_GENERATING");
            log.info("[v2.9.92] VIDEO_GENERATING status set for chatId: {} (legacy async method start)", chatId);

            Path videoDir = getVideoDir(chatId);
            Files.createDirectories(videoDir);

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Video video = videoMapper.findById(videoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + videoId));
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[ContentService] formatId set for final video: {}", formatId);

            // 모든 씬 조회 (OPENING + SLIDE) - scene_video_url이 있는 씬들
            List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

            // v2.9.59: 썸네일 씬 생성 (최종 합성 직전 - Lock Wait 문제 해결)
            // - ThumbnailService 트랜잭션과 동시 실행 충돌 방지
            // - TTS 완료 후 안전하게 썸네일 씬 생성
            boolean hasThumbnailScene = allScenes.stream()
                    .anyMatch(s -> "THUMBNAIL".equals(s.getSceneType()));
            if (!hasThumbnailScene) {
                try {
                    log.info("[v2.9.59] Creating thumbnail scene before final video composition");
                    // v2.9.59: 사용자에게 썸네일 씬 생성 중임을 알림
                    progress.message = "썸네일 2초 영상 생성 중...";
                    progress.completedCount = 0;

                    Path sceneDir = getSceneDir(chatId);
                    int regularSceneCount = (int) allScenes.stream()
                            .filter(s -> !"THUMBNAIL".equals(s.getSceneType()))
                            .count();

                    // 썸네일 씬 생성 (새 트랜잭션)
                    createThumbnailSceneInNewTransaction(chatId, videoId, sceneDir, regularSceneCount, progress);

                    // 씬 목록 재조회 (썸네일 씬 포함)
                    allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);
                    log.info("[v2.9.59] ✅ Thumbnail scene added, total scenes now: {}", allScenes.size());
                } catch (Exception e) {
                    log.warn("[v2.9.59] ⚠️ Thumbnail scene creation failed (non-critical): {}", e.getMessage());
                    // 썸네일 없이 계속 진행
                }
            } else {
                log.info("[v2.9.59] Thumbnail scene already exists, skipping creation");
            }

            log.info("[ContentService] ===== 최종 영상 합성 시작 (v2.5.3 - Scene Concat 방식) =====");
            log.info("[ContentService] Chat: {}, Video: {}, Total scenes: {}", chatId, videoId, allScenes.size());

            // 씬 영상 파일들 수집 (scene_video_url 사용) - v2.6.0: S3 URL 지원
            List<Path> sceneVideoPaths = new ArrayList<>();
            for (int i = 0; i < allScenes.size(); i++) {
                Scene scene = allScenes.get(i);
                String sceneVideoUrl = scene.getSceneVideoUrl();
                if (sceneVideoUrl == null || sceneVideoUrl.isEmpty()) {
                    log.error("[ContentService] ❌ Scene {} (order: {}) has no scene_video_url!",
                            scene.getSceneId(), scene.getSceneOrder());
                    throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "씬 영상이 생성되지 않았습니다. TTS/자막 생성을 먼저 완료해주세요.");
                }

                Path videoPath;
                // v2.6.1: S3 key 또는 URL 처리
                if (sceneVideoUrl.startsWith("content/")) {
                    // S3 key인 경우 - 새로운 presigned URL 생성하여 다운로드
                    progress.message = String.format("씬 영상 준비 중... (%d/%d)", i + 1, allScenes.size());
                    String filename = "scene_" + scene.getSceneOrder() + "_" + scene.getSceneId() + ".mp4";
                    videoPath = videoDir.resolve(filename);
                    try {
                        String presignedUrl = storageService.generatePresignedUrl(sceneVideoUrl);
                        log.info("[ContentService] Generated fresh presigned URL for S3 key: {}", sceneVideoUrl);
                        downloadFromUrl(presignedUrl, videoPath);
                        log.info("[ContentService] Downloaded scene video from S3: {} -> {}", sceneVideoUrl, videoPath);
                    } catch (Exception e) {
                        log.error("[ContentService] Failed to download scene video (S3 key: {}): {}", sceneVideoUrl, e.getMessage());
                        throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                            "씬 영상 다운로드 실패: " + scene.getSceneOrder());
                    }
                } else if (sceneVideoUrl.startsWith("http://") || sceneVideoUrl.startsWith("https://")) {
                    // 기존 presigned URL인 경우 (하위 호환성) - 만료 가능성 있음
                    progress.message = String.format("씬 영상 준비 중... (%d/%d)", i + 1, allScenes.size());
                    String filename = "scene_" + scene.getSceneOrder() + "_" + scene.getSceneId() + ".mp4";
                    videoPath = videoDir.resolve(filename);
                    try {
                        downloadFromUrl(sceneVideoUrl, videoPath);
                        log.info("[ContentService] Downloaded scene video from presigned URL: {} -> {}", sceneVideoUrl, videoPath);
                    } catch (Exception e) {
                        log.error("[ContentService] Failed to download scene video (presigned URL expired?): {}", sceneVideoUrl, e);
                        throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                            "씬 영상 다운로드 실패 (URL 만료 가능): " + scene.getSceneOrder() + " - TTS/자막을 다시 생성해주세요.");
                    }
                } else {
                    // 로컬 파일 경로
                    videoPath = Path.of(sceneVideoUrl);
                }

                if (!Files.exists(videoPath)) {
                    log.error("[ContentService] ❌ Scene video file not found: {}", videoPath);
                    throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "씬 영상 파일을 찾을 수 없습니다: " + scene.getSceneOrder());
                }

                sceneVideoPaths.add(videoPath);
                log.info("[ContentService] ✅ Scene {} ({}): {} ({} bytes)",
                        scene.getSceneOrder(), scene.getSceneType(),
                        videoPath, Files.size(videoPath));
            }

            if (sceneVideoPaths.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "합성할 씬 영상이 없습니다. TTS/자막 생성을 먼저 완료해주세요.");
            }

            progress.message = "씬 영상들을 합성 중...";
            progress.completedCount = 30;

            // FFmpeg concat으로 최종 영상 합성
            Path outputPath = videoDir.resolve("video_" + chatId + ".mp4");
            concatSceneVideos(sceneVideoPaths, outputPath);

            log.info("[ContentService] ✅ Final video created: {} ({} bytes)",
                    outputPath, Files.size(outputPath));

            // S3에 업로드 (활성화된 경우) - v2.9.8: 스트리밍 업로드로 OOM 방지
            String videoKey = null;  // v2.9.39: S3 키 저장
            if (storageService.isEnabled() && Files.exists(outputPath)) {
                progress.message = "영상을 클라우드에 업로드 중...";
                videoKey = getS3Key(chatId, "video.mp4");
                long fileSize = Files.size(outputPath);
                try (InputStream is = Files.newInputStream(outputPath)) {
                    storageService.upload(videoKey, is, "video/mp4", fileSize);
                }
                log.info("Video uploaded to S3: {} (size: {} bytes)", videoKey, fileSize);
            }

            progress.status = "completed";
            progress.completedCount = 100;
            progress.message = "영상 생성 완료!";
            progress.filePath = outputPath.toString();

            // v2.9.52: Video 상태 업데이트 - thumbnailUrl 유지 (레거시 메서드 수정)
            Video currentVideo = videoMapper.findById(videoId).orElse(null);
            String existingThumbnailUrl = (currentVideo != null) ? currentVideo.getThumbnailUrl() : null;
            String finalVideoPath = (videoKey != null) ? videoKey : outputPath.toString();
            videoMapper.updateAsCompleted(videoId, finalVideoPath, existingThumbnailUrl);

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "VIDEO_DONE");
            log.info("[v2.9.92] ✅ VIDEO_DONE status set for chatId: {} (legacy generateVideoAsync)", chatId);

            // v2.9.27: 최종 영상 결과를 채팅 메시지로 저장
            try {
                Video completedVideo = videoMapper.findById(videoId).orElse(null);
                String videoUrl = (storageService.isEnabled() && videoKey != null)
                    ? storageService.generatePresignedUrl(videoKey)  // v2.9.39: 실제 업로드 키 사용
                    : "local://" + outputPath.toString();

                String metadata = objectMapper.writeValueAsString(Map.of(
                    "videoUrl", videoUrl,
                    "title", completedVideo != null ? completedVideo.getTitle() : "Untitled",
                    "duration", progress.totalDuration
                ));

                ConversationMessage message = ConversationMessage.builder()
                    .conversationId(chatId)
                    .role("assistant")
                    .content("최종 영상이 완성되었습니다!")
                    .messageType("VIDEO_RESULT")
                    .metadata(metadata)
                    .build();

                messageMapper.insert(message);
                log.info("[ContentService] Saved video result message for chat: {}", chatId);
            } catch (Exception e1) {
                log.error("[ContentService] Failed to save video result message", e1);
                // 메시지 저장 실패해도 영상 생성은 성공
            }

        } catch (Exception e) {
            log.error("[ContentService] Video generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "영상 생성 실패: " + e.getMessage();
            videoMapper.updateAsFailed(videoId, e.getMessage());
        }
    }

    public ContentDto.VideoResponse getVideoProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        if (progress == null || !"video".equals(progress.processType)) {
            return ContentDto.VideoResponse.builder()
                    .chatId(chatId)
                    .downloadReady(checkVideoReady(chatId))
                    .progressMessage("대기 중")
                    .build();
        }

        return ContentDto.VideoResponse.builder()
                .chatId(chatId)
                .status(progress.status)
                .progress(progress.completedCount)
                .downloadReady("completed".equals(progress.status))
                .progressMessage(progress.message)
                .filePath(progress.filePath)
                .build();
    }

    public ContentDto.DownloadInfo getVideoDownload(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        // v2.9.38: DB에서 video 조회하여 만료 시간 가져오기
        Conversation conversation = conversationMapper.findById(chatId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "대화를 찾을 수 없습니다."));
        Video video = videoMapper.findByConversationId(chatId).stream().findFirst().orElse(null);
        LocalDateTime expiresAt = video != null ? video.getPresignedUrlExpiresAt() : null;

        // S3에서 다운로드 URL 생성
        if (storageService.isEnabled()) {
            String videoKey = getS3Key(chatId, "video.mp4");
            if (storageService.exists(videoKey)) {
                String presignedUrl = storageService.generatePresignedUrl(videoKey);
                return ContentDto.DownloadInfo.builder()
                        .filename("video_" + chatId + ".mp4")
                        .contentType("video/mp4")
                        .downloadUrl(presignedUrl)
                        .presignedUrlExpiresAt(expiresAt)  // v2.9.38: 만료 시간 포함
                        .build();
            }
        }

        // 로컬 파일 폴백
        Path videoPath = getVideoFilePath(chatId);
        if (!Files.exists(videoPath)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "영상 파일이 존재하지 않습니다.");
        }

        return ContentDto.DownloadInfo.builder()
                .filename("video_" + chatId + ".mp4")
                .contentType("video/mp4")
                .fileSize(videoPath.toFile().length())
                .build();
    }

    public Resource getVideoResource(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);
        Path videoPath = getVideoFilePath(chatId);
        return new FileSystemResource(videoPath);
    }

    // ========== 공통 ==========

    public ContentDto.ProgressResponse getProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        if (progress == null) {
            return ContentDto.ProgressResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .message("대기 중")
                    .build();
        }

        return ContentDto.ProgressResponse.builder()
                .chatId(chatId)
                .processType(progress.processType)
                .status(progress.status)
                .progress(progress.totalCount > 0 ? (progress.completedCount * 100 / progress.totalCount) : 0)
                .message(progress.message)
                .currentIndex(progress.completedCount)
                .totalCount(progress.totalCount)
                .build();
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

    private Video getVideoByConversationId(Long chatId) {
        List<Video> videos = videoMapper.findByConversationId(chatId);
        if (videos.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "시나리오가 먼저 생성되어야 합니다.");
        }
        return videos.get(0); // 가장 최근 비디오 반환
    }

    private String buildConversationText(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private String convertScenarioToJson(VideoDto.ScenarioInfo scenarioInfo) {
        try {
            return objectMapper.writeValueAsString(scenarioInfo);
        } catch (Exception e) {
            log.error("Failed to convert scenario to JSON", e);
            return "{}";
        }
    }

    private VideoDto.ScenarioInfo buildScenarioInfo(Scenario scenario, List<Scene> slideScenes, Scene openingScene) {
        List<VideoDto.SlideScene> slides = slideScenes.stream()
                .map(s -> VideoDto.SlideScene.builder()
                        .order(s.getSceneOrder())
                        .imagePrompt(s.getPrompt())
                        .narration(s.getNarration())
                        .durationSeconds(s.getDuration())
                        .build())
                .collect(Collectors.toList());

        // 오프닝 씬 생성 + ⚠️ 필수 검증 (폴백 절대 금지!)
        VideoDto.OpeningScene opening = null;
        if (openingScene != null) {
            String prompt = openingScene.getPrompt();

            // ⚠️ 오프닝 프롬프트 필수 검증
            if (prompt == null || prompt.trim().isEmpty()) {
                log.error("[ContentService] ❌ CRITICAL: Opening scene exists but prompt is null/empty! sceneId: {}", openingScene.getSceneId());
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "오프닝 씬은 있지만 프롬프트가 없습니다. 시나리오를 다시 생성해주세요. (sceneId: " + openingScene.getSceneId() + ")");
            }

            opening = VideoDto.OpeningScene.builder()
                    .videoPrompt(prompt.trim())
                    .narration(openingScene.getNarration())
                    .durationSeconds(openingScene.getDuration() != null ? openingScene.getDuration() : 8)
                    .build();
            log.info("[ContentService] ✅ Built OpeningScene - prompt length: {}, narration: {}, duration: {}s",
                    opening.getVideoPrompt().length(),
                    opening.getNarration() != null ? opening.getNarration().substring(0, Math.min(30, opening.getNarration().length())) + "..." : "null",
                    opening.getDurationSeconds());
        }

        return VideoDto.ScenarioInfo.builder()
                .scenarioId(scenario.getScenarioId())
                .characterBlock(scenario.getCharacterBlock())
                .opening(opening)
                .slides(slides)
                .build();
    }

    private void saveScenarioToFile(Long chatId, VideoDto.ScenarioInfo scenario) {
        try {
            StringBuilder content = new StringBuilder();
            content.append("=== ").append(scenario.getTitle()).append(" ===\n\n");
            content.append("설명: ").append(scenario.getDescription()).append("\n\n");

            if (scenario.getOpening() != null) {
                content.append("=== 오프닝 ===\n");
                content.append("나레이션: ").append(scenario.getOpening().getNarration()).append("\n");
                content.append("프롬프트: ").append(scenario.getOpening().getVideoPrompt()).append("\n\n");
            }

            content.append("=== 슬라이드 ===\n\n");

            if (scenario.getSlides() != null) {
                for (int i = 0; i < scenario.getSlides().size(); i++) {
                    VideoDto.SlideScene slide = scenario.getSlides().get(i);
                    content.append("--- 슬라이드 ").append(i + 1).append(" ---\n");
                    content.append("나레이션: ").append(slide.getNarration()).append("\n");
                    content.append("이미지: ").append(slide.getImagePrompt()).append("\n");
                    content.append("길이: ").append(slide.getDurationSeconds()).append("초\n\n");
                }
            }

            String textContent = content.toString();

            // S3에 업로드 (활성화된 경우)
            if (storageService.isEnabled()) {
                String s3Key = getS3Key(chatId, "scenario.txt");
                storageService.upload(s3Key, textContent.getBytes("UTF-8"), "text/plain; charset=UTF-8");
                log.info("Scenario uploaded to S3: {}", s3Key);
            }

            // 로컬에도 저장 (백업용)
            Path dir = Path.of(CONTENT_DIR, String.valueOf(chatId));
            Files.createDirectories(dir);
            Path filePath = dir.resolve("scenario.txt");
            Files.writeString(filePath, textContent);

        } catch (IOException e) {
            log.error("Failed to save scenario file", e);
        }
    }

    private Path getScenarioFilePath(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "scenario.txt");
    }

    /**
     * S3 키 생성 (chatId/filename 형식)
     */
    private String getS3Key(Long chatId, String filename) {
        return "content/" + chatId + "/" + filename;
    }

    /**
     * v2.5.8: DB에서 시나리오 정보를 로드하여 ScenarioContext 구성
     * 이미지/영상 생성 시 전체 스토리 컨텍스트를 제공하기 위해 사용
     *
     * @param videoId 비디오 ID
     * @return ScenarioContext (필수 - null이면 에러)
     * @throws ApiException 시나리오가 없거나 characterBlock이 없으면 에러 발생
     */
    private ScenarioContext buildScenarioContext(Long videoId) {
        log.info("[ContentService] Building ScenarioContext from DB for videoId: {}", videoId);

        // 1. Scenario 조회 (필수!)
        Scenario scenario = scenarioMapper.findByVideoId(videoId).orElse(null);
        if (scenario == null) {
            log.error("❌ [치명적 오류] 시나리오가 없습니다! videoId: {}", videoId);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "시나리오가 없습니다. 시나리오를 먼저 생성해주세요. (videoId: " + videoId + ")");
        }

        // 2. Video 조회 (title, description, creatorId)
        Video video = videoMapper.findById(videoId).orElse(null);
        String title = (video != null && video.getTitle() != null) ? video.getTitle() : "Untitled";
        String hook = (video != null && video.getDescription() != null) ? video.getDescription() : "";
        Long creatorId = (video != null) ? video.getCreatorId() : null;

        // 3. characterBlock 검증 - v2.8.5: 장르에 따라 필수 여부 결정
        String characterBlock = scenario.getCharacterBlock();
        boolean isCharacterRequired = isCharacterRequiredForCreator(creatorId);
        boolean hasCharacterBlock = characterBlock != null && !characterBlock.trim().isEmpty();

        if (isCharacterRequired && !hasCharacterBlock) {
            log.error("❌ [치명적 오류] characterBlock이 없습니다! videoId: {}, creatorId: {}", videoId, creatorId);
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                "캐릭터 정보가 없습니다. 시나리오를 다시 생성해주세요. (characterBlock is required)");
        }
        if (!hasCharacterBlock) {
            log.info("[ContentService] creatorId={} - 비-캐릭터 장르, characterBlock 검증 건너뜀", creatorId);
            characterBlock = "";  // null 대신 빈 문자열로 설정
        }

        // 4. 전체 씬 조회 (storyOutline 구성)
        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);
        int totalSlides = (int) allScenes.stream()
                .filter(s -> "SLIDE".equals(s.getSceneType()))
                .count();

        // 5. storyOutline 구성 (모든 씬의 나레이션 조합)
        StringBuilder storyOutline = new StringBuilder();
        for (Scene scene : allScenes) {
            String sceneType = scene.getSceneType();
            int order = scene.getSceneOrder();
            String narration = scene.getNarration();

            if ("OPENING".equals(sceneType)) {
                storyOutline.append("[OPENING] ").append(narration != null ? narration : "(no narration)").append("\n\n");
            } else if ("SLIDE".equals(sceneType)) {
                storyOutline.append("[SCENE ").append(order).append("] ").append(narration != null ? narration : "(no narration)").append("\n\n");
            }
        }

        // v2.9.90: 참조 이미지 데이터 조회 (다중 이미지 멀티모달 지원)
        List<String> referenceImagesBase64 = new ArrayList<>();
        List<String> referenceImagesMimeTypes = new ArrayList<>();
        String referenceImageAnalysis = null;

        if (video != null && video.getConversationId() != null) {
            Conversation conversation = conversationMapper.findById(video.getConversationId()).orElse(null);
            if (conversation != null && conversation.getReferenceImageUrl() != null && !conversation.getReferenceImageUrl().isEmpty()) {
                // v2.9.90: 쉼표로 구분된 다중 이미지 URL 처리
                String fullReferenceUrl = conversation.getReferenceImageUrl();
                String[] imageUrls = fullReferenceUrl.split(",");

                log.info("[ContentService] v2.9.90: Loading {} reference images for multimodal - conversationId: {}",
                        imageUrls.length, video.getConversationId());

                for (int i = 0; i < imageUrls.length; i++) {
                    String s3Key = imageUrls[i].trim();
                    try {
                        log.info("[ContentService] v2.9.90: Loading reference image {}/{} - s3Key: {}",
                                i + 1, imageUrls.length, s3Key);

                        // S3에서 이미지 다운로드 및 Base64 인코딩
                        byte[] imageBytes = referenceImageService.downloadImage(s3Key);
                        if (imageBytes != null && imageBytes.length > 0) {
                            String base64 = referenceImageService.encodeToBase64(imageBytes);
                            referenceImagesBase64.add(base64);

                            // MIME 타입 추출 (확장자로 추정)
                            String mimeType;
                            if (s3Key.endsWith(".jpg") || s3Key.endsWith(".jpeg")) {
                                mimeType = "image/jpeg";
                            } else if (s3Key.endsWith(".png")) {
                                mimeType = "image/png";
                            } else if (s3Key.endsWith(".webp")) {
                                mimeType = "image/webp";
                            } else if (s3Key.endsWith(".gif")) {
                                mimeType = "image/gif";
                            } else {
                                mimeType = "image/jpeg";  // 기본값
                            }
                            referenceImagesMimeTypes.add(mimeType);

                            log.info("[ContentService] v2.9.90: Reference image {}/{} loaded - base64Length: {}, mimeType: {}",
                                    i + 1, imageUrls.length, base64.length(), mimeType);
                        }
                    } catch (Exception e) {
                        log.warn("[ContentService] v2.9.90: Failed to load reference image {}/{} (s3Key: {}): {}",
                                i + 1, imageUrls.length, s3Key, e.getMessage());
                        // 개별 이미지 실패 시 계속 진행 (다른 이미지는 처리)
                    }
                }

                referenceImageAnalysis = conversation.getReferenceImageAnalysis();
                log.info("[ContentService] v2.9.90: Total {} reference images loaded, hasAnalysis: {}",
                        referenceImagesBase64.size(), referenceImageAnalysis != null);
            }
        }

        ScenarioContext context = ScenarioContext.builder()
                .title(title)
                .hook(hook)
                .characterBlock(characterBlock)
                .totalSlides(totalSlides)
                .storyOutline(storyOutline.toString().trim())
                // v2.9.90: 참조 이미지 다중 멀티모달 지원
                .referenceImagesBase64(referenceImagesBase64.isEmpty() ? null : referenceImagesBase64)
                .referenceImagesMimeTypes(referenceImagesMimeTypes.isEmpty() ? null : referenceImagesMimeTypes)
                .referenceImageAnalysis(referenceImageAnalysis)
                .build();

        log.info("[ContentService] ✅ ScenarioContext built - title: {}, totalSlides: {}, characterBlockLength: {}, storyOutlineLength: {}, referenceImageCount: {}",
                title, totalSlides, characterBlock.length(), storyOutline.length(), referenceImagesBase64.size());

        return context;
    }

    /**
     * v2.9.77: DB에서 VideoDto.ScenarioInfo 구성 (오프닝 영상 생성용)
     * 전체 시나리오 정보(슬라이드 포함)를 VideoDto.ScenarioInfo 형태로 반환
     *
     * @param videoId 비디오 ID
     * @return VideoDto.ScenarioInfo (null 가능 - 에러 시)
     */
    /**
     * v2.9.162: ScenarioContext에서 이미 로드된 참조 이미지 데이터를 재사용하는 오버로드
     * S3 다운로드 중복 방지 (buildScenarioContext에서 이미 다운로드한 데이터 재사용)
     */
    private VideoDto.ScenarioInfo buildVideoScenarioInfo(Long videoId, ScenarioContext existingContext) {
        return buildVideoScenarioInfoInternal(videoId,
            existingContext != null ? existingContext.getReferenceImagesBase64() : null,
            existingContext != null ? existingContext.getReferenceImagesMimeTypes() : null,
            existingContext != null ? existingContext.getReferenceImageAnalysis() : null);
    }

    private VideoDto.ScenarioInfo buildVideoScenarioInfoInternal(Long videoId,
            List<String> preloadedImagesBase64, List<String> preloadedImagesMimeTypes, String preloadedImageAnalysis) {
        try {
            log.info("[ContentService] Building VideoDto.ScenarioInfo from DB for videoId: {}", videoId);

            // 1. Scenario 조회
            Scenario scenario = scenarioMapper.findByVideoId(videoId).orElse(null);
            if (scenario == null) {
                log.warn("[ContentService] Scenario not found for videoId: {}", videoId);
                return null;
            }

            // 2. Video 조회
            Video video = videoMapper.findById(videoId).orElse(null);
            String title = (video != null && video.getTitle() != null) ? video.getTitle() : "Untitled";
            String description = (video != null && video.getDescription() != null) ? video.getDescription() : "";

            // v2.9.162: 참조 이미지 - 이미 로드된 데이터가 있으면 재사용, 없으면 S3에서 다운로드
            String referenceImageAnalysis = preloadedImageAnalysis;
            List<String> referenceImagesBase64 = preloadedImagesBase64 != null ? preloadedImagesBase64 : new ArrayList<>();
            List<String> referenceImagesMimeTypes = preloadedImagesMimeTypes != null ? preloadedImagesMimeTypes : new ArrayList<>();

            boolean needsRefImageLoad = (preloadedImagesBase64 == null || preloadedImagesBase64.isEmpty());
            if (needsRefImageLoad && video != null && video.getConversationId() != null) {
                Conversation conversation = conversationMapper.findById(video.getConversationId()).orElse(null);
                if (conversation != null) {
                    // 참조 이미지 분석 결과 (텍스트)
                    if (referenceImageAnalysis == null && conversation.getReferenceImageAnalysis() != null) {
                        referenceImageAnalysis = conversation.getReferenceImageAnalysis();
                        log.info("[ContentService] v2.9.84: Reference image analysis found for videoId: {} - length: {}",
                                videoId, referenceImageAnalysis.length());
                    }

                    // v2.9.90: 참조 이미지 Base64 로드 (S3에서 다운로드)
                    String referenceImageUrl = conversation.getReferenceImageUrl();
                    if (referenceImageUrl != null && !referenceImageUrl.isEmpty()) {
                        String[] s3Keys = referenceImageUrl.split(",");
                        log.info("[ContentService] v2.9.90: Loading {} reference images from S3 for videoId: {}",
                                s3Keys.length, videoId);

                        referenceImagesBase64 = new ArrayList<>();
                        referenceImagesMimeTypes = new ArrayList<>();

                        for (String s3Key : s3Keys) {
                            s3Key = s3Key.trim();
                            if (s3Key.isEmpty()) continue;

                            try {
                                byte[] imageBytes = referenceImageService.downloadImage(s3Key);
                                if (imageBytes != null && imageBytes.length > 0) {
                                    String base64 = referenceImageService.encodeToBase64(imageBytes);
                                    referenceImagesBase64.add(base64);

                                    // MIME 타입 추론
                                    String mimeType = "image/jpeg";  // 기본값
                                    if (s3Key.toLowerCase().endsWith(".png")) {
                                        mimeType = "image/png";
                                    } else if (s3Key.toLowerCase().endsWith(".webp")) {
                                        mimeType = "image/webp";
                                    } else if (s3Key.toLowerCase().endsWith(".gif")) {
                                        mimeType = "image/gif";
                                    }
                                    referenceImagesMimeTypes.add(mimeType);
                                    log.info("[ContentService] v2.9.90: Loaded reference image: {} ({} bytes, {})",
                                            s3Key, imageBytes.length, mimeType);
                                }
                            } catch (Exception e) {
                                log.warn("[ContentService] v2.9.90: Failed to load reference image: {} - {}",
                                        s3Key, e.getMessage());
                            }
                        }
                        log.info("[ContentService] v2.9.90: Successfully loaded {} reference images for videoId: {}",
                                referenceImagesBase64.size(), videoId);
                    }
                }
            } else if (!needsRefImageLoad) {
                log.info("[ContentService] v2.9.162: Reusing {} preloaded reference images (skipping S3 download)",
                        referenceImagesBase64.size());
            }

            // 3. 전체 씬 조회
            List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

            // 4. OpeningScene 구성
            VideoDto.OpeningScene openingScene = null;
            for (Scene scene : allScenes) {
                if ("OPENING".equals(scene.getSceneType())) {
                    openingScene = VideoDto.OpeningScene.builder()
                            .videoPrompt(scene.getPrompt())
                            .narration(scene.getNarration())
                            .durationSeconds(scene.getDuration() != null ? scene.getDuration() : 8)
                            .build();
                    break;
                }
            }

            // 5. SlideScene 리스트 구성
            List<VideoDto.SlideScene> slideScenes = new java.util.ArrayList<>();
            for (Scene scene : allScenes) {
                if ("SLIDE".equals(scene.getSceneType())) {
                    slideScenes.add(VideoDto.SlideScene.builder()
                            .order(scene.getSceneOrder())
                            .imagePrompt(scene.getPrompt())
                            .narration(scene.getNarration())
                            .durationSeconds(scene.getDuration() != null ? scene.getDuration() : 10)
                            .build());
                }
            }

            // 6. fullNarration 구성 (모든 나레이션 조합)
            StringBuilder fullNarration = new StringBuilder();
            for (Scene scene : allScenes) {
                if (scene.getNarration() != null && !scene.getNarration().isEmpty()) {
                    fullNarration.append(scene.getNarration()).append("\n\n");
                }
            }

            // 7. ScenarioInfo 구성
            VideoDto.ScenarioInfo scenarioInfo = VideoDto.ScenarioInfo.builder()
                    .scenarioId(scenario.getScenarioId())
                    .title(title)
                    .description(description)
                    .characterBlock(scenario.getCharacterBlock())
                    .opening(openingScene)
                    .slides(slideScenes)
                    .fullNarration(fullNarration.toString().trim())
                    // v2.9.84: 참조 이미지 분석 결과
                    .referenceImageAnalysis(referenceImageAnalysis)
                    // v2.9.90: 참조 이미지 멀티모달 지원 (Veo 3.1 최대 3개, Gemini Image 최대 14개)
                    .referenceImagesBase64(referenceImagesBase64.isEmpty() ? null : referenceImagesBase64)
                    .referenceImagesMimeTypes(referenceImagesMimeTypes.isEmpty() ? null : referenceImagesMimeTypes)
                    .build();

            log.info("[ContentService] ✅ VideoDto.ScenarioInfo built - title: {}, totalSlides: {}, hasOpening: {}, fullNarrationLength: {}, referenceImages: {}",
                    title, slideScenes.size(), openingScene != null, fullNarration.length(), referenceImagesBase64.size());

            return scenarioInfo;

        } catch (Exception e) {
            log.error("[ContentService] Failed to build VideoDto.ScenarioInfo for videoId: {}", videoId, e);
            return null;
        }
    }

    private Path getImageDir(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "images");
    }

    private Path getAudioDir(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "audio");
    }

    private Path getVideoDir(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "video");
    }

    private Path getImagesZipPath(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "images.zip");
    }

    private Path getMergedAudioPath(Long chatId) {
        return getAudioDir(chatId).resolve("narration_full.mp3");
    }

    private Path getVideoFilePath(Long chatId) {
        return getVideoDir(chatId).resolve("video_" + chatId + ".mp4");
    }

    private boolean checkImagesReady(Long chatId) {
        return Files.exists(getImageDir(chatId));
    }

    private boolean checkAudioReady(Long chatId) {
        return Files.exists(getMergedAudioPath(chatId));
    }

    /**
     * v2.9.89: 영상 완료 여부 확인 (로컬 또는 S3)
     * 서버 재시작 후에도 S3에 있는 영상을 인식하도록 개선
     */
    private boolean checkVideoReady(Long chatId) {
        // 로컬 파일 확인
        if (Files.exists(getVideoFilePath(chatId))) {
            return true;
        }
        // S3 확인
        if (storageService.isEnabled()) {
            String videoKey = getS3Key(chatId, "video.mp4");
            return storageService.exists(videoKey);
        }
        return false;
    }

    /**
     * v2.6.0: Scene 테이블의 image_url 기반으로 이미지 ZIP 생성
     */
    private Path createImagesZipFromScenes(Long chatId) {
        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        Path zipPath = getImagesZipPath(chatId);

        try {
            // ZIP 파일 부모 디렉토리 생성
            Files.createDirectories(zipPath.getParent());

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                int imageIndex = 1;
                for (Scene scene : scenes) {
                    String imageUrl = scene.getImageUrl();
                    if (imageUrl == null || imageUrl.isEmpty()) {
                        continue;
                    }

                    try {
                        byte[] imageData;
                        String extension = ".png";

                        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                            // S3 URL에서 다운로드
                            imageData = downloadFromUrl(imageUrl);
                            // URL에서 확장자 추출
                            if (imageUrl.contains(".jpg") || imageUrl.contains(".jpeg")) {
                                extension = ".jpg";
                            }
                        } else {
                            // 로컬 파일
                            Path imagePath = Path.of(imageUrl);
                            if (!Files.exists(imagePath)) {
                                log.warn("[Download] Local image file not found: {}", imageUrl);
                                continue;
                            }
                            imageData = Files.readAllBytes(imagePath);
                            if (imageUrl.endsWith(".jpg") || imageUrl.endsWith(".jpeg")) {
                                extension = ".jpg";
                            }
                        }

                        String filename = String.format("scene_%02d%s", imageIndex++, extension);
                        zos.putNextEntry(new ZipEntry(filename));
                        zos.write(imageData);
                        zos.closeEntry();

                        log.debug("[Download] Added image to ZIP: {}", filename);

                    } catch (Exception e) {
                        log.error("[Download] Failed to add image to ZIP: scene {}", scene.getSceneId(), e);
                    }
                }
            }

            log.info("[Download] Images ZIP created: {}", zipPath);
            return zipPath;

        } catch (IOException e) {
            log.error("[Download] Failed to create images ZIP for chat: {}", chatId, e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "이미지 ZIP 파일 생성 실패");
        }
    }

    /**
     * v2.6.0: Scene 테이블의 audio_url 기반으로 병합 오디오 생성
     */
    private Path createMergedAudioFromScenes(Long chatId) {
        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        Path audioDir = getAudioDir(chatId);
        Path mergedPath = audioDir.resolve("narration_merged_" + chatId + ".mp3");

        // 이미 병합 파일이 있으면 반환
        if (Files.exists(mergedPath)) {
            return mergedPath;
        }

        try {
            Files.createDirectories(audioDir);

            List<String> audioPaths = new ArrayList<>();
            int audioIndex = 1;

            for (Scene scene : scenes) {
                String audioUrl = scene.getAudioUrl();
                if (audioUrl == null || audioUrl.isEmpty()) {
                    continue;
                }

                try {
                    Path localAudioPath;

                    if (audioUrl.startsWith("http://") || audioUrl.startsWith("https://")) {
                        // S3 URL에서 다운로드
                        byte[] audioData = downloadFromUrl(audioUrl);
                        localAudioPath = audioDir.resolve(String.format("temp_scene_%02d.mp3", audioIndex++));
                        Files.write(localAudioPath, audioData);
                    } else {
                        // 로컬 파일
                        localAudioPath = Path.of(audioUrl);
                        if (!Files.exists(localAudioPath)) {
                            log.warn("[Download] Local audio file not found: {}", audioUrl);
                            continue;
                        }
                    }

                    audioPaths.add(localAudioPath.toString());
                    log.debug("[Download] Added audio to merge list: {}", localAudioPath);

                } catch (Exception e) {
                    log.error("[Download] Failed to process audio: scene {}", scene.getSceneId(), e);
                }
            }

            if (audioPaths.isEmpty()) {
                log.warn("[Download] No audio files found for chat: {}", chatId);
                return null;
            }

            // 단일 파일이면 복사, 여러 파일이면 병합
            if (audioPaths.size() == 1) {
                Files.copy(Path.of(audioPaths.get(0)), mergedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                mergeAudioFiles(audioPaths, mergedPath.toString());
            }

            log.info("[Download] Merged audio created: {}", mergedPath);
            return mergedPath;

        } catch (IOException e) {
            log.error("[Download] Failed to create merged audio for chat: {}", chatId, e);
            return null;
        }
    }

    /**
     * URL에서 파일 다운로드 (byte[] 반환)
     */
    private byte[] downloadFromUrl(String url) throws IOException {
        java.net.URL netUrl = new java.net.URL(url);
        try (java.io.InputStream is = netUrl.openStream();
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    /**
     * URL에서 파일 다운로드하여 로컬 Path에 저장
     */
    private void downloadFromUrl(String url, Path destPath) throws IOException {
        java.net.URL netUrl = new java.net.URL(url);
        try (java.io.InputStream is = netUrl.openStream()) {
            Files.copy(is, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 미디어 파일 준비 (S3 URL이면 다운로드, 로컬이면 그대로 사용)
     * v2.6.1 - S3 URL 및 로컬 파일 모두 지원
     */
    private Path prepareMediaFile(String urlOrPath, Path destDir, String filename) {
        try {
            if (urlOrPath == null || urlOrPath.isEmpty()) {
                return null;
            }

            // v2.9.57: S3 key인 경우 새로운 presigned URL 생성하여 다운로드
            // S3 key 패턴: content/, thumbnails/ 등으로 시작 (http:// 또는 https:// 제외)
            if (urlOrPath.startsWith("content/") || urlOrPath.startsWith("thumbnails/")) {
                Path destPath = destDir.resolve(filename);
                if (!Files.exists(destPath)) {
                    String presignedUrl = storageService.generatePresignedUrl(urlOrPath);
                    log.info("[v2.9.57] Downloading from S3 key: {} -> {}", urlOrPath, destPath);
                    downloadFromUrl(presignedUrl, destPath);
                }
                return destPath;
            }

            // S3 presigned URL인 경우 다운로드 (하위 호환성)
            if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
                Path destPath = destDir.resolve(filename);
                if (!Files.exists(destPath)) {
                    log.info("[ContentService] Downloading from URL: {} -> {}", urlOrPath, destPath);
                    downloadFromUrl(urlOrPath, destPath);
                }
                return destPath;
            }

            // 로컬 파일인 경우
            Path localPath = Paths.get(urlOrPath);
            if (Files.exists(localPath)) {
                return localPath;
            }

            log.warn("[ContentService] Media file not found: {}", urlOrPath);
            return null;
        } catch (Exception e) {
            log.error("[ContentService] Failed to prepare media file: {}", urlOrPath, e);
            return null;
        }
    }

    /**
     * URL/파일 경로에서 확장자 추출
     */
    private String getExtension(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            return "";
        }
        // URL인 경우 쿼리 파라미터 제거
        String path = urlOrPath.split("\\?")[0];
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0 && lastDot < path.length() - 1) {
            return path.substring(lastDot);
        }
        return "";
    }

    private void mergeAudioFiles(List<String> audioPaths, String outputPath) {
        // FFmpeg로 오디오 병합
        try {
            StringBuilder filterComplex = new StringBuilder();
            for (int i = 0; i < audioPaths.size(); i++) {
                filterComplex.append("[").append(i).append(":a]");
            }
            filterComplex.append("concat=n=").append(audioPaths.size()).append(":v=0:a=1[out]");

            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            for (String path : audioPaths) {
                command.add("-i");
                command.add(path);
            }
            command.add("-filter_complex");
            command.add(filterComplex.toString());
            command.add("-map");
            command.add("[out]");
            command.add(outputPath);

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            // v2.9.83: 타임아웃 복원 (3분) - 무한 대기 방지
            boolean finished = process.waitFor(3, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("Audio merge timeout (3min)");
            }
        } catch (Exception e) {
            log.error("Failed to merge audio files", e);
        }
    }

    /**
     * v2.9.39: 이미지를 정적 영상으로 변환 (썸네일용)
     * @param imagePath 이미지 파일 경로
     * @param durationSeconds 영상 길이 (초)
     * @param outputPath 출력 영상 경로
     */
    /**
     * v2.9.46: 이미지를 정적 영상으로 변환 (무음 오디오 포함)
     * 썸네일 등 이미지를 영상으로 변환할 때 사용
     * 다른 씬들과 concat 호환을 위해 무음 오디오 트랙 필수 추가
     */
    private void createStaticVideoFromImage(Path imagePath, int durationSeconds, Path outputPath)
            throws IOException, InterruptedException {
        PathValidator.validateForFFmpeg(imagePath);
        PathValidator.validateForFFmpeg(outputPath);

        log.info("[v2.9.48] Creating static video from image: {} ({}s) with silent audio", imagePath, durationSeconds);

        // 현재 포맷의 해상도 가져오기
        int[] res = getCurrentResolution();
        int width = res[0];
        int height = res[1];

        // v2.9.46: 무음 오디오 추가 (-f lavfi -i anullsrc)
        // 이유: 다른 씬들은 모두 오디오가 있는데 썸네일만 없으면 concat demuxer 실패
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.add("-loop");
        command.add("1");
        command.add("-i");
        command.add(imagePath.toString());
        // v2.9.46: 무음 오디오 소스 추가
        command.add("-f");
        command.add("lavfi");
        command.add("-i");
        command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        command.add("-t");
        command.add(String.valueOf(durationSeconds));
        command.add("-vf");
        command.add(String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                width, height, width, height));
        command.add("-c:v");
        command.add("libx264");
        command.add("-pix_fmt");
        command.add("yuv420p");
        // v2.9.46: 오디오 코덱 명시 (AAC, 128k)
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-ar");
        command.add("44100");
        command.add("-ac");
        command.add("2");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-crf");
        command.add("28");
        // v2.9.46: shortest 옵션 추가 (비디오와 오디오 중 짧은 쪽에 맞춤)
        command.add("-shortest");
        command.add(outputPath.toString());

        log.info("[v2.9.48] FFmpeg command: {}", String.join(" ", command));
        PathValidator.validateCommandArgs(command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (line.contains("error") || line.contains("Error") || line.contains("failed")) {
                    log.error("[v2.9.48] FFmpeg error line: {}", line);
                }
            }
        }

        // v2.9.83: 타임아웃 복원 (2분) - 무한 대기 방지
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            log.error("[v2.9.48] FFmpeg timeout (2min) for static video");
            throw new RuntimeException("FFmpeg timeout creating static video");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("[v2.9.48] FFmpeg failed with exit code: {}", exitCode);
            log.error("[v2.9.48] FFmpeg output: {}", output);
            throw new RuntimeException("FFmpeg failed with exit code: " + exitCode);
        }

        long fileSize = Files.size(outputPath);
        log.info("[v2.9.48] Static video created with silent audio: {} ({}bytes)", outputPath, fileSize);
    }

    /**
     * 개별 씬 영상들을 하나로 합침 (FFmpeg xfade filter)
     * v2.9.0 - 씬 간 페이드 트랜지션 효과 추가
     * v2.6.1 - 해상도/fps가 다른 영상도 정상 합성되도록 수정
     */
    private void concatSceneVideos(List<Path> sceneVideoPaths, Path outputPath) throws IOException, InterruptedException {
        // v2.9.9: 경로 보안 검증
        for (Path scenePath : sceneVideoPaths) {
            PathValidator.validateForFFmpeg(scenePath);
        }
        PathValidator.validateForFFmpeg(outputPath);

        log.info("[ContentService] ===== v2.9.9 Concat Demuxer (pure copy) =====");
        log.info("[ContentService] Concat {} scenes to {}", sceneVideoPaths.size(), outputPath);

        int n = sceneVideoPaths.size();

        // 씬이 1개뿐이면 복사
        if (n == 1) {
            Files.copy(sceneVideoPaths.get(0), outputPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[ContentService] Single scene copied to output");
            return;
        }

        // v2.9.46: 각 씬의 실제 길이 + 오디오/비디오 스트림 정보 로그
        double totalDuration = 0;
        for (int i = 0; i < n; i++) {
            Path scenePath = sceneVideoPaths.get(i);
            double duration = getVideoDuration(scenePath);
            totalDuration += duration;

            // v2.9.46: 오디오 스트림 존재 여부 확인
            boolean hasAudio = hasAudioTrack(scenePath.toString());
            log.info("[v2.9.48] Scene {}: {} -> {}s (audio: {})",
                i, scenePath.getFileName(), String.format("%.2f", duration), hasAudio);

            if (!hasAudio) {
                log.warn("[v2.9.48] ⚠️ Scene {} has NO audio stream: {}", i, scenePath.getFileName());
            }
        }
        log.info("[v2.9.48] Expected total duration: {}s, Total scenes: {}",
            String.format("%.2f", totalDuration), n);

        // ============================================================
        // v2.9.9: Concat Demuxer + Pure Copy 방식
        //
        // 핵심: 씬 파일을 그대로 이어붙이기 (-c copy)
        //
        // 이유: 재인코딩 없이 원본 그대로 합성하여 싱크 문제 해결
        //       각 씬은 이미 동일한 코덱/해상도/fps로 생성됨
        //
        // 장점:
        // 1. 비디오+오디오 모두 원본 그대로 (싱크 100% 보장)
        // 2. 메모리 사용량 최소
        // 3. 처리 속도 최고
        //
        // 실패 시 filter_complex로 폴백 (전체 re-encoding)
        // ============================================================

        // 1. 파일 목록 텍스트 파일 생성
        Path listFile = outputPath.getParent().resolve("concat_list_" + System.currentTimeMillis() + ".txt");
        try {
            StringBuilder listContent = new StringBuilder();
            for (Path videoPath : sceneVideoPaths) {
                // FFmpeg concat demuxer 형식: file 'path'
                listContent.append("file '").append(videoPath.toAbsolutePath()).append("'\n");
            }
            Files.writeString(listFile, listContent.toString());
            log.info("[ContentService] Created concat list file: {}", listFile);

            // 2. concat demuxer로 합성
            // v2.9.9: 완전 복사 모드 - 씬 파일 그대로 이어붙이기
            // - 비디오 복사, 오디오 복사 (재인코딩 없음)
            // - 싱크 문제 해결을 위해 아무 처리 안함
            List<String> command = List.of(
                "ffmpeg", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.toAbsolutePath().toString(),
                "-c", "copy",             // 비디오+오디오 모두 복사 (재인코딩 없음)
                outputPath.toString()
            );

            log.info("[ContentService] v2.9.9 Concat Command (pure copy): {}", String.join(" ", command));

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 출력 읽기
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // v2.9.83: 타임아웃 복원 (10분) - 무한 대기 방지
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[ContentService] Concat demuxer timeout (10min), trying filter_complex fallback...");
                concatSceneVideosWithReencode(sceneVideoPaths, outputPath);
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[ContentService] Concat demuxer failed (exit {}), trying filter_complex fallback...", exitCode);
                log.debug("[ContentService] FFmpeg output: {}", output);
                // 폴백: re-encoding 방식 (코덱 불일치 시)
                concatSceneVideosWithReencode(sceneVideoPaths, outputPath);
                return;
            }

            log.info("[ContentService] v2.9.9 Concat completed successfully! (pure copy, no re-encoding)");

        } finally {
            // 임시 파일 삭제
            try {
                Files.deleteIfExists(listFile);
            } catch (Exception e) {
                log.debug("Failed to delete temp list file: {}", listFile);
            }
        }
    }

    /**
     * v2.9.7: Re-encoding을 사용한 concat (폴백용)
     * 코덱/해상도/fps가 다를 때 사용
     * 메모리 사용량 높음 - OOM 위험
     */
    private void concatSceneVideosWithReencode(List<Path> sceneVideoPaths, Path outputPath) throws IOException, InterruptedException {
        // v2.9.9: 경로 보안 검증
        for (Path scenePath : sceneVideoPaths) {
            PathValidator.validateForFFmpeg(scenePath);
        }
        PathValidator.validateForFFmpeg(outputPath);

        log.info("[ContentService] v2.9.7 Concat with re-encoding (fallback): {} scenes", sceneVideoPaths.size());

        int n = sceneVideoPaths.size();

        // 메모리 절약을 위해 2개씩 순차 합성
        if (n > 10) {
            log.info("[ContentService] Too many scenes ({}), using sequential merge...", n);
            concatSceneVideosSequential(sceneVideoPaths, outputPath);
            return;
        }

        // v2.9.22: -threads 1 추가 (메모리 절약)
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.add("-threads");
        command.add("1");

        for (Path videoPath : sceneVideoPaths) {
            command.add("-i");
            command.add(videoPath.toAbsolutePath().toString());
        }

        // filter_complex: 모든 영상을 동일 포맷으로 통일 후 concat
        // v2.9.25: 포맷별 동적 해상도 적용
        int[] resolution = getCurrentResolution();
        StringBuilder filterComplex = new StringBuilder();
        for (int i = 0; i < n; i++) {
            filterComplex.append(String.format(
                "[%d:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=30,setsar=1[v%d];",
                i, resolution[0], resolution[1], resolution[0], resolution[1], i));
            filterComplex.append(String.format("[%d:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a%d];", i, i));
        }
        for (int i = 0; i < n; i++) {
            filterComplex.append(String.format("[v%d][a%d]", i, i));
        }
        filterComplex.append(String.format("concat=n=%d:v=1:a=1[outv][outa]", n));

        command.add("-filter_complex");
        command.add(filterComplex.toString());
        command.add("-map");
        command.add("[outv]");
        command.add("-map");
        command.add("[outa]");
        command.add("-threads");  // v2.9.22: 인코딩 스레드도 1개
        command.add("1");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-crf");
        command.add("28");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(outputPath.toString());

        log.info("[ContentService] Re-encode concat command length: {} chars", String.join(" ", command).length());

        // v2.9.12: 경로 보안 검증
        PathValidator.validateCommandArgs(command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // v2.9.83: 타임아웃 복원 (10분) - 무한 대기 방지
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            log.error("[ContentService] Re-encode concat timeout (10min)");
            throw new RuntimeException("FFmpeg concat timeout (10min)");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("[ContentService] Re-encode concat failed (exit {}): {}", exitCode, output.toString().substring(0, Math.min(500, output.length())));
            throw new RuntimeException("FFmpeg concat failed with exit code " + exitCode);
        }

        log.info("[ContentService] Re-encode concat completed");
    }

    /**
     * v2.9.7: 순차 합성 (메모리 절약)
     * 2개씩 합쳐가며 최종 영상 생성
     * OOM 방지를 위한 최후의 수단
     */
    private void concatSceneVideosSequential(List<Path> sceneVideoPaths, Path outputPath) throws IOException, InterruptedException {
        // v2.9.9: 경로 보안 검증
        for (Path scenePath : sceneVideoPaths) {
            PathValidator.validateForFFmpeg(scenePath);
        }
        PathValidator.validateForFFmpeg(outputPath);

        log.info("[ContentService] v2.9.7 Sequential concat: {} scenes (memory-safe)", sceneVideoPaths.size());

        Path currentOutput = sceneVideoPaths.get(0);
        Path tempDir = outputPath.getParent();

        for (int i = 1; i < sceneVideoPaths.size(); i++) {
            Path nextScene = sceneVideoPaths.get(i);
            Path tempOutput = tempDir.resolve("temp_concat_" + i + ".mp4");

            log.info("[ContentService] Sequential merge {}/{}: {} + {}", i, sceneVideoPaths.size() - 1,
                currentOutput.getFileName(), nextScene.getFileName());

            // v2.9.22: 2개 영상 합성 (-threads 1로 메모리 절약)
            // v2.9.25: 포맷별 동적 해상도 적용
            int[] res = getCurrentResolution();
            String filterComplex = String.format(
                "[0:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=30,setsar=1[v0];" +
                "[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a0];" +
                "[1:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=30,setsar=1[v1];" +
                "[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a1];" +
                "[v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]",
                res[0], res[1], res[0], res[1], res[0], res[1], res[0], res[1]);
            List<String> command = List.of(
                "ffmpeg", "-y",
                "-threads", "1",  // v2.9.22: 메모리 절약
                "-i", currentOutput.toAbsolutePath().toString(),
                "-i", nextScene.toAbsolutePath().toString(),
                "-filter_complex", filterComplex,
                "-map", "[outv]", "-map", "[outa]",
                "-threads", "1",  // v2.9.22: 인코딩 스레드도 1개
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                "-c:a", "aac", "-b:a", "128k",
                "-pix_fmt", "yuv420p",
                tempOutput.toString()
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 출력 읽기
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* consume output */ }
            }

            // v2.9.83: 타임아웃 복원 (5분) - 무한 대기 방지
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Sequential concat timeout at step " + i);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Sequential concat failed at step " + i);
            }

            // 이전 임시 파일 삭제 (첫 번째 원본 제외)
            if (i > 1) {
                try {
                    Files.deleteIfExists(currentOutput);
                } catch (Exception e) {
                    log.debug("Failed to delete temp file: {}", currentOutput);
                }
            }

            currentOutput = tempOutput;
        }

        // 최종 파일 이동
        Files.move(currentOutput, outputPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("[ContentService] ✅ Sequential concat completed: {}", outputPath);
    }

    // v2.9.7: concatSceneVideosSimple 제거됨
    // 대신 concatSceneVideosWithReencode, concatSceneVideosSequential 사용

    /**
     * v2.9.6: 비디오 파일의 실제 길이 측정 (초)
     * FFprobe를 사용하여 정확한 duration 측정
     */
    private double getVideoDuration(Path videoPath) {
        try {
            List<String> command = List.of(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath.toAbsolutePath().toString()
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
                log.warn("[ContentService] ffprobe video duration timeout (30s) for {}, using default 10s", videoPath);
                return 10.0;
            }
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                String durationStr = output.toString().trim();
                if (!durationStr.isEmpty()) {
                    return Double.parseDouble(durationStr);
                }
            }

            log.warn("[ContentService] Failed to get video duration for {} (exit {}), using default 10s", videoPath, exitCode);
            return 10.0;

        } catch (Exception e) {
            log.error("[ContentService] Error getting video duration: {}", e.getMessage());
            return 10.0;
        }
    }

    /**
     * 문자열을 유효한 JSON 형식으로 변환
     * DB의 JSON 컬럼에 저장하기 위해 사용
     */
    private String wrapAsJson(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        // 이미 유효한 JSON인지 확인
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
            try {
                objectMapper.readTree(text);
                return text; // 이미 유효한 JSON
            } catch (Exception ignored) {
                // 유효하지 않으면 아래에서 래핑
            }
        }
        // JSON 객체로 래핑
        try {
            return objectMapper.writeValueAsString(Map.of("text", text));
        } catch (Exception e) {
            log.warn("Failed to wrap text as JSON, using null", e);
            return null;
        }
    }

    // 진행 상태 클래스
    private static class ProgressInfo {
        String processType;
        String status = "processing";
        String message = "";
        int totalCount;
        int completedCount = 0;
        int totalDuration = 0;
        String filePath;

        ProgressInfo(String processType, int totalCount) {
            this.processType = processType;
            this.totalCount = totalCount;
        }
    }

    // v2.9.75: 시나리오 생성 진행 상황 클래스
    private static class ScenarioProgressInfo {
        String status = "generating";  // idle, generating, expanding, completed, failed
        String phase = "INIT";         // INIT, BASE_SCENARIO, NARRATION_EXPAND
        int totalSlides = 0;
        int completedSlides = 0;
        String message = "시나리오 생성 준비 중...";

        int getProgress() {
            if ("INIT".equals(phase)) return 0;
            if ("BASE_SCENARIO".equals(phase)) return 10;
            if ("NARRATION_EXPAND".equals(phase)) {
                if (totalSlides == 0) return 15;
                // 10% (기본 시나리오) + 90% * (완료 슬라이드 / 전체 슬라이드)
                return 10 + (int)(90.0 * completedSlides / totalSlides);
            }
            if ("completed".equals(status)) return 100;
            return 0;
        }
    }

    // v2.9.75: 시나리오 진행 상황 업데이트 메서드 (static으로 ScenarioGeneratorServiceImpl에서 호출)
    public static void updateScenarioProgress(Long chatId, String phase, int completedSlides, int totalSlides, String message) {
        ScenarioProgressInfo info = scenarioProgressStore.computeIfAbsent(chatId, k -> new ScenarioProgressInfo());
        info.phase = phase;
        info.completedSlides = completedSlides;
        info.totalSlides = totalSlides;
        info.message = message;
        if ("completed".equals(phase)) {
            info.status = "completed";
        } else if ("failed".equals(phase)) {
            info.status = "failed";
        } else if ("NARRATION_EXPAND".equals(phase)) {
            info.status = "expanding";
        } else {
            info.status = "generating";
        }
    }

    // v2.9.75: 시나리오 진행 상황 초기화
    public static void initScenarioProgress(Long chatId, int totalSlides) {
        ScenarioProgressInfo info = new ScenarioProgressInfo();
        info.totalSlides = totalSlides;
        info.message = "시나리오 생성 시작...";
        scenarioProgressStore.put(chatId, info);
    }

    // v2.9.75: 시나리오 진행 상황 제거
    public static void clearScenarioProgress(Long chatId) {
        scenarioProgressStore.remove(chatId);
    }

    // v2.9.75: 시나리오 진행 상황 조회
    public ContentDto.ScenarioProgressResponse getScenarioProgress(Long userNo, Long chatId) {
        // 대화 존재 확인
        Conversation conversation = conversationMapper.findById(chatId)
                .orElse(null);

        if (conversation == null || !conversation.getUserNo().equals(userNo)) {
            return ContentDto.ScenarioProgressResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .phase("INIT")
                    .totalSlides(0)
                    .completedSlides(0)
                    .progress(0)
                    .message("대화를 찾을 수 없습니다.")
                    .build();
        }

        ScenarioProgressInfo info = scenarioProgressStore.get(chatId);
        if (info == null) {
            // 진행 중인 시나리오 생성이 없음 - currentStep 확인
            String currentStep = conversation.getCurrentStep();
            if ("GENERATING".equals(currentStep) || "SCENARIO_GENERATING".equals(currentStep)) {
                // 서버 재시작 등으로 진행 정보 손실 - 진행 중으로 표시
                return ContentDto.ScenarioProgressResponse.builder()
                        .chatId(chatId)
                        .status("generating")
                        .phase("BASE_SCENARIO")
                        .totalSlides(0)
                        .completedSlides(0)
                        .progress(10)
                        .message("시나리오 생성 중...")
                        .build();
            }
            return ContentDto.ScenarioProgressResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .phase("INIT")
                    .totalSlides(0)
                    .completedSlides(0)
                    .progress(0)
                    .message("")
                    .build();
        }

        return ContentDto.ScenarioProgressResponse.builder()
                .chatId(chatId)
                .status(info.status)
                .phase(info.phase)
                .totalSlides(info.totalSlides)
                .completedSlides(info.completedSlides)
                .progress(info.getProgress())
                .message(info.message)
                .build();
    }

    // ========== v2.4.0 개별 씬 영상 생성 ==========

    /**
     * 씬 생성 시작 (이미지 + 오프닝 영상 + TTS + 자막 + 개별 씬 영상)
     */
    @Transactional
    public ContentDto.ScenesGenerateResponse generateScenes(Long userNo, Long chatId, ContentDto.ScenesGenerateRequest request) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        // Video 조회
        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 시나리오 조회
        Scenario scenario = scenarioMapper.findByVideoId(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "시나리오가 없습니다."));

        // 모든 씬 조회
        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

        log.info("[ContentService] Generating scenes for chat: {}, total scenes: {}", chatId, allScenes.size());

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("scenes", allScenes.size());
        progressStore.put(chatId, progress);

        // v2.5.8: 씬 생성 (비동기) - ScenarioContext는 generateScenesAsync 내에서 DB로부터 빌드
        boolean includeSubtitle = request == null || request.isIncludeSubtitle();
        generateScenesAsync(userNo, chatId, videoId, allScenes, includeSubtitle, progress);

        // 대화 상태 업데이트
        conversationMapper.updateCurrentStep(chatId, "SCENES_GENERATING");

        return ContentDto.ScenesGenerateResponse.builder()
                .chatId(chatId)
                .status("processing")
                .totalCount(allScenes.size())
                .completedCount(0)
                .progressMessage("씬 생성을 시작합니다...")
                .build();
    }

    @Async
    @Transactional
    protected void generateScenesAsync(Long userNo, Long chatId, Long videoId, List<Scene> allScenes,
                                        boolean includeSubtitle, ProgressInfo progress) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path sceneDir = getSceneDir(chatId);
            Files.createDirectories(sceneDir);

            // v2.5.8: DB에서 전체 시나리오 컨텍스트 로드 (필수!)
            ScenarioContext scenarioContext = buildScenarioContext(videoId);
            String characterBlock = scenarioContext.getCharacterBlock();

            // v2.9.162: ScenarioContext의 참조 이미지 데이터 재사용 (S3 다운로드 중복 방지)
            VideoDto.ScenarioInfo videoScenarioInfo = buildVideoScenarioInfo(videoId, scenarioContext);
            log.info("[ContentService] VideoDto.ScenarioInfo loaded for opening video generation: {}", videoScenarioInfo != null);

            // v2.8.0: Video에서 creatorId 조회 (필수 - 장르 선택 필수화)
            Video video = videoMapper.findById(videoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + videoId));
            Long creatorId = video.getCreatorId();
            if (creatorId == null) {
                log.warn("[ContentService] ⚠️ creatorId is null for videoId={}. Using default genre 1 (legacy data).", videoId);
                creatorId = 1L;
            }

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[ContentService] formatId set for scene generation: {}", formatId);

            // v2.9.161: Video에서 videoSubtitleId, fontSizeLevel 조회하여 ThreadLocal 설정
            Long videoSubtitleId = video.getVideoSubtitleId();
            if (videoSubtitleId == null) {
                videoSubtitleId = 1L;
            }
            currentVideoSubtitleId.set(videoSubtitleId);
            Integer fontSizeLevel = video.getFontSizeLevel();
            if (fontSizeLevel == null) {
                fontSizeLevel = 3;
            }
            currentFontSizeLevel.set(fontSizeLevel);
            // v2.9.167: 자막 위치
            Integer subtitlePosition = video.getSubtitlePosition();
            if (subtitlePosition == null) {
                subtitlePosition = 1;
            }
            currentSubtitlePosition.set(subtitlePosition);
            // v2.9.174: 폰트 ID, 크리에이터 ID
            Long fontId = (video.getFontId() != null) ? video.getFontId() : 1L;
            currentFontId.set(fontId);
            currentCreatorId.set(creatorId);

            for (int i = 0; i < allScenes.size(); i++) {
                Scene scene = allScenes.get(i);

                try {
                    // 씬 상태를 GENERATING으로 업데이트
                    sceneUpdateService.updateStatus(scene.getSceneId(), "GENERATING");

                    progress.message = String.format("씬 %d/%d 생성 중... (%s)",
                            i + 1, allScenes.size(),
                            "OPENING".equals(scene.getSceneType()) ? "오프닝" : "슬라이드 " + scene.getSceneOrder());

                    // 1. 이미지 또는 오프닝 영상 생성
                    String mediaUrl = null;
                    if ("OPENING".equals(scene.getSceneType())) {
                        // ⚠️ 오프닝 프롬프트 필수 검증 (폴백 절대 금지!)
                        String videoPrompt = scene.getPrompt();
                        if (videoPrompt == null || videoPrompt.trim().isEmpty()) {
                            log.error("[ContentService] ❌ CRITICAL: Scene #{} (OPENING) has no prompt in DB! sceneId: {}",
                                scene.getSceneOrder(), scene.getSceneId());
                            throw new ApiException(ErrorCode.INVALID_REQUEST,
                                "오프닝 씬의 프롬프트가 DB에 저장되어 있지 않습니다. 시나리오를 다시 생성해주세요. (sceneId: " + scene.getSceneId() + ")");
                        }

                        log.info("[ContentService] ✅ Opening prompt validated from DB - length: {}", videoPrompt.length());

                        // 오프닝: Veo 3.1로 영상 생성 (v2.8.0: creatorId 추가, v2.9.77: 시나리오 컨텍스트 포함)
                        VideoDto.OpeningScene openingScene = VideoDto.OpeningScene.builder()
                                .videoPrompt(videoPrompt)
                                .narration(scene.getNarration())
                                .durationSeconds(scene.getDuration() != null ? scene.getDuration() : 8)
                                .build();

                        // v2.9.77: 전체 시나리오 정보와 함께 오프닝 영상 생성
                        mediaUrl = videoCreatorService.generateOpeningVideo(userNo, openingScene, QualityTier.PREMIUM, characterBlock, creatorId, videoScenarioInfo);

                        if (mediaUrl != null) {
                            // 로컬에 복사
                            Path destPath = sceneDir.resolve(scene.getSceneFileName().replace(".mp4", "_video.mp4"));
                            Files.copy(Paths.get(mediaUrl), destPath, StandardCopyOption.REPLACE_EXISTING);
                            scene.setImageUrl(destPath.toString()); // 오프닝은 imageUrl에 영상 경로 저장
                            sceneUpdateService.updateImageUrl(scene.getSceneId(), destPath.toString());
                        }
                    } else {
                        // 슬라이드: Gemini 3로 이미지 생성 (v2.8.0: creatorId 추가)
                        VideoDto.SlideScene slideScene = VideoDto.SlideScene.builder()
                                .order(scene.getSceneOrder())
                                .imagePrompt(scene.getPrompt())
                                .narration(scene.getNarration())
                                .durationSeconds(scene.getDuration())
                                .build();

                        // v2.9.25 fix: 각 슬라이드마다 formatId 재설정 (generateImages finally에서 remove됨)
                        setCurrentFormatId(formatId);

                        // v2.8.0: ScenarioContext + creatorId 포함하여 이미지 생성
                        List<String> imagePaths = imageGeneratorService.generateImages(
                                userNo, List.of(slideScene), QualityTier.PREMIUM, scenarioContext, creatorId);

                        if (!imagePaths.isEmpty() && imagePaths.get(0) != null && !imagePaths.get(0).startsWith("ERROR")) {
                            Path destPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".png");
                            Files.copy(Paths.get(imagePaths.get(0)), destPath, StandardCopyOption.REPLACE_EXISTING);
                            scene.setImageUrl(destPath.toString());
                            sceneUpdateService.updateImageUrl(scene.getSceneId(), destPath.toString());
                        }
                    }

                    // 2. TTS 나레이션 생성 (v2.8.2: creatorId 전달하여 TTS_INSTRUCTION 적용)
                    List<double[]> speechSegments = null; // v2.9.3: 음성 구간 저장
                    Double actualAudioDuration = null; // v2.9.175: 실제 오디오 길이 (자막 폴백 타이밍용)
                    if (scene.getNarration() != null && !scene.getNarration().isEmpty()) {
                        String audioPath = ttsService.generateNarration(userNo, scene.getNarration(), QualityTier.PREMIUM, creatorId);

                        if (audioPath != null) {
                            // v2.9.6: 오프닝 씬은 정확히 8초에 맞춤 (Veo 영상 길이)
                            if ("OPENING".equals(scene.getSceneType())) {
                                double openingDuration = 8.0; // Veo API 고정 길이
                                String adjustedPath = ttsService.adjustAudioTempo(audioPath, openingDuration);
                                if (adjustedPath != null && !adjustedPath.equals(audioPath)) {
                                    log.info("[ContentService] ✅ Opening TTS tempo adjusted to {}s", openingDuration);
                                    audioPath = adjustedPath;
                                }
                            }

                            Path destAudioPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".mp3");
                            Files.copy(Paths.get(audioPath), destAudioPath, StandardCopyOption.REPLACE_EXISTING);
                            scene.setAudioUrl(destAudioPath.toString());
                            sceneUpdateService.updateAudioUrl(scene.getSceneId(), destAudioPath.toString());

                            // 실제 오디오 길이 측정 및 업데이트
                            double actualDuration = ttsService.getAudioDuration(destAudioPath.toString());
                            actualAudioDuration = actualDuration; // v2.9.175: 자막 폴백 타이밍에 실제 오디오 길이 전달
                            int durationSeconds = (int) Math.ceil(actualDuration);
                            // v2.9.10: 오프닝은 8초 고정, 슬라이드는 3-60초 범위 (TTS 길이에 따라 동적)
                            if ("OPENING".equals(scene.getSceneType())) {
                                durationSeconds = 8; // 오프닝 고정 8초
                            } else {
                                durationSeconds = Math.max(3, Math.min(6000, durationSeconds));  // v2.9.76: 180초→6000초 (100분, 나레이션 길이 무제한)
                            }
                            scene.setDuration(durationSeconds);
                            sceneUpdateService.updateDuration(scene.getSceneId(), durationSeconds);

                            // v2.9.175: 문장 경계 감지 (SubtitleServiceImpl과 동일한 필터링 기준)
                            int sentenceCount = countSentences(scene.getNarration());
                            speechSegments = ttsService.detectSentenceBoundaries(destAudioPath.toString(), sentenceCount);
                            log.info("[v2.9.175] Detected {} sentence boundaries for scene {} (sentenceCount={})",
                                    speechSegments != null ? speechSegments.size() : 0, scene.getSceneId(), sentenceCount);
                        }
                    }

                    // 3. 자막 생성 (ASS 포맷) - v2.9.175: 실제 오디오 길이 전달
                    if (includeSubtitle && scene.getNarration() != null) {
                        String subtitlePath = generateSceneSubtitle(scene, sceneDir, speechSegments, actualAudioDuration);
                        if (subtitlePath != null) {
                            scene.setSubtitleUrl(subtitlePath);
                            sceneUpdateService.updateSubtitleUrl(scene.getSceneId(), subtitlePath);
                        }
                    }

                    // 4. 개별 씬 영상 합성 (이미지/영상 + 오디오 + 자막)
                    String sceneVideoPath = composeIndividualSceneVideo(scene, sceneDir);
                    if (sceneVideoPath != null) {
                        scene.setSceneVideoUrl(sceneVideoPath);
                        sceneUpdateService.updateSceneVideoUrl(scene.getSceneId(), sceneVideoPath);
                    }

                    // 씬 완료 처리
                    sceneUpdateService.updateStatus(scene.getSceneId(), "COMPLETED");
                    progress.completedCount++;

                } catch (Exception e) {
                    log.error("[ContentService] Scene generation failed for scene: {}", scene.getSceneId(), e);
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                }
            }

            // S3 업로드 (활성화된 경우)
            if (storageService.isEnabled()) {
                uploadScenesToS3(chatId, sceneDir);
            }

            progress.status = "completed";
            progress.message = "모든 씬 생성 완료!";

            // Video 상태 업데이트
            videoMapper.updateProgress(videoId, 80, "SCENES");

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "SCENES_REVIEW");

        } catch (Exception e) {
            log.error("[ContentService] Scenes generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "씬 생성 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * 개별 씬 자막 생성 (ASS 포맷) - 레거시 (글자 수 기반 타이밍)
     */
    private String generateSceneSubtitle(Scene scene, Path sceneDir) {
        return generateSceneSubtitle(scene, sceneDir, null, null);
    }

    /**
     * v2.9.175: 개별 씬 자막 생성 (ASS 포맷) - SubtitleService에 위임
     * @param scene 씬 정보
     * @param sceneDir 씬 디렉토리
     * @param speechSegments 음성 구간 (null이면 글자 수 기반 폴백)
     * @param actualAudioDuration 실제 TTS 오디오 길이 (null이면 scene.getDuration() 사용)
     */
    private String generateSceneSubtitle(Scene scene, Path sceneDir, List<double[]> speechSegments, Double actualAudioDuration) {
        try {
            Long formatId = currentFormatId.get();
            Long videoSubtitleId = currentVideoSubtitleId.get();
            Integer fontSizeLevel = currentFontSizeLevel.get();
            Integer subtitlePosition = currentSubtitlePosition.get();
            Long fontId = currentFontId.get();
            Long creatorId = currentCreatorId.get();
            String subtitleContent = subtitleService.generateSceneSubtitle(
                    scene.getNarration(), scene.getDuration(), speechSegments, formatId, videoSubtitleId, fontSizeLevel, subtitlePosition, fontId, creatorId, actualAudioDuration);
            Path subtitlePath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".ass");
            Files.writeString(subtitlePath, subtitleContent);
            return subtitlePath.toString();
        } catch (Exception e) {
            log.error("Failed to generate subtitle for scene: {}", scene.getSceneId(), e);
            return null;
        }
    }

    // v2.9.160: generateAssSubtitle() 및 관련 헬퍼 메서드는 SubtitleServiceImpl로 이동됨
    // 삭제된 메서드: generateAssSubtitle(), splitNarrationIntoSentences(),
    //   formatSubtitleText(), detectSubtitleStyle(), mergeSegmentsToSentences(), formatAssTime()

    /**
     * 개별 씬 영상 합성 (이미지/영상 + 오디오 + 자막)
     * v2.6.3 - Ken Burns 효과 추가, S3 URL 및 로컬 파일 모두 지원
     */
    private String composeIndividualSceneVideo(Scene scene, Path sceneDir) {
        try {
            // v2.9.25: Video에서 formatId 조회하여 설정 (ThreadLocal이 clear될 수 있으므로)
            Video video = videoMapper.findById(scene.getVideoId()).orElse(null);
            Long formatId = (video != null && video.getFormatId() != null) ? video.getFormatId() : 1L;
            setCurrentFormatId(formatId);
            int[] resolution = getCurrentResolution();
            log.info("[ContentService] composeIndividualSceneVideo - Scene {}, videoId: {}, formatId: {}, resolution: {}x{}",
                scene.getSceneOrder(), scene.getVideoId(), formatId, resolution[0], resolution[1]);

            Path outputPath = sceneDir.resolve(scene.getSceneFileName());

            // v2.6.1: 미디어 파일 준비 (S3 URL인 경우 다운로드)
            Path videoOrImagePath = null;
            Path audioPath = null;
            Path subtitlePath = null;

            // 1. 비디오/이미지 파일 준비
            if (scene.getImageUrl() != null) {
                videoOrImagePath = prepareMediaFile(scene.getImageUrl(), sceneDir,
                    "media_" + scene.getSceneOrder() + getExtension(scene.getImageUrl()));
            }
            if (videoOrImagePath == null) {
                log.error("[ContentService] No video/image for scene: {}", scene.getSceneId());
                return null;
            }

            // 2. 오디오 파일 준비
            if (scene.getAudioUrl() != null && !scene.getAudioUrl().isEmpty()) {
                audioPath = prepareMediaFile(scene.getAudioUrl(), sceneDir,
                    "audio_" + scene.getSceneOrder() + ".mp3");
            }

            // 3. 자막 파일 준비
            if (scene.getSubtitleUrl() != null && !scene.getSubtitleUrl().isEmpty()) {
                subtitlePath = prepareMediaFile(scene.getSubtitleUrl(), sceneDir,
                    "subtitle_" + scene.getSceneOrder() + ".ass");
            }

            log.info("[ContentService] Composing scene {}: video={}, audio={}, subtitle={}",
                scene.getSceneOrder(),
                videoOrImagePath != null,
                audioPath != null,
                subtitlePath != null);

            // v2.9.9: 경로 보안 검증
            PathValidator.validateForFFmpeg(outputPath);
            PathValidator.validateForFFmpeg(videoOrImagePath);
            if (audioPath != null) PathValidator.validateForFFmpeg(audioPath);
            if (subtitlePath != null) PathValidator.validateForFFmpeg(subtitlePath);

            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");

            // v2.6.3: 슬라이드인지 오프닝인지에 따라 처리 방식 다름
            boolean isSlide = !"OPENING".equals(scene.getSceneType());
            int durationSec = scene.getDuration() != null ? scene.getDuration() : 10;

            // v2.8.3: 오프닝 영상은 원본 오디오 + TTS 믹싱 필요
            boolean hasAudio = audioPath != null && Files.exists(audioPath);
            boolean isOpeningWithAudioMix = !isSlide && hasAudio;

            if (isSlide) {
                // v2.9.10: 슬라이드 - 이미지 루프 + 명시적 duration 설정
                // -loop 1: 이미지 무한 반복
                // -t durationSec: 입력 읽기 시간 제한 (필수! 없으면 무한 루프)
                command.add("-loop");
                command.add("1");
                command.add("-t");
                command.add(String.valueOf(durationSec));
                command.add("-i");
                command.add(videoOrImagePath.toString());
                log.info("[ContentService] Slide {} duration set to {}s", scene.getSceneOrder(), durationSec);
            } else {
                // 오프닝: 영상 그대로 사용 (Veo API에서 8초로 생성됨)
                command.add("-i");
                command.add(videoOrImagePath.toString());
                log.info("[ContentService] Opening scene - using original video duration");
            }

            // v2.9.86: 오디오 추가 (없으면 무음 트랙 추가 - concat 호환성 필수)
            if (hasAudio) {
                command.add("-i");
                command.add(audioPath.toString());
            } else if (isSlide) {
                // v2.9.86: 슬라이드에 오디오 없으면 무음 오디오 소스 추가
                // anullsrc: FFmpeg 가상 무음 오디오 필터
                // concat demuxer에서 모든 씬이 동일한 스트림 구조 필요
                command.add("-f");
                command.add("lavfi");
                command.add("-t");
                command.add(String.valueOf(durationSec));
                command.add("-i");
                command.add("anullsrc=r=44100:cl=stereo");
                log.info("[v2.9.86] Added silent audio track for slide {} ({}s)", scene.getSceneOrder(), durationSec);
            }

            // v2.9.0: 오프닝과 슬라이드 필터 처리 분리 (FFmpeg -vf/-filter_complex 충돌 해결)
            boolean hasSubtitle = subtitlePath != null && Files.exists(subtitlePath);
            String subtitleFilter = hasSubtitle ? "ass=" + subtitlePath.toString().replace(":", "\\:") : null;

            // v2.9.11: 모든 영상을 포맷 해상도로 통일 (crop으로 꽉 차게)
            // v2.9.17: 오프닝에도 페이드인/아웃 효과 추가
            // v2.9.25: 포맷별 동적 해상도 적용
            int[] res = getCurrentResolution();
            double fadeOutStart = Math.max(0.5, durationSec - 0.3);  // 최소 0.5초 보장
            String fadeFilter = String.format(",fade=t=in:st=0:d=0.3,fade=t=out:st=%.1f:d=0.3", fadeOutStart);
            String scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d%s",
                res[0], res[1], res[0], res[1], fadeFilter);

            if (isOpeningWithAudioMix) {
                // v2.9.10: 오프닝은 모든 필터를 filter_complex로 통합 (-vf와 -filter_complex 동시 사용 금지)
                // 먼저 원본 영상에 오디오 트랙이 있는지 확인
                boolean hasOriginalAudio = hasAudioTrack(videoOrImagePath.toString());

                StringBuilder complexFilter = new StringBuilder();

                if (hasOriginalAudio) {
                    // v2.9.87: 원본 오디오 + TTS 믹싱 + scale 필터
                    // 핵심: amix 전에 모든 오디오를 soxr로 44100Hz 리샘플링 (노이즈 방지)
                    // resampler=soxr: SoX 리샘플러 (최고 품질, 오디오 프로덕션 표준)
                    // async=1000: 샘플 드롭/복제로 싱크 맞춤 (클릭/팝 방지)
                    if (hasSubtitle) {
                        complexFilter.append("[0:v]").append(scaleFilter).append(",").append(subtitleFilter).append("[vout];");
                        // v2.9.89: soxr 리샘플링 - 원본 오디오 5%로 낮춤 (나레이션과 겹침 방지)
                        complexFilter.append("[0:a]aresample=resampler=soxr:async=1000:osr=44100,volume=0.05[bg];");
                        complexFilter.append("[1:a]aresample=resampler=soxr:async=1000:osr=44100,volume=1.0[tts];");
                        complexFilter.append("[bg][tts]amix=inputs=2:duration=longest[aout]");
                    } else {
                        complexFilter.append("[0:v]").append(scaleFilter).append("[vout];");
                        // v2.9.89: soxr 리샘플링 - 원본 오디오 5%로 낮춤 (나레이션과 겹침 방지)
                        complexFilter.append("[0:a]aresample=resampler=soxr:async=1000:osr=44100,volume=0.05[bg];");
                        complexFilter.append("[1:a]aresample=resampler=soxr:async=1000:osr=44100,volume=1.0[tts];");
                        complexFilter.append("[bg][tts]amix=inputs=2:duration=longest[aout]");
                    }
                    log.info("[v2.9.89] Opening audio mix with soxr resampling (44100Hz) for scene {}", scene.getSceneOrder());
                    command.add("-filter_complex");
                    command.add(complexFilter.toString());
                    command.add("-map");
                    command.add("[vout]");
                    command.add("-map");
                    command.add("[aout]");
                    log.info("[ContentService] Opening audio mix: original(5%) + TTS(100%) + scale for scene {}", scene.getSceneOrder());
                } else {
                    // 원본 오디오 없음 - TTS만 사용 + scale 필터
                    StringBuilder vfFilter = new StringBuilder(scaleFilter);
                    if (hasSubtitle) {
                        vfFilter.append(",").append(subtitleFilter);
                    }
                    command.add("-vf");
                    command.add(vfFilter.toString());
                    // TTS 오디오를 메인 오디오로 사용
                    command.add("-map");
                    command.add("0:v");
                    command.add("-map");
                    command.add("1:a");
                    log.info("[ContentService] Opening with TTS only (no original audio) + scale for scene {}", scene.getSceneOrder());
                }
            } else if (!isSlide) {
                // v2.9.10: 오프닝 but no audio mix (오디오 없음)
                StringBuilder vfFilter = new StringBuilder(scaleFilter);
                if (hasSubtitle) {
                    vfFilter.append(",").append(subtitleFilter);
                }
                command.add("-vf");
                command.add(vfFilter.toString());
                log.info("[ContentService] Opening without audio + scale for scene {}", scene.getSceneOrder());
            } else {
                // 슬라이드: 기존 -vf 필터 체인 사용
                StringBuilder filterChain = new StringBuilder();

                if (isSlide) {
                    // v2.9.25: 정적 이미지 + 페이드 효과만 (Ken Burns 제거)
                    // - Ken Burns zoompan은 고해상도 업스케일 없이 끊김/흔들림 발생
                    // - 고해상도 업스케일(8000px)은 t3.micro에서 OOM 위험
                    // - 따라서 정적 이미지 + 페이드인/아웃만 적용 (안정적, 부하 없음)
                    // v2.9.25: 포맷별 동적 해상도 적용
                    filterChain.append(String.format(Locale.US,
                        "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d," +
                        "fade=t=in:st=0:d=0.3,fade=t=out:st=%.1f:d=0.3",
                        res[0], res[1], res[0], res[1], fadeOutStart
                    ));
                    log.info("[ContentService] Scene {} - Static image + fade ({}s), resolution: {}x{}",
                        scene.getSceneOrder(), durationSec, res[0], res[1]);
                }

                // 자막 필터 추가 (페이드 후 자막 오버레이)
                if (hasSubtitle) {
                    if (filterChain.length() > 0) {
                        filterChain.append(",");
                    }
                    filterChain.append(subtitleFilter);
                }

                // 필터 체인 적용
                if (filterChain.length() > 0) {
                    command.add("-vf");
                    command.add(filterChain.toString());
                }
            }

            // v2.9.22: 출력 설정 - 모든 씬 동일한 코덱/fps로 통일 (concat 호환성)
            // -threads 1: 메모리 사용량 최소화 (t3.micro OOM 방지)
            command.add("-threads");
            command.add("1");
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("ultrafast");
            command.add("-crf");
            command.add("28");
            command.add("-r");
            command.add("30");  // v2.9.17: 모든 씬 30fps로 통일 (더 부드러운 재생)

            // v2.9.87: 오디오 리샘플링 + 품질 향상
            // 문제: TTS 24000Hz mono → 44100Hz stereo 변환 시 노이즈/깨짐 발생
            // 해결: aresample 필터 + soxr 리샘플러 (최고 품질)
            // Alpine FFmpeg는 libsoxr 지원 확인됨 (ffmpeg-libswresample → so:libsoxr.so.0)
            if (hasAudio && isSlide) {
                command.add("-af");
                // aresample: 고품질 리샘플링
                // resampler=soxr: SoX 리샘플러 (최고 품질, 오디오 프로덕션 표준)
                // async=1000: 오디오 싱크 자동 조정 (클릭/팝 노이즈 방지)
                // first_pts=0: 타임스탬프 리셋으로 싱크 문제 방지
                // osr=44100: 출력 샘플레이트 명시적 지정
                command.add("aresample=resampler=soxr:async=1000:first_pts=0:osr=44100");
                log.info("[v2.9.87] Audio resampling with soxr (44100Hz) for slide {}", scene.getSceneOrder());
            }

            // v2.9.86: 오디오 품질 대폭 향상 (128k → 256k) + 오디오 없는 경우 무음 추가
            // 음성 깨짐 방지: 비트레이트 증가 + 명시적 채널 설정
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("256k");  // v2.9.86: 128k → 256k 음질 향상
            command.add("-ar");
            command.add("44100");  // 오디오 샘플레이트 통일
            command.add("-ac");
            command.add("2");     // v2.9.86: 스테레오 명시 (모노→스테레오 변환 문제 방지)

            // v2.9.86: 슬라이드에서 오디오 없는 경우 무음 오디오 트랙 추가 (concat 호환성)
            // 오디오 없으면 concat demuxer에서 스트림 불일치로 실패함
            if (isSlide && !hasAudio) {
                // 이미 입력된 커맨드에서 오디오 관련 옵션 앞에 무음 소스 추가 필요
                // 재구성: 입력 파일 바로 다음에 무음 오디오 추가
                log.info("[v2.9.86] ⚠️ No audio for slide {} - adding silent audio track", scene.getSceneOrder());
            }

            // v2.8.3: 슬라이드는 -shortest로 오디오 길이에 맞춤
            if (isSlide && hasAudio) {
                command.add("-shortest");
            }

            command.add("-pix_fmt");
            command.add("yuv420p");
            command.add(outputPath.toString());

            log.debug("[ContentService] FFmpeg command: {}", String.join(" ", command));

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 로그 출력 읽기
            StringBuilder ffmpegOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ffmpegOutput.append(line).append("\n");
                    log.debug("[FFmpeg] {}", line);
                }
            }

            // v2.9.83: 타임아웃 복원 (5분) - 무한 대기 방지
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("[ContentService] FFmpeg timeout (5min) for scene {}", scene.getSceneId());
                return null;
            }
            int exitCode = process.exitValue();

            if (exitCode == 0 && Files.exists(outputPath)) {
                log.info("[ContentService] Scene video composed: {}", outputPath);
                return outputPath.toString();
            } else {
                // v2.9.22: OOM 감지 (exit code 137 = killed by OOM)
                if (exitCode == 137) {
                    log.error("[ContentService] FFmpeg OOM detected (exit 137) for scene {}", scene.getSceneId());
                    // OOM 발생 시 GC 트리거 (다음 재시도를 위해 메모리 확보)
                    System.gc();
                    throw new OutOfMemoryError("FFmpeg killed by OOM (exit 137)");
                }
                log.error("[ContentService] FFmpeg failed with exit code: {}", exitCode);
                return null;
            }

        } catch (OutOfMemoryError oom) {
            // v2.9.22: OOM 에러는 상위로 전파 (특별 재시도 로직 적용)
            log.error("FFmpeg OOM for scene {}: {}", scene.getSceneId(), oom.getMessage());
            throw oom;
        } catch (Exception e) {
            log.error("Failed to compose scene video: {}", scene.getSceneId(), e);
            return null;
        }
    }

    /**
     * v2.9.1: 비디오 파일에 유효한 오디오 트랙이 있는지 확인
     * ffprobe를 사용하여 오디오 스트림 존재 여부 + 실제 오디오 길이 확인
     * Veo API 영상의 경우 오디오 트랙이 있지만 빈 경우가 있어 길이 검증 추가
     */
    private boolean hasAudioTrack(String videoPath) {
        try {
            // 1단계: 오디오 트랙 존재 여부 확인
            List<String> command1 = List.of(
                "ffprobe", "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0",
                videoPath
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command1);

            ProcessBuilder pb = new ProcessBuilder(command1);
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
                log.warn("[ContentService] ffprobe audio check timeout (30s) for: {}", videoPath);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[ContentService] ffprobe failed (exit {}) for: {}", exitCode, videoPath);
                return false;
            }

            String result = output.toString().trim();
            if (!result.contains("audio")) {
                log.debug("[ContentService] Video {} has no audio track", videoPath);
                return false;
            }

            // 2단계: 오디오 길이 확인 (0초 또는 매우 짧으면 false)
            List<String> command2 = List.of(
                "ffprobe", "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=duration",
                "-of", "csv=p=0",
                videoPath
            );

            // v2.9.12: 경로 보안 검증
            PathValidator.validateCommandArgs(command2);

            ProcessBuilder pb2 = new ProcessBuilder(command2);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();

            StringBuilder output2 = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process2.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output2.append(line);
                }
            }

            // v2.9.83: 타임아웃 복원 (30초) - 무한 대기 방지
            boolean finished2 = process2.waitFor(30, TimeUnit.SECONDS);
            if (!finished2) {
                process2.destroyForcibly();
                log.warn("[ContentService] ffprobe audio duration timeout (30s) for: {}", videoPath);
                return false;
            }
            int exitCode2 = process2.exitValue();
            if (exitCode2 != 0) {
                log.warn("[ContentService] ffprobe audio duration failed (exit {}) for: {}", exitCode2, videoPath);
                return false;
            }

            String durationStr = output2.toString().trim();
            if (durationStr.isEmpty() || durationStr.equals("N/A")) {
                log.info("[ContentService] Video {} has audio track but duration is N/A - treating as no audio", videoPath);
                return false;
            }

            try {
                double duration = Double.parseDouble(durationStr);
                if (duration < 0.5) {
                    log.info("[ContentService] Video {} has audio track but too short ({}s) - treating as no audio", videoPath, duration);
                    return false;
                }
                log.info("[ContentService] Video {} has valid audio track ({}s)", videoPath, duration);
                return true;
            } catch (NumberFormatException e) {
                log.warn("[ContentService] Failed to parse audio duration '{}' for: {}", durationStr, videoPath);
                return false;
            }

        } catch (Exception e) {
            log.warn("[ContentService] Failed to check audio track: {}", e.getMessage());
            return false;  // 확인 실패 시 오디오 없다고 가정
        }
    }

    /**
     * 씬 검토 응답 조회
     */
    public ContentDto.ScenesReviewResponse getScenesReview(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        List<ContentDto.SceneInfo> sceneInfos = scenes.stream()
                .map(this::convertToSceneInfo)
                .collect(Collectors.toList());

        int completedCount = (int) scenes.stream().filter(s -> "COMPLETED".equals(s.getSceneStatus())).count();
        int failedCount = (int) scenes.stream().filter(s -> "FAILED".equals(s.getSceneStatus())).count();

        String status = failedCount > 0 ? "has_failed" :
                        completedCount == scenes.size() ? "all_completed" : "pending";

        return ContentDto.ScenesReviewResponse.builder()
                .chatId(chatId)
                .status(status)
                .totalCount(scenes.size())
                .completedCount(completedCount)
                .failedCount(failedCount)
                .scenes(sceneInfos)
                .canProceedToFinal(completedCount == scenes.size())
                .message(buildScenesReviewMessage(completedCount, failedCount, scenes.size()))
                .build();
    }

    private ContentDto.SceneInfo convertToSceneInfo(Scene scene) {
        return ContentDto.SceneInfo.builder()
                .sceneId(scene.getSceneId())
                .sceneOrder(scene.getSceneOrder())
                .sceneType(scene.getSceneType())
                .title(scene.getTitle())
                .narration(scene.getNarration())
                .prompt(scene.getPrompt())
                .imageUrl(toPresignedUrlIfS3Key(scene.getImageUrl()))
                .audioUrl(toPresignedUrlIfS3Key(scene.getAudioUrl()))
                .subtitleUrl(toPresignedUrlIfS3Key(scene.getSubtitleUrl()))
                .sceneVideoUrl(toPresignedUrlIfS3Key(scene.getSceneVideoUrl()))
                .sceneStatus(scene.getSceneStatus())
                .regenerateCount(scene.getRegenerateCount())
                .userFeedback(scene.getUserFeedback())
                .durationSeconds(scene.getDuration())
                .build();
    }

    /**
     * S3 key를 presigned URL로 변환 (v2.6.1)
     * S3 key가 아닌 경우 (로컬 경로, 이미 URL인 경우) 그대로 반환
     */
    private String toPresignedUrlIfS3Key(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isEmpty()) {
            return urlOrKey;
        }
        // S3 key인 경우 새로운 presigned URL 생성
        if (urlOrKey.startsWith("content/") && storageService.isEnabled()) {
            try {
                return storageService.generatePresignedUrl(urlOrKey);
            } catch (Exception e) {
                log.warn("[ContentService] Failed to generate presigned URL for: {}", urlOrKey, e);
                return urlOrKey;
            }
        }
        return urlOrKey;
    }

    // ========== v2.6.1 URL 무결성 검증 헬퍼 메서드 ==========

    /**
     * 검증된 이미지 URL 업데이트
     * - ERROR: 문자열 등 무효한 URL 저장 방지
     */
    private void updateImageUrlSafe(Long sceneId, String imageUrl) {
        if (UrlValidator.isValid(imageUrl)) {
            sceneUpdateService.updateImageUrl(sceneId, imageUrl);
        } else {
            log.error("[ContentService] Invalid image URL rejected for scene {}: {}",
                    sceneId, imageUrl != null ? imageUrl.substring(0, Math.min(100, imageUrl.length())) : "null");
            // 무효한 URL은 저장하지 않음 - 상태만 FAILED로 변경
            sceneUpdateService.updateStatus(sceneId, "FAILED");
            sceneUpdateService.updateErrorMessage(sceneId, "이미지 URL 생성 실패");
        }
    }

    /**
     * 검증된 오디오 URL 업데이트
     */
    private void updateAudioUrlSafe(Long sceneId, String audioUrl) {
        if (UrlValidator.isValid(audioUrl)) {
            sceneUpdateService.updateAudioUrl(sceneId, audioUrl);
        } else {
            log.error("[ContentService] Invalid audio URL rejected for scene {}: {}",
                    sceneId, audioUrl != null ? audioUrl.substring(0, Math.min(100, audioUrl.length())) : "null");
            sceneUpdateService.updateStatus(sceneId, "FAILED");
            sceneUpdateService.updateErrorMessage(sceneId, "오디오 URL 생성 실패");
        }
    }

    /**
     * 검증된 씬 비디오 URL 업데이트
     */
    private void updateSceneVideoUrlSafe(Long sceneId, String sceneVideoUrl) {
        if (UrlValidator.isValid(sceneVideoUrl)) {
            sceneUpdateService.updateSceneVideoUrl(sceneId, sceneVideoUrl);
        } else {
            log.error("[ContentService] Invalid scene video URL rejected for scene {}: {}",
                    sceneId, sceneVideoUrl != null ? sceneVideoUrl.substring(0, Math.min(100, sceneVideoUrl.length())) : "null");
            sceneUpdateService.updateStatus(sceneId, "FAILED");
            sceneUpdateService.updateErrorMessage(sceneId, "씬 영상 URL 생성 실패");
        }
    }

    private String buildScenesReviewMessage(int completed, int failed, int total) {
        if (completed == total) {
            return "모든 씬이 성공적으로 생성되었습니다. 최종 영상을 생성할 수 있습니다.";
        } else if (failed > 0) {
            return String.format("%d개의 씬 생성에 실패했습니다. 재생성을 요청하거나 최종 영상을 생성할 수 있습니다.", failed);
        } else {
            return String.format("씬 생성 중... (%d/%d 완료)", completed, total);
        }
    }

    /**
     * 특정 씬 재생성
     * v2.6.1: mediaOnly 옵션 추가 - 이미지/영상만 재생성 (TTS/자막 유지)
     */
    @Transactional
    public ContentDto.SceneRegenerateResponse regenerateScene(Long userNo, Long chatId, ContentDto.SceneRegenerateRequest request) {
        validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        Scene scene = sceneMapper.findById(request.getSceneId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "씬을 찾을 수 없습니다."));

        // 프롬프트 업데이트 (새 프롬프트가 제공된 경우)
        if (request.getNewPrompt() != null && !request.getNewPrompt().isEmpty()) {
            sceneUpdateService.updatePrompt(scene.getSceneId(), request.getNewPrompt());
            scene.setPrompt(request.getNewPrompt());
        }

        // 재생성 요청 등록
        sceneUpdateService.requestRegenerate(scene.getSceneId(), request.getUserFeedback());

        // v2.6.1: mediaOnly 옵션 확인
        boolean mediaOnly = request.getMediaOnly() != null && request.getMediaOnly();

        // v2.5.8: 비동기로 재생성 시작 - ScenarioContext는 regenerateSceneAsync 내에서 빌드
        regenerateSceneAsync(userNo, chatId, scene, mediaOnly);

        String message = mediaOnly ? "이미지 재생성을 시작합니다..." : "씬 재생성을 시작합니다...";
        return ContentDto.SceneRegenerateResponse.builder()
                .chatId(chatId)
                .sceneId(scene.getSceneId())
                .status("processing")
                .message(message)
                .build();
    }

    /**
     * v2.6.1: mediaOnly 파라미터 추가 - 이미지/영상만 재생성 옵션
     */
    @Async
    @Transactional
    protected void regenerateSceneAsync(Long userNo, Long chatId, Scene scene, boolean mediaOnly) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path sceneDir = getSceneDir(chatId);
            Files.createDirectories(sceneDir);

            // v2.5.8: DB에서 전체 시나리오 컨텍스트 로드 (필수!)
            ScenarioContext scenarioContext = buildScenarioContext(scene.getVideoId());
            String characterBlock = scenarioContext.getCharacterBlock();

            // v2.9.162: ScenarioContext의 참조 이미지 데이터 재사용 (S3 다운로드 중복 방지)
            VideoDto.ScenarioInfo videoScenarioInfo = buildVideoScenarioInfo(scene.getVideoId(), scenarioContext);
            log.info("[regenerateSceneAsync] VideoDto.ScenarioInfo loaded: {}", videoScenarioInfo != null);

            // v2.8.0: Video에서 creatorId 조회 (필수 - 장르 선택 필수화)
            Video video = videoMapper.findById(scene.getVideoId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + scene.getVideoId()));
            Long creatorId = video.getCreatorId();
            if (creatorId == null) {
                log.warn("[ContentService] ⚠️ creatorId is null for videoId={}. Using default genre 1 (legacy data).", scene.getVideoId());
                creatorId = 1L;
            }

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[regenerateSceneAsync] formatId set: {}", formatId);

            // v2.9.161: Video에서 videoSubtitleId, fontSizeLevel 조회하여 ThreadLocal 설정
            Long videoSubtitleId = video.getVideoSubtitleId();
            if (videoSubtitleId == null) {
                videoSubtitleId = 1L;
            }
            currentVideoSubtitleId.set(videoSubtitleId);
            Integer fontSizeLevel = video.getFontSizeLevel();
            if (fontSizeLevel == null) {
                fontSizeLevel = 3;
            }
            currentFontSizeLevel.set(fontSizeLevel);
            // v2.9.167: 자막 위치
            Integer subtitlePosition = video.getSubtitlePosition();
            if (subtitlePosition == null) {
                subtitlePosition = 1;
            }
            currentSubtitlePosition.set(subtitlePosition);
            // v2.9.174: 폰트 ID, 크리에이터 ID
            Long fontIdVal = (video.getFontId() != null) ? video.getFontId() : 1L;
            currentFontId.set(fontIdVal);
            currentCreatorId.set(creatorId);

            // 기존과 동일한 로직으로 재생성
            sceneUpdateService.updateStatus(scene.getSceneId(), "GENERATING");

            // 1. 이미지/오프닝 영상 재생성
            if ("OPENING".equals(scene.getSceneType())) {
                // ⚠️ 오프닝 프롬프트 필수 검증 (폴백 절대 금지!)
                String videoPrompt = scene.getPrompt();
                if (videoPrompt == null || videoPrompt.trim().isEmpty()) {
                    log.error("[ContentService] ❌ CRITICAL: Scene #{} (OPENING) has no prompt for regeneration! sceneId: {}",
                        scene.getSceneOrder(), scene.getSceneId());
                    throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "오프닝 씬의 프롬프트가 없습니다. 시나리오를 다시 생성해주세요. (sceneId: " + scene.getSceneId() + ")");
                }

                log.info("[ContentService] ✅ Opening prompt validated for regeneration - length: {}", videoPrompt.length());

                // v2.8.0: creatorId 추가, v2.9.77: 시나리오 컨텍스트 포함
                VideoDto.OpeningScene openingScene = VideoDto.OpeningScene.builder()
                        .videoPrompt(videoPrompt)
                        .narration(scene.getNarration())
                        .durationSeconds(scene.getDuration() != null ? scene.getDuration() : 8)
                        .build();

                // v2.9.77: 전체 시나리오 정보와 함께 오프닝 영상 재생성
                String mediaUrl = videoCreatorService.generateOpeningVideo(userNo, openingScene, QualityTier.PREMIUM, characterBlock, creatorId, videoScenarioInfo);

                if (mediaUrl != null) {
                    Path destPath = sceneDir.resolve(scene.getSceneFileName().replace(".mp4", "_video.mp4"));
                    Files.copy(Paths.get(mediaUrl), destPath, StandardCopyOption.REPLACE_EXISTING);
                    scene.setImageUrl(destPath.toString());
                    sceneUpdateService.updateImageUrl(scene.getSceneId(), destPath.toString());
                }
            } else {
                VideoDto.SlideScene slideScene = VideoDto.SlideScene.builder()
                        .order(scene.getSceneOrder())
                        .imagePrompt(scene.getPrompt())
                        .narration(scene.getNarration())
                        .durationSeconds(scene.getDuration())
                        .build();

                // v2.9.25 fix: 각 슬라이드마다 formatId 재설정 (generateImages finally에서 remove됨)
                setCurrentFormatId(formatId);

                // v2.8.0: ScenarioContext + creatorId 포함하여 이미지 생성
                List<String> imagePaths = imageGeneratorService.generateImages(
                        userNo, List.of(slideScene), QualityTier.PREMIUM, scenarioContext, creatorId);

                if (!imagePaths.isEmpty() && imagePaths.get(0) != null && !imagePaths.get(0).startsWith("ERROR")) {
                    Path destPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".png");
                    Files.copy(Paths.get(imagePaths.get(0)), destPath, StandardCopyOption.REPLACE_EXISTING);
                    scene.setImageUrl(destPath.toString());
                    sceneUpdateService.updateImageUrl(scene.getSceneId(), destPath.toString());
                }
            }

            // v2.6.1: mediaOnly=true면 이미지만 재생성하고 MEDIA_READY 상태로 완료
            if (mediaOnly) {
                sceneUpdateService.updateStatus(scene.getSceneId(), "MEDIA_READY");
                log.info("[ContentService] Scene image regenerated successfully (mediaOnly): {}", scene.getSceneId());
                return;
            }

            // 2. TTS 재생성 (v2.8.2: creatorId 전달하여 TTS_INSTRUCTION 적용)
            List<double[]> speechSegments = null; // v2.9.3: 음성 구간
            Double actualAudioDuration = null; // v2.9.175: 실제 오디오 길이 (자막 폴백 타이밍용)
            if (scene.getNarration() != null && !scene.getNarration().isEmpty()) {
                String audioPath = ttsService.generateNarration(userNo, scene.getNarration(), QualityTier.PREMIUM, creatorId);

                if (audioPath != null) {
                    // v2.9.6: 오프닝 씬은 정확히 8초에 맞춤 (Veo 영상 길이)
                    if ("OPENING".equals(scene.getSceneType())) {
                        double openingDuration = 8.0;
                        String adjustedPath = ttsService.adjustAudioTempo(audioPath, openingDuration);
                        if (adjustedPath != null && !adjustedPath.equals(audioPath)) {
                            log.info("[regenerateScene] ✅ Opening TTS tempo adjusted to {}s", openingDuration);
                            audioPath = adjustedPath;
                        }
                    }

                    Path destAudioPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".mp3");
                    Files.copy(Paths.get(audioPath), destAudioPath, StandardCopyOption.REPLACE_EXISTING);
                    scene.setAudioUrl(destAudioPath.toString());
                    sceneUpdateService.updateAudioUrl(scene.getSceneId(), destAudioPath.toString());

                    double actualDuration = ttsService.getAudioDuration(destAudioPath.toString());
                    actualAudioDuration = actualDuration; // v2.9.175: 자막 폴백 타이밍에 실제 오디오 길이 전달
                    int durationSeconds = (int) Math.ceil(actualDuration);
                    // v2.9.10: 오프닝은 8초 고정, 슬라이드는 3-60초 범위 (TTS 길이에 따라 동적)
                    if ("OPENING".equals(scene.getSceneType())) {
                        durationSeconds = 8;
                    } else {
                        durationSeconds = Math.max(3, Math.min(6000, durationSeconds));  // v2.9.76: 180초→6000초 (100분, 나레이션 길이 무제한)
                    }
                    scene.setDuration(durationSeconds);
                    sceneUpdateService.updateDuration(scene.getSceneId(), durationSeconds);

                    // v2.9.175: 문장 경계 감지 (SubtitleServiceImpl과 동일한 필터링 기준)
                    int sentenceCount = countSentences(scene.getNarration());
                    speechSegments = ttsService.detectSentenceBoundaries(destAudioPath.toString(), sentenceCount);
                    log.info("[v2.9.175] Detected {} sentence boundaries for regenerated scene {} (sentenceCount={})",
                            speechSegments != null ? speechSegments.size() : 0, scene.getSceneId(), sentenceCount);
                }
            }

            // 3. 자막 재생성 - v2.9.175: 실제 오디오 길이 전달
            String subtitlePath = generateSceneSubtitle(scene, sceneDir, speechSegments, actualAudioDuration);
            if (subtitlePath != null) {
                scene.setSubtitleUrl(subtitlePath);
                sceneUpdateService.updateSubtitleUrl(scene.getSceneId(), subtitlePath);
            }

            // 4. 개별 씬 영상 재합성
            String sceneVideoPath = composeIndividualSceneVideo(scene, sceneDir);
            if (sceneVideoPath != null) {
                scene.setSceneVideoUrl(sceneVideoPath);
                sceneUpdateService.updateSceneVideoUrl(scene.getSceneId(), sceneVideoPath);
            }

            // 완료 처리
            sceneUpdateService.updateStatus(scene.getSceneId(), "COMPLETED");
            log.info("[ContentService] Scene regenerated successfully: {}", scene.getSceneId());

        } catch (Exception e) {
            log.error("[ContentService] Scene regeneration failed: {}", scene.getSceneId(), e);
            sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * 씬 ZIP 다운로드 정보 조회
     */
    public ContentDto.ScenesZipInfo getScenesZipInfo(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        List<String> includedFiles = scenes.stream()
                .filter(s -> s.getSceneVideoUrl() != null)
                .map(Scene::getSceneFileName)
                .collect(Collectors.toList());

        return ContentDto.ScenesZipInfo.builder()
                .chatId(chatId)
                .filename("scenes_" + chatId + ".zip")
                .sceneCount(includedFiles.size())
                .includedFiles(includedFiles)
                .build();
    }

    /**
     * 씬 ZIP 파일 생성 및 다운로드
     */
    public Resource getScenesZipResource(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        Path zipPath = createScenesZip(chatId, scenes);
        return new FileSystemResource(zipPath);
    }

    private Path createScenesZip(Long chatId, List<Scene> scenes) {
        Path sceneDir = getSceneDir(chatId);
        Path zipPath = sceneDir.resolve("scenes_" + chatId + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            for (Scene scene : scenes) {
                // 씬 영상 추가
                if (scene.getSceneVideoUrl() != null && Files.exists(Paths.get(scene.getSceneVideoUrl()))) {
                    Path videoPath = Paths.get(scene.getSceneVideoUrl());
                    zos.putNextEntry(new ZipEntry(scene.getSceneFileName()));
                    Files.copy(videoPath, zos);
                    zos.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "씬 ZIP 파일 생성 실패");
        }

        return zipPath;
    }

    /**
     * 최종 영상 생성 (모든 씬 합성)
     */
    @Transactional
    public ContentDto.FinalVideoResponse generateFinalVideo(Long userNo, Long chatId, ContentDto.FinalVideoRequest request) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.89: 이미 영상이 완료된 경우 재생성 방지
        String currentStep = conversation.getCurrentStep();
        if ("VIDEO_DONE".equals(currentStep)) {
            log.info("[ContentService] v2.9.89: Video already completed for chat: {}, skipping regeneration", chatId);
            // 이미 완료된 영상 정보 반환
            return ContentDto.FinalVideoResponse.builder()
                    .chatId(chatId)
                    .status("completed")
                    .progress(100)
                    .progressMessage("이미 영상이 생성되어 있습니다.")
                    .downloadReady(true)
                    .build();
        }

        // v2.9.89: S3에 영상이 이미 존재하는 경우도 체크
        if (storageService.isEnabled()) {
            String videoKey = getS3Key(chatId, "video.mp4");
            if (storageService.exists(videoKey)) {
                log.info("[ContentService] v2.9.89: Video already exists in S3 for chat: {}, updating status and skipping regeneration", chatId);
                // 상태 업데이트 (이전 비정상 종료로 인해 상태가 안 바뀐 경우)
                conversationMapper.updateCurrentStep(chatId, "VIDEO_DONE");
                return ContentDto.FinalVideoResponse.builder()
                        .chatId(chatId)
                        .status("completed")
                        .progress(100)
                        .progressMessage("영상이 이미 존재합니다. 상태를 복구했습니다.")
                        .downloadReady(true)
                        .build();
            }
        }

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 모든 씬이 완료되었는지 확인
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(videoId);
        long completedCount = scenes.stream().filter(s -> "COMPLETED".equals(s.getSceneStatus())).count();

        if (completedCount < scenes.size()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    String.format("모든 씬이 완료되어야 합니다. (%d/%d 완료)", completedCount, scenes.size()));
        }

        // v2.9.109: 모든 씬 영상(scene_video_url)이 준비될 때까지 자동 대기 (THUMBNAIL 제외)
        // 씬 상태가 COMPLETED여도 FFmpeg 합성이 완료되지 않으면 scene_video_url이 NULL일 수 있음
        // 최대 60초 대기, 5초마다 DB 재조회
        final int MAX_WAIT_SECONDS = 60;
        final int POLL_INTERVAL_SECONDS = 5;
        int waitedSeconds = 0;

        while (waitedSeconds < MAX_WAIT_SECONDS) {
            // DB에서 씬 목록 재조회
            scenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

            List<Scene> nonThumbnailScenes = scenes.stream()
                    .filter(s -> !"THUMBNAIL".equals(s.getSceneType()))
                    .collect(Collectors.toList());
            long withVideoUrlCount = nonThumbnailScenes.stream()
                    .filter(s -> s.getSceneVideoUrl() != null && !s.getSceneVideoUrl().isBlank())
                    .count();

            if (withVideoUrlCount >= nonThumbnailScenes.size()) {
                log.info("[v2.9.109] All scene video URLs ready: {}/{} (waited {}s)",
                        withVideoUrlCount, nonThumbnailScenes.size(), waitedSeconds);
                break;
            }

            log.info("[v2.9.109] Waiting for scene video URLs: {}/{} ready, waited {}s",
                    withVideoUrlCount, nonThumbnailScenes.size(), waitedSeconds);

            try {
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "씬 영상 대기 중 인터럽트 발생");
            }
            waitedSeconds += POLL_INTERVAL_SECONDS;
        }

        // 최종 확인
        scenes = sceneMapper.findByVideoIdOrderByOrder(videoId);
        List<Scene> finalNonThumbnailScenes = scenes.stream()
                .filter(s -> !"THUMBNAIL".equals(s.getSceneType()))
                .collect(Collectors.toList());
        long finalWithVideoUrlCount = finalNonThumbnailScenes.stream()
                .filter(s -> s.getSceneVideoUrl() != null && !s.getSceneVideoUrl().isBlank())
                .count();

        if (finalWithVideoUrlCount < finalNonThumbnailScenes.size()) {
            log.error("[v2.9.109] Scene video URLs still not ready after {}s: {}/{} scenes have video URL",
                    MAX_WAIT_SECONDS, finalWithVideoUrlCount, finalNonThumbnailScenes.size());
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                    String.format("씬 영상 합성이 %d초 후에도 완료되지 않았습니다. (%d/%d 준비) 관리자에게 문의하세요.",
                            MAX_WAIT_SECONDS, finalWithVideoUrlCount, finalNonThumbnailScenes.size()));
        }

        log.info("[ContentService] Generating final video for chat: {}, scenes: {}", chatId, scenes.size());

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("final_video", 100);
        progressStore.put(chatId, progress);

        // v2.9.92: VIDEO_GENERATING은 비동기 메서드 시작 시 설정 (덮어쓰기 방지)
        // @Async가 protected 메서드에서 작동하지 않아 동기 실행될 수 있으므로,
        // 여기서 설정하지 않고 generateFinalVideoAsync 시작 시 설정
        boolean includeTransitions = request == null || request.isIncludeTransitions();
        generateFinalVideoAsync(chatId, videoId, scenes, includeTransitions, progress);

        // v2.9.92: VIDEO_GENERATING 설정을 여기서 하지 않음 (VIDEO_DONE 덮어쓰기 방지)
        // 비동기 메서드 시작 시 VIDEO_GENERATING 설정됨

        return ContentDto.FinalVideoResponse.builder()
                .chatId(chatId)
                .status("processing")
                .progress(0)
                .progressMessage("최종 영상을 합성합니다...")
                .downloadReady(false)
                .sceneCount(scenes.size())
                .build();
    }

    @Async
    @Transactional
    protected void generateFinalVideoAsync(Long chatId, Long videoId, List<Scene> scenes,
                                            boolean includeTransitions, ProgressInfo progress) {
        try {
            // v2.9.92: VIDEO_GENERATING을 비동기 메서드 시작 시 설정 (덮어쓰기 방지)
            // @Async가 protected 메서드에서 작동하지 않을 수 있으므로 여기서 설정
            conversationMapper.updateCurrentStep(chatId, "VIDEO_GENERATING");
            log.info("[v2.9.92] VIDEO_GENERATING status set for chatId: {} (async method start)", chatId);

            Path videoDir = getVideoDir(chatId);
            Files.createDirectories(videoDir);

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Video video = videoMapper.findById(videoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + videoId));
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[ContentService] formatId set for final video async: {}", formatId);

            progress.message = "씬 영상들을 합성 중...";
            progress.completedCount = 10;

            // v2.6.0: 씬 영상을 로컬로 수집 (S3 URL인 경우 다운로드)
            List<Path> localSceneVideos = new ArrayList<>();
            List<Scene> sortedScenes = scenes.stream()
                    .filter(s -> s.getSceneVideoUrl() != null)
                    .sorted(Comparator.comparing(Scene::getSceneOrder))
                    .collect(Collectors.toList());

            for (int i = 0; i < sortedScenes.size(); i++) {
                Scene scene = sortedScenes.get(i);
                String sceneVideoUrl = scene.getSceneVideoUrl();
                Path localPath;

                progress.message = String.format("씬 영상 준비 중... (%d/%d)", i + 1, sortedScenes.size());
                String filename = "scene_" + scene.getSceneOrder() + "_" + scene.getSceneId() + ".mp4";
                localPath = videoDir.resolve(filename);

                // v2.9.75: prepareMediaFile() 사용하여 S3 key, HTTP URL, 로컬 파일 모두 처리
                Path preparedPath = prepareMediaFile(sceneVideoUrl, videoDir, filename);
                if (preparedPath != null && Files.exists(preparedPath)) {
                    localSceneVideos.add(preparedPath);
                    log.info("[v2.9.75] Scene video prepared: {} -> {}", sceneVideoUrl, preparedPath);
                } else {
                    log.warn("[v2.9.75] Scene video not found: {}", sceneVideoUrl);
                }
            }

            if (localSceneVideos.isEmpty()) {
                throw new RuntimeException("합성할 씬 영상이 없습니다.");
            }

            // 총 영상 길이 계산 (progress 표시용)
            int totalDuration = sortedScenes.stream()
                    .mapToInt(s -> s.getDuration() != null ? s.getDuration() : 10)
                    .sum();

            // v2.9.51: 썸네일 씬 처리 - DB에서 조회하여 사용 (중복 생성 방지)
            log.info("[v2.9.51] ========== 썸네일 씬 처리 시작 ==========");

            // v2.9.51: DB에서 THUMBNAIL 타입 씬 조회 (generateSceneAudioAsync에서 이미 생성됨)
            Optional<Scene> thumbnailSceneOpt = sortedScenes.stream()
                    .filter(s -> "THUMBNAIL".equals(s.getSceneType()))
                    .findFirst();

            if (thumbnailSceneOpt.isPresent()) {
                // v2.9.51: 기존 썸네일 씬 사용 (중복 생성 안 함)
                Scene thumbnailScene = thumbnailSceneOpt.get();
                log.info("[v2.9.51] ✅ Found existing THUMBNAIL scene in DB: sceneId={}, order={}, url={}",
                        thumbnailScene.getSceneId(), thumbnailScene.getSceneOrder(), thumbnailScene.getSceneVideoUrl());

                try {
                    progress.message = "썸네일 씬 준비 중...";

                    // prepareMediaFile()을 사용하여 S3 URL 또는 로컬 파일 처리
                    String thumbnailSceneUrl = thumbnailScene.getSceneVideoUrl();
                    Path thumbnailVideoPath = prepareMediaFile(
                            thumbnailSceneUrl,
                            videoDir,
                            "scene_thumbnail.mp4"
                    );

                    if (thumbnailVideoPath != null && Files.exists(thumbnailVideoPath)) {
                        // 파일 검증
                        long videoSize = Files.size(thumbnailVideoPath);
                        boolean hasAudio = hasAudioTrack(thumbnailVideoPath.toString());

                        log.info("[v2.9.51] Thumbnail scene validated: {} ({}bytes, audio: {})",
                                thumbnailVideoPath, videoSize, hasAudio);

                        // 씬 리스트에 추가
                        localSceneVideos.add(thumbnailVideoPath);
                        totalDuration += 2;  // 썸네일 2초 추가

                        log.info("[v2.9.51] ✅✅✅ Thumbnail scene added from DB (no recreation)!");
                        log.info("[v2.9.51] Total scenes: {}, Thumbnail at position: {} (LAST)",
                                localSceneVideos.size(), localSceneVideos.size());
                    } else {
                        log.warn("[v2.9.51] ⚠️ Failed to prepare thumbnail scene file, skipping");
                    }

                } catch (Exception e) {
                    log.error("[v2.9.51] ❌ Failed to add thumbnail scene from DB: {}", e.getMessage(), e);
                    log.warn("[v2.9.51] Continuing without thumbnail...");
                }
            } else {
                // v2.9.89: THUMBNAIL 씬이 없으면 생성 (generateVideoAsync와 동일 로직)
                log.info("[v2.9.89] No THUMBNAIL scene found, creating one now...");
                try {
                    progress.message = "썸네일 2초 영상 생성 중...";

                    Path sceneDir = getSceneDir(chatId);
                    int regularSceneCount = (int) sortedScenes.stream()
                            .filter(s -> !"THUMBNAIL".equals(s.getSceneType()))
                            .count();

                    // 썸네일 씬 생성 (새 트랜잭션)
                    createThumbnailSceneInNewTransaction(chatId, videoId, sceneDir, regularSceneCount, progress);

                    // DB에서 썸네일 씬 재조회
                    Optional<Scene> newThumbnailOpt = sceneMapper.findByVideoIdOrderByOrder(videoId).stream()
                            .filter(s -> "THUMBNAIL".equals(s.getSceneType()))
                            .findFirst();

                    if (newThumbnailOpt.isPresent()) {
                        Scene thumbnailScene = newThumbnailOpt.get();
                        String thumbnailSceneUrl = thumbnailScene.getSceneVideoUrl();
                        Path thumbnailVideoPath = prepareMediaFile(thumbnailSceneUrl, videoDir, "scene_thumbnail.mp4");

                        if (thumbnailVideoPath != null && Files.exists(thumbnailVideoPath)) {
                            localSceneVideos.add(thumbnailVideoPath);
                            totalDuration += 2;
                            log.info("[v2.9.89] ✅ Thumbnail scene created and added: {}", thumbnailVideoPath);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[v2.9.89] ⚠️ Thumbnail scene creation failed (non-critical): {}", e.getMessage());
                    // 썸네일 없이 계속 진행
                }
            }

            log.info("[v2.9.51] ========== 썸네일 씬 처리 완료 ==========");

            // v2.9.9: 씬 파일 그대로 이어붙이기 (재인코딩/효과 없음)
            Path outputPath = videoDir.resolve("video_" + chatId + ".mp4");

            progress.message = "영상 합성 중...";
            progress.completedCount = 50;

            log.info("[ContentService] v2.9.39 Final video: {} scenes (including thumbnail), total {}s (pure concat, no effects)",
                    localSceneVideos.size(), totalDuration);

            // concat demuxer로 합성 (씬 파일 그대로 이어붙이기)
            concatSceneVideos(localSceneVideos, outputPath);

            if (!Files.exists(outputPath)) {
                throw new RuntimeException("FFmpeg 합성 실패");
            }

            // S3 업로드 - v2.9.8: 스트리밍 업로드로 OOM 방지
            String savedVideoKey = null;
            if (storageService.isEnabled()) {
                progress.message = "클라우드에 업로드 중...";
                String videoKey = getS3Key(chatId, "video.mp4");
                long fileSize = Files.size(outputPath);
                try (InputStream is = Files.newInputStream(outputPath)) {
                    storageService.upload(videoKey, is, "video/mp4", fileSize);
                }
                savedVideoKey = videoKey;
                log.info("Final video uploaded to S3: {} (size: {} bytes)", videoKey, fileSize);
            }

            // totalDuration은 이미 위에서 계산됨 (progress 표시용)

            progress.status = "completed";
            progress.completedCount = 100;
            progress.message = "최종 영상 생성 완료!";
            progress.filePath = outputPath.toString();
            progress.totalDuration = totalDuration;

            // Video 상태 업데이트 - v2.9.30: S3 key 저장
            String videoUrlToSave = savedVideoKey != null ? savedVideoKey : outputPath.toString();

            // v2.9.49: thumbnailUrl 유지 (null로 덮어쓰지 않음)
            Video currentVideo = videoMapper.findById(videoId).orElse(null);
            String existingThumbnailUrl = (currentVideo != null) ? currentVideo.getThumbnailUrl() : null;
            videoMapper.updateAsCompleted(videoId, videoUrlToSave, existingThumbnailUrl);

            // v2.9.38: S3 업로드 완료 시 presigned URL 만료 시간 저장 (3시간)
            if (savedVideoKey != null) {
                LocalDateTime expiresAt = LocalDateTime.now().plusHours(3);
                videoMapper.updatePresignedUrlExpiresAt(videoId, expiresAt);
                log.info("Presigned URL expiry set for video {}: {}", videoId, expiresAt);
            }

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "VIDEO_DONE");
            log.info("[v2.9.92] ✅ VIDEO_DONE status set for chatId: {}", chatId);

            // v2.9.30: 최종 영상 결과를 채팅 메시지로 저장 (수정: 실제 S3 key 사용)
            try {
                Video completedVideo = videoMapper.findById(videoId).orElse(null);
                String videoUrl = savedVideoKey != null
                    ? storageService.generatePresignedUrl(savedVideoKey)
                    : "local://" + outputPath.toString();

                String metadata = objectMapper.writeValueAsString(Map.of(
                    "videoUrl", videoUrl,
                    "title", completedVideo != null ? completedVideo.getTitle() : "Untitled",
                    "duration", progress.totalDuration
                ));

                ConversationMessage message = ConversationMessage.builder()
                    .conversationId(chatId)
                    .role("assistant")
                    .content("최종 영상이 완성되었습니다!")
                    .messageType("VIDEO_RESULT")
                    .metadata(metadata)
                    .build();

                messageMapper.insert(message);
                log.info("[ContentService] Saved video result message for chat: {}", chatId);
            } catch (Exception e1) {
                log.error("[ContentService] Failed to save video result message", e1);
                // 메시지 저장 실패해도 영상은 반환
            }

        } catch (Exception e) {
            log.error("[ContentService] Final video generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "최종 영상 생성 실패: " + e.getMessage();
            videoMapper.updateAsFailed(videoId, e.getMessage());
            // v2.9.0: 영상 합성 실패 시 VIDEO_FAILED 상태로 변경 (재시도 가능)
            conversationMapper.updateCurrentStep(chatId, "VIDEO_FAILED");
        }
    }

    /**
     * 씬 진행 상태 조회
     */
    public ContentDto.ScenesGenerateResponse getScenesProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        List<ContentDto.SceneInfo> sceneInfos = scenes.stream()
                .map(this::convertToSceneInfo)
                .collect(Collectors.toList());

        if (progress == null || !"scenes".equals(progress.processType)) {
            return ContentDto.ScenesGenerateResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .totalCount(scenes.size())
                    .completedCount((int) scenes.stream().filter(s -> "COMPLETED".equals(s.getSceneStatus())).count())
                    .progressMessage("대기 중")
                    .scenes(sceneInfos)
                    .build();
        }

        return ContentDto.ScenesGenerateResponse.builder()
                .chatId(chatId)
                .status(progress.status)
                .totalCount(progress.totalCount)
                .completedCount(progress.completedCount)
                .progressMessage(progress.message)
                .scenes(sceneInfos)
                .build();
    }

    /**
     * 최종 영상 진행 상태 조회
     */
    public ContentDto.FinalVideoResponse getFinalVideoProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);

        if (progress == null || !"final_video".equals(progress.processType)) {
            return ContentDto.FinalVideoResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .downloadReady(checkVideoReady(chatId))
                    .progressMessage("대기 중")
                    .build();
        }

        return ContentDto.FinalVideoResponse.builder()
                .chatId(chatId)
                .status(progress.status)
                .progress(progress.completedCount)
                .progressMessage(progress.message)
                .downloadReady("completed".equals(progress.status))
                .durationSeconds(progress.totalDuration)
                .filePath(progress.filePath)
                .build();
    }

    // ========== v2.4.0 Private Methods ==========

    private Path getSceneDir(Long chatId) {
        return Path.of(CONTENT_DIR, String.valueOf(chatId), "scenes");
    }

    /**
     * v2.9.0: 로컬 콘텐츠 폴더 정리 (시나리오 재생성 시 호출)
     * 디스크 공간 절약을 위해 이전 생성된 파일들을 삭제
     */
    private void cleanupLocalContent(Long chatId) {
        try {
            Path contentDir = Path.of(CONTENT_DIR, String.valueOf(chatId));
            if (Files.exists(contentDir)) {
                // 재귀적으로 모든 파일과 디렉토리 삭제
                Files.walk(contentDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.warn("[ContentService] Failed to delete file: {}", path, e);
                            }
                        });
                log.info("[ContentService] 🗑️ Local content folder cleaned up: {}", contentDir);
            }
        } catch (IOException e) {
            log.warn("[ContentService] Failed to cleanup local content for chat: {}", chatId, e);
            // 로컬 파일 삭제 실패는 치명적이지 않으므로 계속 진행
        }
    }

    /**
     * 씬 파일들을 S3에 업로드하고 DB URL도 업데이트
     * v2.6.1 - DB URL을 S3 URL로 업데이트하여 새로고침 후에도 정상 동작
     */
    private void uploadScenesToS3(Long chatId, Path sceneDir) {
        try {
            // 해당 채팅의 비디오와 씬 정보 조회
            Video video = getVideoByConversationId(chatId);
            if (video == null) {
                log.warn("[S3] No video found for chatId: {}", chatId);
                return;
            }
            List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());
            Map<Integer, Scene> sceneMap = scenes.stream()
                    .collect(java.util.stream.Collectors.toMap(Scene::getSceneOrder, s -> s));

            // v2.9.8: 스트리밍 업로드로 OOM 방지
            Files.list(sceneDir)
                    .filter(p -> p.toString().endsWith(".mp4") || p.toString().endsWith(".png") ||
                                 p.toString().endsWith(".mp3") || p.toString().endsWith(".ass"))
                    .forEach(p -> {
                        try {
                            String filename = p.getFileName().toString();
                            String s3Key = getS3Key(chatId, "scenes/" + filename);
                            String contentType = filename.endsWith(".mp4") ? "video/mp4" :
                                                 filename.endsWith(".png") ? "image/png" :
                                                 filename.endsWith(".mp3") ? "audio/mpeg" : "text/plain";
                            long fileSize = Files.size(p);
                            try (InputStream is = Files.newInputStream(p)) {
                                storageService.upload(s3Key, is, contentType, fileSize);
                            }

                            // v2.6.1: S3 key를 저장 (presigned URL 대신)
                            // 다운로드 시 새로운 presigned URL을 생성하여 만료 문제 방지

                            // 파일명에서 씬 순서 추출하여 DB 업데이트
                            // 예: scene_00.mp3, scene_01.png, scene_00_opening.mp4
                            Integer sceneOrder = extractSceneOrder(filename);
                            if (sceneOrder != null && sceneMap.containsKey(sceneOrder)) {
                                Scene scene = sceneMap.get(sceneOrder);

                                if (filename.endsWith(".mp3")) {
                                    sceneUpdateService.updateAudioUrl(scene.getSceneId(), s3Key);
                                    log.debug("[S3] Updated audio URL (S3 key) for scene {}: {}", sceneOrder, s3Key);
                                } else if (filename.endsWith(".ass")) {
                                    sceneUpdateService.updateSubtitleUrl(scene.getSceneId(), s3Key);
                                    log.debug("[S3] Updated subtitle URL (S3 key) for scene {}: {}", sceneOrder, s3Key);
                                } else if (filename.contains("_opening") && filename.endsWith(".mp4")) {
                                    // scene_00_opening.mp4 = 합성된 씬 영상
                                    sceneUpdateService.updateSceneVideoUrl(scene.getSceneId(), s3Key);
                                    log.debug("[S3] Updated scene video URL (S3 key) for scene {}: {}", sceneOrder, s3Key);
                                } else if (filename.endsWith(".mp4") && !filename.contains("_opening") && !filename.contains("_video")) {
                                    // scene_01.mp4 = 합성된 슬라이드 씬 영상
                                    sceneUpdateService.updateSceneVideoUrl(scene.getSceneId(), s3Key);
                                    log.debug("[S3] Updated scene video URL (S3 key) for scene {}: {}", sceneOrder, s3Key);
                                } else if (filename.endsWith(".png")) {
                                    sceneUpdateService.updateImageUrl(scene.getSceneId(), s3Key);
                                    log.debug("[S3] Updated image URL (S3 key) for scene {}: {}", sceneOrder, s3Key);
                                }
                            }

                            log.debug("Scene file uploaded to S3: {}", s3Key);
                        } catch (IOException e) {
                            log.error("Failed to upload scene file to S3: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list scene directory for S3 upload", e);
        }
    }

    /**
     * 파일명에서 씬 순서 추출
     * 예: scene_00.mp3 -> 0, scene_01.png -> 1, scene_00_opening.mp4 -> 0
     */
    private Integer extractSceneOrder(String filename) {
        try {
            // scene_00, scene_01 패턴에서 숫자 추출
            if (filename.startsWith("scene_")) {
                String numPart = filename.substring(6, 8); // "00", "01" 등
                return Integer.parseInt(numPart);
            }
        } catch (Exception e) {
            log.warn("Failed to extract scene order from filename: {}", filename);
        }
        return null;
    }

    /**
     * v2.7.2: 개별 씬 미디어 즉시 S3 업로드 (필수화)
     * 생성 직후 바로 S3에 저장하여 서버 재시작/실패 시에도 콘텐츠 보존
     * ⚠️ S3 업로드 실패 시 예외를 던져서 씬 생성도 실패로 처리 (로컬 경로가 DB에 저장되는 버그 방지)
     *
     * @param chatId 채팅 ID
     * @param localPath 로컬 파일 경로
     * @param sceneOrder 씬 순서
     * @param mediaType "video" 또는 "image"
     * @return S3 key (업로드 성공)
     * @throws RuntimeException S3 업로드 실패 시
     */
    private String uploadSceneMediaToS3(Long chatId, String localPath, int sceneOrder, String mediaType) {
        // v2.7.2: S3가 비활성화된 경우 에러 (로컬 경로 저장 방지)
        if (!storageService.isEnabled()) {
            log.error("[S3] Storage service is not enabled! Cannot save scene media.");
            throw new RuntimeException("S3 스토리지가 비활성화되어 있습니다. 관리자에게 문의하세요.");
        }

        if (localPath == null || localPath.isEmpty()) {
            log.error("[S3] Local path is null or empty for scene {}", sceneOrder);
            throw new RuntimeException("미디어 파일 경로가 없습니다.");
        }

        // v2.7.2: 최대 3회 재시도
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Path filePath = Path.of(localPath);
                if (!Files.exists(filePath)) {
                    log.error("[S3] Local file not found: {} (attempt {}/{})", localPath, attempt, maxRetries);
                    throw new RuntimeException("로컬 파일이 존재하지 않습니다: " + localPath);
                }

                // v2.9.8: 스트리밍 업로드로 OOM 방지
                String extension = "video".equals(mediaType) ? "mp4" : "png";
                String contentType = "video".equals(mediaType) ? "video/mp4" : "image/png";
                String filename = String.format("scene_%02d.%s", sceneOrder, extension);
                String s3Key = getS3Key(chatId, "scenes/" + filename);
                long fileSize = Files.size(filePath);

                try (InputStream is = Files.newInputStream(filePath)) {
                    storageService.upload(s3Key, is, contentType, fileSize);
                }
                log.info("[S3] Scene media uploaded immediately: {} ({}, {} bytes) - attempt {}/{}", s3Key, mediaType, fileSize, attempt, maxRetries);

                // 업로드 성공 후 로컬 파일 삭제 (디스크 공간 절약)
                try {
                    Files.deleteIfExists(filePath);
                    log.debug("[S3] Local file deleted after upload: {}", localPath);
                } catch (Exception deleteErr) {
                    log.warn("[S3] Failed to delete local file after upload: {}", localPath);
                }

                // v2.6.1: S3 key 반환 (presigned URL은 조회 시점에 생성)
                return s3Key;

            } catch (Exception e) {
                lastException = e;
                log.warn("[S3] Upload attempt {}/{} failed for scene {}: {}",
                        attempt, maxRetries, sceneOrder, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        // 재시도 전 대기 (지수 백오프: 1초, 2초, 4초)
                        Thread.sleep(1000L * (1 << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // v2.7.2: 모든 재시도 실패 시 예외 던지기 (로컬 경로가 DB에 저장되는 것 방지)
        log.error("[S3] All {} upload attempts failed for chatId={}, sceneOrder={}",
                maxRetries, chatId, sceneOrder);
        throw new RuntimeException("S3 업로드 실패 (재시도 " + maxRetries + "회 모두 실패): " +
                (lastException != null ? lastException.getMessage() : "알 수 없는 오류"));
    }

    // ========== v2.5.0 씬 프리뷰 및 나레이션 편집 ==========

    /**
     * 씬 프리뷰 생성 (이미지/영상만 먼저)
     */
    @Transactional
    public ContentDto.ScenePreviewResponse generateScenePreview(Long userNo, Long chatId, ContentDto.ScenePreviewRequest request) {
        Conversation conversation = validateConversation(userNo, chatId);

        // v2.9.172: 이미 프리뷰 생성 중이면 중복 실행 방지 (썸네일 중복 생성 원인)
        String currentStep = conversation.getCurrentStep();
        if ("PREVIEWS_GENERATING".equals(currentStep)) {
            log.warn("[v2.9.172] Scene preview already generating for chatId: {}, skipping duplicate request", chatId);
            Video existingVideo = getVideoByConversationId(chatId);
            List<Scene> existingScenes = sceneMapper.findByVideoIdOrderByOrder(existingVideo.getVideoId());
            String aspectRatio = getAspectRatioByVideo(existingVideo);
            final Long finalChatId = chatId;
            List<ContentDto.ScenePreviewInfo> previews = existingScenes.stream()
                    .map(scene -> convertToScenePreviewInfo(scene, finalChatId))
                    .collect(Collectors.toList());
            return ContentDto.ScenePreviewResponse.builder()
                    .chatId(chatId)
                    .status("processing")
                    .totalCount(existingScenes.size())
                    .completedCount(0)
                    .progressMessage("이미 씬 프리뷰를 생성 중입니다...")
                    .previews(previews)
                    .aspectRatio(aspectRatio)
                    .build();
        }

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        Scenario scenario = scenarioMapper.findByVideoId(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "시나리오를 찾을 수 없습니다."));

        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

        if (allScenes.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "생성할 씬이 없습니다. 먼저 시나리오를 생성하세요.");
        }

        log.info("[ContentService] Generating scene previews for chat: {}, scenes: {}", chatId, allScenes.size());

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("scene_preview", allScenes.size());
        progressStore.put(chatId, progress);

        // 프리뷰 생성 (이미지/영상만, TTS 없음) - 비동기
        generateScenePreviewsAsync(userNo, chatId, videoId, allScenes, progress);

        // 대화 상태 업데이트
        conversationMapper.updateCurrentStep(chatId, "PREVIEWS_GENERATING");

        final Long finalChatId = chatId;
        List<ContentDto.ScenePreviewInfo> previews = allScenes.stream()
                .map(scene -> convertToScenePreviewInfo(scene, finalChatId))
                .collect(Collectors.toList());

        // v2.9.25: aspectRatio 조회
        String aspectRatio = getAspectRatioByVideo(video);

        return ContentDto.ScenePreviewResponse.builder()
                .chatId(chatId)
                .status("processing")
                .totalCount(allScenes.size())
                .completedCount(0)
                .progressMessage("씬 프리뷰를 생성합니다...")
                .previews(previews)
                .aspectRatio(aspectRatio)
                .build();
    }

    @Async
    @Transactional
    public void generateScenePreviewsAsync(Long userNo, Long chatId, Long videoId, List<Scene> allScenes,
                                               ProgressInfo progress) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            // v2.5.8: DB에서 전체 시나리오 컨텍스트 로드 (폴백 없음)
            ScenarioContext scenarioContext = buildScenarioContext(videoId);
            String characterBlock = scenarioContext.getCharacterBlock();

            // v2.9.162: ScenarioContext의 참조 이미지 데이터 재사용 (S3 다운로드 중복 방지)
            VideoDto.ScenarioInfo videoScenarioInfo = buildVideoScenarioInfo(videoId, scenarioContext);
            log.info("[generateScenePreviewsAsync] VideoDto.ScenarioInfo loaded: {}", videoScenarioInfo != null);

            // v2.8.0: Video에서 creatorId 조회 (필수 - 장르 선택 필수화)
            Video video = videoMapper.findById(videoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video를 찾을 수 없습니다: " + videoId));
            Long creatorId = video.getCreatorId();
            if (creatorId == null) {
                log.warn("[ContentService] ⚠️ creatorId is null for videoId={}. Using default genre 1 (legacy data).", videoId);
                creatorId = 1L;
            }

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Long formatId = video.getFormatId();
            if (formatId == null) {
                formatId = 1L;  // 기본값: YOUTUBE_STANDARD
            }
            setCurrentFormatId(formatId);
            log.info("[ContentService] formatId set for scene preview: {}", formatId);

            Path sceneDir = getSceneDir(chatId);
            Files.createDirectories(sceneDir);

            for (int i = 0; i < allScenes.size(); i++) {
                Scene scene = allScenes.get(i);
                progress.message = String.format("씬 %d/%d 미디어 생성 중...", i + 1, allScenes.size());

                try {
                    // 상태 업데이트
                    sceneUpdateService.updateStatus(scene.getSceneId(), "GENERATING");

                    String mediaUrl = null;

                    if ("OPENING".equals(scene.getSceneType())) {
                        // v2.8.0: 오프닝 영상 생성 (Veo) - creatorId 추가, v2.9.77: 시나리오 컨텍스트 포함
                        VideoDto.OpeningScene openingScene = VideoDto.OpeningScene.builder()
                                .videoPrompt(scene.getPrompt())
                                .narration(scene.getNarration())
                                .durationSeconds(8)
                                .build();

                        // v2.9.77: 전체 시나리오 정보와 함께 오프닝 영상 생성
                        mediaUrl = videoCreatorService.generateOpeningVideo(userNo, openingScene,
                                QualityTier.PREMIUM, characterBlock, creatorId, videoScenarioInfo);

                        if (mediaUrl == null) {
                            throw new RuntimeException("오프닝 영상 생성 실패");
                        }

                        // v2.7.2: S3 업로드 (실패 시 예외 던짐 - 로컬 경로 저장 방지)
                        String s3Key = uploadSceneMediaToS3(chatId, mediaUrl, scene.getSceneOrder(), "video");
                        log.info("[v2.9.177] S3 upload success for opening: sceneId={}, s3Key={}", scene.getSceneId(), s3Key);
                        scene.setImageUrl(s3Key);
                        sceneUpdateService.updateImageUrl(scene.getSceneId(), s3Key);
                        log.info("[v2.9.177] DB imageUrl updated for opening: sceneId={}", scene.getSceneId());

                    } else {
                        // v2.8.0: 슬라이드 이미지 생성 (Gemini 3) - creatorId 추가
                        VideoDto.SlideScene slideScene = VideoDto.SlideScene.builder()
                                .order(scene.getSceneOrder())
                                .imagePrompt(scene.getPrompt())
                                .narration(scene.getNarration())
                                .durationSeconds(10)
                                .build();

                        // v2.9.25 fix: 각 슬라이드마다 formatId 재설정 (generateImages finally에서 remove됨)
                        setCurrentFormatId(formatId);

                        List<String> imagePaths = imageGeneratorService.generateImages(
                                userNo, List.of(slideScene), QualityTier.PREMIUM, scenarioContext, creatorId);

                        // v2.6.1: ERROR: 문자열 필터링 - DB에 에러 메시지 저장 방지
                        if (imagePaths == null || imagePaths.isEmpty() || imagePaths.get(0) == null
                                || imagePaths.get(0).startsWith("ERROR:")) {
                            String errorDetail = (imagePaths != null && !imagePaths.isEmpty()) ? imagePaths.get(0) : "null";
                            log.warn("[ContentService] Image generation failed for scene {}: {}", scene.getSceneId(), errorDetail);
                            throw new RuntimeException("이미지 생성 실패: " + errorDetail);
                        }

                        mediaUrl = imagePaths.get(0);
                        // v2.7.2: S3 업로드 (실패 시 예외 던짐 - 로컬 경로 저장 방지)
                        String s3Key = uploadSceneMediaToS3(chatId, mediaUrl, scene.getSceneOrder(), "image");
                        log.info("[v2.9.177] S3 upload success for slide: sceneId={}, s3Key={}", scene.getSceneId(), s3Key);
                        scene.setImageUrl(s3Key);
                        sceneUpdateService.updateImageUrl(scene.getSceneId(), s3Key);
                        log.info("[v2.9.177] DB imageUrl updated for slide: sceneId={}", scene.getSceneId());
                    }

                    // 미디어 생성 완료 - MEDIA_READY 상태로 변경 (TTS 전)
                    sceneUpdateService.updateStatus(scene.getSceneId(), "MEDIA_READY");
                    log.info("[v2.9.177] DB status updated to MEDIA_READY: sceneId={}", scene.getSceneId());
                    progress.completedCount = i + 1;

                } catch (Exception e) {
                    log.error("[ContentService] Scene preview failed for scene {}: {}", scene.getSceneId(), e.getMessage(), e);
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                    // v2.6.0: 에러 메시지 저장
                    sceneUpdateService.updateErrorMessage(scene.getSceneId(), e.getMessage());
                    progress.completedCount = i + 1;  // 실패도 완료로 카운트
                }
            }

            progress.status = "completed";
            progress.message = "모든 씬 프리뷰가 생성되었습니다. 나레이션을 확인하세요.";

            // v2.9.168: Video에서 사용자가 선택한 thumbnailId 조회하여 자동 썸네일 생성
            Long selectedThumbnailId = video.getThumbnailId();
            log.info("[v2.9.168] Auto-generating thumbnail with user-selected thumbnailId: {}", selectedThumbnailId);

            // v2.9.86: 이미지 생성 직후 썸네일 자동 생성 (재시도 로직 추가)
            // 최대 3회 재시도, 실패해도 첫 슬라이드 이미지로 엔딩씬 생성 가능
            final int MAX_THUMBNAIL_RETRIES = 3;
            boolean thumbnailGenerated = false;
            for (int attempt = 1; attempt <= MAX_THUMBNAIL_RETRIES; attempt++) {
                try {
                    log.info("[v2.9.86] Generating thumbnail (attempt {}/{})", attempt, MAX_THUMBNAIL_RETRIES);
                    thumbnailService.generateThumbnail(userNo, chatId, selectedThumbnailId);
                    log.info("[v2.9.86] ✅ Thumbnail generated and saved to DB");
                    thumbnailGenerated = true;
                    break;
                } catch (Exception e) {
                    log.warn("[v2.9.86] ⚠️ Thumbnail generation attempt {} failed: {}", attempt, e.getMessage());
                    if (attempt < MAX_THUMBNAIL_RETRIES) {
                        try {
                            // 지수 백오프: 2초, 4초, 8초
                            long delayMs = 2000L * (1L << (attempt - 1));
                            log.info("[v2.9.86] Retrying thumbnail generation in {}ms...", delayMs);
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (!thumbnailGenerated) {
                log.warn("[v2.9.86] ⚠️ Thumbnail generation failed after {} attempts. Ending scene will use first slide image.", MAX_THUMBNAIL_RETRIES);
            }

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "PREVIEWS_DONE");

        } catch (Exception e) {
            log.error("[ContentService] Scene preview generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "씬 프리뷰 생성 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * 씬 프리뷰 조회
     */
    public ContentDto.ScenePreviewResponse getScenePreview(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        final Long finalChatId = chatId;
        List<ContentDto.ScenePreviewInfo> previews = scenes.stream()
                .map(scene -> convertToScenePreviewInfo(scene, finalChatId))
                .collect(Collectors.toList());

        long mediaReadyCount = scenes.stream()
                .filter(s -> "MEDIA_READY".equals(s.getSceneStatus()) || "COMPLETED".equals(s.getSceneStatus()))
                .count();

        String status = "idle";
        String message = "대기 중";

        if (progress != null && "scene_preview".equals(progress.processType)) {
            status = progress.status;
            message = progress.message;
        } else if (mediaReadyCount == scenes.size()) {
            status = "completed";
            message = "모든 씬 프리뷰가 준비되었습니다.";
        }

        // v2.9.25: aspectRatio 조회
        String aspectRatio = getAspectRatioByVideo(video);

        return ContentDto.ScenePreviewResponse.builder()
                .chatId(chatId)
                .status(status)
                .totalCount(scenes.size())
                .completedCount((int) mediaReadyCount)
                .progressMessage(message)
                .previews(previews)
                .aspectRatio(aspectRatio)
                .build();
    }

    /**
     * 씬 나레이션 편집
     */
    @Transactional
    public ContentDto.SceneNarrationEditResponse editSceneNarration(Long userNo, Long chatId, ContentDto.SceneNarrationEditRequest request) {
        validateConversation(userNo, chatId);

        Scene scene = sceneMapper.findById(request.getSceneId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "씬을 찾을 수 없습니다."));

        String oldNarration = scene.getNarration();
        String newNarration = request.getNewNarration();

        if (newNarration == null || newNarration.trim().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "나레이션 텍스트가 비어있습니다.");
        }

        // 나레이션 업데이트
        sceneUpdateService.updateNarration(scene.getSceneId(), newNarration.trim());

        log.info("[ContentService] Scene narration edited - sceneId: {}, old: {}, new: {}",
                scene.getSceneId(), oldNarration, newNarration);

        return ContentDto.SceneNarrationEditResponse.builder()
                .chatId(chatId)
                .sceneId(scene.getSceneId())
                .status("success")
                .oldNarration(oldNarration)
                .newNarration(newNarration.trim())
                .message("나레이션이 수정되었습니다.")
                .build();
    }

    /**
     * TTS/자막 생성 (나레이션 편집 완료 후)
     */
    @Transactional
    public ContentDto.SceneAudioGenerateResponse generateSceneAudio(Long userNo, Long chatId, ContentDto.SceneAudioGenerateRequest request) {
        validateConversation(userNo, chatId);

        // v2.9.30: 다른 채팅의 진행 중인 콘텐츠 생성이 있는지 확인
        Optional<Conversation> inProgressConversation =
                conversationMapper.findInProgressConversationExcludingCurrent(userNo, chatId);
        if (inProgressConversation.isPresent()) {
            Long inProgressChatId = inProgressConversation.get().getConversationId();
            log.warn("[ContentService] User {} has content generation in progress: chatId={}", userNo, inProgressChatId);
            throw new ApiException(ErrorCode.CONTENT_GENERATION_IN_PROGRESS,
                    "다른 영상이 생성 중입니다 (채팅 #" + inProgressChatId + "). 완료 후 다시 시도해주세요.");
        }

        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(videoId);

        // 특정 씬만 지정된 경우 필터링
        List<Scene> targetScenes;
        if (request != null && request.getSceneIds() != null && !request.getSceneIds().isEmpty()) {
            Set<Long> sceneIdSet = new HashSet<>(request.getSceneIds());
            targetScenes = allScenes.stream()
                    .filter(s -> sceneIdSet.contains(s.getSceneId()))
                    .collect(Collectors.toList());
        } else {
            // 미디어가 준비된 씬만 (MEDIA_READY 상태)
            targetScenes = allScenes.stream()
                    .filter(s -> "MEDIA_READY".equals(s.getSceneStatus()))
                    .collect(Collectors.toList());
        }

        if (targetScenes.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "TTS를 생성할 씬이 없습니다.");
        }

        // v2.9.55: 예상 총 씬 개수 = targetScenes + 썸네일(+1)
        int expectedTotalScenes = targetScenes.size() + 1;

        log.info("[ContentService] Generating scene audio for chat: {}, target scenes: {}, expected total (including thumbnail): {}",
                chatId, targetScenes.size(), expectedTotalScenes);

        // 진행 상태 초기화 (썸네일 씬 포함)
        ProgressInfo progress = new ProgressInfo("scene_audio", expectedTotalScenes);
        progressStore.put(chatId, progress);

        boolean includeSubtitle = request == null || request.isIncludeSubtitle();

        // TTS/자막 생성 (비동기)
        generateSceneAudioAsync(userNo, chatId, videoId, targetScenes, includeSubtitle, progress);

        List<ContentDto.SceneAudioInfo> audioInfos = targetScenes.stream()
                .map(s -> ContentDto.SceneAudioInfo.builder()
                        .sceneId(s.getSceneId())
                        .sceneOrder(s.getSceneOrder())
                        .status("pending")
                        .build())
                .collect(Collectors.toList());

        return ContentDto.SceneAudioGenerateResponse.builder()
                .chatId(chatId)
                .status("processing")
                .totalCount(expectedTotalScenes)
                .completedCount(0)
                .progressMessage("TTS 및 자막을 생성합니다...")
                .audioInfos(audioInfos)
                .build();
    }

    @Async
    @Transactional
    protected void generateSceneAudioAsync(Long userNo, Long chatId, Long videoId, List<Scene> targetScenes,
                                            boolean includeSubtitle, ProgressInfo progress) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path sceneDir = getSceneDir(chatId);

            // v2.8.2: Video에서 creatorId 조회 (TTS_INSTRUCTION 적용용)
            Video video = videoMapper.findById(videoId).orElse(null);
            Long creatorId = (video != null && video.getCreatorId() != null) ? video.getCreatorId() : CreatorConfigService.DEFAULT_CREATOR_ID;
            log.info("[generateSceneAudioAsync] Using creatorId: {} for TTS_INSTRUCTION", creatorId);

            // v2.9.25: Video에서 formatId 조회하여 ThreadLocal 설정
            Long formatId = (video != null && video.getFormatId() != null) ? video.getFormatId() : 1L;
            setCurrentFormatId(formatId);
            log.info("[generateSceneAudioAsync] formatId set for audio/subtitle: {}", formatId);

            // v2.9.161: Video에서 videoSubtitleId, fontSizeLevel 조회하여 ThreadLocal 설정
            Long videoSubtitleId = (video != null && video.getVideoSubtitleId() != null) ? video.getVideoSubtitleId() : 1L;
            currentVideoSubtitleId.set(videoSubtitleId);
            Integer fontSizeLevel = (video != null && video.getFontSizeLevel() != null) ? video.getFontSizeLevel() : 3;
            currentFontSizeLevel.set(fontSizeLevel);
            // v2.9.167: 자막 위치
            Integer subtitlePosition = (video != null && video.getSubtitlePosition() != null) ? video.getSubtitlePosition() : 1;
            currentSubtitlePosition.set(subtitlePosition);
            // v2.9.174: 폰트 ID, 크리에이터 ID
            Long fontId = (video != null && video.getFontId() != null) ? video.getFontId() : 1L;
            currentFontId.set(fontId);
            currentCreatorId.set(creatorId);

            // v2.8.3: 실패 카운트 추적
            int ttsFailedCount = 0;
            List<Long> ttsFailedSceneIds = new ArrayList<>();

            // v2.9.38: 파이프라인 방식으로 TTS Rate Limit 완전 회피
            // TTS 생성 → FFmpeg 실행 → 다음 TTS 생성 (FFmpeg 시간만큼 간격 벌림)
            List<CompletableFuture<Boolean>> ffmpegFutures = new ArrayList<>();
            Map<Long, Scene> sceneMap = new HashMap<>();

            // ========== 파이프라인 처리: TTS → FFmpeg 완료 → 다음 TTS ==========
            log.info("[generateSceneAudioAsync] Starting Pipeline: TTS → FFmpeg → Next TTS for {} scenes", targetScenes.size());

            for (int i = 0; i < targetScenes.size(); i++) {
                Scene scene = targetScenes.get(i);
                sceneMap.put(scene.getSceneId(), scene);

                String sceneTypeName = "OPENING".equals(scene.getSceneType()) ? "오프닝" : "슬라이드 " + scene.getSceneOrder();

                // v2.9.38: 파이프라인 - 이전 씬의 FFmpeg 완료 대기 (Rate Limit 간격 자동 확보)
                // v2.9.78: 타임아웃 60초→300초로 연장 (긴 슬라이드 처리 지원)
                if (i > 0 && !ffmpegFutures.isEmpty()) {
                    progress.message = String.format("[%d/%d] %s: 이전 씬 영상 합성 완료 대기...",
                        i + 1, targetScenes.size(), sceneTypeName);
                    try {
                        CompletableFuture<Boolean> prevFfmpeg = ffmpegFutures.get(i - 1);
                        prevFfmpeg.get(FFMPEG_PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        log.info("[Pipeline] ✓ Previous FFmpeg completed, proceeding to TTS for scene {}", i + 1);
                    } catch (TimeoutException e) {
                        log.warn("[Pipeline] ⚠ Previous FFmpeg timeout after {}s, continuing to TTS anyway", FFMPEG_PIPELINE_TIMEOUT_SECONDS);
                    } catch (Exception e) {
                        log.warn("[Pipeline] ⚠ Previous FFmpeg error ({}), continuing to TTS anyway: {}",
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                }

                // TTS 생성 시작
                progress.message = String.format("[%d/%d] %s: 음성 생성 중...",
                    i + 1, targetScenes.size(), sceneTypeName);

                try {
                    // TTS 생성 (v2.8.2: creatorId 전달하여 TTS_INSTRUCTION 적용)
                    // v2.9.38: 재시도 로직 단순화 - AdaptiveRateLimiter가 자동 대기 처리
                    List<double[]> speechSegments = null; // v2.9.3: 음성 구간
                    Double actualAudioDuration = null; // v2.9.175: 실제 오디오 길이 (자막 폴백 타이밍용)
                    if (scene.getNarration() != null && !scene.getNarration().isEmpty()) {
                        String audioPath = null;
                        Exception lastException = null;

                        for (int ttsRetry = 0; ttsRetry < MAX_TTS_RETRIES; ttsRetry++) {
                            try {
                                audioPath = ttsService.generateNarration(userNo, scene.getNarration(), QualityTier.PREMIUM, creatorId);
                                if (audioPath != null) {
                                    if (ttsRetry > 0) {
                                        log.info("[TTS Retry] ✓ Scene {} succeeded on attempt {}", scene.getSceneOrder(), ttsRetry + 1);
                                    }
                                    break; // 성공 시 루프 탈출
                                }
                            } catch (Exception e) {
                                lastException = e;
                                if (ttsRetry < MAX_TTS_RETRIES - 1) {
                                    // v2.9.38: 재시도 딜레이 제거 - AdaptiveRateLimiter가 자동 대기
                                    progress.message = String.format("[%d/%d] %s: 음성 생성 재시도 중... (%d/%d)",
                                        i + 1, targetScenes.size(), sceneTypeName, ttsRetry + 2, MAX_TTS_RETRIES);
                                    log.warn("[TTS Retry] Scene {} attempt {} failed, retrying immediately (RateLimiter will handle delay): {}",
                                            scene.getSceneOrder(), ttsRetry + 1, e.getMessage());
                                    // Thread.sleep 제거! AdaptiveRateLimiter가 처리
                                } else {
                                    log.error("[TTS Retry] ✗ Scene {} all {} attempts failed: {}",
                                            scene.getSceneOrder(), MAX_TTS_RETRIES, e.getMessage());
                                }
                            }
                        }

                        // 모든 재시도 후에도 실패한 경우
                        if (audioPath == null && lastException != null) {
                            throw lastException;
                        }

                        if (audioPath != null) {
                            // v2.9.6: 오프닝 씬은 정확히 8초에 맞춤 (Veo 영상 길이)
                            if ("OPENING".equals(scene.getSceneType())) {
                                double openingDuration = 8.0;
                                String adjustedPath = ttsService.adjustAudioTempo(audioPath, openingDuration);
                                if (adjustedPath != null && !adjustedPath.equals(audioPath)) {
                                    log.info("[generateSceneAudioAsync] ✅ Opening TTS tempo adjusted to {}s", openingDuration);
                                    audioPath = adjustedPath;
                                }
                            }

                            Path destAudioPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".mp3");
                            Files.copy(Paths.get(audioPath), destAudioPath, StandardCopyOption.REPLACE_EXISTING);
                            scene.setAudioUrl(destAudioPath.toString());
                            sceneUpdateService.updateAudioUrl(scene.getSceneId(), destAudioPath.toString());

                            // 실제 오디오 길이 측정 및 업데이트
                            double actualDuration = ttsService.getAudioDuration(destAudioPath.toString());
                            actualAudioDuration = actualDuration; // v2.9.175: 실제 오디오 길이 전달 (자막 폴백 타이밍용)
                            int durationSeconds = (int) Math.ceil(actualDuration);
                            // v2.9.10: 오프닝은 8초 고정, 슬라이드는 3-60초 범위 (TTS 길이에 따라 동적)
                            if ("OPENING".equals(scene.getSceneType())) {
                                durationSeconds = 8;
                            } else {
                                durationSeconds = Math.max(3, Math.min(6000, durationSeconds));  // v2.9.76: 180초→6000초 (100분, 나레이션 길이 무제한)
                            }
                            scene.setDuration(durationSeconds);
                            sceneUpdateService.updateDuration(scene.getSceneId(), durationSeconds);

                            // v2.9.175: 문장 경계 감지 (SubtitleServiceImpl과 동일한 필터링 기준)
                            int sentenceCount = countSentences(scene.getNarration());
                            speechSegments = ttsService.detectSentenceBoundaries(destAudioPath.toString(), sentenceCount);
                            log.info("[v2.9.175] Detected {} sentence boundaries for scene audio {} (sentenceCount={})",
                                    speechSegments != null ? speechSegments.size() : 0, scene.getSceneId(), sentenceCount);
                        }
                    }

                    // 자막 생성 - v2.9.170: 문장 경계 기반 타이밍
                    if (includeSubtitle && scene.getNarration() != null) {
                        progress.message = String.format("[%d/%d] %s: 자막 생성 중...",
                            i + 1, targetScenes.size(), sceneTypeName);
                        String subtitlePath = generateSceneSubtitle(scene, sceneDir, speechSegments, actualAudioDuration);
                        if (subtitlePath != null) {
                            scene.setSubtitleUrl(subtitlePath);
                            sceneUpdateService.updateSubtitleUrl(scene.getSceneId(), subtitlePath);
                        }
                    }

                    // v2.9.38: FFmpeg 합성 시작 메시지
                    progress.message = String.format("[%d/%d] %s: 영상 합성 시작...",
                        i + 1, targetScenes.size(), sceneTypeName);

                    // v2.9.22: FFmpeg 작업을 비동기로 제출 (1개씩 순차 처리 - OOM 방지)
                    // 재시도 로직: 최대 5회, OOM 시 추가 딜레이 + GC 트리거
                    final Scene sceneForFfmpeg = scene;
                    final Path sceneDirForFfmpeg = sceneDir;
                    CompletableFuture<Boolean> ffmpegFuture = CompletableFuture.supplyAsync(() -> {
                        for (int ffmpegRetry = 0; ffmpegRetry < MAX_FFMPEG_RETRIES; ffmpegRetry++) {
                            try {
                                // v2.9.22: 재시도 전 GC 트리거 (메모리 확보)
                                if (ffmpegRetry > 0) {
                                    log.info("[FFmpeg Retry] Scene {} triggering GC before attempt {}",
                                            sceneForFfmpeg.getSceneOrder(), ffmpegRetry + 1);
                                    System.gc();
                                    Thread.sleep(500); // GC 완료 대기
                                }

                                String sceneVideoPath = composeIndividualSceneVideo(sceneForFfmpeg, sceneDirForFfmpeg);
                                if (sceneVideoPath != null) {
                                    sceneForFfmpeg.setSceneVideoUrl(sceneVideoPath);
                                    sceneUpdateService.updateSceneVideoUrl(sceneForFfmpeg.getSceneId(), sceneVideoPath);
                                    if (ffmpegRetry > 0) {
                                        log.info("[FFmpeg Retry] ✅ Scene {} succeeded on attempt {}", sceneForFfmpeg.getSceneOrder(), ffmpegRetry + 1);
                                    }
                                    return true;
                                }
                                // sceneVideoPath가 null인 경우 재시도
                                if (ffmpegRetry < MAX_FFMPEG_RETRIES - 1) {
                                    long delayMs = FFMPEG_RETRY_BASE_DELAY_MS * (1L << ffmpegRetry); // 2초, 4초, 8초, 16초
                                    log.warn("[FFmpeg Retry] Scene {} attempt {} returned null, retrying in {}ms",
                                            sceneForFfmpeg.getSceneOrder(), ffmpegRetry + 1, delayMs);
                                    Thread.sleep(delayMs);
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.error("[FFmpeg] Scene {} interrupted", sceneForFfmpeg.getSceneId());
                                return false;
                            } catch (OutOfMemoryError oom) {
                                // v2.9.22: OOM 에러 특별 처리 - 더 긴 딜레이 + 강제 GC
                                log.error("[FFmpeg OOM] ⚠️ Scene {} OOM on attempt {}: {}",
                                        sceneForFfmpeg.getSceneOrder(), ffmpegRetry + 1, oom.getMessage());
                                if (ffmpegRetry < MAX_FFMPEG_RETRIES - 1) {
                                    long delayMs = FFMPEG_RETRY_BASE_DELAY_MS * (1L << ffmpegRetry) + FFMPEG_OOM_EXTRA_DELAY_MS;
                                    log.info("[FFmpeg OOM] Triggering GC and waiting {}ms before retry...", delayMs);
                                    System.gc();
                                    try {
                                        Thread.sleep(delayMs);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        return false;
                                    }
                                } else {
                                    log.error("[FFmpeg OOM] Scene {} all {} attempts failed due to OOM",
                                            sceneForFfmpeg.getSceneOrder(), MAX_FFMPEG_RETRIES);
                                }
                            } catch (Exception e) {
                                if (ffmpegRetry < MAX_FFMPEG_RETRIES - 1) {
                                    long delayMs = FFMPEG_RETRY_BASE_DELAY_MS * (1L << ffmpegRetry);
                                    log.warn("[FFmpeg Retry] Scene {} attempt {} failed, retrying in {}ms: {}",
                                            sceneForFfmpeg.getSceneOrder(), ffmpegRetry + 1, delayMs, e.getMessage());
                                    try {
                                        Thread.sleep(delayMs);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        return false;
                                    }
                                } else {
                                    log.error("[FFmpeg Retry] Scene {} all {} attempts failed: {}",
                                            sceneForFfmpeg.getSceneOrder(), MAX_FFMPEG_RETRIES, e.getMessage());
                                }
                            }
                        }
                        return false; // 모든 재시도 실패
                    }, ffmpegExecutor);
                    ffmpegFutures.add(ffmpegFuture);

                    log.info("[generateSceneAudioAsync] Scene {} TTS done, FFmpeg submitted (total pending: {})",
                            scene.getSceneOrder(), ffmpegFutures.size());

                } catch (Exception e) {
                    log.error("[ContentService] Scene TTS generation failed for scene {}: {}", scene.getSceneId(), e.getMessage());
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                    // v2.8.3: 실패 추적
                    ttsFailedCount++;
                    ttsFailedSceneIds.add(scene.getSceneId());
                }
            }

            // v2.8.3: TTS 실패 여부에 따라 상태 분기
            if (ttsFailedCount > 0) {
                progress.status = "partial_failed";
                progress.message = String.format("%d/%d 씬의 TTS 생성에 실패했습니다. 재시도가 필요합니다.", ttsFailedCount, targetScenes.size());
                log.warn("[ContentService] TTS partial failed - chat: {}, failed: {}/{}, failedSceneIds: {}",
                        chatId, ttsFailedCount, targetScenes.size(), ttsFailedSceneIds);
                // 실패 시 TTS_PARTIAL_FAILED 상태로 변경 (완료로 넘어가지 않음)
                conversationMapper.updateCurrentStep(chatId, "TTS_PARTIAL_FAILED");
                return; // 실패 시 S3 업로드/상태 변경 하지 않음
            }

            // ========== Phase 2: 남은 FFmpeg 작업 완료 대기 ==========
            // v2.9.38: 파이프라인 처리로 대부분 완료, 마지막 씬만 대기
            progress.message = String.format("[%d/%d] 마지막 씬 영상 합성 완료 대기...",
                targetScenes.size(), targetScenes.size());
            log.info("[generateSceneAudioAsync] Phase 2: Waiting for remaining FFmpeg tasks (last scene of {})", ffmpegFutures.size());

            // 모든 FFmpeg 작업 완료 대기
            CompletableFuture<Void> allFfmpeg = CompletableFuture.allOf(ffmpegFutures.toArray(new CompletableFuture[0]));
            allFfmpeg.join(); // 모든 작업 완료까지 블로킹

            // FFmpeg 결과 확인 및 씬 상태 업데이트
            int ffmpegFailedCount = 0;
            List<Long> ffmpegFailedSceneIds = new ArrayList<>();
            for (int i = 0; i < ffmpegFutures.size(); i++) {
                try {
                    Boolean success = ffmpegFutures.get(i).get();
                    Scene scene = targetScenes.get(i);
                    if (success != null && success) {
                        sceneUpdateService.updateStatus(scene.getSceneId(), "COMPLETED");
                        progress.completedCount = i + 1;
                    } else {
                        sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                        ffmpegFailedCount++;
                        ffmpegFailedSceneIds.add(scene.getSceneId());
                    }
                } catch (Exception e) {
                    Scene scene = targetScenes.get(i);
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                    ffmpegFailedCount++;
                    ffmpegFailedSceneIds.add(scene.getSceneId());
                    log.error("[generateSceneAudioAsync] FFmpeg future {} failed: {}", i, e.getMessage());
                }
            }

            // FFmpeg 실패 처리
            if (ffmpegFailedCount > 0) {
                progress.status = "partial_failed";
                progress.message = String.format("%d/%d 씬의 영상 합성에 실패했습니다.", ffmpegFailedCount, targetScenes.size());
                log.warn("[ContentService] FFmpeg partial failed - chat: {}, failed: {}/{}, failedSceneIds: {}",
                        chatId, ffmpegFailedCount, targetScenes.size(), ffmpegFailedSceneIds);
                conversationMapper.updateCurrentStep(chatId, "TTS_PARTIAL_FAILED");
                return;
            }

            log.info("[generateSceneAudioAsync] All {} FFmpeg tasks completed successfully", ffmpegFutures.size());

            progress.status = "completed";
            progress.message = "모든 씬의 TTS 및 자막이 생성되었습니다.";

            // v2.6.0: 통합 오디오 파일 생성 (오디오 다운로드용)
            createMergedAudioFile(chatId, targetScenes, sceneDir);

            // S3 업로드
            if (storageService.isEnabled()) {
                uploadScenesToS3(chatId, sceneDir);
            }

            // v2.9.59: 썸네일 씬 생성을 generateFinalVideoAsync()로 이동 (Lock Wait 문제 해결)
            // - ThumbnailService 트랜잭션(15초)과 동시 실행 충돌 방지
            // - 최종 합성 직전에 썸네일 씬 생성하여 안전하게 처리

            // v2.9.94: 대화 상태 업데이트 (원자적 조건부 UPDATE로 레이스 컨디션 방지)
            // VIDEO_DONE, VIDEO_GENERATING, VIDEO_FAILED 상태가 아닐 때만 TTS_DONE으로 업데이트
            // SQL 레벨에서 조건 체크하여 레이스 컨디션 완전 차단
            int updatedRows = conversationMapper.updateCurrentStepIfNotVideoState(chatId, "TTS_DONE");
            if (updatedRows > 0) {
                log.info("[v2.9.94] Set TTS_DONE for chatId: {} (atomic update)", chatId);
            } else {
                // 업데이트 안됨 = 이미 VIDEO 관련 상태
                String currentStep = conversationMapper.findById(chatId)
                        .map(Conversation::getCurrentStep)
                        .orElse("unknown");
                log.info("[v2.9.94] Skipping TTS_DONE - already {}: chatId={}", currentStep, chatId);
            }

        } catch (Exception e) {
            log.error("[ContentService] Scene audio generation failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "TTS/자막 생성 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * v2.9.86: 엔딩 씬 생성 (썸네일 또는 첫 슬라이드 이미지 사용)
     *
     * @Transactional(propagation = REQUIRES_NEW)로 완전히 새로운 SqlSession을 생성하여
     * ThumbnailService가 업데이트한 thumbnailUrl을 정확히 읽어옵니다.
     *
     * v2.9.86 변경: 썸네일이 없어도 첫 슬라이드 이미지로 2초 엔딩씬을 항상 생성합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void createThumbnailSceneInNewTransaction(Long chatId, Long videoId, Path sceneDir,
                                                        int targetScenesCount, ProgressInfo progress)
            throws IOException, InterruptedException {
        log.info("[v2.9.86] Creating ending scene in NEW transaction for videoId={}", videoId);

        // 새 트랜잭션에서 DB 재조회 (MyBatis 캐시 무효화)
        Video currentVideo = videoMapper.findById(videoId).orElse(null);

        if (currentVideo == null) {
            log.warn("[v2.9.86] ⚠️ Video not found: videoId={}", videoId);
            return;
        }

        // v2.9.86: 엔딩 이미지 소스 결정 (우선순위: 썸네일 > 첫 슬라이드)
        String endingImageUrl = currentVideo.getThumbnailUrl();
        String endingImageSource = "thumbnail";

        if (endingImageUrl == null || endingImageUrl.isEmpty()) {
            log.info("[v2.9.86] Thumbnail not found, trying first slide image...");

            // 첫 슬라이드 이미지 조회
            List<Scene> slides = sceneMapper.findByVideoIdOrderByOrder(videoId).stream()
                    .filter(s -> "SLIDE".equals(s.getSceneType()) && s.getImageUrl() != null && !s.getImageUrl().isEmpty())
                    .collect(Collectors.toList());

            if (!slides.isEmpty()) {
                endingImageUrl = slides.get(0).getImageUrl();
                endingImageSource = "first_slide";
                log.info("[v2.9.86] ✅ Using first slide image for ending: {}", endingImageUrl);
            } else {
                // 오프닝 이미지 조회 (마지막 폴백)
                List<Scene> openings = sceneMapper.findByVideoIdOrderByOrder(videoId).stream()
                        .filter(s -> "OPENING".equals(s.getSceneType()) && s.getImageUrl() != null && !s.getImageUrl().isEmpty())
                        .collect(Collectors.toList());

                if (!openings.isEmpty()) {
                    endingImageUrl = openings.get(0).getImageUrl();
                    endingImageSource = "opening";
                    log.info("[v2.9.86] ✅ Using opening image for ending: {}", endingImageUrl);
                } else {
                    log.error("[v2.9.86] ❌ No image available for ending scene (no thumbnail, slides, or opening)");
                    return;
                }
            }
        } else {
            log.info("[v2.9.86] ✅ Thumbnail found: {}", endingImageUrl);
        }

        // 엔딩 이미지 준비 (S3 key → 로컬 다운로드)
        Path endingImagePath = prepareMediaFile(endingImageUrl, sceneDir, "ending_image.jpg");
        if (endingImagePath == null || !Files.exists(endingImagePath)) {
            log.error("[v2.9.86] ❌ Ending image not found after prepareMediaFile: {}", endingImageUrl);
            return;
        }

        // 2초 정적 영상 생성 (무음 오디오 포함)
        String endingSceneFilename = "scene_ending.mp4";
        Path endingScenePath = sceneDir.resolve(endingSceneFilename);
        createStaticVideoFromImage(endingImagePath, 2, endingScenePath);

        if (!Files.exists(endingScenePath)) {
            log.error("[v2.9.86] ❌ Ending scene video file not created");
            return;
        }

        // S3 업로드
        String endingSceneKey = uploadSceneMediaToS3(chatId, endingScenePath.toString(), 999, "video");
        log.info("[v2.9.86] ✅ 2-second ending scene created (source: {}) and uploaded: {}", endingImageSource, endingSceneKey);

        // Scene 객체 생성 및 DB 저장 (최종 영상 합성 시 사용)
        Scene endingScene = Scene.builder()
                .videoId(videoId)
                .sceneType("THUMBNAIL")  // 기존 호환성 유지
                .sceneOrder(targetScenesCount)  // 마지막 순서
                .duration(2)
                .prompt("Ending scene (source: " + endingImageSource + ")")
                .narration("")  // 엔딩씬은 나레이션 없음
                .sceneVideoUrl(endingSceneKey)
                .sceneStatus("COMPLETED")
                .build();
        sceneMapper.insert(endingScene);
        log.info("[v2.9.86] ✅ Ending scene saved to DB: sceneId={}, source={}", endingScene.getSceneId(), endingImageSource);

        // 진행 카운트 증가
        progress.completedCount = targetScenesCount + 1;
        progress.message = String.format("[%d/%d] 엔딩 씬 생성 완료",
            progress.completedCount, progress.totalCount);
        log.info("[v2.9.86] ✅ Progress updated: {}/{} (ending scene included)",
            progress.completedCount, progress.totalCount);
    }

    /**
     * TTS/자막 생성 진행 상태 조회
     */
    public ContentDto.SceneAudioGenerateResponse getSceneAudioProgress(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        ProgressInfo progress = progressStore.get(chatId);
        Video video = getVideoByConversationId(chatId);
        List<Scene> scenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        List<ContentDto.SceneAudioInfo> audioInfos = scenes.stream()
                .map(s -> ContentDto.SceneAudioInfo.builder()
                        .sceneId(s.getSceneId())
                        .sceneOrder(s.getSceneOrder())
                        .audioUrl(s.getAudioUrl())
                        .subtitleUrl(s.getSubtitleUrl())
                        .durationSeconds(s.getDuration())
                        .status("COMPLETED".equals(s.getSceneStatus()) ? "completed" :
                               "FAILED".equals(s.getSceneStatus()) ? "failed" : "pending")
                        .build())
                .collect(Collectors.toList());

        if (progress == null || !"scene_audio".equals(progress.processType)) {
            long completedCount = scenes.stream()
                    .filter(s -> "COMPLETED".equals(s.getSceneStatus()))
                    .count();

            return ContentDto.SceneAudioGenerateResponse.builder()
                    .chatId(chatId)
                    .status("idle")
                    .totalCount(scenes.size())
                    .completedCount((int) completedCount)
                    .progressMessage("대기 중")
                    .audioInfos(audioInfos)
                    .build();
        }

        return ContentDto.SceneAudioGenerateResponse.builder()
                .chatId(chatId)
                .status(progress.status)
                .totalCount(progress.totalCount)
                .completedCount(progress.completedCount)
                .progressMessage(progress.message)
                .audioInfos(audioInfos)
                .build();
    }

    private ContentDto.ScenePreviewInfo convertToScenePreviewInfo(Scene scene) {
        return convertToScenePreviewInfo(scene, null);
    }

    private ContentDto.ScenePreviewInfo convertToScenePreviewInfo(Scene scene, Long chatId) {
        // v2.6.1: S3 key를 presigned URL로 변환
        String mediaUrl = toPresignedUrlIfS3Key(scene.getImageUrl());
        String mediaType = "OPENING".equals(scene.getSceneType()) ? "video" : "image";

        String previewStatus = "PENDING";
        if ("MEDIA_READY".equals(scene.getSceneStatus())) {
            previewStatus = "MEDIA_READY";
        } else if ("COMPLETED".equals(scene.getSceneStatus())) {
            previewStatus = "COMPLETED";
        } else if ("FAILED".equals(scene.getSceneStatus())) {
            previewStatus = "FAILED";
        } else if ("GENERATING".equals(scene.getSceneStatus())) {
            previewStatus = "GENERATING";
        } else if ("TTS_READY".equals(scene.getSceneStatus())) {
            previewStatus = "TTS_READY";
        }

        // v2.6.0: 합성된 씬 영상 URL (COMPLETED 상태일 때만)
        // 로컬 파일 경로인 경우 S3 presigned URL로 변환
        String sceneVideoUrl = null;
        if ("COMPLETED".equals(scene.getSceneStatus()) && scene.getSceneVideoUrl() != null) {
            String storedVideoUrl = scene.getSceneVideoUrl();
            if (storedVideoUrl.startsWith("http://") || storedVideoUrl.startsWith("https://")) {
                // 이미 S3 URL인 경우
                sceneVideoUrl = storedVideoUrl;
            } else if (storageService.isEnabled() && chatId != null) {
                // 로컬 파일 경로인 경우 S3에서 presigned URL 생성
                String videoFilename = Path.of(storedVideoUrl).getFileName().toString();
                String s3Key = getS3Key(chatId, "scenes/" + videoFilename);
                if (storageService.exists(s3Key)) {
                    sceneVideoUrl = storageService.generatePresignedUrl(s3Key);
                } else {
                    // S3에 없으면 로컬 경로 반환 (디버깅용)
                    sceneVideoUrl = storedVideoUrl;
                }
            } else {
                sceneVideoUrl = storedVideoUrl;
            }
        }

        return ContentDto.ScenePreviewInfo.builder()
                .sceneId(scene.getSceneId())
                .sceneOrder(scene.getSceneOrder())
                .sceneType(scene.getSceneType())
                .title(scene.getTitle())
                .mediaUrl(mediaUrl)
                .mediaType(mediaType)
                .sceneVideoUrl(sceneVideoUrl)  // v2.6.0: 합성된 씬 영상
                .narration(scene.getNarration())
                .isEdited(false)  // TODO: 편집 여부 추적 필요 시 DB 컬럼 추가
                .previewStatus(previewStatus)
                .errorMessage(scene.getUserFeedback())  // v2.6.0: userFeedback 필드를 에러 메시지로 재활용
                .build();
    }

    // ========== v2.6.0 부분 실패 복구 시스템 ==========

    /**
     * 실패한 씬 목록 조회
     */
    public ContentDto.FailedScenesRetryResponse getFailedScenes(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        List<Scene> failedScenes = sceneMapper.findFailedByVideoId(video.getVideoId());

        if (failedScenes.isEmpty()) {
            return ContentDto.FailedScenesRetryResponse.builder()
                    .chatId(chatId)
                    .status("no_failed_scenes")
                    .totalFailedCount(0)
                    .retryingCount(0)
                    .failedScenes(List.of())
                    .message("실패한 씬이 없습니다.")
                    .build();
        }

        List<ContentDto.FailedSceneInfo> failedSceneInfos = failedScenes.stream()
                .map(this::convertToFailedSceneInfo)
                .collect(Collectors.toList());

        return ContentDto.FailedScenesRetryResponse.builder()
                .chatId(chatId)
                .status("has_failed")
                .totalFailedCount(failedScenes.size())
                .retryingCount(0)
                .failedScenes(failedSceneInfos)
                .message(String.format("%d개의 실패한 씬이 있습니다.", failedScenes.size()))
                .build();
    }

    /**
     * 실패한 씬 재시도
     */
    @Transactional
    public ContentDto.FailedScenesRetryResponse retryFailedScenes(Long userNo, Long chatId, ContentDto.FailedScenesRetryRequest request) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 재시도할 씬 목록 결정
        List<Scene> targetScenes;
        if (request != null && request.getSceneIds() != null && !request.getSceneIds().isEmpty()) {
            // 특정 씬만 재시도
            targetScenes = new ArrayList<>();
            for (Long sceneId : request.getSceneIds()) {
                sceneMapper.findById(sceneId).ifPresent(scene -> {
                    if ("FAILED".equals(scene.getSceneStatus())) {
                        targetScenes.add(scene);
                    }
                });
            }
        } else {
            // 모든 실패 씬 재시도
            targetScenes = sceneMapper.findFailedByVideoId(videoId);
        }

        if (targetScenes.isEmpty()) {
            return ContentDto.FailedScenesRetryResponse.builder()
                    .chatId(chatId)
                    .status("no_failed_scenes")
                    .totalFailedCount(0)
                    .retryingCount(0)
                    .failedScenes(List.of())
                    .message("재시도할 실패 씬이 없습니다.")
                    .build();
        }

        // v2.6.2: ScenarioContext 빌드 (시나리오 기반 프롬프트 필수!)
        ScenarioContext scenarioContext = buildScenarioContext(videoId);

        // v2.8.0: Video에서 creatorId 조회 (video는 이미 위에서 선언됨)
        Long creatorId = video != null ? video.getCreatorId() : null;

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("retry_failed", targetScenes.size());
        progressStore.put(chatId, progress);

        // v2.8.0: 비동기로 재시도 시작 - creatorId 추가
        // v2.9.25: formatId 추가 (자막 해상도 적용)
        boolean retryMediaOnly = request == null || request.isRetryMediaOnly();
        Long formatId = video.getFormatId() != null ? video.getFormatId() : 1L;
        retryFailedScenesAsync(userNo, chatId, targetScenes, scenarioContext, retryMediaOnly, progress, creatorId, formatId);

        List<ContentDto.FailedSceneInfo> failedSceneInfos = targetScenes.stream()
                .map(s -> {
                    ContentDto.FailedSceneInfo info = convertToFailedSceneInfo(s);
                    // 재시도 중 표시를 위해 새 객체 생성
                    return ContentDto.FailedSceneInfo.builder()
                            .sceneId(info.getSceneId())
                            .sceneOrder(info.getSceneOrder())
                            .sceneType(info.getSceneType())
                            .failedAt(info.getFailedAt())
                            .errorMessage(info.getErrorMessage())
                            .retryCount(info.getRetryCount())
                            .isRetrying(true)
                            .build();
                })
                .collect(Collectors.toList());

        return ContentDto.FailedScenesRetryResponse.builder()
                .chatId(chatId)
                .status("processing")
                .totalFailedCount(targetScenes.size())
                .retryingCount(targetScenes.size())
                .failedScenes(failedSceneInfos)
                .message(String.format("%d개의 실패 씬을 재시도합니다...", targetScenes.size()))
                .build();
    }

    @Async
    @Transactional
    protected void retryFailedScenesAsync(Long userNo, Long chatId, List<Scene> failedScenes,
                                           ScenarioContext scenarioContext, boolean retryMediaOnly, ProgressInfo progress, Long creatorId, Long formatId) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path sceneDir = getSceneDir(chatId);
            Files.createDirectories(sceneDir);

            // v2.9.25: formatId 설정 (자막 해상도 적용)
            setCurrentFormatId(formatId);
            log.info("[retryFailedScenesAsync] formatId set: {}", formatId);

            // v2.9.161: videoSubtitleId, fontSizeLevel 설정 (자막 템플릿 적용)
            // retryFailedScenesAsync는 failedScenes에서 videoId를 가져와 Video 조회
            if (!failedScenes.isEmpty()) {
                Video video = videoMapper.findById(failedScenes.get(0).getVideoId()).orElse(null);
                Long videoSubtitleId = (video != null && video.getVideoSubtitleId() != null) ? video.getVideoSubtitleId() : 1L;
                currentVideoSubtitleId.set(videoSubtitleId);
                Integer fontSizeLevel = (video != null && video.getFontSizeLevel() != null) ? video.getFontSizeLevel() : 3;
                currentFontSizeLevel.set(fontSizeLevel);
                // v2.9.167: 자막 위치
                Integer subtitlePosition = (video != null && video.getSubtitlePosition() != null) ? video.getSubtitlePosition() : 1;
                currentSubtitlePosition.set(subtitlePosition);
                // v2.9.174: 폰트 ID, 크리에이터 ID
                Long fontIdVal = (video != null && video.getFontId() != null) ? video.getFontId() : 1L;
                currentFontId.set(fontIdVal);
                currentCreatorId.set(creatorId);
            }

            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < failedScenes.size(); i++) {
                Scene scene = failedScenes.get(i);
                progress.message = String.format("실패 씬 재시도 중... (%d/%d)", i + 1, failedScenes.size());

                // 재시도 카운트 증가
                sceneUpdateService.incrementRetryCount(scene.getSceneId());

                try {
                    // 상태를 GENERATING으로 변경
                    sceneUpdateService.updateStatus(scene.getSceneId(), "GENERATING");

                    // v2.8.0: 미디어가 없거나 실패한 경우 미디어 재생성 - creatorId 추가
                    if (scene.getImageUrl() == null || scene.getImageUrl().isEmpty()) {
                        String mediaUrl = regenerateSceneMedia(userNo, scene, scenarioContext, sceneDir, creatorId);
                        if (mediaUrl != null) {
                            scene.setImageUrl(mediaUrl);
                            sceneUpdateService.updateImageUrl(scene.getSceneId(), mediaUrl);
                        } else {
                            // v2.9.23: 이미지 생성 실패 시 FAILED 상태로 설정 (버그 수정)
                            log.error("[ContentService] Retry: Image generation failed for scene {}", scene.getSceneId());
                            sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                            sceneUpdateService.updateErrorMessage(scene.getSceneId(), "이미지 생성 실패");
                            failCount++;
                            continue;  // 다음 씬으로
                        }
                    }

                    if (!retryMediaOnly) {
                        // TTS 재생성 (v2.8.2: creatorId 전달하여 TTS_INSTRUCTION 적용)
                        List<double[]> speechSegments = null; // v2.9.3: 음성 구간
                        Double actualAudioDuration = null; // v2.9.175: 실제 오디오 길이 (자막 폴백 타이밍용)
                        if (scene.getNarration() != null && !scene.getNarration().isEmpty()) {
                            String audioPath = ttsService.generateNarration(userNo, scene.getNarration(), QualityTier.PREMIUM, creatorId);
                            if (audioPath != null) {
                                // v2.9.6: 오프닝 씬은 정확히 8초에 맞춤 (Veo 영상 길이)
                                if ("OPENING".equals(scene.getSceneType())) {
                                    double openingDuration = 8.0;
                                    String adjustedPath = ttsService.adjustAudioTempo(audioPath, openingDuration);
                                    if (adjustedPath != null && !adjustedPath.equals(audioPath)) {
                                        log.info("[retryFailedScenesAsync] ✅ Opening TTS tempo adjusted to {}s", openingDuration);
                                        audioPath = adjustedPath;
                                    }
                                }

                                Path destAudioPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".mp3");
                                Files.copy(Paths.get(audioPath), destAudioPath, StandardCopyOption.REPLACE_EXISTING);
                                scene.setAudioUrl(destAudioPath.toString());
                                sceneUpdateService.updateAudioUrl(scene.getSceneId(), destAudioPath.toString());

                                // 오디오 길이 업데이트
                                double actualDuration = ttsService.getAudioDuration(destAudioPath.toString());
                                actualAudioDuration = actualDuration; // v2.9.175: 실제 오디오 길이 전달 (자막 폴백 타이밍용)
                                // v2.9.10: 오프닝은 8초 고정, 슬라이드는 3-60초 범위 (TTS 길이에 따라 동적)
                                int durationSeconds;
                                if ("OPENING".equals(scene.getSceneType())) {
                                    durationSeconds = 8;
                                } else {
                                    durationSeconds = Math.max(3, Math.min(6000, (int) Math.ceil(actualDuration)));  // v2.9.76: 180초→6000초 (100분)
                                }
                                scene.setDuration(durationSeconds);
                                sceneUpdateService.updateDuration(scene.getSceneId(), durationSeconds);

                                // v2.9.175: 문장 경계 감지 (SubtitleServiceImpl과 동일한 필터링 기준)
                                int sentenceCount = countSentences(scene.getNarration());
                                speechSegments = ttsService.detectSentenceBoundaries(destAudioPath.toString(), sentenceCount);
                                log.info("[v2.9.175] Detected {} sentence boundaries for retry scene {} (sentenceCount={})",
                                        speechSegments != null ? speechSegments.size() : 0, scene.getSceneId(), sentenceCount);
                            }
                        }

                        // 자막 재생성 - v2.9.175: 문장 경계 기반 타이밍 + 실제 오디오 길이
                        String subtitlePath = generateSceneSubtitle(scene, sceneDir, speechSegments, actualAudioDuration);
                        if (subtitlePath != null) {
                            scene.setSubtitleUrl(subtitlePath);
                            sceneUpdateService.updateSubtitleUrl(scene.getSceneId(), subtitlePath);
                        }

                        // 개별 씬 영상 재합성
                        String sceneVideoPath = composeIndividualSceneVideo(scene, sceneDir);
                        if (sceneVideoPath != null) {
                            scene.setSceneVideoUrl(sceneVideoPath);
                            sceneUpdateService.updateSceneVideoUrl(scene.getSceneId(), sceneVideoPath);
                        }

                        // 완료 상태로 변경
                        sceneUpdateService.updateStatus(scene.getSceneId(), "COMPLETED");
                    } else {
                        // 미디어만 재생성인 경우 MEDIA_READY 상태로
                        sceneUpdateService.updateStatus(scene.getSceneId(), "MEDIA_READY");
                    }

                    successCount++;
                    progress.completedCount++;

                } catch (Exception e) {
                    log.error("[ContentService] Retry failed for scene {}: {}", scene.getSceneId(), e.getMessage());
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                    sceneUpdateService.updateErrorMessage(scene.getSceneId(), e.getMessage());
                    failCount++;
                }
            }

            progress.status = "completed";
            progress.message = String.format("재시도 완료: 성공 %d개, 실패 %d개", successCount, failCount);

            log.info("[ContentService] Retry completed - success: {}, fail: {}", successCount, failCount);

        } catch (Exception e) {
            log.error("[ContentService] Retry failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "재시도 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * 씬 미디어 재생성 헬퍼
     * v2.6.2: ScenarioContext 필수 사용 - 시나리오 기반 프롬프트로 일관된 이미지 생성
     * v2.8.0: creatorId 추가
     * v2.9.77: 오프닝 영상 생성 시 전체 시나리오 컨텍스트 포함
     */
    private String regenerateSceneMedia(Long userNo, Scene scene, ScenarioContext scenarioContext, Path sceneDir, Long creatorId) throws Exception {
        // v2.9.25: Video에서 formatId 조회하여 설정
        Video video = videoMapper.findById(scene.getVideoId()).orElse(null);
        Long formatId = (video != null && video.getFormatId() != null) ? video.getFormatId() : 1L;
        setCurrentFormatId(formatId);

        // v2.9.77: 오프닝 영상 생성용 전체 시나리오 정보 로드
        // v2.9.162: 이미 로드된 scenarioContext의 참조 이미지 재사용 (S3 중복 다운로드 방지)
        VideoDto.ScenarioInfo videoScenarioInfo = buildVideoScenarioInfo(scene.getVideoId(), scenarioContext);

        // v2.6.0: 사용자 피드백을 프롬프트에 반영
        String basePrompt = scene.getPrompt();
        String userFeedback = scene.getUserFeedback();
        String enhancedPrompt = basePrompt;

        if (userFeedback != null && !userFeedback.trim().isEmpty()) {
            enhancedPrompt = basePrompt + "\n\n=== USER FEEDBACK FOR IMPROVEMENT ===\n" + userFeedback
                    + "\n\nPlease incorporate this feedback to improve the result.";
            log.info("[ContentService] Scene {} regeneration with user feedback: {}", scene.getSceneId(), userFeedback);
        }

        if ("OPENING".equals(scene.getSceneType())) {
            // v2.8.0: 오프닝 영상 재생성 - creatorId 추가, v2.9.77: 시나리오 컨텍스트 포함
            VideoDto.OpeningScene openingScene = VideoDto.OpeningScene.builder()
                    .videoPrompt(enhancedPrompt)
                    .narration(scene.getNarration())
                    .durationSeconds(8)
                    .build();

            // v2.9.77: 전체 시나리오 정보와 함께 오프닝 영상 재생성
            return videoCreatorService.generateOpeningVideo(userNo, openingScene, QualityTier.PREMIUM, scenarioContext.getCharacterBlock(), creatorId, videoScenarioInfo);
        } else {
            // v2.8.0: 슬라이드 이미지 재생성 - creatorId 추가
            VideoDto.SlideScene slideScene = VideoDto.SlideScene.builder()
                    .order(scene.getSceneOrder())
                    .imagePrompt(enhancedPrompt)
                    .narration(scene.getNarration())
                    .durationSeconds(10)
                    .build();

            // v2.8.0: ScenarioContext + creatorId 포함하여 이미지 생성
            List<String> imagePaths = imageGeneratorService.generateImages(
                    userNo, List.of(slideScene), QualityTier.PREMIUM, scenarioContext, creatorId);

            // v2.6.1: ERROR: 문자열 필터링 - DB에 에러 메시지 저장 방지
            if (imagePaths != null && !imagePaths.isEmpty() && imagePaths.get(0) != null) {
                String result = imagePaths.get(0);
                if (!result.startsWith("ERROR:")) {
                    return result;
                } else {
                    log.warn("[ContentService] Image generation returned error: {}", result);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 실패 씬 정보 변환 헬퍼
     */
    private ContentDto.FailedSceneInfo convertToFailedSceneInfo(Scene scene) {
        // 실패 단계 추정
        String failedAt = "MEDIA";
        if (scene.getImageUrl() != null && !scene.getImageUrl().isEmpty()) {
            if (scene.getAudioUrl() == null || scene.getAudioUrl().isEmpty()) {
                failedAt = "TTS";
            } else if (scene.getSubtitleUrl() == null || scene.getSubtitleUrl().isEmpty()) {
                failedAt = "SUBTITLE";
            } else if (scene.getSceneVideoUrl() == null || scene.getSceneVideoUrl().isEmpty()) {
                failedAt = "VIDEO";
            }
        }

        return ContentDto.FailedSceneInfo.builder()
                .sceneId(scene.getSceneId())
                .sceneOrder(scene.getSceneOrder())
                .sceneType(scene.getSceneType())
                .failedAt(failedAt)
                .errorMessage(scene.getUserFeedback())  // userFeedback에 에러 메시지 저장
                .retryCount(scene.getRegenerateCount() != null ? scene.getRegenerateCount() : 0)
                .isRetrying(false)
                .build();
    }

    /**
     * 진행 상태 체크포인트 조회 (서버 재시작 복구용)
     */
    public ContentDto.ProcessCheckpoint getProcessCheckpoint(Long userNo, Long chatId) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        List<Scene> allScenes = sceneMapper.findByVideoIdOrderByOrder(video.getVideoId());

        List<Long> completedIds = allScenes.stream()
                .filter(s -> "COMPLETED".equals(s.getSceneStatus()) || "MEDIA_READY".equals(s.getSceneStatus()))
                .map(Scene::getSceneId)
                .collect(Collectors.toList());

        List<Long> failedIds = allScenes.stream()
                .filter(s -> "FAILED".equals(s.getSceneStatus()))
                .map(Scene::getSceneId)
                .collect(Collectors.toList());

        // 진행 상태 결정
        String status;
        boolean canResume;
        String processType = "unknown";

        ProgressInfo progress = progressStore.get(chatId);
        if (progress != null) {
            processType = progress.processType;
            status = progress.status;
            canResume = "processing".equals(status) || "paused".equals(status);
        } else {
            // DB 상태 기반 추정
            long pendingCount = allScenes.stream()
                    .filter(s -> "PENDING".equals(s.getSceneStatus()) || s.getSceneStatus() == null)
                    .count();

            if (pendingCount > 0 && completedIds.size() > 0) {
                status = "paused";
                canResume = true;
            } else if (failedIds.size() > 0) {
                status = "failed";
                canResume = true;  // 실패 씬 재시도 가능
            } else if (completedIds.size() == allScenes.size()) {
                status = "completed";
                canResume = false;
            } else {
                status = "idle";
                canResume = false;
            }
        }

        return ContentDto.ProcessCheckpoint.builder()
                .chatId(chatId)
                .processType(processType)
                .status(status)
                .totalCount(allScenes.size())
                .completedCount(completedIds.size())
                .failedCount(failedIds.size())
                .completedSceneIds(completedIds)
                .failedSceneIds(failedIds)
                .lastUpdated(java.time.Instant.now().toString())
                .canResume(canResume)
                .build();
    }

    /**
     * 프로세스 재개 (중단된 작업 계속)
     */
    @Transactional
    public ContentDto.ProcessResumeResponse resumeProcess(Long userNo, Long chatId, ContentDto.ProcessResumeRequest request) {
        validateConversation(userNo, chatId);

        Video video = getVideoByConversationId(chatId);
        Long videoId = video.getVideoId();

        // 미완료 씬 조회 (PENDING 또는 FAILED)
        List<Scene> pendingScenes = sceneMapper.findPendingOrFailedByVideoId(videoId);

        if (pendingScenes.isEmpty()) {
            return ContentDto.ProcessResumeResponse.builder()
                    .chatId(chatId)
                    .status("already_completed")
                    .resumedFromIndex(0)
                    .remainingCount(0)
                    .message("모든 씬이 이미 완료되었습니다.")
                    .build();
        }

        // 실패 씬 스킵 옵션
        if (request != null && request.isSkipFailed()) {
            pendingScenes = pendingScenes.stream()
                    .filter(s -> !"FAILED".equals(s.getSceneStatus()))
                    .collect(Collectors.toList());
        }

        if (pendingScenes.isEmpty()) {
            return ContentDto.ProcessResumeResponse.builder()
                    .chatId(chatId)
                    .status("no_pending")
                    .resumedFromIndex(0)
                    .remainingCount(0)
                    .message("재개할 씬이 없습니다. (실패 씬 스킵됨)")
                    .build();
        }

        // v2.6.2: ScenarioContext 빌드 (시나리오 기반 프롬프트 필수!)
        ScenarioContext scenarioContext = buildScenarioContext(videoId);

        // v2.8.0: Video에서 creatorId 조회 (video는 이미 위에서 선언됨)
        Long creatorId = video != null ? video.getCreatorId() : null;

        // 진행 상태 초기화
        ProgressInfo progress = new ProgressInfo("resume", pendingScenes.size());
        progressStore.put(chatId, progress);

        // 첫 번째 미완료 씬의 순서
        int resumeFromIndex = pendingScenes.get(0).getSceneOrder();

        // v2.8.0: 비동기로 재개 - creatorId 추가
        resumeProcessAsync(userNo, chatId, pendingScenes, scenarioContext, progress, creatorId);

        return ContentDto.ProcessResumeResponse.builder()
                .chatId(chatId)
                .status("resuming")
                .resumedFromIndex(resumeFromIndex)
                .remainingCount(pendingScenes.size())
                .message(String.format("씬 %d부터 %d개 씬을 재개합니다...", resumeFromIndex, pendingScenes.size()))
                .build();
    }

    @Async
    @Transactional
    protected void resumeProcessAsync(Long userNo, Long chatId, List<Scene> pendingScenes,
                                       ScenarioContext scenarioContext, ProgressInfo progress, Long creatorId) {
        ApiKeyService.setCurrentUserNo(userNo);
        try {
            Path sceneDir = getSceneDir(chatId);
            Files.createDirectories(sceneDir);

            for (int i = 0; i < pendingScenes.size(); i++) {
                Scene scene = pendingScenes.get(i);
                progress.message = String.format("씬 %d 재개 중... (%d/%d)",
                        scene.getSceneOrder(), i + 1, pendingScenes.size());

                try {
                    sceneUpdateService.updateStatus(scene.getSceneId(), "GENERATING");

                    // v2.8.0: 미디어 생성 (없는 경우만) - creatorId 추가
                    if (scene.getImageUrl() == null || scene.getImageUrl().isEmpty()) {
                        String mediaUrl = regenerateSceneMedia(userNo, scene, scenarioContext, sceneDir, creatorId);
                        if (mediaUrl != null) {
                            scene.setImageUrl(mediaUrl);
                            sceneUpdateService.updateImageUrl(scene.getSceneId(), mediaUrl);
                        } else {
                            // v2.9.23: 이미지 생성 실패 시 FAILED 상태로 설정 (버그 수정)
                            log.error("[ContentService] Resume: Image generation failed for scene {}", scene.getSceneId());
                            sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                            sceneUpdateService.updateErrorMessage(scene.getSceneId(), "이미지 생성 실패");
                            progress.completedCount++;
                            continue;  // 다음 씬으로
                        }
                    }

                    // MEDIA_READY 상태로 변경 (TTS 생성 전)
                    sceneUpdateService.updateStatus(scene.getSceneId(), "MEDIA_READY");
                    progress.completedCount++;

                } catch (Exception e) {
                    log.error("[ContentService] Resume failed for scene {}: {}", scene.getSceneId(), e.getMessage());
                    sceneUpdateService.updateStatus(scene.getSceneId(), "FAILED");
                    sceneUpdateService.updateErrorMessage(scene.getSceneId(), e.getMessage());
                }
            }

            progress.status = "completed";
            progress.message = "프로세스 재개 완료";

            // 대화 상태 업데이트
            conversationMapper.updateCurrentStep(chatId, "PREVIEWS_DONE");

        } catch (Exception e) {
            log.error("[ContentService] Resume failed for chat: {}", chatId, e);
            progress.status = "failed";
            progress.message = "재개 실패: " + e.getMessage();
        } finally {
            ApiKeyService.clearCurrentUserNo();
        }
    }

    /**
     * v2.6.0: 통합 오디오 파일 생성 (오디오 다운로드용)
     * 개별 씬별 TTS 오디오를 하나의 파일로 병합
     */
    private void createMergedAudioFile(Long chatId, List<Scene> scenes, Path sceneDir) {
        try {
            // 오디오 경로 수집 (순서대로)
            List<String> audioPaths = new ArrayList<>();
            for (Scene scene : scenes) {
                Path audioPath = sceneDir.resolve("scene_" + String.format("%02d", scene.getSceneOrder()) + ".mp3");
                if (Files.exists(audioPath)) {
                    audioPaths.add(audioPath.toString());
                }
            }

            if (audioPaths.isEmpty()) {
                log.warn("[ContentService] No audio files found for merging, chatId: {}", chatId);
                return;
            }

            // 통합 오디오 파일 경로
            Path audioDir = getAudioDir(chatId);
            Files.createDirectories(audioDir);
            Path mergedPath = audioDir.resolve("narration_full.mp3");

            if (audioPaths.size() > 1) {
                // FFmpeg로 병합
                mergeAudioFiles(audioPaths, mergedPath.toString());
            } else {
                // 단일 파일이면 복사
                Files.copy(Paths.get(audioPaths.get(0)), mergedPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("[ContentService] Merged audio created: {} (from {} files)", mergedPath, audioPaths.size());

            // S3에 업로드 - v2.9.8: 스트리밍 업로드로 OOM 방지
            if (storageService.isEnabled() && Files.exists(mergedPath)) {
                String audioKey = getS3Key(chatId, "narration.mp3");
                long fileSize = Files.size(mergedPath);
                try (InputStream is = Files.newInputStream(mergedPath)) {
                    storageService.upload(audioKey, is, "audio/mpeg", fileSize);
                }
                log.info("[ContentService] Merged audio uploaded to S3: {} ({} bytes)", audioKey, fileSize);
            }

        } catch (Exception e) {
            log.error("[ContentService] Failed to create merged audio for chat: {}", chatId, e);
            // 병합 실패는 치명적이지 않음 - 로그만 남기고 계속
        }
    }

    /**
     * 캐릭터 블록 필수 여부를 DB 기반으로 판단
     * - hasFixedCharacter() = true: 버추얼 크리에이터 → 캐릭터 필수
     * - 그 외: 선택
     */
    private boolean isCharacterRequiredForCreator(Long creatorId) {
        if (creatorId == null) {
            return true;
        }
        // 버추얼 크리에이터는 캐릭터 필수 (DB identity_anchor 존재 여부로 판단)
        return genreConfigService.hasFixedCharacter(creatorId);
    }

    /**
     * v2.9.47: 기존 최종 영상에 썸네일 2초 영상을 append
     * ThumbnailService에서 호출 (썸네일 생성 후 최종 영상이 이미 있는 경우)
     *
     * @deprecated v2.9.49: 프로세스 재설계로 인해 더 이상 필요하지 않음.
     *             새 프로세스에서는 TTS 단계에서 썸네일 씬이 자동 생성되어 최종 영상에 포함됨.
     *             기존 영상에 대해서만 호환성을 위해 유지됨.
     */
    @Deprecated
    public void appendThumbnailToFinalVideo(Long userNo, Long chatId, String thumbnailUrl) throws IOException, InterruptedException {
        log.info("[v2.9.47] ========== Append Thumbnail to Final Video ==========");
        log.info("[v2.9.47] chatId={}, thumbnailUrl={}", chatId, thumbnailUrl);

        // 1. Video 조회
        Video video = videoMapper.findByConversationId(chatId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video not found"));

        if (!video.getUserNo().equals(userNo)) {
            throw new ApiException(ErrorCode.CONVERSATION_UNAUTHORIZED);
        }

        if (video.getVideoUrl() == null || video.getVideoUrl().isEmpty()) {
            log.warn("[v2.9.47] No final video URL found. Nothing to append to.");
            return;
        }

        // 2. 작업 디렉토리 준비
        Path workDir = getVideoDir(chatId).resolve("append_" + System.currentTimeMillis());
        Files.createDirectories(workDir);
        log.info("[v2.9.47] Work directory: {}", workDir);

        try {
            // 3. 기존 최종 영상 다운로드
            Path existingVideoPath = workDir.resolve("existing_final.mp4");
            log.info("[v2.9.47] Downloading existing final video: {} -> {}", video.getVideoUrl(), existingVideoPath);
            downloadFromUrl(video.getVideoUrl(), existingVideoPath);
            log.info("[v2.9.47] Downloaded existing final video: {} bytes", Files.size(existingVideoPath));

            // 4. 썸네일 이미지 다운로드
            // v2.9.48: prepareMediaFile() 사용 (S3 key 자동 처리, presigned URL 만료 방지)
            log.info("[v2.9.48] Preparing thumbnail image: {}", thumbnailUrl);
            Path thumbnailImagePath = prepareMediaFile(thumbnailUrl, workDir, "thumbnail.jpg");
            if (thumbnailImagePath == null || !Files.exists(thumbnailImagePath)) {
                log.error("[v2.9.48] Failed to prepare thumbnail image: {}", thumbnailUrl);
                throw new RuntimeException("썸네일 이미지를 준비할 수 없습니다: " + thumbnailUrl);
            }
            log.info("[v2.9.48] Prepared thumbnail image: {} bytes", Files.size(thumbnailImagePath));

            // 5. 포맷 정보 조회 (해상도)
            Long formatId = video.getFormatId() != null ? video.getFormatId() : 1L;
            com.aivideo.api.entity.VideoFormat format = videoFormatMapper.findById(formatId)
                    .orElseThrow(() -> new RuntimeException("Format not found: " + formatId));
            int width = format.getWidth();
            int height = format.getHeight();
            log.info("[v2.9.47] Video format: {}x{}", width, height);

            // 6. 썸네일을 2초 정적 영상으로 변환 (무음 오디오 포함)
            Path thumbnailVideoPath = workDir.resolve("thumbnail_video.mp4");
            log.info("[v2.9.47] Creating 2-second static video with silent audio from thumbnail");
            createStaticVideoFromImage(thumbnailImagePath, 2, thumbnailVideoPath);
            log.info("[v2.9.47] Created thumbnail video: {} bytes", Files.size(thumbnailVideoPath));

            // 7. 기존 영상 + 썸네일 영상 concat
            Path newFinalVideoPath = workDir.resolve("final_with_thumbnail.mp4");
            log.info("[v2.9.47] Concatenating existing video + thumbnail video");
            List<Path> videosToConcat = List.of(existingVideoPath, thumbnailVideoPath);
            concatSceneVideos(videosToConcat, newFinalVideoPath);
            log.info("[v2.9.47] Concatenated new final video: {} bytes", Files.size(newFinalVideoPath));

            // 8. 새 최종 영상 S3 업로드
            String newVideoKey = "videos/" + chatId + "_final_with_thumbnail_" + System.currentTimeMillis() + ".mp4";
            log.info("[v2.9.47] Uploading new final video to S3: {}", newVideoKey);
            String newVideoUrl;
            try (var inputStream = Files.newInputStream(newFinalVideoPath)) {
                newVideoUrl = storageService.upload(
                        newVideoKey,
                        inputStream,
                        "video/mp4",
                        Files.size(newFinalVideoPath)
                );
            }
            log.info("[v2.9.47] Uploaded new final video: {}", newVideoUrl);

            // 9. Video.videoUrl 업데이트 (thumbnailUrl은 기존 값 유지)
            videoMapper.updateVideoUrl(video.getVideoId(), newVideoUrl, thumbnailUrl);
            log.info("[v2.9.47] Updated video.videoUrl for videoId={}", video.getVideoId());

            // 10. presigned URL 만료 시간 업데이트 (3시간)
            java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusHours(3);
            videoMapper.updatePresignedUrlExpiresAt(video.getVideoId(), expiresAt);
            log.info("[v2.9.47] Updated presignedUrlExpiresAt: {}", expiresAt);

            log.info("[v2.9.47] ✅✅✅ Successfully appended thumbnail to final video!");

        } finally {
            // 11. 작업 디렉토리 정리
            try {
                Files.walk(workDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
                log.info("[v2.9.47] Cleaned up work directory: {}", workDir);
            } catch (IOException e) {
                log.warn("[v2.9.47] Failed to clean up work directory: {}", e.getMessage());
            }
        }
    }
}
