package com.aivideo.common.prompt;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 캐릭터 신원 블록 (Identity Block)
 * 씬 간 캐릭터 일관성을 위한 상세 정의
 */
@Data
@Builder
public class CharacterIdentityBlock {

    /** 캐릭터 이름/식별자 */
    private String name;

    // ===== PHYSICAL TRAITS (절대 변경 불가) =====
    private PhysicalTraits physicalTraits;

    // ===== SIGNATURE LOOK (기본 복장) =====
    private SignatureLook signatureLook;

    // ===== DISTINGUISHING FEATURES =====
    private List<String> distinguishingFeatures;

    @Data
    @Builder
    public static class PhysicalTraits {
        private String age;
        private String ethnicity;
        private String height;
        private String build;
        private String faceShape;
        private String eyes;
        private String nose;
        private String lips;
        private String skin;
        private String hair;
    }

    @Data
    @Builder
    public static class SignatureLook {
        private String topwear;
        private String bottomwear;
        private String footwear;
        private List<String> accessories;
    }

    /**
     * 프롬프트에 포함할 캐릭터 블록 문자열 생성
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[CHARACTER: ").append(name).append("]\n\n");

        // Physical Traits
        if (physicalTraits != null) {
            sb.append("[PHYSICAL TRAITS]\n");
            PhysicalTraits pt = physicalTraits;
            if (pt.getAge() != null) sb.append("- Age: ").append(pt.getAge()).append("\n");
            if (pt.getEthnicity() != null) sb.append("- Ethnicity: ").append(pt.getEthnicity()).append("\n");
            if (pt.getHeight() != null) sb.append("- Height/Build: ").append(pt.getHeight());
            if (pt.getBuild() != null) sb.append(", ").append(pt.getBuild());
            sb.append("\n");
            if (pt.getFaceShape() != null) sb.append("- Face: ").append(pt.getFaceShape()).append("\n");
            if (pt.getEyes() != null) sb.append("- Eyes: ").append(pt.getEyes()).append("\n");
            if (pt.getNose() != null) sb.append("- Nose: ").append(pt.getNose()).append("\n");
            if (pt.getLips() != null) sb.append("- Lips: ").append(pt.getLips()).append("\n");
            if (pt.getSkin() != null) sb.append("- Skin: ").append(pt.getSkin()).append("\n");
            if (pt.getHair() != null) sb.append("- Hair: ").append(pt.getHair()).append("\n");
            sb.append("\n");
        }

        // Signature Look
        if (signatureLook != null) {
            sb.append("[SIGNATURE LOOK]\n");
            SignatureLook sl = signatureLook;
            if (sl.getTopwear() != null) sb.append("- ").append(sl.getTopwear()).append("\n");
            if (sl.getBottomwear() != null) sb.append("- ").append(sl.getBottomwear()).append("\n");
            if (sl.getFootwear() != null) sb.append("- ").append(sl.getFootwear()).append("\n");
            if (sl.getAccessories() != null && !sl.getAccessories().isEmpty()) {
                sl.getAccessories().forEach(a -> sb.append("- ").append(a).append("\n"));
            }
            sb.append("\n");
        }

        // Distinguishing Features
        if (distinguishingFeatures != null && !distinguishingFeatures.isEmpty()) {
            sb.append("[DISTINGUISHING FEATURES]\n");
            distinguishingFeatures.forEach(f -> sb.append("- ").append(f).append("\n"));
        }

        return sb.toString();
    }

    /**
     * 간단한 한 줄 설명 (이미지 프롬프트용)
     */
    public String toShortDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);

        if (physicalTraits != null) {
            PhysicalTraits pt = physicalTraits;
            if (pt.getAge() != null) sb.append(", ").append(pt.getAge());
            if (pt.getEthnicity() != null) sb.append(" ").append(pt.getEthnicity());
            if (pt.getBuild() != null) sb.append(", ").append(pt.getBuild());
            if (pt.getHair() != null) sb.append(", ").append(pt.getHair());
            if (pt.getEyes() != null) sb.append(", ").append(pt.getEyes());
        }

        return sb.toString();
    }
}
