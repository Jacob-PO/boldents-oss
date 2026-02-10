package com.aivideo.api.mapper;

import com.aivideo.api.entity.VideoThumbnail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 썸네일 디자인 스타일 매퍼 (v2.9.165)
 */
@Mapper
public interface VideoThumbnailMapper {

    VideoThumbnail findById(@Param("thumbnailId") Long thumbnailId);

    VideoThumbnail findByCode(@Param("styleCode") String styleCode);

    List<VideoThumbnail> findAllActive();

    VideoThumbnail findDefault();
}
