package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.dto.DocumentIndexDetailResponse;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.IngestJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentIndexInspectionService {

    private static final int MAX_PREVIEW_CHARS = 240;
    private static final int MAX_CHUNK_PREVIEWS = 20;

    private final DocumentService documentService;
    private final IngestJobService ingestJobService;
    private final IngestJobRepository ingestJobRepository;
    private final ChunkRepository chunkRepository;
    private final MinioStorageService minioStorageService;

    @Transactional(readOnly = true)
    public DocumentIndexDetailResponse inspect(UUID kbId, UUID docId) {
        Document doc = documentService.findOrThrow(kbId, docId);
        IngestJob latestJob = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId).orElse(null);
        IngestJobResponse latestJobResponse = latestJob == null ? null : ingestJobService.toResponse(latestJob, doc);
        long chunkCount = chunkRepository.countByDocId(docId);
        List<Chunk> chunks = chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(docId);
        boolean objectAvailable = objectAvailable(doc.getObjectKey());
        boolean indexReady = doc.getStatus() == DocumentStatus.COMPLETED
                && latestJob != null
                && latestJob.getStatus() == IngestJobStatus.COMPLETED
                && chunkCount > 0;

        return DocumentIndexDetailResponse.builder()
                .document(toDocumentResponse(doc))
                .latestJob(latestJobResponse)
                .objectKey(doc.getObjectKey())
                .objectAvailable(objectAvailable)
                .indexReady(indexReady)
                .chunkCount(Math.toIntExact(chunkCount))
                .chunks(chunks.stream().limit(MAX_CHUNK_PREVIEWS).map(this::toChunkPreview).toList())
                .build();
    }

    private boolean objectAvailable(String objectKey) {
        try (var ignored = minioStorageService.download(objectKey)) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private DocumentIndexDetailResponse.ChunkPreview toChunkPreview(Chunk chunk) {
        return DocumentIndexDetailResponse.ChunkPreview.builder()
                .id(chunk.getId())
                .chunkIndex(chunk.getChunkIndex())
                .contentPreview(truncate(chunk.getContent()))
                .tokenCount(chunk.getTokenCount())
                .metadata(chunk.getMetadata() == null ? Map.of() : chunk.getMetadata())
                .milvusId(chunk.getMilvusId())
                .build();
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_PREVIEW_CHARS ? content : content.substring(0, MAX_PREVIEW_CHARS) + "...";
    }

    private DocumentResponse toDocumentResponse(Document doc) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .fileName(doc.getFileName())
                .mimeType(doc.getMimeType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
