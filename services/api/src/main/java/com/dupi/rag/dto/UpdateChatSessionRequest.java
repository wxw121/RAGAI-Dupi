package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateChatSessionRequest {
    @NotBlank
    @Size(max = 120)
    private String title;
}
