package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.exception.OperationConflictException;

import java.util.Collection;

final class UploadIntentLifecyclePolicy {
    private UploadIntentLifecyclePolicy() {
    }

    static void requireDocumentMutationAllowed(Document document) {
        if (document.getStatus() == DocumentStatus.UPLOADING) {
            throw uploadConflict();
        }
        if (document.getStatus() == DocumentStatus.DELETING) {
            throw deletionConflict();
        }
    }

    static void requireJobCancellationAllowed(IngestJob job) {
        if (job.getStatus() == IngestJobStatus.UPLOAD_INTENT) {
            throw uploadConflict();
        }
    }

    static void requireReindexAllowed(Collection<Document> documents) {
        for (Document document : documents) {
            requireDocumentMutationAllowed(document);
        }
    }

    static boolean isDeleting(Document document) {
        return document != null && document.getStatus() == DocumentStatus.DELETING;
    }

    private static OperationConflictException uploadConflict() {
        return new OperationConflictException("Document upload is still in progress");
    }

    private static OperationConflictException deletionConflict() {
        return new OperationConflictException("Document deletion is still in progress");
    }
}
