package com.aivideo.api.controller;

import com.aivideo.api.dto.ContentDto;
import com.aivideo.api.entity.VideoThumbnail;
import com.aivideo.api.mapper.VideoThumbnailMapper;
import com.aivideo.api.security.UserPrincipal;
import com.aivideo.api.service.ContentService;
import com.aivideo.api.service.ThumbnailService;
import com.aivideo.api.util.PathValidator;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/content")
@Tag(name = "Content", description = "콘텐츠 생성 및 다운로드 API")
public class ContentController {

    private final ContentService contentService;
    private final ThumbnailService thumbnailService;
    private final VideoThumbnailMapper videoThumbnailMapper;

    // ========== 시나리오 ==========

    @GetMapping("/{chatId}/scenario")
    @Operation(summary = "시나리오 조회", description = "기존 생성된 시나리오를 조회합니다.")
    public ApiResponse<ContentDto.ScenarioResponse> getScenario(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get scenario - chatId: {}", chatId);
        ContentDto.ScenarioResponse response = contentService.getScenario(user.getUserNo(), chatId);
        if (response == null) {
            return ApiResponse.success("시나리오가 아직 생성되지 않았습니다.", null);
        }
        return ApiResponse.success(response);
    }

    /**
     * v2.9.75: 시나리오 생성 진행 상황 조회
     */
    @GetMapping("/{chatId}/scenario/progress")
    @Operation(summary = "시나리오 생성 진행 상황 조회", description = "시나리오 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.ScenarioProgressResponse> getScenarioProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.ScenarioProgressResponse response = contentService.getScenarioProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{chatId}/scenario")
    @Operation(summary = "시나리오 생성", description = "대화 내용을 기반으로 시나리오를 생성합니다.")
    public ApiResponse<ContentDto.ScenarioResponse> generateScenario(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.ScenarioRequest request) {
        log.info("[Content] Generate scenario - chatId: {}, slideCount: {}, creatorId: {}, formatId: {}",
                chatId,
                request != null ? request.getSlideCount() : "default",
                request != null ? request.getCreatorId() : "default",
                request != null ? request.getFormatId() : "default");
        ContentDto.ScenarioResponse response = contentService.generateScenario(user.getUserNo(), chatId, request);
        return ApiResponse.success("시나리오가 생성되었습니다.", response);
    }

