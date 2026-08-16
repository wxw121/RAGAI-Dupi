package com.dupi.rag.controller;

import com.dupi.rag.dto.BatchDocumentUploadResponse;
import com.dupi.rag.dto.DocumentIndexDetailResponse;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.dto.MarkdownPackageUploadResponse;
import com.dupi.rag.service.DocumentAssetService;
import com.dupi.rag.service.DocumentService;
import com.dupi.rag.service.DocumentIndexInspectionService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.MarkdownPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final IngestJobService ingestJobService;
    private final DocumentIndexInspectionService documentIndexInspectionService;
    private final MarkdownPackageService markdownPackageService;
    private final DocumentAssetService documentAssetService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return documentService.upload(kbId, file, idempotencyKey);
    }

    public DocumentResponse upload(UUID kbId, MultipartFile file) {
        return upload(kbId, file, null);
    }

    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchDocumentUploadResponse uploadBatch(@PathVariable UUID kbId, @RequestParam("files") List<MultipartFile> files) {
        return documentService.uploadBatch(kbId, files);
    }

    @PostMapping(value = "/markdown-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MarkdownPackageUploadResponse uploadMarkdownPackage(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file
    ) {
        return markdownPackageService.upload(kbId, file);
    }

    @GetMapping("/{docId}/assets")
    public ResponseEntity<StreamingResponseBody> getAsset(
            @PathVariable UUID kbId,
            @PathVariable UUID docId,
            @RequestParam("path") String path
    ) {
        documentService.findOrThrow(kbId, docId);
        DocumentAssetService.AssetDownload download = documentAssetService.download(kbId, docId, path);
        StreamingResponseBody body = output -> {
            try (var input = download.input()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(body);
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable UUID kbId) {
        return documentService.listByKb(kbId);
    }

    @GetMapping("/{docId}")
    public DocumentResponse get(@PathVariable UUID kbId, @PathVariable UUID docId) {
        return documentService.get(kbId, docId);
    }

    @DeleteMapping("/{docId}")
    public void delete(@PathVariable UUID kbId, @PathVariable UUID docId) {
        documentService.delete(kbId, docId);
    }

    @GetMapping("/{docId}/ingest-job")
    public IngestJobResponse getIngestJob(@PathVariable UUID kbId, @PathVariable UUID docId) {
        documentService.findOrThrow(kbId, docId);
        return ingestJobService.getLatestByDoc(docId);
    }

    @GetMapping("/{docId}/index-detail")
    public DocumentIndexDetailResponse getIndexDetail(@PathVariable UUID kbId, @PathVariable UUID docId) {
        return documentIndexInspectionService.inspect(kbId, docId);
    }
}
