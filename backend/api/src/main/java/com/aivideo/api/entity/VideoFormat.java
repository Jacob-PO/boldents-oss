package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상 포맷 엔티티 (v2.9.25)
 * 유튜브 일반, 유튜브 쇼츠, 인스타그램 릴스 등 다양한 포맷 지원
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoFormat {
    private Long formatId;
    private String formatCode;      // YOUTUBE_STANDARD, YOUTUBE_SHORTS, INSTAGRAM_REELS
    private String formatName;      // 유튜브 일반영상, 유튜브 쇼츠 등 (한글)
    private String formatNameEn;    // YouTube Standard, YouTube Shorts 등 (영문)
    private Integer width;          // 가로 해상도 (1920, 1080 등)
    private Integer height;         // 세로 해상도 (1080, 1920 등)
    private String aspectRatio;     // 화면 비율 (16:9, 9:16, 4:5)
    private String icon;            // 아이콘 이모지
    private String description;     // 설명
    private String platform;        // 플랫폼 (YouTube, Instagram, TikTok)
    private Boolean isDefault;      // 기본 선택 여부
    private Boolean isActive;       // 활성화 여부
    private Integer displayOrder;   // 표시 순서
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 해상도 문자열 반환 (예: "1920x1080")
     */
    public String getResolution() {
        return width + "x" + height;
    }

    /**
     * 세로 영상 여부 (쇼츠, 릴스 등)
     */
    public boolean isVertical() {
        return height > width;
    }
}
