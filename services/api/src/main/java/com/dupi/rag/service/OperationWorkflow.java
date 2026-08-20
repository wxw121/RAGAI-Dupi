package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationType;

/** A domain-specific durable operation implementation. */
public interface OperationWorkflow {

    OperationType type();

    void executeForward(OperationExecutionContext context);

    void executeCompensation(OperationExecutionContext context);

    default OperationCompletionMode completionMode(OperationExecutionContext context) {
        return OperationCompletionMode.RUNNER;
    }
}
