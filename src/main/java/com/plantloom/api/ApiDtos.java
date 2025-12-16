package com.plantloom.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.StageExecution;
import com.plantloom.domain.StageStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.equipment.Equipment;
import com.plantloom.equipment.EquipmentState;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request and response payloads for the REST API. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record StartInstanceRequest(@NotBlank String definitionId, Map<String, Object> variables) {
    }

    public record BatchStartRequest(@NotBlank String definitionId,
                                    @Min(1) @Max(1000) int count,
                                    Map<String, Object> variables) {
    }

    public record BatchStartResponse(int requested, int started, int rejected, List<String> instanceIds) {
    }

    public record CancelRequest(String reason) {
    }

    public record SensorReadingRequest(@NotBlank String sensorId, @NotNull Double value) {
    }

    public record MaintenanceRequest(boolean enabled) {
    }

    public record RuleEvaluationRequest(@NotBlank String expression,
                                        Map<String, Object> vars,
                                        Map<String, Double> sensors,
                                        Map<String, String> stages) {
    }

    public record RuleEvaluationResponse(String expression, boolean result) {
    }

    public record InstanceSummary(
            String id,
            String definitionId,
            InstanceStatus status,
            String currentStage,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            long durationMs,
            String failureReason) {

        public static InstanceSummary of(WorkflowInstance instance) {
            return new InstanceSummary(
                    instance.getId(),
                    instance.getDefinitionId(),
                    instance.getStatus(),
                    instance.currentStage().map(StageDefinition::id).orElse(null),
                    instance.getCreatedAt(),
                    instance.getStartedAt(),
                    instance.getEndedAt(),
                    instance.age().toMillis(),
                    instance.getFailureReason());
        }
    }

    public record StageView(
            String stageId,
            String name,
            String equipmentType,
            StageStatus status,
            int attempts,
            int maxAttempts,
            String equipmentUnitId,
            Instant waitingSince,
            Instant startedAt,
            Instant endedAt,
            long durationMs,
            String lastError) {

        static StageView of(StageDefinition stage, StageExecution execution) {
            return new StageView(
                    stage.id(),
                    stage.name(),
                    stage.equipmentType(),
                    execution.getStatus(),
                    execution.getAttempts(),
                    stage.retry().maxAttempts(),
                    execution.getEquipmentUnitId(),
                    execution.getWaitingSince(),
                    execution.getStartedAt(),
                    execution.getEndedAt(),
                    execution.elapsed().toMillis(),
                    execution.getLastError());
        }
    }

    public record InstanceDetail(InstanceSummary summary, Map<String, Object> variables, List<StageView> stages) {

        public static InstanceDetail of(WorkflowInstance instance) {
            List<StageView> stages = instance.getDefinition().stages().stream()
                    .map(stage -> StageView.of(stage, instance.execution(stage.id())))
                    .toList();
            return new InstanceDetail(InstanceSummary.of(instance), instance.variablesSnapshot(), stages);
        }
    }

    public record EquipmentView(
            String id,
            String type,
            EquipmentState state,
            boolean maintenanceRequested,
            String instanceId,
            String stageId,
            Instant busySince,
            long completedJobs,
            long faults,
            long busyMillisTotal) {

        public static EquipmentView of(Equipment unit) {
            return new EquipmentView(unit.getId(), unit.getType(), unit.getState(), unit.isMaintenanceRequested(),
                    unit.getInstanceId(), unit.getStageId(), unit.getBusySince(), unit.getCompletedJobs(),
                    unit.getFaults(), unit.getBusyMillisTotal());
        }
    }
}
