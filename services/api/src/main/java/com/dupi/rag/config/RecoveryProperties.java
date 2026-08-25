package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.recovery")
@Getter
@Setter
public class RecoveryProperties {
    private String bucket = "dupi-recovery";
    private int quiescenceTimeoutSeconds = 300;
    private int pageSize = 500;
    private int maxConcurrentJobs = 2;
    private long maxImportZipBytes = 1024L * 1024 * 1024;
    private long maxImportUncompressedBytes = 8L * 1024 * 1024 * 1024;
    private long maxImportEntryBytes = 4L * 1024 * 1024 * 1024;
    private int maxImportEntries = 20_000;
    private int maxImportCompressionRatio = 100;
}
