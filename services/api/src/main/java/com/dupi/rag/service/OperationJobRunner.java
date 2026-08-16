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
    private final boolean enabled;

    @Autowired
    public OperationJobRunner(
            OperationJobClaimService claimService,
            Collection<OperationWorkflow> workflows,
            @Value("${dupi.operations.runner-batch-size:10}") int batchSize,
            @Value("${dupi.operations.runner-enabled:true}") boolean enabled
    ) {
        this.claimService = claimService;
        this.workflows = registry(workflows);
        this.batchSize = Math.max(0, batchSize);
        this.enabled = enabled;
    }

    public OperationJobRunner(OperationJobClaimService claimService, Collection<OperationWorkflow> workflows) {
        this(claimService, workflows, 10, true);
    }

    @Scheduled(cron = "${dupi.operations.runner-cron:*/5 * * * * *}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        for (int attempt = 0; attempt < batchSize; attempt++) {
            try {
                if (!runOne()) {
                    return;
                }
            } catch (Exception e) {
                log.warn("Unable to process one durable operation; continuing batch", e);
            }
        }
    }

    /** @return whether a due job was claimed. */
    public boolean runOne() {
        return claimService.claimNext().map(job -> {
            OperationWorkflow workflow = workflows.get(job.getOperationType());
            if (workflow == null) {
                persistFailure(job, "No workflow registered for operation type " + job.getOperationType());
                return true;
            }
            Outcome outcome;
            try {
                workflow.execute(job.getId());
                outcome = Outcome.completed();
            } catch (RetryableOperationException e) {
                outcome = Outcome.retry(reason(e));
            } catch (Exception e) {
                log.warn("Durable operation {} failed permanently", job.getId(), e);
                outcome = Outcome.failed(reason(e));
            }
            persistOutcome(job, outcome);
            return true;
        }).orElse(false);
    }

    private void persistOutcome(com.dupi.rag.domain.entity.OperationJob job, Outcome outcome) {
        try {
            if (outcome.kind == OutcomeKind.COMPLETED) {
                claimService.complete(job.getId(), job.getClaimToken());
            } else if (outcome.kind == OutcomeKind.RETRY) {
                claimService.scheduleRetry(job.getId(), job.getClaimToken(), outcome.error);
            } else {
                claimService.fail(job.getId(), job.getClaimToken(), outcome.error);
            }
        } catch (Exception transitionError) {
            log.warn("Could not persist operation {} transition {}; its lease allows safe recovery", job.getId(), outcome.kind,
                    transitionError);
        }
    }

    private void persistFailure(com.dupi.rag.domain.entity.OperationJob job, String error) {
        try {
            claimService.fail(job.getId(), job.getClaimToken(), error);
        } catch (Exception transitionError) {
            log.warn("Could not persist unknown operation type for {}; its lease allows safe recovery", job.getId(), transitionError);
        }
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

    private enum OutcomeKind { COMPLETED, RETRY, FAILED }

    private static final class Outcome {
        private final OutcomeKind kind;
        private final String error;

        private Outcome(OutcomeKind kind, String error) {
            this.kind = kind;
            this.error = error;
        }

        private static Outcome completed() { return new Outcome(OutcomeKind.COMPLETED, null); }
        private static Outcome retry(String error) { return new Outcome(OutcomeKind.RETRY, error); }
        private static Outcome failed(String error) { return new Outcome(OutcomeKind.FAILED, error); }
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
