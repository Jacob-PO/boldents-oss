package com.aivideo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 영상 자막 템플릿 관련 DTO (v2.9.161)
 */
public class VideoSubtitleDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubtitleInfo {
        private Long videoSubtitleId;
        private String subtitleCode;
        private String subtitleName;
        private String subtitleNameEn;
        private String description;
        private Boolean isDefault;
        private Integer displayOrder;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubtitlesResponse {
        private List<SubtitleInfo> subtitles;
        private int totalCount;
    }
}
