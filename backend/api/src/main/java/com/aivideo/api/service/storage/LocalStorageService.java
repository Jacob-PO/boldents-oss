package com.aivideo.api.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 로컬 파일 시스템 기반 저장소 서비스
 * S3가 비활성화되어 있을 때 사용
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private static final String LOCAL_STORAGE_PATH = System.getProperty("java.io.tmpdir") + "/aivideo";

    public LocalStorageService() {
        try {
            Path storagePath = Paths.get(LOCAL_STORAGE_PATH);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
            }
            log.info("LocalStorageService initialized - path: {}", LOCAL_STORAGE_PATH);
        } catch (IOException e) {
            log.error("Failed to create local storage directory", e);
        }
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        try {
            Path filePath = getFilePath(key);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            log.info("File saved locally: {}", filePath);
            return key;
        } catch (IOException e) {
            log.error("Failed to save file locally: {}", key, e);
            throw new RuntimeException("Local storage failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String upload(String key, InputStream inputStream, String contentType, long contentLength) {
        try {
            Path filePath = getFilePath(key);
            Files.createDirectories(filePath.getParent());
            Files.copy(inputStream, filePath);
            log.info("File saved locally: {}", filePath);
            return key;
        } catch (IOException e) {
            log.error("Failed to save file locally: {}", key, e);
            throw new RuntimeException("Local storage failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] download(String key) {
        try {
            Path filePath = getFilePath(key);
            if (!Files.exists(filePath)) {
                throw new RuntimeException("File not found: " + key);
            }
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read local file: {}", key, e);
            throw new RuntimeException("Local storage read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String generatePresignedUrl(String key) {
        // 로컬 저장소는 presigned URL을 생성하지 않음
        // 대신 API 엔드포인트를 통해 파일을 제공
        return "/api/files/download/" + key;
    }

    @Override
    public void delete(String key) {
        try {
            Path filePath = getFilePath(key);
            Files.deleteIfExists(filePath);
            log.info("File deleted locally: {}", key);
        } catch (IOException e) {
            log.error("Failed to delete local file: {}", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(getFilePath(key));
    }

    @Override
    public boolean isEnabled() {
        return false; // S3가 비활성화됨
    }

    private Path getFilePath(String key) {
        return Paths.get(LOCAL_STORAGE_PATH, key);
    }
}
