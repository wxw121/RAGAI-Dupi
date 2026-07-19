package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class SdkMilvusRecoveryPort implements MilvusRecoveryPort {
    private final MilvusServiceClient client;

    @Override
    public List<VectorSnapshotRow> read(String collection, UUID knowledgeBaseId,
                                        long offset, int limit, boolean sparse) {
        String vectorField = sparse ? "sparse_embedding" : "embedding";
        return read(collection, knowledgeBaseId, offset, limit, vectorField, List.of());
    }

    @Override
    public List<VectorSnapshotRow> readProfile(String collection, UUID knowledgeBaseId,
                                               long offset, int limit) {
        return read(collection, knowledgeBaseId, offset, limit, "embedding", List.of(
                "entry_kind", "profile_classic", "profile_parent_child",
                "profile_qa_assisted", "profile_combined"));
    }

    private List<VectorSnapshotRow> read(String collection, UUID knowledgeBaseId,
                                         long offset, int limit, String vectorField,
                                         List<String> scalarFields) {
        List<String> outputFields = new ArrayList<>(List.of(
                "chunk_id", "kb_id", "doc_id", "content", vectorField));
        outputFields.addAll(scalarFields);
        R<QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("kb_id == \"" + knowledgeBaseId + "\"")
                .withOutFields(outputFields)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withOffset(offset).withLimit((long) limit).build());
        requireSuccess(response, "query recovery vectors");
        return new QueryResultsWrapper(response.getData()).getRowRecords().stream()
                .map(record -> row(record.getFieldValues(), vectorField)).toList();
    }

    @Override
    public MilvusRecoverySchema describe(String collection) {
        R<DescribeCollectionResponse> response = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collection).build());
        requireSuccess(response, "describe recovery collection");
        CollectionSchema schema = response.getData().getSchema();
        boolean sparse = schema.getFieldsList().stream()
                .anyMatch(field -> "sparse_embedding".equals(field.getName()));
        int dimension = schema.getFieldsList().stream()
                .filter(field -> "embedding".equals(field.getName()))
                .flatMap(field -> field.getTypeParamsList().stream())
                .filter(param -> "dim".equalsIgnoreCase(param.getKey()))
                .mapToInt(param -> Integer.parseInt(param.getValue())).findFirst().orElse(0);
        Map<String, Object> settings = new TreeMap<>();
        schema.getFieldsList().forEach(field -> settings.put(field.getName(), field.getDataType().name()));
        return new MilvusRecoverySchema(sparse ? "BM25" : "COSINE", dimension, settings);
    }

    @Override
    public void ensure(String collection, MilvusRecoverySchema expected, boolean sparse) {
        R<Boolean> response = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        requireSuccess(response, "check recovery collection");
        if (!Boolean.TRUE.equals(response.getData())) {
            throw new IllegalArgumentException("Milvus recovery collection does not exist: " + collection);
        }
    }

    @Override
    public void upsert(String collection, List<VectorSnapshotRow> rows, boolean sparse) {
        if (rows.isEmpty()) return;
        List<InsertParam.Field> fields = baseFields(rows);
        if (!sparse) {
            fields.add(new InsertParam.Field("embedding", rows.stream()
                    .map(row -> denseEmbedding(row.embedding())).toList()));
        }
        R<MutationResult> response = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(collection).withFields(fields).build());
        requireSuccess(response, "upsert recovery vectors");
    }

    @Override
    public void upsertProfile(String collection, List<VectorSnapshotRow> rows) {
        if (rows.isEmpty()) return;
        List<InsertParam.Field> fields = baseFields(rows);
        for (String field : List.of(
                "entry_kind", "profile_classic", "profile_parent_child",
                "profile_qa_assisted", "profile_combined")) {
            fields.add(new InsertParam.Field(field, rows.stream()
                    .map(row -> requiredProfileScalar(row, field)).toList()));
        }
        fields.add(new InsertParam.Field("embedding", rows.stream()
                .map(row -> denseEmbedding(row.embedding())).toList()));
        R<MutationResult> response = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(collection).withFields(fields).build());
        requireSuccess(response, "upsert recovery profile vectors");
    }

    @Override
    public long count(String collection, UUID knowledgeBaseId) {
        R<QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("kb_id == \"" + knowledgeBaseId + "\"")
                .withOutFields(List.of("count(*)"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG).build());
        requireSuccess(response, "count recovery vectors");
        List<QueryResultsWrapper.RowRecord> rows = new QueryResultsWrapper(response.getData()).getRowRecords();
        if (rows.isEmpty()) return 0;
        Object count = rows.get(0).getFieldValues().get("count(*)");
        return count instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(count));
    }

    private VectorSnapshotRow row(Map<String, Object> values, String vectorField) {
        Map<String, Object> scalar = new TreeMap<>(values);
        Object embedding = scalar.remove(vectorField);
        scalar.keySet().removeAll(Set.of("chunk_id", "kb_id", "doc_id", "content"));
        return new VectorSnapshotRow(String.valueOf(values.get("chunk_id")),
                String.valueOf(values.get("kb_id")), String.valueOf(values.get("doc_id")),
                String.valueOf(values.get("content")), embedding, scalar);
    }

    private List<InsertParam.Field> baseFields(List<VectorSnapshotRow> rows) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("chunk_id", rows.stream().map(VectorSnapshotRow::chunkId).toList()));
        fields.add(new InsertParam.Field("kb_id", rows.stream().map(VectorSnapshotRow::knowledgeBaseId).toList()));
        fields.add(new InsertParam.Field("doc_id", rows.stream().map(VectorSnapshotRow::documentId).toList()));
        fields.add(new InsertParam.Field("content", rows.stream().map(VectorSnapshotRow::content).toList()));
        return fields;
    }

    private Object requiredProfileScalar(VectorSnapshotRow row, String field) {
        Object value = row.scalarPayload() == null ? null : row.scalarPayload().get(field);
        if (value == null) {
            throw new IllegalArgumentException("Profile recovery row missing scalar field: " + field);
        }
        if ("entry_kind".equals(field) && !(value instanceof String)) {
            throw new IllegalArgumentException("Profile recovery entry_kind must be a string");
        }
        if (!"entry_kind".equals(field) && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Profile recovery flags must be booleans");
        }
        return value;
    }

    private List<Float> denseEmbedding(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Dense recovery embedding must be a numeric list");
        }
        return values.stream().map(element -> {
            if (!(element instanceof Number number)) {
                throw new IllegalArgumentException("Dense recovery embedding must be a numeric list");
            }
            return number.floatValue();
        }).toList();
    }

    private void requireSuccess(R<?> response, String operation) {
        if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            throw new IllegalStateException("Failed to " + operation + ": "
                    + (response == null ? "empty response" : response.getMessage()));
        }
    }
}
