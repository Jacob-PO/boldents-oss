package com.aivideo.api.service;

import com.aivideo.api.mapper.SceneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * v2.9.21: 씬 업데이트 전용 서비스
 *
 * 목적: DB Lock Timeout 방지
 * 원인: TTS 생성 등 긴 작업 중 트랜잭션이 오래 유지되어 Lock 충돌 발생
 * 해결: 모든 씬 업데이트를 독립 트랜잭션(REQUIRES_NEW)으로 즉시 커밋
 *
 * 사용법: ContentService에서 sceneMapper 직접 호출 대신 이 서비스 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneUpdateService {

    private final SceneMapper sceneMapper;

    /**
     * 씬 상태 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long sceneId, String status) {
        sceneMapper.updateSceneStatus(sceneId, status);
        log.info("[SceneUpdate] sceneId={} status={} (committed)", sceneId, status);
    }

    /**
     * 씬 이미지 URL 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateImageUrl(Long sceneId, String imageUrl) {
        sceneMapper.updateImageUrl(sceneId, imageUrl);
        log.info("[SceneUpdate] sceneId={} imageUrl updated (committed)", sceneId);
    }

    /**
     * 씬 오디오 URL 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAudioUrl(Long sceneId, String audioUrl) {
        sceneMapper.updateAudioUrl(sceneId, audioUrl);
        log.info("[SceneUpdate] sceneId={} audioUrl updated (committed)", sceneId);
    }

    /**
     * 씬 자막 URL 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSubtitleUrl(Long sceneId, String subtitleUrl) {
        sceneMapper.updateSubtitleUrl(sceneId, subtitleUrl);
        log.info("[SceneUpdate] sceneId={} subtitleUrl updated (committed)", sceneId);
    }

    /**
     * 씬 영상 URL 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSceneVideoUrl(Long sceneId, String sceneVideoUrl) {
        sceneMapper.updateSceneVideoUrl(sceneId, sceneVideoUrl);
        log.info("[SceneUpdate] sceneId={} sceneVideoUrl updated (committed)", sceneId);
    }

    /**
     * 씬 Duration 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateDuration(Long sceneId, Integer duration) {
        sceneMapper.updateDuration(sceneId, duration);
        log.info("[SceneUpdate] sceneId={} duration={} (committed)", sceneId, duration);
    }

    /**
     * 씬 에러 메시지 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateErrorMessage(Long sceneId, String errorMessage) {
        sceneMapper.updateErrorMessage(sceneId, errorMessage);
        log.info("[SceneUpdate] sceneId={} errorMessage updated (committed)", sceneId);
    }

    /**
     * 씬 상태 + 에러 메시지 동시 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusWithError(Long sceneId, String status, String errorMessage) {
        sceneMapper.updateSceneStatus(sceneId, status);
        sceneMapper.updateErrorMessage(sceneId, errorMessage);
        log.info("[SceneUpdate] sceneId={} status={} error={} (committed)", sceneId, status, errorMessage);
    }

    /**
     * 씬 재시도 카운트 증가 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementRetryCount(Long sceneId) {
        sceneMapper.incrementRetryCount(sceneId);
        log.info("[SceneUpdate] sceneId={} retryCount incremented (committed)", sceneId);
    }

    /**
     * 씬 프롬프트 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updatePrompt(Long sceneId, String prompt) {
        sceneMapper.updatePrompt(sceneId, prompt);
        log.info("[SceneUpdate] sceneId={} prompt updated (committed)", sceneId);
    }

    /**
     * 씬 나레이션 업데이트 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateNarration(Long sceneId, String narration) {
        sceneMapper.updateNarration(sceneId, narration);
        log.info("[SceneUpdate] sceneId={} narration updated (committed)", sceneId);
    }

    /**
     * 씬 재생성 요청 (즉시 커밋)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestRegenerate(Long sceneId, String userFeedback) {
        sceneMapper.requestRegenerate(sceneId, userFeedback);
        log.info("[SceneUpdate] sceneId={} regenerate requested (committed)", sceneId);
    }
}
