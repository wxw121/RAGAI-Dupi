package com.dupi.rag.domain.enums;

public enum IngestStage {
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
