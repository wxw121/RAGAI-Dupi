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
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void inspectionDistinguishesAbsentMatchingAndConflictingObjects() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        StoredRecoveryObject expected = new StoredRecoveryObject("dupi-recovery", "planned", 7,
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");

        assertThat(storage.inspect(expected).outcome()).isEqualTo(RecoveryStorageOutcome.ABSENT);
        objectStore.objects.put("planned", "payload".getBytes(StandardCharsets.UTF_8));
        RecoveryStorageInspection matching = storage.inspect(expected);
        assertThat(matching.outcome()).isEqualTo(RecoveryStorageOutcome.MATCHING);
        assertThat(matching.object().versionToken()).isEqualTo("version-1");
        objectStore.objects.put("planned", "changed".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.inspect(expected).outcome()).isEqualTo(RecoveryStorageOutcome.CONFLICT);
    }

    @Test
    void inspectionRejectsAChangedObjectVersionEvenWhenBytesStillMatch() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        objectStore.objects.put("planned", "payload".getBytes(StandardCharsets.UTF_8));
        StoredRecoveryObject expected = new StoredRecoveryObject("dupi-recovery", "planned", 7,
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5", "version-1");

        objectStore.version = "version-2";

        assertThat(storage.inspect(expected).outcome()).isEqualTo(RecoveryStorageOutcome.STALE_VERSION);
    }

    @Test
    void stagingPutNeverOverwritesAConflictingObject() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        objectStore.objects.put("recovery-staging/hash/job.zip", "other".getBytes(StandardCharsets.UTF_8));
        StoredRecoveryObject expected = new StoredRecoveryObject("dupi-recovery",
                "recovery-staging/hash/job.zip", 7,
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");

        assertThatThrownBy(() -> storage.putStaging(expected,
                new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(RecoveryStorageConflictException.class);
        assertThat(objectStore.objects.get(expected.objectKey())).isEqualTo("other".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deleteMissingSucceedsButDeleteOutageIsRetryable() {
        InMemoryRecoveryObjectStore objectStore = new InMemoryRecoveryObjectStore();
        RecoveryStorageService storage = service(objectStore);
        assertThatCode(() -> storage.delete("missing")).doesNotThrowAnyException();

        objectStore.deleteFailure = new Exception("timeout");
        assertThatThrownBy(() -> storage.delete("planned"))
                .isInstanceOf(RecoveryStorageUnavailableException.class)
                .hasMessageContaining("delete");
    }

    @Test
    void inspectionOutageIsNotReportedAsAbsentOrConflict() {
        RecoveryObjectStore failing = new RecoveryObjectStore() {
            @Override public void put(String bucket, String key, InputStream input) { }
            @Override public String version(String bucket, String key) throws Exception { throw new Exception("auth denied"); }
            @Override public InputStream get(String bucket, String key) throws Exception { throw new Exception("auth denied"); }
            @Override public List<String> list(String bucket, String prefix) { return List.of(); }
            @Override public void delete(String bucket, String key) { }
        };

        assertThatThrownBy(() -> service(failing).inspect(new StoredRecoveryObject("dupi-recovery", "planned", 0,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")))
                .isInstanceOf(RecoveryStorageUnavailableException.class)
                .hasMessageContaining("inspect");
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
            @Override public String version(String bucket, String key) throws Exception {
                throw new Exception("stat failed");
            }
            @Override public List<String> list(String bucket, String prefix) throws Exception {
                throw new Exception("list failed");
            }
            @Override public void delete(String bucket, String key) { }
        };
        RecoveryStorageService storage = service(failingStore);

        assertThatThrownBy(() -> storage.put("tenant", UUID.randomUUID(), "a", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("write");
        assertThatThrownBy(() -> storage.verify(new StoredRecoveryObject("b", "k", 0, "sha")))
                .isInstanceOf(RecoveryStorageUnavailableException.class);
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
        private Exception deleteFailure;
        private String version = "version-1";

        @Override
        public void put(String bucket, String key, InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            objects.put(key, output.toByteArray());
        }

        @Override
        public InputStream get(String bucket, String key) throws Exception {
            byte[] value = objects.get(key);
            if (value == null) throw new RecoveryObjectNotFoundException(bucket, key);
            return new ByteArrayInputStream(value);
        }

        @Override
        public List<String> list(String bucket, String prefix) {
            return objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }

        @Override
        public void delete(String bucket, String key) throws Exception {
            if (deleteFailure != null) throw deleteFailure;
            objects.remove(key);
        }

        @Override
        public String version(String bucket, String key) throws Exception {
            if (!objects.containsKey(key)) throw new RecoveryObjectNotFoundException(bucket, key);
            return version;
        }
    }
}
