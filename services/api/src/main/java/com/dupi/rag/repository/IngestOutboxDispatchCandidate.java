package com.dupi.rag.repository;

import java.util.UUID;

public record IngestOutboxDispatchCandidate(UUID eventId, UUID jobId, UUID documentId) { }
