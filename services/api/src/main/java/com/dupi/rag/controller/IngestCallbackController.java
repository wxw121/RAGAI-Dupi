package com.dupi.rag.controller;

import com.dupi.rag.dto.IngestCallbackAckResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.service.IngestJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/ingest")
@RequiredArgsConstructor
public class IngestCallbackController {

    private final IngestJobService ingestJobService;

    @PostMapping("/status")
    public Map<String, Object> updateStatus(@RequestBody IngestStatusUpdate update) {
        IngestCallbackAckResponse ack = ingestJobService.handleStatusUpdate(update);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", ack.getStatus());
        response.put("ignored", ack.isIgnored());
        if (ack.getReason() != null) {
            response.put("reason", ack.getReason());
        }
        return response;
    }

    @PostMapping("/jobs/{jobId}/claim")
    public IngestJobResponse claim(@PathVariable UUID jobId, @RequestBody Map<String, Object> body) {
        return ingestJobService.claim(
                jobId,
                UUID.fromString(requiredText(body, "executionId")),
                requiredText(body, "workerId"),
                Duration.ofSeconds(leaseSeconds(body))
        );
    }

    @PostMapping("/jobs/{jobId}/lease")
    public IngestJobResponse refreshLease(@PathVariable UUID jobId, @RequestBody Map<String, Object> body) {
        return ingestJobService.refreshLease(
                jobId,
                UUID.fromString(requiredText(body, "executionId")),
                requiredText(body, "workerId"),
                Duration.ofSeconds(leaseSeconds(body))
        );
    }

    @GetMapping("/jobs/{jobId}/cancelled")
    public Map<String, Boolean> cancelled(
            @PathVariable UUID jobId,
            @RequestParam UUID executionId
    ) {
        return Map.of("cancelled", ingestJobService.isCancellationRequested(jobId, executionId));
    }

    @GetMapping("/jobs/{jobId}/executions/{executionId}/state")
    public Map<String, Object> executionState(
            @PathVariable UUID jobId,
            @PathVariable UUID executionId
    ) {
        return ingestJobService.getExecutionState(jobId, executionId);
    }

    @PostMapping("/jobs/{jobId}/retry")
    public IngestJobResponse retry(@PathVariable UUID jobId) {
        return ingestJobService.retry(jobId);
    }

    private String requiredText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.toString();
    }

    private long leaseSeconds(Map<String, Object> body) {
        Object value = body.get("leaseSeconds");
        if (value == null) {
            return 60L;
        }
        long seconds = Long.parseLong(value.toString());
        if (seconds <= 0 || seconds > 600) {
            throw new IllegalArgumentException("leaseSeconds must be between 1 and 600");
        }
        return seconds;
    }
}
