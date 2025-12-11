package com.plantloom.domain;

public enum InstanceStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }
}
