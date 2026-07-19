package com.dupi.rag.service;

import java.util.Map;
import java.util.UUID;

public interface SparseRecoveryProvisioner {
    void ensure(UUID knowledgeBaseId, int embeddingDimension, int profileVersion,
                Map<String, Object> indexParameters);
}
