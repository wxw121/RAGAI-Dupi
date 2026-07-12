package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.TenantContext;
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
    @Mock
    VectorCleanupTaskService vectorCleanupTaskService;
    @Mock
    AuditLogService auditLogService;

    LlmProperties llmProperties;
    KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.getEmbedding().setModel("default-embedding");
        llmProperties.getEmbedding().setDimension(1024);
        service = new KnowledgeBaseService(repository, milvusVectorService, llmProperties, vectorCleanupTaskService, auditLogService);
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
        assertThat(response.isEmbeddingConfigCurrent()).isTrue();
        assertThat(response.getEmbeddingConfigWarning()).isNull();
        verify(repository).save(argThat(kb ->
                "default-embedding".equals(kb.getEmbeddingModel())
                        && kb.getEmbeddingDimension() == 1024
                        && "default".equals(kb.getTenantId())));
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
        assertThat(response.isEmbeddingConfigCurrent()).isFalse();
        assertThat(response.getEmbeddingConfigWarning()).contains("current embedding config");
    }

    @Test
    void getMarksExistingKnowledgeBaseWhenEmbeddingConfigIsOutdated() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(id)
                .name("Old")
                .embeddingModel("text-embedding-3-small")
                .embeddingDimension(1536)
                .build();
        when(repository.findByIdAndTenantId(id, "default")).thenReturn(Optional.of(kb));

        var response = service.get(id);

        assertThat(response.isEmbeddingConfigCurrent()).isFalse();
        assertThat(response.getEmbeddingConfigWarning())
                .contains("text-embedding-3-small")
                .contains("default-embedding")
                .contains("re-index");
    }

    @Test
    void listMapsRepositoryEntitiesToResponses() {
        KnowledgeBase kb = KnowledgeBase.builder().id(UUID.randomUUID()).name("A").build();
        when(repository.findByTenantIdOrderByCreatedAtDesc("default")).thenReturn(List.of(kb));

        assertThat(service.list()).extracting("name").containsExactly("A");
    }

    @Test
    void findOrThrowReturnsEntityOrRaisesNotFound() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(id).name("A").build();
        when(repository.findByIdAndTenantId(id, "default")).thenReturn(Optional.of(kb));
        when(repository.findByIdAndTenantId(new UUID(0, 1), "default")).thenReturn(Optional.empty());

        assertThat(service.findOrThrow(id)).isSameAs(kb);
        assertThatThrownBy(() -> service.findOrThrow(new UUID(0, 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Knowledge base not found");
    }

    @Test
    void currentTenantScopesCreateListAndLookup() {
        TenantContext.setTenantId("tenant-a");
        try {
            CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
            request.setName("Tenant KB");
            when(repository.save(any(KnowledgeBase.class))).thenAnswer(inv -> {
                KnowledgeBase kb = inv.getArgument(0);
                kb.setId(UUID.randomUUID());
                return kb;
            });
            when(repository.findByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of(
                    KnowledgeBase.builder().id(UUID.randomUUID()).tenantId("tenant-a").name("Tenant KB").build()
            ));
            UUID hiddenId = UUID.randomUUID();
            when(repository.findByIdAndTenantId(hiddenId, "tenant-a")).thenReturn(Optional.empty());

            assertThat(service.create(request).getTenantId()).isEqualTo("tenant-a");
            assertThat(service.list()).extracting("tenantId").containsExactly("tenant-a");
            assertThatThrownBy(() -> service.findOrThrow(hiddenId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(repository).save(argThat(kb -> "tenant-a".equals(kb.getTenantId())));
            verify(repository).findByTenantIdOrderByCreatedAtDesc("tenant-a");
            verify(repository).findByIdAndTenantId(hiddenId, "tenant-a");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void deleteRemovesVectorsBeforeDatabaseRow() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, "default")).thenReturn(Optional.of(KnowledgeBase.builder().id(id).build()));

        service.delete(id);

        verify(vectorCleanupTaskService).enqueueKnowledgeBase(id);
        verify(milvusVectorService).deleteByKbId(id);
        verify(repository).deleteById(id);
        verify(auditLogService).recordSuccess(
                eq("KNOWLEDGE_BASE_DELETE"),
                eq("KNOWLEDGE_BASE"),
                eq(id),
                contains(id.toString())
        );
    }

    @Test
    void deleteStillRemovesDatabaseRowWhenVectorCleanupFails() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, "default")).thenReturn(Optional.of(KnowledgeBase.builder().id(id).build()));
        doThrow(new IllegalStateException("milvus down")).when(milvusVectorService).deleteByKbId(id);

        service.delete(id);

        verify(vectorCleanupTaskService).enqueueKnowledgeBase(id);
        verify(repository).deleteById(id);
        verify(auditLogService).recordSuccess(
                eq("KNOWLEDGE_BASE_DELETE"),
                eq("KNOWLEDGE_BASE"),
                eq(id),
                contains("compensation")
        );
    }
}
