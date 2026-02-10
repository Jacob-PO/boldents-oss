package com.aivideo.api.dto;

import lombok.*;

/**
 * 크리에이터 관련 DTO
 * v2.9.121: Genre → Creator 리네이밍
 * v2.9.126: icon, description, targetAudience 삭제
 * v2.9.127: showOnHome → isActive 통합, homeDescription → description 변경
 */
public class CreatorDto {

    /**
     * 크리에이터 정보 (목록용)
     * v2.9.126: icon, description, targetAudience 삭제
     * v2.9.127: showOnHome 삭제, homeDescription → description 변경
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatorInfo {
        private Long creatorId;
        private String creatorCode;
        private String creatorName;
        // v2.9.127: showOnHome 삭제 (isActive로 기능 통합)
        private String placeholderText;     // v2.9.103: 입력창 플레이스홀더
        private String description;         // v2.9.127: homeDescription에서 변경
        private Boolean allowImageUpload;   // v2.9.120: ULTRA 티어만 허용 (백엔드에서 동적 결정)
        private String tierCode;            // v2.9.120: AI 모델 티어 (BASIC, PRO, ULTRA)
    }

    /**
     * 크리에이터 상세
     * v2.9.126: icon, description, targetAudience 삭제
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatorDetail {
        private Long creatorId;
        private String creatorCode;
        private String creatorName;
    }
}
