package com.aivideo.api.mapper;

import com.aivideo.api.entity.ApiBatchSettings;
import com.aivideo.api.entity.ApiRateLimit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * v2.9.13: API Rate Limit 매퍼
 */
@Mapper
public interface ApiRateLimitMapper {

    // ========== Rate Limit 조회 ==========

    /**
     * 모델명과 티어로 Rate Limit 조회
     */
    Optional<ApiRateLimit> findByModelAndTier(
            @Param("modelName") String modelName,
            @Param("tier") String tier
    );

    /**
     * API 타입과 모델명으로 Rate Limit 조회 (기본 티어: TIER_1)
     */
    Optional<ApiRateLimit> findByApiTypeAndModel(
            @Param("apiType") String apiType,
            @Param("modelName") String modelName
    );

    /**
     * API 타입으로 모든 Rate Limit 조회
     */
    List<ApiRateLimit> findByApiType(@Param("apiType") String apiType);

    /**
     * 모든 활성 Rate Limit 조회
     */
    List<ApiRateLimit> findAllActive();

    // ========== Batch Settings 조회 ==========

    /**
     * API 타입으로 배치 설정 조회
     */
    Optional<ApiBatchSettings> findBatchSettingsByApiType(@Param("apiType") String apiType);

    /**
     * 모든 활성 배치 설정 조회
     */
    List<ApiBatchSettings> findAllBatchSettings();

    // ========== Rate Limit 수정 (런타임 조정용) ==========

    /**
     * 딜레이 설정 업데이트
     */
    void updateDelaySettings(
            @Param("limitId") Long limitId,
            @Param("minDelayMs") Integer minDelayMs,
            @Param("maxDelayMs") Integer maxDelayMs,
            @Param("initialDelayMs") Integer initialDelayMs
    );

    /**
     * RPM 업데이트
     */
    void updateRpm(
            @Param("limitId") Long limitId,
            @Param("rpm") Integer rpm
    );
}
