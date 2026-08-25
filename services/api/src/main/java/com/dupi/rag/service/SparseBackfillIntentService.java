package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.SparseMigrationState;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

/** Short transaction boundary around sparse backfill intent and completion state. */
@Service
@RequiredArgsConstructor
class SparseBackfillIntentService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final SparseMigrationRepository migrations;
    private final RetrievalProfileRepository profiles;
    private final ChunkRepository chunks;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SparseBackfillIntent begin(String tenantId, UUID knowledgeBaseId, UUID migrationId) {
        KnowledgeBase locked = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(knowledgeBaseId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + knowledgeBaseId));
        KnowledgeBaseLifecyclePolicy.requireReady(locked, knowledgeBaseId);
        SparseMigration migration = migrations.findByIdAndKbId(migrationId, knowledgeBaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Sparse migration not found: " + migrationId));
        requireState(migration, SparseMigrationState.PREPARING,
                SparseMigrationState.BACKFILLING, SparseMigrationState.FAILED);
        RetrievalProfile profile = profiles.findByIdAndKbId(migration.getProfileId(), knowledgeBaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Retrieval profile not found: " + migration.getProfileId()));
        long sourceCount = chunks.countByKbId(knowledgeBaseId);
        migration.setState(SparseMigrationState.BACKFILLING);
        migration.setSourceChunkCount(sourceCount);
        migration.setErrorMessage(null);
        migrations.saveAndFlush(migration);
        return new SparseBackfillIntent(migration, profile, locked.getEmbeddingDimension(), sourceCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SparseMigration complete(UUID knowledgeBaseId, UUID migrationId, long indexedCount, int dimension) {
        SparseMigration migration = locked(knowledgeBaseId, migrationId);
        requireState(migration, SparseMigrationState.BACKFILLING);
        migration.setIndexedChunkCount(indexedCount);
        migration.setExpectedDimension(dimension);
        migration.setActualDimension(dimension);
        migration.setState(SparseMigrationState.DUAL_WRITING);
        migration.setErrorMessage(null);
        return migrations.saveAndFlush(migration);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SparseMigration fail(UUID knowledgeBaseId, UUID migrationId, String diagnostic) {
        SparseMigration migration = locked(knowledgeBaseId, migrationId);
        requireState(migration, SparseMigrationState.BACKFILLING);
        migration.setState(SparseMigrationState.FAILED);
        migration.setErrorMessage(limit(diagnostic));
        return migrations.saveAndFlush(migration);
    }

    private SparseMigration locked(UUID knowledgeBaseId, UUID migrationId) {
        return migrations.findByIdAndKbId(migrationId, knowledgeBaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Sparse migration not found: " + migrationId));
    }

    private void requireState(SparseMigration migration, SparseMigrationState... states) {
        if (!Arrays.asList(states).contains(migration.getState())) {
            throw new IllegalArgumentException("Invalid sparse migration transition from " + migration.getState());
        }
    }

    private String limit(String diagnostic) {
        String text = diagnostic == null || diagnostic.isBlank() ? "Sparse backfill failed" : diagnostic;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
