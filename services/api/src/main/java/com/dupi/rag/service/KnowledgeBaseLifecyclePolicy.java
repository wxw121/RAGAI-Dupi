package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;

import java.util.UUID;

/** One lifecycle rule shared by public access and row-locked mutation boundaries. */
final class KnowledgeBaseLifecyclePolicy {
    private KnowledgeBaseLifecyclePolicy() { }

    static KnowledgeBase requireReady(KnowledgeBase knowledgeBase, UUID id) {
        if (knowledgeBase.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.DELETING) {
            throw new OperationConflictException(
                    "Knowledge base deletion is in progress; inspect the deletion operation status");
        }
        if (knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.READY) {
            throw new ResourceNotFoundException("Knowledge base not found: " + id);
        }
        return knowledgeBase;
    }
}
