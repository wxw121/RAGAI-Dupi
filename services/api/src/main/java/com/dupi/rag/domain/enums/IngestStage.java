package com.dupi.rag.domain.enums;

public enum IngestStage {
    UPLOAD_PENDING,
    UPLOAD_CLEANUP,
    QUEUED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    CANCELLED,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
