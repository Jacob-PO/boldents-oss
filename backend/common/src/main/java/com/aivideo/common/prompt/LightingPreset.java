package com.aivideo.common.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 조명 프리셋
 */
@Getter
@RequiredArgsConstructor
public enum LightingPreset {

    // 자연광
    GOLDEN_HOUR(
            "Golden Hour",
            "warm, romantic",
            "golden hour, warm sunlight from low horizon angle (3000K), soft shadows, warm amber tones"
    ),
    BLUE_HOUR(
            "Blue Hour",
            "mysterious, ethereal",
            "blue hour, twilight, ethereal glow, soft diffused light, cool blue tones (6000K)"
    ),
    MIDDAY_SUN(
            "Midday Sun",
            "bright, harsh",
            "midday harsh sunlight, strong shadows, high contrast, clear bright atmosphere"
    ),
    OVERCAST(
            "Overcast",
            "soft, even",
            "overcast sky, soft diffused daylight, minimal shadows, even illumination"
    ),
    MORNING_LIGHT(
            "Morning Light",
            "fresh, hopeful",
            "early morning light, soft warm glow, gentle shadows, dewey fresh atmosphere"
    ),

    // 스튜디오/인공광
    HIGH_KEY(
            "High Key",
            "bright, hopeful, clean",
            "high key lighting, bright, minimal shadows, clean and optimistic mood"
    ),
    LOW_KEY(
            "Low Key",
            "dramatic, noir, mysterious",
            "low key lighting, dramatic deep shadows, chiaroscuro effect, noir atmosphere"
    ),
    REMBRANDT(
            "Rembrandt",
            "classic, painterly",
            "Rembrandt lighting, triangle of light on cheek, classic portrait setup, warm key light 45 degrees"
    ),
    BUTTERFLY(
            "Butterfly",
            "glamorous, beauty",
            "butterfly lighting, key light directly above camera, glamorous beauty lighting, soft shadows under nose and chin"
    ),
    RIM_LIGHT(
            "Rim Light",
            "dramatic, separation",
            "strong rim lighting from behind, glowing outline separating subject from background, volumetric light"
    ),

    // 특수 조명
    NEON_NOIR(
            "Neon Noir",
            "cyberpunk, urban",
            "neon lighting, cyberpunk aesthetic, pink and blue color contrast, wet reflective surfaces"
    ),
    CANDLELIGHT(
            "Candlelight",
            "intimate, warm",
            "candlelight only, warm flickering light (1800K), intimate atmosphere, dancing shadows"
    ),
    MOONLIGHT(
            "Moonlight",
            "cold, mysterious",
            "moonlight, cold blue illumination (8000K), deep shadows, mysterious night atmosphere"
    ),
    DRAMATIC_CONTRAST(
            "Dramatic Contrast",
            "intense, cinematic",
            "hard key light at 45 degrees, strong contrast, deep blacks, cinematic drama, sharp shadow edges"
    ),
    SOFT_DIFFUSED(
            "Soft Diffused",
            "gentle, flattering",
            "soft diffused light, beauty dish quality, gentle gradients, flattering skin tones, minimal texture"
    );

    private final String name;
    private final String mood;
    private final String promptText;

    /**
     * 색온도 정보와 함께 프롬프트 생성
     */
    public String withColorTemp(int kelvin) {
        return promptText + ", " + kelvin + "K color temperature";
    }

    /**
     * 방향 정보와 함께 프롬프트 생성
     */
    public String withDirection(String direction) {
        return promptText + ", light from " + direction;
    }
}
