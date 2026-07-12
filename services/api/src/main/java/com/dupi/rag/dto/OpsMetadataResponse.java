package com.dupi.rag.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class OpsMetadataResponse {
    private List<String> permissions;
    private List<PermissionMetadataResponse> permissionDetails;
    private List<String> auditActions;
    private List<String> auditTargetTypes;
    private List<String> auditStatuses;
}
