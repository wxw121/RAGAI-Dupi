package com.dupi.rag.client;

import com.dupi.rag.config.MilvusProperties;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.domain.enums.RetrievalProfile;
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

    private static final Map<RetrievalProfile, String> PROFILE_FIELDS = Map.of(
            RetrievalProfile.CLASSIC, "profile_classic",
            RetrievalProfile.PARENT_CHILD, "profile_parent_child",
            RetrievalProfile.QA_ASSISTED, "profile_qa_assisted",
            RetrievalProfile.COMBINED, "profile_combined"
    );
    private static final Set<String> ENTRY_KINDS = Set.of("original", "child", "qa");

    private final MilvusServiceClient client;
    private final MilvusProperties properties;
    private final LlmProperties llmProperties;

    @PostConstruct
    public void initializeCollections() {
        ensureCollection();
        ensureProfileCollection();
    }

    public void ensureCollection() {
        ensureCollection(properties.getCollection(), false);
    }

    public void ensureProfileCollection() {
        ensureCollection(properties.getProfileCollection(), true);
    }

    private void ensureCollection(String collection, boolean profileSchema) {
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        if (Boolean.TRUE.equals(has.getData())) {
            validateExistingCollectionSchema(collection, profileSchema);
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
        List<FieldType> fields = new ArrayList<>(List.of(chunkId, kbId, docId, content));
        if (profileSchema) {
            fields.add(FieldType.newBuilder()
                    .withName("entry_kind")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(32)
                    .build());
            fields.add(booleanField("profile_classic"));
            fields.add(booleanField("profile_parent_child"));
            fields.add(booleanField("profile_qa_assisted"));
            fields.add(booleanField("profile_combined"));
        }
        FieldType vector = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dim)
                .build();
        fields.add(vector);

        CreateCollectionParam.Builder createBuilder = CreateCollectionParam.newBuilder()
                .withCollectionName(collection);
        fields.forEach(createBuilder::addFieldType);
        CreateCollectionParam create = createBuilder.build();
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

    private FieldType booleanField(String name) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.Bool)
                .build();
    }

    private void validateExistingCollectionSchema(String collection, boolean profileSchema) {
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
        if (profileSchema) {
            Set<String> actualFields = schema.getFieldsList().stream()
                    .map(FieldSchema::getName)
                    .collect(Collectors.toSet());
            Set<String> expectedFields = new LinkedHashSet<>(List.of(
                    "chunk_id", "kb_id", "doc_id", "content", "entry_kind",
                    "profile_classic", "profile_parent_child",
                    "profile_qa_assisted", "profile_combined", "embedding"
            ));
            expectedFields.removeAll(actualFields);
            if (!expectedFields.isEmpty()) {
                throw new IllegalStateException("Milvus collection schema is missing profile fields: collection="
                        + collection + " fields=" + expectedFields);
            }
        }
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
        return searchLegacy(kbId, queryVector, topK);
    }

    public List<SearchResult> searchLegacy(UUID kbId, List<Float> queryVector, int topK) {
        return searchCollection(
                properties.getCollection(),
                kbId,
                queryVector,
                topK,
                "kb_id == \"" + kbId + "\""
        );
    }

    public List<SearchResult> searchProfile(
            UUID kbId,
            List<Float> queryVector,
            int topK,
            RetrievalProfile profile,
            String entryKind
    ) {
        String profileField = PROFILE_FIELDS.get(profile);
        if (profileField == null) {
            throw new IllegalArgumentException("Unsupported retrieval profile: " + profile);
        }
        if (entryKind != null && !ENTRY_KINDS.contains(entryKind)) {
            throw new IllegalArgumentException("Unsupported entry kind: " + entryKind);
        }
        String expr = "kb_id == \"" + kbId + "\" and " + profileField + " == true";
        if (entryKind != null) {
            expr += " and entry_kind == \"" + entryKind + "\"";
        }
        return searchCollection(properties.getProfileCollection(), kbId, queryVector, topK, expr);
    }

    private List<SearchResult> searchCollection(
            String collection,
            UUID kbId,
            List<Float> queryVector,
            int topK,
            String expr
    ) {
        ensureSearchableCollection(collection);

        List<String> outputFields = List.of("chunk_id", "doc_id", "content");

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(collection)
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

    private void ensureSearchableCollection(String collection) {
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
        deleteLegacyByDocId(docId);
    }

    public void deleteLegacyByDocId(UUID docId) {
        deleteByDocId(properties.getCollection(), docId, false);
    }

    public void deleteProfileByDocId(UUID docId) {
        deleteByDocId(properties.getProfileCollection(), docId, false);
    }

    public void deleteByDocIdForCleanup(UUID docId) {
        deleteLegacyByDocIdForCleanup(docId);
    }

    public void deleteLegacyByDocIdForCleanup(UUID docId) {
        deleteByDocId(properties.getCollection(), docId, true);
    }

    public void deleteProfileByDocIdForCleanup(UUID docId) {
        deleteByDocId(properties.getProfileCollection(), docId, true);
    }

    private void deleteByDocId(String collection, UUID docId, boolean strict) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("doc_id == \"" + docId + "\"")
                .build();
        if (strict) {
            deleteStrict(param);
        } else {
            deleteIgnoringUnloadedCollection(param, collection, "doc", docId);
        }
    }

    public void deleteByKbId(UUID kbId) {
        deleteLegacyByKbId(kbId);
    }

    public void deleteLegacyByKbId(UUID kbId) {
        deleteByKbId(properties.getCollection(), kbId, false);
    }

    public void deleteProfileByKbId(UUID kbId) {
        deleteByKbId(properties.getProfileCollection(), kbId, false);
    }

    public void deleteByKbIdForCleanup(UUID kbId) {
        deleteLegacyByKbIdForCleanup(kbId);
    }

    public void deleteLegacyByKbIdForCleanup(UUID kbId) {
        deleteByKbId(properties.getCollection(), kbId, true);
    }

    public void deleteProfileByKbIdForCleanup(UUID kbId) {
        deleteByKbId(properties.getProfileCollection(), kbId, true);
    }

    private void deleteByKbId(String collection, UUID kbId, boolean strict) {
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("kb_id == \"" + kbId + "\"")
                .build();
        if (strict) {
            deleteStrict(param);
        } else {
            deleteIgnoringUnloadedCollection(param, collection, "kb", kbId);
        }
    }

    private void deleteStrict(DeleteParam param) {
        R<MutationResult> response = client.delete(param);
        if (response == null || response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus cleanup delete failed: "
                    + (response != null ? response.getMessage() : "empty delete response"));
        }
    }

    private void deleteIgnoringUnloadedCollection(
            DeleteParam param,
            String collection,
            String scope,
            UUID id
    ) {
        if (!isCollectionLoadedForDelete(collection, scope, id)) {
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

    private boolean isCollectionLoadedForDelete(String collection, String scope, UUID id) {
        try {
            R<GetLoadStateResponse> loadState = client.getLoadState(GetLoadStateParam.newBuilder()
                    .withCollectionName(collection)
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
