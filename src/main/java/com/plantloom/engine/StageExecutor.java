package com.plantloom.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.plantloom.config.AsyncConfig;
import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.RetryPolicy;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.StageExecution;
import com.plantloom.domain.StageStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.equipment.EquipmentFaultException;
import com.plantloom.equipment.EquipmentLease;
import com.plantloom.equipment.EquipmentPool;
import com.plantloom.equipment.EquipmentSimulator;
import com.plantloom.equipment.EquipmentUnavailableException;
import com.plantloom.equipment.JobResult;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

/**
 * Runs one stage attempt end to end:
 *
 * <ol>
 *   <li>lease a unit of the required equipment type (bounded by the acquire timeout)</li>
 *   <li>run the job on the simulator (bounded by the stage timeout)</li>
 *   <li>release the lease no matter how the job ended</li>
 *   <li>on success apply the stage's outputs and emit {@code STAGE_COMPLETED}; on failure retry
 *       with exponential backoff or emit {@code STAGE_FAILED} / {@code STAGE_TIMED_OUT}</li>
 * </ol>
 *
 * Everything is asynchronous; outcomes are applied under the instance lock and stale outcomes
 * (an attempt that finished after the instance was cancelled or moved on) are discarded.
 */
@Component
public class StageExecutor implements StageRunner {

    private static final Logger log = LoggerFactory.getLogger(StageExecutor.class);

    /** Handle on an in-flight attempt so it can be cancelled. */
    private static final class InFlight {
        final int attempt;
        volatile CompletableFuture<EquipmentLease> leaseFuture;
        volatile EquipmentLease lease;
        volatile CompletableFuture<JobResult> work;

        InFlight(int attempt) {
            this.attempt = attempt;
        }

        void abort() {
            if (leaseFuture != null) {
                leaseFuture.cancel(false);
            }
            if (work != null) {
                work.completeExceptionally(new CancellationException("instance terminated"));
            }
            EquipmentLease held = lease;
            if (held != null) {
                held.release();
            }
        }
    }

    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    private final EquipmentPool pool;
    private final EquipmentSimulator simulator;
    private final ConcurrencyGuard guard;
    private final EventBus bus;
    private final TaskScheduler scheduler;
    private final Executor engineExecutor;
    private final PlantLoomProperties properties;

    public StageExecutor(EquipmentPool pool, EquipmentSimulator simulator, ConcurrencyGuard guard, EventBus bus,
                         TaskScheduler scheduler,
                         @Qualifier(AsyncConfig.ENGINE_EXECUTOR) Executor engineExecutor,
                         PlantLoomProperties properties) {
        this.pool = pool;
        this.simulator = simulator;
        this.guard = guard;
        this.bus = bus;
        this.scheduler = scheduler;
        this.engineExecutor = engineExecutor;
        this.properties = properties;
    }

    @Override
    public void run(WorkflowInstance instance, StageDefinition stage) {
        StageExecution execution = instance.execution(stage.id());
        int attempt = execution.beginAttempt();
        InFlight handle = new InFlight(attempt);
        inFlight.put(instance.getId(), handle);

        bus.publish(WorkflowEvent.of(EventType.STAGE_STARTED)
                .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                .attr("equipmentType", stage.equipmentType()));

        CompletableFuture<JobResult> work;
        if (!stage.requiresEquipment()) {
            execution.markRunning(null);
            work = CompletableFuture.completedFuture(new JobResult(null, 0, Map.of()));
        } else {
            work = acquire(instance, stage, attempt, handle)
                    .thenCompose(lease -> runOnUnit(instance, stage, attempt, handle, lease));
        }
        handle.work = work;
        work.whenCompleteAsync((result, error) -> onAttemptFinished(instance, stage, handle, result, error),
                engineExecutor);
    }

