package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.VectorSnapshotRow;

import java.util.List;
import java.util.UUID;

public interface MilvusRecoveryPort {
    List<VectorSnapshotRow> read(String collection, UUID knowledgeBaseId, long offset, int limit, boolean sparse);
    default List<VectorSnapshotRow> readProfile(String collection, UUID knowledgeBaseId, long offset, int limit) {
        return read(collection, knowledgeBaseId, offset, limit, false);
    }
    MilvusRecoverySchema describe(String collection);
    void ensure(String collection, MilvusRecoverySchema expected, boolean sparse);
    void upsert(String collection, List<VectorSnapshotRow> rows, boolean sparse);
    default void upsertProfile(String collection, List<VectorSnapshotRow> rows) {
        upsert(collection, rows, false);
    }
    long count(String collection, UUID knowledgeBaseId);
}
