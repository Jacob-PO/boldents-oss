package com.aivideo.api.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal {
    private Long userNo;
    private String loginId;
    private String role;
}
