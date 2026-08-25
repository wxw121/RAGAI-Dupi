package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import com.dupi.rag.exception.RecoveryConflictException;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentAssetRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import com.dupi.rag.repository.IngestFailureNotificationRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.RecoveryRestoreJobRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.VectorCleanupTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the atomic database boundaries around durable knowledge-base deletion. */
@Service
@RequiredArgsConstructor
class KnowledgeBaseDeletionPersistenceService {
    private static final String AGGREGATE_TYPE = "KNOWLEDGE_BASE";
    private static final List<OperationStatus> ACTIVE_OPERATION_STATES = List.of(
            OperationStatus.PREPARED,
            OperationStatus.RUNNING,
            OperationStatus.RETRY_WAIT,
            OperationStatus.COMPENSATING);
    private static final List<String> SAFE_TOMBSTONE_REASONS = List.of(
            DocumentTombstoneService.DOCUMENT_DELETE,
            "UPLOAD_ABANDONED_CLEANED");

    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final DocumentAssetRepository assets;
    private final RetrievalProfileRepository profiles;
    private final RecoveryArchiveRepository archives;
    private final RecoveryRestoreJobRepository restores;
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final VectorCleanupTaskRepository vectorTasks;
    private final DocumentTombstoneRepository tombstones;
    private final IngestFailureNotificationRepository notifications;
    private final OperationDomainGuard guard;
    private final AuditLogService audit;
    private final List<RecoveryActivityProbe> activityProbes;

    @Transactional
    UUID submit(UUID knowledgeBaseId, String tenantId, String actor) {
        KnowledgeBase knowledgeBase = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(knowledgeBaseId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + knowledgeBaseId));
        String idempotencyKey = idempotencyKey(knowledgeBaseId);
        if (knowledgeBase.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.DELETING) {
            return jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                            tenantId, OperationType.KNOWLEDGE_BASE_DELETE, idempotencyKey)
                    .map(OperationJob::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Deleting knowledge base has no durable deletion job"));
        }
        if (knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.READY) {
            throw new RecoveryConflictException(
                    "Knowledge base cannot be deleted while lifecycle is " + knowledgeBase.getLifecycleStatus(),
                    "Finish or abandon the active recovery workflow, then retry deletion.");
        }
        if (jobs.existsByTenantIdAndAggregateTypeAndAggregateIdAndStatusIn(
                tenantId, AGGREGATE_TYPE, knowledgeBaseId, ACTIVE_OPERATION_STATES)) {
            throw new OperationConflictException(
                    "Knowledge base cannot be deleted while another durable operation is active");
        }
        validateRecoveryConstraints(knowledgeBaseId, tenantId);

        UUID jobId = UUID.randomUUID();
        List<OperationStep> inventory = inventory(jobId, knowledgeBaseId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("knowledgeBaseId", knowledgeBaseId.toString());
        input.put("inventoryVersion", 1);
        input.put("inventorySize", inventory.size());
        OperationJob job = OperationJob.builder().id(jobId).tenantId(tenantId)
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE)
                .aggregateType(AGGREGATE_TYPE).aggregateId(knowledgeBaseId)
                .status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(false).idempotencyKey(idempotencyKey).input(Map.copyOf(input))
                .attemptCount(0).phaseAttemptCount(0).nextAttemptAt(Instant.now())
                .createdBy(normalizeActor(actor)).build();
        jobs.save(job);
        steps.saveAll(inventory);
        steps.flush();

