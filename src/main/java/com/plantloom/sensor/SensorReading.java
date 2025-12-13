package com.plantloom.sensor;

import java.time.Instant;

public record SensorReading(String sensorId, double value, Instant timestamp, String source) {
}
