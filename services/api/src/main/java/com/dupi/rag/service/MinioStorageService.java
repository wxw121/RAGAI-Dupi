package com.dupi.rag.service;

import com.dupi.rag.config.MinioProperties;
import com.dupi.rag.exception.ResourceNotFoundException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getBucket())
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure MinIO bucket", e);
        }
    }

    public String upload(String objectKey, InputStream stream, long size, String contentType) {
        ensureBucket();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
            return objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload to MinIO", e);
        }
    }

    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new ResourceNotFoundException("Object not found: " + objectKey);
        }
    }

    public boolean delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete object {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    /** Atomic conditional create for known, bounded document objects. */
    public ObjectWriteResult uploadIfAbsent(String objectKey, InputStream stream, long size, String contentType) {
        ensureBucket();
        try {
            long partSize = Math.max(10L * 1024 * 1024, size);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket()).object(objectKey)
                    .stream(stream, size, partSize).contentType(contentType)
                    .extraHeaders(Map.of("If-None-Match", "*")).build());
            return ObjectWriteResult.CREATED;
        } catch (io.minio.errors.ErrorResponseException conflict) {
            String code = conflict.errorResponse() == null ? "" : conflict.errorResponse().code();
            if ("PreconditionFailed".equals(code)
                    || conflict.response() != null && conflict.response().code() == 412) {
                return ObjectWriteResult.ALREADY_EXISTS;
            }
            throw new IllegalStateException("Failed to upload to MinIO", conflict);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to upload to MinIO", failure);
        }
    }

    /** Neutral replay primitive used by durable workflows before deterministic object writes. */
    public ObjectInspection inspect(String objectKey, long expectedSize, String expectedSha256) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket()).object(objectKey).build());
            if (stat.size() != expectedSize) return new ObjectInspection(ObjectState.CONFLICT, stat.size(), null);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket()).object(objectKey).build())) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            return new ObjectInspection(size == expectedSize && sha.equals(expectedSha256)
                    ? ObjectState.MATCHING : ObjectState.CONFLICT, size, sha);
        } catch (io.minio.errors.ErrorResponseException missing) {
            String code = missing.errorResponse() == null ? "" : missing.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return new ObjectInspection(ObjectState.ABSENT, 0, null);
            }
            throw new IllegalStateException("Failed to inspect MinIO object", missing);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to inspect MinIO object", failure);
        }
    }

    /** Missing objects are successful deletes; every other storage failure is propagated. */
    public void deleteChecked(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket()).object(objectKey).build());
        } catch (io.minio.errors.ErrorResponseException missing) {
            String code = missing.errorResponse() == null ? "" : missing.errorResponse().code();
            if (!"NoSuchKey".equals(code) && !"NoSuchObject".equals(code) && !"NoSuchBucket".equals(code)) {
                throw new IllegalStateException("Failed to delete MinIO object", missing);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to delete MinIO object", failure);
        }
    }

    public enum ObjectState { ABSENT, MATCHING, CONFLICT }
    public enum ObjectWriteResult { CREATED, ALREADY_EXISTS }
    public record ObjectInspection(ObjectState state, long byteSize, String sha256) { }
}
