package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
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

    @Transactional
    public void bumpRevision(KnowledgeBase kb) {
        KnowledgeBase locked = knowledgeBaseRepository.findByIdForUpdate(kb.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + kb.getId()
                ));
        long current = locked.getIndexRevision() == null ? 0L : locked.getIndexRevision();
        locked.setIndexRevision(current + 1);
        knowledgeBaseRepository.save(locked);
        kb.setIndexRevision(locked.getIndexRevision());
    }

    @Transactional
    public void resetForReindex(KnowledgeBase kb, List<Document> documents) {
        documents.forEach(document -> document.setIndexSchemaVersion(1));
        documentRepository.saveAll(documents);
        bumpRevision(kb);
    }
}
