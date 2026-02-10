package com.aivideo.api.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 클라이언트 공통 설정 (v2.9.11)
 * - RestTemplate: Connection timeout, Read timeout 설정
 * - ObjectMapper: JSON 직렬화/역직렬화 설정
 *
 * 각 서비스에서 @Autowired로 주입받아 사용
 */
@Configuration
public class HttpClientConfig {

    /**
     * RestTemplate Bean (싱글톤)
     * - Connection Timeout: 30초
     * - Read Timeout: 무제한 (0 = no timeout)
     * v2.9.63: 긴 TTS 텍스트 생성 지원을 위해 타임아웃 제거
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);  // 30초 (연결 타임아웃)
        factory.setReadTimeout(0);         // 무제한 (TTS 생성은 오래 걸릴 수 있음)

        return new RestTemplate(factory);
    }

    /**
     * ObjectMapper Bean (싱글톤)
     * - 알 수 없는 속성 무시
     * - Java 8 날짜/시간 지원
     * - v2.9.74: StreamReadConstraints 증가 (TTS 응답 ~20MB+ 지원)
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 알 수 없는 속성이 있어도 에러 발생 안 함
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Java 8 날짜/시간 모듈 등록
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // v2.9.76: TTS 응답 파싱을 위해 String 길이 제한 증가
        // 6000자+ 나레이션 → TTS 오디오 base64 응답 ~30MB+
        // 기본값 20MB에서 100MB로 증가 (여유있게)
        StreamReadConstraints constraints = StreamReadConstraints.builder()
            .maxStringLength(100_000_000)  // 100MB (기본값: 20MB)
            .build();
        mapper.getFactory().setStreamReadConstraints(constraints);

        return mapper;
    }
}
