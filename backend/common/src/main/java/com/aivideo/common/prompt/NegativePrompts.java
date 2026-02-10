package com.aivideo.common.prompt;

/**
 * 네거티브 프롬프트 상수
 */
public final class NegativePrompts {

    private NegativePrompts() {}

    /**
     * 기본 네거티브 프롬프트 (모든 생성에 적용)
     */
    public static final String STANDARD =
            "blurry, distorted, watermark, text, subtitles, logo, " +
            "low quality, pixelated, grainy noise, compression artifacts, " +
            "disfigured, bad anatomy, extra limbs, missing limbs, " +
            "floating limbs, disconnected limbs, mutation, mutated, " +
            "ugly, duplicate, morbid, poorly drawn face, deformed";

    /**
     * Veo 3.1 전용 (자막 추가 방지 강화)
     */
    public static final String VEO_SPECIFIC =
            STANDARD + ", " +
            "on-screen text, captions, title cards, name labels, " +
            "text overlay, speech bubbles, any text whatsoever";

    /**
     * 인물/캐릭터 관련
     */
    public static final String CHARACTER =
            "bad anatomy, wrong anatomy, extra limb, missing limb, " +
            "floating limbs, disconnected limbs, mutation, mutated hands, " +
            "poorly drawn hands, malformed hands, extra fingers, " +
            "fewer fingers, fused fingers, too many fingers, " +
            "long neck, bad proportions, cropped head, out of frame";

    /**
     * 립싱크 관련
     */
    public static final String LIPSYNC =
            "lip sync errors, mouth deformation, audio desync, " +
            "unnatural jaw movement, frozen mouth, robotic speech, " +
            "mismatched lip movements";

    /**
     * 소셜 미디어 (9:16) 관련
     */
    public static final String SOCIAL_VERTICAL =
            STANDARD + ", " +
            "horizontal framing, letterboxing, pillarboxing, " +
            "important content in bottom 35% (UI overlap zone), " +
            "important content in top 14% (status bar zone)";

    /**
     * 일관성 관련
     */
    public static final String CONSISTENCY =
            "inconsistent style, changing appearance, " +
            "different character in each frame, " +
            "flickering, temporal inconsistency, " +
            "sudden lighting changes, style drift";

    /**
     * 물리 법칙 관련
     */
    public static final String PHYSICS =
            "floating, clipping through objects, " +
            "unrealistic gravity, impossible physics, " +
            "objects passing through each other, " +
            "unnatural movement speed";

    /**
     * 조명 관련
     */
    public static final String LIGHTING =
            "flat lighting, inconsistent shadows, " +
            "multiple light sources conflicting, " +
            "harsh highlights, blown out exposure, " +
            "too dark, underexposed";

    /**
     * 카테고리 조합 유틸리티
     */
    public static String combine(String... categories) {
        return String.join(", ", categories);
    }

    /**
     * 전체 종합 네거티브 (프리미엄 품질용)
     */
    public static String comprehensive() {
        return combine(VEO_SPECIFIC, CHARACTER, LIPSYNC, CONSISTENCY, PHYSICS, LIGHTING);
    }
}
