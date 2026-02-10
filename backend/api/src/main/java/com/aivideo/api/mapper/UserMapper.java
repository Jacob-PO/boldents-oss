package com.aivideo.api.mapper;

import com.aivideo.api.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserMapper {

    Optional<User> findByLoginId(@Param("loginId") String loginId);

    Optional<User> findByUserNo(@Param("userNo") Long userNo);

    int insert(User user);

    /**
     * v2.9.150: 사용자에게 연결된 크리에이터 ID 조회
     */
    Long findCreatorIdByUserNo(@Param("userNo") Long userNo);

    /**
     * v2.9.150: 사용자 크리에이터 ID 업데이트
     */
    void updateCreatorId(@Param("userNo") Long userNo, @Param("creatorId") Long creatorId);

    /**
     * v2.9.169: 사용자 Google API 키 업데이트 (암호화된 값 또는 null)
     */
    void updateGoogleApiKey(@Param("userNo") Long userNo, @Param("googleApiKey") String googleApiKey);
}
