package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;

record DocumentUploadPublicationResolution(State state, Document document, IngestJob job) {
    enum State {
        PUBLISHED,
        RETAINED
    }

    static DocumentUploadPublicationResolution published(Document document, IngestJob job) {
        return new DocumentUploadPublicationResolution(State.PUBLISHED, document, job);
    }

    static DocumentUploadPublicationResolution retained() {
        return new DocumentUploadPublicationResolution(State.RETAINED, null, null);
    }

    boolean isPublished() {
        return state == State.PUBLISHED;
    }
}
