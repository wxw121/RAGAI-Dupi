package com.dupi.rag.service;

import io.minio.*;
import io.minio.messages.Item;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MinioRecoveryObjectStore implements RecoveryObjectStore {
    private static final long MIN_PART_SIZE = 10L * 1024L * 1024L;
    private final MinioClient minioClient;

    @Override
    public void put(String bucket, String key, InputStream input) throws Exception {
        ensureBucket(bucket);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(input, -1, MIN_PART_SIZE)
                .contentType("application/octet-stream")
                .build());
    }

    @Override
    public InputStream get(String bucket, String key) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException exception) {
            if (isMissing(exception)) {
                throw new RecoveryObjectNotFoundException(bucket, key, exception);
            }
            throw exception;
        }
    }

    @Override
    public List<String> list(String bucket, String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(prefix).recursive(true).build())) {
            keys.add(result.get().objectName());
        }
        return keys;
    }

    @Override
    public void delete(String bucket, String key) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    private void ensureBucket(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * MinIO Java 8.5.10 does not forward extra headers to multipart completion. Recovery ZIPs are
     * bounded to 1 GiB, so a disk-backed, one-part upload stays below the direct-PUT ceiling and
     * places {@code If-None-Match} on the operation that atomically publishes the object.
     */
    @Override
    public RecoveryObjectWriteResult putIfAbsent(String bucket, String key, InputStream input) throws Exception {
        ensureBucket(bucket);
        Path upload = Files.createTempFile("dupi-recovery-stage-", ".upload");
        try {
            long size = Files.copy(input, upload, StandardCopyOption.REPLACE_EXISTING);
            ObjectWriteResponse response = minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .filename(upload.toString(), Math.max(size, MIN_PART_SIZE))
                    .contentType("application/octet-stream")
                    .extraHeaders(Map.of("If-None-Match", "*"))
                    .build());
            String version = response.versionId();
            if (version == null || version.isBlank()) version = response.etag();
            return RecoveryObjectWriteResult.created(version);
        } catch (ErrorResponseException exception) {
            if (isPreconditionFailed(exception)) return RecoveryObjectWriteResult.lostRace();
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(upload);
            } catch (IOException cleanupFailure) {
                upload.toFile().deleteOnExit();
            }
        }
    }

    @Override
    public String version(String bucket, String key) throws Exception {
        try {
            StatObjectResponse response = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            String versionId = response.versionId();
            return versionId == null || versionId.isBlank() ? response.etag() : versionId;
        } catch (ErrorResponseException exception) {
            if (isMissing(exception)) throw new RecoveryObjectNotFoundException(bucket, key, exception);
            throw exception;
        }
    }

    private boolean isMissing(ErrorResponseException exception) {
        String code = exception.errorResponse() == null ? null : exception.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
    }

    private boolean isPreconditionFailed(ErrorResponseException exception) {
        String code = exception.errorResponse() == null ? null : exception.errorResponse().code();
        return "PreconditionFailed".equals(code)
                || exception.response() != null && exception.response().code() == 412;
    }
}
