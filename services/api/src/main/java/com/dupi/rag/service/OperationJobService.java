package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.OperationStepResponse;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public operation API plus context-only workflow step mutations. */
@Service
@RequiredArgsConstructor
public class OperationJobService {
    private final OperationJobRepository operationJobRepository;
    private final OperationStepRepository operationStepRepository;
    private final OperationJobWriteService writeService;
    private final OperationStepWriteService stepWriteService;

    public OperationJobResponse create(OperationType type, String aggregateType, UUID aggregateId,
                                       String idempotencyKey, Map<String, Object> input, String createdBy) {
        String tenant = TenantContext.getTenantId();
        String key = normalizeKey(idempotencyKey);
        validateCreate(type, aggregateType, aggregateId, key, createdBy);
        return operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(tenant, type, key)
                .map(this::toResponse)
                .orElseGet(() -> createNew(tenant, type, aggregateType, aggregateId, key, input, createdBy));
    }

    @Transactional(readOnly = true)
    public OperationJobResponse get(UUID jobId) {
        return toResponse(findAccessible(jobId));
    }

    /** Starts a new operator-approved budget epoch while preserving the active direction. */
    @Transactional
    public OperationJobResponse retry(UUID jobId) {
        OperationJob job = findAccessible(jobId);
        if (job.getStatus() != OperationStatus.FAILED) {
            throw new OperationConflictException("Only failed operations can be retried");
        }
        job.setStatus(job.getPhase() == OperationPhase.COMPENSATION
                ? OperationStatus.COMPENSATING : OperationStatus.PREPARED);
        job.setRunnable(true);
        job.setPhaseAttemptCount(0);
        job.setNextAttemptAt(Instant.now());
        job.setLastError(null);
        job.setCompletedAt(null);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        job.setRetryEpoch(safeRetryEpoch(job) + 1);
        operationStepRepository.findByJobIdAndStatus(jobId, OperationStepStatus.FAILED).forEach(step -> {
            step.setStatus(OperationStepStatus.RETRY_WAIT);
            step.setRetryEpoch(job.getRetryEpoch());
            step.setStartedAt(null);
            step.setCompletedAt(null);
            step.setLastError(null);
            step.setNextAttemptAt(job.getNextAttemptAt());
            operationStepRepository.save(step);
        });
        operationJobRepository.save(job);
        return toResponse(job);
    }

    /** Atomically exposes completed intake to the scheduler. */
    @Transactional
    public OperationJobResponse makeRunnable(UUID jobId) {
        OperationJob job = operationJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (job.getStatus() != OperationStatus.PREPARED) {
            throw new OperationConflictException("Only prepared intake can become runnable");
        }
        job.setRunnable(true);
        job.setNextAttemptAt(Instant.now());
        return toResponse(operationJobRepository.save(job));
    }

    public OperationStep recordStep(OperationExecutionContext context, String stepKey,
                                    String stepType, String resourceRef) {
        return stepWriteService.recordStep(context, stepKey, stepType, resourceRef);
    }

    public OperationStep startStep(OperationExecutionContext context, String stepKey) {
        return stepWriteService.startStep(context, stepKey);
    }

    public OperationStep retryStep(OperationExecutionContext context, String stepKey,
                                   String diagnostic, Instant nextAttemptAt) {
        return stepWriteService.retryStep(context, stepKey, diagnostic, nextAttemptAt);
    }

    public OperationStep failStep(OperationExecutionContext context, String stepKey, String diagnostic) {
        return stepWriteService.failStep(context, stepKey, diagnostic);
    }

    public void completeStep(OperationExecutionContext context, String stepKey) {
        stepWriteService.completeStep(context, stepKey);
    }

    public OperationStep compensateStep(OperationExecutionContext context, String stepKey) {
        return stepWriteService.compensateStep(context, stepKey);
    }

    private OperationJobResponse createNew(String tenant, OperationType type, String aggregateType, UUID aggregateId,
                                           String key, Map<String, Object> input, String createdBy) {
        try {
            return toResponse(writeService.insertJob(tenant, type, aggregateType.trim(), aggregateId, key,
                    input == null ? Map.of() : Map.copyOf(input), createdBy.trim()));
        } catch (DataIntegrityViolationException conflict) {
            return operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(tenant, type, key)
                    .map(this::toResponse).orElseThrow(() -> conflict);
        }
    }

    private OperationJob findAccessible(UUID jobId) {
        OperationJob job = operationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (!TenantContext.getTenantId().equals(job.getTenantId())
                || ("KNOWLEDGE_BASE".equalsIgnoreCase(job.getAggregateType())
                && !SecurityContext.canAccessKnowledgeBase(job.getAggregateId().toString()))) {
            throw new ResourceNotFoundException("Operation job not found: " + jobId);
        }
        return job;
    }

    private OperationJobResponse toResponse(OperationJob job) {
        List<OperationStepResponse> steps = operationStepRepository.findByJobIdOrderBySequenceNumberAsc(job.getId()).stream()
                .map(this::toStepResponse).toList();
        return OperationJobResponse.builder().id(job.getId()).operationType(job.getOperationType())
                .aggregateType(job.getAggregateType()).aggregateId(job.getAggregateId()).status(job.getStatus())
                .attemptCount(job.getAttemptCount()).nextAttemptAt(job.getNextAttemptAt())
                .errorCode(job.getLastError() == null ? null : "operation_failed")
                .errorMessage(job.getLastError() == null ? null
                        : "Operation failed. Retry when the underlying service is available.")
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).completedAt(job.getCompletedAt())
                .steps(steps).build();
    }

    private OperationStepResponse toStepResponse(OperationStep step) {
        return OperationStepResponse.builder().id(step.getId()).sequenceNumber(step.getSequenceNumber())
                .stepKey(step.getStepKey()).stepType(step.getStepType()).status(step.getStatus())
                .attemptCount(step.getAttemptCount())
                .lastError(step.getLastError() == null ? null : "Operation step failed.")
                .startedAt(step.getStartedAt()).completedAt(step.getCompletedAt())
                .nextAttemptAt(step.getNextAttemptAt()).build();
    }

    private long safeRetryEpoch(OperationJob job) {
        return job.getRetryEpoch() == null ? 0L : job.getRetryEpoch();
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim();
    }

    private void validateCreate(OperationType type, String aggregateType, UUID aggregateId,
                                String key, String createdBy) {
        if (type == null || aggregateType == null || aggregateType.isBlank() || aggregateId == null
                || key.isEmpty() || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException(
                    "Operation job requires type, aggregate, idempotency key, and creator");
        }
    }
}
