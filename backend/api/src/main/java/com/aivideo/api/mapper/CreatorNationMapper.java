package com.aivideo.api.mapper;

import com.aivideo.api.entity.CreatorNation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * 크리에이터 국가 매퍼
 * v2.9.174: 해외 버추얼 크리에이터 지원
 */
@Mapper
public interface CreatorNationMapper {

    Optional<CreatorNation> findByCode(String nationCode);

    List<CreatorNation> findAllActive();
}
