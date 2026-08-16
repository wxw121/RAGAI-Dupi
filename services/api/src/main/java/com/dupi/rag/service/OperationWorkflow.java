package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationType;

import java.util.UUID;

/** A domain-specific durable operation implementation. */
public interface OperationWorkflow {

    OperationType type();

    void execute(UUID jobId);
}
