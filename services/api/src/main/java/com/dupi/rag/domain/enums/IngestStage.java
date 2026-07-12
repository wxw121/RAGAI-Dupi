package com.dupi.rag.domain.enums;

public enum IngestStage {
    QUEUED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
