package com.dupi.rag.service;

import com.dupi.rag.dto.OperationJobResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkdownPackageServiceTest {

    @Test
    void unresolvedLocalImageFailsTheWholePlan() throws Exception {
        MarkdownPackageParser parser = new MarkdownPackageParser();
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();

        assertThatThrownBy(() -> parser.parse(jobId, kbId,
                zip("a.md", "![x](missing.png)", "b.md", "valid").getInputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.png");
    }

    @Test
    void missingReferenceStyleImageFailsBeforeIntakeMutation() throws Exception {
        MarkdownImportIntakeService intake = mock(MarkdownImportIntakeService.class);
        MarkdownPackageService service = new MarkdownPackageService(new MarkdownPackageParser(), intake,
                mock(KnowledgeBaseService.class), mock(KnowledgeBaseMaintenanceService.class));

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), zip(
                "guide.md", "![logo][brand]\n\n[brand]: images/missing.png"), "same-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("images/missing.png");

        verify(intake, never()).submit(any(), any(), any());
    }

    @Test
    void referenceStyleAndBalancedOrEscapedInlineTargetsArePlanned() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "![logo][brand]\n![balanced](images/chart_(final).png)\n"
                        + "![escaped](images/chart_\\(draft\\).png)\n\n[brand]: images/logo.png \"Logo\"",
                "images/logo.png", "logo",
                "images/chart_(final).png", "final",
                "images/chart_(draft).png", "draft").getInputStream());

        assertThat(plan.documents().get(0).assets())
                .extracting(MarkdownImportPlan.Asset::sourcePath)
                .containsExactly("images/logo.png", "images/chart_(final).png", "images/chart_(draft).png");
    }

    @Test
    void fencedReferenceDefinitionsDoNotResolveOutsideImageExamples() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "![demo]\n\n```text\n[demo]: images/missing.png\n```")
                .getInputStream());

        assertThat(plan.documents().get(0).assets()).isEmpty();
    }

    @Test
    void fencedImageTokensDoNotUseOutsideDefinitions() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "```markdown\n![demo][brand]\n![inline](images/missing-inline.png)\n```\n\n"
                        + "[brand]: images/missing-reference.png")
                .getInputStream());

        assertThat(plan.documents().get(0).assets()).isEmpty();
    }

    @Test
    void inlineCodeImageExamplesDoNotCreateDependencies() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "Use `![inline](images/missing-inline.png)` or "
                        + "``![demo][brand]``.\n\n[brand]: images/missing-reference.png")
                .getInputStream());

        assertThat(plan.documents().get(0).assets()).isEmpty();
    }

    @Test
    void escapedBackticksDoNotHideGenuineImages() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "\\`before ![logo](images/logo.png) \\`after",
                "images/logo.png", "logo").getInputStream());

        assertThat(plan.documents().get(0).assets())
                .extracting(MarkdownImportPlan.Asset::sourcePath)
                .containsExactly("images/logo.png");
    }

    @Test
    void topLevelFenceIgnoresListLookingFenceContentUntilItsRealClose() throws Exception {
        assertOnlyGenuineImageIsPlanned("```markdown\n- ```\n"
                + "![example](images/missing-example.png)\n```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void blockquoteFenceIgnoresNestedListLookingFenceContentUntilItsRealClose() throws Exception {
        assertOnlyGenuineImageIsPlanned("> ```markdown\n> - ```\n"
                + "> ![example](images/missing-example.png)\n> ```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void listFenceIgnoresNestedListLookingFenceContentUntilItsRealClose() throws Exception {
        assertOnlyGenuineImageIsPlanned("- ```markdown\n  - ```\n"
                + "  ![example](images/missing-example.png)\n  ```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void topLevelFenceAcceptsIndentedCloseAfterUnindentedOpen() throws Exception {
        assertOnlyGenuineImageIsPlanned("```markdown\n"
                + "![example](images/missing-example.png)\n  ```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void topLevelFenceAcceptsUnindentedCloseAfterIndentedOpen() throws Exception {
        assertOnlyGenuineImageIsPlanned("  ```markdown\n"
                + "  ![example](images/missing-example.png)\n```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void blockquoteFenceAcceptsIndependentlyIndentedClose() throws Exception {
        assertOnlyGenuineImageIsPlanned("> ```markdown\n"
                + "> ![example](images/missing-example.png)\n>   ```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void listFenceAcceptsIndependentlyIndentedClose() throws Exception {
        assertOnlyGenuineImageIsPlanned("- ```markdown\n"
                + "  ![example](images/missing-example.png)\n    ```\n"
                + "![genuine](images/genuine.png)");
    }

    @Test
    void backslashBeforeClosingBacktickDoesNotHideFollowingGenuineImage() throws Exception {
        assertOnlyGenuineImageIsPlanned("`example \\` ![genuine](images/genuine.png)`");
    }

    @Test
    void imageBeforeBackslashPrefixedClosingBacktickRemainsMasked() throws Exception {
        assertOnlyGenuineImageIsPlanned("`![example](images/missing-example.png) \\` "
                + "![genuine](images/genuine.png)`");
    }

    @Test
    void genuineContainerReferencesAndBalancedTargetsRemainPlanned() throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", "> ![logo][brand]\n>\n> [brand]: images/logo.png\n\n"
                        + "- ![balanced](images/chart_(final).png)",
                "images/logo.png", "logo",
                "images/chart_(final).png", "chart").getInputStream());

        assertThat(plan.documents().get(0).assets())
                .extracting(MarkdownImportPlan.Asset::sourcePath)
                .containsExactly("images/logo.png", "images/chart_(final).png");
    }

    @Test
    void validPackageCreatesOneImmutableIntakeAfterFullValidation() throws Exception {
        MarkdownPackageParser parser = new MarkdownPackageParser();
        MarkdownImportIntakeService intake = mock(MarkdownImportIntakeService.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        KnowledgeBaseMaintenanceService maintenance = mock(KnowledgeBaseMaintenanceService.class);
        MarkdownPackageService service = new MarkdownPackageService(parser, intake, knowledgeBases, maintenance);
        UUID kbId = UUID.randomUUID();
        OperationJobResponse response = OperationJobResponse.builder().id(UUID.randomUUID()).build();
        when(intake.submit(any(), org.mockito.ArgumentMatchers.eq("same-key"), any())).thenReturn(response);

        MockMultipartFile archive = zip(
                "docs/guide.md", "## Guide\n\n![diagram](../image/flow-chart.png)",
                "image/flow-chart.png", "png-bytes"
        );

        var result = service.upload(kbId, archive, "same-key");

        assertThat(result).isSameAs(response);
        verify(intake).submit(any(MarkdownImportPlan.class), org.mockito.ArgumentMatchers.eq("same-key"), any());
    }

    @Test
    void rejectsZipSlipPathsBeforeUploadingDocuments() throws Exception {
        MarkdownImportIntakeService intake = mock(MarkdownImportIntakeService.class);
        MarkdownPackageService service = new MarkdownPackageService(new MarkdownPackageParser(), intake,
                mock(KnowledgeBaseService.class), mock(KnowledgeBaseMaintenanceService.class));

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), zip("../guide.md", "unsafe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe path");
        verify(intake, never()).submit(any(), any(), any());
    }

    @Test
    void normalizedDuplicatesAndUnsupportedTypesAreRejected() throws Exception {
        MarkdownPackageParser parser = new MarkdownPackageParser();
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        assertThatThrownBy(() -> parser.parse(jobId, kbId,
                zip("docs/../guide.md", "one", "guide.md", "two").getInputStream()))
                .hasMessageContaining("duplicate path");
        assertThatThrownBy(() -> parser.parse(jobId, kbId,
                zip("guide.md", "ok", "script.exe", "bad").getInputStream()))
                .hasMessageContaining("unsupported file type");
    }

    @Test
    void idsAndKeysAreDeterministicForTheSameJobAndNormalizedPaths() throws Exception {
        MarkdownPackageParser parser = new MarkdownPackageParser();
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        MockMultipartFile archive = zip("docs/guide.md", "![x](../images/x.png)", "images/x.png", "png");
        MarkdownImportPlan first = parser.parse(jobId, kbId, archive.getInputStream());
        MarkdownImportPlan second = parser.parse(jobId, kbId, archive.getInputStream());

        assertThat(first.toInput()).isEqualTo(second.toInput());
        assertThat(first.documents().get(0).objectKey()).startsWith(kbId + "/");
        assertThat(first.documents().get(0).assets()).hasSize(1);
    }

    @Test
    void externalImagesAreIgnoredWhileEncodedTraversalIsRejected() throws Exception {
        MarkdownPackageParser parser = new MarkdownPackageParser();
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        MarkdownImportPlan external = parser.parse(jobId, kbId,
                zip("guide.md", "![a](https://example/x.png) ![b](//cdn/x.png)").getInputStream());
        assertThat(external.documents().get(0).assets()).isEmpty();

        assertThatThrownBy(() -> parser.parse(jobId, kbId,
                zip("guide.md", "![x](%2e%2e/out.png)", "out.png", "png").getInputStream()))
                .hasMessageContaining("outside the package");
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

    private static void assertOnlyGenuineImageIsPlanned(String markdown) throws Exception {
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(UUID.randomUUID(), UUID.randomUUID(), zip(
                "guide.md", markdown, "images/genuine.png", "genuine").getInputStream());

        assertThat(plan.documents().get(0).assets())
                .extracting(MarkdownImportPlan.Asset::sourcePath)
                .containsExactly("images/genuine.png");
    }
}
