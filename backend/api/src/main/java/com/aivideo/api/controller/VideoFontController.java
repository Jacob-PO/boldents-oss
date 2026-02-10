package com.aivideo.api.controller;

import com.aivideo.api.dto.VideoFontDto;
import com.aivideo.api.entity.VideoFont;
import com.aivideo.api.mapper.VideoFontMapper;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 비디오 폰트 API 컨트롤러 (v2.9.174)
 * 인증 불필요 (공개 API)
 * 해외 버추얼 크리에이터 지원 - 국가별 폰트 조회
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-fonts")
@Tag(name = "VideoFont", description = "비디오 폰트 API")
public class VideoFontController {

    private final VideoFontMapper videoFontMapper;

    @GetMapping
    @Operation(summary = "폰트 목록 조회", description = "활성화된 모든 폰트 목록을 조회합니다.")
    public ApiResponse<VideoFontDto.FontsResponse> getFonts() {
        log.info("[VideoFont] Get all fonts");
        List<VideoFont> fonts = videoFontMapper.findAllActive();
        List<VideoFontDto.FontInfo> result = fonts.stream()
                .map(this::toFontInfo)
                .collect(Collectors.toList());

        return ApiResponse.success(VideoFontDto.FontsResponse.builder()
                .fonts(result)
                .totalCount(result.size())
                .build());
    }

    @GetMapping("/{fontId}")
    @Operation(summary = "폰트 상세 조회", description = "폰트 ID로 상세 정보를 조회합니다.")
    public ApiResponse<VideoFontDto.FontInfo> getFont(@PathVariable Long fontId) {
        log.info("[VideoFont] Get font - fontId: {}", fontId);
        VideoFont font = videoFontMapper.findById(fontId)
                .orElseThrow(() -> new RuntimeException("폰트를 찾을 수 없습니다: " + fontId));
        return ApiResponse.success(toFontInfo(font));
    }

    @GetMapping("/nation/{nationCode}")
    @Operation(summary = "국가별 폰트 목록 조회", description = "국가 코드별 활성화된 폰트 목록을 조회합니다.")
    public ApiResponse<VideoFontDto.FontsResponse> getFontsByNation(@PathVariable String nationCode) {
        log.info("[VideoFont] Get fonts by nation - nationCode: {}", nationCode);
        List<VideoFont> fonts = videoFontMapper.findByNationCode(nationCode);
        List<VideoFontDto.FontInfo> result = fonts.stream()
                .map(this::toFontInfo)
                .collect(Collectors.toList());

        return ApiResponse.success(VideoFontDto.FontsResponse.builder()
                .fonts(result)
                .totalCount(result.size())
                .build());
    }

    private VideoFontDto.FontInfo toFontInfo(VideoFont font) {
        return VideoFontDto.FontInfo.builder()
                .fontId(font.getFontId())
                .fontCode(font.getFontCode())
                .fontName(font.getFontName())
                .fontNameDisplay(font.getFontNameDisplay())
                .nationCode(font.getNationCode())
                .description(font.getDescription())
                .isDefault(font.getIsDefault())
                .displayOrder(font.getDisplayOrder())
                .build();
    }
}
