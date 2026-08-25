package com.dupi.rag.service;

import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.RetrievalProfileConflictException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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
    AuditLogService auditLogService;
    @Mock
    ProfileIndexStateService profileIndexStateService;
    @Mock
    RetrievalProfileGateService retrievalProfileGateService;
    @Mock
    KnowledgeBaseMaintenanceService maintenanceService;
    @Mock
    KnowledgeBaseDeletionPersistenceService deletionPersistence;
    @Mock
    OperationJobService operationJobService;

    LlmProperties llmProperties;
    KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.getEmbedding().setModel("default-embedding");
        llmProperties.getEmbedding().setDimension(1024);
        service = new KnowledgeBaseService(
                repository,
                llmProperties,
                auditLogService,
                profileIndexStateService,
                retrievalProfileGateService,
                maintenanceService,
                deletionPersistence,
                operationJobService
        );
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
    void createRejectsNonClassicProfileDefaults() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("KB");
        request.setRetrievalProfile(RetrievalProfile.PARENT_CHILD);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(RetrievalProfileConflictException.class)
                .hasMessageContaining("not_evaluated");
        verify(repository, never()).save(any());
    }

    @Test
    void updateRetrievalProfileAllowsClassicWithoutGate() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(id)
                .name("KB")
                .retrievalProfile(RetrievalProfile.QA_ASSISTED)
                .build();
        when(repository.findByIdAndTenantIdForUpdateAnyStatus(id, "default")).thenReturn(Optional.of(kb));
        when(repository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateRetrievalProfile(id, RetrievalProfile.CLASSIC);

        assertThat(response.getRetrievalProfile()).isEqualTo(RetrievalProfile.CLASSIC);
        verify(retrievalProfileGateService, never()).assertCanActivate(any(), any());
        verify(repository).findByIdAndTenantIdForUpdateAnyStatus(id, "default");
        verify(repository).save(argThat(saved -> saved.getRetrievalProfile() == RetrievalProfile.CLASSIC));
    }

    @Test
    void updateRetrievalProfileRequiresPassedGateForNonClassic() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(id)
                .name("KB")
                .retrievalProfile(RetrievalProfile.CLASSIC)
                .build();
        when(repository.findByIdAndTenantIdForUpdateAnyStatus(id, "default")).thenReturn(Optional.of(kb));
        when(repository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateRetrievalProfile(id, RetrievalProfile.PARENT_CHILD);

        assertThat(response.getRetrievalProfile()).isEqualTo(RetrievalProfile.PARENT_CHILD);
        verify(retrievalProfileGateService).assertCanActivate(id, RetrievalProfile.PARENT_CHILD);
        verify(repository).findByIdAndTenantIdForUpdateAnyStatus(id, "default");
        verify(repository).save(argThat(saved -> saved.getRetrievalProfile() == RetrievalProfile.PARENT_CHILD));
        verify(maintenanceService).assertMutationAllowed(id);
        verify(auditLogService).recordSuccessInCurrentTransaction(
                eq("KNOWLEDGE_BASE_RETRIEVAL_PROFILE_UPDATE"),
                eq("KNOWLEDGE_BASE"),
                eq(id),
                contains("PARENT_CHILD"));
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
        when(repository.findByIdAndTenantIdAnyStatus(id, "default")).thenReturn(Optional.of(kb));

        var response = service.get(id);

        assertThat(response.isEmbeddingConfigCurrent()).isFalse();
        assertThat(response.getEmbeddingConfigWarning())
                .contains("text-embedding-3-small")
                .contains("default-embedding")
                .contains("re-index");
    }


    @Test
    void responseIncludesProfileIndexReadinessRevisionAndGateDecisions() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(id)
                .name("Ready")
                .indexRevision(7L)
                .retrievalProfile(RetrievalProfile.CLASSIC)
                .build();
        RagEvalGateDecisionResponse gate = RagEvalGateDecisionResponse.builder()
                .candidate(RetrievalProfile.PARENT_CHILD)
                .baseline(RetrievalProfile.CLASSIC)
                .status(RagEvalGateStatus.PASSED)
                .reason("passed")
                .build();
        when(repository.findByIdAndTenantIdAnyStatus(id, "default")).thenReturn(Optional.of(kb));
        when(profileIndexStateService.isV2Ready(id)).thenReturn(true);
        when(retrievalProfileGateService.latestDecision(id, RetrievalProfile.PARENT_CHILD)).thenReturn(gate);

        KnowledgeBaseResponse response = service.get(id);

        assertThat(response.getIndexSchemaVersion()).isEqualTo(ProfileIndexStateService.TARGET_SCHEMA_VERSION);
        assertThat(response.isProfileIndexReady()).isTrue();
        assertThat(response.getIndexRevision()).isEqualTo(7L);
        assertThat(response.getRetrievalProfileGateDecisions())
                .containsEntry(RetrievalProfile.PARENT_CHILD, gate);
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
        when(repository.findByIdAndTenantIdAnyStatus(id, "default")).thenReturn(Optional.of(kb));
        when(repository.findByIdAndTenantIdAnyStatus(new UUID(0, 1), "default")).thenReturn(Optional.empty());

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
            when(repository.findByIdAndTenantIdAnyStatus(hiddenId, "tenant-a")).thenReturn(Optional.empty());

            assertThat(service.create(request).getTenantId()).isEqualTo("tenant-a");
            assertThat(service.list()).extracting("tenantId").containsExactly("tenant-a");
            assertThatThrownBy(() -> service.findOrThrow(hiddenId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(repository).save(argThat(kb -> "tenant-a".equals(kb.getTenantId())));
            verify(repository).findByTenantIdOrderByCreatedAtDesc("tenant-a");
            verify(repository).findByIdAndTenantIdAnyStatus(hiddenId, "tenant-a");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void repeatedDeleteSubmissionReturnsTheSameDurableJob() {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OperationJobResponse response = OperationJobResponse.builder().id(jobId)
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).aggregateId(id)
                .status(OperationStatus.PREPARED).build();
        when(deletionPersistence.submit(id, "default", "alice")).thenReturn(jobId);
        when(operationJobService.get(jobId)).thenReturn(response);

        assertThat(service.submitDelete(id, "alice")).isSameAs(response);
        assertThat(service.submitDelete(id, "alice")).isSameAs(response);

        verify(deletionPersistence, times(2)).submit(id, "default", "alice");
        verify(operationJobService, times(2)).get(jobId);
    }

    @Test
    void ordinaryReadReportsConflictForDeletingKnowledgeBase() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantIdAnyStatus(id, "default")).thenReturn(Optional.of(
                KnowledgeBase.builder().id(id).tenantId("default")
                        .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build()));

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("deletion");
    }

    @Test
    void lockedAndSystemLookupsAlsoReportDeletingAsConflictInsteadOfNotFoundOrUsable() {
        UUID id = UUID.randomUUID();
        KnowledgeBase deleting = KnowledgeBase.builder().id(id).tenantId("default")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build();
        when(repository.findByIdAndTenantIdForUpdateAnyStatus(id, "default"))
                .thenReturn(Optional.of(deleting));
        when(repository.findById(id)).thenReturn(Optional.of(deleting));
        when(repository.findSystemByIdForUpdate(id)).thenReturn(Optional.of(deleting));

        assertThatThrownBy(() -> service.findForUpdateOrThrow(id))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.findSystemOrThrow(id))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.findSystemForUpdateOrThrow(id))
                .isInstanceOf(OperationConflictException.class);
    }
}
