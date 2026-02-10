package com.aivideo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 영상 생성 상태
 */
@Getter
@RequiredArgsConstructor
public enum VideoStatus {

    PENDING("PENDING", "대기중"),
    SCENARIO_GENERATING("SCENARIO_GEN", "시나리오 생성중"),
    SCENARIO_GENERATED("SCENARIO_DONE", "시나리오 생성 완료"),
    OPENING_GENERATING("OPENING_GEN", "오프닝 영상 생성중"),
    IMAGES_GENERATING("IMAGES_GEN", "이미지 생성중"),
    TTS_GENERATING("TTS_GEN", "나레이션 생성중"),
    COMPOSING("COMPOSING", "영상 합성중"),
    COMPLETED("COMPLETED", "완료"),
    FAILED("FAILED", "실패");

    private final String code;
    private final String description;
}
