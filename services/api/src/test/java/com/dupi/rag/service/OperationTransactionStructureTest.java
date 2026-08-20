package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
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
