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
import java.io.InputStream;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Promotes one durable staged archive through deterministic, fenced steps. */
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
        RecoveryArchiveImportPlan plan = RecoveryArchiveImportPlan.fromInput(jobs.findById(context.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Recovery import job is missing")).getInput());
        try {
            for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) promote(context, plan, entry);
            RecoveryManifest manifest = manifests.seal(plan.finalHeader(context.jobId()), plan.finalItems(context.jobId()));
            StoredRecoveryObject storedManifest = promoteManifest(context, plan, manifest);
            persist(context, plan, manifest, storedManifest);
            claims.renewLease(context); storage.deleteIfPresent(storage.stagingKey(context.jobId())); claims.renewLease(context);
        } catch (RetryableOperationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new RetryableOperationException("Recovery import metadata is temporarily unavailable", exception);
        } catch (Exception exception) {
            throw new CompensateOperationException("Recovery archive import promotion failed: " + exception.getMessage());
        }
    }

    @Override
    public void executeCompensation(OperationExecutionContext context) {
        RecoveryArchiveImportPlan plan = RecoveryArchiveImportPlan.fromInput(jobs.findById(context.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Recovery import job is missing")).getInput());
        claims.renewLease(context);
        for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) storage.deleteIfPresent(storage.finalKey(plan.tenantId(), context.jobId(), entry.relativePath()));
        storage.deleteIfPresent(storage.finalKey(plan.tenantId(), context.jobId(), "manifest.json"));
        storage.deleteIfPresent(storage.stagingKey(context.jobId()));
        claims.renewLease(context);
        for (OperationStep step : steps.findByJobIdOrderBySequenceNumberAsc(context.jobId())) {
            if (step.getStatus() == OperationStepStatus.COMPLETED || step.getStatus() == OperationStepStatus.FAILED) operations.compensateStep(context, step.getStepKey());
        }
    }

    private void promote(OperationExecutionContext context, RecoveryArchiveImportPlan plan, RecoveryArchiveImportPlan.Entry entry) throws Exception {
        String stepKey = "promote-" + digest(entry.relativePath());
        OperationStep step = operations.recordStep(context, stepKey, "PROMOTE_OBJECT", storage.finalKey(plan.tenantId(), context.jobId(), entry.relativePath()));
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        if (step.getStatus() == OperationStepStatus.RUNNING) {
            if (storage.verify(expected(plan, context.jobId(), entry.relativePath(), entry.byteSize(), entry.sha256()))) { operations.completeStep(context, stepKey); return; }
            operations.retryStep(context, stepKey, "promotion interrupted", java.time.Instant.now());
            throw new RetryableOperationException("Recovery object promotion was interrupted");
        }
        operations.startStep(context, stepKey);
        claims.renewLease(context);
        if (storage.verify(expected(plan, context.jobId(), entry.relativePath(), entry.byteSize(), entry.sha256()))) {
            operations.completeStep(context, stepKey);
            return;
        }
        StoredRecoveryObject stored = copyZipEntry(storage.stagingKey(context.jobId()), plan.tenantId(), context.jobId(), entry.relativePath());
        if (stored.byteSize() != entry.byteSize() || !stored.sha256().equals(entry.sha256()) || !storage.verify(stored)) throw new IllegalArgumentException("Promoted recovery object did not verify");
        claims.renewLease(context); operations.completeStep(context, stepKey);
    }

    private StoredRecoveryObject promoteManifest(OperationExecutionContext context, RecoveryArchiveImportPlan plan, RecoveryManifest manifest) {
        String stepKey = "promote-manifest";
        OperationStep step = operations.recordStep(context, stepKey, "PROMOTE_MANIFEST", storage.finalKey(plan.tenantId(), context.jobId(), "manifest.json"));
        if (step.getStatus() == OperationStepStatus.COMPLETED) return storage.describe(storage.finalKey(plan.tenantId(), context.jobId(), "manifest.json"));
        byte[] bytes = manifests.serialize(manifest);
        if (step.getStatus() == OperationStepStatus.RUNNING) {
            StoredRecoveryObject stored = storage.describe(storage.finalKey(plan.tenantId(), context.jobId(), "manifest.json"));
            if (stored.byteSize() == bytes.length && stored.sha256().equals(digest(bytes))) {
                operations.completeStep(context, stepKey);
                return stored;
            }
            operations.retryStep(context, stepKey, "manifest promotion interrupted", java.time.Instant.now());
            throw new RetryableOperationException("Recovery manifest promotion was interrupted");
        }
        operations.startStep(context, stepKey); claims.renewLease(context);
        StoredRecoveryObject stored = storage.putFinal(plan.tenantId(), context.jobId(), "manifest.json", new ByteArrayInputStream(bytes));
        if (!storage.verify(stored)) throw new IllegalArgumentException("Promoted recovery manifest did not verify");
        claims.renewLease(context); operations.completeStep(context, stepKey); return stored;
    }

    private void persist(OperationExecutionContext context, RecoveryArchiveImportPlan plan, RecoveryManifest manifest, StoredRecoveryObject storedManifest) {
        String stepKey = "persist-metadata";
        OperationStep step = operations.recordStep(context, stepKey, "PERSIST_METADATA", "recovery-archive");
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        if (step.getStatus() != OperationStepStatus.RUNNING) operations.startStep(context, stepKey);
        claims.renewLease(context);
        persistence.persist(context.jobId(), plan, manifest, storedManifest);
        claims.renewLease(context); operations.completeStep(context, stepKey);
    }

    private StoredRecoveryObject copyZipEntry(String stagingKey, String tenant, UUID jobId, String path) throws Exception {
        try (InputStream staged = storage.open(storage.bucket(), stagingKey); ZipInputStream zip = new ZipInputStream(staged, java.nio.charset.StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) if (!entry.isDirectory() && path.equals(entry.getName())) return storage.putFinal(tenant, jobId, path, zip);
        }
        throw new IllegalArgumentException("Staged recovery ZIP is missing planned entry: " + path);
    }

    private StoredRecoveryObject expected(RecoveryArchiveImportPlan plan, UUID jobId, String path, long size, String sha) { return new StoredRecoveryObject(storage.bucket(), storage.finalKey(plan.tenantId(), jobId, path), size, sha); }
    private String digest(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
    private String digest(byte[] value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
}
