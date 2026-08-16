package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryArchiveImportServiceTest {
    @Mock KnowledgeBaseService knowledgeBases; @Mock RecoveryStorageService storage;
    @Mock RecoveryArchiveImportIntakeService intakeService;
    private RecoveryArchiveImportService service; private RecoveryManifestService manifests; private UUID kbId;
    private RecoveryProperties properties;
    @BeforeEach void setUp() {
        properties = new RecoveryProperties(); properties.setBucket("dupi-recovery");
        manifests = new RecoveryManifestService(new ObjectMapper().findAndRegisterModules());
        service = new RecoveryArchiveImportService(knowledgeBases, storage, properties, manifests, intakeService);
        kbId = UUID.randomUUID(); lenient().when(knowledgeBases.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).tenantId("tenant-a").build());
    }
    @Test void rejectsDuplicateManifestItemKeysBeforeUploading() throws Exception {
        assertThatThrownBy(() -> service.submit(kbId, archive(true, false, false), "one", "admin")).hasMessageContaining("Duplicate").hasMessageContaining("item key");
        verifyNoInteractions(storage, intakeService);
    }
    @Test void rejectsReservedKeyAndTraversalBeforeUploading() throws Exception {
        assertThatThrownBy(() -> service.submit(kbId, archive(false, true, false), "one", "admin")).hasMessageContaining("reserved");
        assertThatThrownBy(() -> service.submit(kbId, archive(false, false, true), "two", "admin")).hasMessageContaining("unsafe entry path");
        verifyNoInteractions(storage, intakeService);
    }
    @Test void createsTracksStagesThenMakesJobRunnable() throws Exception {
        UUID jobId = UUID.randomUUID(); OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(intakeService.createOrResume(any(), eq("request-1"), eq("admin")))
                .thenReturn(new RecoveryImportIntake(job, com.dupi.rag.domain.enums.OperationStepStatus.PENDING, false));
        when(storage.stagingKey(eq(jobId), anyString())).thenReturn("recovery-staging/hash/" + jobId + ".zip");
        when(storage.bucket()).thenReturn("dupi-recovery");
        when(storage.inspect(any())).thenReturn(inspection(RecoveryStorageOutcome.ABSENT),
                matching(), matching());
        when(storage.putStaging(any(), any())).thenAnswer(call -> { byte[] bytes = ((InputStream) call.getArgument(1)).readAllBytes(); StoredRecoveryObject expected = call.getArgument(0); return new StoredRecoveryObject("dupi-recovery", expected.objectKey(), bytes.length, sha(bytes), "etag-1"); });
        when(intakeService.completeStageAndPublish(eq(jobId), any(), any())).thenReturn(job);
        assertThat(service.submit(kbId, archive(false, false, false), "request-1", "admin").getId()).isEqualTo(jobId);
        InOrder order = inOrder(intakeService, storage);
        order.verify(intakeService).createOrResume(any(), eq("request-1"), eq("admin"));
        order.verify(storage).putStaging(argThat(expected -> expected.objectKey().contains(jobId.toString())), any());
        order.verify(intakeService).completeStageAndPublish(eq(jobId), any(), any());
    }

    @Test void fallbackDigestUsesBoundedReadsInsteadOfBulkAllocation() throws Exception {
        UUID jobId = UUID.randomUUID();
        MockMultipartFile source = archive(false, false, false);
        MultipartFile streamingOnly = new BulkRejectingMultipartFile(source);
        String expectedZipSha = sha(source.getBytes());
        OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(intakeService.createOrResume(any(), eq(expectedZipSha + ":" + kbId), eq("admin")))
                .thenReturn(new RecoveryImportIntake(job, com.dupi.rag.domain.enums.OperationStepStatus.PENDING, false));
        when(storage.stagingKey(jobId, expectedZipSha)).thenReturn("recovery-staging/" + expectedZipSha + "/" + jobId + ".zip");
        when(storage.bucket()).thenReturn("dupi-recovery");
        when(storage.inspect(any())).thenReturn(inspection(RecoveryStorageOutcome.ABSENT),
                matching(), matching());
        when(storage.putStaging(any(), any())).thenReturn(
                new StoredRecoveryObject("dupi-recovery", "recovery-staging/" + jobId + ".zip",
                        source.getSize(), expectedZipSha));
        when(intakeService.completeStageAndPublish(eq(jobId), any(), any())).thenReturn(job);

        assertThat(service.submit(kbId, streamingOnly, null, "admin").getId()).isEqualTo(jobId);
        verify(intakeService).createOrResume(any(), eq(expectedZipSha + ":" + kbId), eq("admin"));
    }

    @Test void entryLimitCountsDirectoryOnlyZipRecords() throws Exception {
        properties.setMaxImportEntries(2);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one/", null); entries.put("two/", null); entries.put("three/", null);
        assertThatThrownBy(() -> service.submit(kbId, zipOf(entries), "dirs", "admin"))
                .hasMessageContaining("too many files");
        verifyNoInteractions(storage, intakeService);
    }

    @Test void entryLimitCountsDirectoriesAndFilesTogether() throws Exception {
        properties.setMaxImportEntries(1);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one/", null);
        entries.put("manifest.json", "{}".getBytes());
        assertThatThrownBy(() -> service.submit(kbId, zipOf(entries), "mixed", "admin"))
                .hasMessageContaining("too many files");
        verifyNoInteractions(storage, intakeService);
    }

    @Test void publishedIdempotentReplayReturnsSameJobWithoutTouchingIntakeOrStorage() throws Exception {
        UUID jobId = UUID.randomUUID(); OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(intakeService.createOrResume(any(), eq("request-1"), eq("admin")))
                .thenReturn(new RecoveryImportIntake(job,
                        com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED, true));

        assertThat(service.submit(kbId, archive(false, false, false), "request-1", "admin").getId())
                .isEqualTo(jobId);

        verifyNoInteractions(storage);
        verify(intakeService, never()).completeStageAndPublish(any(), any(), any());
    }

    @Test void failedStageUploadLeavesTrackedIntakeUnpublished() throws Exception {
        UUID jobId = UUID.randomUUID(); OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(intakeService.createOrResume(any(), eq("request-1"), eq("admin")))
                .thenReturn(new RecoveryImportIntake(job,
                        com.dupi.rag.domain.enums.OperationStepStatus.PENDING, false));
        when(storage.stagingKey(eq(jobId), anyString())).thenReturn("recovery-staging/hash/" + jobId + ".zip");
        when(storage.bucket()).thenReturn("dupi-recovery");
        when(storage.inspect(any())).thenReturn(inspection(RecoveryStorageOutcome.ABSENT));
        when(storage.putStaging(any(), any())).thenThrow(
                new RecoveryStorageUnavailableException("upload timeout", new Exception("down")));

        assertThatThrownBy(() -> service.submit(kbId, archive(false, false, false), "request-1", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("staging upload failed");
        verify(intakeService).scheduleCleanup(eq(jobId), any(), any());
        verify(intakeService, never()).completeStageAndPublish(any(), any(), any());
    }

    @Test
    void fullConcurrentSubmissionsConvergeWhenOnePublishesAfterTheOtherReadIntake() throws Exception {
        UUID jobId = UUID.randomUUID();
        MockMultipartFile upload = archive(false, false, false);
        OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        CountDownLatch bothReadIntake = new CountDownLatch(2);
        CountDownLatch firstPublished = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        when(intakeService.createOrResume(any(), eq("race-key"), eq("admin"))).thenAnswer(call -> {
            bothReadIntake.countDown();
            assertThat(bothReadIntake.await(5, TimeUnit.SECONDS)).isTrue();
            return new RecoveryImportIntake(job, com.dupi.rag.domain.enums.OperationStepStatus.PENDING, false);
        });
        when(storage.stagingKey(eq(jobId), anyString()))
                .thenAnswer(call -> "recovery-staging/" + call.getArgument(1) + "/" + jobId + ".zip");
        when(storage.bucket()).thenReturn("dupi-recovery");
        when(storage.inspect(any())).thenAnswer(call -> {
            StoredRecoveryObject expected = call.getArgument(0);
            return new RecoveryStorageInspection(RecoveryStorageOutcome.MATCHING,
                    new StoredRecoveryObject(expected.bucket(), expected.objectKey(), expected.byteSize(),
                            expected.sha256(), "etag-shared"));
        });
        when(intakeService.completeStageAndPublish(eq(jobId), any(), any())).thenAnswer(call -> {
            if (publications.incrementAndGet() == 1) firstPublished.countDown();
            else assertThat(firstPublished.await(5, TimeUnit.SECONDS)).isTrue();
            return job;
        });

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.submit(kbId, upload, "race-key", "admin").getId());
            var second = pool.submit(() -> service.submit(kbId, upload, "race-key", "admin").getId());
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(jobId);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(jobId);
        } finally {
            pool.shutdownNow();
        }
        verify(intakeService, times(2)).completeStageAndPublish(eq(jobId), any(), any());
    }

    @Test
    void objectVersionChangeAfterInspectionNeverPublishesAndSchedulesRecovery() throws Exception {
        UUID jobId = UUID.randomUUID();
        OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(intakeService.createOrResume(any(), eq("version-race"), eq("admin")))
                .thenReturn(new RecoveryImportIntake(job,
                        com.dupi.rag.domain.enums.OperationStepStatus.PENDING, false));
        when(storage.stagingKey(eq(jobId), anyString()))
                .thenAnswer(call -> "recovery-staging/" + call.getArgument(1) + "/" + jobId + ".zip");
        when(storage.bucket()).thenReturn("dupi-recovery");
        when(storage.inspect(any())).thenAnswer(new org.mockito.stubbing.Answer<RecoveryStorageInspection>() {
            int calls;
            @Override public RecoveryStorageInspection answer(org.mockito.invocation.InvocationOnMock call) {
                StoredRecoveryObject expected = call.getArgument(0);
                calls++;
                StoredRecoveryObject actual = new StoredRecoveryObject(expected.bucket(), expected.objectKey(),
                        expected.byteSize(), expected.sha256(), calls < 2 ? "etag-1" : "etag-2");
                return new RecoveryStorageInspection(calls < 2
                        ? RecoveryStorageOutcome.MATCHING : RecoveryStorageOutcome.STALE_VERSION, actual);
            }
        });

        assertThatThrownBy(() -> service.submit(kbId, archive(false, false, false), "version-race", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");

        verify(intakeService, never()).completeStageAndPublish(any(), any(), any());
        verify(intakeService).scheduleCleanup(eq(jobId), any(), any());
    }
    private MockMultipartFile archive(boolean duplicate, boolean reserved, boolean traversal) throws Exception {
        UUID source = UUID.randomUUID(); Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("records/knowledge-base.json", "{}".getBytes()); files.put("records/documents.ndjson", new byte[0]); files.put("records/chunks.ndjson", "{}\n".getBytes()); files.put("records/evaluation-cases.ndjson", new byte[0]); files.put("records/quality-policy.json", "null".getBytes()); files.put("records/retrieval-profiles.ndjson", new byte[0]); files.put("vectors/dense.ndjson", "{}\n".getBytes());
        List<String> keys = List.of("record:knowledge-base", "record:documents", "record:chunks", "record:evaluation-cases", "record:quality-policy", "record:retrieval-profiles", "vector:dense");
        List<RecoveryManifestItem> entries = new java.util.ArrayList<>(); int i = 0;
        for (var entry : files.entrySet()) { String key = i == 1 && duplicate ? keys.get(0) : i == 1 && reserved ? "manifest" : keys.get(i); entries.add(new RecoveryManifestItem(key, i == 6 ? "VECTOR" : "RECORD", "archives/tenant-a/" + source + "/" + entry.getKey(), entry.getValue().length, sha(entry.getValue()))); i++; }
        RecoveryManifest manifest = manifests.seal(new RecoveryManifestHeader(1, source, "tenant-a", kbId, Instant.now(), "embedding", 1, Map.of()), entries);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(output)) { if (traversal) { zip.putNextEntry(new ZipEntry("../bad")); zip.write(1); zip.closeEntry(); } for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifests.serialize(manifest)); zip.closeEntry(); }
        return new MockMultipartFile("file", "recovery.zip", "application/zip", output.toByteArray());
    }
    private MockMultipartFile zipOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                if (entry.getValue() != null) zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "recovery.zip", "application/zip", output.toByteArray());
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static RecoveryStorageInspection inspection(RecoveryStorageOutcome outcome) {
        return new RecoveryStorageInspection(outcome, null);
    }
    private static RecoveryStorageInspection matching() {
        return new RecoveryStorageInspection(RecoveryStorageOutcome.MATCHING,
                new StoredRecoveryObject("dupi-recovery", "recovery-staging/hash/job.zip",
                        1, "a".repeat(64), "etag-1"));
    }

    private static final class BulkRejectingMultipartFile implements MultipartFile {
        private final MultipartFile delegate;
        private BulkRejectingMultipartFile(MultipartFile delegate) { this.delegate = delegate; }
        @Override public String getName() { return delegate.getName(); }
        @Override public String getOriginalFilename() { return delegate.getOriginalFilename(); }
        @Override public String getContentType() { return delegate.getContentType(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public long getSize() { return delegate.getSize(); }
        @Override public byte[] getBytes() throws IOException { return delegate.getBytes(); }
        @Override public InputStream getInputStream() throws IOException {
            return new FilterInputStream(delegate.getInputStream()) {
                @Override public byte[] readAllBytes() throws IOException {
                    throw new IOException("bulk read forbidden");
                }
            };
        }
        @Override public void transferTo(java.io.File destination) throws IOException, IllegalStateException {
            delegate.transferTo(destination);
        }
    }
}
