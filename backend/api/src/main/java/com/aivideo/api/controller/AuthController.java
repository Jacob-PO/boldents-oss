package com.aivideo.api.controller;

import com.aivideo.api.dto.AuthDto;
import com.aivideo.api.entity.User;
import com.aivideo.api.mapper.UserMapper;
import com.aivideo.api.security.UserPrincipal;
import com.aivideo.api.service.ApiKeyService;
import com.aivideo.api.service.AuthService;
import com.aivideo.api.util.ApiKeyEncryptor;
import com.aivideo.common.dto.ApiResponse;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 API")
public class AuthController {

    private final AuthService authService;
    private final ApiKeyService apiKeyService;
    private final ApiKeyEncryptor apiKeyEncryptor;
    private final UserMapper userMapper;

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "아이디와 비밀번호로 로그인합니다.")
    public ApiResponse<AuthDto.LoginResponse> login(@RequestBody AuthDto.LoginRequest request) {
        log.info("Login attempt: {}", request.getLoginId());
        AuthDto.LoginResponse response = authService.login(request);
        return ApiResponse.success("로그인 성공", response);
    }

    @PostMapping("/signup")
    @Operation(summary = "회원가입", description = "새 계정을 생성합니다.")
    public ApiResponse<AuthDto.LoginResponse> signup(@RequestBody AuthDto.SignupRequest request) {
        log.info("Signup attempt: {}", request.getLoginId());
        AuthDto.LoginResponse response = authService.signup(request);
        return ApiResponse.success("회원가입 성공", response);
    }

    @GetMapping("/me")
    @Operation(summary = "현재 사용자 정보", description = "현재 로그인한 사용자 정보를 조회합니다.")
    public ApiResponse<AuthDto.UserInfo> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        AuthDto.UserInfo userInfo = authService.getCurrentUser(principal.getUserNo());
        return ApiResponse.success(userInfo);
    }

    @PutMapping("/api-key")
    @Operation(summary = "Google API 키 등록/수정", description = "CUSTOM 티어 사용자의 Google API 키를 등록하거나 수정합니다.")
    public ApiResponse<AuthDto.ApiKeyResponse> saveApiKey(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody AuthDto.ApiKeyRequest request) {
        Long userNo = principal.getUserNo();
        User user = userMapper.findByUserNo(userNo)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        if (!"CUSTOM".equalsIgnoreCase(user.getTier())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "CUSTOM 티어 사용자만 API 키를 등록할 수 있습니다.");
        }

        String apiKey = request.getApiKey();
        if (!apiKeyService.validateApiKey(apiKey)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키 형식이 올바르지 않습니다. AIza로 시작하는 30자 이상의 키를 입력해주세요.");
        }

        ApiKeyService.ApiKeyTestResponse testResult = apiKeyService.testApiKey(apiKey);
        if (!testResult.isValid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "API 키 검증 실패: " + testResult.getErrorMessage());
        }

        String encryptedKey = apiKeyEncryptor.encrypt(apiKey);
        userMapper.updateGoogleApiKey(userNo, encryptedKey);
        log.info("[AuthController] Google API key saved for user {}", userNo);

        return ApiResponse.success("API 키가 정상적으로 등록되었습니다.",
                AuthDto.ApiKeyResponse.builder()
                        .hasGoogleApiKey(true)
                        .maskedKey(apiKeyEncryptor.mask(apiKey))
                        .build());
    }

    @DeleteMapping("/api-key")
    @Operation(summary = "Google API 키 삭제", description = "CUSTOM 티어 사용자의 Google API 키를 삭제합니다.")
    public ApiResponse<AuthDto.ApiKeyResponse> deleteApiKey(@AuthenticationPrincipal UserPrincipal principal) {
        Long userNo = principal.getUserNo();
        User user = userMapper.findByUserNo(userNo)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        if (!"CUSTOM".equalsIgnoreCase(user.getTier())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "CUSTOM 티어 사용자만 API 키를 관리할 수 있습니다.");
        }

        userMapper.updateGoogleApiKey(userNo, null);
        log.info("[AuthController] Google API key deleted for user {}", userNo);

        return ApiResponse.success("API 키가 삭제되었습니다.",
                AuthDto.ApiKeyResponse.builder()
                        .hasGoogleApiKey(false)
                        .maskedKey(null)
                        .build());
    }
}
