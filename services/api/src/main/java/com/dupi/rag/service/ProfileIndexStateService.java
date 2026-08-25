package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileIndexStateService {

    public static final int TARGET_SCHEMA_VERSION = 2;

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public boolean isV2Ready(UUID kbId) {
        return documentRepository.countByKbId(kbId) > 0
                && documentRepository.countByKbIdAndStatusNot(kbId, DocumentStatus.COMPLETED) == 0
                && documentRepository.countByKbIdAndIndexSchemaVersionLessThan(
                        kbId,
                        TARGET_SCHEMA_VERSION
                ) == 0;
    }

    public long currentRevision(UUID kbId) {
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::getIndexRevision)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + kbId));
    }

    public boolean isV2Activated(UUID kbId) {
        return knowledgeBaseRepository.findById(kbId)
                .map(KnowledgeBase::isProfileIndexActivated)
                .orElse(false);
    }

    public boolean shouldDeferLegacyCleanup(UUID kbId) {
        return knowledgeBaseRepository.findById(kbId).isPresent() && !isV2Ready(kbId);
    }

    /**
     * Establishes the reindex mutation intent under the tenant-owned knowledge-base row lock.
     * Callers must use the returned managed aggregate for every subsequent reindex mutation.
     */
    @Transactional
    public KnowledgeBase lockForReindex(
            UUID kbId, String tenantId, String embeddingModel, int embeddingDimension) {
        KnowledgeBase locked = knowledgeBaseRepository
                .findByIdAndTenantIdForUpdateAnyStatus(kbId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + kbId));
        KnowledgeBaseLifecyclePolicy.requireReady(locked, kbId);
        locked.setEmbeddingModel(embeddingModel);
        locked.setEmbeddingDimension(embeddingDimension);
        return locked;
    }

    @Transactional
    public void activateV2Index(KnowledgeBase kb) {
        KnowledgeBase locked = lockReady(kb);
        locked.setProfileIndexActivated(true);
        knowledgeBaseRepository.save(locked);
        kb.setProfileIndexActivated(true);
    }

    @Transactional
    public void bumpRevision(KnowledgeBase kb) {
        KnowledgeBase locked = lockReady(kb);
        long current = locked.getIndexRevision() == null ? 0L : locked.getIndexRevision();
        locked.setIndexRevision(current + 1);
        knowledgeBaseRepository.save(locked);
        kb.setIndexRevision(locked.getIndexRevision());
    }

    @Transactional
    public void resetForReindex(KnowledgeBase kb, List<Document> documents) {
        documents.forEach(document -> document.setIndexSchemaVersion(1));
        documentRepository.saveAll(documents);
        long current = kb.getIndexRevision() == null ? 0L : kb.getIndexRevision();
        kb.setIndexRevision(current + 1);
        knowledgeBaseRepository.save(kb);
    }

    private KnowledgeBase lockReady(KnowledgeBase kb) {
        KnowledgeBase locked = knowledgeBaseRepository
                .findByIdAndTenantIdForUpdateAnyStatus(kb.getId(), kb.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + kb.getId()));
        return KnowledgeBaseLifecyclePolicy.requireReady(locked, kb.getId());
    }
}
