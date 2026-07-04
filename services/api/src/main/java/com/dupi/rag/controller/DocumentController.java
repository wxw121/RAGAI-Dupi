package com.dupi.rag.controller;

import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.service.DocumentService;
import com.dupi.rag.service.IngestJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final IngestJobService ingestJobService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(@PathVariable UUID kbId, @RequestParam("file") MultipartFile file) {
        return documentService.upload(kbId, file);
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
}
