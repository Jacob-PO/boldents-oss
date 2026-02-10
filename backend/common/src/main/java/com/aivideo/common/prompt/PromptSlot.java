package com.aivideo.common.prompt;

import lombok.Builder;
import lombok.Data;

/**
 * Veo 3.1 슬롯 구조 프롬프트
 * Subject + Action + Setting + Style + Camera + Lighting + Motion + Audio + Constraints
 */
@Data
@Builder
public class PromptSlot {

    /** 주요 피사체 */
    private String subject;

    /** 동작/행동 */
    private String action;

    /** 배경/환경 */
    private String setting;

    /** 시각적 스타일 */
    private String style;

    /** 카메라 움직임 */
    private String camera;

    /** 조명 설정 */
    private String lighting;

    /** 움직임 특성 */
    private String motion;

    /** 오디오 설명 */
    private String audio;

    /** 제약 조건 */
    private String constraints;

    /** 네거티브 프롬프트 */
    private String negativePrompt;

    /**
     * 슬롯 구조를 완전한 프롬프트 문자열로 변환
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();

        // Subject + Action + Setting
        if (subject != null) sb.append(subject);
        if (action != null) sb.append(" ").append(action);
        if (setting != null) sb.append(" ").append(setting);
        sb.append(". ");

        // Style
        if (style != null) sb.append(style).append(". ");

        // Camera
        if (camera != null) sb.append(camera).append(". ");

        // Lighting
        if (lighting != null) sb.append(lighting).append(". ");

        // Motion
        if (motion != null) sb.append(motion).append(". ");

        // Audio
        if (audio != null) sb.append("Audio: ").append(audio).append(". ");

        // Constraints (항상 추가)
        sb.append("No subtitles, no on-screen text, no watermarks");
        if (constraints != null) sb.append(", ").append(constraints);
        sb.append(".");

        return sb.toString().trim();
    }

    /**
     * 네거티브 프롬프트와 함께 반환
     */
    public String toFullPrompt() {
        String main = toPromptString();
        if (negativePrompt != null && !negativePrompt.isEmpty()) {
            return main + "\n\nNegative: " + negativePrompt;
        }
        return main;
    }
}
