package com.plantloom.event;

public enum EventType {
    INSTANCE_STARTED,
    INSTANCE_COMPLETED,
    INSTANCE_FAILED,
    INSTANCE_TIMED_OUT,
    INSTANCE_CANCELLED,

    STAGE_READY,
    STAGE_SKIPPED,
    STAGE_WAITING,
    STAGE_TRIGGERED,
    STAGE_STARTED,
    STAGE_COMPLETED,
    STAGE_RETRY_SCHEDULED,
    STAGE_FAILED,
    STAGE_TIMED_OUT,

    EQUIPMENT_ACQUIRED,
    EQUIPMENT_RELEASED,
    EQUIPMENT_FAULT,
    EQUIPMENT_MAINTENANCE,

    SENSOR_READING;

    public boolean isInstanceTerminal() {
        return this == INSTANCE_COMPLETED || this == INSTANCE_FAILED
                || this == INSTANCE_TIMED_OUT || this == INSTANCE_CANCELLED;
    }

    public boolean isFailure() {
        return this == INSTANCE_FAILED || this == INSTANCE_TIMED_OUT || this == STAGE_FAILED
                || this == STAGE_TIMED_OUT || this == EQUIPMENT_FAULT;
    }
}
