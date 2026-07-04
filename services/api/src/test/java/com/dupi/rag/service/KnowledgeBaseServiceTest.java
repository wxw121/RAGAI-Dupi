package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    KnowledgeBaseRepository repository;
    @Mock
    MilvusVectorService milvusVectorService;

    LlmProperties llmProperties;
    KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.getEmbedding().setModel("default-embedding");
        llmProperties.getEmbedding().setDimension(1024);
        service = new KnowledgeBaseService(repository, milvusVectorService, llmProperties);
    }

    @Test
    void createUsesDefaultEmbeddingSettingsWhenRequestOmitsThem() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("KB");
        request.setDescription("desc");
        when(repository.save(any(KnowledgeBase.class))).thenAnswer(inv -> {
            KnowledgeBase kb = inv.getArgument(0);
            kb.setId(UUID.randomUUID());
            return kb;
        });

        var response = service.create(request);

        assertThat(response.getName()).isEqualTo("KB");
        assertThat(response.getEmbeddingModel()).isEqualTo("default-embedding");
        assertThat(response.getEmbeddingDimension()).isEqualTo(1024);
        verify(repository).save(argThat(kb ->
                "default-embedding".equals(kb.getEmbeddingModel()) && kb.getEmbeddingDimension() == 1024));
    }

    @Test
    void createKeepsExplicitEmbeddingSettings() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("KB");
        request.setEmbeddingModel("custom");
        request.setEmbeddingDimension(256);
        when(repository.save(any(KnowledgeBase.class))).thenAnswer(inv -> {
            KnowledgeBase kb = inv.getArgument(0);
            kb.setId(UUID.randomUUID());
            return kb;
        });

        var response = service.create(request);

        assertThat(response.getEmbeddingModel()).isEqualTo("custom");
        assertThat(response.getEmbeddingDimension()).isEqualTo(256);
    }

    @Test
    void listMapsRepositoryEntitiesToResponses() {
        KnowledgeBase kb = KnowledgeBase.builder().id(UUID.randomUUID()).name("A").build();
        when(repository.findAll()).thenReturn(List.of(kb));

        assertThat(service.list()).extracting("name").containsExactly("A");
    }

    @Test
    void findOrThrowReturnsEntityOrRaisesNotFound() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(id).name("A").build();
        when(repository.findById(id)).thenReturn(Optional.of(kb));
        when(repository.findById(new UUID(0, 1))).thenReturn(Optional.empty());

        assertThat(service.findOrThrow(id)).isSameAs(kb);
        assertThatThrownBy(() -> service.findOrThrow(new UUID(0, 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Knowledge base not found");
    }

    @Test
    void deleteRemovesVectorsBeforeDatabaseRow() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(KnowledgeBase.builder().id(id).build()));

        service.delete(id);

        verify(milvusVectorService).deleteByKbId(id);
        verify(repository).deleteById(id);
    }
}
