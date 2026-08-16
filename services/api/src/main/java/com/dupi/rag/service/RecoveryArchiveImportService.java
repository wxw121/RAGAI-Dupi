package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** Validates a ZIP completely, then durably stages it before scheduling deterministic promotion. */
@Service
@RequiredArgsConstructor
public class RecoveryArchiveImportService {
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final Set<String> ITEM_TYPES = Set.of("RECORD", "OBJECT", "VECTOR");
    private static final Set<String> REQUIRED_ITEM_KEYS = Set.of(
            "record:knowledge-base", "record:documents", "record:chunks", "record:evaluation-cases",
            "record:quality-policy", "record:retrieval-profiles", "vector:dense");

    private final KnowledgeBaseService knowledgeBases;
    private final RecoveryStorageService storage;
    private final RecoveryProperties properties;
    private final RecoveryManifestService manifests;
    private final RecoveryArchiveImportIntakeService intakeService;

    public OperationJobResponse submit(UUID knowledgeBaseId, MultipartFile file, String idempotencyKey, String actor) {
        KnowledgeBase knowledgeBase = knowledgeBases.findOrThrow(knowledgeBaseId);
        validateUpload(file);
        Path temporaryDirectory = createTemporaryDirectory();
        try {
            Map<String, ExtractedEntry> extracted = extract(file, temporaryDirectory);
            RecoveryManifest source = parseManifest(extracted);
            validateIdentity(source, knowledgeBase);
            List<RecoveryArchiveImportPlan.Entry> entries = validateEntries(source, extracted);
            String zipSha256 = digest(file);
            String key = idempotencyKey == null || idempotencyKey.isBlank()
                    ? zipSha256 + ":" + knowledgeBaseId : idempotencyKey.trim();
            String creator = actor == null || actor.isBlank() ? "system" : actor;
            RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(source.header().archiveId(),
                    knowledgeBase.getTenantId(), knowledgeBaseId, source.header().sourceRevision(),
                    source.header().embeddingModel(), source.header().embeddingDimension(), source.header().collectionSettings(),
                    source.manifestChecksum(), zipSha256, creator, entries);
            RecoveryImportIntake intake = intakeService.createOrResume(plan, key, creator);
            OperationJobResponse job = intake.job();
            if (intake.published()) return job;
            if (intake.cleanupPending()) {
                throw new IllegalStateException("Recovery ZIP staging cleanup is still in progress");
            }
            String stagingKey = storage.stagingKey(job.getId(), zipSha256);
            StoredRecoveryObject expectedStage = new StoredRecoveryObject(storage.bucket(), stagingKey,
                    file.getSize(), zipSha256);
            RecoveryStorageInspection inspection = storage.inspect(
                    intake.stageObject() == null ? expectedStage : intake.stageObject());
            RecoveryStorageOutcome stageOutcome = inspection.outcome();
            if (stageOutcome == RecoveryStorageOutcome.STALE_VERSION) {
                scheduleCleanup(job.getId(), plan,
                        new IllegalStateException("Recovery ZIP staging version changed"));
                throw new IllegalStateException("Recovery ZIP staging changed before publication");
            }
            if (stageOutcome == RecoveryStorageOutcome.CONFLICT) {
                try {
                    storage.delete(stagingKey);
                    inspection = storage.inspect(expectedStage);
                } catch (RuntimeException cleanupFailure) {
                    scheduleCleanup(job.getId(), plan, cleanupFailure);
                    throw new IllegalStateException("Recovery ZIP staging cleanup failed", cleanupFailure);
                }
                if (inspection.outcome() != RecoveryStorageOutcome.ABSENT) {
                    IllegalStateException conflict = new IllegalStateException(
                            "Recovery import staging key could not be cleared");
                    scheduleCleanup(job.getId(), plan, conflict);
                    throw conflict;
                }
                stageOutcome = RecoveryStorageOutcome.ABSENT;
            }
            if (stageOutcome == RecoveryStorageOutcome.ABSENT) {
                StoredRecoveryObject stored;
                try (InputStream input = file.getInputStream()) {
                    stored = storage.putStaging(expectedStage, input);
                } catch (Exception exception) {
                    try { storage.delete(stagingKey); } catch (Exception cleanup) { exception.addSuppressed(cleanup); }
                    scheduleCleanup(job.getId(), plan, exception);
                    throw new IllegalStateException("Recovery ZIP staging upload failed", exception);
                }
                inspection = storage.inspect(expectedStage);
                if (stored.byteSize() != file.getSize() || !zipSha256.equals(stored.sha256())
                        || inspection.outcome() != RecoveryStorageOutcome.MATCHING) {
                    IllegalArgumentException invalidStage = new IllegalArgumentException(
                            "Recovery ZIP staging verification failed");
                    try { storage.delete(stagingKey); } catch (Exception cleanup) { invalidStage.addSuppressed(cleanup); }
                    scheduleCleanup(job.getId(), plan, invalidStage);
                    throw invalidStage;
                }
            }
            StoredRecoveryObject evidence = inspection.object();
            RecoveryStorageInspection publicationCheck = storage.inspect(evidence);
            if (publicationCheck.outcome() != RecoveryStorageOutcome.MATCHING) {
                scheduleCleanup(job.getId(), plan,
                        new IllegalStateException("Recovery ZIP staging changed before publication"));
                throw new IllegalStateException("Recovery ZIP staging changed before publication");
            }
            return intakeService.completeStageAndPublish(job.getId(), plan, publicationCheck.object());
        } catch (IOException exception) {
            throw invalid("Recovery ZIP could not be read", exception);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private void scheduleCleanup(UUID jobId, RecoveryArchiveImportPlan plan, Throwable failure) {
        try {
            intakeService.scheduleCleanup(jobId, plan, failure);
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private RecoveryManifest parseManifest(Map<String, ExtractedEntry> extracted) throws IOException {
        ExtractedEntry manifest = extracted.get(MANIFEST_PATH);
        if (manifest == null) throw invalid("Recovery ZIP is missing manifest.json");
        if (manifest.byteSize() > MAX_MANIFEST_BYTES) throw invalid("Recovery manifest exceeds the 16 MiB limit");
        return manifests.parseAndValidate(Files.readAllBytes(manifest.path()));
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("Select a non-empty Recovery ZIP file");
        if (file.getSize() > properties.getMaxImportZipBytes()) throw invalid("Recovery ZIP exceeds the configured compressed size limit");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) throw invalid("Recovery archive import only accepts .zip files");
    }

    private Map<String, ExtractedEntry> extract(MultipartFile file, Path directory) throws IOException {
        Map<String, ExtractedEntry> entries = new LinkedHashMap<>(); long total = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            ZipEntry zipEntry; byte[] buffer = new byte[8192];
            while ((zipEntry = zip.getNextEntry()) != null) {
                if (++count > properties.getMaxImportEntries()) throw invalid("Recovery ZIP contains too many files");
                if (zipEntry.isDirectory()) { safeEntryName(trimDirectory(zipEntry.getName())); zip.closeEntry(); continue; }
                String name = safeEntryName(zipEntry.getName());
                if (entries.containsKey(name)) throw invalid("Recovery ZIP contains duplicate entry: " + name);
                Path target = directory.resolve(name).normalize();
                if (!target.startsWith(directory)) throw invalid("Recovery ZIP contains an unsafe path: " + name);
                Files.createDirectories(target.getParent()); MessageDigest digest = sha256(); long bytes = 0;
                try (OutputStream output = Files.newOutputStream(target)) {
                    for (int read; (read = zip.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        bytes += read; total += read;
                        if (bytes > properties.getMaxImportEntryBytes()) throw invalid("Recovery ZIP entry exceeds the configured size limit: " + name);
                        if (total > properties.getMaxImportUncompressedBytes()) throw invalid("Recovery ZIP exceeds the configured uncompressed size limit");
                        digest.update(buffer, 0, read); output.write(buffer, 0, read);
                    }
                }
                entries.put(name, new ExtractedEntry(target, bytes, HexFormat.of().formatHex(digest.digest()))); zip.closeEntry();
            }
        } catch (ZipException exception) { throw invalid("Recovery ZIP is invalid or corrupted", exception); }
        if (entries.isEmpty()) throw invalid("Recovery ZIP does not contain any files");
        if (total > Math.max(1L, file.getSize()) * (long) properties.getMaxImportCompressionRatio()) throw invalid("Recovery ZIP compression ratio exceeds the configured safety limit");
        return entries;
    }

    private List<RecoveryArchiveImportPlan.Entry> validateEntries(RecoveryManifest manifest, Map<String, ExtractedEntry> extracted) {
        String prefix = archivePrefix(manifest.header().tenantId(), manifest.header().archiveId());
        Map<String, RecoveryManifestItem> byPath = new HashMap<>(); Set<String> keys = new HashSet<>();
        for (RecoveryManifestItem item : manifest.items()) {
            if (!ITEM_TYPES.contains(item.itemType())) throw invalid("Recovery manifest contains unsupported item type: " + item.itemType());
            if ("manifest".equals(item.itemKey())) throw invalid("Recovery manifest item key is reserved: manifest");
            if (!keys.add(item.itemKey())) throw invalid("Recovery manifest contains duplicate item key: " + item.itemKey());
            if (!item.sha256().matches("[0-9a-f]{64}")) throw invalid("Recovery manifest contains an invalid SHA-256: " + item.itemKey());
            if (!item.objectKey().startsWith(prefix)) throw invalid("Recovery manifest item is outside its archive: " + item.itemKey());
            String path = safeEntryName(item.objectKey().substring(prefix.length()));
            if (MANIFEST_PATH.equals(path) || byPath.putIfAbsent(path, item) != null) throw invalid("Recovery manifest contains a duplicate file path: " + path);
            ExtractedEntry actual = extracted.get(path);
            if (actual == null) throw invalid("Recovery ZIP is missing manifest item: " + path);
            if (actual.byteSize() != item.byteSize() || !actual.sha256().equals(item.sha256())) throw invalid("Recovery ZIP checksum or size mismatch: " + path);
        }
        Set<String> missing = new HashSet<>(REQUIRED_ITEM_KEYS); missing.removeAll(keys);
        if (!missing.isEmpty()) throw invalid("Recovery manifest is missing required item: " + missing.stream().sorted().findFirst().orElse("unknown"));
        Set<String> unexpected = new HashSet<>(extracted.keySet()); unexpected.removeAll(byPath.keySet()); unexpected.remove(MANIFEST_PATH);
        if (!unexpected.isEmpty()) throw invalid("Recovery ZIP contains files not declared by manifest: " + unexpected.stream().sorted().findFirst().orElse("unknown"));
        return byPath.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            RecoveryManifestItem item = entry.getValue(); return new RecoveryArchiveImportPlan.Entry(item.itemKey(), item.itemType(), entry.getKey(), item.byteSize(), item.sha256());
        }).toList();
    }

    private void validateIdentity(RecoveryManifest manifest, KnowledgeBase knowledgeBase) {
        if (!knowledgeBase.getTenantId().equals(manifest.header().tenantId())) throw invalid("Recovery ZIP belongs to a different tenant");
        if (!knowledgeBase.getId().equals(manifest.header().sourceKnowledgeBaseId())) throw invalid("Recovery ZIP belongs to a different knowledge base");
    }
    private String digest(MultipartFile file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private String archivePrefix(String tenant, UUID archiveId) { return "archives/" + tenant + "/" + archiveId + "/"; }
    private String trimDirectory(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String safeEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || name.indexOf('\0') >= 0) throw invalid("Recovery ZIP contains an invalid entry path");
        Path normalized = Path.of(name).normalize(); String safe = normalized.toString().replace(java.io.File.separatorChar, '/');
        if (normalized.isAbsolute() || safe.equals("..") || safe.startsWith("../") || !safe.equals(name)) throw invalid("Recovery ZIP contains an unsafe entry path: " + name);
        return safe;
    }
    private MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); } }
    private Path createTemporaryDirectory() { try { return Files.createTempDirectory("dupi-recovery-import-").toAbsolutePath().normalize(); } catch (IOException exception) { throw new IllegalStateException("Failed to create Recovery ZIP import workspace", exception); } }
    private void deleteTemporaryDirectory(Path directory) { try (var paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } catch (IOException ignored) { } }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private IllegalArgumentException invalid(String message, Exception cause) { return new IllegalArgumentException(message, cause); }
    private record ExtractedEntry(Path path, long byteSize, String sha256) { }
}
