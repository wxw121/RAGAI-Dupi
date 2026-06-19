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

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestJobProducer {

    private final StringRedisTemplate redisTemplate;
    private final RedisQueueProperties queueProperties;
    private final ObjectMapper objectMapper;

    public void enqueue(IngestJob job, KnowledgeBase kb, String objectKey, String fileName, String mimeType) {
        try {
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
                    .build();
            String payload = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().leftPush(queueProperties.getIngestQueue(), payload);
            log.info("Enqueued ingest job {}", job.getId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue ingest job", e);
        }
    }
}
