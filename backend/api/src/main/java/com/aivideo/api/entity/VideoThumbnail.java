package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 썸네일 디자인 스타일 엔티티 (v2.9.165)
 * 유저가 채팅에서 썸네일 생성 시 디자인 스타일을 선택할 수 있음
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoThumbnail {
    private Long thumbnailId;
    private String styleCode;           // CLASSIC, PURPLE_ACCENT
    private String styleName;           // 클래식, 보라색 강조
    private String description;         // 스타일 설명

    // 테두리
    private Boolean borderEnabled;
    private String borderColor;         // hex: #8B5CF6
    private Integer borderWidth;

    // 하단 그라데이션
    private Boolean gradientEnabled;
    private String gradientColor;       // hex: #000000
    private BigDecimal gradientHeightRatio;  // 0.00 ~ 1.00
    private BigDecimal gradientOpacity;      // 0.00 ~ 1.00

    // 텍스트 색상 (줄별)
    private String textLine1Color;      // hex: 1번째 줄 색상
    private String textLine2Color;      // hex: 2번째 줄 색상

    // 텍스트 외곽선
    private String outlineColor;        // hex
    private Integer outlineThickness;

    // 텍스트 그림자
    private Boolean shadowEnabled;
    private String shadowColor;         // hex
    private Integer shadowOpacity;      // 0 ~ 255
    private Integer shadowOffsetX;
    private Integer shadowOffsetY;

    // 관리
    private Boolean isDefault;
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
