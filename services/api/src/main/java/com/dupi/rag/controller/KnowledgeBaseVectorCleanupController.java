package com.dupi.rag.controller;

import com.dupi.rag.dto.VectorCleanupTaskResponse;
import com.dupi.rag.service.KnowledgeBaseService;
import com.dupi.rag.service.VectorCleanupTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/vector-cleanup-tasks")
@RequiredArgsConstructor
public class KnowledgeBaseVectorCleanupController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorCleanupTaskService vectorCleanupTaskService;

    @GetMapping
    public List<VectorCleanupTaskResponse> list(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return vectorCleanupTaskService.listOpenTasks(kbId);
    }

    @PostMapping("/{taskId}/retry")
    public VectorCleanupTaskResponse retry(@PathVariable UUID kbId, @PathVariable UUID taskId) {
        knowledgeBaseService.findOrThrow(kbId);
        return vectorCleanupTaskService.retry(kbId, taskId);
    }
}
