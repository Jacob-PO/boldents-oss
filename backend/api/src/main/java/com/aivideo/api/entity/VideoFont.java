package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비디오 폰트 엔티티
 * 자막 + 썸네일 공용 폰트 관리
 * 국가별 폰트 필터링 지원
 *
 * v2.9.174: 해외 버추얼 크리에이터 지원
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoFont {

    private Long fontId;                // PK
    private String fontCode;            // 'SUIT_BOLD' (코드)
    private String fontName;            // 'SUIT-Bold' (ASS Fontname + Java2D)
    private String fontNameDisplay;     // '수트 볼드' (UI 표시명)
    private String fontFileName;        // 'SUIT-Bold.ttf' (파일명)
    private String nationCode;          // FK → creator_nation
    private String description;
    private Boolean isDefault;
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
