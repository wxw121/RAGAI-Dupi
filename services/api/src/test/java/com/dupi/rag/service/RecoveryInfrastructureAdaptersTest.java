package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryAsyncConfig;
import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecoveryInfrastructureAdaptersTest {
    @Test
    void largeMinioConditionalCreatePublishesWithThePreconditionOnOnePut() throws Exception {
        try (RecordingS3Server server = new RecordingS3Server()) {
            MinioRecoveryObjectStore store = new MinioRecoveryObjectStore(server.client());
            byte[] large = new byte[10 * 1024 * 1024 + 1];

            assertThat(store.putIfAbsent("recovery", "stage", new ByteArrayInputStream(large)))
                    .isEqualTo(RecoveryObjectWriteResult.created("version-1"));

            assertThat(server.requestsFor("PUT", "/recovery/stage", null))
                    .singleElement()
                    .satisfies(request -> {
                        assertThat(request.ifNoneMatch()).isEqualTo("*");
                        assertThat(request.bodySize()).isEqualTo(large.length);
                    });
            assertThat(server.multipartRequests()).isEmpty();
        }
    }

    @Test
    void independentMinioClientsRaceLargeStageAndConvergeOnOneImmutableVersion() throws Exception {
        byte[] large = new byte[10 * 1024 * 1024 + 1];
        Arrays.fill(large, (byte) 'z');
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(large));
        StoredRecoveryObject expected = new StoredRecoveryObject(
                "recovery", "recovery-staging/hash/job.zip", large.length, sha256);
        RecoveryProperties properties = new RecoveryProperties();
        properties.setBucket("recovery");

        try (RacingS3Server server = new RacingS3Server()) {
            RecoveryStorageService firstStorage = new RecoveryStorageService(
                    properties, new MinioRecoveryObjectStore(server.client()));
            RecoveryStorageService secondStorage = new RecoveryStorageService(
                    properties, new MinioRecoveryObjectStore(server.client()));
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> firstStorage.putStaging(
                        expected, new ByteArrayInputStream(large)));
                var second = pool.submit(() -> secondStorage.putStaging(
                        expected, new ByteArrayInputStream(large)));

                StoredRecoveryObject firstEvidence;
                StoredRecoveryObject secondEvidence;
                try {
                    firstEvidence = first.get(20, TimeUnit.SECONDS);
                    secondEvidence = second.get(20, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    server.assertHealthy();
                    throw new AssertionError(server.state(), failure);
                }
                assertThat(firstEvidence).isEqualTo(secondEvidence);
                assertThat(firstEvidence.versionToken()).isEqualTo("version-1");
                assertThat(server.publishedVersions()).isEqualTo(1);
                assertThat(server.objectBytes()).isEqualTo(large);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void minioConditionalCreateDeletesDiskSpoolAndTranslatesOnlyLostRaces() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ObjectWriteResponse written = new ObjectWriteResponse(
                Headers.of(), "recovery", null, "stage", "etag-7", "version-7");
        when(client.bucketExists(any())).thenReturn(true);
        when(client.uploadObject(any())).thenReturn(written);
        MinioRecoveryObjectStore store = new MinioRecoveryObjectStore(client);

        assertThat(store.putIfAbsent("recovery", "stage", new ByteArrayInputStream(new byte[]{1})))
                .isEqualTo(RecoveryObjectWriteResult.created("version-7"));

        ErrorResponseException lostRace = error("PreconditionFailed", 412);
        doThrow(lostRace).when(client).uploadObject(any());
        assertThat(store.putIfAbsent("recovery", "stage", new ByteArrayInputStream(new byte[]{1})))
                .isEqualTo(RecoveryObjectWriteResult.lostRace());

        ErrorResponseException outage = error("AccessDenied", 403);
        doThrow(outage).when(client).uploadObject(any());
        assertThatThrownBy(() -> store.putIfAbsent(
                "recovery", "stage", new ByteArrayInputStream(new byte[]{1})))
                .isSameAs(outage);

        ArgumentCaptor<UploadObjectArgs> uploads = ArgumentCaptor.forClass(UploadObjectArgs.class);
        verify(client, times(3)).uploadObject(uploads.capture());
        assertThat(uploads.getAllValues()).allSatisfy(args -> {
            assertThat(args.extraHeaders().get("If-None-Match")).containsExactly("*");
            assertThat(java.nio.file.Path.of(args.filename())).doesNotExist();
        });
    }

    private static ErrorResponseException error(String code, int status) {
        ErrorResponse error = new ErrorResponse(code, code, "recovery", "stage", "/stage", "request", "host");
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost/stage").build())
                .protocol(Protocol.HTTP_1_1).code(status).message(code).build();
        return new ErrorResponseException(error, response, null);
    }

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
        port.upsertProfile("profiles", List.of(new VectorSnapshotRow(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "content", List.of(0.1d, 0.2d), Map.of(
                "entry_kind", "child", "profile_classic", false,
                "profile_parent_child", true, "profile_qa_assisted", false,
                "profile_combined", true))));
        assertThat(port.count("chunks", UUID.randomUUID())).isZero();

        ArgumentCaptor<UpsertParam> upsert = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client, times(2)).upsert(upsert.capture());
        List<?> embeddings = upsert.getAllValues().get(0).getFields().stream()
                .filter(field -> "embedding".equals(field.getName()))
                .map(InsertParam.Field::getValues).findFirst().orElseThrow();
        assertThat((List<?>) embeddings.get(0)).allMatch(Float.class::isInstance);
        assertThat(upsert.getAllValues().get(1).getFields())
                .extracting(InsertParam.Field::getName)
                .contains("entry_kind", "profile_classic", "profile_parent_child",
                        "profile_qa_assisted", "profile_combined");
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

    private record WireRequest(String method, String path, String query,
                               String ifNoneMatch, int bodySize) { }

    private static final class RecordingS3Server implements AutoCloseable {
        private final List<WireRequest> requests = new CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final HttpServer server;

        private RecordingS3Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private MinioClient client() {
            return MinioClient.builder()
                    .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                    .region("us-east-1")
                    .credentials("minio", "minio-secret")
                    .httpClient(new OkHttpClient.Builder()
                            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                            .build())
                    .build();
        }

        private List<WireRequest> requestsFor(String method, String path, String query) {
            return requests.stream()
                    .filter(request -> method.equals(request.method()))
                    .filter(request -> path.equals(request.path()))
                    .filter(request -> java.util.Objects.equals(query, request.query()))
                    .toList();
        }

        private List<WireRequest> multipartRequests() {
            return requests.stream().filter(request -> request.query() != null).toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(new WireRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query,
                    exchange.getRequestHeaders().getFirst("If-None-Match"), body.length));
            if ("HEAD".equals(exchange.getRequestMethod()) && "/recovery".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "", Map.of());
            } else if ("POST".equals(exchange.getRequestMethod()) && query != null && query.startsWith("uploads")) {
                respond(exchange, 200, "<InitiateMultipartUploadResult>"
                        + "<Bucket>recovery</Bucket><Key>stage</Key><UploadId>upload-1</UploadId>"
                        + "</InitiateMultipartUploadResult>", Map.of());
            } else if ("PUT".equals(exchange.getRequestMethod()) && query != null
                    && query.contains("uploadId=upload-1")) {
                respond(exchange, 200, "", Map.of("ETag", "\"part-1\""));
            } else if ("POST".equals(exchange.getRequestMethod()) && query != null
                    && query.contains("uploadId=upload-1")) {
                respond(exchange, 200, "<CompleteMultipartUploadResult>"
                        + "<Location>http://localhost/recovery/stage</Location>"
                        + "<Bucket>recovery</Bucket><Key>stage</Key><ETag>\"etag-1\"</ETag>"
                        + "</CompleteMultipartUploadResult>", Map.of("x-amz-version-id", "version-1"));
            } else if ("PUT".equals(exchange.getRequestMethod()) && query == null) {
                respond(exchange, 200, "", Map.of(
                        "ETag", "\"etag-1\"", "x-amz-version-id", "version-1"));
            } else {
                respond(exchange, 500, "unexpected request", Map.of());
            }
        }

        private void respond(HttpExchange exchange, int status, String body, Map<String, String> headers)
                throws IOException {
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class RacingS3Server implements AutoCloseable {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final HttpServer server;
        private final CyclicBarrier initialInspections = new CyclicBarrier(2);
        private final AtomicInteger inspectionCount = new AtomicInteger();
        private final AtomicInteger uploadSequence = new AtomicInteger();
        private final AtomicInteger publishedVersions = new AtomicInteger();
        private final AtomicInteger putStarts = new AtomicInteger();
        private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        private final Map<String, Map<Integer, byte[]>> uploads = new ConcurrentHashMap<>();
        private volatile byte[] objectBytes;

        private RacingS3Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    handle(exchange);
                } catch (Throwable failure) {
                    serverFailure.compareAndSet(null, failure);
                    exchange.close();
                }
            });
            server.setExecutor(executor);
            server.start();
        }

        private MinioClient client() {
            return MinioClient.builder()
                    .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                    .region("us-east-1")
                    .credentials("minio", "minio-secret")
                    .httpClient(new OkHttpClient.Builder()
                            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                            .build())
                    .build();
        }

        private int publishedVersions() {
            return publishedVersions.get();
        }

        private byte[] objectBytes() {
            assertHealthy();
            return objectBytes;
        }

        private void assertHealthy() {
            if (serverFailure.get() != null) {
                throw new AssertionError("protocol-faithful S3 server failed", serverFailure.get());
            }
        }

        private String state() {
            return "inspections=" + inspectionCount.get()
                    + ", uploads=" + uploadSequence.get()
                    + ", putStarts=" + putStarts.get()
                    + ", versions=" + publishedVersions.get()
                    + ", object=" + (objectBytes == null ? "absent" : objectBytes.length);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if ("HEAD".equals(method) && "/recovery".equals(path)) {
                respond(exchange, 200, new byte[0], Map.of());
                return;
            }
            if ("HEAD".equals(method) && "/recovery/recovery-staging/hash/job.zip".equals(path)) {
                if (objectBytes == null) {
                    awaitInitialInspection();
                    respond(exchange, 404, new byte[0], Map.of(
                            "Connection", "close", "x-amz-error-code", "NoSuchKey",
                            "x-amz-error-message", "missing"));
                } else {
                    respond(exchange, 200, new byte[0], Map.of(
                            "Content-Length", Integer.toString(objectBytes.length),
                            "Content-Type", "application/octet-stream",
                            "ETag", "\"etag-" + publishedVersions.get() + "\"",
                            "Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT",
                            "x-amz-version-id", "version-" + publishedVersions.get()));
                }
                return;
            }
            if ("GET".equals(method) && "/recovery/recovery-staging/hash/job.zip".equals(path)) {
                byte[] value = objectBytes;
                if (value == null) {
                    respond(exchange, 404, errorXml("NoSuchKey", "missing"), Map.of());
                } else {
                    respond(exchange, 200, value, Map.of(
                            "ETag", "\"etag-" + publishedVersions.get() + "\"",
                            "x-amz-version-id", "version-" + publishedVersions.get()));
                }
                return;
            }
            if ("PUT".equals(method)) putStarts.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("POST".equals(method) && query != null && query.startsWith("uploads")) {
                String uploadId = "upload-" + uploadSequence.incrementAndGet();
                uploads.put(uploadId, new ConcurrentHashMap<>());
                respond(exchange, 200, ("<InitiateMultipartUploadResult>"
                        + "<Bucket>recovery</Bucket><Key>recovery-staging/hash/job.zip</Key>"
                        + "<UploadId>" + uploadId + "</UploadId></InitiateMultipartUploadResult>")
                        .getBytes(StandardCharsets.UTF_8), Map.of());
                return;
            }
            Map<String, String> parameters = queryParameters(query);
            String uploadId = parameters.get("uploadId");
            if ("PUT".equals(method) && uploadId != null) {
                uploads.get(uploadId).put(Integer.parseInt(parameters.get("partNumber")), body);
                respond(exchange, 200, new byte[0], Map.of("ETag", "\"part-" + parameters.get("partNumber") + "\""));
                return;
            }
            if ("POST".equals(method) && uploadId != null) {
                byte[] uploaded = uploads.get(uploadId).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .reduce(new byte[0], RacingS3Server::concatenate);
                int version;
                synchronized (this) {
                    objectBytes = uploaded;
                    version = publishedVersions.incrementAndGet();
                }
                respond(exchange, 200, ("<CompleteMultipartUploadResult>"
                        + "<Location>http://localhost/recovery/recovery-staging/hash/job.zip</Location>"
                        + "<Bucket>recovery</Bucket><Key>recovery-staging/hash/job.zip</Key>"
                        + "<ETag>\"etag-" + version + "\"</ETag></CompleteMultipartUploadResult>")
                        .getBytes(StandardCharsets.UTF_8), Map.of("x-amz-version-id", "version-" + version));
                return;
            }
            if ("PUT".equals(method) && query == null) {
                synchronized (this) {
                    if ("*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))
                            && objectBytes != null) {
                        respond(exchange, 412, errorXml("PreconditionFailed", "already exists"),
                                Map.of("Content-Type", "application/xml"));
                        return;
                    }
                    objectBytes = body;
                    int version = publishedVersions.incrementAndGet();
                    respond(exchange, 200, new byte[0], Map.of(
                            "ETag", "\"etag-" + version + "\"",
                            "x-amz-version-id", "version-" + version));
                }
                return;
            }
            respond(exchange, 500, "unexpected request".getBytes(StandardCharsets.UTF_8), Map.of());
        }

        private void awaitInitialInspection() throws IOException {
            if (inspectionCount.incrementAndGet() <= 2) await(initialInspections);
        }

        private void await(CyclicBarrier barrier) throws IOException {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IOException("test server coordination failed", exception);
            }
        }

        private Map<String, String> queryParameters(String query) {
            if (query == null || query.isBlank()) return Map.of();
            Map<String, String> values = new java.util.HashMap<>();
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(parts[0], parts.length == 1 ? "" : parts[1]);
            }
            return values;
        }

        private static byte[] concatenate(byte[] left, byte[] right) {
            byte[] combined = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, combined, left.length, right.length);
            return combined;
        }

        private byte[] errorXml(String code, String message) {
            return ("<Error><Code>" + code + "</Code><Message>" + message + "</Message>"
                    + "<BucketName>recovery</BucketName><Key>recovery-staging/hash/job.zip</Key>"
                    + "<RequestId>request</RequestId><HostId>host</HostId></Error>")
                    .getBytes(StandardCharsets.UTF_8);
        }

        private void respond(HttpExchange exchange, int status, byte[] body, Map<String, String> headers)
                throws IOException {
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
