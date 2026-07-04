package com.dupi.rag.service;

import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.dto.IngestJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IngestJobProducerTest {

    @Test
    void enqueueSerializesJobWithKnowledgeBaseSettings() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        RedisQueueProperties props = new RedisQueueProperties();
        props.setIngestQueue("jobs");
        ObjectMapper mapper = new ObjectMapper();
        IngestJobProducer producer = new IngestJobProducer(redis, props, mapper);
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(UUID.randomUUID()).docId(UUID.randomUUID()).build();
        KnowledgeBase kb = KnowledgeBase.builder()
                .chunkSize(300)
                .chunkOverlap(30)
                .chunkStrategy(ChunkStrategy.MARKDOWN)
                .embeddingModel("embed")
                .embeddingDimension(99)
                .build();

        producer.enqueue(job, kb, "obj", "file.md", "text/markdown");

        verify(listOps).leftPush(eq("jobs"), argThat(payload -> {
            try {
                IngestJobMessage msg = mapper.readValue(payload, IngestJobMessage.class);
                return msg.getJobId().equals(job.getId().toString())
                        && msg.getChunkSize() == 300
                        && msg.getChunkStrategy().equals("markdown")
                        && msg.getEmbeddingDimension() == 99;
            } catch (Exception e) {
                return false;
            }
        }));
    }
}
