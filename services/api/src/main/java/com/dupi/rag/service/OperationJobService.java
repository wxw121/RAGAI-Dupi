package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.OperationStepResponse;
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

/** Creates, exposes and manually requeues durable operation jobs. */
@Service
@RequiredArgsConstructor
public class OperationJobService {

    private final OperationJobRepository operationJobRepository;
    private final OperationStepRepository operationStepRepository;

    @Transactional
    public OperationJobResponse create(
            OperationType operationType,
            String aggregateType,
            UUID aggregateId,
            String idempotencyKey,
            Map<String, Object> input,
            String createdBy
    ) {
        String tenantId = TenantContext.getTenantId();
        validateCreate(operationType, aggregateType, aggregateId, idempotencyKey, createdBy);
        return operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(
                        tenantId, operationType, idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> createNew(tenantId, operationType, aggregateType, aggregateId, idempotencyKey, input, createdBy));
    }

    @Transactional(readOnly = true)
    public OperationJobResponse get(UUID jobId) {
        return toResponse(findAccessible(jobId));
    }

    @Transactional
    public OperationJobResponse retry(UUID jobId) {
        OperationJob job = findAccessible(jobId);
        if (job.getStatus() == OperationStatus.COMPLETED || job.getStatus() == OperationStatus.RUNNING) {
            throw new IllegalStateException("Operation job cannot be retried while " + job.getStatus());
        }
        job.setStatus(OperationStatus.PREPARED);
        job.setNextAttemptAt(Instant.now());
        job.setLastError(null);
        job.setCompletedAt(null);
        operationJobRepository.save(job);
        return toResponse(job);
    }

    /**
     * Records a deterministic workflow step once. A completed step is returned
     * unchanged so a resumed domain workflow can skip its already-visible side
     * effect.
     */
    @Transactional
    public OperationStep recordStep(UUID jobId, String stepKey, String stepType, String resourceRef) {
        if (stepKey == null || stepKey.isBlank() || stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("Operation step requires a key and type");
        }
        return operationStepRepository.findByJobIdAndStepKey(jobId, stepKey)
                .orElseGet(() -> operationStepRepository.save(OperationStep.builder()
                        .jobId(jobId)
                        .sequenceNumber(operationStepRepository.findByJobIdOrderBySequenceNumberAsc(jobId).size() + 1)
                        .stepKey(stepKey)
                        .stepType(stepType)
                        .status(OperationStepStatus.PENDING)
                        .resourceRef(resourceRef)
                        .attemptCount(0)
                        .nextAttemptAt(Instant.now())
                        .build()));
    }

    @Transactional
    public void completeStep(UUID jobId, String stepKey) {
        OperationStep step = operationStepRepository.findByJobIdAndStepKey(jobId, stepKey)
                .orElseThrow(() -> new ResourceNotFoundException("Operation step not found: " + stepKey));
        if (step.getStatus() == OperationStepStatus.COMPLETED) {
            return;
        }
        step.setStatus(OperationStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        step.setLastError(null);
        operationStepRepository.save(step);
    }

    private OperationJobResponse createNew(
            String tenantId,
            OperationType operationType,
            String aggregateType,
            UUID aggregateId,
            String idempotencyKey,
            Map<String, Object> input,
            String createdBy
    ) {
        OperationJob job = OperationJob.builder()
                .tenantId(tenantId)
                .operationType(operationType)
                .aggregateType(aggregateType.trim())
                .aggregateId(aggregateId)
                .status(OperationStatus.PREPARED)
                .idempotencyKey(idempotencyKey.trim())
                .input(input == null ? Map.of() : Map.copyOf(input))
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdBy(createdBy.trim())
                .build();
        try {
            return toResponse(operationJobRepository.save(job));
        } catch (DataIntegrityViolationException race) {
            return operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(
                            tenantId, operationType, idempotencyKey)
                    .map(this::toResponse)
                    .orElseThrow(() -> race);
        }
    }

    private OperationJob findAccessible(UUID jobId) {
        OperationJob job = operationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (!TenantContext.getTenantId().equals(job.getTenantId())) {
            throw new ResourceNotFoundException("Operation job not found: " + jobId);
        }
        if ("KNOWLEDGE_BASE".equalsIgnoreCase(job.getAggregateType())
                && !SecurityContext.canAccessKnowledgeBase(job.getAggregateId().toString())) {
            throw new ResourceNotFoundException("Operation job not found: " + jobId);
        }
        return job;
    }

    private OperationJobResponse toResponse(OperationJob job) {
        List<OperationStepResponse> steps = operationStepRepository.findByJobIdOrderBySequenceNumberAsc(job.getId())
                .stream()
                .map(this::toStepResponse)
                .toList();
        return OperationJobResponse.builder()
                .id(job.getId())
                .operationType(job.getOperationType())
                .aggregateType(job.getAggregateType())
                .aggregateId(job.getAggregateId())
                .status(job.getStatus())
                .attemptCount(job.getAttemptCount())
                .nextAttemptAt(job.getNextAttemptAt())
                .lastError(job.getLastError())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .completedAt(job.getCompletedAt())
                .steps(steps)
                .build();
    }

    private OperationStepResponse toStepResponse(OperationStep step) {
        return OperationStepResponse.builder()
                .id(step.getId())
                .sequenceNumber(step.getSequenceNumber())
                .stepKey(step.getStepKey())
                .stepType(step.getStepType())
                .status(step.getStatus())
                .resourceRef(step.getResourceRef())
                .attemptCount(step.getAttemptCount())
                .lastError(step.getLastError())
                .startedAt(step.getStartedAt())
                .completedAt(step.getCompletedAt())
                .nextAttemptAt(step.getNextAttemptAt())
                .build();
    }

    private void validateCreate(
            OperationType operationType,
            String aggregateType,
            UUID aggregateId,
            String idempotencyKey,
            String createdBy
    ) {
        if (operationType == null || aggregateType == null || aggregateType.isBlank()
                || aggregateId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Operation job requires type, aggregate, idempotency key, and creator");
        }
    }
}
