package com.plantloom.domain;

import java.util.Map;

/**
 * One step of a workflow.
 *
 * @param id            unique id within the workflow
 * @param name          human readable label
 * @param equipmentType equipment type to lease while running; {@code null} for pure logic/gate stages
 * @param durationMs    nominal processing time on the equipment
 * @param timeoutMs     per-attempt timeout; {@code null} falls back to the engine default
 * @param retry         retry policy; {@code null} means a single attempt
 * @param when          optional SpEL guard; when it evaluates to false the stage is skipped
 * @param await         optional trigger the stage must wait for before running
 * @param set           variables merged into the instance once the stage completes
 */
public record StageDefinition(
        String id,
        String name,
        String equipmentType,
        long durationMs,
        Long timeoutMs,
        RetryPolicy retry,
        String when,
        AwaitCondition await,
        Map<String, Object> set) {

    public StageDefinition {
        if (name == null || name.isBlank()) {
            name = id;
        }
        if (retry == null) {
            retry = RetryPolicy.NONE;
        }
        set = set == null ? Map.of() : Map.copyOf(set);
    }

    public boolean requiresEquipment() {
        return equipmentType != null && !equipmentType.isBlank();
    }

    public boolean hasGuard() {
        return when != null && !when.isBlank();
    }

    public boolean hasAwait() {
        return await != null && await.condition() != null && !await.condition().isBlank();
    }
}
