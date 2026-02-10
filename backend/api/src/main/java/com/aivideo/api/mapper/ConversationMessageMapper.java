package com.aivideo.api.mapper;

import com.aivideo.api.entity.ConversationMessage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * ConversationMessage Mapper Interface
 */
@Mapper
public interface ConversationMessageMapper {

    /**
     * 메시지 추가
     */
    void insert(ConversationMessage message);

    /**
     * 대화 ID로 메시지 목록 조회 (시간순 정렬)
     */
    List<ConversationMessage> findByConversationId(Long conversationId);

    /**
     * 대화의 메시지 수 조회
     */
    int countByConversationId(Long conversationId);

    /**
     * 대화 ID로 모든 메시지 삭제
     */
    void deleteByConversationId(Long conversationId);
}
