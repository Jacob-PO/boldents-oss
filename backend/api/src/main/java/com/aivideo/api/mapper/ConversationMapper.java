package com.aivideo.api.mapper;

import com.aivideo.api.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * Conversation Mapper Interface
 */
@Mapper
public interface ConversationMapper {

    /**
     * 대화 세션 생성
     */
    void insert(Conversation conversation);

    /**
     * 대화 ID로 조회
     */
    Optional<Conversation> findById(Long conversationId);

    /**
     * 사용자 번호와 상태로 대화 목록 조회
     */
    List<Conversation> findByUserNoAndStatus(@Param("userNo") Long userNo, @Param("status") String status);

    /**
     * 사용자의 모든 대화 목록 조회 (상태 무관)
     */
    List<Conversation> findAllByUserNo(@Param("userNo") Long userNo);

    /**
     * 대화 상태 업데이트
     */
    void updateStatus(@Param("conversationId") Long conversationId, @Param("status") String status);

    /**
     * 현재 단계 업데이트
     */
    void updateCurrentStep(@Param("conversationId") Long conversationId, @Param("currentStep") String currentStep);

    /**
     * v2.9.94: 조건부 현재 단계 업데이트 (레이스 컨디션 방지)
     * VIDEO_DONE, VIDEO_GENERATING, VIDEO_FAILED 상태가 아닐 때만 업데이트
     * @return 업데이트된 행 수 (0이면 업데이트 안됨 - 이미 영상 관련 상태)
     */
    int updateCurrentStepIfNotVideoState(@Param("conversationId") Long conversationId, @Param("currentStep") String currentStep);

    /**
     * 질문 횟수 증가
     */
    void incrementQuestionCount(Long conversationId);

    /**
     * 총 메시지 수 증가
     */
    void incrementTotalMessages(Long conversationId);

    /**
     * 초기 프롬프트 업데이트
     */
    void updateInitialPrompt(@Param("conversationId") Long conversationId, @Param("initialPrompt") String initialPrompt);

    /**
     * 영상 길이 업데이트
     */
    void updateVideoDuration(@Param("conversationId") Long conversationId, @Param("videoDuration") Integer videoDuration);

    /**
     * 영상 ID 업데이트
     */
    void updateVideoId(@Param("conversationId") Long conversationId, @Param("videoId") Long videoId);

    /**
     * 크리에이터 ID 업데이트 (v2.9.134: genreId → creatorId)
     */
    void updateCreatorId(@Param("conversationId") Long conversationId, @Param("creatorId") Long creatorId);

    /**
     * v2.9.84: 참조 이미지 정보 업데이트
     */
    void updateReferenceImage(
            @Param("conversationId") Long conversationId,
            @Param("referenceImageUrl") String referenceImageUrl,
            @Param("referenceImageAnalysis") String referenceImageAnalysis
    );

    /**
     * 대화 삭제
     */
    void delete(Long conversationId);

    /**
     * v2.9.30: 사용자의 진행 중인 대화 조회 (콘텐츠 생성 중인 대화)
     * Video가 생성되었지만 아직 완료되지 않은 대화를 조회
     */
    Optional<Conversation> findInProgressConversationByUserNo(@Param("userNo") Long userNo);

    /**
     * v2.9.30: 사용자의 진행 중인 대화 조회 (현재 채팅 제외)
     * 다른 채팅에서 콘텐츠 생성 중인지 확인
     */
    Optional<Conversation> findInProgressConversationExcludingCurrent(
            @Param("userNo") Long userNo,
            @Param("excludeChatId") Long excludeChatId
    );
}
