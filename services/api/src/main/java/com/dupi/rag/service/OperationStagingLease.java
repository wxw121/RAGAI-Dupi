package com.dupi.rag.service;

import java.util.UUID;

record OperationStagingLease(UUID jobId, UUID token, long epoch) { }
