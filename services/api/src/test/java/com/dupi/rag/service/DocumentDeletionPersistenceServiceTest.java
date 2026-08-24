package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentDeletionPersistenceServiceTest {

    @Test
    void beginClaimsDeletionUnderKbLockWithoutRemovingMetadata() {
        Fixture fixture = new Fixture();

        DocumentDeletionClaim claim = fixture.service.begin(fixture.kbId, fixture.document.getId());

        assertThat(claim.documentId()).isEqualTo(fixture.document.getId());
        assertThat(claim.sparseProfileVersions()).containsExactly(3, 1);
        assertThat(fixture.document.getStatus()).isEqualTo(DocumentStatus.DELETING);
        var order = inOrder(fixture.knowledgeBases, fixture.documents,
                fixture.tombstones, fixture.vectorTasks);
        order.verify(fixture.knowledgeBases).findForUpdateOrThrow(fixture.kbId);
        order.verify(fixture.documents).findByIdForUpdate(fixture.document.getId());
        order.verify(fixture.tombstones).recordDeleted(fixture.document);
        order.verify(fixture.vectorTasks).enqueueProfileDocument(fixture.document.getId());
        order.verify(fixture.vectorTasks).enqueueLegacyDocument(fixture.document.getId());
        verify(fixture.documents, never()).delete(fixture.document);
        verify(fixture.assets, never()).deleteMetadataInCurrentTransaction(fixture.document.getId());
    }

    @Test
    void finalizationDeletesMetadataOnlyAfterFreshKbAndDocumentFence() {
        Fixture fixture = new Fixture();
        DocumentDeletionClaim claim = fixture.service.begin(fixture.kbId, fixture.document.getId());

        fixture.service.complete(claim);

        var order = inOrder(fixture.knowledgeBases, fixture.documents, fixture.chunks,
                fixture.assets, fixture.quota, fixture.profileState);
        order.verify(fixture.knowledgeBases).findForUpdateOrThrow(fixture.kbId);
        order.verify(fixture.documents).findByIdForUpdate(fixture.document.getId());
        order.verify(fixture.knowledgeBases).findForUpdateOrThrow(fixture.kbId);
        order.verify(fixture.documents).findByIdForUpdate(fixture.document.getId());
        order.verify(fixture.chunks).deleteByDocId(fixture.document.getId());
        order.verify(fixture.assets).deleteMetadataInCurrentTransaction(fixture.document.getId());
        order.verify(fixture.quota).releaseCommittedInCurrentTransaction(
                fixture.document.getQuotaReservationId(), "Document deleted");
        order.verify(fixture.documents).delete(fixture.document);
        order.verify(fixture.profileState).bumpRevision(fixture.knowledgeBase);
    }

    private static class Fixture {
        final UUID kbId = UUID.randomUUID();
        final KnowledgeBase knowledgeBase = KnowledgeBase.builder().id(kbId).build();
        final Document document = Document.builder().id(UUID.randomUUID()).kbId(kbId)
                .fileName("a.md").objectKey("objects/a.md").quotaReservationId(UUID.randomUUID())
                .status(DocumentStatus.PENDING).build();
        final DocumentRepository documents = mock(DocumentRepository.class);
        final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        final DocumentTombstoneService tombstones = mock(DocumentTombstoneService.class);
        final VectorCleanupTaskService vectorTasks = mock(VectorCleanupTaskService.class);
        final RetrievalProfileRepository profiles = mock(RetrievalProfileRepository.class);
        final ChunkRepository chunks = mock(ChunkRepository.class);
        final DocumentAssetService assets = mock(DocumentAssetService.class);
        final UploadQuotaService quota = mock(UploadQuotaService.class);
        final ProfileIndexStateService profileState = mock(ProfileIndexStateService.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final DocumentDeletionPersistenceService service = new DocumentDeletionPersistenceService(
                documents, knowledgeBases, tombstones, vectorTasks, profiles, chunks,
                assets, quota, profileState, audit);

        Fixture() {
            when(knowledgeBases.findForUpdateOrThrow(kbId)).thenReturn(knowledgeBase);
            when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
            when(profiles.findByKbIdOrderByVersionDesc(kbId)).thenReturn(List.of(
                    RetrievalProfile.builder().version(3).build(),
                    RetrievalProfile.builder().version(1).build()));
        }
    }
}
