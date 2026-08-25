package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Recovery-only transactional boundary for immutable intake lifecycle transitions. */
@Service
class RecoveryArchiveImportIntakeWriteService {
    static final String STAGE_STEP = "stage-zip";
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final KnowledgeBaseRepository knowledgeBases;
    private final AuditLogService audit;

    @Autowired
    RecoveryArchiveImportIntakeWriteService(OperationJobRepository jobs, OperationStepRepository steps,
                                            KnowledgeBaseRepository knowledgeBases, AuditLogService audit) {
        this.jobs = jobs;
        this.steps = steps;
        this.knowledgeBases = knowledgeBases;
        this.audit = audit;
    }

    RecoveryArchiveImportIntakeWriteService(OperationJobRepository jobs, OperationStepRepository steps,
                                            KnowledgeBaseRepository knowledgeBases) {
        this(jobs, steps, knowledgeBases, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationJob insert(String tenant, RecoveryArchiveImportPlan plan, String key,
                        String createdBy, String stagingKey) {
        var knowledgeBase = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(plan.knowledgeBaseId(), tenant)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + plan.knowledgeBaseId()));
        KnowledgeBaseLifecyclePolicy.requireReady(knowledgeBase, plan.knowledgeBaseId());
        UUID jobId = UUID.randomUUID();
        String actualStagingKey = stagingKey == null
                ? "recovery-staging/" + plan.zipSha256() + "/" + jobId + ".zip"
                : stagingKey;
        OperationJob job = jobs.saveAndFlush(OperationJob.builder().id(jobId).tenantId(tenant)
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(plan.knowledgeBaseId()).status(OperationStatus.PREPARED)
                .phase(OperationPhase.FORWARD).runnable(false).idempotencyKey(key)
                .input(plan.toInput()).attemptCount(0).nextAttemptAt(Instant.now())
                .createdBy(createdBy).build());
        steps.saveAndFlush(OperationStep.builder().jobId(jobId).sequenceNumber(1).stepKey(STAGE_STEP)
                .stepType("STAGE_UPLOAD").resourceRef(actualStagingKey).status(OperationStepStatus.PENDING)
                .attemptCount(0).nextAttemptAt(Instant.now()).build());
        audit(job, AuditLogService.OPERATION_SUBMIT, "Operation submitted");
        return job;
    }

    /**
     * Object storage cannot share this database transaction. The caller supplies evidence from the
     * content-addressed key; this transaction persists its version token, and the worker rechecks it.
     */
    @Transactional
    OperationJobResponse completeStageAndPublish(UUID jobId, RecoveryArchiveImportPlan plan,
                                                  StoredRecoveryObject evidence) {
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        validateEvidence(jobId, plan, evidence);
        OperationStep stage = requiredStage(jobId);
        if (stage.getStatus() == OperationStepStatus.COMPLETED) {
            StoredRecoveryObject recorded = RecoveryStageEvidence.decode(stage.getResourceRef());
            if (!recorded.equals(evidence)) {
                throw new OperationConflictException("Recovery import stage version changed before publication");
            }
            if (Boolean.TRUE.equals(job.getRunnable())) return response(job);
        } else {
            if (stage.getStatus() != OperationStepStatus.PENDING) {
                throw new OperationConflictException("Recovery import stage cannot complete while " + stage.getStatus());
            }
            if (job.getStatus() != OperationStatus.PREPARED || Boolean.TRUE.equals(job.getRunnable())
                    || job.getPhase() != OperationPhase.FORWARD) {
                throw new OperationConflictException("Recovery import intake is no longer writable");
            }
            stage.setStatus(OperationStepStatus.COMPLETED);
            stage.setResourceRef(RecoveryStageEvidence.encode(evidence));
            stage.setCompletedAt(Instant.now());
            stage.setNextAttemptAt(null);
            stage.setLastError(null);
            steps.saveAndFlush(stage);
        }
        if (job.getStatus() != OperationStatus.PREPARED || job.getPhase() != OperationPhase.FORWARD) {
            throw new OperationConflictException("Only prepared Recovery import intake can become runnable");
        }
        job.setRunnable(true);
        job.setNextAttemptAt(Instant.now());
        return response(jobs.saveAndFlush(job));
    }

    @Transactional
    void scheduleCleanup(UUID jobId, RecoveryArchiveImportPlan plan, String diagnostic) {
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        OperationStep stage = requiredStage(jobId);
        if (stage.getStatus() == OperationStepStatus.COMPLETED && Boolean.TRUE.equals(job.getRunnable())) return;
        stage.setStatus(OperationStepStatus.FAILED);
        stage.setLastError(limit(diagnostic));
        stage.setCompletedAt(Instant.now());
        stage.setNextAttemptAt(null);
        steps.saveAndFlush(stage);
        job.setPhase(OperationPhase.COMPENSATION);
        job.setStatus(OperationStatus.COMPENSATING);
        job.setRunnable(true);
        job.setLastError(limit(diagnostic));
        job.setNextAttemptAt(Instant.now());
        jobs.saveAndFlush(job);
        audit(job, AuditLogService.OPERATION_COMPENSATE, job.getLastError());
    }

