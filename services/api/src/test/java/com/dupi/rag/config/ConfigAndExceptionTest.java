package com.dupi.rag.config;

import com.dupi.rag.exception.GlobalExceptionHandler;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.UserAccountRepository;
import io.milvus.client.MilvusServiceClient;
import io.minio.MinioClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigAndExceptionTest {

    @Test
    void corsConfigAllowsLocalDevelopmentOriginsAndStandardMethods() {
        CorsFilter filter = new CorsConfig().corsFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, mock(FilterChain.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void traceIdFilterPropagatesExistingTraceIdOrCreatesOne() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest withTrace = new MockHttpServletRequest();
        withTrace.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(withTrace, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-123");
        verify(chain).doFilter(withTrace, response);

        MockHttpServletResponse generated = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), generated, mock(FilterChain.class));
        assertThat(generated.getHeader("X-Trace-Id")).hasSize(16);
    }

    @Test
    void apiKeyFilterAllowsHealthAndRejectsMissingPublicApiKey() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiKey("public-key");
        properties.setInternalKey("internal-key");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse healthResponse = new MockHttpServletResponse();
        filter.doFilter(health, healthResponse, chain);
        verify(chain).doFilter(health, healthResponse);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("Unauthorized API request");
    }

    @Test
    void apiKeyFilterAllowsPublicAndInternalRequestsWithExpectedKeys() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiKey("public-key");
        properties.setInternalKey("internal-key");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties);
        FilterChain publicChain = mock(FilterChain.class);
        FilterChain internalChain = mock(FilterChain.class);

        MockHttpServletRequest publicRequest = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        publicRequest.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse publicResponse = new MockHttpServletResponse();
        filter.doFilter(publicRequest, publicResponse, publicChain);

        MockHttpServletRequest internalRequest = new MockHttpServletRequest("POST", "/api/v1/internal/ingest/status");
        internalRequest.addHeader(ApiKeyAuthFilter.INTERNAL_API_KEY_HEADER, "internal-key");
        MockHttpServletResponse internalResponse = new MockHttpServletResponse();
        filter.doFilter(internalRequest, internalResponse, internalChain);

        verify(publicChain).doFilter(publicRequest, publicResponse);
        verify(internalChain).doFilter(internalRequest, internalResponse);
    }

    @Test
    void apiKeyFilterKeepsLocalDevelopmentOpenWhenKeysAreBlank() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ingest/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiKeyFilterClosesLocalDevelopmentOpenWhenPersistedAccountsExist() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        when(userAccountRepository.countByRoleCodeAndDisabledFalse("ADMIN")).thenReturn(0L, 1L);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                properties,
                new ApiTokenService(properties),
                userAccountRepository
        );

        MockHttpServletRequest createAccount = new MockHttpServletRequest("POST", "/api/v1/ops/accounts");
        MockHttpServletResponse createAccountResponse = new MockHttpServletResponse();
        FilterChain createAccountChain = (request, response) -> {
            assertThat(SecurityContext.getPrincipal()).isEqualTo("local-open");
            assertThat(SecurityContext.hasPermission("OPS_ADMIN")).isTrue();
        };
        filter.doFilter(createAccount, createAccountResponse, createAccountChain);

        MockHttpServletRequest listKnowledgeBases = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        MockHttpServletResponse listKnowledgeBasesResponse = new MockHttpServletResponse();
        filter.doFilter(listKnowledgeBases, listKnowledgeBasesResponse, mock(FilterChain.class));

        assertThat(createAccountResponse.getStatus()).isEqualTo(200);
        assertThat(listKnowledgeBasesResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(listKnowledgeBasesResponse.getContentAsString()).contains("Unauthorized API request");
    }

    @Test
    void apiKeyFilterKeepsLocalDevelopmentOpenWhenOnlyNonAdminAccountsExist() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        when(userAccountRepository.countByRoleCodeAndDisabledFalse("ADMIN")).thenReturn(0L);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                properties,
                new ApiTokenService(properties),
                userAccountRepository
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ops/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(SecurityContext.getPrincipal()).isEqualTo("local-open");
            assertThat(SecurityContext.hasPermission("OPS_ADMIN")).isTrue();
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiKeyFilterRequiresBearerTokenWhenAccountsAreConfigured() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        filter.doFilter(missing, missingResponse, chain);

        MockHttpServletRequest authorized = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        authorized.addHeader("Authorization", "Bearer " + tokenService.issueToken("alice", "tenant-a", "USER"));
        authorized.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-b");
        MockHttpServletResponse authorizedResponse = new MockHttpServletResponse();
        AtomicBoolean contextObserved = new AtomicBoolean(false);
        FilterChain authorizedChain = (request, response) -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
            assertThat(SecurityContext.getPrincipal()).isEqualTo("alice");
            assertThat(SecurityContext.hasRole("USER")).isTrue();
            contextObserved.set(true);
        };
        filter.doFilter(authorized, authorizedResponse, authorizedChain);

        assertThat(missingResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(missingResponse.getContentAsString()).contains("Unauthorized API request");
        assertThat(contextObserved).isTrue();
        assertThat(authorizedResponse.getHeader(TenantContextFilter.TENANT_HEADER)).isEqualTo("tenant-a");
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void apiKeyFilterAuthenticatesCookieTokenAndRequiresCsrfForMutations() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);
        String token = tokenService.issueToken("alice", "tenant-a", "USER");
        String csrf = "csrf-token";

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        read.setCookies(new jakarta.servlet.http.Cookie(ApiKeyAuthFilter.AUTH_COOKIE_NAME, token));
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        FilterChain readChain = mock(FilterChain.class);
        filter.doFilter(read, readResponse, readChain);

        MockHttpServletRequest missingCsrf = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/chat");
        missingCsrf.setCookies(
                new jakarta.servlet.http.Cookie(ApiKeyAuthFilter.AUTH_COOKIE_NAME, token),
                new jakarta.servlet.http.Cookie(ApiKeyAuthFilter.CSRF_COOKIE_NAME, csrf)
        );
        MockHttpServletResponse missingCsrfResponse = new MockHttpServletResponse();
        filter.doFilter(missingCsrf, missingCsrfResponse, mock(FilterChain.class));

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/chat");
        write.setCookies(
                new jakarta.servlet.http.Cookie(ApiKeyAuthFilter.AUTH_COOKIE_NAME, token),
                new jakarta.servlet.http.Cookie(ApiKeyAuthFilter.CSRF_COOKIE_NAME, csrf)
        );
        write.addHeader(ApiKeyAuthFilter.CSRF_HEADER, csrf);
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        FilterChain writeChain = mock(FilterChain.class);
        filter.doFilter(write, writeResponse, writeChain);

        verify(readChain).doFilter(read, readResponse);
        assertThat(missingCsrfResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(missingCsrfResponse.getContentAsString()).contains("CSRF token required");
        verify(writeChain).doFilter(write, writeResponse);
    }

    @Test
    void apiKeyFilterKeepsApiKeyCompatibilityWhenAccountsAreConfigured() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiKey("public-key");
        properties.setAuthSecret("test-secret");
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                properties,
                new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC))
        );
        AtomicBoolean contextObserved = new AtomicBoolean(false);
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("tenant-api");
            assertThat(SecurityContext.getPrincipal()).isEqualTo("api-key");
            assertThat(SecurityContext.hasRole("ADMIN")).isTrue();
            contextObserved.set(true);
        };

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        request.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-api");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(contextObserved).isTrue();
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void apiKeyFilterAllowsOnlyAdminRoleToAccessOpsRoutes() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiKey("public-key");
        properties.setAuthSecret("test-secret");
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        properties.getUsers().add(user("admin", "pw", "ops", "ADMIN"));
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);
        AtomicBoolean adminContextObserved = new AtomicBoolean(false);
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("ops");
            assertThat(SecurityContext.getPrincipal()).isEqualTo("admin");
            assertThat(SecurityContext.hasRole("ADMIN")).isTrue();
            adminContextObserved.set(true);
        };

        MockHttpServletRequest userRequest = new MockHttpServletRequest("GET", "/api/v1/ops/vector-cleanup-tasks");
        userRequest.addHeader("Authorization", "Bearer " + tokenService.issueToken("alice", "tenant-a", "USER"));
        userRequest.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse userResponse = new MockHttpServletResponse();
        filter.doFilter(userRequest, userResponse, chain);

        MockHttpServletRequest adminRequest = new MockHttpServletRequest("GET", "/api/v1/ops/vector-cleanup-tasks");
        adminRequest.addHeader("Authorization", "Bearer " + tokenService.issueToken("admin", "ops", "ADMIN"));
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();
        filter.doFilter(adminRequest, adminResponse, chain);

        assertThat(userResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(userResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(adminContextObserved).isTrue();
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void apiKeyFilterRequiresFineGrainedPermissionsForDestructiveRoutes() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        ApiSecurityProperties.UserAccount reader = user("reader", "pw", "tenant-a", "USER");
        ApiSecurityProperties.UserAccount maintainer = user("maintainer", "pw", "tenant-a", "USER");
        maintainer.setPermissions("KB_READ,DOCUMENT_DELETE");
        properties.getUsers().add(reader);
        properties.getUsers().add(maintainer);
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);
        AtomicBoolean deleteContextObserved = new AtomicBoolean(false);
        FilterChain deleteChain = (servletRequest, servletResponse) -> {
            assertThat(SecurityContext.getPrincipal()).isEqualTo("maintainer");
            assertThat(SecurityContext.hasPermission("DOCUMENT_DELETE")).isTrue();
            assertThat(SecurityContext.hasPermission("OPS_ADMIN")).isFalse();
            deleteContextObserved.set(true);
        };

        MockHttpServletRequest readerList = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        readerList.addHeader("Authorization", "Bearer " + tokenService.issueToken("reader", "tenant-a", "USER"));
        MockHttpServletResponse readerListResponse = new MockHttpServletResponse();
        FilterChain readChain = mock(FilterChain.class);
        filter.doFilter(readerList, readerListResponse, readChain);

        MockHttpServletRequest readerDelete = new MockHttpServletRequest("DELETE", "/api/v1/knowledge-bases/kb/documents/doc");
        readerDelete.addHeader("Authorization", "Bearer " + tokenService.issueToken("reader", "tenant-a", "USER"));
        MockHttpServletResponse readerDeleteResponse = new MockHttpServletResponse();
        filter.doFilter(readerDelete, readerDeleteResponse, mock(FilterChain.class));

        MockHttpServletRequest maintainerDelete = new MockHttpServletRequest("DELETE", "/api/v1/knowledge-bases/kb/documents/doc");
        maintainerDelete.addHeader("Authorization", "Bearer " + tokenService.issueToken("maintainer", "tenant-a", "USER"));
        MockHttpServletResponse maintainerDeleteResponse = new MockHttpServletResponse();
        filter.doFilter(maintainerDelete, maintainerDeleteResponse, deleteChain);

        MockHttpServletRequest maintainerOps = new MockHttpServletRequest("GET", "/api/v1/ops/vector-cleanup-tasks");
        maintainerOps.addHeader("Authorization", "Bearer " + tokenService.issueToken("maintainer", "tenant-a", "USER"));
        MockHttpServletResponse maintainerOpsResponse = new MockHttpServletResponse();
        filter.doFilter(maintainerOps, maintainerOpsResponse, mock(FilterChain.class));

        verify(readChain).doFilter(readerList, readerListResponse);
        assertThat(readerDeleteResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readerDeleteResponse.getContentAsString()).contains("permission required: DOCUMENT_DELETE");
        assertThat(deleteContextObserved).isTrue();
        assertThat(maintainerOpsResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(maintainerOpsResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void apiKeyFilterRestrictsScopedUsersToConfiguredKnowledgeBases() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        ApiSecurityProperties.UserAccount scoped = user("scoped", "pw", "tenant-a", "USER");
        scoped.setPermissions("KB_READ,CHAT_WRITE,DOCUMENT_DELETE");
        scoped.setKnowledgeBaseIds("kb-a,kb-b");
        properties.getUsers().add(scoped);
        properties.getUsers().add(user("admin", "pw", "ops", "ADMIN"));
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);

        assertAllowed(filter, tokenService, "scoped", "GET", "/api/v1/knowledge-bases/kb-a/documents");
        assertAllowed(filter, tokenService, "scoped", "POST", "/api/v1/knowledge-bases/kb-b/chat");
        assertAllowed(filter, tokenService, "admin", "DELETE", "/api/v1/knowledge-bases/kb-secret");

        MockHttpServletRequest forbidden = bearerRequest(tokenService, "scoped", "GET", "/api/v1/knowledge-bases/kb-secret/documents");
        MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
        filter.doFilter(forbidden, forbiddenResponse, mock(FilterChain.class));

        assertThat(forbiddenResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(forbiddenResponse.getContentAsString()).contains("knowledge base access required: kb-secret");
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void apiKeyFilterMapsWriteMaintenanceAndChatPermissions() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        ApiSecurityProperties.UserAccount operator = user("operator", "pw", "tenant-a", "USER");
        operator.setPermissions("KB_READ,KB_WRITE,KB_DELETE,DOCUMENT_UPLOAD,CHAT_WRITE,CHAT_DELETE,MAINTENANCE");
        ApiSecurityProperties.UserAccount reader = user("reader", "pw", "tenant-a", "USER");
        ApiSecurityProperties.UserAccount maintenanceOnly = user("maintenance", "pw", "tenant-a", "USER");
        maintenanceOnly.setPermissions("MAINTENANCE");
        ApiSecurityProperties.UserAccount baselineAdmin = user("baseline-admin", "pw", "tenant-a", "USER");
        baselineAdmin.setPermissions("OPS_ADMIN,KB_READ");
        properties.getUsers().add(operator);
        properties.getUsers().add(reader);
        properties.getUsers().add(maintenanceOnly);
        properties.getUsers().add(baselineAdmin);
        ApiTokenService tokenService = new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);

        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/import");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/documents");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/documents/batch");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/chat");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/retrieve");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/reindex");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/ingest-jobs/job/retry");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/rag-eval/cases");
        assertAllowed(filter, tokenService, "operator", "PATCH", "/api/v1/knowledge-bases/kb/rag-eval/cases/case");
        assertAllowed(filter, tokenService, "operator", "DELETE", "/api/v1/knowledge-bases/kb/rag-eval/cases/case");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs");
        assertAllowed(filter, tokenService, "operator", "PATCH", "/api/v1/knowledge-bases/kb/rag-eval/policy");
        assertAllowed(filter, tokenService, "baseline-admin", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs/run/baseline");
        assertAllowed(filter, tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/retrieval-profiles");
        assertAllowed(filter, tokenService, "baseline-admin", "POST", "/api/v1/knowledge-bases/kb/retrieval-profiles/profile/activate");
        assertAllowed(filter, tokenService, "operator", "DELETE", "/api/v1/knowledge-bases/kb");
        assertAllowed(filter, tokenService, "operator", "DELETE", "/api/v1/knowledge-bases/kb/chat-sessions/session");

        MockHttpServletRequest createKb = bearerRequest(tokenService, "reader", "POST", "/api/v1/knowledge-bases");
        MockHttpServletResponse createKbResponse = new MockHttpServletResponse();
        filter.doFilter(createKb, createKbResponse, mock(FilterChain.class));

        MockHttpServletRequest reindex = bearerRequest(tokenService, "reader", "POST", "/api/v1/knowledge-bases/kb/reindex");
        MockHttpServletResponse reindexResponse = new MockHttpServletResponse();
        filter.doFilter(reindex, reindexResponse, mock(FilterChain.class));

        MockHttpServletRequest importKb = bearerRequest(tokenService, "reader", "POST", "/api/v1/knowledge-bases/import");
        MockHttpServletResponse importKbResponse = new MockHttpServletResponse();
        filter.doFilter(importKb, importKbResponse, mock(FilterChain.class));

        MockHttpServletRequest createEval = bearerRequest(tokenService, "reader", "POST", "/api/v1/knowledge-bases/kb/rag-eval/cases");
        MockHttpServletResponse createEvalResponse = new MockHttpServletResponse();
        filter.doFilter(createEval, createEvalResponse, mock(FilterChain.class));

        MockHttpServletRequest runEval = bearerRequest(tokenService, "reader", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs");
        MockHttpServletResponse runEvalResponse = new MockHttpServletResponse();
        filter.doFilter(runEval, runEvalResponse, mock(FilterChain.class));

        MockHttpServletRequest maintenanceWithoutRead = bearerRequest(
                tokenService, "maintenance", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs");
        MockHttpServletResponse maintenanceWithoutReadResponse = new MockHttpServletResponse();
        filter.doFilter(maintenanceWithoutRead, maintenanceWithoutReadResponse, mock(FilterChain.class));

        MockHttpServletRequest promoteBaseline = bearerRequest(
                tokenService, "reader", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs/run/baseline");
        MockHttpServletResponse promoteBaselineResponse = new MockHttpServletResponse();
        filter.doFilter(promoteBaseline, promoteBaselineResponse, mock(FilterChain.class));

        MockHttpServletRequest maintenancePromoteWithoutRead = bearerRequest(
                tokenService, "maintenance", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs/run/baseline");
        MockHttpServletResponse maintenancePromoteWithoutReadResponse = new MockHttpServletResponse();
        filter.doFilter(maintenancePromoteWithoutRead, maintenancePromoteWithoutReadResponse, mock(FilterChain.class));

        MockHttpServletRequest operatorPromote = bearerRequest(
                tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/rag-eval/runs/run/baseline");
        MockHttpServletResponse operatorPromoteResponse = new MockHttpServletResponse();
        filter.doFilter(operatorPromote, operatorPromoteResponse, mock(FilterChain.class));

        MockHttpServletRequest readerCreateProfile = bearerRequest(
                tokenService, "reader", "POST", "/api/v1/knowledge-bases/kb/retrieval-profiles");
        MockHttpServletResponse readerCreateProfileResponse = new MockHttpServletResponse();
        filter.doFilter(readerCreateProfile, readerCreateProfileResponse, mock(FilterChain.class));

        MockHttpServletRequest operatorActivateProfile = bearerRequest(
                tokenService, "operator", "POST", "/api/v1/knowledge-bases/kb/retrieval-profiles/profile/activate");
        MockHttpServletResponse operatorActivateProfileResponse = new MockHttpServletResponse();
        filter.doFilter(operatorActivateProfile, operatorActivateProfileResponse, mock(FilterChain.class));

        assertThat(createKbResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(createKbResponse.getContentAsString()).contains("permission required: KB_WRITE");
        assertThat(reindexResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(reindexResponse.getContentAsString()).contains("permission required: MAINTENANCE");
        assertThat(importKbResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(importKbResponse.getContentAsString()).contains("permission required: KB_WRITE");
        assertThat(createEvalResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(createEvalResponse.getContentAsString()).contains("permission required: KB_WRITE");
        assertThat(runEvalResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(runEvalResponse.getContentAsString()).contains("permission required: MAINTENANCE");
        assertThat(maintenanceWithoutReadResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(maintenanceWithoutReadResponse.getContentAsString()).contains("permission required: KB_READ");
        assertThat(promoteBaselineResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(maintenancePromoteWithoutReadResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(operatorPromoteResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(readerCreateProfileResponse.getContentAsString()).contains("permission required: KB_WRITE");
        assertThat(operatorActivateProfileResponse.getContentAsString()).contains("permission required: OPS_ADMIN");
        assertThat(SecurityContext.hasPermission(null)).isFalse();
        assertThat(SecurityContext.hasPermission(" ")).isFalse();
    }

    @Test
    void auditWebhookNotificationRequiresDedicatedDispatchPermission() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        ApiSecurityProperties.UserAccount auditOnly = user("audit-only", "pw", "tenant-a", "USER");
        auditOnly.setPermissions("OPS_ADMIN,OPS_AUDIT_READ");
        ApiSecurityProperties.UserAccount notifier = user("notifier", "pw", "tenant-a", "USER");
        notifier.setPermissions("OPS_ADMIN,OPS_AUDIT_READ,OPS_ALERT_NOTIFY");
        properties.getUsers().add(auditOnly);
        properties.getUsers().add(notifier);
        ApiTokenService tokenService = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, tokenService);

        MockHttpServletRequest forbidden = bearerRequest(
                tokenService, "audit-only", "POST", "/api/v1/ops/audit-alerts/notify");
        MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
        filter.doFilter(forbidden, forbiddenResponse, mock(FilterChain.class));

        assertThat(forbiddenResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(forbiddenResponse.getContentAsString()).contains("permission required: OPS_ALERT_NOTIFY");
        assertAllowed(filter, tokenService, "notifier", "POST", "/api/v1/ops/audit-alerts/notify");
    }

    @Test
    void apiKeyFilterLeavesInternalKeyAuthenticationIndependentFromBearerAccounts() throws Exception {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setInternalKey("internal-key");
        properties.setAuthSecret("test-secret");
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                properties,
                new ApiTokenService(properties, Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC))
        );
        AtomicBoolean contextObserved = new AtomicBoolean(false);
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(SecurityContext.getPrincipal()).isEqualTo("internal");
            assertThat(SecurityContext.hasRole("INTERNAL")).isTrue();
            contextObserved.set(true);
        };

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ingest/status");
        request.addHeader(ApiKeyAuthFilter.INTERNAL_API_KEY_HEADER, "internal-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(contextObserved).isTrue();
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void tenantContextFilterAcceptsSafeTenantHeaderAndClearsAfterRequest() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-a_01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(TenantContextFilter.TENANT_HEADER)).isEqualTo("tenant-a_01");
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    @Test
    void tenantContextFilterPreservesTenantAlreadyBoundByToken() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "spoofed-tenant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        TenantContext.setTenantId("token-tenant");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(TenantContextFilter.TENANT_HEADER)).isEqualTo("token-tenant");
        assertThat(TenantContext.getTenantId()).isEqualTo("token-tenant");
        TenantContext.clear();
    }

    @Test
    void tenantContextFilterRejectsUnsafeTenantHeader() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "../bad");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getContentAsString()).contains("Invalid tenant id");
    }

    @Test
    void tenantContextDefaultsBlankOrNullTenantIds() {
        try {
            TenantContext.setTenantId(null);
            assertThat(TenantContext.getTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
            assertThat(TenantContext.hasTenantId()).isTrue();

            TenantContext.setTenantId(" ");
            assertThat(TenantContext.getTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void securityContextHandlesEmptyInputsAndScopedKnowledgeBases() {
        try {
            SecurityContext.clear();
            assertThat(SecurityContext.getRole()).isNull();
            assertThat(SecurityContext.hasRole("ADMIN")).isFalse();
            assertThat(SecurityContext.canAccessKnowledgeBase("kb-a")).isFalse();

            SecurityContext.set(
                    "alice",
                    null,
                    List.of("kb-read", " ", "chat-write"),
                    List.of(" kb-a ", " ")
            );

            assertThat(SecurityContext.getRole()).isEqualTo("USER");
            assertThat(SecurityContext.hasPermission("kb-read")).isTrue();
            assertThat(SecurityContext.hasPermission("document-delete")).isFalse();
            assertThat(SecurityContext.canAccessKnowledgeBase("kb-a")).isTrue();
            assertThat(SecurityContext.canAccessKnowledgeBase("kb-b")).isFalse();
        } finally {
            SecurityContext.clear();
        }
    }

    @Test
    void uploadRateLimitRejectsOnlyUploadRequestsAfterQuotaIsExceeded() throws Exception {
        UploadRateLimitProperties properties = new UploadRateLimitProperties();
        properties.setRequests(1);
        properties.setWindowSeconds(60);
        UploadRateLimitFilter filter = new UploadRateLimitFilter(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );
        FilterChain firstChain = mock(FilterChain.class);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents/batch");
        first.setRemoteAddr("127.0.0.1");
        first.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, firstChain);

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents/batch");
        second.setRemoteAddr("127.0.0.1");
        second.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, mock(FilterChain.class));

        MockHttpServletRequest listDocs = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases/kb/documents");
        MockHttpServletResponse listResponse = new MockHttpServletResponse();
        FilterChain listChain = mock(FilterChain.class);
        filter.doFilter(listDocs, listResponse, listChain);

        verify(firstChain).doFilter(first, firstResponse);
        verify(listChain).doFilter(listDocs, listResponse);
        assertThat(secondResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(secondResponse.getContentAsString()).contains("rate_limited");
    }

    @Test
    void uploadRateLimitUsesForwardedIpAndCanBeDisabled() throws Exception {
        UploadRateLimitProperties properties = new UploadRateLimitProperties();
        properties.setRequests(1);
        properties.setWindowSeconds(0);
        UploadRateLimitFilter filter = new UploadRateLimitFilter(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
        first.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
        first.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        FilterChain firstChain = mock(FilterChain.class);
        filter.doFilter(first, firstResponse, firstChain);

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
        second.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
        second.addHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER, "public-key");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, mock(FilterChain.class));

        properties.setEnabled(false);
        MockHttpServletRequest disabled = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
        MockHttpServletResponse disabledResponse = new MockHttpServletResponse();
        FilterChain disabledChain = mock(FilterChain.class);
        filter.doFilter(disabled, disabledResponse, disabledChain);

        verify(firstChain).doFilter(first, firstResponse);
        verify(disabledChain).doFilter(disabled, disabledResponse);
        assertThat(secondResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void uploadRateLimitScopesAuthenticatedUsersWithinTenant() throws Exception {
        UploadRateLimitProperties properties = new UploadRateLimitProperties();
        properties.setRequests(1);
        properties.setWindowSeconds(60);
        UploadRateLimitFilter filter = new UploadRateLimitFilter(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );

        try {
            TenantContext.setTenantId("tenant-a");
            SecurityContext.set("alice", "USER");
            MockHttpServletRequest aliceFirst = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
            aliceFirst.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse aliceFirstResponse = new MockHttpServletResponse();
            FilterChain aliceFirstChain = mock(FilterChain.class);
            filter.doFilter(aliceFirst, aliceFirstResponse, aliceFirstChain);

            SecurityContext.set("bob", "USER");
            MockHttpServletRequest bobFirst = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
            bobFirst.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse bobFirstResponse = new MockHttpServletResponse();
            FilterChain bobFirstChain = mock(FilterChain.class);
            filter.doFilter(bobFirst, bobFirstResponse, bobFirstChain);

            SecurityContext.set("alice", "USER");
            MockHttpServletRequest aliceSecond = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/kb/documents");
            aliceSecond.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse aliceSecondResponse = new MockHttpServletResponse();
            filter.doFilter(aliceSecond, aliceSecondResponse, mock(FilterChain.class));

            verify(aliceFirstChain).doFilter(aliceFirst, aliceFirstResponse);
            verify(bobFirstChain).doFilter(bobFirst, bobFirstResponse);
            assertThat(aliceSecondResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        } finally {
            SecurityContext.clear();
            TenantContext.clear();
        }
    }

    @Test
    void propertyBeansAndWebClientConfigExposeMutableSettings() {
        LlmProperties llm = new LlmProperties();
        llm.getChat().setBaseUrl("chat-url");
        llm.getChat().setApiKey("chat-key");
        llm.getChat().setModel("chat-model");
        llm.getEmbedding().setBaseUrl("embed-url");
        llm.getEmbedding().setApiKey("embed-key");
        llm.getEmbedding().setModel("embed-model");
        llm.getEmbedding().setDimension(12);
        MinioProperties minio = new MinioProperties();
        minio.setEndpoint("http://minio");
        minio.setAccessKey("ak");
        minio.setSecretKey("sk");
        minio.setBucket("bucket");
        MilvusProperties milvus = new MilvusProperties();
        milvus.setHost("localhost");
        milvus.setPort(19530);
        milvus.setCollection("collection");
        UploadRateLimitProperties uploadRateLimit = new UploadRateLimitProperties();
        uploadRateLimit.setEnabled(false);
        uploadRateLimit.setRequests(7);
        uploadRateLimit.setWindowSeconds(30);
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);

        assertThat(llm.getChat().getBaseUrl()).isEqualTo("chat-url");
        assertThat(llm.getEmbedding().getDimension()).isEqualTo(12);
        assertThat(minio.getBucket()).isEqualTo("bucket");
        assertThat(milvus.getCollection()).isEqualTo("collection");
        assertThat(uploadRateLimit.isEnabled()).isFalse();
        assertThat(uploadRateLimit.getRequests()).isEqualTo(7);
        assertThat(uploadRateLimit.getWindowSeconds()).isEqualTo(30);
        assertThat(rag.getMaxContextChars()).isEqualTo(1000);
        assertThat(new WebClientConfig().webClientBuilder().build()).isNotNull();
    }

    private static ApiSecurityProperties.UserAccount user(String username, String password, String tenantId, String role) {
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username);
        user.setPassword(password);
        user.setTenantId(tenantId);
        user.setRole(role);
        return user;
    }

    private static void assertAllowed(ApiKeyAuthFilter filter, ApiTokenService tokenService, String username, String method, String uri) throws Exception {
        MockHttpServletRequest request = bearerRequest(tokenService, username, method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContext.getPrincipal()).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
    }

    private static MockHttpServletRequest bearerRequest(ApiTokenService tokenService, String username, String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + tokenService.issueToken(username, "tenant-a", "USER"));
        return request;
    }

    @Test
    void minioConfigBuildsClientAndMilvusConfigClosesSafelyWhenUnused() {
        MinioProperties minio = new MinioProperties();
        minio.setEndpoint("http://localhost:9000");
        minio.setAccessKey("ak");
        minio.setSecretKey("sk");
        MinioClient minioClient = new MinioConfig().minioClient(minio);
        assertThat(minioClient).isNotNull();

        MilvusConfig milvusConfig = new MilvusConfig();
        milvusConfig.close();
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        ReflectionTestUtils.setField(milvusConfig, "client", milvusClient);
        milvusConfig.close();
        verify(milvusClient).close();
    }

    @Test
    void globalExceptionHandlerMapsNotFoundValidationAndGenericErrors() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var notFound = handler.handleNotFound(new ResourceNotFoundException("missing"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().getError()).isEqualTo("not_found");
        assertThat(notFound.getBody().getMessage()).isEqualTo("missing");

        Object target = new Object();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "target");
        binding.addError(new FieldError("target", "name", "must not be blank"));
        MethodArgumentNotValidException validationEx = new MethodArgumentNotValidException(null, binding);
        var validation = handler.handleValidation(validationEx);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validation.getBody().getError()).isEqualTo("validation_error");
        assertThat(validation.getBody().getMessage()).isEqualTo("must not be blank");

        var badRequest = handler.handleBadRequest(new IllegalArgumentException("bad input"));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getBody().getError()).isEqualTo("bad_request");
        assertThat(badRequest.getBody().getMessage()).isEqualTo("bad input");

        IllegalStateException providerFailure = new IllegalStateException("provider down");
        com.dupi.rag.exception.ChatPipelineException chatException =
                new com.dupi.rag.exception.ChatPipelineException(
                        "llm", "answer generation failed", "Check CHAT_API_KEY.", providerFailure);
        var chatFailure = handler.handleChatPipeline(chatException);
        assertThat(chatException.getStage()).isEqualTo("llm");
        assertThat(chatException.getSuggestion()).isEqualTo("Check CHAT_API_KEY.");
        assertThat(chatException.getCause()).isSameAs(providerFailure);
        assertThat(chatFailure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(chatFailure.getBody().getError()).isEqualTo("chat_pipeline_error");
        assertThat(chatFailure.getBody().getStage()).isEqualTo("llm");
        assertThat(chatFailure.getBody().getSuggestion()).isEqualTo("Check CHAT_API_KEY.");

        var generic = handler.handleGeneric(new RuntimeException());
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(generic.getBody().getMessage()).isEqualTo("Unexpected error");
    }
}
