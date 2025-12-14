package com.plantloom.simulation;

import java.util.Random;

import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;

/**
 * Shared randomness for the simulators. A non-zero {@code plantloom.simulation.seed} makes
 * fault injection and sensor walks reproducible across runs.
 */
@Component
public class SimulationRandom {

    private final Random random;

    public SimulationRandom(PlantLoomProperties properties) {
        long seed = properties.getSimulation().getSeed();
        this.random = seed != 0 ? new Random(seed) : new Random();
    }

    public synchronized double nextDouble() {
        return random.nextDouble();
    }

    /** Uniform value in [-1, 1). */
    public synchronized double nextSigned() {
        return random.nextDouble() * 2 - 1;
    }

    public synchronized int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public boolean chance(double probability) {
        return probability > 0 && nextDouble() < probability;
    }
}
