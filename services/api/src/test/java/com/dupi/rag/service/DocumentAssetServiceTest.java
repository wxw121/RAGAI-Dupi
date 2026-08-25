package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentAssetRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class DocumentAssetServiceTest {

    @Test
    void storesAndReadsAnAssetOnlyWithinItsKnowledgeBase() {
        DocumentAssetRepository repository = mock(DocumentAssetRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentAssetService service = new DocumentAssetService(repository, storage, knowledgeBases);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document document = Document.builder().id(docId).kbId(kbId).build();
        when(repository.save(any(DocumentAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentAsset asset = service.register(
                document, "../image/diagram.png", "diagram.png", "image/png", "png".getBytes());

        verify(storage).upload(org.mockito.ArgumentMatchers.eq(asset.getObjectKey()), any(), org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("image/png"));
        when(repository.findByDocIdAndRelativePath(docId, "../image/diagram.png"))
                .thenReturn(Optional.of(asset));
        when(storage.download(asset.getObjectKey())).thenReturn(new ByteArrayInputStream("png".getBytes()));

        assertThat(service.download(kbId, docId, "../image/diagram.png").fileName()).isEqualTo("diagram.png");
        assertThatThrownBy(() -> service.download(UUID.randomUUID(), docId, "../image/diagram.png"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void downloadChecksKnowledgeBaseLifecycleBeforeReadingAssetOrObject() {
        DocumentAssetRepository repository = mock(DocumentAssetRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentAssetService service = new DocumentAssetService(repository, storage, knowledgeBases);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(knowledgeBases.findOrThrow(kbId)).thenThrow(
                new com.dupi.rag.exception.OperationConflictException("deletion in progress"));

        assertThatThrownBy(() -> service.download(kbId, docId, "image.png"))
                .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class);

        verify(repository, never()).findByDocIdAndRelativePath(any(), any());
        verify(storage, never()).download(any());
    }
}
