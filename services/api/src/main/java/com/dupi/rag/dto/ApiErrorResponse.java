package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResponse {
    private String error;
    private String message;
    private String stage;
    private String suggestion;
    private String requestId;
    private String timestamp;

    public static ApiErrorResponse of(String error, String message, String stage, String suggestion, String requestId) {
        return ApiErrorResponse.builder()
                .error(error)
                .message(message)
                .stage(stage)
                .suggestion(suggestion)
                .requestId(requestId)
                .timestamp(Instant.now().toString())
                .build();
    }
}
