package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(tombstones.findByReasonOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("UPLOAD_ABANDONED"),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
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
}
