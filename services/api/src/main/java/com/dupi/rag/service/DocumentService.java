package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final IngestJobRepository ingestJobRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MinioStorageService minioStorageService;
    private final MilvusVectorService milvusVectorService;
    private final IngestJobProducer ingestJobProducer;

    @Transactional
    public DocumentResponse upload(UUID kbId, MultipartFile file) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        UUID docId = UUID.randomUUID();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String objectKey = kbId + "/" + docId + "/" + fileName;
        Instant now = Instant.now();

        Document doc = Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName(fileName)
                .objectKey(objectKey)
                .mimeType(mimeType)
                .fileSize(file.getSize())
                .status(DocumentStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        documentRepository.save(doc);

        try {
            minioStorageService.upload(objectKey, file.getInputStream(), file.getSize(), doc.getMimeType());
        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            throw new IllegalStateException("Upload failed", e);
        }

        IngestJob job = IngestJob.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .docId(doc.getId())
                .status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ingestJobRepository.save(job);

        ingestJobProducer.enqueue(job, kb, objectKey, doc.getFileName(), doc.getMimeType());

        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        return toResponse(doc);
    }

    public List<DocumentResponse> listByKb(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    public DocumentResponse get(UUID kbId, UUID docId) {
        Document doc = findOrThrow(kbId, docId);
        return toResponse(doc);
    }

    @Transactional
    public void delete(UUID kbId, UUID docId) {
        Document doc = findOrThrow(kbId, docId);
        milvusVectorService.deleteByDocId(docId);
        chunkRepository.deleteByDocId(docId);
        minioStorageService.delete(doc.getObjectKey());
        documentRepository.delete(doc);
    }

    public Document findOrThrow(UUID kbId, UUID docId) {
        return documentRepository.findById(docId)
                .filter(d -> d.getKbId().equals(kbId))
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + docId));
    }

    private DocumentResponse toResponse(Document doc) {
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
