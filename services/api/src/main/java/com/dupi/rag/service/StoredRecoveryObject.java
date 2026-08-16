package com.dupi.rag.service;

public record StoredRecoveryObject(
        String bucket,
        String objectKey,
        long byteSize,
        String sha256,
        String versionToken
) {
    public StoredRecoveryObject(String bucket, String objectKey, long byteSize, String sha256) {
        this(bucket, objectKey, byteSize, sha256, null);
    }
}
