package com.dupi.rag.client;

import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.GetLoadingProgressResponse;
import io.milvus.grpc.GetLoadStateResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.LoadState;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.GetLoadingProgressParam;
import io.milvus.param.collection.GetLoadStateParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MilvusVectorServiceTest {

    @Test
    void ensureCollectionLoadsExistingCollection() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(describeCollection(1536)));

        service(client).ensureCollection();

        verify(client, never()).createCollection(any(CreateCollectionParam.class));
        verify(client, never()).createIndex(any());
        verify(client).loadCollection(any());
    }

    @Test
    void ensureCollectionCreatesSchemaIndexAndLoadsCollectionWhenMissing() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.createCollection(any(CreateCollectionParam.class))).thenReturn(R.success());

        service(client).ensureCollection();

        verify(client).createCollection(any(CreateCollectionParam.class));
        verify(client).createIndex(any());
        verify(client).loadCollection(any());
    }

    @Test
    void ensureCollectionContinuesIndexAndLoadWhenCreateReportsFailure() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.createCollection(any(CreateCollectionParam.class)))
                .thenReturn(R.failed(R.Status.Unknown, "already exists"));

        service(client).ensureCollection();

        verify(client).createCollection(any(CreateCollectionParam.class));
        verify(client).createIndex(any());
        verify(client).loadCollection(any());
    }

    @Test
    void ensureCollectionFailsFastWhenExistingCollectionDimensionDiffers() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(describeCollection(1024)));

        assertThatThrownBy(() -> service(client).ensureCollection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding dimension mismatch")
                .hasMessageContaining("expected=1536")
                .hasMessageContaining("actual=1024");
        verify(client, never()).loadCollection(any());
    }

    @Test
    void searchThrowsWhenMilvusReturnsFailure() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        R<SearchResults> failed = R.failed(R.Status.Unknown, "boom");
        when(client.search(any(SearchParam.class))).thenReturn(failed);

        assertThatThrownBy(() -> service(client).search(UUID.randomUUID(), List.of(0.1f), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus search failed");
    }

    @Test
    void searchThrowsWhenCollectionIsStillLoading() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        R<SearchResults> failed = R.failed(R.Status.Unknown, "collection not fully loaded");
        when(client.search(any(SearchParam.class))).thenReturn(failed);

        assertThatThrownBy(() -> service(client).search(UUID.randomUUID(), List.of(0.1f), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus collection is not ready for search");
    }

    @Test
    void searchFailsFastBeforeSearchWhenCollectionIsLoading() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoading)));
        when(client.getLoadingProgress(any(GetLoadingProgressParam.class))).thenReturn(R.success(loadingProgress(0)));

        assertThatThrownBy(() -> service(client).search(UUID.randomUUID(), List.of(0.1f), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus collection is not ready for search")
                .hasMessageContaining("state=LoadStateLoading")
                .hasMessageContaining("progress=0%");
        verify(client, never()).search(any(SearchParam.class));
    }

    @Test
    void searchFailsFastWhenLoadStateIsUnavailable() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(null);

        assertThatThrownBy(() -> service(client).search(UUID.randomUUID(), List.of(0.1f), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state=unknown")
                .hasMessageContaining("empty load state response");
        verify(client, never()).search(any(SearchParam.class));
    }

    @Test
    void searchReportsUnknownProgressWhenLoadingProgressIsUnavailable() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoading)));
        when(client.getLoadingProgress(any(GetLoadingProgressParam.class))).thenReturn(null);

        assertThatThrownBy(() -> service(client).search(UUID.randomUUID(), List.of(0.1f), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state=LoadStateLoading")
                .hasMessageContaining("progress=-1%");
        verify(client, never()).search(any(SearchParam.class));
    }

    @Test
    void deleteBuildsDocAndKnowledgeBaseExpressions() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success());
        UUID docId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        MilvusVectorService service = service(client);

        service.deleteByDocId(docId);
        service.deleteByKbId(kbId);

        verify(client, times(2)).delete(any(DeleteParam.class));
    }

    @Test
    void deleteIgnoresUnloadedCollectionFailures() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        when(client.delete(any(DeleteParam.class)))
                .thenReturn(R.failed(R.Status.Unknown, "collection not fully loaded"));

        service(client).deleteByDocId(UUID.randomUUID());

        verify(client).delete(any(DeleteParam.class));
    }

    @Test
    void deleteSkipsMilvusCallWhenCollectionIsNotLoaded() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateNotLoad)));

        service(client).deleteByDocId(UUID.randomUUID());

        verify(client, never()).delete(any(DeleteParam.class));
    }

    @Test
    void deleteSkipsMilvusCallWhenLoadStateCheckFails() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenThrow(new IllegalStateException("milvus down"));

        service(client).deleteByKbId(UUID.randomUUID());

        verify(client, never()).delete(any(DeleteParam.class));
    }

    @Test
    void deleteRethrowsNonLoadingSdkFailures() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        when(client.delete(any(DeleteParam.class))).thenThrow(new IllegalStateException("permission denied"));

        assertThatThrownBy(() -> service(client).deleteByDocId(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void deleteIgnoresLoadingSdkFailuresThrownByClient() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.getLoadState(any(GetLoadStateParam.class))).thenReturn(R.success(loadState(LoadState.LoadStateLoaded)));
        when(client.delete(any(DeleteParam.class))).thenThrow(new IllegalStateException("wait for loading collection timeout"));

        service(client).deleteByDocId(UUID.randomUUID());

        verify(client).delete(any(DeleteParam.class));
    }

    @Test
    void cleanupDeleteThrowsWhenMilvusDeleteFails() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.delete(any(DeleteParam.class)))
                .thenReturn(R.failed(R.Status.Unknown, "delete failed"));

        assertThatThrownBy(() -> service(client).deleteByDocIdForCleanup(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus cleanup delete failed")
                .hasMessageContaining("delete failed");
    }

    @Test
    void cleanupDeleteByKnowledgeBaseReturnsWhenMilvusDeleteSucceeds() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success());

        service(client).deleteByKbIdForCleanup(UUID.randomUUID());

        verify(client).delete(any(DeleteParam.class));
    }

    @Test
    void cleanupDeleteReportsEmptyMilvusResponse() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.delete(any(DeleteParam.class))).thenReturn(null);

        assertThatThrownBy(() -> service(client).deleteByDocIdForCleanup(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty delete response");
    }

    private static MilvusVectorService service(MilvusServiceClient client) {
        MilvusProperties milvus = new MilvusProperties();
        milvus.setCollection("chunks");
        LlmProperties llm = new LlmProperties();
        llm.getEmbedding().setDimension(1536);
        return new MilvusVectorService(client, milvus, llm);
    }

    private static GetLoadStateResponse loadState(LoadState state) {
        return GetLoadStateResponse.newBuilder().setState(state).build();
    }

    private static DescribeCollectionResponse describeCollection(int dimension) {
        return DescribeCollectionResponse.newBuilder()
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder()
                                .setName("embedding")
                                .setDataType(DataType.FloatVector)
                                .addTypeParams(KeyValuePair.newBuilder()
                                        .setKey("dim")
                                        .setValue(String.valueOf(dimension))
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static GetLoadingProgressResponse loadingProgress(long progress) {
        return GetLoadingProgressResponse.newBuilder().setProgress(progress).build();
    }
}
