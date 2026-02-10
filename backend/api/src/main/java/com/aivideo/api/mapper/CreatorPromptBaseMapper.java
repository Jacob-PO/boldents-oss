package com.aivideo.api.mapper;

import com.aivideo.api.entity.CreatorPromptBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 크리에이터 프롬프트 Base 템플릿 매퍼
 *
 * v2.9.152: 2단계 프롬프트 아키텍처 - Layer 1 (Base 템플릿)
 * - 백엔드 전용 XML 템플릿 조회
 * - 프롬프트 타입별 Base 템플릿 관리
 */
@Mapper
public interface CreatorPromptBaseMapper {

    /**
     * 프롬프트 타입으로 Base 템플릿 조회
     * @param promptType 프롬프트 타입 (SCENARIO, IMAGE_STYLE 등)
     * @return Base 템플릿 (활성화된 것만)
     */
    Optional<CreatorPromptBase> findByPromptType(@Param("promptType") String promptType);

    /**
     * 모든 활성화된 Base 템플릿 조회
     * @return 활성화된 Base 템플릿 목록
     */
    List<CreatorPromptBase> findAllActive();

    /**
     * Base ID로 조회
     * @param baseId Base 템플릿 ID
     * @return Base 템플릿
     */
    Optional<CreatorPromptBase> findById(@Param("baseId") Long baseId);
}