    @Transactional
    RecoveryIntakeReopenOutcome reopenCleanedIntake(
            UUID jobId, RecoveryArchiveImportPlan plan, String stagingKey) {
        var knowledgeBase = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(plan.knowledgeBaseId(), plan.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + plan.knowledgeBaseId()));
        KnowledgeBaseLifecyclePolicy.requireReady(knowledgeBase, plan.knowledgeBaseId());
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        OperationStep stage = requiredStage(jobId);
        if (job.getPhase() == OperationPhase.FORWARD && job.getStatus() == OperationStatus.PREPARED
                && !Boolean.TRUE.equals(job.getRunnable())
                && stage.getStatus() == OperationStepStatus.PENDING
                && stagingKey.equals(stage.getResourceRef())) {
            return RecoveryIntakeReopenOutcome.JOINED;
        }
        if (job.getPhase() != OperationPhase.COMPENSATION || job.getStatus() != OperationStatus.COMPLETED
                || stage.getStatus() != OperationStepStatus.COMPENSATED) {
            throw new OperationConflictException("Recovery import cleanup has not completed");
        }
        job.setPhase(OperationPhase.FORWARD);
        job.setStatus(OperationStatus.PREPARED);
        job.setRunnable(false);
        job.setRetryEpoch(safe(job.getRetryEpoch()) + 1);
        job.setPhaseAttemptCount(0);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        job.setCompletedAt(null);
        job.setLastError(null);
        job.setNextAttemptAt(Instant.now());
        stage.setStatus(OperationStepStatus.PENDING);
        stage.setResourceRef(stagingKey);
        stage.setRetryEpoch(job.getRetryEpoch());
        stage.setStartedAt(null);
        stage.setCompletedAt(null);
        stage.setLastError(null);
        stage.setNextAttemptAt(Instant.now());
        steps.saveAndFlush(stage);
        for (OperationStep previous : steps.findByJobIdOrderBySequenceNumberAsc(jobId)) {
            if (STAGE_STEP.equals(previous.getStepKey())) continue;
            if (previous.getStatus() == OperationStepStatus.COMPENSATED
                    || previous.getStepKey().startsWith("cleanup-")
                    || "delete-staging".equals(previous.getStepKey())) {
                previous.setStatus(OperationStepStatus.PENDING);
                previous.setRetryEpoch(job.getRetryEpoch());
                previous.setStartedAt(null);
                previous.setCompletedAt(null);
                previous.setLastError(null);
                previous.setNextAttemptAt(Instant.now());
                steps.save(previous);
            }
        }
        jobs.saveAndFlush(job);
        audit(job, AuditLogService.OPERATION_RETRY, "Operation intake reopened");
        return RecoveryIntakeReopenOutcome.REOPENED;
    }

    void validatePlan(OperationJob job, RecoveryArchiveImportPlan plan) {
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
                || !plan.equals(stored)) {
            throw new OperationConflictException(
                    "Recovery import idempotency key already belongs to different input");
        }
    }

    private void validateEvidence(UUID jobId, RecoveryArchiveImportPlan plan, StoredRecoveryObject evidence) {
        String suffix = "/" + jobId + ".zip";
        if (evidence == null || evidence.versionToken() == null || evidence.versionToken().isBlank()
                || evidence.byteSize() < 0 || !plan.zipSha256().equals(evidence.sha256())
                || !evidence.objectKey().startsWith("recovery-staging/" + plan.zipSha256() + "/")
                || !evidence.objectKey().endsWith(suffix)) {
            throw new OperationConflictException("Recovery import stage evidence does not match its plan");
        }
    }

    private OperationJob locked(UUID jobId) {
        return jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
    }

    private OperationStep requiredStage(UUID jobId) {
        return steps.findByJobIdAndStepKey(jobId, STAGE_STEP)
                .orElseThrow(() -> new OperationConflictException(
                        "Recovery import intake is missing its required stage step"));
    }

    private OperationJobResponse response(OperationJob job) {
        return OperationJobResponse.builder().id(job.getId()).operationType(job.getOperationType())
                .aggregateType(job.getAggregateType()).aggregateId(job.getAggregateId()).status(job.getStatus())
                .attemptCount(job.getAttemptCount()).nextAttemptAt(job.getNextAttemptAt())
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).completedAt(job.getCompletedAt())
                .steps(java.util.List.of()).build();
    }

    private long safe(Long value) { return value == null ? 0L : value; }
    private String limit(String value) {
        if (value == null) return "Recovery stage upload failed";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
    private void audit(OperationJob job, String action, String message) {
        if (audit != null) {
            audit.recordOperationInCurrentTransaction(job.getTenantId(), action, job.getId(), message);
        }
    }
}
