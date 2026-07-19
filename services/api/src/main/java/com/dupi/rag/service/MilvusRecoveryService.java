package com.dupi.rag.service;

import com.dupi.rag.config.MilvusProperties;
import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.dto.recovery.VectorSnapshotPage;
import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MilvusRecoveryService {
    private final MilvusRecoveryPort port;
    private final MilvusProperties milvus;
    private final RecoveryProperties recovery;
    private final ObjectMapper mapper;

    public MilvusRecoveryService(MilvusRecoveryPort port, MilvusProperties milvus,
                                 RecoveryProperties recovery, ObjectMapper mapper) {
        this.port = port;
        this.milvus = milvus;
        this.recovery = recovery;
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public VectorSnapshotPage readDense(UUID knowledgeBaseId, String cursor, int requestedLimit) {
        return read(milvus.getCollection(), knowledgeBaseId, cursor, requestedLimit, false);
    }

    public VectorSnapshotPage readSparse(UUID knowledgeBaseId, int profileVersion,
                                         String cursor, int requestedLimit) {
        if (profileVersion <= 0) throw new IllegalArgumentException("Sparse profile version must be positive");
        return read(sparseCollection(knowledgeBaseId, profileVersion), knowledgeBaseId, cursor, requestedLimit, true);
    }

    public VectorSnapshotPage readProfile(UUID knowledgeBaseId, String cursor, int requestedLimit) {
        long offset = parseCursor(cursor);
        int limit = Math.max(1, Math.min(requestedLimit, recovery.getPageSize()));
        List<VectorSnapshotRow> rows = port.readProfile(
                        profileCollection(), knowledgeBaseId, offset, limit).stream()
                .sorted(Comparator.comparing(VectorSnapshotRow::chunkId))
                .toList();
        String next = rows.size() < limit ? null : Long.toString(offset + rows.size());
        return new VectorSnapshotPage(rows, next, checksum(rows));
    }

    public MilvusRecoverySchema describe(String collection) {
        return port.describe(collection);
    }

    public String denseCollection() {
        return milvus.getCollection();
    }

    public String profileCollection() {
        return milvus.getProfileCollection();
    }

    public void upsert(String collection, MilvusRecoverySchema expected, List<VectorSnapshotRow> rows) {
        boolean sparse = collection.contains("_sparse_");
        port.ensure(collection, expected, sparse);
        MilvusRecoverySchema actual = port.describe(collection);
        if (!Objects.equals(expected.metric(), actual.metric())
                || expected.dimension() != actual.dimension()) {
            throw new IllegalArgumentException("Milvus recovery schema mismatch");
        }
        if (collection.equals(profileCollection())) {
            port.upsertProfile(collection, rows);
        } else {
            port.upsert(collection, rows, sparse);
        }
    }

    public long count(String collection, UUID knowledgeBaseId) {
        return port.count(collection, knowledgeBaseId);
    }

    public byte[] serializeRows(List<VectorSnapshotRow> rows) {
        StringBuilder output = new StringBuilder();
        rows.stream().sorted(Comparator.comparing(VectorSnapshotRow::chunkId)).forEach(row -> {
            try {
                output.append(mapper.writeValueAsString(row)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize vector snapshot", e);
            }
        });
        return output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public String checksum(List<VectorSnapshotRow> rows) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serializeRows(rows)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String sparseCollection(UUID knowledgeBaseId, int profileVersion) {
        return milvus.getCollection() + "_sparse_"
                + knowledgeBaseId.toString().replace("-", "").toLowerCase() + "_v" + profileVersion;
    }

    private VectorSnapshotPage read(String collection, UUID knowledgeBaseId, String cursor,
                                    int requestedLimit, boolean sparse) {
        long offset = parseCursor(cursor);
        int limit = Math.max(1, Math.min(requestedLimit, recovery.getPageSize()));
        List<VectorSnapshotRow> rows = port.read(collection, knowledgeBaseId, offset, limit, sparse).stream()
                .sorted(Comparator.comparing(VectorSnapshotRow::chunkId))
                .toList();
        String next = rows.size() < limit ? null : Long.toString(offset + rows.size());
        return new VectorSnapshotPage(rows, next, checksum(rows));
    }

    private long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            long value = Long.parseLong(cursor);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid vector snapshot cursor");
        }
    }
}
