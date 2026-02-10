package com.aivideo.api.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대화 메시지 엔티티
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    private Long messageId;
    private Long conversationId;

    // 메시지 내용
    private String role;  // "user", "assistant"
    private String content;

    // 메시지 유형
    private String messageType;  // INITIAL_PROMPT, USER_RESPONSE, AI_QUESTION, AI_READY_SIGNAL

    // Gemini API 관련
    private Integer tokensUsed;

    // 메타데이터 (JSON)
    private String metadata;

    // 타임스탬프
    private LocalDateTime createdAt;
}
