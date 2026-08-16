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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class RecoveryArchiveImportService {
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final Set<String> ITEM_TYPES = Set.of("RECORD", "OBJECT", "VECTOR");
    private static final Set<String> REQUIRED_ITEM_KEYS = Set.of(
            "record:knowledge-base",
            "record:documents",
            "record:chunks",
            "record:evaluation-cases",
            "record:quality-policy",
            "record:retrieval-profiles",
            "vector:dense");

    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository items;
    private final KnowledgeBaseService knowledgeBases;
    private final RecoveryStorageService storage;
    private final RecoveryProperties properties;
    private final RecoveryManifestService manifests;
    private final AuditLogService auditLogService;

    @Transactional
    public RecoveryArchive importZip(UUID knowledgeBaseId, MultipartFile file, String actor) {
        KnowledgeBase knowledgeBase = knowledgeBases.findOrThrow(knowledgeBaseId);
        validateUpload(file);
        Path temporaryDirectory = createTemporaryDirectory();
        UUID importedArchiveId = UUID.randomUUID();
        boolean storageStarted = false;
        try {
            Map<String, ExtractedEntry> extracted = extract(file, temporaryDirectory);
            ExtractedEntry manifestEntry = extracted.get(MANIFEST_PATH);
            if (manifestEntry == null) {
                throw invalid("Recovery ZIP is missing manifest.json");
            }
            if (manifestEntry.byteSize() > MAX_MANIFEST_BYTES) {
                throw invalid("Recovery manifest exceeds the 16 MiB limit");
            }

            RecoveryManifest sourceManifest = manifests.parseAndValidate(Files.readAllBytes(manifestEntry.path()));
            validateIdentity(sourceManifest, knowledgeBase);
            Map<String, RecoveryManifestItem> sourceItemsByPath = validateEntries(sourceManifest, extracted);

            String importedPrefix = archivePrefix(knowledgeBase.getTenantId(), importedArchiveId);
            List<RecoveryManifestItem> importedItems = sourceItemsByPath.entrySet().stream()
                    .map(entry -> new RecoveryManifestItem(
                            entry.getValue().itemKey(),
                            entry.getValue().itemType(),
                            importedPrefix + entry.getKey(),
                            entry.getValue().byteSize(),
                            entry.getValue().sha256()))
                    .toList();
            RecoveryManifest importedManifest = manifests.seal(new RecoveryManifestHeader(
                    RecoveryManifestService.SCHEMA_VERSION,
                    importedArchiveId,
                    knowledgeBase.getTenantId(),
                    knowledgeBaseId,
                    sourceManifest.header().sourceRevision(),
                    sourceManifest.header().embeddingModel(),
                    sourceManifest.header().embeddingDimension(),
                    sourceManifest.header().collectionSettings()), importedItems);
            byte[] importedManifestBytes = manifests.serialize(importedManifest);

            RecoveryArchive archive = RecoveryArchive.builder()
                    .id(importedArchiveId)
                    .tenantId(knowledgeBase.getTenantId())
                    .sourceKnowledgeBaseId(knowledgeBaseId)
                    .status(RecoveryArchiveStatus.COMPLETED)
                    .schemaVersion(RecoveryManifestService.SCHEMA_VERSION)
                    .bucket(properties.getBucket())
                    .objectPrefix(importedPrefix)
                    .sourceRevision(sourceManifest.header().sourceRevision())
                    .itemCount(importedManifest.itemCount())
                    .totalBytes(importedManifest.totalBytes())
                    .manifestChecksum(importedManifest.manifestChecksum())
                    .createdBy(actor == null || actor.isBlank() ? "system" : actor)
                    .build();
            archives.save(archive);

            storageStarted = true;
            List<RecoveryArchiveItem> importedItemRows = new ArrayList<>();
            for (Map.Entry<String, RecoveryManifestItem> entry : sourceItemsByPath.entrySet()) {
                ExtractedEntry extractedEntry = extracted.get(entry.getKey());
                try (InputStream input = Files.newInputStream(extractedEntry.path())) {
                    StoredRecoveryObject stored = storage.put(
                            knowledgeBase.getTenantId(), importedArchiveId, entry.getKey(), input);
                    verifyStored(entry.getValue(), stored);
                    importedItemRows.add(itemRow(importedArchiveId, entry.getValue(), stored));
                }
            }
            StoredRecoveryObject storedManifest = storage.put(
                    knowledgeBase.getTenantId(), importedArchiveId, MANIFEST_PATH,
                    new ByteArrayInputStream(importedManifestBytes));
            if (!storage.verify(storedManifest)) {
                throw invalid("Imported recovery manifest failed storage verification");
            }
            importedItemRows.add(RecoveryArchiveItem.builder()
                    .id(UUID.randomUUID())
                    .archiveId(importedArchiveId)
                    .itemKey("manifest")
                    .itemType("MANIFEST")
                    .objectKey(storedManifest.objectKey())
                    .byteSize(storedManifest.byteSize())
                    .sha256(storedManifest.sha256())
                    .status(RecoveryItemStatus.VERIFIED)
                    .attemptCount(1)
                    .build());
            items.saveAll(importedItemRows);
            auditLogService.recordSuccess("RECOVERY_ARCHIVE_IMPORT", "RECOVERY_ARCHIVE", importedArchiveId,
                    "Imported and verified recovery archive " + importedArchiveId
                            + " from manifest " + sourceManifest.header().archiveId());
            return archive;
        } catch (IllegalArgumentException exception) {
            if (storageStarted) safelyDelete(knowledgeBase.getTenantId(), importedArchiveId);
            throw exception;
        } catch (Exception exception) {
            if (storageStarted) safelyDelete(knowledgeBase.getTenantId(), importedArchiveId);
            throw invalid("Recovery ZIP could not be imported", exception);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("Select a non-empty Recovery ZIP file");
        }
        if (file.getSize() > properties.getMaxImportZipBytes()) {
            throw invalid("Recovery ZIP exceeds the configured compressed size limit");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw invalid("Recovery archive import only accepts .zip files");
        }
    }

    private Map<String, ExtractedEntry> extract(MultipartFile file, Path temporaryDirectory) throws IOException {
        Map<String, ExtractedEntry> entries = new LinkedHashMap<>();
        long totalBytes = 0;
        int fileCount = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            byte[] buffer = new byte[8192];
            while ((zipEntry = zip.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    String directoryName = zipEntry.getName().endsWith("/")
                            ? zipEntry.getName().substring(0, zipEntry.getName().length() - 1)
                            : zipEntry.getName();
                    safeEntryName(directoryName);
                    zip.closeEntry();
                    continue;
                }
                String name = safeEntryName(zipEntry.getName());
                fileCount++;
                if (fileCount > properties.getMaxImportEntries()) {
                    throw invalid("Recovery ZIP contains too many files");
                }
                if (entries.containsKey(name)) {
                    throw invalid("Recovery ZIP contains duplicate entry: " + name);
                }
                Path target = temporaryDirectory.resolve(name).normalize();
                if (!target.startsWith(temporaryDirectory)) {
                    throw invalid("Recovery ZIP contains an unsafe path: " + name);
                }
                Files.createDirectories(target.getParent());
                MessageDigest digest = sha256();
                long entryBytes = 0;
                try (OutputStream output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > properties.getMaxImportEntryBytes()) {
                            throw invalid("Recovery ZIP entry exceeds the configured size limit: " + name);
                        }
                        if (totalBytes > properties.getMaxImportUncompressedBytes()) {
                            throw invalid("Recovery ZIP exceeds the configured uncompressed size limit");
                        }
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                entries.put(name, new ExtractedEntry(target, entryBytes, HexFormat.of().formatHex(digest.digest())));
                zip.closeEntry();
            }
        } catch (ZipException exception) {
            throw invalid("Recovery ZIP is invalid or corrupted", exception);
        }
        if (entries.isEmpty()) {
            throw invalid("Recovery ZIP does not contain any files");
        }
        long compressedBytes = Math.max(1L, file.getSize());
        if (totalBytes > compressedBytes * (long) properties.getMaxImportCompressionRatio()) {
            throw invalid("Recovery ZIP compression ratio exceeds the configured safety limit");
        }
        return entries;
    }

    private void validateIdentity(RecoveryManifest manifest, KnowledgeBase knowledgeBase) {
        if (!knowledgeBase.getTenantId().equals(manifest.header().tenantId())) {
            throw invalid("Recovery ZIP belongs to a different tenant");
        }
        if (!knowledgeBase.getId().equals(manifest.header().sourceKnowledgeBaseId())) {
            throw invalid("Recovery ZIP belongs to a different knowledge base");
        }
    }

    private Map<String, RecoveryManifestItem> validateEntries(
            RecoveryManifest manifest, Map<String, ExtractedEntry> extracted) {
        String sourcePrefix = archivePrefix(manifest.header().tenantId(), manifest.header().archiveId());
        Map<String, RecoveryManifestItem> itemsByPath = new HashMap<>();
        Set<String> itemKeys = new HashSet<>();
        for (RecoveryManifestItem item : manifest.items()) {
            if (!ITEM_TYPES.contains(item.itemType())) {
                throw invalid("Recovery manifest contains unsupported item type: " + item.itemType());
            }
            if (!item.sha256().matches("[0-9a-f]{64}")) {
                throw invalid("Recovery manifest contains an invalid SHA-256: " + item.itemKey());
            }
            itemKeys.add(item.itemKey());
            if (!item.objectKey().startsWith(sourcePrefix)) {
                throw invalid("Recovery manifest item is outside its archive: " + item.itemKey());
            }
            String path = safeEntryName(item.objectKey().substring(sourcePrefix.length()));
            if (MANIFEST_PATH.equals(path) || itemsByPath.putIfAbsent(path, item) != null) {
                throw invalid("Recovery manifest contains a duplicate file path: " + path);
            }
            ExtractedEntry actual = extracted.get(path);
            if (actual == null) {
                throw invalid("Recovery ZIP is missing manifest item: " + path);
            }
            if (actual.byteSize() != item.byteSize() || !actual.sha256().equals(item.sha256())) {
                throw invalid("Recovery ZIP checksum or size mismatch: " + path);
            }
        }
        Set<String> missingItemKeys = new HashSet<>(REQUIRED_ITEM_KEYS);
        missingItemKeys.removeAll(itemKeys);
        if (!missingItemKeys.isEmpty()) {
            throw invalid("Recovery manifest is missing required item: "
                    + missingItemKeys.stream().sorted().findFirst().orElse("unknown"));
        }
        Set<String> expectedPaths = new HashSet<>(itemsByPath.keySet());
        expectedPaths.add(MANIFEST_PATH);
        Set<String> unexpectedPaths = new HashSet<>(extracted.keySet());
        unexpectedPaths.removeAll(expectedPaths);
        if (!unexpectedPaths.isEmpty()) {
            throw invalid("Recovery ZIP contains files not declared by manifest: "
                    + unexpectedPaths.stream().sorted().findFirst().orElse("unknown"));
        }
        return itemsByPath.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }

    private void verifyStored(RecoveryManifestItem expected, StoredRecoveryObject stored) {
        if (stored.byteSize() != expected.byteSize() || !stored.sha256().equals(expected.sha256())
                || !storage.verify(stored)) {
            throw invalid("Imported recovery object failed storage verification: " + expected.itemKey());
        }
    }

    private RecoveryArchiveItem itemRow(
            UUID archiveId, RecoveryManifestItem source, StoredRecoveryObject stored) {
        return RecoveryArchiveItem.builder()
                .id(UUID.randomUUID())
                .archiveId(archiveId)
                .itemKey(source.itemKey())
                .itemType(source.itemType())
                .objectKey(stored.objectKey())
                .byteSize(stored.byteSize())
                .sha256(stored.sha256())
                .status(RecoveryItemStatus.VERIFIED)
                .attemptCount(1)
                .build();
    }

    private String archivePrefix(String tenantId, UUID archiveId) {
        return "archives/" + tenantId + "/" + archiveId + "/";
    }

    private String safeEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw invalid("Recovery ZIP contains an invalid entry path");
        }
        Path normalized = Path.of(name).normalize();
        String normalizedName = normalized.toString().replace(java.io.File.separatorChar, '/');
        if (normalized.isAbsolute() || normalizedName.equals("..") || normalizedName.startsWith("../")
                || !normalizedName.equals(name)) {
            throw invalid("Recovery ZIP contains an unsafe entry path: " + name);
        }
        return normalizedName;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("dupi-recovery-import-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create Recovery ZIP import workspace", exception);
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private void safelyDelete(String tenantId, UUID archiveId) {
        try { storage.deleteArchive(tenantId, archiveId); } catch (RuntimeException ignored) { }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }

    private record ExtractedEntry(Path path, long byteSize, String sha256) { }
}
