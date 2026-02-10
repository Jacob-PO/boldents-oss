package com.aivideo.api.service.image;

import lombok.Getter;

import java.util.List;

/**
 * IMAGE_SAFETY 필터에 의해 이미지 생성이 차단되었을 때 발생하는 예외
 * AI를 통한 프롬프트 수정 및 재시도에 필요한 정보를 담고 있음
 */
@Getter
public class SafetyFilterException extends RuntimeException {

    private final String finishReason;           // SAFETY, BLOCKED 등
    private final String originalPrompt;          // 원본 프롬프트
    private final List<SafetyRating> safetyRatings;  // 안전 필터 상세 정보

    public SafetyFilterException(String finishReason, String originalPrompt, List<SafetyRating> safetyRatings) {
        super(buildMessage(finishReason, safetyRatings));
        this.finishReason = finishReason;
        this.originalPrompt = originalPrompt;
        this.safetyRatings = safetyRatings;
    }

    private static String buildMessage(String finishReason, List<SafetyRating> safetyRatings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Image generation blocked by safety filter. Reason: ").append(finishReason);

        if (safetyRatings != null && !safetyRatings.isEmpty()) {
            sb.append(". Blocked categories: ");
            safetyRatings.stream()
                    .filter(SafetyRating::isBlocked)
                    .forEach(r -> sb.append("[").append(r.getCategory())
                            .append(": ").append(r.getProbability()).append("] "));
        }

        return sb.toString();
    }

    /**
     * AI 프롬프트 수정에 사용할 상세 안전 필터 정보 반환
     */
    public String getSafetyIssueDescription() {
        if (safetyRatings == null || safetyRatings.isEmpty()) {
            return "Unknown safety issue - content may be too explicit or violent";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("The following safety categories were triggered:\n");

        for (SafetyRating rating : safetyRatings) {
            if (rating.isBlocked() || "HIGH".equals(rating.getProbability()) || "MEDIUM".equals(rating.getProbability())) {
                sb.append("- ").append(formatCategory(rating.getCategory()))
                        .append(" (severity: ").append(rating.getProbability()).append(")\n");
            }
        }

        return sb.toString();
    }

    private String formatCategory(String category) {
        if (category == null) return "Unknown";
        return category.replace("HARM_CATEGORY_", "")
                .replace("_", " ")
                .toLowerCase();
    }

    @Getter
    public static class SafetyRating {
        private final String category;    // HARM_CATEGORY_SEXUALLY_EXPLICIT 등
        private final String probability; // HIGH, MEDIUM, LOW, NEGLIGIBLE
        private final boolean blocked;

        public SafetyRating(String category, String probability, boolean blocked) {
            this.category = category;
            this.probability = probability;
            this.blocked = blocked;
        }

        public static SafetyRating fromJsonNode(com.fasterxml.jackson.databind.JsonNode node) {
            String category = node.has("category") ? node.get("category").asText() : "UNKNOWN";
            String probability = node.has("probability") ? node.get("probability").asText() : "UNKNOWN";
            boolean blocked = node.has("blocked") && node.get("blocked").asBoolean();
            return new SafetyRating(category, probability, blocked);
        }
    }
}
