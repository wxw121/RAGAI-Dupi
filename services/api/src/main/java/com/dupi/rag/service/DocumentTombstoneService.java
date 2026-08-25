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

    static final String DOCUMENT_DELETE = "DOCUMENT_DELETE";
    static final String UPLOAD_WRITE_ARMED = "UPLOAD_WRITE_ARMED";
    static final String UPLOAD_ABANDONED = "UPLOAD_ABANDONED";

    private final DocumentTombstoneRepository repository;

    @Transactional
    public void recordDeleted(Document document) {
        record(document, DOCUMENT_DELETE);
    }

    @Transactional
    public void recordAbandonedUpload(Document document) {
        record(document, UPLOAD_ABANDONED);
    }

    @Transactional
    public void armUploadCleanup(Document document) {
        record(document, UPLOAD_WRITE_ARMED);
    }

    @Transactional
    public void disarmUploadCleanup(Document document) {
        if (document == null || document.getId() == null) {
            return;
        }
        repository.findByDocId(document.getId())
                .filter(tombstone -> UPLOAD_WRITE_ARMED.equals(tombstone.getReason()))
                .filter(tombstone -> java.util.Objects.equals(
                        document.getObjectKey(), tombstone.getObjectKey()))
                .ifPresent(repository::delete);
    }

    private void record(Document document, String reason) {
        if (document == null || document.getId() == null) {
            return;
        }
        DocumentTombstone existing = repository.findByDocId(document.getId()).orElse(null);
        if (existing != null) {
            if (UPLOAD_ABANDONED.equals(reason)
                    && !UPLOAD_ABANDONED.equals(existing.getReason())) {
                existing.setKbId(document.getKbId());
                existing.setObjectKey(document.getObjectKey());
                existing.setFileName(document.getFileName());
                existing.setReason(reason);
                repository.save(existing);
            }
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
