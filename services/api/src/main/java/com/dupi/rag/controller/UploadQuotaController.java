package com.dupi.rag.controller;

import com.dupi.rag.dto.UploadQuotaResponse;
import com.dupi.rag.service.UploadQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/upload-quota")
@RequiredArgsConstructor
public class UploadQuotaController {

    private final UploadQuotaService uploadQuotaService;

    @GetMapping
    public UploadQuotaResponse getUploadQuota() {
        return uploadQuotaService.snapshot();
    }
}
