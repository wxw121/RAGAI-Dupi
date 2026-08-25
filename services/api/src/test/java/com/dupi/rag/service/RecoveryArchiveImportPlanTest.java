package com.dupi.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryArchiveImportPlanTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void jsonRoundTripPreservesNullableRevisionNestedSettingsLongSizesAndStableEntryOrder() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("metric", "COSINE");
        nested.put("index", Map.of("m", 32, "efConstruction", 200));
        RecoveryArchiveImportPlan withoutRevision = plan(null, nested);
        RecoveryArchiveImportPlan withRevision = plan(Instant.parse("2026-08-17T03:04:05Z"), nested);

        assertThat(roundTrip(withoutRevision)).isEqualTo(withoutRevision);
        assertThat(roundTrip(withRevision)).isEqualTo(withRevision);
        assertThat(withoutRevision.toInput()).doesNotContainKey("sourceRevision");
        assertThat(roundTrip(withoutRevision).entries())
                .extracting(RecoveryArchiveImportPlan.Entry::relativePath)
                .containsExactly("records/a.json", "vectors/z.ndjson");
        assertThat(roundTrip(withoutRevision).entries().get(1).byteSize())
                .isEqualTo(5_000_000_000L);
    }

    private RecoveryArchiveImportPlan roundTrip(RecoveryArchiveImportPlan plan) throws Exception {
        byte[] json = mapper.writeValueAsBytes(plan.toInput());
        Map<String, Object> decoded = mapper.readValue(json, new TypeReference<>() { });
        return RecoveryArchiveImportPlan.fromInput(decoded);
    }

    private RecoveryArchiveImportPlan plan(Instant revision, Map<String, Object> settings) {
        return new RecoveryArchiveImportPlan(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "tenant-a",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                revision,
                "embedding-v1",
                1536,
                settings,
                "a".repeat(64),
                "b".repeat(64),
                "admin",
                List.of(
                        new RecoveryArchiveImportPlan.Entry("record:a", "RECORD", "records/a.json", 7L, "c".repeat(64)),
                        new RecoveryArchiveImportPlan.Entry("vector:z", "VECTOR", "vectors/z.ndjson", 5_000_000_000L, "d".repeat(64))));
    }
}
