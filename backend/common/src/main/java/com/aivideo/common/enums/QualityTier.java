package com.aivideo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 영상 품질 티어
 * STANDARD: 저비용, 빠른 생성 (Veo 2, Imagen 3 Fast, Google TTS)
 * PREMIUM: 고품질, 고비용 (Veo 3.1, Imagen 3 + DALL-E 3, ElevenLabs)
 */
@Getter
@RequiredArgsConstructor
public enum QualityTier {

    STANDARD("standard", "일반", 1.50, 3.00),
    PREMIUM("premium", "프리미엄", 15.00, 25.00);

    private final String code;
    private final String displayName;
    private final double estimatedCost;  // 예상 원가 (USD)
    private final double sellingPrice;   // 판매가 (USD)
}
