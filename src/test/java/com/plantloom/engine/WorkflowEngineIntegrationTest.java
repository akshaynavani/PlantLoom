package com.plantloom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.plantloom.diagnostics.DiagnosticsService;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.StageStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.equipment.Equipment;
import com.plantloom.equipment.EquipmentFaultException;
import com.plantloom.equipment.EquipmentPool;
import com.plantloom.equipment.EquipmentSimulator;
import com.plantloom.equipment.EquipmentState;
import com.plantloom.equipment.JobResult;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;
import com.plantloom.sensor.SensorStore;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowEngineIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    WorkflowOrchestrator orchestrator;
    @Autowired
    DiagnosticsService diagnostics;
    @Autowired
    SensorStore sensors;
    @Autowired
    EquipmentPool pool;
    @MockitoSpyBean
    EquipmentSimulator simulator;

    @Test
    void runsAllStagesAndSkipsGuardedStage() {
        WorkflowInstance instance = orchestrator.start("quick", Map.of());

        awaitStatus(instance, InstanceStatus.COMPLETED);

        assertThat(instance.execution("mix").getStatus()).isEqualTo(StageStatus.COMPLETED);
        assertThat(instance.execution("record").getStatus()).isEqualTo(StageStatus.COMPLETED);
        assertThat(instance.execution("heat").getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(instance.getVariables()).containsEntry("mixed", true).containsEntry("recorded", true)
                .doesNotContainKey("heated");
        assertThat(types(instance)).containsSubsequence(
                EventType.INSTANCE_STARTED, EventType.STAGE_READY, EventType.STAGE_STARTED,
                EventType.EQUIPMENT_ACQUIRED, EventType.STAGE_COMPLETED, EventType.STAGE_SKIPPED,
                EventType.INSTANCE_COMPLETED);
    }

    @Test
    void guardedStageRunsWhenVariableAllowsIt() {
        WorkflowInstance instance = orchestrator.start("quick", Map.of("heat", true));

        awaitStatus(instance, InstanceStatus.COMPLETED);

        assertThat(instance.execution("heat").getStatus()).isEqualTo(StageStatus.COMPLETED);
        assertThat(instance.getVariables()).containsEntry("heated", true);
    }

    @Test
    void awaitStageIsReleasedBySensorReading() {
        sensors.record("test.gate", 1, "test");
        WorkflowInstance instance = orchestrator.start("gated", Map.of());

        await().atMost(WAIT).until(() -> instance.getStatus() == InstanceStatus.WAITING);
        assertThat(instance.execution("gate").getStatus()).isEqualTo(StageStatus.WAITING);

        sensors.record("test.gate", 25, "test");

        awaitStatus(instance, InstanceStatus.COMPLETED);
        assertThat(types(instance)).contains(EventType.STAGE_WAITING, EventType.STAGE_TRIGGERED);
    }

    @Test
    void awaitStageTimesOutWhenTriggerNeverFires() {
        WorkflowInstance instance = orchestrator.start("gated-short", Map.of());

        awaitStatus(instance, InstanceStatus.TIMED_OUT);

        assertThat(instance.execution("gate").getStatus()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(instance.getFailureReason()).contains("trigger condition not met");
    }

    @Test
    void retriesTransientFaultsThenSucceeds() {
        CompletableFuture<JobResult> fault = CompletableFuture.failedFuture(
                new EquipmentFaultException("OVEN-1", "E-TEST", "transient"));
        doReturn(fault).doReturn(fault).doCallRealMethod()
                .when(simulator).perform(any(), argThat(stageNamed("flaky")), anyInt());

        WorkflowInstance instance = orchestrator.start("flaky", Map.of());

        awaitStatus(instance, InstanceStatus.COMPLETED);
        assertThat(instance.execution("flaky").getAttempts()).isEqualTo(3);
        assertThat(types(instance).stream().filter(t -> t == EventType.STAGE_RETRY_SCHEDULED)).hasSize(2);
        assertThat(types(instance)).contains(EventType.EQUIPMENT_FAULT);
    }

    @Test
    void failsInstanceWhenRetriesAreExhausted() {
        doReturn(CompletableFuture.failedFuture(new EquipmentFaultException("OVEN-1", "E-TEST", "permanent")))
                .when(simulator).perform(any(), argThat(stageNamed("flaky")), anyInt());

        WorkflowInstance instance = orchestrator.start("flaky", Map.of());

        awaitStatus(instance, InstanceStatus.FAILED);
        assertThat(instance.execution("flaky").getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(instance.execution("flaky").getAttempts()).isEqualTo(3);
        assertThat(instance.getFailureReason()).contains("permanent");
        await().atMost(WAIT).until(() -> unitsIdle("OVEN"));
    }

    @Test
    void hungEquipmentTimesOutAndReleasesTheUnit() {
        doAnswer(invocation -> new CompletableFuture<JobResult>())
                .when(simulator).perform(any(), argThat(stageNamed("flaky")), anyInt());

        WorkflowInstance instance = orchestrator.start("flaky", Map.of());

        awaitStatus(instance, InstanceStatus.TIMED_OUT);
        assertThat(instance.execution("flaky").getStatus()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(instance.execution("flaky").getAttempts()).isEqualTo(3);
        await().atMost(WAIT).until(() -> unitsIdle("OVEN"));
    }

    @Test
    void cancellingARunningInstanceFreesItsEquipment() {
        WorkflowInstance instance = orchestrator.start("slow", Map.of());
        await().atMost(WAIT).until(() -> instance.execution("long").getStatus() == StageStatus.RUNNING);

        assertThat(orchestrator.cancel(instance.getId(), "operator stop")).isTrue();

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(instance.execution("long").getStatus()).isEqualTo(StageStatus.CANCELLED);
        await().atMost(WAIT).until(() -> unitsIdle("OVEN"));
        assertThat(orchestrator.cancel(instance.getId(), "again")).isFalse();
    }

    @Test
    void concurrentInstancesNeverOversubscribeEquipment() {
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger maxInUse = new AtomicInteger();
        doAnswer(invocation -> {
            Equipment unit = invocation.getArgument(0);
            maxInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
            return CompletableFuture.supplyAsync(() -> {
                inUse.decrementAndGet();
                return new JobResult(unit.getId(), 15, Map.of());
            }, CompletableFuture.delayedExecutor(15, TimeUnit.MILLISECONDS));
        }).when(simulator).perform(any(), argThat(stageNamed("mix")), anyInt());

        List<WorkflowInstance> instances = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            instances.add(orchestrator.start("mixer-load", Map.of("n", i)));
        }

        await().atMost(Duration.ofSeconds(20))
                .until(() -> instances.stream().allMatch(WorkflowInstance::isTerminal));

        assertThat(instances).allMatch(i -> i.getStatus() == InstanceStatus.COMPLETED);
        assertThat(maxInUse.get()).isBetween(1, 2);
        assertThat(unitsIdle("MIXER")).isTrue();
    }

    private void awaitStatus(WorkflowInstance instance, InstanceStatus status) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).until(instance::isTerminal);
        assertThat(instance.getStatus())
                .as("instance %s ended with reason %s", instance.getId(), instance.getFailureReason())
                .isEqualTo(status);
    }

    private List<EventType> types(WorkflowInstance instance) {
        return diagnostics.timeline(instance.getId()).stream().map(WorkflowEvent::type).toList();
    }

    private boolean unitsIdle(String type) {
        return pool.units().stream()
                .filter(u -> u.getType().equals(type))
                .allMatch(u -> u.getState() == EquipmentState.IDLE);
    }

    private static org.mockito.ArgumentMatcher<StageDefinition> stageNamed(String id) {
        return stage -> stage != null && stage.id().equals(id);
    }
}
