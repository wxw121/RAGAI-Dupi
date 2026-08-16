package com.dupi.rag.controller;

import com.dupi.rag.config.ApiKeyAuthFilter;
import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.exception.GlobalExceptionHandler;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.service.RecoveryArchiveImportService;
import com.dupi.rag.service.RecoveryArchiveService;
import com.dupi.rag.service.RecoveryJobExecutor;
import com.dupi.rag.service.RecoveryRestoreService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecoveryControllerWebTest {

    @Test
    void importRequiresRecoveryAndReadPermissionsThenReturnsAcceptedPublicOperation() throws Exception {
        Harness harness = harness();
        UUID kbId = UUID.randomUUID(); UUID jobId = UUID.randomUUID();
        when(harness.imports.submit(eq(kbId), any(), eq("request-1"), eq("recovery-user")))
                .thenReturn(OperationJobResponse.builder().id(jobId).status(OperationStatus.PREPARED).build());

        harness.mvc.perform(request(kbId))
                .andExpect(status().isUnauthorized());
        harness.mvc.perform(request(kbId).header("Authorization", "Bearer " + harness.readToken))
                .andExpect(status().isForbidden());
        harness.mvc.perform(request(kbId).header("Authorization", "Bearer " + harness.recoveryToken))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.resourceRef").doesNotExist());
    }

    @Test
    void importConflictAndValidationErrorsUsePublicJsonEnvelope() throws Exception {
        Harness harness = harness(); UUID kbId = UUID.randomUUID();
        when(harness.imports.submit(eq(kbId), any(), eq("conflict"), anyString()))
                .thenThrow(new OperationConflictException("same key belongs to different input"));
        when(harness.imports.submit(eq(kbId), any(), eq("invalid"), anyString()))
                .thenThrow(new IllegalArgumentException("Recovery ZIP checksum mismatch"));

        harness.mvc.perform(request(kbId, "conflict").header("Authorization", "Bearer " + harness.recoveryToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("operation_conflict"))
                .andExpect(jsonPath("$.stage").value("operation"));
        harness.mvc.perform(request(kbId, "invalid").header("Authorization", "Bearer " + harness.recoveryToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Recovery ZIP checksum mismatch"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(UUID kbId) {
        return request(kbId, "request-1");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(UUID kbId, String key) {
        MockMultipartFile file = new MockMultipartFile("file", "recovery.zip", "application/zip", new byte[]{1, 2, 3});
        return multipart("/api/v1/knowledge-bases/{kbId}/recovery/archives/import", kbId)
                .file(file).header("Idempotency-Key", key).accept(MediaType.APPLICATION_JSON);
    }

    private static Harness harness() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("recovery-controller-test-secret"); properties.setTokenTtlSeconds(300);
        properties.getUsers().add(user("reader", "KB_READ"));
        properties.getUsers().add(user("recovery-user", "KB_READ,KB_RECOVERY"));
        ApiTokenService tokens = new ApiTokenService(properties);
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryArchiveImportService imports = mock(RecoveryArchiveImportService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        RecoveryJobExecutor executor = mock(RecoveryJobExecutor.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecoveryController(archives, imports, restores, executor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ApiKeyAuthFilter(properties, tokens)).build();
        return new Harness(imports, mvc, tokens.issueToken("reader", "tenant-a", "USER"),
                tokens.issueToken("recovery-user", "tenant-a", "USER"));
    }

    private static ApiSecurityProperties.UserAccount user(String username, String permissions) {
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username); user.setPassword("unused"); user.setTenantId("tenant-a");
        user.setRole("USER"); user.setPermissions(permissions); return user;
    }

    private record Harness(RecoveryArchiveImportService imports, MockMvc mvc,
                           String readToken, String recoveryToken) { }
}
