package com.plantloom.diagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.engine.ConcurrencyGuard;
import com.plantloom.engine.InstanceRepository;
import com.plantloom.engine.StageExecutor;
import com.plantloom.engine.StageScheduler;
import com.plantloom.equipment.EquipmentPool;
import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

/**
 * Structured diagnostics built from the event stream: per-instance timelines, a global recent
 * event buffer, a recent-failures log and counters per event type. Listens synchronously and
 * does O(1) work per event so it never slows the engine down.
 */
@Service
public class DiagnosticsService {

    /** Aggregated health/throughput snapshot served by the diagnostics endpoint. */
    public record Summary(
            Instant generatedAt,
            long uptimeSeconds,
            long lastEventSequence,
            int activeInstances,
            int instanceLimit,
            int waitingStages,
            int inFlightAttempts,
            Map<InstanceStatus, Long> instancesByStatus,
            Map<EventType, Long> eventCounts,
            List<EquipmentPool.TypeStats> equipment,
            List<WorkflowEvent> recentFailures) {
    }

    private final Instant startedAt = Instant.now();
    private final Map<String, Timeline> timelines = new ConcurrentHashMap<>();
    private final Map<EventType, LongAdder> counts = new ConcurrentHashMap<>();
    private final Timeline recent;
    private final Timeline failures;
    private final int timelineCapacity;

    private final InstanceRepository repository;
    private final ConcurrencyGuard guard;
    private final StageScheduler scheduler;
    private final StageExecutor executor;
    private final EquipmentPool pool;
    private final EventBus bus;

    public DiagnosticsService(PlantLoomProperties properties, InstanceRepository repository, ConcurrencyGuard guard,
                              StageScheduler scheduler, StageExecutor executor, EquipmentPool pool, EventBus bus) {
        PlantLoomProperties.Diagnostics config = properties.getDiagnostics();
        this.timelineCapacity = config.getTimelineCapacity();
        this.recent = new Timeline(config.getRecentEventsCapacity());
        this.failures = new Timeline(config.getRecentFailuresCapacity());
        this.repository = repository;
        this.guard = guard;
        this.scheduler = scheduler;
        this.executor = executor;
        this.pool = pool;
        this.bus = bus;
    }

    @EventListener
    public void record(WorkflowEvent event) {
        counts.computeIfAbsent(event.type(), t -> new LongAdder()).increment();
        if (event.type() == EventType.SENSOR_READING) {
            return; // high volume; latest values live in the SensorStore
        }
        recent.add(event);
        if (event.type().isFailure() || event.type() == EventType.STAGE_RETRY_SCHEDULED) {
            failures.add(event);
        }
        if (event.instanceId() != null) {
            timelines.computeIfAbsent(event.instanceId(), id -> new Timeline(timelineCapacity)).add(event);
        }
        if (event.type().isInstanceTerminal() && timelines.size() > 20_000) {
            pruneTimelines();
        }
    }

    public List<WorkflowEvent> timeline(String instanceId) {
        Timeline timeline = timelines.get(instanceId);
        return timeline == null ? List.of() : timeline.snapshot();
    }

    public List<WorkflowEvent> recentEvents(long afterSequence, int limit) {
        return recent.after(afterSequence, limit);
    }

    public Summary summary() {
        Map<EventType, Long> eventCounts = new EnumMap<>(EventType.class);
        counts.forEach((type, adder) -> eventCounts.put(type, adder.sum()));
        return new Summary(
                Instant.now(),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                bus.lastSequence(),
                guard.activeCount(),
                guard.limit(),
                scheduler.waitingStages().size(),
                executor.inFlightCount(),
                repository.countByStatus(),
                eventCounts,
                pool.stats(),
                failures.snapshot());
    }

    private void pruneTimelines() {
        timelines.keySet().removeIf(id -> repository.find(id).isEmpty());
    }
}
