package com.dupi.rag.controller;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.dto.recovery.*;
import com.dupi.rag.service.RecoveryArchiveService;
import com.dupi.rag.service.RecoveryJobExecutor;
import com.dupi.rag.service.RecoveryRestoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/recovery")
@RequiredArgsConstructor
public class RecoveryController {
    private final RecoveryArchiveService archives;
    private final RecoveryRestoreService restores;
    private final RecoveryJobExecutor executor;

    @PostMapping("/archives")
    public ResponseEntity<RecoveryArchiveResponse> createArchive(@PathVariable UUID kbId) {
        var archive = archives.create(kbId, SecurityContext.getPrincipal());
        executor.submitArchive(archive.getId());
        return ResponseEntity.accepted().body(RecoveryArchiveResponse.from(archive));
    }

    @GetMapping("/archives")
    public List<RecoveryArchiveResponse> listArchives(@PathVariable UUID kbId) {
        return archives.list(kbId).stream().map(RecoveryArchiveResponse::from).toList();
    }

    @GetMapping("/archives/{archiveId}")
    public RecoveryArchiveResponse getArchive(@PathVariable UUID kbId, @PathVariable UUID archiveId) {
        return RecoveryArchiveResponse.from(archives.get(kbId, archiveId));
    }

    @PostMapping("/archives/{archiveId}/retry")
    public ResponseEntity<RecoveryArchiveResponse> retryArchive(
            @PathVariable UUID kbId, @PathVariable UUID archiveId) {
        var archive = archives.prepareRetry(kbId, archiveId);
        executor.submitArchive(archive.getId());
        return ResponseEntity.accepted().body(RecoveryArchiveResponse.from(archive));
    }

    @GetMapping(value = "/archives/{archiveId}/download", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> downloadArchive(
            @PathVariable UUID kbId, @PathVariable UUID archiveId) {
        StreamingResponseBody body = output -> archives.download(kbId, archiveId, output);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("dupi-recovery-" + archiveId + ".zip").build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    @DeleteMapping("/archives/{archiveId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArchive(@PathVariable UUID kbId, @PathVariable UUID archiveId) {
        archives.delete(kbId, archiveId);
    }

    @PostMapping("/restores")
    public ResponseEntity<RecoveryRestoreResponse> createRestore(
            @PathVariable UUID kbId, @Valid @RequestBody CreateRestoreRequest request) {
        archives.get(kbId, request.archiveId());
        var job = restores.create(request.archiveId(), SecurityContext.getPrincipal());
        executor.submitRestore(job.getId(), job.getTenantId());
        return ResponseEntity.accepted().body(RecoveryRestoreResponse.from(job));
    }

    @GetMapping("/restores")
    public List<RecoveryRestoreResponse> listRestores(@PathVariable UUID kbId) {
        Set<UUID> archiveIds = archives.list(kbId).stream()
                .map(archive -> archive.getId())
                .collect(Collectors.toSet());
        return restores.list().stream()
                .filter(job -> archiveIds.contains(job.getArchiveId()))
                .map(RecoveryRestoreResponse::from)
                .toList();
    }

    @GetMapping("/restores/{jobId}")
    public RecoveryRestoreResponse getRestore(@PathVariable UUID kbId, @PathVariable UUID jobId) {
        var job = restores.find(jobId);
        archives.get(kbId, job.getArchiveId());
        return RecoveryRestoreResponse.from(job);
    }

    @PostMapping("/restores/{jobId}/retry")
    public ResponseEntity<RecoveryRestoreResponse> retryRestore(
            @PathVariable UUID kbId, @PathVariable UUID jobId) {
        var existing = restores.find(jobId);
        archives.get(kbId, existing.getArchiveId());
        var job = restores.prepareRetry(jobId);
        executor.submitRestore(job.getId(), job.getTenantId());
        return ResponseEntity.accepted().body(RecoveryRestoreResponse.from(job));
    }

    @DeleteMapping("/restores/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandonRestore(@PathVariable UUID kbId, @PathVariable UUID jobId) {
        var job = restores.find(jobId);
        archives.get(kbId, job.getArchiveId());
        restores.abandon(jobId);
    }
}
