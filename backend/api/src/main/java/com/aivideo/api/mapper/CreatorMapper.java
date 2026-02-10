package com.aivideo.api.mapper;

import com.aivideo.api.entity.Creator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * Creator Mapper Interface
 * v2.9.121: Genre → Creator 리네이밍
 * v2.9.127: showOnHome → isActive 통합, findAllShowOnHome 삭제
 */
@Mapper
public interface CreatorMapper {

    /**
     * 크리에이터 ID로 조회
     */
    Optional<Creator> findById(Long creatorId);

    /**
     * 크리에이터 코드로 조회
     */
    Optional<Creator> findByCode(String creatorCode);

    /**
     * 활성화된 모든 크리에이터 조회 (표시 순서대로)
     * v2.9.127: showOnHome 기능이 isActive로 통합됨
     */
    List<Creator> findAllActive();

    // v2.9.127: findAllShowOnHome() 삭제 (showOnHome이 isActive로 통합됨)

    /**
     * 모든 크리에이터 조회 (관리용)
     */
    List<Creator> findAll();

    /**
     * 크리에이터 생성
     */
    void insert(Creator creator);

    /**
     * 크리에이터 수정
     */
    void update(Creator creator);

    /**
     * 크리에이터 활성화/비활성화
     */
    void updateActive(@Param("creatorId") Long creatorId, @Param("isActive") Boolean isActive);
}
