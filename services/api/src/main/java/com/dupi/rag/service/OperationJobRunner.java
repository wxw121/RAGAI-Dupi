package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Dispatches already-claimed jobs to their dedicated domain workflow. */
@Service
@Slf4j
public class OperationJobRunner {

    private final OperationJobClaimService claimService;
    private final Map<OperationType, OperationWorkflow> workflows;
    private final int batchSize;

    @Autowired
    public OperationJobRunner(
            OperationJobClaimService claimService,
            Collection<OperationWorkflow> workflows,
            @Value("${dupi.operations.runner-batch-size:10}") int batchSize
    ) {
        this.claimService = claimService;
        this.workflows = registry(workflows);
        this.batchSize = Math.max(1, batchSize);
    }

    public OperationJobRunner(OperationJobClaimService claimService, Collection<OperationWorkflow> workflows) {
        this(claimService, workflows, 10);
    }

    @Scheduled(cron = "${dupi.operations.runner-cron:*/5 * * * * *}")
    public void runScheduled() {
        for (int attempt = 0; attempt < batchSize && runOne(); attempt++) {
            // Keep transactions short: every claimed job starts and completes in separate transactions.
        }
    }

    /** @return whether a due job was claimed. */
    public boolean runOne() {
        return claimService.claimNext().map(job -> {
            OperationWorkflow workflow = workflows.get(job.getOperationType());
            if (workflow == null) {
                claimService.fail(job.getId(), "No workflow registered for operation type " + job.getOperationType());
                return true;
            }
            try {
                workflow.execute(job.getId());
                claimService.complete(job.getId());
            } catch (RetryableOperationException e) {
                claimService.scheduleRetry(job.getId(), reason(e));
            } catch (Exception e) {
                log.warn("Durable operation {} failed permanently", job.getId(), e);
                claimService.fail(job.getId(), reason(e));
            }
            return true;
        }).orElse(false);
    }

    private Map<OperationType, OperationWorkflow> registry(Collection<OperationWorkflow> candidates) {
        Map<OperationType, OperationWorkflow> handlers = new EnumMap<>(OperationType.class);
        for (OperationWorkflow workflow : candidates) {
            OperationWorkflow previous = handlers.putIfAbsent(workflow.type(), workflow);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate workflow handler for operation type " + workflow.type());
            }
        }
        return Map.copyOf(handlers);
    }

    private String reason(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}

/** Signals that an external dependency failure can be retried safely. */
class RetryableOperationException extends RuntimeException {

    RetryableOperationException(String message) {
        super(message);
    }

    RetryableOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
