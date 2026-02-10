package com.aivideo.api.mapper;

import com.aivideo.api.entity.Scene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SceneMapper {

    void insert(Scene scene);

    void insertBatch(@Param("scenes") List<Scene> scenes);

    Optional<Scene> findById(Long sceneId);

    List<Scene> findByVideoId(Long videoId);

    List<Scene> findByVideoIdOrderByOrder(Long videoId);

    void updateMediaUrl(@Param("sceneId") Long sceneId, @Param("mediaUrl") String mediaUrl, @Param("mediaStatus") String mediaStatus);

    void updateMediaStatus(@Param("sceneId") Long sceneId, @Param("mediaStatus") String mediaStatus);

    void updateDuration(@Param("sceneId") Long sceneId, @Param("duration") Integer duration);

    void delete(Long sceneId);

    void deleteByVideoId(Long videoId);

    /**
     * v2.9.13: 여러 video의 scenes 일괄 삭제 (N+1 쿼리 방지)
     */
    void deleteByVideoIdsBatch(@Param("videoIds") List<Long> videoIds);

    int countByVideoId(Long videoId);

    // ========== v2.4.0 추가 메서드 - 개별 씬 영상 생성 지원 ==========

    /**
     * 씬의 이미지 URL 업데이트
     */
    void updateImageUrl(@Param("sceneId") Long sceneId, @Param("imageUrl") String imageUrl);

    /**
     * 씬의 오디오 URL 업데이트
     */
    void updateAudioUrl(@Param("sceneId") Long sceneId, @Param("audioUrl") String audioUrl);

    /**
     * 씬의 자막 URL 업데이트
     */
    void updateSubtitleUrl(@Param("sceneId") Long sceneId, @Param("subtitleUrl") String subtitleUrl);

    /**
     * 씬의 개별 영상 URL 업데이트
     */
    void updateSceneVideoUrl(@Param("sceneId") Long sceneId, @Param("sceneVideoUrl") String sceneVideoUrl);

    /**
     * 씬 상태 업데이트 (sceneStatus)
     */
    void updateSceneStatus(@Param("sceneId") Long sceneId, @Param("sceneStatus") String sceneStatus);

    /**
     * 씬 전체 미디어 정보 업데이트 (이미지, 오디오, 자막, 개별영상, 상태)
     */
    void updateSceneMedia(
            @Param("sceneId") Long sceneId,
            @Param("imageUrl") String imageUrl,
            @Param("audioUrl") String audioUrl,
            @Param("subtitleUrl") String subtitleUrl,
            @Param("sceneVideoUrl") String sceneVideoUrl,
            @Param("sceneStatus") String sceneStatus
    );

    /**
     * 씬 재생성 요청 (userFeedback 저장, regenerateCount 증가)
     */
    void requestRegenerate(@Param("sceneId") Long sceneId, @Param("userFeedback") String userFeedback);

    /**
     * 씬 프롬프트 업데이트 (재생성 시 프롬프트 수정)
     */
    void updatePrompt(@Param("sceneId") Long sceneId, @Param("prompt") String prompt);

    /**
     * 완료된 씬 수 조회
     */
    int countCompletedByVideoId(Long videoId);

    // ========== v2.5.0 추가 메서드 - 나레이션 편집 지원 ==========

    /**
     * 씬 나레이션 업데이트 (사용자 편집)
     */
    void updateNarration(@Param("sceneId") Long sceneId, @Param("narration") String narration);

    // ========== v2.6.0 추가 메서드 - 부분 실패 복구 ==========

    /**
     * 실패한 씬 목록 조회
     */
    List<Scene> findFailedByVideoId(Long videoId);

    /**
     * 특정 상태의 씬 목록 조회
     */
    List<Scene> findByVideoIdAndStatus(@Param("videoId") Long videoId, @Param("sceneStatus") String sceneStatus);

    /**
     * 실패 씬 에러 메시지 업데이트
     */
    void updateErrorMessage(@Param("sceneId") Long sceneId, @Param("errorMessage") String errorMessage);

    /**
     * 씬 재시도 카운트 증가
     */
    void incrementRetryCount(@Param("sceneId") Long sceneId);

    /**
     * 미디어 생성이 필요한 씬 (PENDING 또는 FAILED 상태)
     */
    List<Scene> findPendingOrFailedByVideoId(Long videoId);
}
