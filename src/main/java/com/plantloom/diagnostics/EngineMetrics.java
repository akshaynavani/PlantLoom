package com.plantloom.diagnostics;

import java.util.concurrent.TimeUnit;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.plantloom.engine.ConcurrencyGuard;
import com.plantloom.engine.StageScheduler;
import com.plantloom.equipment.EquipmentPool;
import com.plantloom.event.WorkflowEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer instrumentation, exposed through {@code /actuator/metrics}:
 * <ul>
 *   <li>{@code plantloom.events} - counter tagged by event type</li>
 *   <li>{@code plantloom.stage.duration} - timer tagged by stage and equipment type</li>
 *   <li>{@code plantloom.instance.duration} - timer tagged by outcome</li>
 *   <li>{@code plantloom.instances.active}, {@code plantloom.stages.waiting} - gauges</li>
 *   <li>{@code plantloom.equipment.busy|queued} - gauges tagged by equipment type</li>
 * </ul>
 */
@Component
public class EngineMetrics {

    private final MeterRegistry registry;

    public EngineMetrics(MeterRegistry registry, ConcurrencyGuard guard, StageScheduler scheduler,
                         EquipmentPool pool) {
        this.registry = registry;
        Gauge.builder("plantloom.instances.active", guard, ConcurrencyGuard::activeCount)
                .description("Workflow instances currently admitted")
                .register(registry);
        Gauge.builder("plantloom.stages.waiting", scheduler, s -> s.waitingStages().size())
                .description("Stages parked on an await trigger")
                .register(registry);
        pool.stats().forEach(stats -> {
            String type = stats.type();
            Gauge.builder("plantloom.equipment.busy", pool, p -> busy(p, type))
                    .tag("type", type).register(registry);
            Gauge.builder("plantloom.equipment.queued", pool, p -> queued(p, type))
                    .tag("type", type).register(registry);
        });
    }

    @EventListener
    public void onEvent(WorkflowEvent event) {
        Counter.builder("plantloom.events")
                .tag("type", event.type().name())
                .register(registry)
                .increment();

        switch (event.type()) {
            case STAGE_COMPLETED -> {
                if (event.attribute("durationMs") instanceof Number duration) {
                    Timer.builder("plantloom.stage.duration")
                            .tag("stage", event.stageId())
                            .tag("equipmentType", String.valueOf(event.attribute("equipmentType")))
                            .register(registry)
                            .record(duration.longValue(), TimeUnit.MILLISECONDS);
                }
            }
            case INSTANCE_COMPLETED, INSTANCE_FAILED, INSTANCE_TIMED_OUT, INSTANCE_CANCELLED -> {
                if (event.attribute("durationMs") instanceof Number duration) {
                    Timer.builder("plantloom.instance.duration")
                            .tag("outcome", event.type().name())
                            .register(registry)
                            .record(duration.longValue(), TimeUnit.MILLISECONDS);
                }
            }
            default -> {
            }
        }
    }

    private static double busy(EquipmentPool pool, String type) {
        return pool.stats().stream().filter(s -> s.type().equals(type)).mapToInt(EquipmentPool.TypeStats::busy)
                .findFirst().orElse(0);
    }

    private static double queued(EquipmentPool pool, String type) {
        return pool.stats().stream().filter(s -> s.type().equals(type)).mapToInt(EquipmentPool.TypeStats::queued)
                .findFirst().orElse(0);
    }
}
