package com.dupi.rag.client;

import com.dupi.rag.config.MilvusProperties;
import com.dupi.rag.config.LlmProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.GetLoadingProgressResponse;
import io.milvus.grpc.GetLoadStateResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.LoadState;
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
            validateExistingCollectionSchema(collection);
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withSyncLoad(false)
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
                .withSyncLoad(false)
                .build());
        log.info("Milvus collection {} load requested asynchronously", collection);
    }

    private void validateExistingCollectionSchema(String collection) {
        R<DescribeCollectionResponse> described = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        if (described == null
                || described.getStatus() != R.Status.Success.getCode()
                || described.getData() == null
                || !described.getData().hasSchema()) {
            throw new IllegalStateException("Milvus collection schema is unavailable: collection=" + collection
                    + " message=" + (described != null ? described.getMessage() : "empty describe response"));
        }

        CollectionSchema schema = described.getData().getSchema();
        FieldSchema vectorField = schema.getFieldsList().stream()
                .filter(field -> "embedding".equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Milvus collection schema is missing embedding field: collection=" + collection));
        if (vectorField.getDataType() != DataType.FloatVector) {
            throw new IllegalStateException("Milvus collection embedding field type mismatch: collection=" + collection
                    + " expected=FloatVector actual=" + vectorField.getDataType());
        }

        int expectedDimension = llmProperties.getEmbedding().getDimension();
        int actualDimension = vectorField.getTypeParamsList().stream()
                .filter(param -> "dim".equalsIgnoreCase(param.getKey()))
                .map(KeyValuePair::getValue)
                .findFirst()
                .map(this::parseDimension)
                .orElseThrow(() -> new IllegalStateException(
                        "Milvus collection embedding dimension is missing: collection=" + collection));
        if (actualDimension != expectedDimension) {
            throw new IllegalStateException("Milvus collection embedding dimension mismatch: collection=" + collection
                    + " expected=" + expectedDimension
                    + " actual=" + actualDimension
                    + ". Reset the collection or configure a dimension-specific collection before startup.");
        }
    }

    private int parseDimension(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Milvus collection embedding dimension is invalid: " + value, e);
        }
    }

    public List<SearchResult> search(UUID kbId, List<Float> queryVector, int topK) {
        ensureSearchableCollection();

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
                // Milvus 集合未加载属于检索基础设施故障，不能降级为空命中。
                // 如果这里返回空列表，聊天层会误以为知识库缺少资料，并输出“根据现有知识库资料无法回答”。
                throw new IllegalStateException("Milvus collection is not ready for search: " + response.getMessage());
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

    private void ensureSearchableCollection() {
        String collection = properties.getCollection();
        R<GetLoadStateResponse> loadState = client.getLoadState(GetLoadStateParam.newBuilder()
                .withCollectionName(collection)
                .build());
        if (loadState == null || loadState.getStatus() != R.Status.Success.getCode() || loadState.getData() == null) {
            throw new IllegalStateException("Milvus collection is not ready for search: state=unknown progress=unknown message="
                    + (loadState != null ? loadState.getMessage() : "empty load state response"));
        }

        LoadState state = loadState.getData().getState();
        if (state == LoadState.LoadStateLoaded) {
            return;
        }

        // 先查询 Milvus 的加载状态再搜索，避免集合卡在 Loading 时让 search 请求阻塞到超时。
        // 这里暴露真实基础设施状态，方便页面和日志区分“检索不可用”和“知识库没有命中”。
        throw new IllegalStateException("Milvus collection is not ready for search: state="
                + state + " progress=" + loadingProgress(collection) + "%");
    }

    private long loadingProgress(String collection) {
        R<GetLoadingProgressResponse> progress = client.getLoadingProgress(GetLoadingProgressParam.newBuilder()
                .withCollectionName(collection)
                .build());
        if (progress == null || progress.getStatus() != R.Status.Success.getCode() || progress.getData() == null) {
            return -1;
        }
        return progress.getData().getProgress();
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
        deleteIgnoringUnloadedCollection(param, "doc", docId);
    }

    public void deleteByDocIdForCleanup(UUID docId) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withExpr("doc_id == \"" + docId + "\"")
                .build();
        deleteStrict(param);
    }

    public void deleteSparseByDocId(UUID kbId, UUID docId, java.util.Collection<Integer> profileVersions) {
        for (Integer version : profileVersions) {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(sparseCollection(kbId, version))
                    .withExpr("doc_id == \"" + docId + "\"")
                    .build();
            deleteStrict(param);
        }
    }

    public void deleteSparseByKbId(UUID kbId, java.util.Collection<Integer> profileVersions) {
        for (Integer version : profileVersions) {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(sparseCollection(kbId, version))
                    .withExpr("kb_id == \"" + kbId + "\"")
                    .build();
            deleteStrict(param);
        }
    }

    private String sparseCollection(UUID kbId, Integer version) {
        return properties.getCollection() + "_sparse_" + kbId.toString().replace("-", "").toLowerCase()
                + "_v" + version;
    }

    public void deleteByKbId(UUID kbId) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withExpr("kb_id == \"" + kbId + "\"")
                .build();
        deleteIgnoringUnloadedCollection(param, "kb", kbId);
    }

    public void deleteByKbIdForCleanup(UUID kbId) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withExpr("kb_id == \"" + kbId + "\"")
                .build();
        deleteStrict(param);
    }

    private void deleteStrict(DeleteParam param) {
        R<MutationResult> response = client.delete(param);
        if (response == null || response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus cleanup delete failed: "
                    + (response != null ? response.getMessage() : "empty delete response"));
        }
    }

    private void deleteIgnoringUnloadedCollection(DeleteParam param, String scope, UUID id) {
        if (!isCollectionLoadedForDelete(scope, id)) {
            return;
        }
        try {
            R<MutationResult> response = client.delete(param);
            if (response != null
                    && response.getStatus() != R.Status.Success.getCode()
                    && isCollectionLoading(response.getMessage())) {
                // 删除采用“业务主库优先”的容错策略：Milvus 集合处于未加载/半加载状态时，
                // 向量残留只影响后续后台清理，不应该阻塞用户删除文档或知识库主记录。
                log.warn("Skip Milvus vector delete because collection is not ready, scope={} id={} message={}",
                        scope, id, response.getMessage());
            }
        } catch (Exception e) {
            if (isCollectionLoading(e.getMessage())) {
                // Milvus Java SDK 在集合未完全加载时可能直接抛异常，而不是返回失败响应。
                // 这里把该基础设施瞬时状态降级为告警，保持删除接口对用户可用。
                log.warn("Skip Milvus vector delete because collection is not ready, scope={} id={} message={}",
                        scope, id, e.getMessage());
                return;
            }
            throw e;
        }
    }

    private boolean isCollectionLoadedForDelete(String scope, UUID id) {
        try {
            R<GetLoadStateResponse> loadState = client.getLoadState(GetLoadStateParam.newBuilder()
                    .withCollectionName(properties.getCollection())
                    .build());
            if (loadState == null || loadState.getStatus() != R.Status.Success.getCode() || loadState.getData() == null) {
                // 删除入口不能依赖 Milvus 一定可用：状态查询失败时跳过向量清理，
                // 让文档/知识库主记录删除先完成，避免用户界面被基础设施瞬时故障卡住。
                log.warn("Skip Milvus vector delete because load state is unavailable, scope={} id={} message={}",
                        scope, id, loadState != null ? loadState.getMessage() : "empty load state response");
                return false;
            }

            LoadState state = loadState.getData().getState();
            if (state == LoadState.LoadStateLoaded) {
                return true;
            }

            // Milvus 对未加载集合执行 delete 会等待并最终抛出 collection not fully loaded。
            // 这里在调用 delete 前提前短路，保证存量文档删除接口快速返回。
            log.warn("Skip Milvus vector delete because collection is not loaded, scope={} id={} state={}",
                    scope, id, state);
            return false;
        } catch (Exception e) {
            log.warn("Skip Milvus vector delete because load state check failed, scope={} id={} message={}",
                    scope, id, e.getMessage());
            return false;
        }
    }

    public record SearchResult(String chunkId, String docId, String content, double score) {}
}
