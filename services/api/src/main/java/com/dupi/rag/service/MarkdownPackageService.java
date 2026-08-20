package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.dto.OperationJobResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** Validates the complete package first, then hands immutable intake to the durable workflow. */
@Service
@RequiredArgsConstructor
public class MarkdownPackageService {
    private final MarkdownPackageParser parser;
    private final MarkdownImportIntakeService intake;
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBaseMaintenanceService maintenance;

    public OperationJobResponse upload(UUID kbId, MultipartFile archive) {
        return upload(kbId, archive, null);
    }

    public OperationJobResponse upload(UUID kbId, MultipartFile archive, String idempotencyKey) {
        validateArchive(archive);
        maintenance.assertMutationAllowed(kbId);
        knowledgeBases.findOrThrow(kbId);
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "generated-" + UUID.randomUUID() : idempotencyKey.trim();
        UUID jobId = MarkdownImportIntakeService.jobId(TenantContext.getTenantId(), key);
        MarkdownImportPlan plan;
        try {
            plan = parser.parse(jobId, kbId, archive.getInputStream());
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("Unable to read Markdown package", unreadable);
        }
        String creator = SecurityContext.getPrincipal();
        return intake.submit(plan, key, creator == null || creator.isBlank() ? "anonymous" : creator);
    }

    private void validateArchive(MultipartFile archive) {
        if (archive == null || archive.isEmpty()) throw new IllegalArgumentException("Markdown package is empty");
        String fileName = archive.getOriginalFilename() == null ? "" : archive.getOriginalFilename();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Markdown package must be a ZIP file");
        }
    }
}
