package com.dupi.rag.service;

import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.IngestJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import com.dupi.rag.domain.enums.SparseMigrationState;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestJobProducer {

    private final StringRedisTemplate redisTemplate;
    private final RedisQueueProperties queueProperties;
    private final ObjectMapper objectMapper;
    private final SparseMigrationRepository sparseMigrationRepository;
    private final RetrievalProfileRepository retrievalProfileRepository;

    public void enqueue(IngestJob job, KnowledgeBase kb, String objectKey, String fileName, String mimeType) {
        assertQueueAccepting();
        try {
            Integer sparseProfileVersion = sparseMigrationRepository
                    .findTopByKbIdAndStateInOrderByCreatedAtDesc(job.getKbId(), List.of(
                                SparseMigrationState.BACKFILLING, SparseMigrationState.DUAL_WRITING,
                                SparseMigrationState.SHADOW_VALIDATING, SparseMigrationState.CUTOVER))
                    .flatMap(migration -> retrievalProfileRepository.findByIdAndKbId(
                                migration.getProfileId(), job.getKbId()))
                    .map(profile -> profile.getVersion()).orElse(null);
            if (sparseProfileVersion == null && kb.getActiveRetrievalProfileId() != null) {
                sparseProfileVersion = retrievalProfileRepository
                        .findByIdAndKbId(kb.getActiveRetrievalProfileId(), job.getKbId())
                        .map(profile -> profile.getVersion()).orElse(null);
            }
            IngestJobMessage message = IngestJobMessage.builder()
                    .jobId(job.getId().toString())
                    .kbId(job.getKbId().toString())
                    .docId(job.getDocId().toString())
                    .objectKey(objectKey)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .chunkSize(kb.getChunkSize())
                    .chunkOverlap(kb.getChunkOverlap())
                    .chunkStrategy(kb.getChunkStrategy().name().toLowerCase())
                    .embeddingModel(kb.getEmbeddingModel())
                    .embeddingDimension(kb.getEmbeddingDimension())
                    .sparseProfileVersion(sparseProfileVersion)
                    .build();
            String payload = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().leftPush(queueProperties.getIngestQueue(), payload);
            log.info("Enqueued ingest job {}", job.getId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue ingest job", e);
        }
    }

    public void assertQueueAccepting() {
        int maxPendingJobs = queueProperties.getMaxPendingJobs();
        if (maxPendingJobs <= 0) {
            return;
        }
        Long currentSize = redisTemplate.opsForList().size(queueProperties.getIngestQueue());
        long pendingJobs = currentSize != null ? currentSize : 0L;
        if (pendingJobs >= maxPendingJobs) {
            throw new IllegalStateException("Ingest queue is full: pending=" + pendingJobs + ", max=" + maxPendingJobs);
        }
    }
}
