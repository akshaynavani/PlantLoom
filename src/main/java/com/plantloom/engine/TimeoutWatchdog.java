package com.plantloom.engine;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.StageExecution;
import com.plantloom.domain.StageStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

/**
 * Periodic safety net for timeouts that are not tied to a single future:
 * <ul>
 *   <li>stages parked on an await trigger past their await timeout</li>
 *   <li>instances running past the global instance deadline</li>
 * </ul>
 * Per-attempt stage timeouts are enforced directly by {@link StageExecutor}.
 */
@Component
public class TimeoutWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatchdog.class);

    private final StageScheduler scheduler;
    private final InstanceRepository repository;
    private final WorkflowOrchestrator orchestrator;
    private final ConcurrencyGuard guard;
    private final EventBus bus;
    private final PlantLoomProperties properties;

    public TimeoutWatchdog(StageScheduler scheduler, InstanceRepository repository,
                           WorkflowOrchestrator orchestrator, ConcurrencyGuard guard, EventBus bus,
                           PlantLoomProperties properties) {
        this.scheduler = scheduler;
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.guard = guard;
        this.bus = bus;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${plantloom.engine.watchdog-interval-ms:1000}")
    public void scan() {
        try {
            expireAwaits(Instant.now());
            enforceDeadlines();
        } catch (RuntimeException e) {
            log.error("Watchdog scan failed", e);
        }
    }

    void expireAwaits(Instant now) {
        for (StageScheduler.WaitingStage parked : scheduler.drainExpired(now)) {
            repository.find(parked.instanceId()).ifPresent(instance -> guard.runExclusive(instance.getId(), () -> {
                boolean current = !instance.isTerminal()
                        && instance.currentStage().map(s -> s.id().equals(parked.stageId())).orElse(false);
                if (!current) {
                    return;
                }
                StageExecution execution = instance.execution(parked.stageId());
                String message = "trigger condition not met within " + parked.waited().toMillis() + " ms";
                execution.finish(StageStatus.TIMED_OUT, message);
                bus.publish(WorkflowEvent.of(EventType.STAGE_TIMED_OUT)
                        .instance(instance.getId()).stage(parked.stageId())
                        .attr("error", message)
                        .attr("kind", "await"));
            }));
        }
    }

    void enforceDeadlines() {
        long deadline = properties.getEngine().getInstanceDeadlineMs();
        if (deadline <= 0) {
            return;
        }
        for (WorkflowInstance instance : repository.active()) {
            if (instance.age().toMillis() > deadline) {
                orchestrator.terminate(instance, InstanceStatus.TIMED_OUT,
                        "instance deadline of " + deadline + " ms exceeded");
            }
        }
    }
}
