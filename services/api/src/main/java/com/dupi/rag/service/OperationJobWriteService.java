package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
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
    OperationStep insertStep(UUID jobId, String key, String type, String resourceRef) {
        jobs.findByIdForUpdate(jobId).orElseThrow();
        return steps.findByJobIdAndStepKey(jobId, key).orElseGet(() -> steps.saveAndFlush(OperationStep.builder()
                .jobId(jobId).sequenceNumber(steps.findByJobIdOrderBySequenceNumberAsc(jobId).size() + 1)
                .stepKey(key).stepType(type).status(OperationStepStatus.PENDING).resourceRef(resourceRef)
                .attemptCount(0).nextAttemptAt(Instant.now()).build()));
    }
}
