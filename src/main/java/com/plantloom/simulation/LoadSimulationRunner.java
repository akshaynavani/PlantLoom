package com.plantloom.simulation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.plantloom.diagnostics.DiagnosticsService;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.engine.CapacityExceededException;
import com.plantloom.engine.WorkflowOrchestrator;

/**
 * Optional load generator: on startup it launches a burst of instances across the configured
 * workflows and logs a progress line until they have all finished. Enable with
 * {@code plantloom.simulation.load.enabled=true} (or the {@code load} profile).
 */
@Component
@ConditionalOnProperty(prefix = "plantloom.simulation.load", name = "enabled", havingValue = "true")
public class LoadSimulationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadSimulationRunner.class);

    private final WorkflowOrchestrator orchestrator;
    private final DiagnosticsService diagnostics;
    private final int instancesPerWorkflow;
    private final List<String> workflows;
    private final long staggerMs;

    public LoadSimulationRunner(WorkflowOrchestrator orchestrator, DiagnosticsService diagnostics,
                                @Value("${plantloom.simulation.load.instances-per-workflow:25}") int instancesPerWorkflow,
                                @Value("${plantloom.simulation.load.workflows:bottling-line,cnc-machining,paint-shop}") String workflows,
                                @Value("${plantloom.simulation.load.stagger-ms:20}") long staggerMs) {
        this.orchestrator = orchestrator;
        this.diagnostics = diagnostics;
        this.instancesPerWorkflow = instancesPerWorkflow;
        this.workflows = Arrays.stream(workflows.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.staggerMs = staggerMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(this::generate, "load-simulation");
        thread.setDaemon(true);
        thread.start();
    }

    private void generate() {
        int started = 0;
        int rejected = 0;
        log.info("Load simulation: {} instance(s) for each of {}", instancesPerWorkflow, workflows);
        for (int i = 0; i < instancesPerWorkflow; i++) {
            for (String workflow : workflows) {
                try {
                    orchestrator.start(workflow, Map.of("batch", i, "finish", i % 3 == 0 ? "fine" : "standard"));
                    started++;
                } catch (CapacityExceededException e) {
                    rejected++;
                }
                pause(staggerMs);
            }
        }
        log.info("Load simulation submitted {} instance(s), {} rejected at admission", started, rejected);

        while (diagnostics.summary().activeInstances() > 0) {
            report();
            pause(2_000);
        }
        report();
        log.info("Load simulation finished");
    }

    private void report() {
        DiagnosticsService.Summary summary = diagnostics.summary();
        Map<InstanceStatus, Long> byStatus = summary.instancesByStatus();
        log.info("load: active={} waiting={} completed={} failed={} timedOut={} equipment={}",
                summary.activeInstances(), summary.waitingStages(),
                byStatus.getOrDefault(InstanceStatus.COMPLETED, 0L),
                byStatus.getOrDefault(InstanceStatus.FAILED, 0L),
                byStatus.getOrDefault(InstanceStatus.TIMED_OUT, 0L),
                summary.equipment());
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
