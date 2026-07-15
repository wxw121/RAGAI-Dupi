package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryAsyncConfig;
import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecoveryInfrastructureAdaptersTest {
    @Test
    void minioAdapterEnsuresBucketAndDelegatesObjectOperations() throws Exception {
        MinioClient client = mock(MinioClient.class);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(client.bucketExists(any())).thenReturn(false);
        when(client.getObject(any())).thenReturn(response);
        when(client.listObjects(any())).thenReturn(List.of());
        MinioRecoveryObjectStore store = new MinioRecoveryObjectStore(client);

        store.put("recovery", "a", new ByteArrayInputStream(new byte[]{1}));
        assertThat(store.get("recovery", "a")).isSameAs(response);
        assertThat(store.list("recovery", "prefix/")).isEmpty();
        store.delete("recovery", "a");

        verify(client).makeBucket(any());
        verify(client).putObject(any());
        verify(client).removeObject(any());
    }

    @Test
    void sdkMilvusAdapterReadsDescribesEnsuresUpsertsAndCounts() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.query(any(QueryParam.class))).thenReturn(R.success(QueryResults.newBuilder().build()));
        CollectionSchema schema = CollectionSchema.newBuilder()
                .addFields(FieldSchema.newBuilder().setName("chunk_id").setDataType(DataType.VarChar))
                .addFields(FieldSchema.newBuilder().setName("embedding").setDataType(DataType.FloatVector)
                        .addTypeParams(KeyValuePair.newBuilder().setKey("dim").setValue("2")))
                .build();
        when(client.describeCollection(any())).thenReturn(R.success(
                DescribeCollectionResponse.newBuilder().setSchema(schema).build()));
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.upsert(any())).thenReturn(R.success(MutationResult.newBuilder().build()));
        SdkMilvusRecoveryPort port = new SdkMilvusRecoveryPort(client);

        assertThat(port.read("chunks", UUID.randomUUID(), 0, 10, false)).isEmpty();
        assertThat(port.describe("chunks")).satisfies(value -> {
            assertThat(value.metric()).isEqualTo("COSINE");
            assertThat(value.dimension()).isEqualTo(2);
        });
        port.ensure("chunks", new MilvusRecoverySchema("COSINE", 2, Map.of()), false);
        port.upsert("chunks", List.of(new VectorSnapshotRow(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "content", List.of(0.1d, 0.2d), Map.of())), false);
        assertThat(port.count("chunks", UUID.randomUUID())).isZero();

        ArgumentCaptor<UpsertParam> upsert = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client).upsert(upsert.capture());
        List<?> embeddings = upsert.getValue().getFields().stream()
                .filter(field -> "embedding".equals(field.getName()))
                .map(InsertParam.Field::getValues).findFirst().orElseThrow();
        assertThat((List<?>) embeddings.get(0)).allMatch(Float.class::isInstance);
        ArgumentCaptor<QueryParam> queries = ArgumentCaptor.forClass(QueryParam.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(queries.getAllValues())
                .allMatch(query -> query.getConsistencyLevel() == ConsistencyLevelEnum.STRONG);
    }

    @Test
    void sdkMilvusAdapterRejectsMissingOrFailedCollections() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        SdkMilvusRecoveryPort port = new SdkMilvusRecoveryPort(client);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        assertThatThrownBy(() -> port.ensure("missing", new MilvusRecoverySchema("BM25", 0, Map.of()), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        when(client.query(any(QueryParam.class))).thenReturn(R.failed(ErrorCode.UnexpectedError, "down"));
        assertThatThrownBy(() -> port.read("chunks", UUID.randomUUID(), 0, 1, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("down");
    }

    @Test
    void workerProvisionerPostsEmptyBackfillToCreateSparseCollection() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).endsWith("/api/v1/retrieve/sparse/backfill");
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", "application/json").body("{\"indexed_count\":0}").build());
        });
        WorkerSparseRecoveryProvisioner provisioner = new WorkerSparseRecoveryProvisioner(builder);
        ReflectionTestUtils.setField(provisioner, "workerBaseUrl", "http://worker");

        provisioner.ensure(UUID.randomUUID(), 1024, 3, Map.of("bm25_k1", 1.5));
    }

    @Test
    void asyncConfigBuildsBoundedExecutor() {
        RecoveryProperties properties = new RecoveryProperties();
        properties.setMaxConcurrentJobs(1);
        Executor executor = new RecoveryAsyncConfig().recoveryExecutor(properties);

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(1);
        pool.shutdown();
    }
}
