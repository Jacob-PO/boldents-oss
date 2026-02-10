package com.aivideo.api.service.storage;

import com.aivideo.api.config.S3Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.time.Duration;

/**
 * AWS S3 기반 파일 저장소 서비스
 * 30분 후 자동 삭제되는 임시 파일 저장용
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.s3.enabled", havingValue = "true")
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Config s3Config;

    public S3StorageService(S3Client s3Client, S3Presigner s3Presigner, S3Config s3Config) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.s3Config = s3Config;
        log.info("S3StorageService initialized - bucket: {}, region: {}",
                s3Config.getBucket(), s3Config.getRegion());
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));

            log.info("File uploaded to S3: {}/{}", s3Config.getBucket(), key);
            return key;
        } catch (S3Exception e) {
            log.error("Failed to upload file to S3: {}", key, e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String upload(String key, InputStream inputStream, String contentType, long contentLength) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));

            log.info("File uploaded to S3: {}/{} (size: {} bytes)",
                    s3Config.getBucket(), key, contentLength);
            return key;
        } catch (S3Exception e) {
            log.error("Failed to upload file to S3: {}", key, e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] download(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException e) {
            log.warn("File not found in S3: {}", key);
            throw new RuntimeException("File not found: " + key, e);
        } catch (S3Exception e) {
            log.error("Failed to download file from S3: {}", key, e);
            throw new RuntimeException("S3 download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String generatePresignedUrl(String key) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(s3Config.getPresignedUrlExpiration()))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            log.debug("Generated presigned URL for: {} (expires in {} seconds)",
                    key, s3Config.getPresignedUrlExpiration());
            return url;
        } catch (S3Exception e) {
            log.error("Failed to generate presigned URL for: {}", key, e);
            throw new RuntimeException("Failed to generate presigned URL: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.info("File deleted from S3: {}", key);
        } catch (S3Exception e) {
            log.error("Failed to delete file from S3: {}", key, e);
            throw new RuntimeException("S3 delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(key)
                    .build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.error("Failed to check file existence in S3: {}", key, e);
            throw new RuntimeException("S3 head object failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
