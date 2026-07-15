package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsNotificationResponse {
    private boolean configured;
    private boolean delivered;
    private int alertCount;
    private Integer statusCode;
    private String message;
}
