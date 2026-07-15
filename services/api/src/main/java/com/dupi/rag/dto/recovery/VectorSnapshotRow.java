package com.dupi.rag.dto.recovery;

import java.util.Map;

public record VectorSnapshotRow(
        String chunkId,
        String knowledgeBaseId,
        String documentId,
        String content,
        Object embedding,
        Map<String, Object> scalarPayload) {
}
