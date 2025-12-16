package com.plantloom.diagnostics;

import java.util.List;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import com.plantloom.engine.ConcurrencyGuard;
import com.plantloom.equipment.EquipmentPool;

/**
 * Reports {@code DEGRADED} when an equipment type has no usable units (all in maintenance) or
 * the orchestrator is at its admission limit; {@code UP} otherwise. Exposed as
 * {@code /actuator/health/engine}.
 */
@Component("engine")
public class EngineHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final EquipmentPool pool;
    private final ConcurrencyGuard guard;

    public EngineHealthIndicator(EquipmentPool pool, ConcurrencyGuard guard) {
        this.pool = pool;
        this.guard = guard;
    }

    @Override
    public Health health() {
        List<EquipmentPool.TypeStats> stats = pool.stats();
        List<String> unavailable = stats.stream()
                .filter(s -> s.total() > 0 && s.maintenance() == s.total())
                .map(EquipmentPool.TypeStats::type)
                .toList();
        boolean saturated = guard.activeCount() >= guard.limit();

        Health.Builder builder = unavailable.isEmpty() && !saturated ? Health.up() : Health.status(DEGRADED);
        return builder
                .withDetail("activeInstances", guard.activeCount())
                .withDetail("instanceLimit", guard.limit())
                .withDetail("unavailableEquipmentTypes", unavailable)
                .withDetail("equipment", stats)
                .build();
    }
}
