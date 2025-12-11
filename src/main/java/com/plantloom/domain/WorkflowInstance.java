package com.plantloom.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state of one execution of a {@link WorkflowDefinition}.
 *
 * <p>Status transitions are guarded by the instance monitor; the orchestrator additionally
 * serializes per-instance event handling so only one transition is in flight at a time.
 */
public class WorkflowInstance {

    private final String id;
    private final WorkflowDefinition definition;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, StageExecution> executions = new LinkedHashMap<>();
    private final Instant createdAt = Instant.now();

    private InstanceStatus status = InstanceStatus.PENDING;
    private int currentStageIndex = -1;
    private Instant startedAt;
    private Instant endedAt;
    private String failureReason;

    public WorkflowInstance(String id, WorkflowDefinition definition, Map<String, Object> initialVariables) {
        this.id = id;
        this.definition = definition;
        this.variables.putAll(definition.variables());
        if (initialVariables != null) {
            initialVariables.forEach((k, v) -> {
                if (k != null && v != null) {
                    variables.put(k, v);
                }
            });
        }
        definition.stages().forEach(s -> executions.put(s.id(), new StageExecution(s.id())));
    }

    public String getId() {
        return id;
    }

    public WorkflowDefinition getDefinition() {
        return definition;
    }

    public String getDefinitionId() {
        return definition.id();
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public Map<String, Object> variablesSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized InstanceStatus getStatus() {
        return status;
    }

    public synchronized int getCurrentStageIndex() {
        return currentStageIndex;
    }

    public synchronized Optional<StageDefinition> currentStage() {
        return definition.stage(currentStageIndex);
    }

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized Instant getEndedAt() {
        return endedAt;
    }

    public synchronized String getFailureReason() {
        return failureReason;
    }

    public synchronized boolean isTerminal() {
        return status.isTerminal();
    }

    public StageExecution execution(String stageId) {
        StageExecution execution = executions.get(stageId);
        if (execution == null) {
            throw new IllegalArgumentException("Unknown stage '" + stageId + "' for instance " + id);
        }
        return execution;
    }

    public List<StageExecution> executions() {
        return List.copyOf(executions.values());
    }

    public synchronized Duration age() {
        Instant from = startedAt != null ? startedAt : createdAt;
        return Duration.between(from, endedAt != null ? endedAt : Instant.now());
    }

    public synchronized void markStarted() {
        if (status != InstanceStatus.PENDING) {
            throw new IllegalStateException("Instance " + id + " already started (" + status + ")");
        }
        status = InstanceStatus.RUNNING;
        startedAt = Instant.now();
    }

    /** Moves the cursor to the given stage index; returns false if the instance already ended. */
    public synchronized boolean moveTo(int stageIndex) {
        if (status.isTerminal()) {
            return false;
        }
        currentStageIndex = stageIndex;
        status = InstanceStatus.RUNNING;
        return true;
    }

    public synchronized void markWaiting() {
        if (!status.isTerminal()) {
            status = InstanceStatus.WAITING;
        }
    }

    public synchronized void markRunning() {
        if (!status.isTerminal()) {
            status = InstanceStatus.RUNNING;
        }
    }

    /**
     * Ends the instance. Returns false if it had already ended, which lets racing callers
     * (watchdog vs. stage completion vs. cancel) agree on a single winner.
     */
    public synchronized boolean finish(InstanceStatus terminal, String reason) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal instance status: " + terminal);
        }
        if (status.isTerminal()) {
            return false;
        }
        status = terminal;
        endedAt = Instant.now();
        failureReason = reason;
        return true;
    }
}
