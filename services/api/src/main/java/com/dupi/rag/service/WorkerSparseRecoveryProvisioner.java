package com.dupi.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WorkerSparseRecoveryProvisioner implements SparseRecoveryProvisioner {
    private final WebClient.Builder webClientBuilder;

    @Value("${dupi.worker.base-url:http://localhost:8000}")
    private String workerBaseUrl;

    public WorkerSparseRecoveryProvisioner(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public void ensure(UUID knowledgeBaseId, int embeddingDimension, int profileVersion,
                       Map<String, Object> indexParameters) {
        Map<String, Object> request = Map.of(
                "kb_id", knowledgeBaseId.toString(),
                "profile_version", profileVersion,
                "embedding_dimension", embeddingDimension,
                "sparse_index_params", indexParameters == null ? Map.of() : indexParameters,
                "chunks", List.of());
        webClientBuilder.build().post()
                .uri(workerBaseUrl + "/api/v1/retrieve/sparse/backfill")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
}
