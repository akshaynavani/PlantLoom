package com.plantloom.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable fact emitted by the engine. Everything that happens in PlantLoom - state changes,
 * equipment leases, sensor readings - is expressed as one of these, which makes the engine
 * replayable in diagnostics and streamable to monitoring clients.
 *
 * @param sequence   monotonically increasing sequence number assigned by the {@link EventBus}
 * @param type       event type
 * @param timestamp  when the event was created
 * @param instanceId owning workflow instance, or {@code null} for plant-wide events
 * @param stageId    stage the event relates to, if any
 * @param attempt    stage attempt number, if any
 * @param attributes free-form structured details (unit id, error, sensor value, ...)
 */
public record WorkflowEvent(
        long sequence,
        EventType type,
        Instant timestamp,
        String instanceId,
        String stageId,
        Integer attempt,
        Map<String, Object> attributes) {

    public WorkflowEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Builder of(EventType type) {
        return new Builder(type);
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    /** Fluent builder; the sequence and timestamp are assigned when the bus publishes it. */
    public static final class Builder {
        private final EventType type;
        private String instanceId;
        private String stageId;
        private Integer attempt;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder(EventType type) {
            this.type = type;
        }

        public Builder instance(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder stage(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        public Builder attr(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        WorkflowEvent build(long sequence) {
            return new WorkflowEvent(sequence, type, Instant.now(), instanceId, stageId, attempt, attributes);
        }
    }
}
