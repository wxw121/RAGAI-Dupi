package com.dupi.rag.service;

import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.dto.IngestJobMessage;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        ProfileIndexStateService profileIndexStateService = mock(ProfileIndexStateService.class);
        SparseMigrationRepository sparseMigrationRepository = mock(SparseMigrationRepository.class);
        RetrievalProfileRepository retrievalProfileRepository = mock(RetrievalProfileRepository.class);
        IngestJobProducer producer = new IngestJobProducer(
                redis, props, mapper, profileIndexStateService,
                sparseMigrationRepository, retrievalProfileRepository);
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(UUID.randomUUID()).docId(UUID.randomUUID()).build();
        when(profileIndexStateService.isV2Activated(job.getKbId())).thenReturn(false);
        UUID activeProfileId = UUID.randomUUID();
        when(sparseMigrationRepository.findTopByKbIdAndStateInOrderByCreatedAtDesc(eq(job.getKbId()), anyList()))
                .thenReturn(Optional.empty());
        when(retrievalProfileRepository.findByIdAndKbId(activeProfileId, job.getKbId()))
                .thenReturn(Optional.of(RetrievalProfile.builder().version(7).build()));
        KnowledgeBase kb = KnowledgeBase.builder()
                .chunkSize(300)
                .chunkOverlap(30)
                .chunkStrategy(ChunkStrategy.MARKDOWN)
                .retrievalProfile(com.dupi.rag.domain.enums.RetrievalProfile.COMBINED)
                .activeRetrievalProfileId(activeProfileId)
                .embeddingModel("embed")
                .embeddingDimension(99)
                .build();

        producer.enqueue(job, kb, "obj", "file.md", "text/markdown");

        verify(listOps).leftPush(eq("jobs"), argThat(payload -> {
            try {
                IngestJobMessage msg = mapper.readValue(payload, IngestJobMessage.class);
                return mapper.readTree(payload).hasNonNull("executionId")
                        && msg.getJobId().equals(job.getId().toString())
                        && msg.getChunkSize() == 300
                        && msg.getChunkStrategy().equals("markdown")
                        && msg.getRetrievalProfile().equals("combined")
                        && msg.getEmbeddingDimension() == 99
                        && msg.getSparseProfileVersion() == 7
                        && msg.getLegacyWriteRequired()
                        && msg.getIndexSchemaVersion() == 2;
            } catch (Exception e) {
                return false;
            }
        }));
    }

    @Test
    void enqueueRejectsWhenIngestQueueIsOverHighWatermark() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.size("jobs")).thenReturn(10L);
        RedisQueueProperties props = new RedisQueueProperties();
        props.setIngestQueue("jobs");
        props.setMaxPendingJobs(10);
        IngestJobProducer producer = new IngestJobProducer(
                redis,
                props,
                new ObjectMapper(),
                mock(ProfileIndexStateService.class),
                mock(SparseMigrationRepository.class),
                mock(RetrievalProfileRepository.class)
        );
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(UUID.randomUUID()).docId(UUID.randomUUID()).build();
        KnowledgeBase kb = KnowledgeBase.builder()
                .chunkSize(300)
                .chunkOverlap(30)
                .chunkStrategy(ChunkStrategy.MARKDOWN)
                .embeddingModel("embed")
                .embeddingDimension(99)
                .build();

        assertThatThrownBy(() -> producer.enqueue(job, kb, "obj", "file.md", "text/markdown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingest queue is full");

        verify(listOps, never()).leftPush(anyString(), anyString());
    }
}
