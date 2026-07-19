package com.dupi.rag.controller;

import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.config.UploadRateLimitProperties;
import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.dto.AccountResponse;
import com.dupi.rag.dto.AccountUpsertRequest;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.AuditLogQuery;
import com.dupi.rag.dto.AuditLogResponse;
import com.dupi.rag.dto.GovernanceSummaryResponse;
import com.dupi.rag.dto.OpsGuardrailsResponse;
import com.dupi.rag.dto.OpsMetadataResponse;
import com.dupi.rag.dto.OpsNotificationResponse;
import com.dupi.rag.dto.PasswordResetRequest;
import com.dupi.rag.dto.RoleRequest;
import com.dupi.rag.dto.RoleResponse;
import com.dupi.rag.dto.VectorCleanupTaskResponse;
import jakarta.validation.Valid;
import com.dupi.rag.service.AccountService;
import com.dupi.rag.service.AuditLogService;
import com.dupi.rag.service.GovernanceOpsService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.OpsNotificationService;
import com.dupi.rag.service.RoleService;
import com.dupi.rag.service.VectorCleanupTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops")
@RequiredArgsConstructor
public class OpsController {

    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final AuditLogService auditLogService;
    private final AccountService accountService;
    private final RoleService roleService;
    private final IngestJobService ingestJobService;
    private final UploadRateLimitProperties uploadRateLimitProperties;
    private final RedisQueueProperties redisQueueProperties;
    private final AuditProperties auditProperties;
    private final MultipartProperties multipartProperties;
    private final OpsNotificationService opsNotificationService;
    private final GovernanceOpsService governanceOpsService;

    @GetMapping("/vector-cleanup-tasks")
    public List<VectorCleanupTaskResponse> listVectorCleanupTasks() {
        return vectorCleanupTaskService.listOpenTasks();
    }

    @PostMapping("/vector-cleanup-tasks/{taskId}/retry")
    public VectorCleanupTaskResponse retryVectorCleanupTask(@PathVariable UUID taskId) {
        return vectorCleanupTaskService.retry(taskId);
    }

    @GetMapping("/audit-logs")
    public List<AuditLogResponse> listAuditLogs(
            String tenantId,
            String action,
            String targetType,
            String status,
            Integer limit
    ) {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(tenantId);
        query.setAction(action);
        query.setTargetType(targetType);
        query.setStatus(parseStatus(status));
        query.setLimit(limit);
        return auditLogService.list(query);
    }

    @GetMapping(value = "/audit-logs/export", produces = "text/csv")
    public String exportAuditLogs(
            String tenantId,
            String action,
            String targetType,
            String status
    ) {
        return auditLogService.exportCsv(query(tenantId, action, targetType, status, 10_000));
    }

    @GetMapping("/audit-alerts")
    public List<AuditAlertResponse> listAuditAlerts() {
        List<AuditAlertResponse> alerts = new ArrayList<>();
        alerts.addAll(auditLogService.summarizeAlerts());
        alerts.addAll(ingestJobService.summarizeAlerts());
        alerts.addAll(vectorCleanupTaskService.summarizeAlerts());
        return alerts;
    }

    @GetMapping("/governance-summary")
    public GovernanceSummaryResponse governanceSummary() {
        return governanceOpsService.summarize();
    }

