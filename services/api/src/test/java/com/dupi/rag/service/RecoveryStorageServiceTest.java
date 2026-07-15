package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryStorageServiceTest {

    @Test
    void putStreamsBytesAndReturnsDigestEvidence() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);

        StoredRecoveryObject stored = storage.put(
                "default",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "objects/doc-1/report.pdf",
                new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(stored.objectKey()).isEqualTo(
                "archives/default/11111111-1111-1111-1111-111111111111/objects/doc-1/report.pdf");
        assertThat(stored.byteSize()).isEqualTo(7);
        assertThat(stored.sha256()).isEqualTo(
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");
        assertThat(storage.verify(stored)).isTrue();
    }

    @Test
    void verifyRejectsChangedRemoteBytes() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        UUID archiveId = UUID.randomUUID();
        StoredRecoveryObject stored = storage.put(
                "tenant-a", archiveId, "manifest.json",
                new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));
        objectStore.objects.put(stored.objectKey(), "changed".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.verify(stored)).isFalse();
    }

    @Test
    void rejectsKeysThatEscapeArchivePrefix() {
        RecoveryStorageService storage = service(new InMemoryRecoveryObjectStore());

        assertThatThrownBy(() -> storage.put(
                "default", UUID.randomUUID(), "../other/manifest.json",
                new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative archive key");
    }

    @Test
    void deletingPrefixTwiceIsIdempotent() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        UUID archiveId = UUID.randomUUID();
        storage.put("default", archiveId, "a", new ByteArrayInputStream(new byte[]{1}));
        storage.put("default", archiveId, "b", new ByteArrayInputStream(new byte[]{2}));

        storage.deleteArchive("default", archiveId);
        storage.deleteArchive("default", archiveId);

        assertThat(objectStore.objects).isEmpty();
    }

    @Test
    void readsSmallObjectsAndStreamsArchiveZip() throws Exception {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        UUID archiveId = UUID.randomUUID();
        StoredRecoveryObject first = storage.put("default", archiveId, "records/a.json",
                new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)));
        storage.put("default", archiveId, "manifest.json",
                new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)));

        assertThat(storage.readSmall(first.bucket(), first.objectKey(), 10)).isEqualTo("one".getBytes());
        assertThat(storage.open(first.bucket(), first.objectKey()).readAllBytes()).isEqualTo("one".getBytes());
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        storage.streamZip("default", archiveId, zipBytes);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes.toByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("manifest.json");
            assertThat(zip.readAllBytes()).isEqualTo("two".getBytes());
            assertThat(zip.getNextEntry().getName()).isEqualTo("records/a.json");
        }
    }

    @Test
    void smallReadRejectsOversizedObject() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        StoredRecoveryObject stored = storage.put("default", UUID.randomUUID(), "large",
                new ByteArrayInputStream(new byte[20]));

        assertThatThrownBy(() -> storage.readSmall(stored.bucket(), stored.objectKey(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void validatesTenantAndReadLimitAndWrapsStoreFailures() throws Exception {
        RecoveryObjectStore failingStore = new RecoveryObjectStore() {
            @Override public void put(String bucket, String key, InputStream input) throws Exception {
                throw new Exception("write failed");
            }
            @Override public InputStream get(String bucket, String key) throws Exception {
                throw new Exception("read failed");
            }
            @Override public List<String> list(String bucket, String prefix) throws Exception {
                throw new Exception("list failed");
            }
            @Override public void delete(String bucket, String key) { }
        };
        RecoveryStorageService storage = service(failingStore);

        assertThatThrownBy(() -> storage.put("tenant", UUID.randomUUID(), "a", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("write");
        assertThat(storage.verify(new StoredRecoveryObject("b", "k", 0, "sha"))).isFalse();
        assertThatThrownBy(() -> storage.open("b", "k"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("open");
        assertThatThrownBy(() -> storage.readSmall("b", "k", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read");
        assertThatThrownBy(() -> storage.readSmall("b", "k", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> storage.deleteArchive("tenant", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("delete");
        assertThatThrownBy(() -> storage.streamZip("tenant", UUID.randomUUID(), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ZIP");
        assertThatThrownBy(() -> storage.put("bad/tenant", UUID.randomUUID(), "a", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant");
    }

    private RecoveryStorageService service(RecoveryObjectStore objectStore) {
        RecoveryProperties properties = new RecoveryProperties();
        properties.setBucket("dupi-recovery");
        return new RecoveryStorageService(properties, objectStore);
    }

    private static final class InMemoryRecoveryObjectStore implements RecoveryObjectStore {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public void put(String bucket, String key, InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            objects.put(key, output.toByteArray());
        }

        @Override
        public InputStream get(String bucket, String key) {
            return new ByteArrayInputStream(objects.get(key));
        }

        @Override
        public List<String> list(String bucket, String prefix) {
            return objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(key);
        }
    }
}
