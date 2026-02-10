package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상 자막 템플릿 엔티티 (v2.9.161)
 * ASS 자막 스타일 파라미터를 DB에서 관리
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoSubtitle {
    private Long videoSubtitleId;
    private String subtitleCode;        // DEFAULT_OUTLINE, BACKGROUND_BOX
    private String subtitleName;        // 기본 자막, 배경 자막 (한글)
    private String subtitleNameEn;      // Default Outline, Background Box (영문)
    private String description;

    // 폰트 설정
    private String fontName;            // SUIT-Bold
    private Integer fontSize;           // 72 (가로 기본)
    private Integer fontSizeVertical;   // 100 (세로 기본)
    private Integer fontSizeEmotion;    // 80 (감정 자막 가로)
    private Integer fontSizeEmotionVertical; // 100 (감정 자막 세로)
    private Boolean bold;               // true
    private Integer spacing;            // 2

    // 색상 (ASS &HAABBGGRR 형식)
    private String primaryColour;       // &H00FFFFFF (흰색)
    private String secondaryColour;     // &H000000FF
    private String outlineColour;       // &H00000000 (검정)
    private String backColour;          // &HB0000000

    // 스타일
    private Integer borderStyle;        // 1=외곽선+그림자, 3=불투명박스
    private Integer outline;            // 4
    private Integer shadow;             // 2

    // 위치
    private Integer alignment;          // 2 (하단 중앙)
    private Integer marginL;            // 20
    private Integer marginR;            // 20
    private Integer marginV;            // 40 (가로)
    private Integer marginVVertical;    // 300 (세로)

    // 감정 자막 색상
    private String emotionPrimaryColour; // &H0000FFFF (노란색)

    // 관리
    private Boolean isDefault;
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
