package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.config.RecoveryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationTransactionStructureTest {
    @Test
    void documentDeletionUsesTwoProxiedShortTransactionsAroundUnlockedObjectIo() throws Exception {
        var documents = mock(com.dupi.rag.repository.DocumentRepository.class);
        var ingestJobs = mock(com.dupi.rag.repository.IngestJobRepository.class);
        var knowledgeBases = mock(KnowledgeBaseService.class);
        var tombstones = mock(DocumentTombstoneService.class);
        var vectorTasks = mock(VectorCleanupTaskService.class);
        var profiles = mock(com.dupi.rag.repository.RetrievalProfileRepository.class);
        var chunks = mock(com.dupi.rag.repository.ChunkRepository.class);
        var assets = mock(DocumentAssetService.class);
        var quota = mock(UploadQuotaService.class);
        var profileState = mock(ProfileIndexStateService.class);
        var audit = mock(AuditLogService.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        UUID kbId = UUID.randomUUID();
        Document document = Document.builder()
                .id(UUID.randomUUID()).kbId(kbId).status(DocumentStatus.PENDING)
                .objectKey("objects/a.md").fileName("a.md").build();
        when(knowledgeBases.findForUpdateOrThrow(kbId))
                .thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));

        assertThat(DocumentService.class.getMethod("delete", UUID.class, UUID.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
        try (AnnotationConfigApplicationContext spring = transactionalContext(transactions)) {
            spring.registerBean(DocumentDeletionPersistenceService.class,
                    () -> new DocumentDeletionPersistenceService(
                            documents, ingestJobs, knowledgeBases, tombstones, vectorTasks, profiles,
                            chunks, assets, quota, profileState, audit));
            spring.refresh();

            DocumentDeletionPersistenceService persistence =
                    spring.getBean(DocumentDeletionPersistenceService.class);
            assertThat(AopUtils.isAopProxy(persistence)).isTrue();
            DocumentDeletionClaim claim = persistence.begin(kbId, document.getId());

            assertThat(transactions.isActive()).isFalse();
            Runnable externalObjectIo = mock(Runnable.class);
            doAnswer(call -> {
                assertThat(transactions.isActive()).isFalse();
                return null;
            }).when(externalObjectIo).run();
            externalObjectIo.run();

            persistence.complete(claim);

            assertThat(transactions.begins).isEqualTo(2);
            assertThat(transactions.commits).isEqualTo(2);
            assertThat(transactions.rollbacks).isZero();
        }
    }

    @Test
    void proxiedUploadPublicationRollsBackQuotaMetadataAndOutboxAsOneTransaction() {
        var knowledgeBases = mock(com.dupi.rag.repository.KnowledgeBaseRepository.class);
        var documents = mock(com.dupi.rag.repository.DocumentRepository.class);
        var jobs = mock(com.dupi.rag.repository.IngestJobRepository.class);
        var quota = mock(UploadQuotaService.class);
        var outbox = mock(IngestOutboxService.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        Document document = Document.builder().id(UUID.randomUUID()).kbId(kbId)
                .objectKey("objects/a.md").fileName("a.md").mimeType("text/markdown")
                .status(DocumentStatus.UPLOADING).build();
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(kbId).docId(document.getId())
                .status(IngestJobStatus.UPLOAD_INTENT).stage(IngestStage.UPLOAD_PENDING).build();
        UploadQuotaReservation reservation = UploadQuotaReservation.builder().id(UUID.randomUUID()).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(kb));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("outbox write failed"))
                .when(outbox).record(job, kb, document.getObjectKey(), document.getFileName(), document.getMimeType());

        try (AnnotationConfigApplicationContext spring = transactionalContext(transactions)) {
            spring.registerBean(com.dupi.rag.repository.KnowledgeBaseRepository.class, () -> knowledgeBases);
            spring.registerBean(com.dupi.rag.repository.DocumentRepository.class, () -> documents);
            spring.registerBean(com.dupi.rag.repository.IngestJobRepository.class, () -> jobs);
            spring.registerBean(UploadQuotaService.class, () -> quota);
            spring.registerBean(IngestOutboxService.class, () -> outbox);
            spring.registerBean(DocumentUploadIntentService.class,
                    () -> new DocumentUploadIntentService(knowledgeBases, documents, jobs, quota, outbox,
                            mock(DocumentTombstoneService.class)));
            spring.refresh();

            DocumentUploadIntentService publisher = spring.getBean(DocumentUploadIntentService.class);
            assertThat(AopUtils.isAopProxy(publisher)).isTrue();
            assertThatThrownBy(() -> publisher.publish("tenant-a", document, job, reservation))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outbox write failed");

            InOrder order = inOrder(knowledgeBases, jobs, documents, quota, outbox);
            order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a");
            order.verify(jobs).findByIdForUpdate(job.getId());
            order.verify(documents).findById(document.getId());
            order.verify(quota).commitInCurrentTransaction(reservation, document);
            order.verify(documents).save(document);
            order.verify(jobs).save(job);
            order.verify(outbox).record(job, kb, document.getObjectKey(), document.getFileName(), document.getMimeType());
            assertThat(transactions.begins).isEqualTo(1);
            assertThat(transactions.commits).isZero();
            assertThat(transactions.rollbacks).isEqualTo(1);
        }
    }

    @Test
    void lostCommitAcknowledgementIsReconciledAsPublishedBeforeAnyCompensation() {
        var knowledgeBases = mock(com.dupi.rag.repository.KnowledgeBaseRepository.class);
        var documents = mock(com.dupi.rag.repository.DocumentRepository.class);
        var jobs = mock(com.dupi.rag.repository.IngestJobRepository.class);
        var quota = mock(UploadQuotaService.class);
        var outbox = mock(IngestOutboxService.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        Document document = Document.builder().id(UUID.randomUUID()).kbId(kbId)
                .objectKey("objects/a.md").fileName("a.md").mimeType("text/markdown")
                .status(DocumentStatus.UPLOADING).build();
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(kbId).docId(document.getId())
                .status(IngestJobStatus.UPLOAD_INTENT).stage(IngestStage.UPLOAD_PENDING).build();
        UploadQuotaReservation reservation = UploadQuotaReservation.builder().id(UUID.randomUUID()).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(kb));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(outbox.hasDurableRecord(job.getId())).thenReturn(true);

        try (AnnotationConfigApplicationContext spring = transactionalContext(transactions)) {
            spring.registerBean(com.dupi.rag.repository.KnowledgeBaseRepository.class, () -> knowledgeBases);
            spring.registerBean(com.dupi.rag.repository.DocumentRepository.class, () -> documents);
            spring.registerBean(com.dupi.rag.repository.IngestJobRepository.class, () -> jobs);
            spring.registerBean(UploadQuotaService.class, () -> quota);
            spring.registerBean(IngestOutboxService.class, () -> outbox);
            spring.registerBean(DocumentUploadIntentService.class,
                    () -> new DocumentUploadIntentService(knowledgeBases, documents, jobs, quota, outbox,
                            mock(DocumentTombstoneService.class)));
            spring.refresh();

            DocumentUploadIntentService publisher = spring.getBean(DocumentUploadIntentService.class);
            transactions.loseNextCommitAcknowledgement();
            assertThatThrownBy(() -> publisher.publish("tenant-a", document, job, reservation))
                    .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                    .hasMessageContaining("acknowledgement");

            DocumentUploadPublicationResolution resolution = publisher.reconcilePublication(
                    "tenant-a", document, job);
            assertThat(resolution.isPublished()).isTrue();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
            assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PENDING);
            assertThat(transactions.commits).isEqualTo(2);
            assertThat(transactions.rollbacks).isZero();
            verify(outbox, never()).cancelPendingForJob(any(), anyString());
        }
    }

    @Test
    void proxiedReindexLocksBeforeMutatingManagedKnowledgeBase() {
        var documents = mock(com.dupi.rag.repository.DocumentRepository.class);
        var knowledgeBases = mock(com.dupi.rag.repository.KnowledgeBaseRepository.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        UUID kbId = UUID.randomUUID();
        KnowledgeBase locked = spy(KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build());
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(locked));

        try (AnnotationConfigApplicationContext spring = transactionalContext(transactions)) {
            spring.registerBean(com.dupi.rag.repository.DocumentRepository.class, () -> documents);
            spring.registerBean(com.dupi.rag.repository.KnowledgeBaseRepository.class, () -> knowledgeBases);
            spring.registerBean(ProfileIndexStateService.class,
                    () -> new ProfileIndexStateService(documents, knowledgeBases));
            spring.refresh();

            ProfileIndexStateService state = spring.getBean(ProfileIndexStateService.class);
            assertThat(AopUtils.isAopProxy(state)).isTrue();
            assertThat(state.lockForReindex(kbId, "tenant-a", "model-v2", 2048)).isSameAs(locked);

            InOrder order = inOrder(knowledgeBases, locked);
            order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a");
            order.verify(locked).setEmbeddingModel("model-v2");
            order.verify(locked).setEmbeddingDimension(2048);
            assertThat(transactions.begins).isEqualTo(1);
            assertThat(transactions.commits).isEqualTo(1);
            assertThat(transactions.rollbacks).isZero();
        }
    }

    @Test
    void proxiedSparseCutoverUsesKnowledgeBaseThenMigrationLockOrder() {
        var migrations = mock(com.dupi.rag.repository.SparseMigrationRepository.class);
        var profiles = mock(com.dupi.rag.repository.RetrievalProfileRepository.class);
        var runs = mock(com.dupi.rag.repository.RagEvalRunRepository.class);
        var chunks = mock(com.dupi.rag.repository.ChunkRepository.class);
        var knowledgeBases = mock(KnowledgeBaseService.class);
        var audit = mock(AuditLogService.class);
        var profileService = mock(RetrievalProfileService.class);
        var webClient = mock(org.springframework.web.reactive.function.client.WebClient.Builder.class);
        var maintenance = mock(KnowledgeBaseMaintenanceService.class);
        var backfills = mock(SparseBackfillIntentService.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        UUID kbId = UUID.randomUUID();
        var profile = com.dupi.rag.domain.entity.RetrievalProfile.builder()
                .id(UUID.randomUUID()).kbId(kbId).name("candidate").version(2)
                .vectorCandidateCount(20).sparseCandidateCount(20).rrfConstant(60)
                .rerankCandidateLimit(10).finalTopK(5).build();
        var migration = com.dupi.rag.domain.entity.SparseMigration.builder()
                .id(UUID.randomUUID()).kbId(kbId).profileId(profile.getId())
                .state(com.dupi.rag.domain.enums.SparseMigrationState.SHADOW_VALIDATING)
                .sourceChunkCount(10L).indexedChunkCount(10L)
                .expectedDimension(384).actualDimension(384)
                .baselineP95Ms(100.0).candidateP95Ms(105.0)
                .baselineFallbackRate(0.1).candidateFallbackRate(0.1).build();
        var pass = com.dupi.rag.domain.entity.RagEvalRun.builder().kbId(kbId)
                .status(com.dupi.rag.domain.enums.RagEvalRunStatus.COMPLETED)
                .gateStatus(com.dupi.rag.domain.enums.RagQualityGateStatus.PASS)
                .profileSnapshot(profile.snapshot()).build();
        when(knowledgeBases.findForUpdateOrThrow(kbId))
                .thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(migrations.findByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profiles.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runs.findByKbIdAndStatusAndGateStatus(kbId,
                com.dupi.rag.domain.enums.RagEvalRunStatus.COMPLETED,
                com.dupi.rag.domain.enums.RagQualityGateStatus.PASS)).thenReturn(List.of(pass));
        when(migrations.save(migration)).thenReturn(migration);

        try (AnnotationConfigApplicationContext spring = transactionalContext(transactions)) {
            spring.registerBean(SparseMigrationService.class, () -> new SparseMigrationService(
                    migrations, profiles, runs, chunks, knowledgeBases, audit, profileService,
                    webClient, maintenance, backfills));
            spring.refresh();

            SparseMigrationService service = spring.getBean(SparseMigrationService.class);
            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThat(service.cutover(kbId, migration.getId()).getState())
                    .isEqualTo(com.dupi.rag.domain.enums.SparseMigrationState.CUTOVER);

            InOrder order = inOrder(knowledgeBases, migrations);
            order.verify(knowledgeBases).findForUpdateOrThrow(kbId);
            order.verify(migrations).findByIdAndKbId(migration.getId(), kbId);
            assertThat(transactions.begins).isEqualTo(1);
            assertThat(transactions.commits).isEqualTo(1);
            assertThat(transactions.rollbacks).isZero();
        }
    }
    @Test
    void proxiedStepWriterKeepsParentFenceUntilStepFlushCommits() {
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        OperationJob job = currentJob();
        OperationExecutionContext execution = context(job);
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(job.getId(), "store")).thenReturn(Optional.empty());
        when(steps.findByJobIdOrderBySequenceNumberAsc(job.getId())).thenReturn(List.of());
        when(steps.saveAndFlush(any(OperationStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean(OperationJobRepository.class, () -> jobs);
            spring.registerBean(OperationStepRepository.class, () -> steps);
            spring.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
            spring.register(TransactionConfig.class);
            spring.refresh();

            OperationStepWriteService writer = spring.getBean(OperationStepWriteService.class);
            OperationDomainGuard guard = spring.getBean(OperationDomainGuard.class);
            assertThat(AopUtils.isAopProxy(writer)).isTrue();
            assertThat(AopUtils.isAopProxy(guard)).isTrue();
            assertThatThrownBy(() -> guard.assertActive(execution))
                    .isInstanceOf(IllegalTransactionStateException.class);

            writer.recordStep(execution, "store", "STORE", "ref");

            InOrder order = inOrder(jobs, steps);
            order.verify(jobs).findByIdForUpdate(job.getId());
            order.verify(steps).saveAndFlush(any(OperationStep.class));
            assertThat(transactions.begins).isEqualTo(1);
            assertThat(transactions.commits).isEqualTo(1);
        }
    }

    @Test
    void proxiedRecoveryMetadataRejectsStaleContextBeforeAnyDomainWrite() {
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        RecoveryArchiveRepository archives = mock(RecoveryArchiveRepository.class);
        RecoveryArchiveItemRepository items = mock(RecoveryArchiveItemRepository.class);
        com.dupi.rag.repository.KnowledgeBaseRepository knowledgeBases =
                mock(com.dupi.rag.repository.KnowledgeBaseRepository.class);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        OperationJob job = currentJob();
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a",
                UUID.randomUUID(), null, "embedding", 3, Map.of(), "a".repeat(64), "b".repeat(64),
                "admin", List.of());
        RecoveryManifestService manifests = new RecoveryManifestService(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var manifest = manifests.seal(plan.finalHeader(job.getId()), plan.finalItems(job.getId()));
        StoredRecoveryObject stored = new StoredRecoveryObject("dupi-recovery",
                "archives/tenant-a/" + job.getId() + "/manifest.json", 2, "c".repeat(64));
        OperationExecutionContext stale = new OperationExecutionContext(job.getId(), UUID.randomUUID(),
                job.getClaimEpoch(), job.getRetryEpoch(), job.getPhase());

        try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean(OperationJobRepository.class, () -> jobs);
            spring.registerBean(RecoveryArchiveRepository.class, () -> archives);
            spring.registerBean(RecoveryArchiveItemRepository.class, () -> items);
            spring.registerBean(com.dupi.rag.repository.KnowledgeBaseRepository.class, () -> knowledgeBases);
            spring.registerBean(RecoveryProperties.class, () -> { RecoveryProperties value = new RecoveryProperties(); value.setBucket("dupi-recovery"); return value; });
            spring.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
            spring.register(MetadataTransactionConfig.class);
            spring.refresh();

            RecoveryArchiveImportPersistenceService persistence = spring.getBean(RecoveryArchiveImportPersistenceService.class);
            assertThat(AopUtils.isAopProxy(persistence)).isTrue();
            assertThatThrownBy(() -> persistence.persist(stale, plan, manifest, stored))
                    .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class);
            verify(jobs).findByIdForUpdate(job.getId());
            verifyNoInteractions(archives, items);
            assertThat(transactions.begins).isEqualTo(1);
            assertThat(transactions.commits).isZero();
            assertThat(transactions.rollbacks).isEqualTo(1);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {
        @Bean
        OperationDomainGuard operationDomainGuard(OperationJobRepository jobs) {
            return new OperationDomainGuard(jobs);
        }

        @Bean
        OperationStepWriteService operationStepWriteService(OperationStepRepository steps,
                                                             OperationDomainGuard guard) {
            return new OperationStepWriteService(steps, guard);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MetadataTransactionConfig {
        @Bean
        OperationDomainGuard operationDomainGuard(OperationJobRepository jobs) {
            return new OperationDomainGuard(jobs);
        }

        @Bean
        RecoveryArchiveImportPersistenceService recoveryArchiveImportPersistenceService(
                RecoveryArchiveRepository archives, RecoveryArchiveItemRepository items,
                RecoveryProperties properties, OperationDomainGuard guard,
                com.dupi.rag.repository.KnowledgeBaseRepository knowledgeBases) {
            return new RecoveryArchiveImportPersistenceService(archives, items, properties, guard, knowledgeBases);
        }
    }

    static class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        int begins;
        int commits;
        int rollbacks;
        boolean loseNextCommitAcknowledgement;

        void loseNextCommitAcknowledgement() {
            loseNextCommitAcknowledgement = true;
        }

        boolean isActive() {
            return active.get();
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active.get();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (loseNextCommitAcknowledgement) {
                loseNextCommitAcknowledgement = false;
                throw new org.springframework.transaction.TransactionSystemException(
                        "commit acknowledgement lost after durable commit");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            active.remove();
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            // The participating MANDATORY guard marks the outer metadata transaction rollback-only.
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.remove();
        }
    }

    private static AnnotationConfigApplicationContext transactionalContext(
            TrackingTransactionManager transactions) {
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        spring.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
        spring.register(TransactionOnlyConfig.class);
        return spring;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionOnlyConfig {
    }

    private static OperationJob currentJob() {
        return OperationJob.builder().id(UUID.randomUUID()).status(OperationStatus.RUNNING)
                .phase(OperationPhase.FORWARD).claimToken(UUID.randomUUID()).claimEpoch(4L).retryEpoch(3L)
                .leaseExpiresAt(Instant.now().plusSeconds(30)).build();
    }

    private static OperationExecutionContext context(OperationJob job) {
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), job.getClaimEpoch(),
                job.getRetryEpoch(), job.getPhase());
    }
}
