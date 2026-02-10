package com.aivideo.api.controller;

import com.aivideo.api.dto.VideoSubtitleDto;
import com.aivideo.api.entity.VideoSubtitle;
import com.aivideo.api.mapper.VideoSubtitleMapper;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 영상 자막 템플릿 API 컨트롤러 (v2.9.161)
 * 인증 불필요 (공개 API)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-subtitles")
@Tag(name = "VideoSubtitle", description = "영상 자막 템플릿 API")
public class VideoSubtitleController {

    private final VideoSubtitleMapper videoSubtitleMapper;

    @GetMapping
    @Operation(summary = "자막 템플릿 목록 조회", description = "활성화된 모든 자막 템플릿 목록을 조회합니다.")
    public ApiResponse<VideoSubtitleDto.SubtitlesResponse> getSubtitles() {
        log.info("[VideoSubtitle] Get all subtitle templates");
        List<VideoSubtitle> subtitles = videoSubtitleMapper.findAllActive();
        List<VideoSubtitleDto.SubtitleInfo> result = subtitles.stream()
            .map(this::toSubtitleInfo)
            .collect(Collectors.toList());

        return ApiResponse.success(VideoSubtitleDto.SubtitlesResponse.builder()
            .subtitles(result)
            .totalCount(result.size())
            .build());
    }

    @GetMapping("/{videoSubtitleId}")
    @Operation(summary = "자막 템플릿 상세 조회", description = "자막 템플릿 ID로 상세 정보를 조회합니다.")
    public ApiResponse<VideoSubtitleDto.SubtitleInfo> getSubtitle(@PathVariable Long videoSubtitleId) {
        log.info("[VideoSubtitle] Get subtitle template - videoSubtitleId: {}", videoSubtitleId);
        VideoSubtitle subtitle = videoSubtitleMapper.findById(videoSubtitleId)
            .orElseThrow(() -> new RuntimeException("자막 템플릿을 찾을 수 없습니다: " + videoSubtitleId));
        return ApiResponse.success(toSubtitleInfo(subtitle));
    }

    private VideoSubtitleDto.SubtitleInfo toSubtitleInfo(VideoSubtitle subtitle) {
        return VideoSubtitleDto.SubtitleInfo.builder()
            .videoSubtitleId(subtitle.getVideoSubtitleId())
            .subtitleCode(subtitle.getSubtitleCode())
            .subtitleName(subtitle.getSubtitleName())
            .subtitleNameEn(subtitle.getSubtitleNameEn())
            .description(subtitle.getDescription())
            .isDefault(subtitle.getIsDefault())
            .displayOrder(subtitle.getDisplayOrder())
            .build();
    }
}
