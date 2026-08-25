package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTombstoneServiceTest {

    @Mock
    DocumentTombstoneRepository repository;

    @Test
    void recordDeletedIgnoresNullDocument() {
        service().recordDeleted(null);

        verifyNoInteractions(repository);
    }

    @Test
    void recordDeletedIgnoresDocumentWithoutId() {
        Document document = Document.builder()
                .kbId(UUID.randomUUID())
                .fileName("a.md")
                .objectKey("kb/a.md")
                .build();

        service().recordDeleted(document);

        verifyNoInteractions(repository);
    }

    @Test
    void recordDeletedSkipsWhenTombstoneAlreadyExists() {
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(UUID.randomUUID()).build();
        DocumentTombstone tombstone = DocumentTombstone.builder()
                .docId(docId)
                .reason("DOCUMENT_DELETE")
                .build();
        when(repository.findByDocId(docId)).thenReturn(Optional.of(tombstone));

        service().recordDeleted(document);

        verify(repository).findByDocId(docId);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordDeletedSavesTombstoneSnapshotForDeletedDocument() {
        UUID docId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        Document document = Document.builder()
                .id(docId)
                .kbId(kbId)
                .objectKey("kb/doc/a.md")
                .fileName("a.md")
                .build();
        service().recordDeleted(document);

        ArgumentCaptor<DocumentTombstone> captor = ArgumentCaptor.forClass(DocumentTombstone.class);
        verify(repository).save(captor.capture());
        DocumentTombstone tombstone = captor.getValue();
        assertThat(tombstone.getDocId()).isEqualTo(docId);
        assertThat(tombstone.getKbId()).isEqualTo(kbId);
        assertThat(tombstone.getObjectKey()).isEqualTo("kb/doc/a.md");
        assertThat(tombstone.getFileName()).isEqualTo("a.md");
        assertThat(tombstone.getReason()).isEqualTo("DOCUMENT_DELETE");
    }

    @Test
    void lateAbandonedWriterUpgradesDocumentDeleteTombstoneInsteadOfDroppingCleanupTruth() {
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(UUID.randomUUID())
                .objectKey("kb/doc/late.md").fileName("late.md").build();
        DocumentTombstone deletion = DocumentTombstone.builder().docId(docId)
                .kbId(document.getKbId()).objectKey(document.getObjectKey())
                .fileName(document.getFileName()).reason("DOCUMENT_DELETE").build();
        when(repository.findByDocId(docId)).thenReturn(Optional.of(deletion));

        service().recordAbandonedUpload(document);

        assertThat(deletion.getReason()).isEqualTo("UPLOAD_ABANDONED");
        verify(repository).save(deletion);
    }

    @Test
    void documentDeletePreservesArmedLateWriterCleanupObligation() {
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(UUID.randomUUID())
                .objectKey("kb/doc/slow.md").fileName("slow.md").build();
        DocumentTombstone armed = DocumentTombstone.builder().docId(docId)
                .kbId(document.getKbId()).objectKey(document.getObjectKey())
                .fileName(document.getFileName()).reason("UPLOAD_WRITE_ARMED").build();
        when(repository.findByDocId(docId)).thenReturn(Optional.of(armed));

        service().recordDeleted(document);

        assertThat(armed.getReason()).isEqualTo("UPLOAD_WRITE_ARMED");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void armUploadCleanupPersistsTruthBeforeRemoteWriteAndPublishDisarmsOnlyThatTruth() {
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(UUID.randomUUID())
                .objectKey("kb/doc/armed.md").fileName("armed.md").build();
        when(repository.findByDocId(docId)).thenReturn(Optional.empty());

        service().armUploadCleanup(document);

        ArgumentCaptor<DocumentTombstone> captor = ArgumentCaptor.forClass(DocumentTombstone.class);
        verify(repository).save(captor.capture());
        DocumentTombstone armed = captor.getValue();
        assertThat(armed.getReason()).isEqualTo("UPLOAD_WRITE_ARMED");

        when(repository.findByDocId(docId)).thenReturn(Optional.of(armed));
        service().disarmUploadCleanup(document);

        verify(repository).delete(armed);
    }

    @Test
    void publishDisarmDoesNotEraseConcurrentDocumentDeletionTruth() {
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(UUID.randomUUID())
                .objectKey("kb/doc/deleted.md").fileName("deleted.md").build();
        DocumentTombstone deletion = DocumentTombstone.builder().docId(docId)
                .kbId(document.getKbId()).objectKey(document.getObjectKey())
                .reason("DOCUMENT_DELETE").build();
        when(repository.findByDocId(docId)).thenReturn(Optional.of(deletion));

        service().disarmUploadCleanup(document);

        verify(repository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isDeletedReturnsFalseForNullAndDelegatesForIds() {
        UUID docId = UUID.randomUUID();
        when(repository.existsById(docId)).thenReturn(true);

        assertThat(service().isDeleted(null)).isFalse();
        assertThat(service().isDeleted(docId)).isTrue();

        verify(repository).existsById(docId);
    }

    private DocumentTombstoneService service() {
        return new DocumentTombstoneService(repository);
    }
}
