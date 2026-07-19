package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryManifestServiceTest {
    private final RecoveryManifestService service = new RecoveryManifestService(new ObjectMapper().findAndRegisterModules());

    @Test
    void sealOrdersItemsAndProducesStableChecksum() {
        RecoveryManifestHeader header = new RecoveryManifestHeader(
                1, UUID.fromString("11111111-1111-1111-1111-111111111111"), "default",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                Instant.parse("2026-07-15T12:00:00Z"), "embedding-2", 1024,
                Map.of("metric", "COSINE"));
        RecoveryManifestItem itemA = new RecoveryManifestItem("item-a", "RECORD", "records/a.ndjson", 4, "aaaa");
        RecoveryManifestItem itemB = new RecoveryManifestItem("item-b", "OBJECT", "objects/b", 8, "bbbb");

        RecoveryManifest first = service.seal(header, List.of(itemB, itemA));
        RecoveryManifest second = service.seal(header, List.of(itemA, itemB));

        assertThat(first.items()).extracting(RecoveryManifestItem::itemKey)
                .containsExactly("item-a", "item-b");
        assertThat(first.manifestChecksum()).isEqualTo(second.manifestChecksum());
        assertThat(first.itemCount()).isEqualTo(2);
        assertThat(first.totalBytes()).isEqualTo(12);
    }

    @Test
    void validateRejectsUnsupportedSchemaAndDuplicateKeys() {
        RecoveryManifestHeader unsupported = new RecoveryManifestHeader(
                2, UUID.randomUUID(), "default", UUID.randomUUID(), Instant.now(), "model", 8, Map.of());
        RecoveryManifestHeader supported = new RecoveryManifestHeader(
                1, UUID.randomUUID(), "default", UUID.randomUUID(), Instant.now(), "model", 8, Map.of());
        RecoveryManifestItem duplicate = new RecoveryManifestItem("same", "RECORD", "a", 1, "aa");

        assertThatThrownBy(() -> service.seal(unsupported, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");
        assertThatThrownBy(() -> service.seal(supported, List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void serializedManifestParsesAndDetectsTampering() {
        RecoveryManifestHeader header = new RecoveryManifestHeader(
                1, UUID.randomUUID(), "default", UUID.randomUUID(), Instant.now(), "model", 8, Map.of());
        RecoveryManifest sealed = service.seal(header,
                List.of(new RecoveryManifestItem("a", "RECORD", "a", 1, "sha")));

        assertThat(service.parseAndValidate(service.serialize(sealed))).isEqualTo(sealed);
        RecoveryManifest tampered = new RecoveryManifest(sealed.header(), sealed.itemCount(), 99,
                sealed.items(), sealed.manifestChecksum());
        assertThatThrownBy(() -> service.parseAndValidate(service.serialize(tampered)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mismatch");
    }

    @Test
    void rejectsInvalidJsonAndIncompleteHeaderOrItem() {
        RecoveryManifestHeader incomplete = new RecoveryManifestHeader(
                1, UUID.randomUUID(), " ", UUID.randomUUID(), Instant.now(), "model", 8, Map.of());
        RecoveryManifestHeader valid = new RecoveryManifestHeader(
                1, UUID.randomUUID(), "default", UUID.randomUUID(), Instant.now(), "model", 8, Map.of());
        RecoveryManifestItem incompleteItem = new RecoveryManifestItem("", "RECORD", "a", 1, "sha");

        assertThatThrownBy(() -> service.parseAndValidate("not-json".getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> service.seal(incomplete, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> service.seal(valid, List.of(incompleteItem)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("item");
    }
}
