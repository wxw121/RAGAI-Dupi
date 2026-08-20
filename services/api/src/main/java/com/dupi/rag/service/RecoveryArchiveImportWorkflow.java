package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Deterministic, retryable promotion and truthful durable compensation for Recovery imports. */
@Service
@RequiredArgsConstructor
public class RecoveryArchiveImportWorkflow implements OperationWorkflow {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final OperationJobService operations;
    private final OperationJobClaimService claims;
    private final RecoveryStorageService storage;
    private final RecoveryManifestService manifests;
    private final RecoveryArchiveImportPersistenceService persistence;

    @Override public OperationType type() { return OperationType.RECOVERY_ARCHIVE_IMPORT; }

    @Override
    public void executeForward(OperationExecutionContext context) {
        RecoveryArchiveImportPlan plan = plan(context);
        try {
            StoredRecoveryObject stageEvidence = stageEvidence(context);
            requireMatchingStage(context, stageEvidence);
            List<Promotion> missing = new ArrayList<>();
            for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) {
                preparePromotion(context, plan, entry, missing);
            }
            promoteMissing(context, plan, stageEvidence, missing);
            requireMatchingStage(context, stageEvidence);
            RecoveryManifest manifest = manifests.seal(plan.finalHeader(context.jobId()), plan.finalItems(context.jobId()));
            StoredRecoveryObject storedManifest = promoteManifest(context, plan, manifest);
            persist(context, plan, manifest, storedManifest);
            deleteStaging(context, plan, stageEvidence);
        } catch (RetryableOperationException exception) {
            throw exception;
        } catch (RecoveryStorageUnavailableException | DataAccessException exception) {
            throw new RetryableOperationException("Recovery import dependency is temporarily unavailable", exception);
        } catch (CompensateOperationException exception) {
            throw exception;
        } catch (RecoveryStorageConflictException | IllegalArgumentException exception) {
            throw new CompensateOperationException("Recovery archive import invariant conflict: " + exception.getMessage());
        } catch (IOException exception) {
            throw new CompensateOperationException("Recovery staged ZIP is invalid: " + exception.getMessage());
        }
    }

    @Override
    public void executeCompensation(OperationExecutionContext context) {
        RecoveryArchiveImportPlan plan = plan(context);
        cleanup(context, "cleanup-metadata", "CLEANUP_METADATA", "recovery-archive",
                () -> persistence.delete(context, plan));
        for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) {
            String key = storage.finalKey(plan.tenantId(), context.jobId(), entry.relativePath());
            cleanup(context, "cleanup-object-" + digest(entry.relativePath()), "CLEANUP_OBJECT", key,
                    () -> fencedDelete(context, key));
        }
        String manifestKey = storage.finalKey(plan.tenantId(), context.jobId(), "manifest.json");
        cleanup(context, "cleanup-manifest", "CLEANUP_OBJECT", manifestKey,
                () -> fencedDelete(context, manifestKey));
        String stagingKey = storage.stagingKey(context.jobId(), plan.zipSha256());
        cleanup(context, "cleanup-staging", "CLEANUP_STAGING", stagingKey,
                () -> fencedDelete(context, stagingKey));

        for (OperationStep step : steps.findByJobIdOrderBySequenceNumberAsc(context.jobId())) {
            if (!step.getStepKey().startsWith("cleanup-")
                    && step.getStatus() != OperationStepStatus.COMPENSATED) {
                operations.compensateStep(context, step.getStepKey());
            }
        }
    }

    private void preparePromotion(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                                  RecoveryArchiveImportPlan.Entry entry,
                                  List<Promotion> missing) {
        String stepKey = "promote-" + digest(entry.relativePath());
        StoredRecoveryObject expected = expected(plan, context.jobId(), entry.relativePath(),
                entry.byteSize(), entry.sha256());
        OperationStep step = operations.recordStep(context, stepKey, "PROMOTE_OBJECT", expected.objectKey());
        RecoveryStorageOutcome outcome = inspect(context, expected);
        if (outcome == RecoveryStorageOutcome.MATCHING) {
            completeUnlessCompleted(context, stepKey, step);
            return;
        }
        if (outcome == RecoveryStorageOutcome.CONFLICT) {
            throw new RecoveryStorageConflictException("Deterministic Recovery object contains different bytes: " + expected.objectKey());
        }
        startIfNeeded(context, stepKey, step);
        missing.add(new Promotion(entry, stepKey, expected));
    }

    private void promoteMissing(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                                StoredRecoveryObject stageEvidence,
                                List<Promotion> missing) throws IOException {
        if (missing.isEmpty()) return;
        String stagingKey = stageEvidence.objectKey();
        Map<String, Promotion> remaining = new HashMap<>();
        for (Promotion promotion : missing) remaining.put(promotion.entry().relativePath(), promotion);
        renew(context);
        try (InputStream staged = storage.open(storage.bucket(), stagingKey);
             ZipInputStream zip = new ZipInputStream(staged, java.nio.charset.StandardCharsets.UTF_8)) {
            byte[] skipBuffer = new byte[64 * 1024];
            for (ZipEntry zipEntry; (zipEntry = zip.getNextEntry()) != null;) {
                if (zipEntry.isDirectory()) continue;
                LeaseRenewingInputStream entryStream = new LeaseRenewingInputStream(zip, context);
                Promotion promotion = remaining.remove(zipEntry.getName());
                if (promotion == null) {
                    while (entryStream.read(skipBuffer) >= 0) { }
                    continue;
                }
                StoredRecoveryObject stored = storage.putFinal(plan.tenantId(), context.jobId(),
                        promotion.entry().relativePath(), entryStream);
                renew(context);
                if (stored.byteSize() != promotion.entry().byteSize()
                        || !stored.sha256().equals(promotion.entry().sha256())) {
                    throw new RecoveryStorageConflictException(
                            "Promoted Recovery object differs from its immutable plan");
                }
                RecoveryStorageOutcome storedOutcome = inspect(context, promotion.expected());
                if (storedOutcome == RecoveryStorageOutcome.CONFLICT) {
                    throw new RecoveryStorageConflictException(
                            "Promoted Recovery object conflicts at its deterministic key");
                }
                if (storedOutcome == RecoveryStorageOutcome.ABSENT) {
                    throw new RetryableOperationException("Promoted Recovery object is not yet visible");
                }
                operations.completeStep(context, promotion.stepKey());
            }
        }
        if (!remaining.isEmpty()) {
            throw new RecoveryStorageConflictException(
                    "Staged Recovery ZIP is missing planned entry: " + remaining.keySet().stream().sorted().findFirst().orElse("unknown"));
        }
    }

    private StoredRecoveryObject promoteManifest(OperationExecutionContext context,
                                                  RecoveryArchiveImportPlan plan,
                                                  RecoveryManifest manifest) {
        byte[] bytes = manifests.serialize(manifest);
        StoredRecoveryObject expected = expected(plan, context.jobId(), "manifest.json", bytes.length, digest(bytes));
        String stepKey = "promote-manifest";
        OperationStep step = operations.recordStep(context, stepKey, "PROMOTE_MANIFEST", expected.objectKey());
        RecoveryStorageOutcome outcome = inspect(context, expected);
        if (outcome == RecoveryStorageOutcome.MATCHING) {
            completeUnlessCompleted(context, stepKey, step);
            return expected;
        }
        if (outcome == RecoveryStorageOutcome.CONFLICT) {
            throw new RecoveryStorageConflictException("Deterministic Recovery manifest contains different bytes");
        }
        startIfNeeded(context, stepKey, step);
        renew(context);
        StoredRecoveryObject stored = storage.putFinal(plan.tenantId(), context.jobId(), "manifest.json",
                new ByteArrayInputStream(bytes));
        renew(context);
        if (stored.byteSize() != expected.byteSize() || !stored.sha256().equals(expected.sha256())) {
            throw new RecoveryStorageConflictException("Promoted Recovery manifest differs from its immutable plan");
        }
        RecoveryStorageOutcome storedOutcome = inspect(context, expected);
        if (storedOutcome == RecoveryStorageOutcome.CONFLICT) {
            throw new RecoveryStorageConflictException("Promoted Recovery manifest conflicts at its deterministic key");
        }
        if (storedOutcome == RecoveryStorageOutcome.ABSENT) {
            throw new RetryableOperationException("Promoted Recovery manifest is not yet visible");
        }
        operations.completeStep(context, stepKey);
        return expected;
    }

    private void persist(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                         RecoveryManifest manifest, StoredRecoveryObject storedManifest) {
        String stepKey = "persist-metadata";
        OperationStep step = operations.recordStep(context, stepKey, "PERSIST_METADATA", "recovery-archive");
        startIfNeeded(context, stepKey, step);
        renew(context);
        persistence.persist(context, plan, manifest, storedManifest);
        renew(context);
        completeUnlessCompleted(context, stepKey, step);
    }

    private void deleteStaging(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                               StoredRecoveryObject evidence) {
        String key = evidence.objectKey();
        cleanup(context, "delete-staging", "DELETE_STAGING", key, () -> fencedDelete(context, key));
    }

    private void cleanup(OperationExecutionContext context, String stepKey, String stepType,
                         String resourceRef, CleanupAction action) {
        OperationStep step = operations.recordStep(context, stepKey, stepType, resourceRef);
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        startIfNeeded(context, stepKey, step);
        try {
            action.run();
            operations.completeStep(context, stepKey);
        } catch (RecoveryStorageUnavailableException | DataAccessException exception) {
            operations.retryStep(context, stepKey, exception.getMessage(), Instant.now());
            throw new RetryableOperationException("Recovery cleanup dependency is temporarily unavailable", exception);
        } catch (RuntimeException exception) {
            operations.failStep(context, stepKey, exception.getMessage());
            throw exception;
        }
    }

    private void fencedDelete(OperationExecutionContext context, String key) {
        renew(context);
        storage.delete(key);
        renew(context);
    }

    private RecoveryStorageOutcome inspect(OperationExecutionContext context, StoredRecoveryObject expected) {
        renew(context);
        RecoveryStorageOutcome outcome = storage.inspect(expected, () -> renew(context)).outcome();
        renew(context);
        if (outcome == RecoveryStorageOutcome.STALE_VERSION) {
            throw new RecoveryStorageConflictException("Recovery object version changed from its durable evidence");
        }
        return outcome;
    }

    private StoredRecoveryObject stageEvidence(OperationExecutionContext context) {
        return steps.findByJobIdAndStepKey(context.jobId(), RecoveryArchiveImportIntakeWriteService.STAGE_STEP)
                .filter(step -> step.getStatus() == OperationStepStatus.COMPLETED)
                .map(step -> RecoveryStageEvidence.decode(step.getResourceRef()))
                .orElseThrow(() -> new RecoveryStorageConflictException(
                        "Recovery import is missing completed staging evidence"));
    }

    private void requireMatchingStage(OperationExecutionContext context, StoredRecoveryObject evidence) {
        renew(context);
        RecoveryStorageOutcome outcome = storage.inspectVersion(evidence).outcome();
        renew(context);
        if (outcome == RecoveryStorageOutcome.ABSENT) {
            throw new RetryableOperationException("Recovery staging object is temporarily absent");
        }
        if (outcome == RecoveryStorageOutcome.CONFLICT || outcome == RecoveryStorageOutcome.STALE_VERSION) {
            throw new RecoveryStorageConflictException(
                    "Recovery staging object differs from its published immutable evidence");
        }
    }

    private void startIfNeeded(OperationExecutionContext context, String stepKey, OperationStep step) {
        if (step.getStatus() == OperationStepStatus.PENDING || step.getStatus() == OperationStepStatus.RETRY_WAIT) {
            operations.startStep(context, stepKey);
        }
    }

    private void completeUnlessCompleted(OperationExecutionContext context, String stepKey, OperationStep step) {
        if (step.getStatus() != OperationStepStatus.COMPLETED) operations.completeStep(context, stepKey);
    }

    private RecoveryArchiveImportPlan plan(OperationExecutionContext context) {
        return RecoveryArchiveImportPlan.fromInput(jobs.findById(context.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Recovery import job is missing")).getInput());
    }

    private void renew(OperationExecutionContext context) { claims.renewLease(context); }
    private StoredRecoveryObject expected(RecoveryArchiveImportPlan plan, UUID jobId, String path, long size, String sha) {
        return new StoredRecoveryObject(storage.bucket(), storage.finalKey(plan.tenantId(), jobId, path), size, sha);
    }
    private String digest(String value) { return digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Promotion(RecoveryArchiveImportPlan.Entry entry, String stepKey,
                             StoredRecoveryObject expected) { }

    private final class LeaseRenewingInputStream extends java.io.FilterInputStream {
        private static final long RENEW_BYTES = 64L * 1024L;
        private static final long RENEW_NANOS = 5_000_000_000L;
        private final OperationExecutionContext context;
        private long bytesSinceRenew;
        private long lastRenewed = System.nanoTime();

        private LeaseRenewingInputStream(InputStream input, OperationExecutionContext context) {
            super(input);
            this.context = context;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) afterRead(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int bounded = (int) Math.min(length, RENEW_BYTES);
            int read = super.read(bytes, offset, bounded);
            if (read > 0) afterRead(read);
            return read;
        }

        @Override public void close() { /* the owning ZipInputStream controls this stream */ }

        private void afterRead(int count) {
            bytesSinceRenew += count;
            long now = System.nanoTime();
            if (bytesSinceRenew >= RENEW_BYTES || now - lastRenewed >= RENEW_NANOS) {
                renew(context);
                bytesSinceRenew = 0;
                lastRenewed = now;
            }
        }
    }

    @FunctionalInterface
    private interface CleanupAction { void run(); }
}
