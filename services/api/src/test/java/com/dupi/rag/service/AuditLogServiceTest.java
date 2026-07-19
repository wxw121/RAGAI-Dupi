package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.dto.AuditLogQuery;
import com.dupi.rag.repository.AuditLogRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recordSuccessStoresTenantActionTargetAndMessage() {
        TenantContext.setTenantId("tenant-a");
        UUID targetId = UUID.randomUUID();

        service().recordSuccess("DOCUMENT_DELETE", "DOCUMENT", targetId, "Deleted document");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo("tenant-a");
        assertThat(log.getAction()).isEqualTo("DOCUMENT_DELETE");
        assertThat(log.getTargetType()).isEqualTo("DOCUMENT");
        assertThat(log.getTargetId()).isEqualTo(targetId);
        assertThat(log.getStatus()).isEqualTo(AuditLogStatus.SUCCESS);
        assertThat(log.getMessage()).isEqualTo("Deleted document");
        assertThat(log.getErrorMessage()).isNull();
    }

    @Test
    void recordFailureStoresErrorMessageWithoutDroppingTargetContext() {
        TenantContext.clear();
        UUID targetId = UUID.randomUUID();

        service().recordFailure("VECTOR_CLEANUP_RETRY", "VECTOR_CLEANUP_TASK", targetId,
                new IllegalStateException("milvus down"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo("default");
        assertThat(log.getAction()).isEqualTo("VECTOR_CLEANUP_RETRY");
        assertThat(log.getTargetType()).isEqualTo("VECTOR_CLEANUP_TASK");
        assertThat(log.getTargetId()).isEqualTo(targetId);
        assertThat(log.getStatus()).isEqualTo(AuditLogStatus.FAILED);
        assertThat(log.getErrorMessage()).isEqualTo("milvus down");
    }

    @Test
    void recordFailureUsesExceptionClassNameWhenMessageIsBlankAndAllowsNullErrors() {
        UUID targetId = UUID.randomUUID();

        service().recordFailure("REINDEX", "KNOWLEDGE_BASE", targetId, new IllegalArgumentException(" "));
        service().recordFailure("REINDEX", "KNOWLEDGE_BASE", targetId, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getErrorMessage()).isEqualTo("IllegalArgumentException");
        assertThat(captor.getAllValues().get(1).getErrorMessage()).isNull();
    }

    @Test
    void recordSuccessInCurrentTransactionStoresTheSameAuditContextWithoutNewTransaction() {
        UUID targetId = UUID.randomUUID();

        service().recordSuccessInCurrentTransaction("PROFILE_ACTIVATE", "KNOWLEDGE_BASE", targetId,
                "Activated retrieval profile");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo("tenant-a");
        assertThat(log.getAction()).isEqualTo("PROFILE_ACTIVATE");
        assertThat(log.getTargetType()).isEqualTo("KNOWLEDGE_BASE");
        assertThat(log.getTargetId()).isEqualTo(targetId);
        assertThat(log.getStatus()).isEqualTo(AuditLogStatus.SUCCESS);
        assertThat(log.getMessage()).isEqualTo("Activated retrieval profile");
        assertThat(log.getErrorMessage()).isNull();
    }

    @Test
    void listFiltersAuditLogsByTenantActionStatusAndLimit() {
        UUID targetId = UUID.randomUUID();
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .action("DOCUMENT_DELETE")
                .targetType("DOCUMENT")
                .targetId(targetId)
                .status(AuditLogStatus.SUCCESS)
                .message("Deleted document")
                .createdAt(Instant.parse("2026-07-06T08:00:00Z"))
                .build();
        when(repository.findAll(any(Specification.class), eq(PageRequest.of(0, 25))))
                .thenReturn(new PageImpl<>(List.of(log)));
        AuditLogQuery query = new AuditLogQuery();
        query.setAction("DOCUMENT_DELETE");
        query.setStatus(AuditLogStatus.SUCCESS);
        query.setTargetType("DOCUMENT");
        query.setLimit(25);

        var result = service().list(query);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-a");
        assertThat(result.get(0).getAction()).isEqualTo("DOCUMENT_DELETE");
        assertThat(result.get(0).getStatus()).isEqualTo(AuditLogStatus.SUCCESS);
        assertThat(result.get(0).getMessage()).isEqualTo("Deleted document");
        assertThat(result.get(0).getTargetId()).isEqualTo(targetId);
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 25)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listSpecificationBuildsAllConfiguredPredicatesAndSortsByCreatedAt() {
        ArgumentCaptor<Specification<AuditLog>> specificationCaptor = ArgumentCaptor.forClass(Specification.class);
        when(repository.findAll(specificationCaptor.capture(), eq(PageRequest.of(0, 25))))
                .thenReturn(new PageImpl<>(List.of()));
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(" tenant-a ");
        query.setAction(" DOCUMENT_DELETE ");
        query.setTargetType(" DOCUMENT ");
        query.setStatus(AuditLogStatus.SUCCESS);
        query.setLimit(25);

        service().list(query);

        Root<AuditLog> root = mock(Root.class);
        CriteriaQuery criteriaQuery = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path tenantPath = mock(Path.class);
        Path actionPath = mock(Path.class);
        Path targetTypePath = mock(Path.class);
        Path statusPath = mock(Path.class);
        Path createdAtPath = mock(Path.class);
        Order createdAtDesc = mock(Order.class);
        Predicate tenantPredicate = mock(Predicate.class);
        Predicate actionPredicate = mock(Predicate.class);
        Predicate targetTypePredicate = mock(Predicate.class);
        Predicate statusPredicate = mock(Predicate.class);
        Predicate combinedPredicate = mock(Predicate.class);
        when(root.get("tenantId")).thenReturn(tenantPath);
        when(root.get("action")).thenReturn(actionPath);
        when(root.get("targetType")).thenReturn(targetTypePath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("createdAt")).thenReturn(createdAtPath);
        when(criteriaBuilder.equal(tenantPath, "tenant-a")).thenReturn(tenantPredicate);
        when(criteriaBuilder.equal(actionPath, "DOCUMENT_DELETE")).thenReturn(actionPredicate);
        when(criteriaBuilder.equal(targetTypePath, "DOCUMENT")).thenReturn(targetTypePredicate);
        when(criteriaBuilder.equal(statusPath, AuditLogStatus.SUCCESS)).thenReturn(statusPredicate);
        when(criteriaBuilder.desc(createdAtPath)).thenReturn(createdAtDesc);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(combinedPredicate);

        Predicate result = specificationCaptor.getValue().toPredicate(root, criteriaQuery, criteriaBuilder);

        assertThat(result).isSameAs(combinedPredicate);
        verify(criteriaBuilder).desc(createdAtPath);
        verify(criteriaQuery).orderBy(createdAtDesc);
        verify(criteriaBuilder).and(any(Predicate[].class));
    }

    @Test
    void listUsesSafeDefaultsAndClampsLimit() {
        when(repository.findAll(any(Specification.class), eq(PageRequest.of(0, 50))))
                .thenReturn(new PageImpl<>(List.of()));
        when(repository.findAll(any(Specification.class), eq(PageRequest.of(0, 1))))
                .thenReturn(new PageImpl<>(List.of()));
        when(repository.findAll(any(Specification.class), eq(PageRequest.of(0, 200))))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service().list(null)).isEmpty();

        AuditLogQuery tiny = new AuditLogQuery();
        tiny.setLimit(-10);
        assertThat(service().list(tiny)).isEmpty();

        AuditLogQuery huge = new AuditLogQuery();
        huge.setLimit(999);
        assertThat(service().list(huge)).isEmpty();

        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 50)));
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 1)));
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 200)));
    }

    @Test
    void exportCsvEscapesAuditRowsAndUsesLargeButBoundedQuery() {
        UUID targetId = UUID.randomUUID();
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .action("DOCUMENT_DELETE")
                .targetType("DOCUMENT")
                .targetId(targetId)
                .status(AuditLogStatus.FAILED)
                .message("Deleted \"quoted\" document")
                .errorMessage("line1\nline2")
                .createdAt(Instant.parse("2026-07-06T08:00:00Z"))
                .build();
        when(repository.findAll(any(Specification.class), eq(PageRequest.of(0, 10_000))))
                .thenReturn(new PageImpl<>(List.of(log)));

        String csv = service().exportCsv(new AuditLogQuery());

        assertThat(csv).contains("createdAt,tenantId,action,targetType,targetId,status,message,errorMessage");
        assertThat(csv).contains("\"2026-07-06T08:00:00Z\",\"tenant-a\",\"DOCUMENT_DELETE\",\"DOCUMENT\",\"" + targetId + "\",\"FAILED\",\"Deleted \"\"quoted\"\" document\",\"line1 line2\"");
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 10_000)));
    }

    @Test
    void purgeExpiredDeletesOnlyWhenRetentionIsPositive() {
        AuditProperties properties = new AuditProperties();
        properties.setRetentionDays(30);
        AuditLogService service = new AuditLogService(
                repository,
                properties,
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC)
        );
        when(repository.deleteByCreatedAtBefore(Instant.parse("2026-06-07T00:00:00Z"))).thenReturn(7);

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(7);
        verify(repository).deleteByCreatedAtBefore(Instant.parse("2026-06-07T00:00:00Z"));

        properties.setRetentionDays(0);
        assertThat(service.purgeExpired()).isZero();
        verify(repository, times(1)).deleteByCreatedAtBefore(any());
    }

    @Test
    void summarizeAlertsReportsFailureThresholdBreaches() {
        AuditProperties properties = new AuditProperties();
        properties.setAlertFailedThreshold(2);
        properties.setAlertWindowMinutes(30);
        AuditLogService service = new AuditLogService(
                repository,
                properties,
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC)
        );
        when(repository.countByStatusAndCreatedAtAfter(AuditLogStatus.FAILED, Instant.parse("2026-07-06T23:30:00Z")))
                .thenReturn(3L);

        var alerts = service.summarizeAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getCode()).isEqualTo("AUDIT_FAILED_SPIKE");
        assertThat(alerts.get(0).getSeverity()).isEqualTo("WARN");
        assertThat(alerts.get(0).getCount()).isEqualTo(3L);
        assertThat(alerts.get(0).getThreshold()).isEqualTo(2);
    }

    private AuditLogService service() {
        return new AuditLogService(repository, new AuditProperties());
    }
}
