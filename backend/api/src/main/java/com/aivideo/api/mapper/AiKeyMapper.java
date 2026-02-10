package com.aivideo.api.mapper;

import com.aivideo.api.entity.AiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI API 키 매퍼
 * v2.9.150: 서비스 레벨 API 키 관리
 */
@Mapper
public interface AiKeyMapper {

    /**
     * 활성화된 API 키 목록 조회 (우선순위 순)
     */
    List<AiKey> findActiveKeysByProvider(@Param("provider") String provider);

    /**
     * 모든 API 키 목록 조회
     */
    List<AiKey> findAll();

    /**
     * ID로 API 키 조회
     */
    AiKey findById(@Param("aiKeyId") Long aiKeyId);

    /**
     * API 키 저장
     */
    void insert(AiKey aiKey);

    /**
     * API 키 업데이트
     */
    void update(AiKey aiKey);

    /**
     * 마지막 사용 시간 업데이트
     */
    void updateLastUsed(@Param("aiKeyId") Long aiKeyId);

    /**
     * 에러 발생 기록
     */
    void incrementErrorCount(@Param("aiKeyId") Long aiKeyId);

    /**
     * 에러 카운트 리셋 (성공 시)
     */
    void resetErrorCount(@Param("aiKeyId") Long aiKeyId);

    /**
     * API 키 비활성화
     */
    void deactivate(@Param("aiKeyId") Long aiKeyId);

    /**
     * API 키 활성화
     */
    void activate(@Param("aiKeyId") Long aiKeyId);

    /**
     * API 키 삭제
     */
    void delete(@Param("aiKeyId") Long aiKeyId);
}
