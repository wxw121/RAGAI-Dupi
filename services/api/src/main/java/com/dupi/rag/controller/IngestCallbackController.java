package com.dupi.rag.controller;

import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.service.IngestJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/ingest")
@RequiredArgsConstructor
public class IngestCallbackController {

    private final IngestJobService ingestJobService;

    @PostMapping("/status")
    public Map<String, String> updateStatus(@RequestBody IngestStatusUpdate update) {
        ingestJobService.handleStatusUpdate(update);
        return Map.of("status", "ok");
    }

    @PostMapping("/jobs/{jobId}/retry")
    public IngestJobResponse retry(@PathVariable UUID jobId) {
        return ingestJobService.retry(jobId);
    }
}
