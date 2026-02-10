package com.aivideo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 콘텐츠 유형 - 확장 가능한 구조
 * Phase 1: YOUTUBE_SCENARIO (유튜브 시나리오 비디오)
 * Phase 2+: 추가 콘텐츠 유형
 */
@Getter
@RequiredArgsConstructor
public enum ContentType {

    YOUTUBE_SCENARIO("YT_SCENARIO", "유튜브 시나리오 비디오", true),
    SHORTS("SHORTS", "쇼츠/릴스 (세로형)", true),
    PRESENTATION("PRESENTATION", "프레젠테이션", true),
    ;

    private final String code;
    private final String description;
    private final boolean enabled;
}
