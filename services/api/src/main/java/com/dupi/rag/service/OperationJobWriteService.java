package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStepStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Isolates uniqueness violations so callers can reload a database winner in a clean transaction. */
@Service
@RequiredArgsConstructor
class OperationJobWriteService {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationJob insertJob(String tenant, OperationType type, String aggregateType, UUID aggregateId,
                            String key, Map<String, Object> input, String createdBy) {
        return jobs.saveAndFlush(OperationJob.builder().tenantId(tenant).operationType(type)
                .aggregateType(aggregateType).aggregateId(aggregateId).status(OperationStatus.PREPARED)
                .runnable(false).idempotencyKey(key).input(input).attemptCount(0)
                .nextAttemptAt(Instant.now()).createdBy(createdBy).build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationJob insertRecoveryImport(String tenant, RecoveryArchiveImportPlan plan, String key, String createdBy) {
        UUID jobId = UUID.randomUUID();
        OperationJob job = jobs.saveAndFlush(OperationJob.builder().id(jobId).tenantId(tenant)
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(plan.knowledgeBaseId()).status(OperationStatus.PREPARED).runnable(false)
                .idempotencyKey(key).input(plan.toInput()).attemptCount(0).nextAttemptAt(Instant.now())
                .createdBy(createdBy).build());
        steps.saveAndFlush(OperationStep.builder().jobId(jobId).sequenceNumber(1).stepKey("stage-zip")
                .stepType("STAGE_UPLOAD").resourceRef("recovery-staging/" + jobId + ".zip")
                .status(OperationStepStatus.PENDING).attemptCount(0).nextAttemptAt(Instant.now()).build());
        return job;
    }
}
