package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank
    @Size(max = 4000)
    private String query;

    private Boolean stream = true;

    private Integer topK;

    private Boolean useRerank = false;

    private String sessionId;
}
