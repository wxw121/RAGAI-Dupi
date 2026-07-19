package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.domain.entity.VectorCleanupTask;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.VectorCleanupTaskResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.VectorCleanupTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VectorCleanupTaskService {

    private final VectorCleanupTaskRepository repository;
    private final MilvusVectorService milvusVectorService;
    private final AuditLogService auditLogService;

    @Transactional
    public void enqueueKnowledgeBase(UUID kbId) {
        enqueueProfileKnowledgeBase(kbId);
        enqueueLegacyKnowledgeBase(kbId);
    }

    @Transactional
    public void enqueueDocument(UUID docId) {
        enqueueProfileDocument(docId);
        enqueueLegacyDocument(docId);
    }

    @Transactional
    public void enqueueProfileKnowledgeBase(UUID kbId) {
        enqueue(VectorCleanupTargetType.PROFILE_KNOWLEDGE_BASE, kbId);
    }

    @Transactional
    public void enqueueLegacyKnowledgeBase(UUID kbId) {
        enqueue(VectorCleanupTargetType.LEGACY_KNOWLEDGE_BASE, kbId);
    }

    @Transactional
    public void enqueueProfileDocument(UUID docId) {
        enqueue(VectorCleanupTargetType.PROFILE_DOCUMENT, docId);
    }

    @Transactional
    public void enqueueLegacyDocument(UUID docId) {
        enqueue(VectorCleanupTargetType.LEGACY_DOCUMENT, docId);
    }

    @Scheduled(cron = "${dupi.cleanup.orphan-vectors-cron:0 30 3 * * *}")
    @Transactional
    public void processPendingTasks() {
        Instant now = Instant.now();
        repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                VectorCleanupStatus.PENDING,
                now
        ).forEach(task -> process(task, now));
    }

    public java.util.List<VectorCleanupTaskResponse> listOpenTasks() {
        return repository.findTop50ByStatusInOrderByUpdatedAtDesc(java.util.List.of(
                        VectorCleanupStatus.PENDING,
                        VectorCleanupStatus.FAILED
                ))
                .stream()
                .filter(this::canAccessTask)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<AuditAlertResponse> summarizeAlerts() {
        long openFailures = repository.countByStatusIn(java.util.List.of(
                VectorCleanupStatus.PENDING,
                VectorCleanupStatus.FAILED
        ));
        if (openFailures <= 0) {
            return java.util.List.of();
        }
        return java.util.List.of(AuditAlertResponse.builder()
                .code("VECTOR_CLEANUP_FAILURES_OPEN")
                .severity("WARN")
                .message("Open vector cleanup tasks need operator review")
                .count(openFailures)
                .threshold(0)
                .build());
    }

    @Transactional
    public VectorCleanupTaskResponse retry(UUID taskId) {
        VectorCleanupTask task = repository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Vector cleanup task not found: " + taskId));
        if (!canAccessTask(task)) {
            throw new IllegalArgumentException("vector cleanup task access denied: " + taskId);
        }
        process(task, Instant.now());
        if (task.getStatus() == VectorCleanupStatus.COMPLETED) {
            auditLogService.recordSuccess(
                    "VECTOR_CLEANUP_RETRY",
                    "VECTOR_CLEANUP_TASK",
                    task.getId(),
                    "Retried vector cleanup for " + task.getTargetType() + " " + task.getTargetId()
            );
        }
        return toResponse(task);
    }

    private void enqueue(VectorCleanupTargetType targetType, UUID targetId) {
        repository.findByTargetTypeAndTargetIdAndStatus(targetType, targetId, VectorCleanupStatus.PENDING)
                .orElseGet(() -> repository.save(VectorCleanupTask.builder()
                        .targetType(targetType)
                        .targetId(targetId)
                        .status(VectorCleanupStatus.PENDING)
                        .attemptCount(0)
                        .nextAttemptAt(Instant.now())
                        .build()));
    }

    private void process(VectorCleanupTask task, Instant now) {
        try {
            switch (task.getTargetType()) {
                case PROFILE_KNOWLEDGE_BASE ->
                        milvusVectorService.deleteProfileByKbIdForCleanup(task.getTargetId());
                case PROFILE_DOCUMENT ->
                        milvusVectorService.deleteProfileByDocIdForCleanup(task.getTargetId());
                case LEGACY_KNOWLEDGE_BASE ->
                        milvusVectorService.deleteLegacyByKbIdForCleanup(task.getTargetId());
                case LEGACY_DOCUMENT ->
                        milvusVectorService.deleteLegacyByDocIdForCleanup(task.getTargetId());
            }
            task.setStatus(VectorCleanupStatus.COMPLETED);
            task.setLastError(null);
        } catch (Exception e) {
            int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            task.setAttemptCount(attempts);
            task.setStatus(VectorCleanupStatus.PENDING);
            task.setLastError(e.getMessage());
            task.setNextAttemptAt(now.plus(backoff(attempts)));
            log.warn("Vector cleanup task failed, targetType={} targetId={} attempts={}",
                    task.getTargetType(), task.getTargetId(), attempts, e);
            if (task.getId() != null) {
                auditLogService.recordFailure("VECTOR_CLEANUP_RETRY", "VECTOR_CLEANUP_TASK", task.getId(), e);
            }
        }
    }

    private Duration backoff(int attempts) {
        long minutes = Math.min(60, Math.max(1, attempts * 5L));
        return Duration.ofMinutes(minutes);
    }

    private boolean canAccessTask(VectorCleanupTask task) {
        if (SecurityContext.hasPermission("*") || SecurityContext.getPrincipal() == null) {
            return true;
        }
        if (task.getTargetType() == VectorCleanupTargetType.PROFILE_KNOWLEDGE_BASE
                || task.getTargetType() == VectorCleanupTargetType.LEGACY_KNOWLEDGE_BASE) {
            return SecurityContext.canAccessKnowledgeBase(task.getTargetId().toString());
        }
        return repository.resolveKnowledgeBaseIdForDocumentTarget(task.getTargetId())
                .map(kbId -> SecurityContext.canAccessKnowledgeBase(kbId.toString()))
                .orElse(false);
    }

    private VectorCleanupTaskResponse toResponse(VectorCleanupTask task) {
        return VectorCleanupTaskResponse.builder()
                .id(task.getId())
                .targetType(task.getTargetType())
                .targetId(task.getTargetId())
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .lastError(task.getLastError())
                .nextAttemptAt(task.getNextAttemptAt())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