    @GetMapping("/{chatId}/scenario/download")
    @Operation(summary = "시나리오 다운로드 정보", description = "시나리오 파일 다운로드 정보를 조회합니다.")
    public ApiResponse<ContentDto.DownloadInfo> getScenarioDownloadInfo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get scenario download info - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getScenarioDownload(user.getUserNo(), chatId);
        return ApiResponse.success(info);
    }

    @GetMapping("/{chatId}/scenario/file")
    @Operation(summary = "시나리오 파일 다운로드", description = "생성된 시나리오를 TXT 파일로 다운로드합니다.")
    public ResponseEntity<Resource> downloadScenarioFile(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Download scenario file - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getScenarioDownload(user.getUserNo(), chatId);
        Resource resource = contentService.getScenarioResource(user.getUserNo(), chatId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(info.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(info.getFilename()) + "\"")
                .body(resource);
    }

    // ========== 이미지 ==========

    @PostMapping("/{chatId}/images")
    @Operation(summary = "이미지 생성", description = "시나리오의 각 슬라이드에 대한 이미지를 생성합니다.")
    public ApiResponse<ContentDto.ImagesResponse> generateImages(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Generate images - chatId: {}", chatId);
        ContentDto.ImagesResponse response = contentService.generateImages(user.getUserNo(), chatId);
        return ApiResponse.success("이미지 생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/images/progress")
    @Operation(summary = "이미지 생성 진행 상황", description = "이미지 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.ImagesResponse> getImagesProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.ImagesResponse response = contentService.getImagesProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @GetMapping("/{chatId}/images/info")
    @Operation(summary = "이미지 다운로드 정보", description = "이미지 다운로드 정보를 조회합니다.")
    public ApiResponse<ContentDto.DownloadInfo> getImagesInfo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get images info - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getImagesDownload(user.getUserNo(), chatId);
        return ApiResponse.success(info);
    }

    @GetMapping("/{chatId}/images/download")
    @Operation(summary = "이미지 다운로드", description = "생성된 모든 이미지를 ZIP 파일로 다운로드합니다.")
    public ResponseEntity<?> downloadImages(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Download images - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getImagesDownload(user.getUserNo(), chatId);

        // v2.9.8: S3에 파일이 있으면 presigned URL로 리다이렉트
        // v2.9.11: URL 검증 추가 (Open Redirect 방지)
        if (info.getDownloadUrl() != null && !info.getDownloadUrl().isEmpty()) {
            String validatedUrl = PathValidator.validateRedirectUrl(info.getDownloadUrl());
            log.info("[Content] Redirecting to S3 presigned URL for images download - chatId: {}", chatId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, validatedUrl)
                    .build();
        }

        // 로컬 파일 폴백
        Resource resource = contentService.getImagesResource(user.getUserNo(), chatId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(info.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(info.getFilename()) + "\"")
                .body(resource);
    }

    // ========== 오디오 (TTS) ==========

    @PostMapping("/{chatId}/audio")
    @Operation(summary = "나레이션 생성", description = "시나리오의 나레이션을 TTS로 생성합니다.")
    public ApiResponse<ContentDto.AudioResponse> generateAudio(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Generate audio - chatId: {}", chatId);
        ContentDto.AudioResponse response = contentService.generateAudio(user.getUserNo(), chatId);
        return ApiResponse.success("나레이션 생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/audio/progress")
    @Operation(summary = "나레이션 생성 진행 상황", description = "나레이션 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.AudioResponse> getAudioProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.AudioResponse response = contentService.getAudioProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @GetMapping("/{chatId}/audio/info")
    @Operation(summary = "나레이션 다운로드 정보", description = "나레이션 다운로드 정보를 조회합니다.")
    public ApiResponse<ContentDto.DownloadInfo> getAudioInfo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get audio info - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getAudioDownload(user.getUserNo(), chatId);
        return ApiResponse.success(info);
    }

    @GetMapping("/{chatId}/audio/download")
    @Operation(summary = "나레이션 다운로드", description = "생성된 나레이션을 MP3 파일로 다운로드합니다.")
    public ResponseEntity<?> downloadAudio(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Download audio - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getAudioDownload(user.getUserNo(), chatId);

        // v2.9.8: S3에 파일이 있으면 presigned URL로 리다이렉트
        // v2.9.11: URL 검증 추가 (Open Redirect 방지)
        if (info.getDownloadUrl() != null && !info.getDownloadUrl().isEmpty()) {
            String validatedUrl = PathValidator.validateRedirectUrl(info.getDownloadUrl());
            log.info("[Content] Redirecting to S3 presigned URL for audio download - chatId: {}", chatId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, validatedUrl)
                    .build();
        }

        // 로컬 파일 폴백
        Resource resource = contentService.getAudioResource(user.getUserNo(), chatId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(info.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(info.getFilename()) + "\"")
                .body(resource);
    }

    // ========== 영상 ==========

    @PostMapping("/{chatId}/video")
    @Operation(summary = "영상 합성", description = "이미지와 나레이션을 합성하여 영상을 생성합니다.")
    public ApiResponse<ContentDto.VideoResponse> generateVideo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.VideoRequest request) {
        log.info("[Content] Generate video - chatId: {}", chatId);
        ContentDto.VideoResponse response = contentService.generateVideo(user.getUserNo(), chatId, request);
        return ApiResponse.success("영상 합성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/video/progress")
    @Operation(summary = "영상 합성 진행 상황", description = "영상 합성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.VideoResponse> getVideoProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.VideoResponse response = contentService.getVideoProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @GetMapping("/{chatId}/video/info")
    @Operation(summary = "영상 다운로드 정보", description = "영상 다운로드 정보를 조회합니다.")
    public ApiResponse<ContentDto.DownloadInfo> getVideoInfo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get video info - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getVideoDownload(user.getUserNo(), chatId);
        return ApiResponse.success(info);
    }

    @GetMapping("/{chatId}/video/download")
    @Operation(summary = "영상 다운로드", description = "생성된 영상을 MP4 파일로 다운로드합니다.")
    public ResponseEntity<?> downloadVideo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Download video - chatId: {}", chatId);
        ContentDto.DownloadInfo info = contentService.getVideoDownload(user.getUserNo(), chatId);

        // v2.9.8: S3에 파일이 있으면 presigned URL로 리다이렉트
        // v2.9.11: URL 검증 추가 (Open Redirect 방지)
        if (info.getDownloadUrl() != null && !info.getDownloadUrl().isEmpty()) {
            String validatedUrl = PathValidator.validateRedirectUrl(info.getDownloadUrl());
            log.info("[Content] Redirecting to S3 presigned URL for video download - chatId: {}", chatId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, validatedUrl)
                    .build();
        }

        // 로컬 파일 폴백
        Resource resource = contentService.getVideoResource(user.getUserNo(), chatId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(info.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(info.getFilename()) + "\"")
                .body(resource);
    }

    // ========== 공통 ==========

    @GetMapping("/{chatId}/progress")
    @Operation(summary = "전체 진행 상황", description = "콘텐츠 생성 전체 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.ProgressResponse> getProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.ProgressResponse response = contentService.getProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    // ========== v2.4.0 개별 씬 영상 생성 API ==========

    @PostMapping("/{chatId}/scenes")
    @Operation(summary = "씬 생성 시작", description = "시나리오의 각 씬(오프닝 영상 + 슬라이드 이미지)과 TTS, 자막을 생성하고 개별 씬 영상을 합성합니다.")
    public ApiResponse<ContentDto.ScenesGenerateResponse> generateScenes(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.ScenesGenerateRequest request) {
        log.info("[Content] Generate scenes - chatId: {}", chatId);
        ContentDto.ScenesGenerateResponse response = contentService.generateScenes(user.getUserNo(), chatId, request);
        return ApiResponse.success("씬 생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/scenes/progress")
    @Operation(summary = "씬 생성 진행 상황", description = "씬 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.ScenesGenerateResponse> getScenesProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.ScenesGenerateResponse response = contentService.getScenesProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @GetMapping("/{chatId}/scenes/review")
    @Operation(summary = "씬 검토", description = "생성된 모든 씬의 상태와 정보를 조회합니다. 사용자가 각 씬을 검토할 수 있습니다.")
    public ApiResponse<ContentDto.ScenesReviewResponse> getScenesReview(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get scenes review - chatId: {}", chatId);
        ContentDto.ScenesReviewResponse response = contentService.getScenesReview(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{chatId}/scenes/regenerate")
    @Operation(summary = "씬 재생성", description = "특정 씬을 재생성합니다. 사용자 피드백이나 새 프롬프트를 제공할 수 있습니다.")
    public ApiResponse<ContentDto.SceneRegenerateResponse> regenerateScene(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody ContentDto.SceneRegenerateRequest request) {
        log.info("[Content] Regenerate scene - chatId: {}, sceneId: {}", chatId, request.getSceneId());
        ContentDto.SceneRegenerateResponse response = contentService.regenerateScene(user.getUserNo(), chatId, request);
        return ApiResponse.success("씬 재생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/scenes/zip")
    @Operation(summary = "씬 ZIP 다운로드 정보", description = "개별 씬 영상들의 ZIP 파일 다운로드 정보를 조회합니다.")
    public ApiResponse<ContentDto.ScenesZipInfo> getScenesZipInfo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get scenes zip info - chatId: {}", chatId);
        ContentDto.ScenesZipInfo info = contentService.getScenesZipInfo(user.getUserNo(), chatId);
        return ApiResponse.success(info);
    }

    @GetMapping("/{chatId}/scenes/download")
    @Operation(summary = "씬 ZIP 다운로드", description = "개별 씬 영상들을 ZIP 파일로 다운로드합니다.")
    public ResponseEntity<?> downloadScenes(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Download scenes - chatId: {}", chatId);
        ContentDto.ScenesZipInfo info = contentService.getScenesZipInfo(user.getUserNo(), chatId);

        // v2.9.8: S3에 파일이 있으면 presigned URL로 리다이렉트
        // v2.9.11: URL 검증 추가 (Open Redirect 방지)
        if (info.getDownloadUrl() != null && !info.getDownloadUrl().isEmpty()) {
            String validatedUrl = PathValidator.validateRedirectUrl(info.getDownloadUrl());
            log.info("[Content] Redirecting to S3 presigned URL for scenes download - chatId: {}", chatId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, validatedUrl)
                    .build();
        }

        // 로컬 파일 폴백
        Resource resource = contentService.getScenesZipResource(user.getUserNo(), chatId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(info.getFilename()) + "\"")
                .body(resource);
    }

    @PostMapping("/{chatId}/final-video")
    @Operation(summary = "최종 영상 생성", description = "모든 확정된 씬 영상들을 합성하여 최종 영상을 생성합니다.")
    public ApiResponse<ContentDto.FinalVideoResponse> generateFinalVideo(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.FinalVideoRequest request) {
        log.info("[Content] Generate final video - chatId: {}", chatId);
        ContentDto.FinalVideoResponse response = contentService.generateFinalVideo(user.getUserNo(), chatId, request);
        return ApiResponse.success("최종 영상 합성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/final-video/progress")
    @Operation(summary = "최종 영상 진행 상황", description = "최종 영상 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.FinalVideoResponse> getFinalVideoProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.FinalVideoResponse response = contentService.getFinalVideoProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{chatId}/thumbnail")
    @Operation(summary = "유튜브 썸네일 생성", description = "영상의 썸네일(이미지+텍스트)을 생성합니다. thumbnailId로 디자인 스타일을 선택할 수 있습니다.")
    public ApiResponse<Map<String, Object>> generateThumbnail(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestParam(required = false) Long thumbnailId) {
        log.info("[Content] Generate thumbnail - chatId: {}, thumbnailId: {}", chatId, thumbnailId);

        // v2.9.84: 비동기로 실행 (클라이언트 연결 끊김에도 계속 진행)
        // 클라이언트가 페이지를 나가거나 새로고침해도 썸네일 생성은 계속됨
        final Long userNo = user.getUserNo();
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[v2.9.165] Async thumbnail generation started - chatId: {}, thumbnailId: {}", chatId, thumbnailId);
                thumbnailService.generateThumbnail(userNo, chatId, thumbnailId);
                log.info("[v2.9.165] Async thumbnail generation completed - chatId: {}", chatId);
            } catch (Exception e) {
                log.error("[v2.9.165] Async thumbnail generation failed - chatId: {}", chatId, e);
            }
        });

        return ApiResponse.success("썸네일 생성이 시작되었습니다. GET /api/content/{chatId}/thumbnail로 결과를 확인하세요.",
            Map.of("chatId", chatId, "status", "GENERATING"));
    }

    // ========== v2.9.165 썸네일 디자인 스타일 조회 ==========

    @GetMapping("/thumbnail-styles")
    @Operation(summary = "썸네일 디자인 스타일 목록", description = "활성화된 썸네일 디자인 스타일 목록을 조회합니다.")
    public ApiResponse<java.util.List<VideoThumbnail>> getThumbnailStyles() {
        java.util.List<VideoThumbnail> styles = videoThumbnailMapper.findAllActive();
        return ApiResponse.success(styles);
    }

    @GetMapping("/{chatId}/thumbnail")
    @Operation(summary = "유튜브 썸네일 조회", description = "생성된 썸네일 정보를 조회합니다. 생성 중이면 status=GENERATING, 완료되면 status=COMPLETED")
    public ApiResponse<ContentDto.ThumbnailResponse> getThumbnail(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get thumbnail - chatId: {}", chatId);
        ContentDto.ThumbnailResponse response = thumbnailService.getThumbnail(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    // ========== v2.5.0 씬 프리뷰 및 나레이션 편집 API ==========

    @PostMapping("/{chatId}/scenes/preview")
    @Operation(summary = "씬 프리뷰 생성", description = "씬의 이미지/영상만 먼저 생성합니다. TTS 생성 전에 나레이션 텍스트를 편집할 수 있습니다.")
    public ApiResponse<ContentDto.ScenePreviewResponse> generateScenePreview(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.ScenePreviewRequest request) {
        log.info("[Content] Generate scene preview - chatId: {}", chatId);
        ContentDto.ScenePreviewResponse response = contentService.generateScenePreview(user.getUserNo(), chatId, request);
        return ApiResponse.success("씬 프리뷰 생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/scenes/preview")
    @Operation(summary = "씬 프리뷰 조회", description = "생성된 씬 프리뷰 정보를 조회합니다. 이미지와 편집 가능한 나레이션 텍스트가 포함됩니다.")
    public ApiResponse<ContentDto.ScenePreviewResponse> getScenePreview(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get scene preview - chatId: {}", chatId);
        ContentDto.ScenePreviewResponse response = contentService.getScenePreview(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PutMapping("/{chatId}/scenes/narration")
    @Operation(summary = "씬 나레이션 편집", description = "특정 씬의 나레이션 텍스트를 편집합니다. TTS 생성 전에 사용합니다.")
    public ApiResponse<ContentDto.SceneNarrationEditResponse> editSceneNarration(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody ContentDto.SceneNarrationEditRequest request) {
        log.info("[Content] Edit scene narration - chatId: {}, sceneId: {}", chatId, request.getSceneId());
        ContentDto.SceneNarrationEditResponse response = contentService.editSceneNarration(user.getUserNo(), chatId, request);
        return ApiResponse.success("나레이션이 수정되었습니다.", response);
    }

    @PostMapping("/{chatId}/scenes/audio")
    @Operation(summary = "씬 TTS/자막 생성", description = "나레이션 편집 완료 후 TTS 오디오와 자막을 생성합니다.")
    public ApiResponse<ContentDto.SceneAudioGenerateResponse> generateSceneAudio(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.SceneAudioGenerateRequest request) {
        log.info("[Content] Generate scene audio - chatId: {}", chatId);
        ContentDto.SceneAudioGenerateResponse response = contentService.generateSceneAudio(user.getUserNo(), chatId, request);
        return ApiResponse.success("TTS/자막 생성이 시작되었습니다.", response);
    }

    @GetMapping("/{chatId}/scenes/audio/progress")
    @Operation(summary = "TTS/자막 생성 진행 상황", description = "TTS/자막 생성 진행 상황을 조회합니다.")
    public ApiResponse<ContentDto.SceneAudioGenerateResponse> getSceneAudioProgress(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        ContentDto.SceneAudioGenerateResponse response = contentService.getSceneAudioProgress(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    // ========== v2.6.0 부분 실패 복구 API ==========

    @GetMapping("/{chatId}/scenes/failed")
    @Operation(summary = "실패한 씬 목록 조회", description = "실패한 씬 목록과 실패 원인을 조회합니다.")
    public ApiResponse<ContentDto.FailedScenesRetryResponse> getFailedScenes(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get failed scenes - chatId: {}", chatId);
        ContentDto.FailedScenesRetryResponse response = contentService.getFailedScenes(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{chatId}/scenes/retry")
    @Operation(summary = "실패한 씬 재시도", description = "실패한 씬만 선택적으로 재시도합니다. 전체 재시작 없이 실패 씬만 재생성합니다.")
    public ApiResponse<ContentDto.FailedScenesRetryResponse> retryFailedScenes(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.FailedScenesRetryRequest request) {
        log.info("[Content] Retry failed scenes - chatId: {}", chatId);
        ContentDto.FailedScenesRetryResponse response = contentService.retryFailedScenes(user.getUserNo(), chatId, request);
        return ApiResponse.success("실패 씬 재시도를 시작합니다.", response);
    }

    @GetMapping("/{chatId}/checkpoint")
    @Operation(summary = "진행 상태 체크포인트 조회", description = "서버 재시작 후에도 복구 가능한 진행 상태를 조회합니다.")
    public ApiResponse<ContentDto.ProcessCheckpoint> getProcessCheckpoint(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId) {
        log.info("[Content] Get process checkpoint - chatId: {}", chatId);
        ContentDto.ProcessCheckpoint response = contentService.getProcessCheckpoint(user.getUserNo(), chatId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{chatId}/resume")
    @Operation(summary = "프로세스 재개", description = "중단된 프로세스를 재개합니다. 서버 재시작 후 복구에 사용합니다.")
    public ApiResponse<ContentDto.ProcessResumeResponse> resumeProcess(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long chatId,
            @RequestBody(required = false) ContentDto.ProcessResumeRequest request) {
        log.info("[Content] Resume process - chatId: {}", chatId);
        ContentDto.ProcessResumeResponse response = contentService.resumeProcess(user.getUserNo(), chatId, request);
        return ApiResponse.success("프로세스 재개를 시작합니다.", response);
    }

    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    }
}
