package com.dupi.rag.controller;

import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.service.DocumentAssetService;
import com.dupi.rag.service.DocumentIndexInspectionService;
import com.dupi.rag.service.DocumentService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.MarkdownPackageService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerWebTest {

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
