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

@Service
@RequiredArgsConstructor
public class RecoveryStorageService {
    private final RecoveryProperties properties;
    private final RecoveryObjectStore objectStore;

    public StoredRecoveryObject put(String tenantId, UUID archiveId, String relativeKey, InputStream input) {
        String objectKey = archivePrefix(tenantId, archiveId) + validateRelativeKey(relativeKey);
        CountingDigestInputStream digestInput = new CountingDigestInputStream(input);
        try {
            objectStore.put(properties.getBucket(), objectKey, digestInput);
            return new StoredRecoveryObject(properties.getBucket(), objectKey,
                    digestInput.byteCount(), digestInput.hexDigest());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write recovery object", e);
        }
    }

    public boolean verify(StoredRecoveryObject expected) {
        try (InputStream input = objectStore.get(expected.bucket(), expected.objectKey())) {
            CountingDigestInputStream digestInput = new CountingDigestInputStream(input);
            digestInput.transferTo(OutputStreamSink.INSTANCE);
            return digestInput.byteCount() == expected.byteSize()
                    && digestInput.hexDigest().equals(expected.sha256());
        } catch (Exception e) {
            return false;
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

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int value) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    }
}
