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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryArchiveImportServiceTest {
    @Mock KnowledgeBaseService knowledgeBases; @Mock RecoveryStorageService storage; @Mock OperationJobService operations;
    private RecoveryArchiveImportService service; private RecoveryManifestService manifests; private UUID kbId;
    @BeforeEach void setUp() {
        RecoveryProperties properties = new RecoveryProperties(); properties.setBucket("dupi-recovery");
        manifests = new RecoveryManifestService(new ObjectMapper().findAndRegisterModules());
        service = new RecoveryArchiveImportService(knowledgeBases, storage, properties, manifests, operations);
        kbId = UUID.randomUUID(); lenient().when(knowledgeBases.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).tenantId("tenant-a").build());
    }
    @Test void rejectsDuplicateManifestItemKeysBeforeUploading() throws Exception {
        assertThatThrownBy(() -> service.submit(kbId, archive(true, false, false), "one", "admin")).hasMessageContaining("Duplicate").hasMessageContaining("item key");
        verifyNoInteractions(storage, operations);
    }
    @Test void rejectsReservedKeyAndTraversalBeforeUploading() throws Exception {
        assertThatThrownBy(() -> service.submit(kbId, archive(false, true, false), "one", "admin")).hasMessageContaining("reserved");
        assertThatThrownBy(() -> service.submit(kbId, archive(false, false, true), "two", "admin")).hasMessageContaining("unsafe entry path");
        verifyNoInteractions(storage, operations);
    }
    @Test void createsTracksStagesThenMakesJobRunnable() throws Exception {
        UUID jobId = UUID.randomUUID(); OperationJobResponse job = OperationJobResponse.builder().id(jobId).build();
        when(operations.create(any(), anyString(), any(), anyString(), anyMap(), anyString())).thenReturn(job);
        when(operations.recordIntakeStep(eq(jobId), eq("stage-zip"), anyString(), anyString())).thenReturn(com.dupi.rag.domain.entity.OperationStep.builder().status(com.dupi.rag.domain.enums.OperationStepStatus.PENDING).build());
        when(storage.stagingKey(jobId)).thenReturn("recovery-staging/" + jobId + ".zip");
        when(storage.putStaging(anyString(), any())).thenAnswer(call -> { byte[] bytes = ((InputStream) call.getArgument(1)).readAllBytes(); return new StoredRecoveryObject("dupi-recovery", call.getArgument(0), bytes.length, sha(bytes)); });
        when(storage.verify(any())).thenReturn(true); when(operations.makeRunnable(jobId)).thenReturn(job);
        assertThat(service.submit(kbId, archive(false, false, false), "request-1", "admin").getId()).isEqualTo(jobId);
        InOrder order = inOrder(operations, storage);
        order.verify(operations).create(any(), eq("KNOWLEDGE_BASE"), eq(kbId), eq("request-1"), anyMap(), eq("admin"));
        order.verify(operations).replaceIntakeInput(eq(jobId), anyMap());
        order.verify(operations).recordIntakeStep(eq(jobId), eq("stage-zip"), eq("STAGE_UPLOAD"), contains(jobId.toString()));
        order.verify(storage).putStaging(contains(jobId.toString()), any());
        order.verify(operations).completeIntakeStep(jobId, "stage-zip"); order.verify(operations).makeRunnable(jobId);
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
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
