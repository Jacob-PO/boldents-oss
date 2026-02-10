package com.aivideo.api.mapper;

import com.aivideo.api.entity.VideoSubtitle;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * VideoSubtitle Mapper Interface (v2.9.161)
 * 자막 템플릿 DB 조회
 */
@Mapper
public interface VideoSubtitleMapper {

    /**
     * 자막 템플릿 ID로 조회
     */
    Optional<VideoSubtitle> findById(Long videoSubtitleId);

    /**
     * 자막 템플릿 코드로 조회
     */
    Optional<VideoSubtitle> findByCode(String subtitleCode);

    /**
     * 활성화된 모든 자막 템플릿 조회 (표시 순서대로)
     */
    List<VideoSubtitle> findAllActive();

    /**
     * 기본 자막 템플릿 조회
     */
    Optional<VideoSubtitle> findDefault();
}
