package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class RecoveryStorageService {
    private final RecoveryProperties properties;
    private final RecoveryObjectStore objectStore;

    public StoredRecoveryObject put(String tenantId, UUID archiveId, String relativeKey, InputStream input) {
        return putAtKey(archivePrefix(tenantId, archiveId) + validateRelativeKey(relativeKey), input);
    }

    public String stagingKey(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("Recovery staging job is required");
        return "recovery-staging/" + jobId + ".zip";
    }

    public String stagingKey(UUID jobId, String zipSha256) {
        if (jobId == null) throw new IllegalArgumentException("Recovery staging job is required");
        if (zipSha256 == null || !zipSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Recovery staging ZIP hash is required");
        }
        String suffix = zipSha256;
        return "recovery-staging/" + suffix + "/" + jobId + ".zip";
    }

    public String bucket() {
        return properties.getBucket();
    }

    public String finalKey(String tenantId, UUID jobId, String relativeKey) {
        return archivePrefix(tenantId, jobId) + validateRelativeKey(relativeKey);
    }

    /** Writes only to an absent content-addressed stage; matching bytes are an idempotent success. */
    public StoredRecoveryObject putStaging(StoredRecoveryObject expected, InputStream input) {
        if (expected == null || expected.objectKey() == null
                || !expected.objectKey().startsWith("recovery-staging/")) {
            throw new IllegalArgumentException("Invalid recovery staging key");
        }
        RecoveryStorageInspection before = inspect(expected);
        if (before.outcome() == RecoveryStorageOutcome.MATCHING) return before.object();
        if (before.outcome() != RecoveryStorageOutcome.ABSENT) {
            throw new RecoveryStorageConflictException(
                    "Recovery staging object already contains different bytes or version");
        }
        CountingDigestInputStream digestInput = new CountingDigestInputStream(input);
        RecoveryObjectWriteResult write;
        try {
            write = objectStore.putIfAbsent(properties.getBucket(), expected.objectKey(), digestInput);
        } catch (Exception exception) {
            throw new RecoveryStorageUnavailableException("Failed to write recovery staging object", exception);
        }
        if (!write.created()) {
            RecoveryStorageInspection winner = inspect(expected);
            if (winner.outcome() == RecoveryStorageOutcome.MATCHING) return winner.object();
            if (winner.outcome() == RecoveryStorageOutcome.ABSENT) {
                throw new RecoveryStorageUnavailableException("Recovery staging create winner is not yet visible",
                        new IllegalStateException("conditional create race has no visible winner"));
            }
            throw new RecoveryStorageConflictException(
                    "Recovery staging create winner differs from its immutable plan");
        }
        StoredRecoveryObject stored = new StoredRecoveryObject(properties.getBucket(), expected.objectKey(),
                digestInput.byteCount(), digestInput.hexDigest(), write.versionToken());
        if (stored.byteSize() != expected.byteSize() || !stored.sha256().equals(expected.sha256())) {
            throw new RecoveryStorageConflictException("Recovery staging upload differs from its immutable plan");
        }
        return stored;
    }

    public StoredRecoveryObject putFinal(String tenantId, UUID jobId, String relativeKey, InputStream input) {
        return putAtKey(finalKey(tenantId, jobId, relativeKey), input);
    }

    public void delete(String objectKey) {
        try {
            objectStore.delete(properties.getBucket(), objectKey);
        } catch (RecoveryObjectNotFoundException absent) {
            // A confirmed missing object satisfies an idempotent delete.
        } catch (Exception exception) {
            throw new RecoveryStorageUnavailableException("Failed to delete recovery object", exception);
        }
    }

    /** @deprecated use {@link #delete(String)}; retained for older domain callers. */
    @Deprecated
    public void deleteIfPresent(String objectKey) {
        delete(objectKey);
    }

    private StoredRecoveryObject putAtKey(String objectKey, InputStream input) {
        CountingDigestInputStream digestInput = new CountingDigestInputStream(input);
        try {
            objectStore.put(properties.getBucket(), objectKey, digestInput);
            return new StoredRecoveryObject(properties.getBucket(), objectKey,
                    digestInput.byteCount(), digestInput.hexDigest(),
                    requireVersion(properties.getBucket(), objectKey));
        } catch (Exception e) {
            throw new RecoveryStorageUnavailableException("Failed to write recovery object", e);
        }
    }

    public RecoveryStorageInspection inspect(StoredRecoveryObject expected) {
        return inspect(expected, () -> { });
    }

    public RecoveryStorageInspection inspect(StoredRecoveryObject expected, Runnable heartbeat) {
        if (heartbeat == null) throw new IllegalArgumentException("Recovery inspection heartbeat is required");
        try {
            String before = requireVersion(expected.bucket(), expected.objectKey());
            try (InputStream input = objectStore.get(expected.bucket(), expected.objectKey())) {
                CountingDigestInputStream digestInput = new CountingDigestInputStream(
                        new HeartbeatInputStream(input, heartbeat));
                digestInput.transferTo(OutputStreamSink.INSTANCE);
                String after = requireVersion(expected.bucket(), expected.objectKey());
                if (!before.equals(after)) {
                    throw new RecoveryStorageUnavailableException(
                            "Recovery object changed while it was being inspected",
                            new IllegalStateException("object version changed during inspection"));
                }
                StoredRecoveryObject actual = new StoredRecoveryObject(expected.bucket(), expected.objectKey(),
                        digestInput.byteCount(), digestInput.hexDigest(), after);
                if (actual.byteSize() != expected.byteSize() || !actual.sha256().equals(expected.sha256())) {
                    return RecoveryStorageInspection.of(RecoveryStorageOutcome.CONFLICT, actual);
                }
                if (expected.versionToken() != null && !expected.versionToken().equals(after)) {
                    return RecoveryStorageInspection.of(RecoveryStorageOutcome.STALE_VERSION, actual);
                }
                return RecoveryStorageInspection.of(RecoveryStorageOutcome.MATCHING, actual);
            }
        } catch (RecoveryObjectNotFoundException absent) {
            return RecoveryStorageInspection.absent();
        } catch (RecoveryStorageUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RecoveryStorageUnavailableException("Failed to inspect recovery object", exception);
        }
    }

    /** Checks immutable version evidence without re-reading a potentially large staged ZIP. */
    public RecoveryStorageInspection inspectVersion(StoredRecoveryObject expected) {
        if (expected.versionToken() == null || expected.versionToken().isBlank()) {
            throw new IllegalArgumentException("Recovery version-only inspection requires version evidence");
        }
        try {
            String actual = requireVersion(expected.bucket(), expected.objectKey());
            if (!expected.versionToken().equals(actual)) {
                return RecoveryStorageInspection.of(RecoveryStorageOutcome.STALE_VERSION,
                        new StoredRecoveryObject(expected.bucket(), expected.objectKey(), expected.byteSize(),
                                expected.sha256(), actual));
            }
            return RecoveryStorageInspection.of(RecoveryStorageOutcome.MATCHING, expected);
        } catch (RecoveryObjectNotFoundException absent) {
            return RecoveryStorageInspection.absent();
        } catch (Exception exception) {
            throw new RecoveryStorageUnavailableException("Failed to inspect recovery object version", exception);
        }
    }

    public boolean verify(StoredRecoveryObject expected) {
        return inspect(expected).outcome() == RecoveryStorageOutcome.MATCHING;
    }

    public StoredRecoveryObject describe(String objectKey) {
        try (InputStream input = objectStore.get(properties.getBucket(), objectKey)) {
            CountingDigestInputStream digestInput = new CountingDigestInputStream(input);
            digestInput.transferTo(OutputStreamSink.INSTANCE);
            return new StoredRecoveryObject(properties.getBucket(), objectKey, digestInput.byteCount(),
                    digestInput.hexDigest(), requireVersion(properties.getBucket(), objectKey));
        } catch (Exception e) {
            throw new RecoveryStorageUnavailableException("Failed to describe recovery object", e);
        }
    }

    public void deleteArchive(String tenantId, UUID archiveId) {
        String prefix = archivePrefix(tenantId, archiveId);
        try {
            for (String key : objectStore.list(properties.getBucket(), prefix)) {
                objectStore.delete(properties.getBucket(), key);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete recovery archive", e);
        }
    }

    public InputStream open(String bucket, String objectKey) {
        try {
            return objectStore.get(bucket, objectKey);
        } catch (RecoveryObjectNotFoundException absent) {
            throw new RecoveryStorageConflictException("Recovery object is absent: " + objectKey, absent);
        } catch (Exception e) {
            throw new RecoveryStorageUnavailableException("Failed to open recovery object", e);
        }
    }

    public byte[] readSmall(String bucket, String objectKey, int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Recovery read limit must be positive");
        try (InputStream input = objectStore.get(bucket, objectKey);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximumBytes) {
                    throw new IllegalArgumentException("Recovery object exceeds read limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read recovery object", e);
        }
    }

    public void streamZip(String tenantId, UUID archiveId, OutputStream output) {
        String prefix = archivePrefix(tenantId, archiveId);
        try (ZipOutputStream zip = new ZipOutputStream(output, java.nio.charset.StandardCharsets.UTF_8)) {
            for (String key : objectStore.list(properties.getBucket(), prefix).stream().sorted().toList()) {
                zip.putNextEntry(new ZipEntry(key.substring(prefix.length())));
                try (InputStream input = objectStore.get(properties.getBucket(), key)) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
            zip.finish();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stream recovery archive ZIP", e);
        }
    }

    private String archivePrefix(String tenantId, UUID archiveId) {
        if (tenantId == null || tenantId.isBlank() || tenantId.contains("/") || tenantId.contains("\\")) {
            throw new IllegalArgumentException("Invalid recovery tenant");
        }
        return "archives/" + tenantId + "/" + archiveId + "/";
    }

    private String validateRelativeKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.startsWith("\\")
                || key.contains("..") || key.contains("\\")) {
            throw new IllegalArgumentException("Invalid relative archive key");
        }
        return key;
    }

    private String requireVersion(String bucket, String key) throws Exception {
        String version = objectStore.version(bucket, key);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Recovery object store did not return an object version");
        }
        return version;
    }

    private static final class CountingDigestInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long byteCount;

        private CountingDigestInputStream(InputStream input) {
            super(input);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
                byteCount++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                digest.update(bytes, offset, read);
                byteCount += read;
            }
            return read;
        }

        private long byteCount() { return byteCount; }
        private String hexDigest() { return HexFormat.of().formatHex(digest.digest()); }
    }

    private static final class HeartbeatInputStream extends FilterInputStream {
        private static final long RENEW_BYTES = 64L * 1024L;
        private static final long RENEW_NANOS = 5_000_000_000L;
        private final Runnable heartbeat;
        private long bytesSinceHeartbeat;
        private long lastHeartbeat = System.nanoTime();

        private HeartbeatInputStream(InputStream input, Runnable heartbeat) {
            super(input);
            this.heartbeat = heartbeat;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) afterRead(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, (int) Math.min(length, RENEW_BYTES));
            if (read > 0) afterRead(read);
            return read;
        }

        private void afterRead(int count) {
            bytesSinceHeartbeat += count;
            long now = System.nanoTime();
            if (bytesSinceHeartbeat >= RENEW_BYTES || now - lastHeartbeat >= RENEW_NANOS) {
                heartbeat.run();
                bytesSinceHeartbeat = 0;
                lastHeartbeat = now;
            }
        }
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int value) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    }
}
