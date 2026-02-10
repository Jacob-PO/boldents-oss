package com.aivideo.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 DB 스키마 마이그레이션 자동 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running database schema migrations...");

        // video_duration 컬럼 추가
        addColumnIfNotExists("conversations", "video_duration",
            "ALTER TABLE conversations ADD COLUMN video_duration INT NULL DEFAULT 10 COMMENT '영상 길이 (분단위: 5, 10, 15)' AFTER quality_tier");

        // google_api_key 컬럼 추가 (users 테이블)
        addColumnIfNotExists("users", "google_api_key",
            "ALTER TABLE users ADD COLUMN google_api_key VARCHAR(500) NULL COMMENT '암호화된 Google AI API 키' AFTER role");

        log.info("Database schema migrations completed.");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String alterSql) {
        try {
            String checkSql = """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = ?
                AND COLUMN_NAME = ?
                """;

            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, columnName);

            if (count == null || count == 0) {
                log.info("Adding column {} to table {}...", columnName, tableName);
                jdbcTemplate.execute(alterSql);
                log.info("Column {} added successfully.", columnName);
            } else {
                log.info("Column {} already exists in table {}.", columnName, tableName);
            }
        } catch (Exception e) {
            log.warn("Failed to add column {} to {}: {}", columnName, tableName, e.getMessage());
        }
    }
}
