package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.OperationStepResponse;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Public operation status API plus the small shared step lifecycle. */
@Service
public class OperationJobService {
    private final OperationJobRepository operationJobRepository;
    private final OperationStepRepository operationStepRepository;
    private final OperationJobWriteService writeService;
    private final OperationJobClaimService claimService;

    public OperationJobService(OperationJobRepository operationJobRepository, OperationStepRepository operationStepRepository,
                               OperationJobWriteService writeService) {
        this(operationJobRepository, operationStepRepository, writeService, null);
    }

    @Autowired
    public OperationJobService(OperationJobRepository operationJobRepository, OperationStepRepository operationStepRepository,
                               OperationJobWriteService writeService, OperationJobClaimService claimService) {
        this.operationJobRepository = operationJobRepository;
        this.operationStepRepository = operationStepRepository;
        this.writeService = writeService;
        this.claimService = claimService;
    }

    public OperationJobService(OperationJobRepository operationJobRepository, OperationStepRepository operationStepRepository) {
        this(operationJobRepository, operationStepRepository, null, null);
    }

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

    /** Manual retry deliberately resets the automatic attempt budget for a new operator-approved epoch. */
    @Transactional
    public OperationJobResponse retry(UUID jobId) {
        OperationJob job = findAccessible(jobId);
        if (job.getStatus() != OperationStatus.FAILED) {
            throw new OperationConflictException("Only failed operations can be retried");
        }
        job.setStatus(job.getPhase() == OperationPhase.COMPENSATION ? OperationStatus.COMPENSATING : OperationStatus.PREPARED);
        job.setRunnable(true);
        job.setAttemptCount(0);
        job.setNextAttemptAt(Instant.now());
        job.setLastError(null);
        job.setCompletedAt(null);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        job.setRetryEpoch((job.getRetryEpoch() == null ? 0L : job.getRetryEpoch()) + 1);
        operationStepRepository.findByJobIdAndStatus(jobId, OperationStepStatus.FAILED).forEach(step -> {
            step.setStatus(OperationStepStatus.RETRY_WAIT);
            step.setRetryEpoch(job.getRetryEpoch());
            step.setLastError(null);
            step.setCompletedAt(null);
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

    @Transactional
    public OperationStep recordStep(UUID jobId, String stepKey, String stepType, String resourceRef) {
        String key = normalizeKey(stepKey);
        if (key.isEmpty() || stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("Operation step requires a key and type");
        }
        return operationStepRepository.findByJobIdAndStepKey(jobId, key).orElseGet(() -> {
            if (writeService != null) {
                try {
                    return writeService.insertStep(jobId, key, stepType.trim(), resourceRef);
                } catch (DataIntegrityViolationException conflict) {
                    return operationStepRepository.findByJobIdAndStepKey(jobId, key).orElseThrow(() -> conflict);
                }
            }
            // Locking the parent serializes sequence allocation for distinct step keys.
            operationJobRepository.findByIdForUpdate(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
            return operationStepRepository.findByJobIdAndStepKey(jobId, key).orElseGet(() ->
                    operationStepRepository.saveAndFlush(OperationStep.builder()
                            .jobId(jobId)
                            .sequenceNumber(operationStepRepository.findByJobIdOrderBySequenceNumberAsc(jobId).size() + 1)
                            .stepKey(key).stepType(stepType.trim()).status(OperationStepStatus.PENDING)
                            .resourceRef(resourceRef).attemptCount(0).nextAttemptAt(Instant.now()).build()));
        });
    }

    @Transactional
    public OperationStep startStep(UUID jobId, String stepKey) {
        OperationStep step = step(jobId, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPLETED || step.getStatus() == OperationStepStatus.COMPENSATED) return step;
        require(step, OperationStepStatus.PENDING, OperationStepStatus.RETRY_WAIT);
        step.setStatus(OperationStepStatus.RUNNING);
        step.setAttemptCount((step.getAttemptCount() == null ? 0 : step.getAttemptCount()) + 1);
        step.setStartedAt(Instant.now());
        step.setLastError(null);
        return operationStepRepository.save(step);
    }

    public OperationStep startStep(OperationExecutionContext context, String stepKey) {
        requireActive(context);
        return startStep(context.jobId(), stepKey);
    }

    @Transactional
    public OperationStep retryStep(UUID jobId, String stepKey, String diagnostic) {
        OperationStep step = step(jobId, stepKey); require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.RETRY_WAIT); step.setLastError(diagnostic);
        step.setNextAttemptAt(Instant.now().plusSeconds(30));
        return operationStepRepository.save(step);
    }

    public OperationStep retryStep(OperationExecutionContext context, String stepKey, String diagnostic, Instant nextAttemptAt) {
        requireActive(context);
        OperationStep step = step(context.jobId(), stepKey); require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.RETRY_WAIT); step.setLastError(diagnostic); step.setNextAttemptAt(nextAttemptAt);
        return operationStepRepository.save(step);
    }

    @Transactional
    public OperationStep failStep(UUID jobId, String stepKey, String diagnostic) {
        OperationStep step = step(jobId, stepKey); require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.FAILED); step.setLastError(diagnostic); step.setCompletedAt(Instant.now()); step.setNextAttemptAt(null);
        return operationStepRepository.save(step);
    }

    public OperationStep failStep(OperationExecutionContext context, String stepKey, String diagnostic) { requireActive(context); return failStep(context.jobId(), stepKey, diagnostic); }

    @Transactional
    public void completeStep(UUID jobId, String stepKey) {
        OperationStep step = step(jobId, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.COMPLETED); step.setCompletedAt(Instant.now()); step.setLastError(null); step.setNextAttemptAt(null);
        operationStepRepository.save(step);
    }

    public void completeStep(OperationExecutionContext context, String stepKey) { requireActive(context); completeStep(context.jobId(), stepKey); }

    @Transactional
    public OperationStep compensateStep(UUID jobId, String stepKey) {
        OperationStep step = step(jobId, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPENSATED) return step;
        require(step, OperationStepStatus.COMPLETED, OperationStepStatus.FAILED);
        step.setStatus(OperationStepStatus.COMPENSATED); step.setCompletedAt(Instant.now());
        step.setNextAttemptAt(null);
        return operationStepRepository.save(step);
    }

    public OperationStep compensateStep(OperationExecutionContext context, String stepKey) { requireActive(context); return compensateStep(context.jobId(), stepKey); }

    private OperationJobResponse createNew(String tenant, OperationType type, String aggregateType, UUID aggregateId,
                                            String key, Map<String, Object> input, String createdBy) {
        OperationJob job = OperationJob.builder().tenantId(tenant).operationType(type)
                .aggregateType(aggregateType.trim()).aggregateId(aggregateId).status(OperationStatus.PREPARED)
                .runnable(false).idempotencyKey(key).input(input == null ? Map.of() : Map.copyOf(input))
                .attemptCount(0).nextAttemptAt(Instant.now()).createdBy(createdBy.trim()).build();
        try {
            return toResponse(writeService == null ? operationJobRepository.saveAndFlush(job)
                    : writeService.insertJob(tenant, type, aggregateType.trim(), aggregateId, key,
                    input == null ? Map.of() : Map.copyOf(input), createdBy.trim()));
        } catch (DataIntegrityViolationException conflict) {
            return operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(tenant, type, key)
                    .map(this::toResponse).orElseThrow(() -> conflict);
        }
    }

    private OperationJob findAccessible(UUID jobId) {
        OperationJob job = operationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (!TenantContext.getTenantId().equals(job.getTenantId()) || ("KNOWLEDGE_BASE".equalsIgnoreCase(job.getAggregateType())
                && !SecurityContext.canAccessKnowledgeBase(job.getAggregateId().toString()))) {
            throw new ResourceNotFoundException("Operation job not found: " + jobId);
        }
        return job;
    }

    private OperationStep step(UUID jobId, String key) {
        return operationStepRepository.findByJobIdAndStepKey(jobId, normalizeKey(key))
                .orElseThrow(() -> new ResourceNotFoundException("Operation step not found: " + key));
    }

    private void require(OperationStep step, OperationStepStatus... allowed) {
        for (OperationStepStatus status : allowed) if (step.getStatus() == status) return;
        throw new OperationConflictException("Operation step cannot transition while " + step.getStatus());
    }

    private void requireActive(OperationExecutionContext context) {
        if (claimService == null) throw new OperationConflictException("Operation execution context is required");
        claimService.assertActiveClaim(context);
    }

    private OperationJobResponse toResponse(OperationJob job) {
        List<OperationStepResponse> steps = operationStepRepository.findByJobIdOrderBySequenceNumberAsc(job.getId()).stream()
                .map(this::toStepResponse).toList();
        return OperationJobResponse.builder().id(job.getId()).operationType(job.getOperationType())
                .aggregateType(job.getAggregateType()).aggregateId(job.getAggregateId()).status(job.getStatus())
                .attemptCount(job.getAttemptCount()).nextAttemptAt(job.getNextAttemptAt())
                .errorCode(job.getLastError() == null ? null : "operation_failed")
                .errorMessage(job.getLastError() == null ? null : "Operation failed. Retry when the underlying service is available.")
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).completedAt(job.getCompletedAt()).steps(steps).build();
    }

    private OperationStepResponse toStepResponse(OperationStep step) {
        return OperationStepResponse.builder().id(step.getId()).sequenceNumber(step.getSequenceNumber())
                .stepKey(step.getStepKey()).stepType(step.getStepType()).status(step.getStatus())
                .attemptCount(step.getAttemptCount())
                .lastError(step.getLastError() == null ? null : "Operation step failed.")
                .startedAt(step.getStartedAt()).completedAt(step.getCompletedAt()).nextAttemptAt(step.getNextAttemptAt()).build();
    }

    private String normalizeKey(String key) { return key == null ? "" : key.trim(); }

    private void validateCreate(OperationType type, String aggregateType, UUID aggregateId, String key, String createdBy) {
        if (type == null || aggregateType == null || aggregateType.isBlank() || aggregateId == null || key.isEmpty()
                || createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("Operation job requires type, aggregate, idempotency key, and creator");
    }
}
