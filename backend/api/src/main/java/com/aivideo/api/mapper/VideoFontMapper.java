package com.aivideo.api.mapper;

import com.aivideo.api.entity.VideoFont;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 비디오 폰트 매퍼
 * v2.9.174: 해외 버추얼 크리에이터 지원
 */
@Mapper
public interface VideoFontMapper {

    Optional<VideoFont> findById(Long fontId);

    List<VideoFont> findByNationCode(String nationCode);

    Optional<VideoFont> findDefaultByNationCode(String nationCode);

    List<VideoFont> findAllActive();
}
