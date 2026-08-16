package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryArchiveImportServiceTest {
    @Mock RecoveryArchiveRepository archives;
    @Mock RecoveryArchiveItemRepository items;
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock RecoveryStorageService storage;
    @Mock AuditLogService auditLogService;

    private RecoveryArchiveImportService service;
    private RecoveryManifestService manifests;
    private RecoveryProperties properties;
    private UUID kbId;
    private KnowledgeBase knowledgeBase;
    private Map<String, byte[]> storedBytes;

    @BeforeEach
    void setUp() {
        properties = new RecoveryProperties();
        properties.setBucket("dupi-recovery");
        manifests = new RecoveryManifestService(new ObjectMapper().findAndRegisterModules());
        service = new RecoveryArchiveImportService(
                archives, items, knowledgeBases, storage, properties, manifests, auditLogService);
        kbId = UUID.randomUUID();
        knowledgeBase = KnowledgeBase.builder().id(kbId).tenantId("tenant-a").name("KB")
                .embeddingModel("embedding-2").embeddingDimension(1024).build();
        storedBytes = new LinkedHashMap<>();
        lenient().when(knowledgeBases.findOrThrow(kbId)).thenReturn(knowledgeBase);
        lenient().when(archives.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(items.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(storage.verify(any())).thenReturn(true);
        lenient().when(storage.put(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            UUID archiveId = invocation.getArgument(1);
            String path = invocation.getArgument(2);
            InputStream input = invocation.getArgument(3);
            byte[] bytes = input.readAllBytes();
            String objectKey = "archives/" + tenant + "/" + archiveId + "/" + path;
            storedBytes.put(objectKey, bytes);
            return new StoredRecoveryObject("dupi-recovery", objectKey,
                    bytes.length, sha256(bytes));
        });
    }

    @Test
    void importsVerifiedZipAsNewCompletedArchive() throws Exception {
        UUID sourceArchiveId = UUID.randomUUID();
        MockMultipartFile zip = validArchiveZip(sourceArchiveId, kbId, "tenant-a", null, false);

        RecoveryArchive imported = service.importZip(kbId, zip, "admin");

        assertThat(imported.getId()).isNotEqualTo(sourceArchiveId);
        assertThat(imported.getStatus()).isEqualTo(RecoveryArchiveStatus.COMPLETED);
        assertThat(imported.getSourceKnowledgeBaseId()).isEqualTo(kbId);
        assertThat(imported.getItemCount()).isEqualTo(7);
        assertThat(imported.getManifestChecksum()).hasSize(64);
        ArgumentCaptor<List<RecoveryArchiveItem>> rows = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(8)
                .allMatch(item -> item.getStatus() == RecoveryItemStatus.VERIFIED);
        assertThat(rows.getValue()).extracting(RecoveryArchiveItem::getItemKey).contains("manifest", "vector:dense");
        RecoveryManifest storedManifest = manifests.parseAndValidate(storedBytes.get(
                "archives/tenant-a/" + imported.getId() + "/manifest.json"));
        assertThat(storedManifest.header().archiveId()).isEqualTo(imported.getId());
        assertThat(storedManifest.header().sourceKnowledgeBaseId()).isEqualTo(kbId);
        assertThat(storedManifest.items()).allMatch(item -> item.objectKey().startsWith(
                "archives/tenant-a/" + imported.getId() + "/"));
        verify(storage, times(8)).put(eq("tenant-a"), eq(imported.getId()), anyString(), any());
        verify(auditLogService).recordSuccess(eq("RECOVERY_ARCHIVE_IMPORT"), eq("RECOVERY_ARCHIVE"),
                eq(imported.getId()), contains(sourceArchiveId.toString()));
    }

    @Test
    void rejectsTamperedEntryBeforeWritingAnything() throws Exception {
        MockMultipartFile zip = validArchiveZip(UUID.randomUUID(), kbId, "tenant-a",
                "records/chunks.ndjson", false);

        assertThatThrownBy(() -> service.importZip(kbId, zip, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum or size mismatch")
                .hasMessageContaining("records/chunks.ndjson");

        verifyNoInteractions(archives, items, storage, auditLogService);
    }

    @Test
    void rejectsUndeclaredFilesAndWrongKnowledgeBase() throws Exception {
        MockMultipartFile extraFileZip = validArchiveZip(
                UUID.randomUUID(), kbId, "tenant-a", null, true);
        assertThatThrownBy(() -> service.importZip(kbId, extraFileZip, "admin"))
                .hasMessageContaining("not declared by manifest");

        MockMultipartFile wrongKbZip = validArchiveZip(
                UUID.randomUUID(), UUID.randomUUID(), "tenant-a", null, false);
        assertThatThrownBy(() -> service.importZip(kbId, wrongKbZip, "admin"))
                .hasMessageContaining("different knowledge base");

        verifyNoInteractions(archives, items, storage, auditLogService);
    }

    @Test
    void rejectsUnsafeZipPathsAndConfiguredSizeLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../manifest.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        MockMultipartFile unsafe = new MockMultipartFile(
                "file", "unsafe.zip", "application/zip", output.toByteArray());
        assertThatThrownBy(() -> service.importZip(kbId, unsafe, "admin"))
                .hasMessageContaining("unsafe entry path");

        properties.setMaxImportZipBytes(1);
        MockMultipartFile oversized = validArchiveZip(
                UUID.randomUUID(), kbId, "tenant-a", null, false);
        assertThatThrownBy(() -> service.importZip(kbId, oversized, "admin"))
                .hasMessageContaining("compressed size limit");
    }

    @Test
    void acceptsOrdinaryDirectoryEntries() throws Exception {
        MockMultipartFile source = validArchiveZip(UUID.randomUUID(), kbId, "tenant-a", null, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output);
             java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(source.getInputStream())) {
            zip.putNextEntry(new ZipEntry("records/"));
            zip.closeEntry();
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                input.transferTo(zip);
                zip.closeEntry();
            }
        }
        MockMultipartFile withDirectories = new MockMultipartFile(
                "file", "dupi-recovery.zip", "application/zip", output.toByteArray());

        assertThat(service.importZip(kbId, withDirectories, "admin").getStatus())
                .isEqualTo(RecoveryArchiveStatus.COMPLETED);
    }

    private MockMultipartFile validArchiveZip(
            UUID archiveId, UUID manifestKbId, String tenantId, String tamperedPath, boolean includeExtra)
            throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("records/knowledge-base.json", "{}".getBytes());
        contents.put("records/documents.ndjson", new byte[0]);
        contents.put("records/chunks.ndjson", "{\"content\":\"guide\"}\n".getBytes());
        contents.put("records/evaluation-cases.ndjson", new byte[0]);
        contents.put("records/quality-policy.json", "null".getBytes());
        contents.put("records/retrieval-profiles.ndjson", new byte[0]);
        contents.put("vectors/dense.ndjson", "{\"vector\":[0.1]}\n".getBytes());
        List<String> itemKeys = List.of(
                "record:knowledge-base", "record:documents", "record:chunks", "record:evaluation-cases",
                "record:quality-policy", "record:retrieval-profiles", "vector:dense");
        List<String> itemTypes = List.of("RECORD", "RECORD", "RECORD", "RECORD", "RECORD", "RECORD", "VECTOR");
        List<RecoveryManifestItem> manifestItems = new java.util.ArrayList<>();
        int index = 0;
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            manifestItems.add(new RecoveryManifestItem(
                    itemKeys.get(index), itemTypes.get(index),
                    "archives/" + tenantId + "/" + archiveId + "/" + entry.getKey(),
                    entry.getValue().length, sha256(entry.getValue())));
            index++;
        }
        RecoveryManifest manifest = manifests.seal(new RecoveryManifestHeader(
                1, archiveId, tenantId, manifestKbId, Instant.parse("2026-07-15T12:00:00Z"),
                "embedding-2", 1024, Map.of("retrievalMode", "VECTOR")), manifestItems);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getKey().equals(tamperedPath) ? "tampered".getBytes() : entry.getValue());
                zip.closeEntry();
            }
            if (includeExtra) {
                zip.putNextEntry(new ZipEntry("unexpected.txt"));
                zip.write("extra".getBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifests.serialize(manifest));
            zip.closeEntry();
        }
        return new MockMultipartFile(
                "file", "dupi-recovery.zip", "application/zip", output.toByteArray());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
