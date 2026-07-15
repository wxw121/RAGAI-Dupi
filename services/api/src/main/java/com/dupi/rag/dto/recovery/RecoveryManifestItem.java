package com.dupi.rag.dto.recovery;

public record RecoveryManifestItem(
        String itemKey,
        String itemType,
        String objectKey,
        long byteSize,
        String sha256) {
}
