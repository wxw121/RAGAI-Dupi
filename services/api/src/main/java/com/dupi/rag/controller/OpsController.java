package com.dupi.rag.controller;

import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.dto.AccountResponse;
import com.dupi.rag.dto.AccountUpsertRequest;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.AuditLogQuery;
import com.dupi.rag.dto.AuditLogResponse;
import com.dupi.rag.dto.VectorCleanupTaskResponse;
import jakarta.validation.Valid;
import com.dupi.rag.service.AccountService;
import com.dupi.rag.service.AuditLogService;
import com.dupi.rag.service.VectorCleanupTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return auditLogService.summarizeAlerts();
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts() {
        return accountService.listUsers();
    }

    @PostMapping("/accounts")
    public AccountResponse createAccount(@Valid @RequestBody AccountUpsertRequest request) {
        AccountResponse response = accountService.create(request);
        auditLogService.recordSuccess("ACCOUNT_MANAGE", "ACCOUNT", null, "Created account " + response.getUsername());
        return response;
    }

    @PatchMapping("/accounts/{username}")
    public AccountResponse updateAccount(@PathVariable String username, @Valid @RequestBody AccountUpsertRequest request) {
        AccountResponse response = accountService.update(username, request);
        auditLogService.recordSuccess("ACCOUNT_MANAGE", "ACCOUNT", null, "Updated account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/disable")
    public AccountResponse disableAccount(@PathVariable String username) {
        AccountResponse response = accountService.disable(username);
        auditLogService.recordSuccess("ACCOUNT_MANAGE", "ACCOUNT", null, "Disabled account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/enable")
    public AccountResponse enableAccount(@PathVariable String username) {
        AccountResponse response = accountService.enable(username);
        auditLogService.recordSuccess("ACCOUNT_MANAGE", "ACCOUNT", null, "Enabled account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/{username}/rotate-token")
    public AccountResponse rotateAccountToken(@PathVariable String username) {
        AccountResponse response = accountService.rotateTokenVersion(username);
        auditLogService.recordSuccess("ACCOUNT_MANAGE", "ACCOUNT", null, "Rotated token version for account " + response.getUsername());
        return response;
    }

    @PostMapping("/accounts/password-hash")
    public Map<String, String> generatePasswordHash(@RequestBody Map<String, String> request) {
        return Map.of("passwordHash", accountService.generatePasswordHash(request == null ? null : request.get("password")));
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
