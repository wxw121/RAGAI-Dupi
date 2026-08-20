package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Markdown-owned transactions that keep intake non-runnable until every staged entry is durable. */
@Service
@RequiredArgsConstructor
class MarkdownImportIntakeWriteService {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationJob insert(String tenant, String key, String createdBy, MarkdownImportPlan plan) {
        OperationJob job = jobs.saveAndFlush(OperationJob.builder().id(plan.jobId()).tenantId(tenant)
                .operationType(OperationType.MARKDOWN_PACKAGE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(plan.knowledgeBaseId()).status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(false).idempotencyKey(key).input(plan.toInput()).attemptCount(0)
                .nextAttemptAt(Instant.now()).createdBy(createdBy).build());
        int sequence = 1;
        for (MarkdownImportPlan.Entry entry : plan.entries()) {
            steps.save(OperationStep.builder().jobId(job.getId()).sequenceNumber(sequence++)
                    .stepKey(stageStep(entry)).stepType("STAGE_OBJECT").resourceRef(entry.stagingKey())
                    .status(OperationStepStatus.PENDING).attemptCount(0).nextAttemptAt(Instant.now()).build());
        }
        steps.flush();
        return job;
    }

    @Transactional
    void completeStage(java.util.UUID jobId, MarkdownImportPlan plan, MarkdownImportPlan.Entry entry) {
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        OperationStep step = required(jobId, stageStep(entry));
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        requireWritable(job);
        if (step.getStatus() != OperationStepStatus.PENDING && step.getStatus() != OperationStepStatus.RUNNING) {
            throw new OperationConflictException("Markdown import stage cannot complete while " + step.getStatus());
        }
        step.setStatus(OperationStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now()); step.setNextAttemptAt(null); step.setLastError(null);
        steps.saveAndFlush(step);
    }

    @Transactional
    OperationJob publishRunnable(java.util.UUID jobId, MarkdownImportPlan plan) {
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        if (Boolean.TRUE.equals(job.getRunnable())) return job;
        requireWritable(job);
        boolean incomplete = plan.entries().stream().map(entry -> required(jobId, stageStep(entry)))
                .anyMatch(step -> step.getStatus() != OperationStepStatus.COMPLETED);
        if (incomplete) throw new OperationConflictException("Markdown import staging is incomplete");
        job.setRunnable(true); job.setNextAttemptAt(Instant.now());
        return jobs.saveAndFlush(job);
    }

    @Transactional
    void scheduleCleanup(java.util.UUID jobId, MarkdownImportPlan plan, String diagnostic) {
        OperationJob job = locked(jobId);
        validatePlan(job, plan);
        if (Boolean.TRUE.equals(job.getRunnable())) return;
        job.setPhase(OperationPhase.COMPENSATION); job.setStatus(OperationStatus.COMPENSATING);
        job.setRunnable(true); job.setLastError(limit(diagnostic)); job.setNextAttemptAt(Instant.now());
        jobs.saveAndFlush(job);
    }

    void validatePlan(OperationJob job, MarkdownImportPlan plan) {
        MarkdownImportPlan stored;
        try { stored = MarkdownImportPlan.fromInput(job.getInput()); }
        catch (RuntimeException invalid) {
            throw new OperationConflictException("Markdown import idempotency key has an invalid immutable plan");
        }
        if (job.getOperationType() != OperationType.MARKDOWN_PACKAGE_IMPORT
                || !"KNOWLEDGE_BASE".equals(job.getAggregateType())
                || !plan.toInput().equals(stored.toInput())) {
            throw new OperationConflictException("Markdown import idempotency key already belongs to different input");
        }
    }

    static String stageStep(MarkdownImportPlan.Entry entry) {
        return "stage-" + java.util.UUID.nameUUIDFromBytes(entry.path().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private OperationJob locked(java.util.UUID id) {
        return jobs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + id));
    }
    private OperationStep required(java.util.UUID id, String key) {
        return steps.findByJobIdAndStepKey(id, key)
                .orElseThrow(() -> new OperationConflictException("Markdown import intake is missing step " + key));
    }
    private void requireWritable(OperationJob job) {
        if (job.getStatus() != OperationStatus.PREPARED || job.getPhase() != OperationPhase.FORWARD
                || Boolean.TRUE.equals(job.getRunnable())) {
            throw new OperationConflictException("Markdown import intake is no longer writable");
        }
    }
    private String limit(String value) {
        String text = value == null || value.isBlank() ? "Markdown staging failed" : value;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
