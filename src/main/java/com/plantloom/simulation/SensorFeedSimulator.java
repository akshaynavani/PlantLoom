package com.plantloom.simulation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;
import com.plantloom.sensor.SensorStore;

/**
 * Emits random-walk readings for the configured sensor channels (tank level, booth humidity,
 * line pressure, ...). These readings are what release stages waiting on await triggers.
 * Disable with {@code plantloom.simulation.sensor-feed.enabled=false} to drive sensors via the API.
 */
@Component
@ConditionalOnProperty(prefix = "plantloom.simulation.sensor-feed", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SensorFeedSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorFeedSimulator.class);

    private final Map<String, Double> current = new ConcurrentHashMap<>();
    private final PlantLoomProperties.SensorFeed config;
    private final SensorStore sensors;
    private final SimulationRandom random;

    public SensorFeedSimulator(PlantLoomProperties properties, SensorStore sensors, SimulationRandom random) {
        this.config = properties.getSimulation().getSensorFeed();
        this.sensors = sensors;
        this.random = random;
        config.getChannels().forEach((id, channel) -> current.put(id, channel.getStart()));
        log.info("Sensor feed simulating channels {}", config.getChannels().keySet());
    }

    @Scheduled(fixedDelayString = "${plantloom.simulation.sensor-feed.interval-ms:500}")
    public void tick() {
        config.getChannels().forEach((id, channel) -> {
            double next = current.getOrDefault(id, channel.getStart()) + channel.getStep() * random.nextSigned();
            next = Math.max(channel.getMin(), Math.min(channel.getMax(), next));
            next = Math.round(next * 100) / 100.0;
            current.put(id, next);
            sensors.record(id, next, "simulator");
        });
    }
}
