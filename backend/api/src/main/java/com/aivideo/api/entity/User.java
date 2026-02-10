package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long userNo;
    private String loginId;
    private String password;
    private String name;
    private String email;
    private String phone;
    private LocalDate birthDate;
    private String profileImage;
    private String role;
    private String tier;          // FREE, PREMIUM, CUSTOM 등
    private Long creatorId;       // v2.9.150: 연결된 크리에이터 ID (1:1 매핑)
    private String googleApiKey;  // CUSTOM 티어 사용자의 개인 Google API 키 (암호화)

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
