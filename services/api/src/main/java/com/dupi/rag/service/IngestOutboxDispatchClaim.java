package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;

import java.util.UUID;

record IngestOutboxDispatchClaim(
        UUID eventId,
        String claimMarker,
        UUID executionId,
        IngestJob job,
        Document document,
        KnowledgeBase knowledgeBase,
        String objectKey,
        String fileName,
        String mimeType
) { }
