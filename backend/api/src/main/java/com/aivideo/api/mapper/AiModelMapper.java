package com.aivideo.api.mapper;

import com.aivideo.api.entity.AiModel;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * AI Model Mapper Interface
 * v2.9.120: AI 모델 티어 관리
 */
@Mapper
public interface AiModelMapper {

    /**
     * 티어 ID로 조회
     */
    Optional<AiModel> findById(Long tierId);

    /**
     * 티어 코드로 조회 (BASIC, PRO, ULTRA)
     */
    Optional<AiModel> findByCode(String tierCode);
}
