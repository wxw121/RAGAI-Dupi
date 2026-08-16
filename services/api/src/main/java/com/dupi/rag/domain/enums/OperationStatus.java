package com.dupi.rag.domain.enums;

public enum OperationStatus {
    PREPARED,
    RUNNING,
    RETRY_WAIT,
    COMPENSATING,
    COMPLETED,
    FAILED
}
