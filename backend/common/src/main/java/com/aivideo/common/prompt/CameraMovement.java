package com.aivideo.common.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 카메라 움직임 프리셋
 */
@Getter
@RequiredArgsConstructor
public enum CameraMovement {

    // 기본 움직임
    STATIC("Static", "static shot, fixed camera, tripod stability"),
    PAN_LEFT("Pan Left", "slow pan left revealing the scene"),
    PAN_RIGHT("Pan Right", "slow pan right revealing the scene"),
    TILT_UP("Tilt Up", "tilt up from bottom to top"),
    TILT_DOWN("Tilt Down", "tilt down from top to bottom"),
    DOLLY_IN("Dolly In", "slow dolly in toward subject"),
    DOLLY_OUT("Dolly Out", "slow dolly out from subject"),
    TRACKING("Tracking", "tracking shot following the subject"),
    CRANE_UP("Crane Up", "crane up revealing the scene below"),
    CRANE_DOWN("Crane Down", "crane down descending to subject level"),
    ORBIT_CW("Orbit Clockwise", "120° orbit clockwise around subject"),
    ORBIT_CCW("Orbit Counter-Clockwise", "120° orbit counter-clockwise around subject"),

    // 고급 움직임
    VERTIGO("Vertigo Effect", "vertigo effect, dolly zoom, camera moves backward while zooming in, background expands while subject remains same size"),
    FPV_DRONE("FPV Drone", "FPV drone shot, low altitude flight, aggressive banking turns, first-person perspective, slight motion blur"),
    SNORRICAM("Snorricam", "snorricam shot, camera fixed to subject's body, background moves while subject stays centered"),
    STEADICAM_FOLLOW("Steadicam Follow", "smooth steadicam following subject through environment, floating feel"),
    WHIP_PAN("Whip Pan", "fast whip pan transition, motion blur during pan"),
    PUSH_IN_DRAMATIC("Dramatic Push In", "slow dramatic push in building tension, narrowing focus on subject"),
    PULL_BACK_REVEAL("Pull Back Reveal", "slow pull back revealing the full scope of the scene");

    private final String name;
    private final String promptText;

    /**
     * 속도와 함께 프롬프트 생성
     */
    public String withSpeed(String speed) {
        return speed + " " + promptText;
    }

    /**
     * 렌즈 정보와 함께 프롬프트 생성
     */
    public String withLens(String lens) {
        return promptText + ", " + lens + " lens";
    }

    /**
     * 완전한 카메라 프롬프트 생성
     */
    public String toFullPrompt(String lens, String aperture, String speed) {
        return String.format("%s %s, %s lens at f/%s", speed, promptText, lens, aperture);
    }
}
