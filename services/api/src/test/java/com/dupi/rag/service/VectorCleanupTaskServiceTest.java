package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.domain.entity.VectorCleanupTask;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import com.dupi.rag.repository.VectorCleanupTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorCleanupTaskServiceTest {

    @Mock VectorCleanupTaskRepository repository;
    @Mock MilvusVectorService milvusVectorService;
    @Mock AuditLogService auditLogService;
    @Mock ProfileIndexStateService profileIndexStateService;

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void enqueueDocumentCreatesProfileAndLegacyTasksOnlyWhenMissing() {
        UUID docId = UUID.randomUUID();
        when(repository.findByTargetTypeAndTargetIdAndStatus(
                any(VectorCleanupTargetType.class),
                eq(docId),
                eq(VectorCleanupStatus.PENDING)
        )).thenReturn(Optional.empty());

        service().enqueueDocument(docId);

        ArgumentCaptor<VectorCleanupTask> captor = ArgumentCaptor.forClass(VectorCleanupTask.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(VectorCleanupTask::getTargetType)
                .containsExactly(
                        VectorCleanupTargetType.PROFILE_DOCUMENT,
                        VectorCleanupTargetType.LEGACY_DOCUMENT
                );
        assertThat(captor.getAllValues()).allSatisfy(task -> {
            assertThat(task.getTargetId()).isEqualTo(docId);
            assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        });

        reset(repository);
        when(repository.findByTargetTypeAndTargetIdAndStatus(
                any(VectorCleanupTargetType.class),
                eq(docId),
                eq(VectorCleanupStatus.PENDING)
        )).thenReturn(Optional.of(VectorCleanupTask.builder().targetId(docId).build()));

        service().enqueueDocument(docId);

        verify(repository, never()).save(any());
    }

    @Test
    void enqueueKnowledgeBaseCreatesProfileAndLegacyTasks() {
        UUID kbId = UUID.randomUUID();
        when(repository.findByTargetTypeAndTargetIdAndStatus(
                any(VectorCleanupTargetType.class),
                eq(kbId),
                eq(VectorCleanupStatus.PENDING)
        )).thenReturn(Optional.empty());

        service().enqueueKnowledgeBase(kbId);

        verify(repository, times(2)).save(argThat(task ->
                task.getTargetId().equals(kbId)
                        && task.getStatus() == VectorCleanupStatus.PENDING));
    }

    @Test
    void processPendingTasksCompletesSuccessfulDocumentCleanup() {
        UUID docId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.PROFILE_DOCUMENT, docId);
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(VectorCleanupStatus.PENDING),
                any(Instant.class)
        )).thenReturn(List.of(task));

        service().processPendingTasks();

        verify(milvusVectorService).deleteProfileByDocIdForCleanup(docId);
        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.COMPLETED);
        assertThat(task.getLastError()).isNull();
    }

    @Test
    void processPendingTasksBacksOffFailedKnowledgeBaseCleanup() {
        UUID kbId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.LEGACY_KNOWLEDGE_BASE, kbId);
        Instant before = Instant.now();
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(VectorCleanupStatus.PENDING),
                any(Instant.class)
        )).thenReturn(List.of(task));
        when(profileIndexStateService.shouldDeferLegacyCleanup(kbId)).thenReturn(false);
        doThrow(new IllegalStateException("milvus down"))
                .when(milvusVectorService).deleteLegacyByKbIdForCleanup(kbId);

        service().processPendingTasks();

        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).contains("milvus down");
        assertThat(task.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void completePendingProfileKnowledgeBaseClosesStaleCompensationTask() {
        UUID kbId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.PROFILE_KNOWLEDGE_BASE, kbId);
        when(repository.findByTargetTypeAndTargetIdAndStatus(
                VectorCleanupTargetType.PROFILE_KNOWLEDGE_BASE,
                kbId,
                VectorCleanupStatus.PENDING
        )).thenReturn(Optional.of(task));

        service().completePendingProfileKnowledgeBase(kbId);

        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.COMPLETED);
        verify(repository).save(task);
    }

    @Test
    void processPendingTasksDefersLegacyKnowledgeBaseCleanupUntilProfileIndexIsReady() {
        UUID kbId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.LEGACY_KNOWLEDGE_BASE, kbId);
        Instant before = task.getNextAttemptAt();
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(VectorCleanupStatus.PENDING),
                any(Instant.class)
        )).thenReturn(List.of(task));
        when(profileIndexStateService.shouldDeferLegacyCleanup(kbId)).thenReturn(true);

        service().processPendingTasks();

        verify(milvusVectorService, never()).deleteLegacyByKbIdForCleanup(kbId);
        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        assertThat(task.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void listOpenTasksReturnsPendingAndFailedTasksFromRepositoryOrdering() {
        VectorCleanupTask pending = task(VectorCleanupTargetType.PROFILE_DOCUMENT, UUID.randomUUID());
        VectorCleanupTask failed = task(VectorCleanupTargetType.LEGACY_KNOWLEDGE_BASE, UUID.randomUUID());
        failed.setStatus(VectorCleanupStatus.FAILED);
        when(repository.findTop50ByStatusInOrderByUpdatedAtDesc(List.of(
                VectorCleanupStatus.PENDING,
                VectorCleanupStatus.FAILED
        ))).thenReturn(List.of(failed, pending));

        var responses = service().listOpenTasks();

        assertThat(responses).extracting("id").containsExactly(failed.getId(), pending.getId());
        assertThat(responses).extracting("status").containsExactly(VectorCleanupStatus.FAILED, VectorCleanupStatus.PENDING);
    }

    @Test
    void listOpenTasksFiltersDocumentTasksByKnowledgeBaseScopeForNonAdminUsers() {
        UUID allowedKbId = UUID.randomUUID();
        UUID allowedDocId = UUID.randomUUID();
        UUID hiddenDocId = UUID.randomUUID();
        VectorCleanupTask allowed = task(VectorCleanupTargetType.PROFILE_DOCUMENT, allowedDocId);
        VectorCleanupTask hidden = task(VectorCleanupTargetType.LEGACY_DOCUMENT, hiddenDocId);
        SecurityContext.set("maintainer", "USER", List.of("OPS_ADMIN"), List.of(allowedKbId.toString()));
        when(repository.findTop50ByStatusInOrderByUpdatedAtDesc(List.of(
                VectorCleanupStatus.PENDING,
                VectorCleanupStatus.FAILED
        ))).thenReturn(List.of(allowed, hidden));
        when(repository.resolveKnowledgeBaseIdForDocumentTarget(allowedDocId)).thenReturn(Optional.of(allowedKbId));
        when(repository.resolveKnowledgeBaseIdForDocumentTarget(hiddenDocId)).thenReturn(Optional.of(UUID.randomUUID()));

        var responses = service().listOpenTasks();

        assertThat(responses).extracting("targetId").containsExactly(allowedDocId);
    }

    @Test
    void retryProcessesTaskImmediatelyAndRecordsSuccessAudit() {
        UUID taskId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.LEGACY_DOCUMENT, docId);
        task.setId(taskId);
        task.setAttemptCount(2);
        task.setLastError("previous failure");
        when(repository.findById(taskId)).thenReturn(Optional.of(task));

        var response = service().retry(taskId);

        verify(milvusVectorService).deleteLegacyByDocIdForCleanup(docId);
        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.COMPLETED);
        assertThat(task.getLastError()).isNull();
        assertThat(response.getId()).isEqualTo(taskId);
        assertThat(response.getStatus()).isEqualTo(VectorCleanupStatus.COMPLETED);
        verify(auditLogService).recordSuccess(
                eq("VECTOR_CLEANUP_RETRY"),
                eq("VECTOR_CLEANUP_TASK"),
                eq(taskId),
                contains(docId.toString())
        );
    }

    @Test
    void retryRecordsFailureAuditWhenImmediateCleanupStillFails() {
        UUID taskId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.PROFILE_KNOWLEDGE_BASE, kbId);
        task.setId(taskId);
        when(repository.findById(taskId)).thenReturn(Optional.of(task));
        doThrow(new IllegalStateException("milvus down"))
                .when(milvusVectorService).deleteProfileByKbIdForCleanup(kbId);

        var response = service().retry(taskId);

        assertThat(response.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).contains("milvus down");
        verify(auditLogService).recordFailure(
                eq("VECTOR_CLEANUP_RETRY"),
                eq("VECTOR_CLEANUP_TASK"),
                eq(taskId),
                any(IllegalStateException.class)
        );
    }

    @Test
    void retryRejectsTaskOutsideCurrentKnowledgeBaseScope() {
        UUID taskId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID allowedKbId = UUID.randomUUID();
        VectorCleanupTask task = task(VectorCleanupTargetType.PROFILE_DOCUMENT, docId);
        task.setId(taskId);
        SecurityContext.set("maintainer", "USER", List.of("OPS_ADMIN"), List.of(allowedKbId.toString()));
        when(repository.findById(taskId)).thenReturn(Optional.of(task));
        when(repository.resolveKnowledgeBaseIdForDocumentTarget(docId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service().retry(taskId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector cleanup task access denied");

        verifyNoInteractions(milvusVectorService);
    }

    @Test
    void summarizeAlertsReportsOpenVectorCleanupFailures() {
        when(repository.countByStatusIn(List.of(
                VectorCleanupStatus.PENDING,
                VectorCleanupStatus.FAILED
        ))).thenReturn(3L);

        var alerts = service().summarizeAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getCode()).isEqualTo("VECTOR_CLEANUP_FAILURES_OPEN");
        assertThat(alerts.get(0).getSeverity()).isEqualTo("WARN");
        assertThat(alerts.get(0).getCount()).isEqualTo(3L);
        assertThat(alerts.get(0).getMessage()).contains("vector cleanup");
    }

    private VectorCleanupTaskService service() {
        return new VectorCleanupTaskService(
                repository,
                milvusVectorService,
                auditLogService,
                profileIndexStateService
        );
    }

    private static VectorCleanupTask task(VectorCleanupTargetType targetType, UUID targetId) {
        return VectorCleanupTask.builder()
                .targetType(targetType)
                .targetId(targetId)
                .status(VectorCleanupStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
