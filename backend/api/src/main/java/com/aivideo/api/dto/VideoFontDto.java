package com.aivideo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 비디오 폰트 관련 DTO (v2.9.174)
 * 해외 버추얼 크리에이터 지원 - 국가별 폰트 관리
 */
public class VideoFontDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FontInfo {
        private Long fontId;
        private String fontCode;
        private String fontName;
        private String fontNameDisplay;
        private String nationCode;
        private String description;
        private Boolean isDefault;
        private Integer displayOrder;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FontsResponse {
        private List<FontInfo> fonts;
        private int totalCount;
    }
}
