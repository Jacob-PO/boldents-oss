package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 크리에이터 국가 엔티티
 * 국가별 언어 정보 및 자막 타이밍 설정
 *
 * v2.9.174: 해외 버추얼 크리에이터 지원
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorNation {

    private String nationCode;          // PK: 'KR', 'JP', 'US', 'CN'
    private String nationName;          // 대한민국, 日本
    private String nationNameEn;        // South Korea, Japan
    private String languageCode;        // 'ko', 'ja', 'en', 'zh'
    private String languageName;        // Korean, Japanese, English
    private Double ttsCharsPerSecond;   // 자막 타이밍 (언어별 발화 속도)
    private Integer maxCharsPerLine;    // 자막 줄바꿈 기준 글자 수
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
