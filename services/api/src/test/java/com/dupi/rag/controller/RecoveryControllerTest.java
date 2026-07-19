package com.dupi.rag.controller;

import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import com.dupi.rag.dto.recovery.CreateRestoreRequest;
import com.dupi.rag.service.RecoveryArchiveService;
import com.dupi.rag.service.RecoveryJobExecutor;
import com.dupi.rag.service.RecoveryRestoreService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;

class RecoveryControllerTest {
    @Test
    void createArchiveReturnsAcceptedAndSchedulesCapture() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        RecoveryController controller = new RecoveryController(archives, restores, executor);
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = RecoveryArchive.builder().id(UUID.randomUUID())
                .sourceKnowledgeBaseId(kbId).status(RecoveryArchiveStatus.PREPARING).build();
        when(archives.create(kbId, null)).thenReturn(archive);

        var response = controller.createArchive(kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo(RecoveryArchiveStatus.PREPARING);
        verify(executor).submitArchive(archive.getId());
    }

    @Test
    void listAndRetryArchiveExposeStableProgress() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        RecoveryController controller = new RecoveryController(archives, restores, executor);
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = RecoveryArchive.builder().id(UUID.randomUUID())
                .sourceKnowledgeBaseId(kbId).status(RecoveryArchiveStatus.FAILED)
                .itemCount(3L).totalBytes(20L).build();
        when(archives.list(kbId)).thenReturn(List.of(archive));
        when(archives.prepareRetry(kbId, archive.getId())).thenReturn(archive);

        assertThat(controller.listArchives(kbId)).singleElement()
                .satisfies(item -> assertThat(item.itemCount()).isEqualTo(3));
        assertThat(controller.retryArchive(kbId, archive.getId()).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(executor).submitArchive(archive.getId());
    }

    @Test
    void createRestoreReturnsAcceptedAndSchedulesSameTarget() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        RecoveryController controller = new RecoveryController(archives, restores, executor);
        UUID kbId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().id(UUID.randomUUID()).archiveId(archiveId)
                .targetKnowledgeBaseId(targetId).status(RecoveryRestoreStatus.VALIDATING).build();
        when(restores.create(archiveId, null)).thenReturn(job);

        var response = controller.createRestore(kbId, new CreateRestoreRequest(archiveId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().targetKnowledgeBaseId()).isEqualTo(targetId);
        verify(executor).submitRestore(job.getId(), job.getTenantId());
    }

    @Test
    void detailsDownloadDeleteRestoreRetryAndAbandonDelegate() throws Exception {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        RecoveryController controller = new RecoveryController(archives, restores, executor);
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = RecoveryArchive.builder().id(UUID.randomUUID())
                .sourceKnowledgeBaseId(kbId).status(RecoveryArchiveStatus.COMPLETED).build();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().id(UUID.randomUUID()).archiveId(archive.getId())
                .tenantId("tenant-a").targetKnowledgeBaseId(UUID.randomUUID())
                .status(RecoveryRestoreStatus.FAILED).build();
        when(archives.get(kbId, archive.getId())).thenReturn(archive);
        when(archives.list(kbId)).thenReturn(List.of(archive));
        when(restores.list()).thenReturn(List.of(job));
        when(restores.find(job.getId())).thenReturn(job);
        when(restores.prepareRetry(job.getId())).thenReturn(job);

        assertThat(controller.getArchive(kbId, archive.getId()).id()).isEqualTo(archive.getId());
        var download = controller.downloadArchive(kbId, archive.getId());
        download.getBody().writeTo(new ByteArrayOutputStream());
        controller.deleteArchive(kbId, archive.getId());
        assertThat(controller.listRestores(kbId)).hasSize(1);
        assertThat(controller.getRestore(kbId, job.getId()).id()).isEqualTo(job.getId());
        assertThat(controller.retryRestore(kbId, job.getId()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        controller.abandonRestore(kbId, job.getId());

        verify(archives).download(eq(kbId), eq(archive.getId()), any());
        verify(archives).delete(kbId, archive.getId());
        verify(executor).submitRestore(job.getId(), "tenant-a");
        verify(restores).abandon(job.getId());
    }

    @Test
    void restoreOperationsRemainScopedToArchivesOwnedByKnowledgeBase() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        RecoveryController controller = new RecoveryController(archives, restores, executor);
        UUID kbId = UUID.randomUUID();
        RecoveryArchive owned = RecoveryArchive.builder().id(UUID.randomUUID())
                .sourceKnowledgeBaseId(kbId).status(RecoveryArchiveStatus.COMPLETED).build();
        RecoveryRestoreJob ownedJob = RecoveryRestoreJob.builder().id(UUID.randomUUID())
                .archiveId(owned.getId()).status(RecoveryRestoreStatus.FAILED).build();
        RecoveryRestoreJob otherJob = RecoveryRestoreJob.builder().id(UUID.randomUUID())
                .archiveId(UUID.randomUUID()).status(RecoveryRestoreStatus.FAILED).build();
        when(archives.list(kbId)).thenReturn(List.of(owned));
        when(restores.list()).thenReturn(List.of(ownedJob, otherJob));

        assertThat(controller.listRestores(kbId)).extracting(item -> item.id())
                .containsExactly(ownedJob.getId());
    }
}
