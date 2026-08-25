package com.dupi.rag.service;
import java.util.UUID;
record OperationStagingCleanupClaim(UUID attemptId, UUID token, String storageType, String objectKey) { }
