package com.dupi.rag.domain.enums;

public enum IngestJobStatus {
    UPLOAD_INTENT,
    PENDING,
    PROCESSING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
