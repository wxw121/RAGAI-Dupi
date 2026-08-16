package com.dupi.rag.controller;

import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.service.OperationJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OperationController {

    private final OperationJobService operationJobService;

    @GetMapping("/api/v1/operations/{jobId}")
    public OperationJobResponse get(@PathVariable UUID jobId) {
        return operationJobService.get(jobId);
    }

    @PostMapping("/api/v1/operations/{jobId}/retry")
    public OperationJobResponse retry(@PathVariable UUID jobId) {
        return operationJobService.retry(jobId);
    }
}
