package com.dupi.rag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateChatSessionRequest {
    @Size(max = 120)
    private String title;
}
