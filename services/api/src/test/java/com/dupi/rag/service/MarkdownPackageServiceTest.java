package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.dto.DocumentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkdownPackageServiceTest {

    @Test
    void uploadsMarkdownThroughTheExistingPipelineAndRegistersReferencedImages() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        DocumentAssetService assetService = mock(DocumentAssetService.class);
        MarkdownPackageService service = new MarkdownPackageService(documentService, assetService);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        DocumentResponse response = DocumentResponse.builder().id(docId).kbId(kbId).build();
        Document document = Document.builder().id(docId).kbId(kbId).build();
        when(documentService.upload(eq(kbId), any())).thenReturn(response);
        when(documentService.findOrThrow(kbId, docId)).thenReturn(document);

        MockMultipartFile archive = zip(
                "docs/guide.md", "## Guide\n\n![diagram](../image/flow-chart.png)",
                "image/flow-chart.png", "png-bytes"
        );

        var result = service.upload(kbId, archive);

        assertThat(result.getDocuments()).containsExactly(response);
        assertThat(result.getAssetCount()).isEqualTo(1);
        verify(assetService).register(
                eq(document), eq("../image/flow-chart.png"), eq("image/flow-chart.png"),
                eq("image/png"), eq("png-bytes".getBytes())
        );
    }

    @Test
    void rejectsZipSlipPathsBeforeUploadingDocuments() throws Exception {
        MarkdownPackageService service = new MarkdownPackageService(
                mock(DocumentService.class), mock(DocumentAssetService.class));

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), zip("../guide.md", "unsafe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe path");
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
    }
}
