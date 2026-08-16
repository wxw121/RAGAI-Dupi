package com.dupi.rag.service;

import io.minio.*;
import io.minio.messages.Item;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    private boolean isMissing(ErrorResponseException exception) {
        String code = exception.errorResponse() == null ? null : exception.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
    }
}
