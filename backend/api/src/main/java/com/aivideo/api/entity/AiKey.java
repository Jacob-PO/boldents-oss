package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 서비스 레벨 AI API 키 엔티티
 * v2.9.150: 계정별 API 키 → 서비스 레벨 다중 API 키 폴백 시스템
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiKey {
    private Long aiKeyId;
    private String provider;        // 공급사 (GOOGLE, OPENAI 등)
    private String accountEmail;    // 계정 이메일
    private String projectName;     // 프로젝트명
    private String apiKey;          // API 키 (암호화 저장)
    private Integer priority;       // 우선순위 (낮을수록 먼저 사용)
    private Boolean isActive;       // 활성화 여부
    private LocalDateTime lastUsedAt;   // 마지막 사용 시간
    private LocalDateTime lastErrorAt;  // 마지막 에러 발생 시간
    private Integer errorCount;     // 연속 에러 횟수 (성공 시 리셋)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
