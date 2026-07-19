package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.dto.BatchDocumentUploadResponse;
import com.dupi.rag.dto.BatchDocumentUploadResult;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final IngestJobRepository ingestJobRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MinioStorageService minioStorageService;
    private final MilvusVectorService milvusVectorService;
    private final IngestJobProducer ingestJobProducer;
    private final IngestOutboxService ingestOutboxService;
    private final DocumentTombstoneService documentTombstoneService;
    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final AuditLogService auditLogService;
    private final RetrievalProfileRepository retrievalProfileRepository;
    private final KnowledgeBaseMaintenanceService maintenanceService;
    private final UploadQuotaService uploadQuotaService;
    private final ProfileIndexStateService profileIndexStateService;

    public DocumentResponse upload(UUID kbId, MultipartFile file) {
        return upload(kbId, file, null);
    }

    public DocumentResponse upload(UUID kbId, MultipartFile file, String idempotencyKey) {
        maintenanceService.assertMutationAllowed(kbId);
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        return upload(kb, kbId, file, idempotencyKey);
    }

    public BatchDocumentUploadResponse uploadBatch(UUID kbId, List<MultipartFile> files) {
        maintenanceService.assertMutationAllowed(kbId);
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Files are empty");
        }

        List<BatchDocumentUploadResult> results = files.stream()
                .map(file -> uploadOneForBatch(kb, kbId, file))
                .toList();
        int succeeded = (int) results.stream().filter(BatchDocumentUploadResult::isSuccess).count();
        return BatchDocumentUploadResponse.builder()
                .total(results.size())
                .succeeded(succeeded)
                .failed(results.size() - succeeded)
                .results(results)
                .build();
    }

    private BatchDocumentUploadResult uploadOneForBatch(KnowledgeBase kb, UUID kbId, MultipartFile file) {
        String fileName = file != null && file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        try {
            return BatchDocumentUploadResult.builder()
                    .fileName(fileName)
                    .success(true)
                    .document(upload(kb, kbId, file, null))
                    .build();
        } catch (Exception e) {
            return BatchDocumentUploadResult.builder()
                    .fileName(fileName)
                    .success(false)
                    .errorMessage(e.getMessage() != null ? e.getMessage() : "Upload failed")
                    .build();
        }
    }

    private DocumentResponse upload(KnowledgeBase kb, UUID kbId, MultipartFile file, String idempotencyKey) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        ingestJobProducer.assertQueueAccepting();

        UUID docId = UUID.randomUUID();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String fingerprint = fileFingerprint(file);
        UploadQuotaReservation reservation = uploadQuotaService.reserveForUpload(
                kbId, docId, idempotencyKey, fileName, mimeType, file.getSize(), fingerprint);
        if (reservation.getStatus() == UploadQuotaReservationStatus.COMMITTED && reservation.getDocId() != null) {
            return replayCommittedUpload(kbId, reservation.getDocId());
        }

        String objectKey = kbId + "/" + docId + "/" + fileName;
        Instant now = Instant.now();
        Document doc = Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName(fileName)
                .objectKey(objectKey)
                .mimeType(mimeType)
                .fileSize(file.getSize())
                .quotaReservationId(reservation.getId())
                .status(DocumentStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        boolean objectUploaded = false;
        IngestJob job = null;
        boolean jobSaved = false;
        try {
            documentRepository.save(doc);
            profileIndexStateService.bumpRevision(kb);
            uploadQuotaService.refreshAttemptLease(reservation);
            minioStorageService.upload(objectKey, file.getInputStream(), file.getSize(), doc.getMimeType());
            objectUploaded = true;
            uploadQuotaService.refreshAttemptLease(reservation);

            job = IngestJob.builder()
                    .id(UUID.randomUUID())
                    .kbId(kbId)
                    .docId(doc.getId())
                    .status(IngestJobStatus.PENDING)
                    .stage(IngestStage.QUEUED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            ingestJobRepository.save(job);
            jobSaved = true;
            uploadQuotaService.refreshAttemptLease(reservation);

            ingestOutboxService.record(job, kb, objectKey, doc.getFileName(), doc.getMimeType());
            uploadQuotaService.commit(reservation, doc);
            doc.setStatus(DocumentStatus.PENDING);
            doc.setErrorMessage(null);
            documentRepository.save(doc);

            return toResponse(doc, job);
        } catch (Exception e) {
            if (objectUploaded) {
                try {
                    minioStorageService.delete(objectKey);
                } catch (Exception objectCleanupFailure) {
                    if (objectCleanupFailure != e) {
                        e.addSuppressed(objectCleanupFailure);
                    }
                }
            }
            if (jobSaved) {
                try {
                    ingestJobRepository.delete(job);
                } catch (Exception jobCleanupFailure) {
                    if (jobCleanupFailure != e) {
                        e.addSuppressed(jobCleanupFailure);
                    }
                }
            }
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            doc.setQuotaReservationId(null);
            try {
                documentRepository.save(doc);
            } catch (Exception statusFailure) {
                if (statusFailure != e) {
                    e.addSuppressed(statusFailure);
                }
            }
            try {
                uploadQuotaService.release(reservation, "Upload failed");
            } catch (Exception releaseFailure) {
                if (releaseFailure != e) {
                    e.addSuppressed(releaseFailure);
                }
            }
            if (e.getMessage() != null && e.getMessage().contains("database down")) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            throw new IllegalStateException("Upload failed", e);
        }
    }

    String fileFingerprint(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprintPart(digest, file.getOriginalFilename(), "unknown");
            updateFingerprintPart(digest, file.getContentType(), "application/octet-stream");
            updateFingerprintPart(digest, Long.toString(file.getSize()), "0");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint upload", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void updateFingerprintPart(MessageDigest digest, String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private DocumentResponse replayCommittedUpload(UUID kbId, UUID docId) {
        Document doc = findOrThrow(kbId, docId);
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId).orElse(null);
        return toResponse(doc, job);
    }

    public List<DocumentResponse> listByKb(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(doc -> toResponse(
                        doc,
                        ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(doc.getId()).orElse(null)
                ))
                .toList();
    }

    public DocumentResponse get(UUID kbId, UUID docId) {
        Document doc = findOrThrow(kbId, docId);
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId).orElse(null);
        return toResponse(doc, job);
    }

    @Transactional
    public void delete(UUID kbId, UUID docId) {
        maintenanceService.assertMutationAllowed(kbId);
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        Document doc = findOrThrow(kbId, docId);
        documentTombstoneService.recordDeleted(doc);
        vectorCleanupTaskService.enqueueProfileDocument(docId);
        vectorCleanupTaskService.enqueueLegacyDocument(docId);
        try {
            milvusVectorService.deleteProfileByDocId(docId);
        } catch (Exception e) {
            log.warn("Failed to delete profile Milvus vectors for doc {}", docId, e);
        }
        try {
            milvusVectorService.deleteByDocId(docId);
            milvusVectorService.deleteSparseByDocId(kbId, docId,
                    retrievalProfileRepository.findByKbIdOrderByVersionDesc(kbId).stream()
                            .map(profile -> profile.getVersion()).toList());
        } catch (Exception e) {
            log.warn("Failed to delete Milvus vectors for doc {}", docId, e);
        }
        chunkRepository.deleteByDocId(docId);
        try {
            minioStorageService.delete(doc.getObjectKey());
        } catch (Exception e) {
            log.warn("Failed to delete object {} for doc {}", doc.getObjectKey(), docId, e);
        }
        uploadQuotaService.releaseCommitted(doc.getQuotaReservationId(), "Document deleted");
        documentRepository.delete(doc);
        profileIndexStateService.bumpRevision(kb);
        auditLogService.recordSuccess(
                "DOCUMENT_DELETE",
                "DOCUMENT",
                docId,
                "Deleted document " + doc.getFileName()
        );
    }

    public Document findOrThrow(UUID kbId, UUID docId) {
        return documentRepository.findById(docId)
                .filter(d -> d.getKbId().equals(kbId))
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + docId));
    }

    private DocumentResponse toResponse(Document doc) {
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(doc.getId()).orElse(null);
        return toResponse(doc, job);
    }

    private DocumentResponse toResponse(Document doc, IngestJob job) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .fileName(doc.getFileName())
                .mimeType(doc.getMimeType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .currentJob(job == null ? null : toJobResponse(job))
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }

    private IngestJobResponse toJobResponse(IngestJob job) {
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
