package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MarkdownImportIntakeServiceTest {

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void jobStaysNonRunnableUntilEveryDeterministicStageIsDurable() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a", "b.md", "b");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob prepared = job(plan, false);
        OperationJob runnable = job(plan, true);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.MARKDOWN_PACKAGE_IMPORT, "same-key")).thenReturn(Optional.empty());
        when(writes.insert("tenant-a", "same-key", "alice", plan)).thenReturn(prepared);
        when(writes.publishRunnable(plan.jobId(), plan)).thenReturn(runnable);
        when(storage.inspect(anyString(), anyLong(), anyString())).thenReturn(null);
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        var response = intake.submit(plan, "same-key", "alice");

        assertThat(response.getId()).isEqualTo(plan.jobId());
        verify(storage, times(2)).uploadIfAbsent(anyString(), any(), anyLong(), anyString());
        verify(writes, times(2)).completeStage(eq(plan.jobId()), eq(plan), any());
        var order = inOrder(writes);
        order.verify(writes).insert("tenant-a", "same-key", "alice", plan);
        order.verify(writes).validatePlan(prepared, plan);
        order.verify(writes, times(2)).completeStage(eq(plan.jobId()), eq(plan), any());
        order.verify(writes).publishRunnable(plan.jobId(), plan);
    }

    @Test
    void sameKeyReplayReturnsRunnableWinnerWithoutRestagingAndDifferentPlanConflicts() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob winner = job(plan, true);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.MARKDOWN_PACKAGE_IMPORT, "same-key")).thenReturn(Optional.of(winner));
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        assertThat(intake.submit(plan, "same-key", "alice").getId()).isEqualTo(plan.jobId());
        verifyNoInteractions(storage);

        MarkdownImportPlan different = plan("same-key", "a.md", "different");
        doThrow(new OperationConflictException("different input")).when(writes).validatePlan(winner, different);
        assertThatThrownBy(() -> intake.submit(different, "same-key", "alice"))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void partialStageFailureDurablySchedulesCompensation() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a", "b.md", "b");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob prepared = job(plan, false);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(writes.insert(anyString(), anyString(), anyString(), eq(plan))).thenReturn(prepared);
        when(storage.inspect(anyString(), anyLong(), anyString())).thenReturn(null);
        when(storage.uploadIfAbsent(anyString(), any(), anyLong(), anyString()))
                .thenReturn(MinioStorageService.ObjectWriteResult.CREATED)
                .thenThrow(new IllegalStateException("minio down"));
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        assertThatThrownBy(() -> intake.submit(plan, "same-key", "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("minio down");
        verify(writes).scheduleCleanup(plan.jobId(), plan, "minio down");
        verify(writes, never()).publishRunnable(any(), any());
    }

    private static MarkdownImportPlan plan(String key, String... entries) throws Exception {
        UUID jobId = MarkdownImportIntakeService.jobId("tenant-a", key);
        return new MarkdownPackageParser().parse(jobId, UUID.randomUUID(), zip(entries).getInputStream());
    }

    private static OperationJob job(MarkdownImportPlan plan, boolean runnable) {
        return OperationJob.builder().id(plan.jobId()).tenantId("tenant-a")
                .operationType(OperationType.MARKDOWN_PACKAGE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(plan.knowledgeBaseId()).input(plan.toInput()).idempotencyKey("same-key")
                .createdBy("alice").status(OperationStatus.PREPARED).runnable(runnable).build();
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index])); zip.write(entries[index + 1].getBytes()); zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
    }
}
