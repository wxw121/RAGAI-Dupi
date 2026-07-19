package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfileIndexStateServiceTest {

    @Test
    void readinessRequiresAtLeastOneDocumentAndNoLegacySchemaDocuments() {
        UUID kbId = UUID.randomUUID();
        DocumentRepository documents = mock(DocumentRepository.class);
        ProfileIndexStateService service = service(documents, mock(KnowledgeBaseRepository.class));

        when(documents.countByKbId(kbId)).thenReturn(0L, 2L, 2L);
        when(documents.countByKbIdAndIndexSchemaVersionLessThan(kbId, 2)).thenReturn(1L, 0L);

        assertThat(service.isV2Ready(kbId)).isFalse();
        assertThat(service.isV2Ready(kbId)).isFalse();
        assertThat(service.isV2Ready(kbId)).isTrue();
    }

    @Test
    void bumpAndResetAdvanceRevisionAndRestoreLegacySchemaVersion() {
        DocumentRepository documents = mock(DocumentRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        ProfileIndexStateService service = service(documents, knowledgeBases);
        KnowledgeBase kb = KnowledgeBase.builder().id(UUID.randomUUID()).indexRevision(4L).build();
        Document first = Document.builder().indexSchemaVersion(2).build();
        Document second = Document.builder().indexSchemaVersion(2).build();
        when(knowledgeBases.findByIdForUpdate(kb.getId())).thenReturn(java.util.Optional.of(kb));

        service.resetForReindex(kb, List.of(first, second));

        assertThat(kb.getIndexRevision()).isEqualTo(5L);
        assertThat(first.getIndexSchemaVersion()).isEqualTo(1);
        assertThat(second.getIndexSchemaVersion()).isEqualTo(1);
        verify(documents).saveAll(List.of(first, second));
        verify(knowledgeBases).save(kb);
    }

    private static ProfileIndexStateService service(
            DocumentRepository documents,
            KnowledgeBaseRepository knowledgeBases
    ) {
        return new ProfileIndexStateService(documents, knowledgeBases);
    }
}
