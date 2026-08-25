package com.dupi.rag.controller;

import com.dupi.rag.config.ApiKeyAuthFilter;
import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.OperationStepResponse;
import com.dupi.rag.exception.GlobalExceptionHandler;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.service.OperationJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationControllerWebTest {
    @Test
    void getRequiresAuthenticationAndKbReadThenSerializesOnlyPublicDiagnostics() throws Exception {
        Harness harness = harness();
        UUID jobId = UUID.randomUUID();
        OperationJobResponse response = OperationJobResponse.builder().id(jobId).status(OperationStatus.FAILED)
                .errorCode("operation_failed").errorMessage("Operation failed.").nextAttemptAt(null)
                .steps(List.of(OperationStepResponse.builder().stepKey("store")
                        .status(OperationStepStatus.FAILED).lastError("Operation step failed.").build()))
                .build();
        when(harness.service.get(jobId)).thenReturn(response);

        harness.mvc.perform(get("/api/v1/operations/{jobId}", jobId))
                .andExpect(status().isUnauthorized());
        harness.mvc.perform(get("/api/v1/operations/{jobId}", jobId)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + harness.readToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("operation_failed"))
                .andExpect(jsonPath("$.steps[0].lastError").value("Operation step failed."))
                .andExpect(jsonPath("$.steps[0].resourceRef").doesNotExist());
    }

    @Test
    void retryRequiresMaintenanceAndKbRead() throws Exception {
        Harness harness = harness();
        UUID jobId = UUID.randomUUID();
        when(harness.service.retry(jobId)).thenReturn(OperationJobResponse.builder().id(jobId).build());

        harness.mvc.perform(post("/api/v1/operations/{jobId}/retry", jobId)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + harness.readToken))
                .andExpect(status().isForbidden());
        harness.mvc.perform(post("/api/v1/operations/{jobId}/retry", jobId)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + harness.maintenanceToken))
                .andExpect(status().isOk());
    }

    @Test
    void inaccessibleGetIs404AndInvalidRetryStateIs409() throws Exception {
        Harness harness = harness();
        UUID hidden = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        when(harness.service.get(hidden)).thenThrow(new ResourceNotFoundException("Operation job not found: " + hidden));
        when(harness.service.retry(running)).thenThrow(new OperationConflictException("Only failed operations can be retried"));

        harness.mvc.perform(get("/api/v1/operations/{jobId}", hidden)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + harness.readToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        harness.mvc.perform(post("/api/v1/operations/{jobId}/retry", running)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + harness.maintenanceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("operation_conflict"));
    }

    private static Harness harness() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("operation-controller-test-secret");
        properties.setTokenTtlSeconds(300);
        properties.getUsers().add(user("reader", "KB_READ"));
        properties.getUsers().add(user("maintainer", "KB_READ,MAINTENANCE"));
        ApiTokenService tokens = new ApiTokenService(properties);
        OperationJobService service = mock(OperationJobService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ApiKeyAuthFilter(properties, tokens))
                .build();
        return new Harness(service, mvc,
                tokens.issueToken("reader", "tenant-a", "USER"),
                tokens.issueToken("maintainer", "tenant-a", "USER"));
    }

    private static ApiSecurityProperties.UserAccount user(String username, String permissions) {
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username);
        user.setPassword("unused");
        user.setTenantId("tenant-a");
        user.setRole("USER");
        user.setPermissions(permissions);
        return user;
    }

    private record Harness(OperationJobService service, MockMvc mvc, String readToken, String maintenanceToken) { }
}
