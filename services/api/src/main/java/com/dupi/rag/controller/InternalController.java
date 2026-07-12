package com.dupi.rag.controller;

import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class InternalController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    @GetMapping("/knowledge-bases/{kbId}/chunks")
    public List<Map<String, Object>> listChunks(@PathVariable UUID kbId) {
        knowledgeBaseService.findSystemOrThrow(kbId);
        var fileNames = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .collect(Collectors.toMap(d -> d.getId(), d -> d.getFileName()));

        return chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId).stream()
                .map(c -> Map.<String, Object>of(
                        "chunk_id", c.getId().toString(),
                        "doc_id", c.getDocId().toString(),
                        "file_name", fileNames.getOrDefault(c.getDocId(), ""),
                        "content", c.getContent(),
                        "metadata", c.getMetadata() != null ? c.getMetadata() : Map.of()
                ))
                .toList();
    }
}
