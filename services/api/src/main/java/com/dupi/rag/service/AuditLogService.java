package com.dupi.rag.service;

import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.AuditLogQuery;
import com.dupi.rag.dto.AuditLogResponse;
import com.dupi.rag.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final AuditProperties auditProperties;
    private final Clock clock;

    @Autowired
    public AuditLogService(AuditLogRepository repository, AuditProperties auditProperties) {
        this(repository, auditProperties, Clock.systemUTC());
    }

    AuditLogService(AuditLogRepository repository, AuditProperties auditProperties, Clock clock) {
        this.repository = repository;
        this.auditProperties = auditProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> list(AuditLogQuery query) {
        AuditLogQuery safeQuery = query == null ? new AuditLogQuery() : query;
        int limit = safeQuery.getLimit() == null ? 50 : Math.max(1, Math.min(200, safeQuery.getLimit()));
        return repository.findAll(specification(safeQuery), PageRequest.of(0, limit))
                .stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public String exportCsv(AuditLogQuery query) {
        AuditLogQuery exportQuery = copy(query);
        exportQuery.setLimit(10_000);
        StringBuilder csv = new StringBuilder("createdAt,tenantId,action,targetType,targetId,status,message,errorMessage\n");
        repository.findAll(specification(exportQuery), PageRequest.of(0, 10_000))
                .forEach(log -> csv.append(csv(log.getCreatedAt()))
                        .append(',').append(csv(log.getTenantId()))
                        .append(',').append(csv(log.getAction()))
                        .append(',').append(csv(log.getTargetType()))
                        .append(',').append(csv(log.getTargetId()))
                        .append(',').append(csv(log.getStatus()))
                        .append(',').append(csv(log.getMessage()))
                        .append(',').append(csv(log.getErrorMessage()))
                        .append('\n'));
        return csv.toString();
    }

    @Transactional
    public int purgeExpired() {
        if (auditProperties.getRetentionDays() <= 0) {
            return 0;
        }
        return repository.deleteByCreatedAtBefore(clock.instant().minusSeconds(auditProperties.getRetentionDays() * 86_400L));
    }

    @Scheduled(cron = "${dupi.audit.retention-cron:0 15 2 * * *}")
    public void purgeExpiredOnSchedule() {
        purgeExpired();
    }

    @Transactional(readOnly = true)
    public List<AuditAlertResponse> summarizeAlerts() {
        if (auditProperties.getAlertFailedThreshold() <= 0 || auditProperties.getAlertWindowMinutes() <= 0) {
            return List.of();
        }
        var windowEnd = clock.instant();
        var windowStart = windowEnd.minusSeconds(auditProperties.getAlertWindowMinutes() * 60L);
        long failures = repository.countByStatusAndCreatedAtAfter(AuditLogStatus.FAILED, windowStart);
        if (failures < auditProperties.getAlertFailedThreshold()) {
            return List.of();
        }
        return List.of(AuditAlertResponse.builder()
                .code("AUDIT_FAILED_SPIKE")
                .severity("WARN")
                .message("Too many failed audit events in the recent audit window")
                .count(failures)
                .threshold(auditProperties.getAlertFailedThreshold())
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String action, String targetType, UUID targetId, String message) {
        repository.save(AuditLog.builder()
                .tenantId(TenantContext.getTenantId())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .status(AuditLogStatus.SUCCESS)
                .message(message)
                .build());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccessInCurrentTransaction(
            String action, String targetType, UUID targetId, String message
    ) {
        repository.save(AuditLog.builder()
                .tenantId(TenantContext.getTenantId())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .status(AuditLogStatus.SUCCESS)
                .message(message)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String action, String targetType, UUID targetId, Exception error) {
        repository.save(AuditLog.builder()
                .tenantId(TenantContext.getTenantId())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .status(AuditLogStatus.FAILED)
                .errorMessage(errorMessage(error))
                .build());
    }

    private String errorMessage(Exception error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private Specification<AuditLog> specification(AuditLogQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(query.getTenantId())) {
                predicates.add(criteriaBuilder.equal(root.get("tenantId"), query.getTenantId().trim()));
            }
            if (hasText(query.getAction())) {
                predicates.add(criteriaBuilder.equal(root.get("action"), query.getAction().trim()));
            }
            if (hasText(query.getTargetType())) {
                predicates.add(criteriaBuilder.equal(root.get("targetType"), query.getTargetType().trim()));
            }
            if (query.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), query.getStatus()));
            }
            criteriaQuery.orderBy(criteriaBuilder.desc(root.get("createdAt")));
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private AuditLogQuery copy(AuditLogQuery source) {
        AuditLogQuery copy = new AuditLogQuery();
        if (source == null) {
            return copy;
        }
        copy.setTenantId(source.getTenantId());
        copy.setAction(source.getAction());
        copy.setTargetType(source.getTargetType());
        copy.setStatus(source.getStatus());
        copy.setLimit(source.getLimit());
        return copy;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace("\r", " ").replace("\n", " ");
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
