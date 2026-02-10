package com.aivideo.api.mapper;

import com.aivideo.api.entity.Scenario;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ScenarioMapper {

    void insert(Scenario scenario);

    Optional<Scenario> findById(Long scenarioId);

    Optional<Scenario> findByVideoId(Long videoId);

    void update(Scenario scenario);

    void updateVersion(@Param("scenarioId") Long scenarioId, @Param("version") Integer version);

    void delete(Long scenarioId);

    void deleteByVideoId(Long videoId);

    /**
     * v2.9.13: 여러 video의 scenarios 일괄 삭제 (N+1 쿼리 방지)
     */
    void deleteByVideoIdsBatch(@Param("videoIds") List<Long> videoIds);
}
