package com.dupi.rag.service;

/** Declares who atomically owns the terminal transition for one workflow execution. */
public enum OperationCompletionMode {
    RUNNER,
    DOMAIN_TRANSACTION
}
