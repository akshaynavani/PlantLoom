package com.plantloom.domain;

public enum StageStatus {
    PENDING,
    WAITING,
    ACQUIRING,
    RUNNING,
    RETRY_SCHEDULED,
    COMPLETED,
    SKIPPED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }
}
