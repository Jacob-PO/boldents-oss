package com.aivideo.api.mapper;

import com.aivideo.api.entity.Video;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface VideoMapper {

    void insert(Video video);

    Optional<Video> findById(Long videoId);

    List<Video> findByUserNo(@Param("userNo") Long userNo, @Param("offset") int offset, @Param("limit") int limit);

    List<Video> findByConversationId(Long conversationId);

    /**
     * v2.9.48: SELECT FOR UPDATE (Race condition 방지 - 썸네일 중복 생성 차단)
     */
    List<Video> findByConversationIdForUpdate(Long conversationId);

    void updateStatus(@Param("videoId") Long videoId, @Param("status") String status);

    void updateProgress(@Param("videoId") Long videoId, @Param("progress") int progress, @Param("currentStep") String currentStep);

    void updateVideoUrl(@Param("videoId") Long videoId, @Param("videoUrl") String videoUrl, @Param("thumbnailUrl") String thumbnailUrl);

    void updateAsCompleted(@Param("videoId") Long videoId, @Param("videoUrl") String videoUrl, @Param("thumbnailUrl") String thumbnailUrl);

    void updateAsFailed(@Param("videoId") Long videoId, @Param("errorMessage") String errorMessage);

    void delete(Long videoId);

    /**
     * v2.9.13: 여러 video 일괄 삭제 (N+1 쿼리 방지)
     */
    void deleteByIdsBatch(@Param("videoIds") List<Long> videoIds);

    int countByUserNo(Long userNo);

    /**
     * v2.9.134: 크리에이터 ID 업데이트 (genreId → creatorId)
     */
    void updateCreatorId(@Param("videoId") Long videoId, @Param("creatorId") Long creatorId);

    /**
     * v2.9.25: 영상 포맷 업데이트
     */
    void updateFormatId(@Param("videoId") Long videoId, @Param("formatId") Long formatId);

    /**
     * v2.9.38: presigned URL 만료 시간 업데이트
     */
    void updatePresignedUrlExpiresAt(@Param("videoId") Long videoId, @Param("expiresAt") java.time.LocalDateTime expiresAt);

    /**
     * v2.9.43: 썸네일 URL만 업데이트 (최종 영상에 포함하기 위함)
     */
    void updateThumbnailUrl(@Param("videoId") Long videoId, @Param("thumbnailUrl") String thumbnailUrl);

    /**
     * v2.9.172: 썸네일 URL이 NULL일 때만 업데이트 (레이스 컨디션 방지)
     * @return 업데이트된 행 수 (0이면 이미 썸네일이 존재)
     */
    int updateThumbnailUrlIfNull(@Param("videoId") Long videoId, @Param("thumbnailUrl") String thumbnailUrl);
}
