package com.dupi.rag.service;

import java.util.List;
import java.util.UUID;

record DocumentDeletionClaim(
        UUID knowledgeBaseId,
        UUID documentId,
        String objectKey,
        UUID quotaReservationId,
        String fileName,
        List<Integer> sparseProfileVersions) {
}
