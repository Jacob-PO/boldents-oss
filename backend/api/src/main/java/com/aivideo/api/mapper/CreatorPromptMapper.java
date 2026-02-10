package com.aivideo.api.mapper;

import com.aivideo.api.entity.CreatorPrompt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * CreatorPrompt Mapper Interface (Wide Table 구조)
 * v2.9.121: Genre → Creator 리네이밍
 * v2.9.122: EAV → Wide Table 구조 변경
 * v2.9.129: scenarioSystem + scenarioUserTemplate → scenarioPrompt 통합
 * v2.9.130: imageSafetyFallback + safetyFilterInstruction → safetyPrompt 통합
 * v2.9.132: characterPrompt → 7개 개별 컬럼 복원
 * v2.9.133: appearancePromptBlock 제거 (characterBlockFull에 통합) → 6개 캐릭터 컬럼
 */
@Mapper
public interface CreatorPromptMapper {

    /**
     * 크리에이터 ID로 프롬프트 조회 (단일 row에 모든 프롬프트 타입 포함)
     */
    Optional<CreatorPrompt> findByCreatorId(Long creatorId);

    /**
     * 프롬프트 생성/업데이트 (UPSERT)
     */
    void upsert(CreatorPrompt prompt);

    /**
     * 특정 프롬프트 타입 업데이트
     * v2.9.129: updateScenarioSystem + updateScenarioUserTemplate → updateScenarioPrompt 통합
     * v2.9.130: updateImageSafetyFallback + updateSafetyFilterInstruction → updateSafetyPrompt 통합
     * v2.9.132: 7개 캐릭터 필드 개별 업데이트 복원
     */
    void updateScenarioPrompt(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateImageStyle(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateImageNegative(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateOpeningVideo(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateTtsInstruction(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateSafetyPrompt(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateThumbnail(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateNarrationExpand(@Param("creatorId") Long creatorId, @Param("content") String content);
    // v2.9.132: 7개 캐릭터 필드 개별 업데이트 복원
    // v2.9.133: appearancePromptBlock 제거 → 6개 캐릭터 필드
    void updateIdentityAnchor(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateCharacterBlockFull(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateNegativePromptsCharacter(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateStyleLock(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateVideoPromptBlock(@Param("creatorId") Long creatorId, @Param("content") String content);
    void updateThumbnailStylePrompt(@Param("creatorId") Long creatorId, @Param("content") String content);
}
