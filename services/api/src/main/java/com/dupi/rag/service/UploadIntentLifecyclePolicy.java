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
            throw conflict();
        }
    }

    static void requireJobCancellationAllowed(IngestJob job) {
        if (job.getStatus() == IngestJobStatus.UPLOAD_INTENT) {
            throw conflict();
        }
    }

    static void requireReindexAllowed(Collection<Document> documents) {
        if (documents.stream().anyMatch(document -> document.getStatus() == DocumentStatus.UPLOADING)) {
            throw conflict();
        }
    }

    private static OperationConflictException conflict() {
        return new OperationConflictException("Document upload is still in progress");
    }
}