        knowledgeBase.setLifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING);
        knowledgeBases.save(knowledgeBase);
        job.setRunnable(true);
        jobs.saveAndFlush(job);
        audit.recordSuccessInCurrentTransactionForTenant(tenantId,
                "KNOWLEDGE_BASE_DELETE_SUBMIT", AGGREGATE_TYPE, knowledgeBaseId,
                "Submitted durable knowledge-base deletion " + jobId);
        return jobId;
    }

    @Transactional
    void completeDeletion(OperationExecutionContext context, String finalStepKey) {
        OperationJob job = guard.assertActive(context);
        if (job.getOperationType() != OperationType.KNOWLEDGE_BASE_DELETE
                || !AGGREGATE_TYPE.equals(job.getAggregateType())
                || !context.tenantId().equals(job.getTenantId())) {
            throw new IllegalStateException("Deletion job ownership differs from its current claim");
        }
        UUID knowledgeBaseId = job.getAggregateId();
        KnowledgeBase knowledgeBase = knowledgeBases.findByIdForUpdate(knowledgeBaseId)
                .orElseThrow(() -> new IllegalStateException(
                        "Knowledge base metadata disappeared before durable deletion completed"));
        if (!job.getTenantId().equals(knowledgeBase.getTenantId())
                || knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.DELETING) {
            throw new IllegalStateException("Knowledge base is not owned by the active deletion job");
        }

        List<OperationStep> inventory = steps.findByJobIdOrderBySequenceNumberAsc(context.jobId());
        OperationStep finalStep = inventory.stream()
                .filter(step -> finalStepKey.equals(step.getStepKey())
                        && KnowledgeBaseDeletionWorkflow.FINALIZE_DELETE.equals(step.getStepType()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Knowledge-base deletion finalization step is missing"));
        boolean incomplete = inventory.stream()
                .filter(step -> step != finalStep)
                .anyMatch(step -> step.getStatus() != OperationStepStatus.COMPLETED);
        if (incomplete) {
            throw new IllegalStateException("Knowledge-base deletion inventory is incomplete");
        }

        Instant now = Instant.now();
        finalStep.setStatus(OperationStepStatus.COMPLETED);
        finalStep.setStartedAt(finalStep.getStartedAt() == null ? now : finalStep.getStartedAt());
        finalStep.setCompletedAt(now);
        finalStep.setLastError(null);
        finalStep.setNextAttemptAt(null);
        steps.saveAndFlush(finalStep);

        vectorTasks.deleteByKnowledgeBaseId(knowledgeBaseId);
        tombstones.deleteByKbIdAndReasonIn(knowledgeBaseId, SAFE_TOMBSTONE_REASONS);
        notifications.deleteByKbId(knowledgeBaseId);
        knowledgeBases.delete(knowledgeBase);
        audit.recordSuccessInCurrentTransactionForTenant(job.getTenantId(),
                "KNOWLEDGE_BASE_DELETE", AGGREGATE_TYPE, knowledgeBaseId,
                "Completed durable knowledge-base deletion " + job.getId());

        job.setStatus(OperationStatus.COMPLETED);
        job.setRunnable(false);
        job.setCompletedAt(now);
        job.setLastError(null);
        job.setNextAttemptAt(null);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        jobs.saveAndFlush(job);
    }

    private void validateRecoveryConstraints(UUID knowledgeBaseId, String tenantId) {
        var existingArchives = archives
                .findByTenantIdAndSourceKnowledgeBaseIdOrderByCreatedAtDesc(tenantId, knowledgeBaseId);
        if (!existingArchives.isEmpty()) {
            throw new RecoveryConflictException(
                    "Knowledge base cannot be deleted while " + existingArchives.size()
                            + " recovery archive(s) still exist.",
                    "Delete the recovery archives, then retry deleting the knowledge base.");
        }
        restores.findByTenantIdAndTargetKnowledgeBaseId(tenantId, knowledgeBaseId).ifPresent(restore -> {
            if (restore.getStatus() != RecoveryRestoreStatus.COMPLETED) {
                throw new RecoveryConflictException(
                        "Knowledge base is the target of recovery restore " + restore.getId()
                                + " in status " + restore.getStatus() + ".",
                        "Abandon the recovery restore, then retry deletion.");
            }
            restore.setTargetKnowledgeBaseId(null);
            restores.saveAndFlush(restore);
        });
        if (activityProbes.stream().anyMatch(probe -> probe.hasActiveWork(knowledgeBaseId))) {
            throw new RecoveryConflictException(
                    "Knowledge base cannot be deleted while active work is still running.",
                    "Wait for ingestion, evaluation, or sparse migration work to finish, then retry deletion.");
        }
    }

    private List<OperationStep> inventory(UUID jobId, UUID knowledgeBaseId) {
        List<InventoryItem> items = new ArrayList<>();
        assets.findByKbIdOrderByIdAsc(knowledgeBaseId).forEach(asset -> items.add(new InventoryItem(
                "asset-" + asset.getId(), KnowledgeBaseDeletionWorkflow.DELETE_OBJECT, asset.getObjectKey())));
        documents.findByKbIdOrderByIdAsc(knowledgeBaseId).forEach(document -> items.add(new InventoryItem(
                "source-" + document.getId(), KnowledgeBaseDeletionWorkflow.DELETE_OBJECT, document.getObjectKey())));
        items.add(new InventoryItem("profile-vectors", KnowledgeBaseDeletionWorkflow.DELETE_PROFILE_VECTORS,
                knowledgeBaseId.toString()));
        items.add(new InventoryItem("legacy-vectors", KnowledgeBaseDeletionWorkflow.DELETE_LEGACY_VECTORS,
                knowledgeBaseId.toString()));
        profiles.findByKbIdOrderByVersionDesc(knowledgeBaseId).stream()
                .map(profile -> profile.getVersion())
                .filter(java.util.Objects::nonNull)
                .distinct().sorted(Comparator.naturalOrder())
                .forEach(version -> items.add(new InventoryItem("sparse-vectors-v" + version,
                        KnowledgeBaseDeletionWorkflow.DELETE_SPARSE_VECTORS,
                        knowledgeBaseId + ":" + version)));
        items.add(new InventoryItem("finalize-delete", KnowledgeBaseDeletionWorkflow.FINALIZE_DELETE,
                knowledgeBaseId.toString()));

        Instant now = Instant.now();
        List<OperationStep> inventory = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            InventoryItem item = items.get(index);
            inventory.add(OperationStep.builder().id(UUID.randomUUID()).jobId(jobId)
                    .sequenceNumber(index + 1).stepKey(item.key()).stepType(item.type())
                    .status(OperationStepStatus.PENDING).resourceRef(item.resourceRef())
                    .attemptCount(0).retryEpoch(0L).nextAttemptAt(now).build());
        }
        return List.copyOf(inventory);
    }

    private String idempotencyKey(UUID knowledgeBaseId) {
        return "knowledge-base-delete:" + knowledgeBaseId;
    }

    private String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor.trim();
    }

    private record InventoryItem(String key, String type, String resourceRef) { }
}
