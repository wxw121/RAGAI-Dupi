package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.VectorSnapshotRow;

import java.util.List;
import java.util.UUID;

public interface MilvusRecoveryPort {
    List<VectorSnapshotRow> read(String collection, UUID knowledgeBaseId, long offset, int limit, boolean sparse);
    MilvusRecoverySchema describe(String collection);
    void ensure(String collection, MilvusRecoverySchema expected, boolean sparse);
    void upsert(String collection, List<VectorSnapshotRow> rows, boolean sparse);
    long count(String collection, UUID knowledgeBaseId);
}
