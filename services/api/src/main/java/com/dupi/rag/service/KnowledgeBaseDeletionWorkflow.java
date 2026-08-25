package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Executes the immutable cleanup inventory captured when knowledge-base deletion was submitted. */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeletionWorkflow implements OperationWorkflow {
    static final String DELETE_OBJECT = "DELETE_OBJECT";
    static final String DELETE_PROFILE_VECTORS = "DELETE_PROFILE_VECTORS";
    static final String DELETE_LEGACY_VECTORS = "DELETE_LEGACY_VECTORS";
    static final String DELETE_SPARSE_VECTORS = "DELETE_SPARSE_VECTORS";
    static final String FINALIZE_DELETE = "FINALIZE_DELETE";

    private final OperationStepRepository steps;
    private final OperationJobService operations;
    private final OperationJobClaimService claims;
    private final MinioStorageService storage;
    private final MilvusVectorService vectors;
    private final KnowledgeBaseDeletionPersistenceService persistence;

    @Override
    public OperationType type() {
        return OperationType.KNOWLEDGE_BASE_DELETE;
    }

    @Override
    public void executeForward(OperationExecutionContext context) {
        boolean finalized = false;
        for (OperationStep step : steps.findByJobIdOrderBySequenceNumberAsc(context.jobId())) {
            if (step.getStatus() == OperationStepStatus.COMPLETED) {
                finalized |= FINALIZE_DELETE.equals(step.getStepType());
                continue;
            }
            if (FINALIZE_DELETE.equals(step.getStepType())) {
                completeDeletion(context, step.getStepKey());
                finalized = true;
                continue;
            }
            executeExternal(context, step);
        }
        if (!finalized) {
            throw new IllegalStateException("Knowledge-base deletion inventory has no finalization step");
        }
    }

    @Override
    public void executeCompensation(OperationExecutionContext context) {
        throw new IllegalStateException("Knowledge-base deletion does not support compensation");
    }

    @Override
    public OperationCompletionMode completionMode(OperationExecutionContext context) {
        return OperationCompletionMode.DOMAIN_TRANSACTION;
    }

    private void completeDeletion(OperationExecutionContext context, String finalStepKey) {
        try {
            persistence.completeDeletion(context, finalStepKey);
        } catch (DataAccessException failure) {
            throw new RetryableOperationException(
                    "Knowledge-base deletion final transaction is temporarily unavailable", failure);
        }
    }

    private void executeExternal(OperationExecutionContext context, OperationStep step) {
        if (step.getStatus() == OperationStepStatus.PENDING
                || step.getStatus() == OperationStepStatus.RETRY_WAIT) {
            operations.startStep(context, step.getStepKey());
        } else if (step.getStatus() != OperationStepStatus.RUNNING) {
            throw new IllegalStateException("Deletion step cannot execute while " + step.getStatus());
        }
        try {
            claims.renewLease(context);
            switch (step.getStepType()) {
                case DELETE_OBJECT -> storage.deleteChecked(step.getResourceRef());
                case DELETE_PROFILE_VECTORS ->
                        vectors.deleteProfileByKbIdForCleanup(UUID.fromString(step.getResourceRef()));
                case DELETE_LEGACY_VECTORS ->
                        vectors.deleteLegacyByKbIdForCleanup(UUID.fromString(step.getResourceRef()));
                case DELETE_SPARSE_VECTORS -> deleteSparse(step.getResourceRef());
                default -> throw new IllegalStateException(
                        "Unsupported knowledge-base deletion step type: " + step.getStepType());
            }
            claims.renewLease(context);
            operations.completeStep(context, step.getStepKey());
        } catch (RuntimeException failure) {
            String diagnostic = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            operations.retryStep(context, step.getStepKey(), diagnostic, Instant.now());
            String dependency = DELETE_OBJECT.equals(step.getStepType()) ? "object storage" : "vector storage";
            throw new RetryableOperationException(
                    "Knowledge-base deletion " + dependency + " is temporarily unavailable", failure);
        }
    }

    private void deleteSparse(String resourceRef) {
        int separator = resourceRef.lastIndexOf(':');
        if (separator <= 0 || separator == resourceRef.length() - 1) {
            throw new IllegalStateException("Invalid sparse vector deletion scope");
        }
        UUID kbId = UUID.fromString(resourceRef.substring(0, separator));
        int version = Integer.parseInt(resourceRef.substring(separator + 1));
        vectors.deleteSparseByKbIdForCleanup(kbId, List.of(version));
    }
}
