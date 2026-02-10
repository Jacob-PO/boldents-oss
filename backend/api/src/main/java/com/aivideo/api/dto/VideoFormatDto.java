package com.aivideo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 영상 포맷 관련 DTO (v2.9.25)
 */
public class VideoFormatDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatInfo {
        private Long formatId;
        private String formatCode;
        private String formatName;
        private String formatNameEn;
        private Integer width;
        private Integer height;
        private String aspectRatio;
        private String icon;
        private String description;
        private String platform;
        private Boolean isDefault;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatsResponse {
        private List<FormatInfo> formats;
        private int totalCount;
    }
}
