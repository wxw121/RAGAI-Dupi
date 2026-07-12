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
        when(repository.existsById(docId)).thenReturn(true);

        service().recordDeleted(document);

        verify(repository).existsById(docId);
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
        when(repository.existsById(docId)).thenReturn(false);

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