    @PostMapping("/audit-alerts/notify")
    public OpsNotificationResponse notifyAuditAlerts() {
        OpsNotificationResponse response = opsNotificationService.notifyAlerts(listAuditAlerts());
        if (response.isDelivered()) {
            auditLogService.recordSuccess(
                    "AUDIT_ALERT_NOTIFY",
                    "AUDIT_ALERT",
                    null,
                    "Delivered " + response.getAlertCount() + " alerts to the configured webhook"
            );
        } else {
            auditLogService.recordFailure(
                    "AUDIT_ALERT_NOTIFY",
                    "AUDIT_ALERT",
                    null,
                    new IllegalStateException(response.getMessage() == null ? "webhook delivery failed" : response.getMessage())
            );
        }
        return response;
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts() {
        return accountService.listUsers();
    }

    @GetMapping("/metadata")
    public OpsMetadataResponse metadata() {
        return OpsMetadataResponse.builder()
                .permissions(RoleService.DEFAULT_PERMISSIONS)
                .permissionDetails(RoleService.DEFAULT_PERMISSION_DETAILS)
                .auditActions(List.of(
                        "ACCOUNT_CREATE",
                        "ACCOUNT_UPDATE",
                        "ACCOUNT_PASSWORD_RESET",
                        "ACCOUNT_DISABLE",
                        "ACCOUNT_ENABLE",
                        "ACCOUNT_TOKEN_ROTATE",
                        "ACCOUNT_DELETE_E2E",
                        "ROLE_CREATE",
                        "ROLE_UPDATE",
                        "ROLE_DISABLE",
                        "DOCUMENT_DELETE",
                        "KNOWLEDGE_BASE_DELETE",
                        "REINDEX",
                        "INGEST_RETRY",
                        "VECTOR_CLEANUP_RETRY",
                        "AUDIT_ALERT_NOTIFY"
                ))
                .auditTargetTypes(List.of("ACCOUNT", "ROLE", "DOCUMENT", "KNOWLEDGE_BASE", "INGEST_JOB", "VECTOR_CLEANUP_TASK", "CHAT_SESSION", "AUDIT_ALERT"))
                .auditStatuses(List.of("SUCCESS", "FAILED"))
                .guardrails(guardrails())
                .build();
    }

    @PostMapping("/accounts")
    public AccountResponse createAccount(@Valid @RequestBody AccountUpsertRequest request) {
        AccountResponse response = accountService.create(request);
        auditLogService.recordSuccess("ACCOUNT_CREATE", "ACCOUNT", null, "Created account " + response.getUsername());
        return response;
    }

    @PatchMapping("/accounts/{username}")
    public AccountResponse updateAccount(@PathVariable String username, @Valid @RequestBody AccountUpsertRequest request) {
        AccountResponse response = accountService.update(username, request);
        auditLogService.recordSuccess("ACCOUNT_UPDATE", "ACCOUNT", null, "Updated account " + response.getUsername());
        return response;
    }

    @DeleteMapping("/accounts/{username}")
    public void deleteE2eAccount(@PathVariable String username) {
        String deleted = accountService.deleteE2e(username);
        auditLogService.recordSuccess("ACCOUNT_DELETE_E2E", "ACCOUNT", null, "Deleted E2E account " + deleted);
    }

    @PostMapping("/accounts/{username}/reset-password")
    public AccountResponse resetAccountPassword(@PathVariable String username, @RequestBody PasswordResetRequest request) {
        AccountResponse response = accountService.resetPassword(username, request == null ? null : request.getPassword());
        auditLogService.recordSuccess("ACCOUNT_PASSWORD_RESET", "ACCOUNT", null, "Reset password for account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/disable")
    public AccountResponse disableAccount(@PathVariable String username) {
        AccountResponse response = accountService.disable(username);
        auditLogService.recordSuccess("ACCOUNT_DISABLE", "ACCOUNT", null, "Disabled account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/enable")
    public AccountResponse enableAccount(@PathVariable String username) {
        AccountResponse response = accountService.enable(username);
        auditLogService.recordSuccess("ACCOUNT_ENABLE", "ACCOUNT", null, "Enabled account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/rotate-token")
    public AccountResponse rotateAccountToken(@PathVariable String username) {
        AccountResponse response = accountService.rotateTokenVersion(username);
        auditLogService.recordSuccess("ACCOUNT_TOKEN_ROTATE", "ACCOUNT", null, "Rotated token version for account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/password-hash")
    public Map<String, String> generatePasswordHash(@RequestBody Map<String, String> request) {
        return Map.of("passwordHash", accountService.generatePasswordHash(request == null ? null : request.get("password")));
    }

    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return roleService.listRoles();
    }

    @PostMapping("/roles")
    public RoleResponse createRole(@Valid @RequestBody RoleRequest request) {
        RoleResponse response = roleService.create(request);
        auditLogService.recordSuccess("ROLE_CREATE", "ROLE", response.getId(), "Created role " + response.getCode());
        return response;
    }

    @PatchMapping("/roles/{roleId}")
    public RoleResponse updateRole(@PathVariable UUID roleId, @Valid @RequestBody RoleRequest request) {
        RoleResponse response = roleService.update(roleId, request);
        auditLogService.recordSuccess("ROLE_UPDATE", "ROLE", response.getId(), "Updated role " + response.getCode());
        return response;
    }

    @PostMapping("/roles/{roleId}/disable")
    public RoleResponse disableRole(@PathVariable UUID roleId) {
        RoleResponse response = roleService.disable(roleId);
        auditLogService.recordSuccess("ROLE_DISABLE", "ROLE", response.getId(), "Disabled role " + response.getCode());
        return response;
    }

    private OpsGuardrailsResponse guardrails() {
        return OpsGuardrailsResponse.builder()
                .uploadRateLimit(OpsGuardrailsResponse.UploadRateLimit.builder()
                        .enabled(uploadRateLimitProperties.isEnabled())
                        .requests(uploadRateLimitProperties.getRequests())
                        .windowSeconds(uploadRateLimitProperties.getWindowSeconds())
                        .build())
                .ingestQueue(OpsGuardrailsResponse.IngestQueue.builder()
                        .maxPendingJobs(redisQueueProperties.getMaxPendingJobs())
                        .maxRecoveryAttempts(redisQueueProperties.getMaxRecoveryAttempts())
                        .build())
                .audit(OpsGuardrailsResponse.Audit.builder()
                        .alertWindowMinutes(auditProperties.getAlertWindowMinutes())
                        .alertFailedThreshold(auditProperties.getAlertFailedThreshold())
                        .build())
                .multipart(OpsGuardrailsResponse.Multipart.builder()
                        .maxFileSizeBytes(multipartProperties.getMaxFileSize() == null
                                ? 0
                                : multipartProperties.getMaxFileSize().toBytes())
                        .build())
                .build();
    }

    private AuditLogQuery query(String tenantId, String action, String targetType, String status, Integer limit) {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(tenantId);
        query.setAction(action);
        query.setTargetType(targetType);
        query.setStatus(parseStatus(status));
        query.setLimit(limit);
        return query;
    }

    private AuditLogStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AuditLogStatus.valueOf(status.trim().toUpperCase());
    }
}
