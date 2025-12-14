package com.plantloom.equipment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.plantloom.config.AsyncConfig;
import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.StageDefinition;
import com.plantloom.simulation.SimulationRandom;

/**
 * Pretends to be the PLC/controller of an equipment unit. A job takes the stage's nominal
 * duration (scaled and jittered) and can be configured to fail or hang at random, which is what
 * exercises the engine's retry and timeout paths.
 *
 * <p>No thread sleeps: completion is scheduled with {@link CompletableFuture#delayedExecutor}.
 */
@Component
public class EquipmentSimulator {

    private static final Map<String, List<String>> FAULT_CODES = Map.of(
            "MIXER", List.of("E-MIX-TORQUE", "E-MIX-SEAL"),
            "OVEN", List.of("E-OVN-THERMOCOUPLE", "E-OVN-OVERTEMP"),
            "FILLER", List.of("E-FIL-NOZZLE", "E-FIL-UNDERFILL"),
            "CAPPER", List.of("E-CAP-JAM"),
            "INSPECTOR", List.of("E-INS-CAMERA"),
            "ROBOT_ARM", List.of("E-ARM-COLLISION", "E-ARM-GRIP"),
            "CNC", List.of("E-CNC-SPINDLE", "E-CNC-TOOLWEAR"),
            "PAINT_BOOTH", List.of("E-PNT-PRESSURE"));

    private final PlantLoomProperties.Simulation config;
    private final SimulationRandom random;
    private final Executor executor;

    public EquipmentSimulator(PlantLoomProperties properties, SimulationRandom random,
                              @Qualifier(AsyncConfig.EQUIPMENT_EXECUTOR) Executor executor) {
        this.config = properties.getSimulation();
        this.random = random;
        this.executor = executor;
    }

    public CompletableFuture<JobResult> perform(Equipment unit, StageDefinition stage, int attempt) {
        long cycleTime = cycleTime(stage.durationMs());

        if (random.chance(config.getHangRate())) {
            // The controller stops responding; only the caller's timeout can rescue the stage.
            return new CompletableFuture<>();
        }

        if (random.chance(config.getFailureRate())) {
            long failAfter = Math.max(1, (long) (cycleTime * (0.2 + 0.6 * random.nextDouble())));
            String code = faultCode(unit.getType());
            return CompletableFuture.supplyAsync(() -> {
                throw new EquipmentFaultException(unit.getId(), code,
                        "simulated fault during '" + stage.id() + "' attempt " + attempt);
            }, delayed(failAfter));
        }

        return CompletableFuture.supplyAsync(() -> new JobResult(unit.getId(), cycleTime, Map.of(
                "attempt", attempt,
                "nominalMs", stage.durationMs(),
                "load", Math.round((0.6 + 0.4 * random.nextDouble()) * 100) / 100.0)), delayed(cycleTime));
    }

    long cycleTime(long nominalMs) {
        double jitter = 1 + config.getDurationJitter() * random.nextSigned();
        return Math.max(0, Math.round(nominalMs * config.getTimeScale() * jitter));
    }

    private Executor delayed(long millis) {
        return CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor);
    }

    private String faultCode(String type) {
        List<String> codes = FAULT_CODES.getOrDefault(type, List.of("E-GENERIC"));
        return codes.get(random.nextInt(codes.size()));
    }
}
