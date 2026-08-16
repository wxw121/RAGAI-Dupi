package com.dupi.rag.domain.enums;

public enum OperationStatus {
    PREPARED,
    PENDING,
    RUNNING,
    RETRY_WAIT,
    COMPENSATING,
    COMPLETED,
    FAILED
}
