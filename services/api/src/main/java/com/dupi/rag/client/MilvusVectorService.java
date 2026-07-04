package com.dupi.rag.client;

import com.dupi.rag.config.MilvusProperties;
import com.dupi.rag.config.LlmProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorService {

    private final MilvusServiceClient client;
    private final MilvusProperties properties;
    private final LlmProperties llmProperties;

    @PostConstruct
    public void ensureCollection() {
        String collection = properties.getCollection();
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        if (Boolean.TRUE.equals(has.getData())) {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .build());
            return;
        }

        int dim = llmProperties.getEmbedding().getDimension();
        FieldType chunkId = FieldType.newBuilder()
                .withName("chunk_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .build();
        FieldType kbId = FieldType.newBuilder()
                .withName("kb_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
        FieldType docId = FieldType.newBuilder()
                .withName("doc_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build();
        FieldType content = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();
        FieldType vector = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dim)
                .build();

        CreateCollectionParam create = CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .addFieldType(chunkId)
                .addFieldType(kbId)
                .addFieldType(docId)
                .addFieldType(content)
                .addFieldType(vector)
                .build();
        R<RpcStatus> created = client.createCollection(create);
        if (created.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus create collection: {}", created.getMessage());
        }

        CreateIndexParam index = CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName("embedding")
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                .build();
        client.createIndex(index);
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        log.info("Milvus collection {} ready", collection);
    }

    public List<SearchResult> search(UUID kbId, List<Float> queryVector, int topK) {
        List<String> outputFields = List.of("chunk_id", "doc_id", "content");
        String expr = "kb_id == \"" + kbId + "\"";

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withExpr(expr)
                .withOutFields(outputFields)
                .build();

        R<SearchResults> response = client.search(param);
        if (response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            if (isCollectionLoading(response.getMessage())) {
                log.warn("Milvus collection {} is still loading; returning empty search results", properties.getCollection());
                return List.of();
            }
            throw new IllegalStateException("Milvus search failed: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResult> results = new ArrayList<>();
        if (wrapper.getRowRecords(0).isEmpty()) {
            return results;
        }
        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
            Map<String, Object> fieldValues = wrapper.getRowRecords(0).get(i).getFieldValues();
            results.add(new SearchResult(
                    String.valueOf(fieldValues.get("chunk_id")),
                    String.valueOf(fieldValues.get("doc_id")),
                    String.valueOf(fieldValues.get("content")),
                    idScore.getScore()
            ));
        }
        return results;
    }

    private boolean isCollectionLoading(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("collection not loaded")
                || normalized.contains("collection not fully loaded")
                || normalized.contains("wait for loading collection timeout");
    }

    public void deleteByDocId(UUID docId) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withExpr("doc_id == \"" + docId + "\"")
                .build();
        client.delete(param);
    }

    public void deleteByKbId(UUID kbId) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withExpr("kb_id == \"" + kbId + "\"")
                .build();
        client.delete(param);
    }

    public record SearchResult(String chunkId, String docId, String content, double score) {}
}
