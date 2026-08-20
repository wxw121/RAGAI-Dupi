package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentAssetService {

    private final DocumentAssetRepository repository;
    private final MinioStorageService storageService;
    private final KnowledgeBaseService knowledgeBaseService;

    @Transactional
    public DocumentAsset register(
            Document document,
            String relativePath,
            String assetFileName,
            String mimeType,
            byte[] content
    ) {
        String reference = normalizeReference(relativePath);
        if (reference.isBlank()) {
            throw new IllegalArgumentException("Document asset path is empty");
        }
        UUID assetId = UUID.randomUUID();
        String safeName = safeFileName(assetFileName);
        String objectKey = document.getKbId() + "/" + document.getId() + "/assets/" + assetId + "/" + safeName;
        storageService.upload(objectKey, new ByteArrayInputStream(content), content.length, mimeType);
        DocumentAsset asset = DocumentAsset.builder()
                .id(assetId)
                .kbId(document.getKbId())
                .docId(document.getId())
                .relativePath(reference)
                .objectKey(objectKey)
                .mimeType(mimeType)
                .fileName(safeName)
                .fileSize((long) content.length)
                .createdAt(Instant.now())
                .build();
        try {
            return repository.save(asset);
        } catch (RuntimeException ex) {
            storageService.delete(objectKey);
            throw ex;
        }
    }

    public AssetDownload download(UUID kbId, UUID docId, String relativePath) {
        knowledgeBaseService.findOrThrow(kbId);
        String reference = normalizeReference(relativePath);
        DocumentAsset asset = repository.findByDocIdAndRelativePath(docId, reference)
                .filter(candidate -> kbId.equals(candidate.getKbId()))
                .orElseThrow(() -> new ResourceNotFoundException("Document image not found"));
        return new AssetDownload(
                storageService.download(asset.getObjectKey()),
                asset.getMimeType(),
                asset.getFileName(),
                asset.getFileSize()
        );
    }

    public List<DocumentAsset> list(UUID docId) {
        return repository.findByDocIdOrderByCreatedAtAsc(docId);
    }

    @Transactional
    public void deleteByDocument(UUID docId) {
        List<DocumentAsset> assets = repository.findByDocIdOrderByCreatedAtAsc(docId);
        assets.forEach(asset -> {
            try {
                storageService.delete(asset.getObjectKey());
            } catch (RuntimeException ex) {
                log.warn("Failed to delete document asset {}", asset.getId(), ex);
            }
        });
        repository.deleteByDocId(docId);
    }

    public String normalizeReference(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("<") && normalized.endsWith(">") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String safeFileName(String value) {
        String normalized = value == null ? "image" : value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return name.isBlank() ? "image" : name;
    }

    public record AssetDownload(
            InputStream input,
            String mimeType,
            String fileName,
            long fileSize
    ) {
    }
}
