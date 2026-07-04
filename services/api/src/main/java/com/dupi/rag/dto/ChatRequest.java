package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank
    @Size(max = 4000)
    private String query;

    private Boolean stream = true;

    @Min(1)
    @Max(50)
    private Integer topK;

    private Boolean useRerank = false;

    private String sessionId;
}
