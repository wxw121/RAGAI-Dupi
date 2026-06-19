package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class Citation {
    private UUID chunkId;
    private UUID docId;
    private String fileName;
    private String snippet;
    private double score;
}
