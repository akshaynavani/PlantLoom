package com.plantloom.domain;

/**
 * A trigger gate: the stage is held in {@code WAITING} until {@code condition} (a SpEL rule)
 * evaluates to true. It is re-evaluated whenever a relevant event (e.g. a sensor reading) arrives.
 */
public record AwaitCondition(String condition, Long timeoutMs) {
}
