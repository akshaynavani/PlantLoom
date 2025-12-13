package com.plantloom.sensor;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.plantloom.event.EventBus;
import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

/**
 * Latest value of every plant sensor. Each new reading is published as a
 * {@code SENSOR_READING} event, which is what wakes up stages parked on an await trigger.
 */
@Component
public class SensorStore {

    private final Map<String, SensorReading> latest = new ConcurrentHashMap<>();
    private final EventBus bus;

    public SensorStore(EventBus bus) {
        this.bus = bus;
    }

    public SensorReading record(String sensorId, double value, String source) {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId is required");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("sensor value must be a finite number");
        }
        SensorReading reading = new SensorReading(sensorId, value, Instant.now(), source);
        latest.put(sensorId, reading);
        bus.publish(WorkflowEvent.of(EventType.SENSOR_READING)
                .attr("sensorId", sensorId)
                .attr("value", value)
                .attr("source", source));
        return reading;
    }

    public Optional<SensorReading> get(String sensorId) {
        return Optional.ofNullable(latest.get(sensorId));
    }

    public Collection<SensorReading> all() {
        return latest.values();
    }

    /** Sensor id -> value view used by the rule engine. */
    public Map<String, Double> values() {
        return latest.values().stream()
                .collect(Collectors.toUnmodifiableMap(SensorReading::sensorId, SensorReading::value));
    }
}
