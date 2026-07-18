package com.dupi.rag.domain.enums;

public enum IngestJobStatus {
    PENDING,
    PROCESSING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
