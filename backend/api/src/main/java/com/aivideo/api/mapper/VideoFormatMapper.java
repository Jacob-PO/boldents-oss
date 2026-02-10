package com.aivideo.api.mapper;

import com.aivideo.api.entity.VideoFormat;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * VideoFormat Mapper Interface (v2.9.25)
 */
@Mapper
public interface VideoFormatMapper {

    /**
     * 포맷 ID로 조회
     */
    Optional<VideoFormat> findById(Long formatId);

    /**
     * 포맷 코드로 조회
     */
    Optional<VideoFormat> findByCode(String formatCode);

    /**
     * 활성화된 모든 포맷 조회 (표시 순서대로)
     */
    List<VideoFormat> findAllActive();

    /**
     * 기본 포맷 조회
     */
    Optional<VideoFormat> findDefault();
}
