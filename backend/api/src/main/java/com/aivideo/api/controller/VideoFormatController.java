package com.aivideo.api.controller;

import com.aivideo.api.dto.VideoFormatDto;
import com.aivideo.api.entity.VideoFormat;
import com.aivideo.api.mapper.VideoFormatMapper;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 영상 포맷 API 컨트롤러 (v2.9.25)
 * 인증 불필요 (공개 API)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/formats")
@Tag(name = "VideoFormat", description = "영상 포맷 API")
public class VideoFormatController {

    private final VideoFormatMapper videoFormatMapper;

    @GetMapping
    @Operation(summary = "포맷 목록 조회", description = "활성화된 모든 영상 포맷 목록을 조회합니다.")
    public ApiResponse<VideoFormatDto.FormatsResponse> getFormats() {
        log.info("[VideoFormat] Get all formats");
        List<VideoFormat> formats = videoFormatMapper.findAllActive();
        List<VideoFormatDto.FormatInfo> result = formats.stream()
            .map(this::toFormatInfo)
            .collect(Collectors.toList());

        return ApiResponse.success(VideoFormatDto.FormatsResponse.builder()
            .formats(result)
            .totalCount(result.size())
            .build());
    }

    @GetMapping("/{formatId}")
    @Operation(summary = "포맷 상세 조회", description = "포맷 ID로 상세 정보를 조회합니다.")
    public ApiResponse<VideoFormatDto.FormatInfo> getFormat(@PathVariable Long formatId) {
        log.info("[VideoFormat] Get format - formatId: {}", formatId);
        VideoFormat format = videoFormatMapper.findById(formatId)
            .orElseThrow(() -> new RuntimeException("포맷을 찾을 수 없습니다: " + formatId));
        return ApiResponse.success(toFormatInfo(format));
    }

    private VideoFormatDto.FormatInfo toFormatInfo(VideoFormat format) {
        return VideoFormatDto.FormatInfo.builder()
            .formatId(format.getFormatId())
            .formatCode(format.getFormatCode())
            .formatName(format.getFormatName())
            .formatNameEn(format.getFormatNameEn())
            .width(format.getWidth())
            .height(format.getHeight())
            .aspectRatio(format.getAspectRatio())
            .icon(format.getIcon())
            .description(format.getDescription())
            .platform(format.getPlatform())
            .isDefault(format.getIsDefault())
            .build();
    }
}
