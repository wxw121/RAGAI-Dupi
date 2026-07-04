package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ChatMessageResponse {
    private UUID id;
    private UUID sessionId;
    private Integer sequenceNumber;
    private String role;
    private String content;
    private List<Citation> citations;
    private Instant createdAt;
}
