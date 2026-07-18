package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dupi.upload-quota")
public class UploadQuotaProperties {
    private boolean enabled = true;
    private long retainedBytesLimit = 1024L * 1024L * 1024L;
    private long retainedDocumentsLimit = 1000L;
    private long windowBytesLimit = 256L * 1024L * 1024L;
    private long windowSeconds = 3600L;
    private long attemptLeaseSeconds = 300L;
    private int reconciliationBatchSize = 50;
    private String reconciliationCron = "0 */5 * * * *";
}
