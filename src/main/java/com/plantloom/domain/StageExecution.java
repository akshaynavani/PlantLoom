package com.plantloom.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Mutable runtime record of a single stage within an instance. All mutators are synchronized
 * because completions, timeouts and cancellations may race from different threads.
 */
public class StageExecution {

    private final String stageId;
    private StageStatus status = StageStatus.PENDING;
    private int attempts;
    private String equipmentUnitId;
    private Instant waitingSince;
    private Instant startedAt;
    private Instant endedAt;
    private String lastError;

    public StageExecution(String stageId) {
        this.stageId = stageId;
    }

    public String getStageId() {
        return stageId;
    }

    public synchronized StageStatus getStatus() {
        return status;
    }

    public synchronized int getAttempts() {
        return attempts;
    }

    public synchronized String getEquipmentUnitId() {
        return equipmentUnitId;
    }

    public synchronized Instant getWaitingSince() {
        return waitingSince;
    }

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized Instant getEndedAt() {
        return endedAt;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized Duration elapsed() {
        if (startedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, endedAt != null ? endedAt : Instant.now());
    }

    public synchronized void markWaiting() {
        status = StageStatus.WAITING;
        if (waitingSince == null) {
            waitingSince = Instant.now();
        }
    }

    /** Begins a new attempt and returns its 1-based number. */
    public synchronized int beginAttempt() {
        attempts++;
        status = StageStatus.ACQUIRING;
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        return attempts;
    }

    public synchronized void markRunning(String unitId) {
        status = StageStatus.RUNNING;
        equipmentUnitId = unitId;
    }

    public synchronized void markRetryScheduled(String error) {
        status = StageStatus.RETRY_SCHEDULED;
        lastError = error;
        equipmentUnitId = null;
    }

    public synchronized void finish(StageStatus terminal, String error) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal stage status: " + terminal);
        }
        status = terminal;
        endedAt = Instant.now();
        if (error != null) {
            lastError = error;
        }
    }
}
