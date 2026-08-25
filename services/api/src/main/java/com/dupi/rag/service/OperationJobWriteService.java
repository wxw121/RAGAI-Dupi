package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Isolates uniqueness violations so callers can reload a database winner in a clean transaction. */
@Service
class OperationJobWriteService {
    private final OperationJobRepository jobs;
    private final AuditLogService audit;

    @Autowired
    OperationJobWriteService(OperationJobRepository jobs, AuditLogService audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    OperationJobWriteService(OperationJobRepository jobs) {
        this(jobs, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationJob insertJob(String tenant, OperationType type, String aggregateType, UUID aggregateId,
                            String key, Map<String, Object> input, String createdBy) {
        OperationJob job = jobs.saveAndFlush(OperationJob.builder().tenantId(tenant).operationType(type)
                .aggregateType(aggregateType).aggregateId(aggregateId).status(OperationStatus.PREPARED)
                .runnable(false).idempotencyKey(key).input(input).attemptCount(0)
                .nextAttemptAt(Instant.now()).createdBy(createdBy).build());
        if (audit != null) {
            audit.recordOperationInCurrentTransaction(
                    tenant, AuditLogService.OPERATION_SUBMIT, job.getId(), "Operation submitted");
        }
        return job;
    }

}
