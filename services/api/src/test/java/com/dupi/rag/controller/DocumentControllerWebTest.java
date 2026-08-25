package com.dupi.rag.controller;

import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.service.DocumentAssetService;
import com.dupi.rag.service.DocumentIndexInspectionService;
import com.dupi.rag.service.DocumentService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.MarkdownPackageService;
import com.dupi.rag.config.ApiKeyAuthFilter;
import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.exception.GlobalExceptionHandler;
import com.dupi.rag.exception.OperationConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerWebTest {

    @Test
    void deletingKnowledgeBaseChildReadsRequireAuthAndReturnConflictInsteadOfNotFound() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentController controller = new DocumentController(documents, mock(IngestJobService.class),
                mock(DocumentIndexInspectionService.class), mock(MarkdownPackageService.class),
                mock(DocumentAssetService.class));
        ApiSecurityProperties security = new ApiSecurityProperties();
        security.setAuthSecret("document-controller-test-secret");
        var reader = new ApiSecurityProperties.UserAccount();
        reader.setUsername("reader"); reader.setPassword("unused"); reader.setTenantId("tenant-a");
        reader.setRole("USER"); reader.setPermissions("KB_READ");
        security.getUsers().add(reader);
        ApiTokenService tokens = new ApiTokenService(security);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ApiKeyAuthFilter(security, tokens)).build();
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(documents.get(kbId, docId)).thenThrow(
                new OperationConflictException("Knowledge base deletion is in progress"));
        when(documents.findOrThrow(kbId, docId)).thenThrow(
                new OperationConflictException("Knowledge base deletion is in progress"));

        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents/{docId}", kbId, docId))
                .andExpect(status().isUnauthorized());
        String token = tokens.issueToken("reader", "tenant-a", "USER");
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents/{docId}/assets", kbId, docId)
                .param("path", "images/a.png")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void markdownPackageReturnsAcceptedPublicOperationDto() throws Exception {
        MarkdownPackageService markdown = mock(MarkdownPackageService.class);
        DocumentController controller = new DocumentController(mock(DocumentService.class), mock(IngestJobService.class),
                mock(DocumentIndexInspectionService.class), markdown, mock(DocumentAssetService.class));
        UUID kbId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "docs.zip", "application/zip", "zip".getBytes());
        when(markdown.upload(any(), any(), any())).thenReturn(OperationJobResponse.builder()
                .id(jobId).operationType(OperationType.MARKDOWN_PACKAGE_IMPORT)
                .aggregateType("KNOWLEDGE_BASE").aggregateId(kbId).status(OperationStatus.PREPARED).build());

        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart(
                        "/api/v1/knowledge-bases/{kbId}/documents/markdown-package", kbId)
                        .file(file).header("Idempotency-Key", "same-key")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.operationType").value("MARKDOWN_PACKAGE_IMPORT"))
                .andExpect(jsonPath("$.documents").doesNotExist());
    }
}
