package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class AbandonedUploadCleanupPersistence {
    private final DocumentTombstoneRepository tombstones;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UUID documentId, String objectKey) {
        DocumentTombstone tombstone = tombstones.findByDocId(documentId).orElse(null);
        if (tombstone == null
                || !"UPLOAD_ABANDONED".equals(tombstone.getReason())
                || !java.util.Objects.equals(objectKey, tombstone.getObjectKey())) {
            return;
        }
        tombstone.setReason("UPLOAD_ABANDONED_CLEANED");
        tombstones.save(tombstone);
    }
}
