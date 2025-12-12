package com.plantloom.engine;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.plantloom.config.AsyncConfig;
import com.plantloom.definition.WorkflowDefinitionRegistry;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.StageExecution;
import com.plantloom.domain.WorkflowDefinition;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

/**
 * The state machine driving workflow instances. It never does stage work itself: it reacts to
 * events, advances the stage cursor and emits new events, delegating stage readiness to the
 * {@link StageScheduler}.
 *
 * <pre>
 *  INSTANCE_STARTED ─▶ advance(0) ─▶ STAGE_READY ─▶ (scheduler) ─▶ STAGE_COMPLETED / SKIPPED
 *                                                                   │
 *                                    advance(i+1) ◀─────────────────┘
 *  STAGE_FAILED / STAGE_TIMED_OUT ─▶ terminate(FAILED / TIMED_OUT)
 * </pre>
 */
@Service
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final WorkflowDefinitionRegistry definitions;
    private final InstanceRepository repository;
    private final ConcurrencyGuard guard;
    private final EventBus bus;
    private final StageScheduler scheduler;
    private final StageRunner runner;

    public WorkflowOrchestrator(WorkflowDefinitionRegistry definitions, InstanceRepository repository,
                                ConcurrencyGuard guard, EventBus bus, StageScheduler scheduler,
                                StageRunner runner) {
        this.definitions = definitions;
        this.repository = repository;
        this.guard = guard;
        this.bus = bus;
        this.scheduler = scheduler;
        this.runner = runner;
    }

    /** Creates and starts a new instance. Throws {@link CapacityExceededException} when full. */
    public WorkflowInstance start(String definitionId, Map<String, Object> variables) {
        WorkflowDefinition definition = definitions.require(definitionId);
        guard.admit();
        WorkflowInstance instance = new WorkflowInstance(UUID.randomUUID().toString(), definition, variables);
        repository.save(instance);
        instance.markStarted();
        log.info("Starting instance {} of workflow {} v{}", instance.getId(), definition.id(), definition.version());
        bus.publish(WorkflowEvent.of(EventType.INSTANCE_STARTED)
                .instance(instance.getId())
                .attr("definitionId", definition.id())
                .attr("version", definition.version()));
        return instance;
    }

    /** Operator cancel. Returns false if the instance had already finished. */
    public boolean cancel(String instanceId, String reason) {
        WorkflowInstance instance = repository.require(instanceId);
        return terminate(instance, InstanceStatus.CANCELLED,
                reason == null || reason.isBlank() ? "cancelled by operator" : reason);
    }

    @Async(AsyncConfig.ENGINE_EXECUTOR)
    @EventListener
    public void onEvent(WorkflowEvent event) {
        if (event.instanceId() == null) {
            return;
        }
        switch (event.type()) {
            case INSTANCE_STARTED -> withInstance(event, instance -> advance(instance, 0));
            case STAGE_COMPLETED, STAGE_SKIPPED -> withInstance(event, instance ->
                    advance(instance, instance.getDefinition().indexOf(event.stageId()) + 1));
            case STAGE_READY -> withInstance(event, instance -> scheduler.onStageReady(instance, event.stageId()));
            case STAGE_FAILED -> withInstance(event, instance -> terminate(instance, InstanceStatus.FAILED,
                    "stage '" + event.stageId() + "' failed: " + event.attribute("error")));
            case STAGE_TIMED_OUT -> withInstance(event, instance -> terminate(instance, InstanceStatus.TIMED_OUT,
                    "stage '" + event.stageId() + "' timed out: " + event.attribute("error")));
            default -> {
                // other events are informational for the orchestrator
            }
        }
    }

    /**
     * Moves the instance to the stage at {@code index}, or completes it when past the last stage.
     * Stale events (e.g. a completion arriving after the cursor moved on) are ignored.
     */
    void advance(WorkflowInstance instance, int index) {
        guard.runExclusive(instance.getId(), () -> {
            if (instance.isTerminal()) {
                return;
            }
            int expectedPrevious = index - 1;
            if (index > 0 && instance.getCurrentStageIndex() != expectedPrevious) {
                log.debug("Ignoring stale advance to {} for instance {} (cursor at {})",
                        index, instance.getId(), instance.getCurrentStageIndex());
                return;
            }
            var next = instance.getDefinition().stage(index);
            if (next.isEmpty()) {
                if (instance.finish(InstanceStatus.COMPLETED, null)) {
                    guard.release(instance.getId());
                    log.info("Instance {} completed in {} ms", instance.getId(), instance.age().toMillis());
                    bus.publish(WorkflowEvent.of(EventType.INSTANCE_COMPLETED)
                            .instance(instance.getId())
                            .attr("durationMs", instance.age().toMillis()));
                }
                return;
            }
            StageDefinition stage = next.get();
            instance.moveTo(index);
            bus.publish(WorkflowEvent.of(EventType.STAGE_READY)
                    .instance(instance.getId())
                    .stage(stage.id())
                    .attr("index", index));
        });
    }

    /**
     * Ends an instance with a terminal status, exactly once, releasing everything it holds.
     * Shared by failures, timeouts (watchdog) and operator cancellation.
     */
    public boolean terminate(WorkflowInstance instance, InstanceStatus status, String reason) {
        return guard.callExclusive(instance.getId(), () -> {
            if (!instance.finish(status, reason)) {
                return false;
            }
            instance.currentStage().ifPresent(stage -> {
                StageExecution execution = instance.execution(stage.id());
                if (!execution.getStatus().isTerminal()) {
                    execution.finish(status == InstanceStatus.TIMED_OUT
                            ? com.plantloom.domain.StageStatus.TIMED_OUT
                            : com.plantloom.domain.StageStatus.CANCELLED, reason);
                }
            });
            scheduler.forget(instance.getId());
            runner.cancel(instance.getId());
            guard.release(instance.getId());

            EventType type = switch (status) {
                case FAILED -> EventType.INSTANCE_FAILED;
                case TIMED_OUT -> EventType.INSTANCE_TIMED_OUT;
                case CANCELLED -> EventType.INSTANCE_CANCELLED;
                default -> throw new IllegalArgumentException("Unsupported terminal status " + status);
            };
            log.warn("Instance {} ended as {}: {}", instance.getId(), status, reason);
            bus.publish(WorkflowEvent.of(type)
                    .instance(instance.getId())
                    .stage(instance.currentStage().map(StageDefinition::id).orElse(null))
                    .attr("reason", reason)
                    .attr("durationMs", instance.age().toMillis()));
            return true;
        });
    }

    private void withInstance(WorkflowEvent event, java.util.function.Consumer<WorkflowInstance> action) {
        repository.find(event.instanceId()).ifPresent(instance -> {
            MDC.put("instanceId", instance.getId());
            if (event.stageId() != null) {
                MDC.put("stageId", event.stageId());
            }
            try {
                action.accept(instance);
            } catch (RuntimeException e) {
                log.error("Engine error handling {} for instance {}", event.type(), instance.getId(), e);
                terminate(instance, InstanceStatus.FAILED, "engine error: " + e.getMessage());
            } finally {
                MDC.remove("instanceId");
                MDC.remove("stageId");
            }
        });
    }
}
