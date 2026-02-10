package com.aivideo.api.controller;

import com.aivideo.api.dto.CreatorDto;
import com.aivideo.api.entity.Creator;
import com.aivideo.api.service.CreatorConfigService;
import com.aivideo.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 크리에이터 API 컨트롤러
 * 인증 불필요 (공개 API)
 * v2.9.121: Genre → Creator 리네이밍
 * v2.9.127: showOnHome → isActive 통합, homeDescription → description 변경
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/creators")
@Tag(name = "Creator", description = "크리에이터 API")
public class CreatorController {

    private final CreatorConfigService creatorConfigService;

    @GetMapping
    @Operation(summary = "크리에이터 목록 조회", description = "활성화된 모든 크리에이터 목록을 조회합니다.")
    public ApiResponse<List<CreatorDto.CreatorInfo>> getCreators() {
        log.info("[Creator] Get all creators");
        List<Creator> creators = creatorConfigService.getAllActiveCreators();
        List<CreatorDto.CreatorInfo> result = creators.stream()
            .map(this::toCreatorInfo)
            .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    // v2.9.127: showOnHome이 isActive로 통합되어 getAllActiveCreators 사용
    @GetMapping("/home")
    @Operation(summary = "홈 화면 노출 크리에이터 조회", description = "홈 화면에 표시되는 크리에이터 목록을 조회합니다.")
    public ApiResponse<List<CreatorDto.CreatorInfo>> getCreatorsForHome() {
        log.info("[Creator] Get creators for home");
        List<Creator> creators = creatorConfigService.getAllActiveCreators();
        List<CreatorDto.CreatorInfo> result = creators.stream()
            .map(this::toCreatorInfo)
            .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @GetMapping("/{creatorId}")
    @Operation(summary = "크리에이터 상세 조회", description = "크리에이터 상세 정보를 조회합니다.")
    public ApiResponse<CreatorDto.CreatorDetail> getCreatorDetail(@PathVariable Long creatorId) {
        log.info("[Creator] Get creator detail - creatorId: {}", creatorId);
        Creator creator = creatorConfigService.getCreator(creatorId);
        return ApiResponse.success(toCreatorDetail(creator));
    }

    // ========== DTO 변환 메서드 ==========

    // v2.9.126: icon, description, targetAudience, displayOrder 삭제
    // v2.9.127: showOnHome 삭제, homeDescription → description 변경
    private CreatorDto.CreatorInfo toCreatorInfo(Creator creator) {
        // v2.9.120: 티어 정보 조회 (ULTRA 티어만 이미지 업로드 허용)
        String tierCode = creatorConfigService.getTierCode(creator.getCreatorId());
        boolean allowImageUpload = "ULTRA".equals(tierCode);

        return CreatorDto.CreatorInfo.builder()
            .creatorId(creator.getCreatorId())
            .creatorCode(creator.getCreatorCode())
            .creatorName(creator.getCreatorName())
            // v2.9.127: showOnHome 삭제 (isActive로 기능 통합)
            .placeholderText(creator.getPlaceholderText())
            .description(creator.getDescription())                // v2.9.127: homeDescription에서 변경
            .allowImageUpload(allowImageUpload)                   // v2.9.120: ULTRA 티어만 허용
            .tierCode(tierCode)                                   // v2.9.120: AI 모델 티어
            .build();
    }

    // v2.9.126: icon, description, targetAudience 삭제
    private CreatorDto.CreatorDetail toCreatorDetail(Creator creator) {
        return CreatorDto.CreatorDetail.builder()
            .creatorId(creator.getCreatorId())
            .creatorCode(creator.getCreatorCode())
            .creatorName(creator.getCreatorName())
            .build();
    }
}
