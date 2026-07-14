package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "retrieval_profiles")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievalProfile {
    @Id
    private UUID id;
    @Column(name = "kb_id", nullable = false)
    private UUID kbId;
    @Column(nullable = false, length = 128)
    private String name;
    @Column(nullable = false)
    private Integer version;
    @Column(name = "vector_candidate_count", nullable = false)
    private Integer vectorCandidateCount;
    @Column(name = "sparse_candidate_count", nullable = false)
    private Integer sparseCandidateCount;
    @Column(name = "rrf_constant", nullable = false)
    private Integer rrfConstant;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sparse_index_params", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> sparseIndexParams = Map.of();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sparse_search_params", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> sparseSearchParams = Map.of();
    @Column(name = "rerank_enabled", nullable = false)
    @Builder.Default
    private Boolean rerankEnabled = false;
    @Column(name = "rerank_candidate_limit", nullable = false)
    private Integer rerankCandidateLimit;
    @Column(name = "final_top_k", nullable = false)
    private Integer finalTopK;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        freezeParameters();
    }

    @PostLoad
    @PostPersist
    void freezeParameters() {
        sparseIndexParams = immutableMap(sparseIndexParams);
        sparseSearchParams = immutableMap(sparseSearchParams);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("profileId", id.toString());
        snapshot.put("name", name);
        snapshot.put("version", version);
        snapshot.put("vectorCandidateCount", vectorCandidateCount);
        snapshot.put("sparseCandidateCount", sparseCandidateCount);
        snapshot.put("rrfConstant", rrfConstant);
        snapshot.put("sparseIndexParams", sparseIndexParams);
        snapshot.put("sparseSearchParams", sparseSearchParams);
        snapshot.put("rerankEnabled", Boolean.TRUE.equals(rerankEnabled));
        snapshot.put("rerankCandidateLimit", rerankCandidateLimit);
        snapshot.put("finalTopK", finalTopK);
        return Map.copyOf(snapshot);
    }

    private Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Map.copyOf(copy);
    }

    private Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutableValue(item)));
            return Map.copyOf(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(this::immutableValue).toList();
        }
        return value;
    }
}
