package com.dupi.rag.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BatchDeleteChatSessionsRequest {
    @NotEmpty
    private List<UUID> sessionIds;
}
