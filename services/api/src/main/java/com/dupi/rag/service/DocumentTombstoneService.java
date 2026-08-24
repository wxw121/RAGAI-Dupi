package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentTombstoneService {

    private final DocumentTombstoneRepository repository;

    @Transactional
    public void recordDeleted(Document document) {
        record(document, "DOCUMENT_DELETE");
    }

    @Transactional
    public void recordAbandonedUpload(Document document) {
        record(document, "UPLOAD_ABANDONED");
    }

    private void record(Document document, String reason) {
        if (document == null || document.getId() == null) {
            return;
        }
        if (repository.existsById(document.getId())) {
            return;
        }
        repository.save(DocumentTombstone.builder()
                .docId(document.getId())
                .kbId(document.getKbId())
                .objectKey(document.getObjectKey())
                .fileName(document.getFileName())
                .reason(reason)
                .build());
    }

    @Transactional(readOnly = true)
    public boolean isDeleted(UUID docId) {
        return docId != null && repository.existsById(docId);
    }
}
