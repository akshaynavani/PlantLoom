package com.plantloom.equipment;

import java.util.Map;

/** Outcome of a successful simulated equipment job. */
public record JobResult(String unitId, long cycleTimeMs, Map<String, Object> telemetry) {

    public JobResult {
        telemetry = telemetry == null ? Map.of() : Map.copyOf(telemetry);
    }
}
