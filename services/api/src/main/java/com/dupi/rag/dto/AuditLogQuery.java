package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.AuditLogStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogQuery {

    private String tenantId;
    private String action;
    private String targetType;
    private AuditLogStatus status;
    private Integer limit = 50;
}
