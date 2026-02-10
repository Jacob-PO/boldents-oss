package com.aivideo.common.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 7가지 검증된 훅 공식
 * 첫 3초 안에 시선을 사로잡는 유형
 */
@Getter
@RequiredArgsConstructor
public enum HookType {

    CONTROVERSY(
            "Controversy",
            "통념에 반하는 진술",
            "Scene opens with bold statement defying common belief, immediate visual that challenges expectations",
            "Extreme close-up on shocking element, quick pull-back reveal, sharp audio sting at 0.3s"
    ),

    VALUE_PROMISE(
            "Value Promise",
            "즉각적 가치 제시",
            "Character reveals valuable secret with dramatic gesture, immediate benefit shown",
            "Medium shot, character turning toward camera, dramatic lighting shift, confident voice"
    ),

    CURIOSITY_GAP(
            "Curiosity Gap",
            "호기심 유발",
            "Mysterious object slowly revealed from shadow, partial reveal creating intrigue",
            "Slow dolly in, shallow depth of field, silhouette gradually illuminated, suspenseful audio"
    ),

    IN_MEDIAS_RES(
            "In Medias Res",
            "액션 중간 시작",
            "Mid-action scene, protagonist in dynamic motion, no setup needed",
            "Fast tracking shot, wide angle, high energy camera movement, impact sounds"
    ),

    IDENTIFICATION(
            "Identification",
            "공감대 형성",
            "Relatable everyday situation, character showing universal frustration or joy",
            "Medium close-up, naturalistic lighting, character making eye contact, ambient sounds"
    ),

    SOCIAL_PROOF(
            "Social Proof",
            "사회적 증거",
            "Crowd reacting with amazement, multiple people validating the subject",
            "Wide establishing shot of crowd, rack focus to subject, collective gasp/cheer audio"
    ),

    PATTERN_BREAK(
            "Pattern Break",
            "예상 파괴",
            "Normal scene suddenly disrupted by unexpected element, subverted expectation",
            "Static shot establishing normalcy, sudden movement or element enters frame, jarring sound"
    );

    private final String name;
    private final String description;
    private final String sceneDescription;
    private final String cameraAudioGuide;

    /**
     * 훅 타입에 맞는 프롬프트 세그먼트 생성
     */
    public String toPromptSegment(String specificContent) {
        return String.format(
                "[HOOK - 0-3초]\n" +
                        "Hook Type: %s\n" +
                        "Scene: %s\n" +
                        "Technical: %s\n" +
                        "Content: %s\n" +
                        "Constraints: No subtitles in first 3 seconds, visual storytelling only, " +
                        "immediate full-volume audio at 0.3 seconds, maximum impact with minimal setup.",
                name, sceneDescription, cameraAudioGuide, specificContent
        );
    }
}
