package com.plantloom.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.plantloom.config.AsyncConfig;
import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.StageExecution;
import com.plantloom.domain.StageStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;
import com.plantloom.rules.ConditionEvaluator;

/**
 * Decides what happens to a stage once it becomes current:
 *
 * <ul>
 *   <li>its {@code when} guard is false &rarr; {@code STAGE_SKIPPED}</li>
 *   <li>it declares an {@code await} trigger that is not yet satisfied &rarr; parked as
 *       {@code WAITING} and re-evaluated whenever a sensor reading arrives</li>
 *   <li>otherwise &rarr; dispatched to the {@link StageRunner}</li>
 * </ul>
 */
@Component
public class StageScheduler {

    private static final Logger log = LoggerFactory.getLogger(StageScheduler.class);

    /** A stage parked until its trigger condition holds. */
    public record WaitingStage(String instanceId, String stageId, Instant since, Instant deadline) {
        public Duration waited() {
            return Duration.between(since, Instant.now());
        }
    }

    private final Map<String, WaitingStage> waiting = new ConcurrentHashMap<>();
    private final AtomicBoolean reevaluationQueued = new AtomicBoolean();

    private final InstanceRepository repository;
    private final ConcurrencyGuard guard;
    private final ConditionEvaluator conditions;
    private final StageRunner runner;
    private final EventBus bus;
    private final Executor engineExecutor;
    private final PlantLoomProperties properties;

    public StageScheduler(InstanceRepository repository, ConcurrencyGuard guard, ConditionEvaluator conditions,
                          StageRunner runner, EventBus bus,
                          @Qualifier(AsyncConfig.ENGINE_EXECUTOR) Executor engineExecutor,
                          PlantLoomProperties properties) {
        this.repository = repository;
        this.guard = guard;
        this.conditions = conditions;
        this.runner = runner;
        this.bus = bus;
        this.engineExecutor = engineExecutor;
        this.properties = properties;
    }

    void onStageReady(WorkflowInstance instance, String stageId) {
        guard.runExclusive(instance.getId(), () -> {
            if (!isCurrent(instance, stageId)) {
                return;
            }
            StageDefinition stage = instance.currentStage().orElseThrow();
            StageExecution execution = instance.execution(stage.id());

            if (stage.hasGuard() && !conditions.test(stage.when(), instance)) {
                execution.finish(StageStatus.SKIPPED, null);
                bus.publish(WorkflowEvent.of(EventType.STAGE_SKIPPED)
                        .instance(instance.getId()).stage(stage.id())
                        .attr("guard", stage.when()));
                return;
            }

            if (stage.hasAwait() && !conditions.test(stage.await().condition(), instance)) {
                park(instance, stage, execution);
                return;
            }
            runner.run(instance, stage);
        });
    }

    private void park(WorkflowInstance instance, StageDefinition stage, StageExecution execution) {
        long timeout = stage.await().timeoutMs() != null
                ? stage.await().timeoutMs()
                : properties.getEngine().getDefaultAwaitTimeoutMs();
        Instant now = Instant.now();
        execution.markWaiting();
        instance.markWaiting();
        waiting.put(instance.getId(), new WaitingStage(instance.getId(), stage.id(), now, now.plusMillis(timeout)));
        bus.publish(WorkflowEvent.of(EventType.STAGE_WAITING)
                .instance(instance.getId()).stage(stage.id())
                .attr("condition", stage.await().condition())
                .attr("timeoutMs", timeout));
    }

    /**
     * Sensor readings arrive at high frequency, so re-evaluation is coalesced: at most one pass
     * is queued at a time and it always sees the latest sensor values.
     */
    @EventListener
    public void onSensorReading(WorkflowEvent event) {
        if (event.type() != EventType.SENSOR_READING || waiting.isEmpty()) {
            return;
        }
        requestReevaluation();
    }

    public void requestReevaluation() {
        if (reevaluationQueued.compareAndSet(false, true)) {
            engineExecutor.execute(() -> {
                reevaluationQueued.set(false);
                reevaluateWaiting();
            });
        }
    }

    void reevaluateWaiting() {
        for (WaitingStage parked : List.copyOf(waiting.values())) {
            repository.find(parked.instanceId()).ifPresentOrElse(
                    instance -> reevaluate(instance, parked),
                    () -> waiting.remove(parked.instanceId()));
        }
    }

    private void reevaluate(WorkflowInstance instance, WaitingStage parked) {
        guard.runExclusive(instance.getId(), () -> {
            if (!isCurrent(instance, parked.stageId()) || !waiting.remove(instance.getId(), parked)) {
                waiting.remove(instance.getId(), parked);
                return;
            }
            StageDefinition stage = instance.currentStage().orElseThrow();
            boolean satisfied;
            try {
                satisfied = conditions.test(stage.await().condition(), instance);
            } catch (RuntimeException e) {
                instance.execution(stage.id()).finish(StageStatus.FAILED, e.getMessage());
                bus.publish(WorkflowEvent.of(EventType.STAGE_FAILED)
                        .instance(instance.getId()).stage(stage.id())
                        .attr("error", "await condition error: " + e.getMessage()));
                return;
            }
            if (!satisfied) {
                waiting.put(instance.getId(), parked);
                return;
            }
            instance.markRunning();
            log.info("Trigger satisfied for instance {} stage {} after {} ms",
                    instance.getId(), stage.id(), parked.waited().toMillis());
            bus.publish(WorkflowEvent.of(EventType.STAGE_TRIGGERED)
                    .instance(instance.getId()).stage(stage.id())
                    .attr("waitedMs", parked.waited().toMillis()));
            runner.run(instance, stage);
        });
    }

    /** Removes and returns parked stages whose await deadline has passed. */
    public List<WaitingStage> drainExpired(Instant now) {
        List<WaitingStage> expired = new ArrayList<>();
        for (WaitingStage parked : List.copyOf(waiting.values())) {
            if (parked.deadline().isBefore(now) && waiting.remove(parked.instanceId(), parked)) {
                expired.add(parked);
            }
        }
        return expired;
    }

    public void forget(String instanceId) {
        waiting.remove(instanceId);
    }

    public List<WaitingStage> waitingStages() {
        return List.copyOf(waiting.values());
    }

    private static boolean isCurrent(WorkflowInstance instance, String stageId) {
        return !instance.isTerminal()
                && instance.currentStage().map(s -> s.id().equals(stageId)).orElse(false);
    }
}
