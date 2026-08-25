package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AbandonedUploadCleanupServiceTest {

    @Test
    void crashOrTransportFailureLeavesDurableCleanupForTheNextRun() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        AbandonedUploadCleanupPersistence persistence = mock(AbandonedUploadCleanupPersistence.class);
        DocumentTombstone task = DocumentTombstone.builder().docId(UUID.randomUUID())
                .kbId(UUID.randomUUID()).objectKey("objects/abandoned.md")
                .reason("UPLOAD_ABANDONED").build();
        when(tombstones.findByReasonOrderByCreatedAtAsc("UPLOAD_ABANDONED"))
                .thenReturn(List.of(task));
        doThrow(new IllegalStateException("minio unavailable"))
                .doNothing().when(storage).deleteChecked(task.getObjectKey());
        AbandonedUploadCleanupService service = new AbandonedUploadCleanupService(
                tombstones, storage, persistence);

        assertThat(service.cleanup(10)).isZero();
        verify(persistence, never()).complete(task.getDocId(), task.getObjectKey());

        assertThat(service.cleanup(10)).isEqualTo(1);
        var order = inOrder(storage, persistence);
        order.verify(storage, org.mockito.Mockito.times(2)).deleteChecked(task.getObjectKey());
        order.verify(persistence).complete(task.getDocId(), task.getObjectKey());
    }

    @Test
    void expiredArmedCrashIsTerminalizedAfterSafeObjectDeletion() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        AbandonedUploadCleanupPersistence persistence = mock(AbandonedUploadCleanupPersistence.class);
        DocumentTombstone armed = DocumentTombstone.builder().docId(UUID.randomUUID())
                .kbId(UUID.randomUUID()).objectKey("objects/possibly-late.md")
                .reason("UPLOAD_WRITE_ARMED").build();
        when(tombstones.findByReasonOrderByCreatedAtAsc("UPLOAD_WRITE_ARMED"))
                .thenReturn(List.of(armed));
        when(persistence.shouldReplayArmed(armed.getDocId(), armed.getObjectKey())).thenReturn(true);
        AbandonedUploadCleanupService service = new AbandonedUploadCleanupService(
                tombstones, storage, persistence);

        assertThat(service.cleanup(10)).isEqualTo(1);

        verify(storage).deleteChecked(armed.getObjectKey());
        verify(persistence).complete(armed.getDocId(), armed.getObjectKey());
    }

    @Test
    void liveWriterKeepsArmedCleanupFromPerformingConflictingObjectIo() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        AbandonedUploadCleanupPersistence persistence = mock(AbandonedUploadCleanupPersistence.class);
        DocumentTombstone armed = DocumentTombstone.builder().docId(UUID.randomUUID())
                .kbId(UUID.randomUUID()).objectKey("objects/live.md")
                .reason("UPLOAD_WRITE_ARMED").build();
        when(tombstones.findByReasonOrderByCreatedAtAsc("UPLOAD_WRITE_ARMED"))
                .thenReturn(List.of(armed));
        when(persistence.shouldReplayArmed(armed.getDocId(), armed.getObjectKey())).thenReturn(false);

        assertThat(new AbandonedUploadCleanupService(tombstones, storage, persistence).cleanup(10)).isZero();

        verifyNoInteractions(storage);
    }

    @Test
    void liveOldestArmedWriteDoesNotStarveLaterExpiredCleanupInSmallBatch() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        AbandonedUploadCleanupPersistence persistence = mock(AbandonedUploadCleanupPersistence.class);
        DocumentTombstone live = DocumentTombstone.builder().docId(UUID.randomUUID())
                .objectKey("objects/live.md").reason("UPLOAD_WRITE_ARMED").build();
        DocumentTombstone expired = DocumentTombstone.builder().docId(UUID.randomUUID())
                .objectKey("objects/expired.md").reason("UPLOAD_WRITE_ARMED").build();
        when(tombstones.findByReasonOrderByCreatedAtAsc("UPLOAD_WRITE_ARMED"))
                .thenReturn(List.of(live, expired));
        when(persistence.shouldReplayArmed(live.getDocId(), live.getObjectKey())).thenReturn(false);
        when(persistence.shouldReplayArmed(expired.getDocId(), expired.getObjectKey())).thenReturn(true);

        assertThat(new AbandonedUploadCleanupService(tombstones, storage, persistence).cleanup(1))
                .isEqualTo(1);

        verify(storage, never()).deleteChecked(live.getObjectKey());
        verify(storage).deleteChecked(expired.getObjectKey());
        verify(persistence).complete(expired.getDocId(), expired.getObjectKey());
    }
}