    private CompletableFuture<EquipmentLease> acquire(WorkflowInstance instance, StageDefinition stage,
                                                      int attempt, InFlight handle) {
        long acquireTimeout = properties.getEngine().getEquipmentAcquireTimeoutMs();
        Instant requested = Instant.now();
        CompletableFuture<EquipmentLease> leaseFuture = pool.acquire(stage.equipmentType(), instance.getId(), stage.id());
        handle.leaseFuture = leaseFuture;
        // orTimeout completes the pool's own waiter future, so the pool skips us once we give up.
        leaseFuture.orTimeout(acquireTimeout, TimeUnit.MILLISECONDS);
        return leaseFuture.handle((lease, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof TimeoutException) {
                    throw new CompletionException(new EquipmentUnavailableException(stage.equipmentType(),
                            Duration.between(requested, Instant.now()).toMillis()));
                }
                throw new CompletionException(cause);
            }
            handle.lease = lease;
            if (instance.isTerminal()) {
                lease.release();
                throw new CancellationException("instance terminated while acquiring equipment");
            }
            return lease;
        });
    }

    private CompletableFuture<JobResult> runOnUnit(WorkflowInstance instance, StageDefinition stage, int attempt,
                                                   InFlight handle, EquipmentLease lease) {
        String unitId = lease.unit().getId();
        instance.execution(stage.id()).markRunning(unitId);
        bus.publish(WorkflowEvent.of(EventType.EQUIPMENT_ACQUIRED)
                .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                .attr("unitId", unitId)
                .attr("waitedMs", Duration.between(lease.acquiredAt(), Instant.now()).toMillis()));

        long timeout = scaled(stage.timeoutMs() != null
                ? stage.timeoutMs()
                : properties.getEngine().getDefaultStageTimeoutMs());
        return simulator.perform(lease.unit(), stage, attempt)
                .orTimeout(timeout, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> {
                    if (error == null) {
                        pool.recordCompletion(lease.unit());
                    } else if (unwrap(error) instanceof EquipmentFaultException) {
                        pool.recordFault(lease.unit());
                    }
                    lease.release();
                    bus.publish(WorkflowEvent.of(EventType.EQUIPMENT_RELEASED)
                            .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                            .attr("unitId", unitId)
                            .attr("heldMs", Duration.between(lease.acquiredAt(), Instant.now()).toMillis()));
                });
    }

    private void onAttemptFinished(WorkflowInstance instance, StageDefinition stage, InFlight handle,
                                   JobResult result, Throwable error) {
        MDC.put("instanceId", instance.getId());
        MDC.put("stageId", stage.id());
        try {
            guard.runExclusive(instance.getId(), () -> {
                inFlight.remove(instance.getId(), handle);
                StageExecution execution = instance.execution(stage.id());
                boolean current = !instance.isTerminal()
                        && instance.currentStage().map(s -> s.id().equals(stage.id())).orElse(false)
                        && execution.getAttempts() == handle.attempt
                        && !execution.getStatus().isTerminal();
                if (!current) {
                    log.debug("Discarding stale outcome of {} attempt {}", stage.id(), handle.attempt);
                    return;
                }
                if (error == null) {
                    complete(instance, stage, execution, handle.attempt, result);
                } else {
                    handleFailure(instance, stage, execution, handle.attempt, unwrap(error));
                }
            });
        } finally {
            MDC.remove("instanceId");
            MDC.remove("stageId");
        }
    }

    private void complete(WorkflowInstance instance, StageDefinition stage, StageExecution execution, int attempt,
                          JobResult result) {
        instance.getVariables().putAll(stage.set());
        execution.finish(StageStatus.COMPLETED, null);
        log.info("Stage {} completed on attempt {} ({} ms, unit {})",
                stage.id(), attempt, execution.elapsed().toMillis(), result.unitId());
        bus.publish(WorkflowEvent.of(EventType.STAGE_COMPLETED)
                .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                .attr("unitId", result.unitId())
                .attr("equipmentType", stage.equipmentType())
                .attr("cycleTimeMs", result.cycleTimeMs())
                .attr("durationMs", execution.elapsed().toMillis())
                .attr("telemetry", result.telemetry()));
    }

    private void handleFailure(WorkflowInstance instance, StageDefinition stage, StageExecution execution,
                               int attempt, Throwable cause) {
        if (cause instanceof CancellationException) {
            return;
        }
        boolean timedOut = cause instanceof TimeoutException;
        String message = timedOut ? "no response from equipment within stage timeout" : cause.getMessage();

        if (cause instanceof EquipmentFaultException fault) {
            bus.publish(WorkflowEvent.of(EventType.EQUIPMENT_FAULT)
                    .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                    .attr("unitId", fault.getUnitId())
                    .attr("code", fault.getCode())
                    .attr("error", fault.getMessage()));
        }

        RetryPolicy retry = stage.retry();
        if (retry.hasAttemptsAfter(attempt)) {
            long delay = scaled(retry.delayBeforeAttempt(attempt + 1));
            execution.markRetryScheduled(message);
            log.warn("Stage {} attempt {}/{} failed ({}); retrying in {} ms",
                    stage.id(), attempt, retry.maxAttempts(), message, delay);
            bus.publish(WorkflowEvent.of(EventType.STAGE_RETRY_SCHEDULED)
                    .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                    .attr("error", message)
                    .attr("timeout", timedOut)
                    .attr("delayMs", delay)
                    .attr("nextAttempt", attempt + 1));
            scheduler.schedule(() -> retry(instance, stage, attempt + 1), Instant.now().plusMillis(delay));
            return;
        }

        StageStatus status = timedOut ? StageStatus.TIMED_OUT : StageStatus.FAILED;
        execution.finish(status, message);
        log.error("Stage {} exhausted {} attempt(s): {}", stage.id(), attempt, message);
        bus.publish(WorkflowEvent.of(timedOut ? EventType.STAGE_TIMED_OUT : EventType.STAGE_FAILED)
                .instance(instance.getId()).stage(stage.id()).attempt(attempt)
                .attr("error", message)
                .attr("attempts", attempt));
    }

    private void retry(WorkflowInstance instance, StageDefinition stage, int expectedAttempt) {
        engineExecutor.execute(() -> guard.runExclusive(instance.getId(), () -> {
            StageExecution execution = instance.execution(stage.id());
            boolean stillPending = !instance.isTerminal()
                    && execution.getStatus() == StageStatus.RETRY_SCHEDULED
                    && execution.getAttempts() == expectedAttempt - 1;
            if (stillPending) {
                run(instance, stage);
            }
        }));
    }

    @Override
    public void cancel(String instanceId) {
        InFlight handle = inFlight.remove(instanceId);
        if (handle != null) {
            handle.abort();
        }
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    private long scaled(long millis) {
        double scale = properties.getSimulation().getTimeScale();
        return Math.max(1, Math.round(millis * (scale > 0 ? scale : 1.0)));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
