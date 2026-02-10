package com.aivideo.api.mapper;

import com.aivideo.api.entity.CreatorPromptLength;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 크리에이터별 길이 설정 매퍼
 *
 * v2.9.155: 길이 관련 설정을 별도 테이블에서 중앙 관리
 */
@Mapper
public interface CreatorPromptLengthMapper {

    /**
     * 크리에이터 ID로 길이 설정 조회
     */
    CreatorPromptLength findByCreatorId(@Param("creatorId") Long creatorId);

    /**
     * 길이 설정 UPSERT
     */
    void upsert(CreatorPromptLength length);

    /**
     * 개별 필드 업데이트 메서드들
     */
    void updateThumbnailHookLength(@Param("creatorId") Long creatorId, @Param("length") Integer length);
    void updateYoutubeTitleLength(@Param("creatorId") Long creatorId, @Param("minLength") Integer minLength, @Param("maxLength") Integer maxLength);
    void updateYoutubeDescriptionLength(@Param("creatorId") Long creatorId, @Param("minLength") Integer minLength, @Param("maxLength") Integer maxLength);
    void updateOpeningNarrationLength(@Param("creatorId") Long creatorId, @Param("length") Integer length);
    void updateSlideNarrationLength(@Param("creatorId") Long creatorId, @Param("length") Integer length);
    void updateNarrationExpandLength(@Param("creatorId") Long creatorId, @Param("length") Integer length);
}
