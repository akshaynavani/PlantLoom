package com.plantloom.engine;

public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(int limit) {
        super("Orchestrator is at capacity (" + limit + " active instances); retry later");
    }
}
