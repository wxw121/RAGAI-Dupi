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
import java.util.HexFormat;
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
            for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) promote(context, plan, entry);
            RecoveryManifest manifest = manifests.seal(plan.finalHeader(context.jobId()), plan.finalItems(context.jobId()));
            StoredRecoveryObject storedManifest = promoteManifest(context, plan, manifest);
            persist(context, plan, manifest, storedManifest);
            deleteStaging(context);
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
        String stagingKey = storage.stagingKey(context.jobId());
        cleanup(context, "cleanup-staging", "CLEANUP_STAGING", stagingKey,
                () -> fencedDelete(context, stagingKey));

        for (OperationStep step : steps.findByJobIdOrderBySequenceNumberAsc(context.jobId())) {
            if (!step.getStepKey().startsWith("cleanup-")
                    && step.getStatus() != OperationStepStatus.COMPENSATED) {
                operations.compensateStep(context, step.getStepKey());
            }
        }
    }

    private void promote(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                         RecoveryArchiveImportPlan.Entry entry) throws IOException {
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
        StoredRecoveryObject stored = copyZipEntry(context, storage.stagingKey(context.jobId()),
                plan.tenantId(), context.jobId(), entry.relativePath());
        if (stored.byteSize() != entry.byteSize() || !stored.sha256().equals(entry.sha256())) {
            throw new RecoveryStorageConflictException("Promoted Recovery object differs from its immutable plan");
        }
        RecoveryStorageOutcome storedOutcome = inspect(context, expected);
        if (storedOutcome == RecoveryStorageOutcome.CONFLICT) {
            throw new RecoveryStorageConflictException("Promoted Recovery object conflicts at its deterministic key");
        }
        if (storedOutcome == RecoveryStorageOutcome.ABSENT) {
            throw new RetryableOperationException("Promoted Recovery object is not yet visible");
        }
        operations.completeStep(context, stepKey);
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

    private void deleteStaging(OperationExecutionContext context) {
        String key = storage.stagingKey(context.jobId());
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
        RecoveryStorageOutcome outcome = storage.inspect(expected);
        renew(context);
        return outcome;
    }

    private StoredRecoveryObject copyZipEntry(OperationExecutionContext context, String stagingKey,
                                               String tenant, UUID jobId, String path) throws IOException {
        renew(context);
        try (InputStream staged = storage.open(storage.bucket(), stagingKey);
             ZipInputStream zip = new ZipInputStream(staged, java.nio.charset.StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.isDirectory() && path.equals(entry.getName())) {
                    renew(context);
                    StoredRecoveryObject stored = storage.putFinal(tenant, jobId, path, zip);
                    renew(context);
                    return stored;
                }
            }
        }
        throw new RecoveryStorageConflictException("Staged Recovery ZIP is missing planned entry: " + path);
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

    @FunctionalInterface
    private interface CleanupAction { void run(); }
}
