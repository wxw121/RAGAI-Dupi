package com.dupi.rag.dto.recovery;

import java.util.List;

public record RecoveryManifest(
        RecoveryManifestHeader header,
        long itemCount,
        long totalBytes,
        List<RecoveryManifestItem> items,
        String manifestChecksum) {
}
