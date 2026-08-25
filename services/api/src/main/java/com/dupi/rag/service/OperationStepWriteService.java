package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Parent fencing and each step write/flush share one proxied transaction. */
@Service
@RequiredArgsConstructor
public class OperationStepWriteService {
    private final OperationStepRepository steps;
    private final OperationDomainGuard guard;

    @Transactional
    public OperationStep recordStep(OperationExecutionContext context, String stepKey,
                                    String stepType, String resourceRef) {
        guard.assertActive(context);
        String key = normalize(stepKey);
        if (key.isEmpty() || stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("Operation step requires a key and type");
        }
        return steps.findByJobIdAndStepKey(context.jobId(), key).orElseGet(() ->
                steps.saveAndFlush(OperationStep.builder()
                        .jobId(context.jobId())
                        .sequenceNumber(steps.findByJobIdOrderBySequenceNumberAsc(context.jobId()).size() + 1)
                        .stepKey(key)
                        .stepType(stepType.trim())
                        .status(OperationStepStatus.PENDING)
                        .resourceRef(resourceRef)
                        .attemptCount(0)
                        .retryEpoch(context.retryEpoch())
                        .nextAttemptAt(Instant.now())
                        .build()));
    }

    @Transactional
    public OperationStep startStep(OperationExecutionContext context, String stepKey) {
        guard.assertActive(context);
        OperationStep step = step(context, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPLETED || step.getStatus() == OperationStepStatus.COMPENSATED) {
            return step;
        }
        require(step, OperationStepStatus.PENDING, OperationStepStatus.RETRY_WAIT);
        step.setStatus(OperationStepStatus.RUNNING);
        step.setAttemptCount((step.getAttemptCount() == null ? 0 : step.getAttemptCount()) + 1);
        step.setStartedAt(Instant.now());
        step.setCompletedAt(null);
        step.setLastError(null);
        step.setNextAttemptAt(null);
        return steps.saveAndFlush(step);
    }

    @Transactional
    public OperationStep retryStep(OperationExecutionContext context, String stepKey,
                                   String diagnostic, Instant nextAttemptAt) {
        guard.assertActive(context);
        OperationStep step = step(context, stepKey);
        require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.RETRY_WAIT);
        step.setStartedAt(null);
        step.setCompletedAt(null);
        step.setLastError(diagnostic);
        step.setNextAttemptAt(nextAttemptAt);
        return steps.saveAndFlush(step);
    }

    @Transactional
    public OperationStep failStep(OperationExecutionContext context, String stepKey, String diagnostic) {
        guard.assertActive(context);
        OperationStep step = step(context, stepKey);
        require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.FAILED);
        step.setLastError(diagnostic);
        step.setCompletedAt(Instant.now());
        step.setNextAttemptAt(null);
        return steps.saveAndFlush(step);
    }

    @Transactional
    public void completeStep(OperationExecutionContext context, String stepKey) {
        guard.assertActive(context);
        OperationStep step = step(context, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPLETED) {
            return;
        }
        require(step, OperationStepStatus.RUNNING);
        step.setStatus(OperationStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        step.setLastError(null);
        step.setNextAttemptAt(null);
        steps.saveAndFlush(step);
    }

    @Transactional
    public OperationStep compensateStep(OperationExecutionContext context, String stepKey) {
        guard.assertActive(context);
        OperationStep step = step(context, stepKey);
        if (step.getStatus() == OperationStepStatus.COMPENSATED) {
            return step;
        }
        require(step, OperationStepStatus.PENDING, OperationStepStatus.RUNNING,
                OperationStepStatus.RETRY_WAIT, OperationStepStatus.COMPLETED,
                OperationStepStatus.FAILED);
        step.setStatus(OperationStepStatus.COMPENSATED);
        step.setCompletedAt(Instant.now());
        step.setNextAttemptAt(null);
        return steps.saveAndFlush(step);
    }

    private OperationStep step(OperationExecutionContext context, String key) {
        return steps.findByJobIdAndStepKey(context.jobId(), normalize(key))
                .orElseThrow(() -> new ResourceNotFoundException("Operation step not found: " + key));
    }

    private void require(OperationStep step, OperationStepStatus... allowed) {
        for (OperationStepStatus status : allowed) {
            if (step.getStatus() == status) {
                return;
            }
        }
        throw new OperationConflictException("Operation step cannot transition while " + step.getStatus());
    }

    private String normalize(String key) {
        return key == null ? "" : key.trim();
    }
}
