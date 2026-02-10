package com.aivideo.common.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 프레이밍 타입 (샷 사이즈)
 */
@Getter
@RequiredArgsConstructor
public enum FramingType {

    EXTREME_CLOSE_UP(
            "Extreme Close-up",
            "ECU",
            "extreme close-up, single feature filling frame (eye, lips, hands)",
            "24mm-35mm"
    ),
    CLOSE_UP(
            "Close-up",
            "CU",
            "close-up, face filling frame, intimate detail",
            "50mm-85mm"
    ),
    MEDIUM_CLOSE_UP(
            "Medium Close-up",
            "MCU",
            "medium close-up, head and shoulders visible",
            "50mm-85mm"
    ),
    MEDIUM_SHOT(
            "Medium Shot",
            "MS",
            "medium shot, waist up, conversational distance",
            "35mm-50mm"
    ),
    MEDIUM_WIDE(
            "Medium Wide",
            "MWS",
            "medium wide shot, knees up, shows body language",
            "35mm"
    ),
    FULL_SHOT(
            "Full Shot",
            "FS",
            "full shot, entire body visible with headroom and footroom",
            "24mm-35mm"
    ),
    WIDE_SHOT(
            "Wide Shot",
            "WS",
            "wide shot, subject in environment, context established",
            "18mm-24mm"
    ),
    EXTREME_WIDE(
            "Extreme Wide Shot",
            "EWS",
            "extreme wide establishing shot, subject small in vast environment, epic scale",
            "14mm-18mm"
    ),
    OVER_THE_SHOULDER(
            "Over the Shoulder",
            "OTS",
            "over the shoulder shot, foreground character partially visible, focus on background character",
            "50mm-85mm"
    ),
    POV(
            "Point of View",
            "POV",
            "point of view shot, first-person perspective, seeing through character's eyes",
            "24mm-35mm"
    ),
    DUTCH_ANGLE(
            "Dutch Angle",
            "Dutch",
            "dutch angle, tilted frame creating unease, off-kilter composition",
            "any"
    ),
    LOW_ANGLE(
            "Low Angle",
            "LA",
            "low angle shot, camera below eye level looking up, subject appears powerful",
            "24mm-35mm"
    ),
    HIGH_ANGLE(
            "High Angle",
            "HA",
            "high angle shot, camera above eye level looking down, subject appears vulnerable",
            "24mm-35mm"
    ),
    BIRDS_EYE(
            "Bird's Eye",
            "BE",
            "bird's eye view, directly overhead, abstract perspective, patterns visible",
            "any"
    );

    private final String name;
    private final String abbreviation;
    private final String promptText;
    private final String recommendedLens;

    /**
     * Rule of Thirds 위치와 함께 프롬프트 생성
     */
    public String withRuleOfThirds(String position) {
        return promptText + ", rule of thirds composition, subject on " + position + " intersection";
    }
}
