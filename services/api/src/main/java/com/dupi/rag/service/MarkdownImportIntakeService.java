package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Markdown-domain create/resume/stage/publish orchestration. */
@Service
@RequiredArgsConstructor
public class MarkdownImportIntakeService {
    private final OperationJobRepository jobs;
    private final MarkdownImportIntakeWriteService writes;
    private final MinioStorageService storage;

    public static UUID jobId(String tenant, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(("markdown-import:" + tenant + ":" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    public OperationJobResponse submit(MarkdownImportPlan plan, String idempotencyKey, String createdBy) {
        validate(plan, idempotencyKey, createdBy);
        String tenant = TenantContext.getTenantId();
        String key = idempotencyKey.trim();
        OperationJob job = jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                tenant, OperationType.MARKDOWN_PACKAGE_IMPORT, key).orElse(null);
        if (job == null) {
            try { job = writes.insert(tenant, key, createdBy.trim(), plan); }
            catch (DataIntegrityViolationException race) {
                job = jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                        tenant, OperationType.MARKDOWN_PACKAGE_IMPORT, key).orElseThrow(() -> race);
            }
        }
        writes.validatePlan(job, plan);
        if (Boolean.TRUE.equals(job.getRunnable()) || job.getStatus() == OperationStatus.COMPLETED) return response(job);
        try {
            for (MarkdownImportPlan.Entry entry : plan.entries()) stage(plan, entry);
            return response(writes.publishRunnable(job.getId(), plan));
        } catch (OperationConflictException conflict) {
            writes.scheduleCleanup(job.getId(), plan, conflict.getMessage());
            OperationJob winner = jobs.findById(job.getId()).orElse(job);
            if (Boolean.TRUE.equals(winner.getRunnable()) || winner.getStatus() == OperationStatus.COMPLETED) {
                return response(winner);
            }
            throw conflict;
        } catch (RuntimeException unavailable) {
            writes.scheduleCleanup(job.getId(), plan, unavailable.getMessage());
            OperationJob winner = jobs.findById(job.getId()).orElse(job);
            if (Boolean.TRUE.equals(winner.getRunnable()) || winner.getStatus() == OperationStatus.COMPLETED) {
                return response(winner);
            }
            throw unavailable;
        }
    }

    private void stage(MarkdownImportPlan plan, MarkdownImportPlan.Entry entry) {
        MinioStorageService.ObjectInspection inspection = storage.inspect(
                entry.stagingKey(), entry.byteSize(), entry.sha256());
        if (inspection != null && inspection.state() == MinioStorageService.ObjectState.CONFLICT) {
            throw new OperationConflictException("Markdown staging key contains different bytes: " + entry.path());
        }
        if (inspection == null || inspection.state() == MinioStorageService.ObjectState.ABSENT) {
            byte[] content = entry.content();
            if (content == null) throw new OperationConflictException("Markdown replay is missing staged input bytes");
            storage.uploadIfAbsent(entry.stagingKey(), new ByteArrayInputStream(content), entry.byteSize(), entry.mimeType());
            MinioStorageService.ObjectInspection stored = storage.inspect(entry.stagingKey(), entry.byteSize(), entry.sha256());
            if (stored != null && stored.state() != MinioStorageService.ObjectState.MATCHING) {
                throw new OperationConflictException("Markdown staged object differs from its immutable plan: " + entry.path());
            }
        }
        writes.completeStage(plan.jobId(), plan, entry);
    }

    private void validate(MarkdownImportPlan plan, String key, String createdBy) {
        if (plan == null || key == null || key.isBlank() || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Markdown import requires plan, idempotency key, and creator");
        }
        UUID expected = jobId(TenantContext.getTenantId(), key.trim());
        if (!expected.equals(plan.jobId())) throw new OperationConflictException("Markdown import job ID does not match its key");
    }

    private OperationJobResponse response(OperationJob job) {
        return OperationJobResponse.builder().id(job.getId()).operationType(job.getOperationType())
                .aggregateType(job.getAggregateType()).aggregateId(job.getAggregateId()).status(job.getStatus())
                .attemptCount(job.getAttemptCount()).nextAttemptAt(job.getNextAttemptAt())
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).completedAt(job.getCompletedAt())
                .steps(List.of()).build();
    }
}
