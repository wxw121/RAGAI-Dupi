package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Short row-lock boundaries around replay-safe document cleanup I/O. */
@Service
@RequiredArgsConstructor
class DocumentDeletionPersistenceService {
    private final DocumentRepository documents;
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentTombstoneService tombstones;
    private final VectorCleanupTaskService vectorTasks;
    private final RetrievalProfileRepository profiles;
    private final ChunkRepository chunks;
    private final DocumentAssetService assets;
    private final UploadQuotaService quota;
    private final ProfileIndexStateService profileState;
    private final AuditLogService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DocumentDeletionClaim begin(UUID knowledgeBaseId, UUID documentId) {
        knowledgeBases.findForUpdateOrThrow(knowledgeBaseId);
        Document document = documents.findByIdForUpdate(documentId)
                .filter(candidate -> knowledgeBaseId.equals(candidate.getKbId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found: " + documentId));
        if (document.getStatus() != DocumentStatus.DELETING) {
            UploadIntentLifecyclePolicy.requireDocumentMutationAllowed(document);
            document.setStatus(DocumentStatus.DELETING);
            document.setErrorMessage(null);
            documents.save(document);
        }
        tombstones.recordDeleted(document);
        vectorTasks.enqueueProfileDocument(documentId);
        vectorTasks.enqueueLegacyDocument(documentId);
        List<Integer> sparseVersions = profiles.findByKbIdOrderByVersionDesc(knowledgeBaseId).stream()
                .map(profile -> profile.getVersion())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new DocumentDeletionClaim(
                knowledgeBaseId, documentId, document.getObjectKey(),
                document.getQuotaReservationId(), document.getFileName(),
                List.copyOf(sparseVersions));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(DocumentDeletionClaim claim) {
        KnowledgeBase knowledgeBase = knowledgeBases.findForUpdateOrThrow(claim.knowledgeBaseId());
        Document document = documents.findByIdForUpdate(claim.documentId())
                .filter(candidate -> claim.knowledgeBaseId().equals(candidate.getKbId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found: " + claim.documentId()));
        if (document.getStatus() != DocumentStatus.DELETING
                || !java.util.Objects.equals(claim.objectKey(), document.getObjectKey())) {
            throw new IllegalStateException("Document deletion claim is stale");
        }
        chunks.deleteByDocId(document.getId());
        assets.deleteMetadataInCurrentTransaction(document.getId());
        quota.releaseCommittedInCurrentTransaction(
                document.getQuotaReservationId(), "Document deleted");
        documents.delete(document);
        profileState.bumpRevision(knowledgeBase);
        audit.recordSuccess(
                "DOCUMENT_DELETE", "DOCUMENT", document.getId(),
                "Deleted document " + document.getFileName());
    }
}
