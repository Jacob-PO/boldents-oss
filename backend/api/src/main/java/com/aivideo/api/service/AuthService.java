package com.aivideo.api.service;

import com.aivideo.api.dto.AuthDto;
import com.aivideo.api.entity.User;
import com.aivideo.api.mapper.UserMapper;
import com.aivideo.api.security.JwtTokenProvider;
import com.aivideo.common.exception.ApiException;
import com.aivideo.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        User user = userMapper.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserNo(), user.getLoginId(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserNo());

        log.info("User logged in: {}", user.getLoginId());

        return AuthDto.LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(buildUserInfo(user))
                .build();
    }

    public AuthDto.UserInfo getCurrentUser(Long userNo) {
        User user = userMapper.findByUserNo(userNo)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        return buildUserInfo(user);
    }

    public AuthDto.LoginResponse signup(AuthDto.SignupRequest request) {
        // 아이디 중복 확인
        if (userMapper.findByLoginId(request.getLoginId()).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_LOGIN_ID, "이미 사용 중인 아이디입니다.");
        }

        // 사용자 생성
        User user = User.builder()
                .loginId(request.getLoginId())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .role("USER")
                .status("ACTIVE")
                .build();

        userMapper.insert(user);
        log.info("New user registered: {}", user.getLoginId());

        // 회원가입 후 자동 로그인
        String accessToken = jwtTokenProvider.createAccessToken(user.getUserNo(), user.getLoginId(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserNo());

        return AuthDto.LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(buildUserInfo(user))
                .build();
    }

    private AuthDto.UserInfo buildUserInfo(User user) {
        return AuthDto.UserInfo.builder()
                .userNo(user.getUserNo())
                .loginId(user.getLoginId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .tier(user.getTier())
                .hasGoogleApiKey(user.getGoogleApiKey() != null && !user.getGoogleApiKey().isBlank())
                .build();
    }
}
