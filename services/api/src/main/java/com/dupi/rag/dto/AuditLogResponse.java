package com.dupi.rag.dto;

import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.enums.AuditLogStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AuditLogResponse {

    private UUID id;
    private String tenantId;
    private String action;
    private String targetType;
    private UUID targetId;
    private AuditLogStatus status;
    private String message;
    private String errorMessage;
    private Instant createdAt;

    public static AuditLogResponse from(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .tenantId(log.getTenantId())
                .action(log.getAction())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .status(log.getStatus())
                .message(log.getMessage())
                .errorMessage(log.getErrorMessage())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
