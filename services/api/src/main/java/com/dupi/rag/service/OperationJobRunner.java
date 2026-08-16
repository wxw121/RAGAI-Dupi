package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationPhase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Dispatches already-claimed jobs to their dedicated domain workflow. */
@Service
@Slf4j
public class OperationJobRunner {

    private final OperationJobClaimService claimService;
    private final Map<OperationType, OperationWorkflow> workflows;
    private final int batchSize;
    private final int cleanupLimit;
    private final boolean enabled;

    @Autowired
    public OperationJobRunner(
            OperationJobClaimService claimService,
            Collection<OperationWorkflow> workflows,
            @Value("${dupi.operations.runner-batch-size:10}") int batchSize,
            @Value("${dupi.operations.runner-cleanup-limit:10}") int cleanupLimit,
            @Value("${dupi.operations.runner-enabled:true}") boolean enabled
    ) {
        this.claimService = claimService;
        this.workflows = registry(workflows);
        this.batchSize = Math.max(0, batchSize);
        this.cleanupLimit = Math.max(0, cleanupLimit);
        this.enabled = enabled;
    }

    public OperationJobRunner(OperationJobClaimService claimService, Collection<OperationWorkflow> workflows,
                              int batchSize, boolean enabled) {
        this(claimService, workflows, batchSize, batchSize, enabled);
    }

    public OperationJobRunner(OperationJobClaimService claimService, Collection<OperationWorkflow> workflows) {
        this(claimService, workflows, 10, 10, true);
    }

    @Scheduled(cron = "${dupi.operations.runner-cron:*/5 * * * * *}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        runBatch(batchSize);
    }

    /** @return whether a due job was claimed. */
    public boolean runOne() {
        return runBatch(1) == 1;
    }

    private int runBatch(int jobLimit) {
        int processed = 0;
        int cleaned = 0;
        while (processed < jobLimit) {
            OperationClaimResult result;
            try {
                result = claimService.claimNext();
            } catch (Exception e) {
                log.warn("Unable to claim one durable operation; continuing batch", e);
                return processed;
            }
            if (result.kind() == OperationClaimKind.NONE) {
                return processed;
            }
            if (result.kind() == OperationClaimKind.TERMINALIZED) {
                cleaned++;
                if (cleaned >= cleanupLimit) {
                    return processed;
                }
                continue;
            }
            try {
                process(result.job());
            } catch (Exception e) {
                log.warn("Unable to process one durable operation; continuing batch", e);
            }
            processed++;
        }
        return processed;
    }

    private void process(com.dupi.rag.domain.entity.OperationJob job) {
            OperationWorkflow workflow = workflows.get(job.getOperationType());
            if (workflow == null) {
                OperationExecutionContext context = context(job);
                persistOutcome(context, Outcome.failed("No workflow registered for operation type " + job.getOperationType()));
                return;
            }
            OperationExecutionContext context = context(job);
            Outcome outcome;
            try {
                if (context.phase() == OperationPhase.COMPENSATION) {
                    workflow.executeCompensation(context);
                } else {
                    workflow.executeForward(context);
                }
                outcome = Outcome.completed();
            } catch (CompensateOperationException e) {
                try {
                    claimService.beginCompensation(context, reason(e));
                } catch (Exception transitionError) {
                    log.warn("Could not start compensation for {}; lease permits recovery", job.getId(), transitionError);
                }
                return;
            } catch (RetryableOperationException e) {
                outcome = Outcome.retry(reason(e));
            } catch (Exception e) {
                log.warn("Durable operation {} failed permanently", job.getId(), e);
                outcome = Outcome.failed(reason(e));
            }
            persistOutcome(context, outcome);
    }

    private OperationExecutionContext context(com.dupi.rag.domain.entity.OperationJob job) {
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), job.getClaimEpoch(),
                job.getRetryEpoch() == null ? 0L : job.getRetryEpoch(), job.getPhase());
    }

    private void persistOutcome(OperationExecutionContext context, Outcome outcome) {
        try {
            if (outcome.kind == OutcomeKind.COMPLETED) {
                claimService.complete(context);
            } else if (outcome.kind == OutcomeKind.RETRY) {
                claimService.scheduleRetry(context, outcome.error);
            } else {
                claimService.fail(context, outcome.error);
            }
        } catch (Exception transitionError) {
            log.warn("Could not persist operation {} transition {}; its lease allows safe recovery", context.jobId(), outcome.kind,
                    transitionError);
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

/** A forward workflow requests its own persisted compensation phase. */
class CompensateOperationException extends RuntimeException {
    CompensateOperationException(String message) { super(message); }
}
