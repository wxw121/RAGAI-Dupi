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

    /** Creates the immutable Recovery intake and its required stage step in one transaction. */
    public RecoveryImportIntake createOrResumeRecoveryImport(
            RecoveryArchiveImportPlan plan, String idempotencyKey, String createdBy) {
        if (plan == null) throw new IllegalArgumentException("Recovery import plan is required");
        String tenant = TenantContext.getTenantId();
        String key = normalizeKey(idempotencyKey);
        validateCreate(OperationType.RECOVERY_ARCHIVE_IMPORT, "KNOWLEDGE_BASE",
                plan.knowledgeBaseId(), key, createdBy);
        if (!tenant.equals(plan.tenantId())) {
            throw new OperationConflictException("Recovery import tenant does not match the active tenant");
        }
        operationJobRepository.findFirstByOperationTypeAndIdempotencyKeyOrderByCreatedAtAsc(
                        OperationType.RECOVERY_ARCHIVE_IMPORT, key)
                .filter(job -> !tenant.equals(job.getTenantId()))
                .ifPresent(job -> { throw new OperationConflictException(
                        "Recovery import idempotency key already belongs to another tenant"); });
        var existing = operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(
                tenant, OperationType.RECOVERY_ARCHIVE_IMPORT, key);
        if (existing.isPresent()) return recoveryIntake(existing.get(), plan);
        try {
            return recoveryIntake(writeService.insertRecoveryImport(tenant, plan, key, createdBy.trim()), plan);
        } catch (DataIntegrityViolationException conflict) {
            OperationJob winner = operationJobRepository.findByTenantIdAndOperationTypeAndIdempotencyKey(
                            tenant, OperationType.RECOVERY_ARCHIVE_IMPORT, key)
                    .orElseThrow(() -> conflict);
            return recoveryIntake(winner, plan);
        }
    }

    @Transactional
    public void completeRecoveryImportStage(UUID jobId, RecoveryArchiveImportPlan plan) {
        OperationJob job = operationJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        validateRecoveryPlan(job, plan);
        if (job.getStatus() != OperationStatus.PREPARED || Boolean.TRUE.equals(job.getRunnable())) {
            throw new OperationConflictException("Recovery import intake is no longer writable");
        }
        OperationStep step = requiredStage(jobId);
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        if (step.getStatus() != OperationStepStatus.PENDING) {
            throw new OperationConflictException("Recovery import stage cannot complete while " + step.getStatus());
        }
        step.setStatus(OperationStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        step.setNextAttemptAt(null);
        operationStepRepository.saveAndFlush(step);
    }

    /** The only Recovery import publication gate; the caller has already verified staging bytes. */
    @Transactional
    public OperationJobResponse publishRecoveryImport(UUID jobId, RecoveryArchiveImportPlan plan) {
        OperationJob job = operationJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        validateRecoveryPlan(job, plan);
        if (Boolean.TRUE.equals(job.getRunnable())) return toResponse(job);
        if (job.getStatus() != OperationStatus.PREPARED) {
            throw new OperationConflictException("Only prepared Recovery import intake can become runnable");
        }
        OperationStep stage = requiredStage(jobId);
        if (stage.getStatus() != OperationStepStatus.COMPLETED
                || !("recovery-staging/" + jobId + ".zip").equals(stage.getResourceRef())) {
            throw new OperationConflictException("Recovery import staging is not complete");
        }
        job.setRunnable(true);
        job.setNextAttemptAt(Instant.now());
        return toResponse(operationJobRepository.saveAndFlush(job));
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

    private RecoveryImportIntake recoveryIntake(OperationJob job, RecoveryArchiveImportPlan plan) {
        validateRecoveryPlan(job, plan);
        OperationStep stage = requiredStage(job.getId());
        return new RecoveryImportIntake(toResponse(job), stage.getStatus(), Boolean.TRUE.equals(job.getRunnable()));
    }

    private OperationStep requiredStage(UUID jobId) {
        return operationStepRepository.findByJobIdAndStepKey(jobId, "stage-zip")
                .orElseThrow(() -> new OperationConflictException("Recovery import intake is missing its required stage step"));
    }

    private void validateRecoveryPlan(OperationJob job, RecoveryArchiveImportPlan plan) {
        RecoveryArchiveImportPlan stored;
        try {
            stored = RecoveryArchiveImportPlan.fromInput(job.getInput());
        } catch (RuntimeException invalid) {
            throw new OperationConflictException("Recovery import idempotency key has an invalid immutable plan");
        }
        if (job.getOperationType() != OperationType.RECOVERY_ARCHIVE_IMPORT
                || !"KNOWLEDGE_BASE".equals(job.getAggregateType())
                || !plan.tenantId().equals(job.getTenantId())
                || !plan.knowledgeBaseId().equals(job.getAggregateId())
                || !sameImmutableRecoveryPlan(plan, stored)) {
            throw new OperationConflictException("Recovery import idempotency key already belongs to different input");
        }
    }

    private boolean sameImmutableRecoveryPlan(RecoveryArchiveImportPlan requested,
                                               RecoveryArchiveImportPlan stored) {
        return java.util.Objects.equals(requested.sourceArchiveId(), stored.sourceArchiveId())
                && java.util.Objects.equals(requested.tenantId(), stored.tenantId())
                && java.util.Objects.equals(requested.knowledgeBaseId(), stored.knowledgeBaseId())
                && java.util.Objects.equals(requested.sourceRevision(), stored.sourceRevision())
                && java.util.Objects.equals(requested.embeddingModel(), stored.embeddingModel())
                && requested.embeddingDimension() == stored.embeddingDimension()
                && java.util.Objects.equals(requested.collectionSettings(), stored.collectionSettings())
                && java.util.Objects.equals(requested.sourceManifestChecksum(), stored.sourceManifestChecksum())
                && java.util.Objects.equals(requested.zipSha256(), stored.zipSha256())
                && java.util.Objects.equals(requested.entries(), stored.entries());
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
