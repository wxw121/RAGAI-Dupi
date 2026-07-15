package com.dupi.rag.service;

import java.util.UUID;

public interface RecoveryActivityProbe {
    boolean hasActiveWork(UUID knowledgeBaseId);
}
