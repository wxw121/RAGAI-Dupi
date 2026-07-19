package com.dupi.rag.service;

import io.minio.*;
import io.minio.messages.Item;
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
        return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
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
}
