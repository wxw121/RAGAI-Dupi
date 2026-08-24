package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
class AbandonedUploadCleanupService {
    private static final String PENDING_REASON = "UPLOAD_ABANDONED";

    private final DocumentTombstoneRepository tombstones;
    private final MinioStorageService storage;
    private final AbandonedUploadCleanupPersistence persistence;

    int cleanup(int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        int completed = 0;
        for (DocumentTombstone tombstone : tombstones.findByReasonOrderByCreatedAtAsc(
                PENDING_REASON, PageRequest.of(0, limit))) {
            try {
                storage.deleteChecked(tombstone.getObjectKey());
                persistence.complete(tombstone.getDocId(), tombstone.getObjectKey());
                completed++;
            } catch (RuntimeException failure) {
                log.warn("Failed to clean abandoned upload object for document {}",
                        tombstone.getDocId(), failure);
            }
        }
        return completed;
    }
}
