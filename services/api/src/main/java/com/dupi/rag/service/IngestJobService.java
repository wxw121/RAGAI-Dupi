package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestJobService {

    private final IngestJobRepository ingestJobRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestJobProducer ingestJobProducer;

    public IngestJobResponse getLatestByDoc(UUID docId) {
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found for doc: " + docId));
        return toResponse(job);
    }

    public List<IngestJobResponse> listByKb(UUID kbId) {
        return ingestJobRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void handleStatusUpdate(IngestStatusUpdate update) {
        UUID jobId = UUID.fromString(update.getJobId());
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));

        Document doc = documentRepository.findById(UUID.fromString(update.getDocId()))
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if (update.getStage() != null) {
            job.setStage(IngestStage.valueOf(update.getStage().toUpperCase()));
        }
        if (update.getStatus() != null) {
            job.setStatus(IngestJobStatus.valueOf(update.getStatus().toUpperCase()));
        }
        job.setErrorMessage(update.getErrorMessage());

        if (update.getChunks() != null && !update.getChunks().isEmpty()) {
            chunkRepository.deleteByDocId(doc.getId());
            for (IngestStatusUpdate.ChunkPayload payload : update.getChunks()) {
                Chunk chunk = Chunk.builder()
                        .id(UUID.fromString(payload.getId()))
                        .kbId(doc.getKbId())
                        .docId(doc.getId())
                        .chunkIndex(payload.getChunkIndex())
                        .content(payload.getContent())
                        .tokenCount(payload.getTokenCount())
                        .metadata(payload.getMetadata())
                        .milvusId(payload.getMilvusId())
                        .build();
                chunkRepository.save(chunk);
            }
        }

        if ("COMPLETED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setErrorMessage(null);
            job.setStage(IngestStage.COMPLETED);
        } else if ("FAILED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(update.getErrorMessage());
            job.setStage(IngestStage.FAILED);
        } else {
            doc.setStatus(DocumentStatus.PROCESSING);
        }

        documentRepository.save(doc);
        ingestJobRepository.save(job);
    }

    @Transactional
    public IngestJobResponse retry(UUID jobId) {
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
        if (job.getRetryCount() >= 3) {
            throw new IllegalStateException("Max retries exceeded");
        }
        job.setRetryCount(job.getRetryCount() + 1);
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage(null);
        ingestJobRepository.save(job);

        Document doc = documentRepository.findById(job.getDocId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(job.getKbId());
        doc.setStatus(com.dupi.rag.domain.enums.DocumentStatus.PROCESSING);
        documentRepository.save(doc);
        ingestJobProducer.enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());

        return toResponse(job);
    }

    private IngestJobResponse toResponse(IngestJob job) {
        return IngestJobResponse.builder()
                .id(job.getId())
                .kbId(job.getKbId())
                .docId(job.getDocId())
                .status(job.getStatus())
                .stage(job.getStage())
                .retryCount(job.getRetryCount())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
