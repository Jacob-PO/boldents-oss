package com.aivideo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AuthDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String loginId;
        private String password;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignupRequest {
        private String loginId;
        private String password;
        private String name;
        private String email;
        private String phone;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private String accessToken;
        private String refreshToken;
        private UserInfo user;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long userNo;
        private String loginId;
        private String name;
        private String email;
        private String phone;
        private String role;
        private String tier;
        private boolean hasGoogleApiKey;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKeyRequest {
        private String apiKey;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKeyResponse {
        private boolean hasGoogleApiKey;
        private String maskedKey;
    }
}
