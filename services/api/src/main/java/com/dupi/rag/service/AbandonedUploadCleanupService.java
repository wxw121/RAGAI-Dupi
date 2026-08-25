package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
class AbandonedUploadCleanupService {
    private final DocumentTombstoneRepository tombstones;
    private final MinioStorageService storage;
    private final AbandonedUploadCleanupPersistence persistence;

    int cleanup(int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        int completed = 0;
        for (String reason : java.util.List.of(
                DocumentTombstoneService.UPLOAD_ABANDONED,
                DocumentTombstoneService.UPLOAD_WRITE_ARMED)) {
            for (DocumentTombstone tombstone : tombstones.findByReasonOrderByCreatedAtAsc(reason)) {
                if (completed >= limit) {
                    return completed;
                }
                if (DocumentTombstoneService.UPLOAD_WRITE_ARMED.equals(reason)
                        && !persistence.shouldReplayArmed(
                                tombstone.getDocId(), tombstone.getObjectKey())) {
                    continue;
                }
                try {
                    storage.deleteChecked(tombstone.getObjectKey());
                    persistence.complete(tombstone.getDocId(), tombstone.getObjectKey());
                    completed++;
                } catch (RuntimeException failure) {
                    log.warn("Failed to clean abandoned upload object for document {}",
                            tombstone.getDocId(), failure);
                }
            }
        }
        return completed;
    }
}
