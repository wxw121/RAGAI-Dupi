package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ChatSessionResponse {
    private UUID id;
    private UUID kbId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
}
