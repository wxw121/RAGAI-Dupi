package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable validate-prepare-store-publish workflow for one Markdown package. */
@Service
@RequiredArgsConstructor
public class MarkdownPackageImportWorkflow implements OperationWorkflow {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final OperationJobService operations;
    private final OperationJobClaimService claims;
    private final MinioStorageService storage;
    private final MarkdownImportPersistenceService persistence;

    @Override public OperationType type() { return OperationType.MARKDOWN_PACKAGE_IMPORT; }

    @Override
    public OperationCompletionMode completionMode(OperationExecutionContext context) {
        return context.phase() == OperationPhase.FORWARD
                ? OperationCompletionMode.DOMAIN_TRANSACTION : OperationCompletionMode.RUNNER;
    }

    @Override
    public void executeForward(OperationExecutionContext context) {
        MarkdownImportPlan plan = plan(context);
        try {
            prepare(context, plan);
            for (MarkdownImportPlan.MarkdownDocument document : plan.documents()) {
                store(context, "store-document-" + digest(document.path()), "STORE_DOCUMENT",
                        document.objectKey(), document.stagingKey(), document.byteSize(), document.sha256(), "text/markdown");
                for (MarkdownImportPlan.Asset asset : document.assets()) {
                    store(context, "store-asset-" + digest(document.path() + "\u0000" + asset.reference()), "STORE_ASSET",
                            asset.objectKey(), asset.stagingKey(), asset.byteSize(), asset.sha256(), asset.mimeType());
                }
            }
            publish(context, plan);
        } catch (RetryableOperationException | CompensateOperationException known) {
            throw known;
        } catch (DataAccessException unavailable) {
            throw new RetryableOperationException("Markdown import database is temporarily unavailable", unavailable);
        } catch (MarkdownImportInvariantException conflict) {
            throw new CompensateOperationException("Markdown import invariant conflict: " + conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new CompensateOperationException("Markdown import plan is invalid: " + invalid.getMessage());
        } catch (RuntimeException unavailable) {
            throw new RetryableOperationException("Markdown import storage is temporarily unavailable", unavailable);
        }
    }

    @Override
    public void executeCompensation(OperationExecutionContext context) {
        MarkdownImportPlan plan = plan(context);
        try {
            List<StoredTarget> targets = new ArrayList<>();
            for (MarkdownImportPlan.MarkdownDocument document : plan.documents()) {
                targets.add(new StoredTarget("store-document-" + digest(document.path()), document.objectKey()));
                for (MarkdownImportPlan.Asset asset : document.assets()) {
                    targets.add(new StoredTarget("store-asset-" + digest(document.path() + "\u0000" + asset.reference()),
                            asset.objectKey()));
                }
            }
            Collections.reverse(targets);
            for (StoredTarget target : targets) {
                OperationStep original = steps.findByJobIdAndStepKey(context.jobId(), target.stepKey()).orElse(null);
                if (original != null && original.getStatus() != OperationStepStatus.PENDING
                        && original.getStatus() != OperationStepStatus.COMPENSATED) {
                    cleanupObject(context, "cleanup-" + target.stepKey(), target.objectKey());
                }
            }
            cleanupMetadata(context, plan);
            List<MarkdownImportPlan.Entry> staged = new ArrayList<>(plan.entries());
            Collections.reverse(staged);
            for (MarkdownImportPlan.Entry entry : staged) {
                cleanupObject(context, "cleanup-stage-" + digest(entry.path()), entry.stagingKey());
            }
            for (OperationStep step : steps.findByJobIdOrderBySequenceNumberAsc(context.jobId())) {
                if (!step.getStepKey().startsWith("cleanup-")
                        && step.getStatus() != OperationStepStatus.COMPENSATED) {
                    operations.compensateStep(context, step.getStepKey());
                }
            }
        } catch (DataAccessException | IllegalStateException unavailable) {
            throw new RetryableOperationException("Markdown compensation dependency is temporarily unavailable", unavailable);
        }
    }

    private void prepare(OperationExecutionContext context, MarkdownImportPlan plan) {
        String key = "prepare-metadata";
        OperationStep step = operations.recordStep(context, key, "PREPARE_METADATA", "documents:" + context.jobId());
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        start(context, key, step);
        persistence.prepare(context, plan, key);
    }

    private void store(OperationExecutionContext context, String stepKey, String stepType, String objectKey,
                       String stagingKey, long byteSize, String sha256, String mimeType) {
        OperationStep step = operations.recordStep(context, stepKey, stepType, objectKey);
        MinioStorageService.ObjectInspection inspection = inspect(objectKey, byteSize, sha256);
        if (inspection != null && inspection.state() == MinioStorageService.ObjectState.MATCHING) {
            if (step.getStatus() != OperationStepStatus.COMPLETED) {
                start(context, stepKey, step);
                operations.completeStep(context, stepKey);
            }
            return;
        }
        if (inspection != null && inspection.state() == MinioStorageService.ObjectState.CONFLICT) {
            throw new MarkdownImportInvariantException("Deterministic Markdown object contains different bytes: " + objectKey);
        }
        if (step.getStatus() == OperationStepStatus.COMPLETED) {
            throw new MarkdownImportInvariantException("Completed Markdown object is missing: " + objectKey);
        }
        start(context, stepKey, step);
        persistence.assertActive(context);
        claims.renewLease(context);
        try (InputStream input = storage.download(stagingKey)) {
            storage.uploadIfAbsent(objectKey, input, byteSize, mimeType);
        } catch (java.io.IOException closed) {
            throw new RetryableOperationException("Unable to close Markdown staged object", closed);
        }
        claims.renewLease(context);
        persistence.assertActive(context);
        MinioStorageService.ObjectInspection stored = inspect(objectKey, byteSize, sha256);
        if (stored != null && stored.state() == MinioStorageService.ObjectState.CONFLICT) {
            throw new MarkdownImportInvariantException("Stored Markdown object differs from its immutable plan: " + objectKey);
        }
        if (stored != null && stored.state() == MinioStorageService.ObjectState.ABSENT) {
            throw new RetryableOperationException("Stored Markdown object is not yet visible: " + objectKey);
        }
        operations.completeStep(context, stepKey);
    }

    private MinioStorageService.ObjectInspection inspect(String key, long size, String sha) {
        return storage.inspect(key, size, sha);
    }

    private void publish(OperationExecutionContext context, MarkdownImportPlan plan) {
        String key = "publish";
        OperationStep step = operations.recordStep(context, key, "PUBLISH", "documents:" + context.jobId());
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        start(context, key, step);
        persistence.publish(context, plan, key);
    }

    private void cleanupObject(OperationExecutionContext context, String key, String objectKey) {
        OperationStep step = operations.recordStep(context, key, "CLEANUP_OBJECT", objectKey);
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        start(context, key, step);
        persistence.assertActive(context);
        storage.deleteChecked(objectKey);
        persistence.assertActive(context);
        operations.completeStep(context, key);
    }

    private void cleanupMetadata(OperationExecutionContext context, MarkdownImportPlan plan) {
        String key = "cleanup-metadata";
        OperationStep step = operations.recordStep(context, key, "CLEANUP_METADATA", "documents:" + context.jobId());
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        start(context, key, step);
        persistence.compensateMetadata(context, plan, key);
    }

    private void start(OperationExecutionContext context, String key, OperationStep step) {
        if (step.getStatus() == OperationStepStatus.PENDING || step.getStatus() == OperationStepStatus.RETRY_WAIT) {
            operations.startStep(context, key);
        }
    }

    private MarkdownImportPlan plan(OperationExecutionContext context) {
        return MarkdownImportPlan.fromInput(jobs.findById(context.jobId())
                .orElseThrow(() -> new MarkdownImportInvariantException("Markdown import job is missing")).getInput());
    }

    private String digest(String value) {
        return MarkdownImportPlan.deterministicId(new UUID(0, 0), "step", value).toString();
    }

    private record StoredTarget(String stepKey, String objectKey) { }
}

class MarkdownImportInvariantException extends RuntimeException {
    MarkdownImportInvariantException(String message) { super(message); }
}
