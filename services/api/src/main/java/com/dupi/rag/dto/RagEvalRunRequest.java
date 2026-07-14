package com.dupi.rag.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class RagEvalRunRequest {
    private Boolean useRerank = false;
    private UUID profileId;
}
