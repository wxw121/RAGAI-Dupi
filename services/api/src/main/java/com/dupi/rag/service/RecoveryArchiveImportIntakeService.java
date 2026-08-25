package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Recovery-domain create/resume/stage/publish orchestration. */
@Service
public class RecoveryArchiveImportIntakeService {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final RecoveryArchiveImportIntakeWriteService writes;
    private final RecoveryStorageService storage;
    private final OperationStagingLeaseCoordinator stagingLeases;

    @org.springframework.beans.factory.annotation.Autowired
    public RecoveryArchiveImportIntakeService(OperationJobRepository jobs, OperationStepRepository steps,
            RecoveryArchiveImportIntakeWriteService writes, RecoveryStorageService storage,
            OperationStagingLeaseCoordinator stagingLeases) {
        this.jobs = jobs; this.steps = steps; this.writes = writes; this.storage = storage;
        this.stagingLeases = stagingLeases;
    }

    RecoveryArchiveImportIntakeService(OperationJobRepository jobs, OperationStepRepository steps,
            RecoveryArchiveImportIntakeWriteService writes, RecoveryStorageService storage) {
        this(jobs, steps, writes, storage, null);
    }

    public RecoveryImportIntake createOrResume(RecoveryArchiveImportPlan plan, String idempotencyKey,
                                                String createdBy) {
        validateRequest(plan, idempotencyKey, createdBy);
        String tenant = TenantContext.getTenantId();
        String key = idempotencyKey.trim();
        var existing = jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                tenant, OperationType.RECOVERY_ARCHIVE_IMPORT, key);
        if (existing.isPresent()) return acquireIfWritable(resume(existing.get(), plan));
        try {
            OperationJob inserted = writes.insert(tenant, plan, key, createdBy.trim(), null);
            return acquireIfWritable(intake(inserted, plan));
        } catch (DataIntegrityViolationException conflict) {
            OperationJob winner = jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                            tenant, OperationType.RECOVERY_ARCHIVE_IMPORT, key)
                    .orElseThrow(() -> conflict);
            return acquireIfWritable(resume(winner, plan));
        }
    }

    public OperationJobResponse completeStageAndPublish(java.util.UUID jobId,
                                                         RecoveryArchiveImportPlan plan,
                                                         StoredRecoveryObject evidence) {
        return writes.completeStageAndPublish(jobId, plan, evidence);
    }

    public OperationJobResponse completeStageAndPublish(java.util.UUID jobId,
            RecoveryArchiveImportPlan plan, StoredRecoveryObject evidence,
            OperationStagingLease lease) {
        return writes.completeStageAndPublish(jobId, plan, evidence, lease);
    }

    public void scheduleCleanup(java.util.UUID jobId, RecoveryArchiveImportPlan plan, Throwable failure) {
        writes.scheduleCleanup(jobId, plan,
                failure == null ? "Recovery stage upload failed" : failure.getMessage());
    }

    private RecoveryImportIntake resume(OperationJob job, RecoveryArchiveImportPlan plan) {
        writes.validatePlan(job, plan);
        OperationStep stage = requiredStage(job);
        if (job.getPhase() == OperationPhase.COMPENSATION && job.getStatus() == OperationStatus.COMPLETED
                && stage.getStatus() == OperationStepStatus.COMPENSATED) {
            String key = storage.stagingKey(job.getId(), plan.zipSha256());
            writes.reopenCleanedIntake(job.getId(), plan, key);
            job = jobs.findById(job.getId()).orElse(job);
        }
        return intake(job, plan);
    }

    private RecoveryImportIntake intake(OperationJob job, RecoveryArchiveImportPlan plan) {
        writes.validatePlan(job, plan);
        OperationStep stage = requiredStage(job);
        StoredRecoveryObject evidence = stage.getStatus() == OperationStepStatus.COMPLETED
                ? RecoveryStageEvidence.decode(stage.getResourceRef()) : null;
        return new RecoveryImportIntake(response(job), stage.getStatus(),
                Boolean.TRUE.equals(job.getRunnable()) && stage.getStatus() == OperationStepStatus.COMPLETED,
                evidence, job.getPhase() == OperationPhase.COMPENSATION, null);
    }

    private RecoveryImportIntake acquireIfWritable(RecoveryImportIntake intake) {
        if (stagingLeases == null || intake.published() || intake.cleanupPending()) return intake;
        OperationStagingLease lease = stagingLeases.acquire(intake.job().getId());
        return new RecoveryImportIntake(intake.job(), intake.stageStatus(), intake.published(),
                intake.stageObject(), intake.cleanupPending(), lease);
    }

    private OperationStep requiredStage(OperationJob job) {
        return steps.findByJobIdAndStepKey(job.getId(), RecoveryArchiveImportIntakeWriteService.STAGE_STEP)
                .orElseThrow(() -> new OperationConflictException(
                        "Recovery import intake is missing its required stage step"));
    }

    private void validateRequest(RecoveryArchiveImportPlan plan, String key, String createdBy) {
        if (plan == null || key == null || key.isBlank() || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Recovery import requires plan, idempotency key, and creator");
        }
        if (!TenantContext.getTenantId().equals(plan.tenantId())) {
            throw new OperationConflictException("Recovery import tenant does not match the active tenant");
        }
    }

    private OperationJobResponse response(OperationJob job) {
        return OperationJobResponse.builder().id(job.getId()).operationType(job.getOperationType())
                .aggregateType(job.getAggregateType()).aggregateId(job.getAggregateId()).status(job.getStatus())
                .attemptCount(job.getAttemptCount()).nextAttemptAt(job.getNextAttemptAt())
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).completedAt(job.getCompletedAt())
                .steps(List.of()).build();
    }
}
