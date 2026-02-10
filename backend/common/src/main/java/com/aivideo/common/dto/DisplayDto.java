package com.aivideo.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * UI 표시용 유틸리티 DTO
 */
public class DisplayDto {

    // ============ 상태 표시 ============

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusDisplay {
        private String label;
        private String variant; // success, warning, default, error
    }

    // ============ 포맷팅 요청/응답 ============

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatDurationRequest {
        private Integer seconds;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatDateRequest {
        private String dateString;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatResponse {
        private String formatted;
    }
}
