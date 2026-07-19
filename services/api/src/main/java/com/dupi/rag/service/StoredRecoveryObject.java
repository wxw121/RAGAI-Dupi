package com.dupi.rag.service;

public record StoredRecoveryObject(String bucket, String objectKey, long byteSize, String sha256) {
}
