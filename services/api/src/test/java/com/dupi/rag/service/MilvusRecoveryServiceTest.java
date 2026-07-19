package com.dupi.rag.service;

import com.dupi.rag.config.MilvusProperties;
import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.dto.recovery.VectorSnapshotPage;
import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusRecoveryServiceTest {

    @Test
    void densePageIsBoundedSortedAndCursorBased() {
        RecordingPort port = new RecordingPort(List.of(
                row("chunk-b", List.of(0.2f, 0.3f)),
                row("chunk-a", List.of(0.1f, 0.4f))));
        MilvusRecoveryService service = service(port, 2);

        VectorSnapshotPage page = service.readDense(UUID.randomUUID(), null, 50);

        assertThat(page.rows()).extracting(VectorSnapshotRow::chunkId)
                .containsExactly("chunk-a", "chunk-b");
        assertThat(page.nextCursor()).isEqualTo("2");
        assertThat(port.requestedLimit).isEqualTo(2);
        assertThat(page.payloadChecksum()).hasSize(64);
    }

    @Test
    void snapshotSerializationIsStableAndNeverProducesDiagnostics() {
        MilvusRecoveryService service = service(new RecordingPort(List.of()), 10);
        VectorSnapshotRow row = row("chunk-a", List.of(0.1f, 0.4f));

        byte[] first = service.serializeRows(List.of(row));
        byte[] second = service.serializeRows(List.of(row));

        assertThat(first).isEqualTo(second);
        assertThat(new String(first)).contains("embedding").doesNotContain("provider", "credential", "baseUrl");
    }

    @Test
    void snapshotSerializationWrapsInvalidPayload() {
        MilvusRecoveryService service = service(new RecordingPort(List.of()), 10);
        Map<String, Object> recursive = new HashMap<>();
        recursive.put("self", recursive);
        VectorSnapshotRow invalid = new VectorSnapshotRow("chunk", "kb", "doc", "content", List.of(), recursive);

        assertThatThrownBy(() -> service.serializeRows(List.of(invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize vector snapshot");
    }

    @Test
    void upsertRejectsSchemaMismatchBeforeWriting() {
        RecordingPort port = new RecordingPort(List.of());
        port.schema = new MilvusRecoverySchema("L2", 8, Map.of());
        MilvusRecoveryService service = service(port, 10);

        assertThatThrownBy(() -> service.upsert(
                "chunks", new MilvusRecoverySchema("COSINE", 8, Map.of()), List.of(row("chunk", List.of(1f)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema mismatch");
        assertThat(port.upserted).isEmpty();
    }

    @Test
    void sparsePagingSuccessfulUpsertCountAndCursorValidation() {
        RecordingPort port = new RecordingPort(List.of(row("chunk-a", Map.of(1L, 0.5f))));
        port.schema = new MilvusRecoverySchema("BM25", 0, Map.of());
        MilvusRecoveryService service = service(port, 5);
        UUID kbId = UUID.randomUUID();

        assertThat(service.readSparse(kbId, 3, null, 5).rows()).hasSize(1);
        service.upsert(service.sparseCollection(kbId, 3), port.schema, port.rows);
        assertThat(port.upserted).hasSize(1);
        assertThat(service.count("chunks", kbId)).isEqualTo(1);
        assertThat(service.denseCollection()).isEqualTo("chunks");
        assertThat(service.describe("chunks")).isEqualTo(port.schema);
        assertThatThrownBy(() -> service.readDense(kbId, "bad", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.readDense(kbId, "-1", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.readSparse(kbId, 0, null, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    private MilvusRecoveryService service(MilvusRecoveryPort port, int pageSize) {
        MilvusProperties milvus = new MilvusProperties();
        milvus.setCollection("chunks");
        RecoveryProperties recovery = new RecoveryProperties();
        recovery.setPageSize(pageSize);
        return new MilvusRecoveryService(port, milvus, recovery, new ObjectMapper());
    }

    private VectorSnapshotRow row(String chunkId, Object embedding) {
        return new VectorSnapshotRow(chunkId, "kb", "doc", "content", embedding, Map.of());
    }

    private static final class RecordingPort implements MilvusRecoveryPort {
        private final List<VectorSnapshotRow> rows;
        private int requestedLimit;
        private MilvusRecoverySchema schema = new MilvusRecoverySchema("COSINE", 2, Map.of());
        private final List<VectorSnapshotRow> upserted = new ArrayList<>();

        private RecordingPort(List<VectorSnapshotRow> rows) { this.rows = rows; }

        @Override
        public List<VectorSnapshotRow> read(String collection, UUID kbId, long offset, int limit, boolean sparse) {
            requestedLimit = limit;
            return rows;
        }

        @Override
        public MilvusRecoverySchema describe(String collection) { return schema; }

        @Override
        public void ensure(String collection, MilvusRecoverySchema expected, boolean sparse) { }

        @Override
        public void upsert(String collection, List<VectorSnapshotRow> rows, boolean sparse) {
            upserted.addAll(rows);
        }

        @Override
        public long count(String collection, UUID kbId) { return rows.size(); }
    }
}
