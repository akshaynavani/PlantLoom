package com.plantloom.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables for the orchestrator, bound from the {@code plantloom.*} namespace.
 */
@ConfigurationProperties(prefix = "plantloom")
public class PlantLoomProperties {

    private final Engine engine = new Engine();
    private final Equipment equipment = new Equipment();
    private final Simulation simulation = new Simulation();
    private final Diagnostics diagnostics = new Diagnostics();

    /** Classpath/file pattern for workflow definition files. */
    private String definitionsLocation = "classpath:workflows/*.yml";

    public Engine getEngine() {
        return engine;
    }

    public Equipment getEquipment() {
        return equipment;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public String getDefinitionsLocation() {
        return definitionsLocation;
    }

    public void setDefinitionsLocation(String definitionsLocation) {
        this.definitionsLocation = definitionsLocation;
    }

    public static class Engine {
        /** Threads that process engine events and stage completions. */
        private int workerThreads = 8;
        /** Hard cap on simultaneously active workflow instances. */
        private int maxConcurrentInstances = 200;
        /** How long a stage may wait for an equipment unit before the attempt fails. */
        private long equipmentAcquireTimeoutMs = 10_000;
        /** Default per-attempt stage timeout when a stage does not declare one. */
        private long defaultStageTimeoutMs = 30_000;
        /** Default wait timeout for stages that await a trigger condition. */
        private long defaultAwaitTimeoutMs = 60_000;
        /** Overall instance deadline, enforced by the watchdog. 0 disables it. */
        private long instanceDeadlineMs = 10 * 60_000;
        /** Watchdog scan interval. */
        private long watchdogIntervalMs = 1_000;

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getMaxConcurrentInstances() {
            return maxConcurrentInstances;
        }

        public void setMaxConcurrentInstances(int maxConcurrentInstances) {
            this.maxConcurrentInstances = maxConcurrentInstances;
        }

        public long getEquipmentAcquireTimeoutMs() {
            return equipmentAcquireTimeoutMs;
        }

        public void setEquipmentAcquireTimeoutMs(long equipmentAcquireTimeoutMs) {
            this.equipmentAcquireTimeoutMs = equipmentAcquireTimeoutMs;
        }

        public long getDefaultStageTimeoutMs() {
            return defaultStageTimeoutMs;
        }

        public void setDefaultStageTimeoutMs(long defaultStageTimeoutMs) {
            this.defaultStageTimeoutMs = defaultStageTimeoutMs;
        }

        public long getDefaultAwaitTimeoutMs() {
            return defaultAwaitTimeoutMs;
        }

        public void setDefaultAwaitTimeoutMs(long defaultAwaitTimeoutMs) {
            this.defaultAwaitTimeoutMs = defaultAwaitTimeoutMs;
        }

        public long getInstanceDeadlineMs() {
            return instanceDeadlineMs;
        }

        public void setInstanceDeadlineMs(long instanceDeadlineMs) {
            this.instanceDeadlineMs = instanceDeadlineMs;
        }

        public long getWatchdogIntervalMs() {
            return watchdogIntervalMs;
        }

        public void setWatchdogIntervalMs(long watchdogIntervalMs) {
            this.watchdogIntervalMs = watchdogIntervalMs;
        }
    }

    public static class Equipment {
        /** Number of simulated units per equipment type, e.g. {@code MIXER: 2}. */
        private Map<String, Integer> units = new LinkedHashMap<>();

        public Map<String, Integer> getUnits() {
            return units;
        }

        public void setUnits(Map<String, Integer> units) {
            this.units = units;
        }
    }

    public static class Simulation {
        /** Probability [0,1] that a stage attempt fails with a simulated equipment fault. */
        private double failureRate = 0.05;
        /** Probability [0,1] that equipment hangs and never answers (exercises timeouts). */
        private double hangRate = 0.01;
        /** Relative jitter applied to stage durations, e.g. 0.2 = +/-20%. */
        private double durationJitter = 0.2;
        /** Global multiplier for stage durations; handy to speed up demos and tests. */
        private double timeScale = 1.0;
        /** Optional RNG seed for reproducible runs; 0 = random. */
        private long seed = 0;
        private final SensorFeed sensorFeed = new SensorFeed();

        public double getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(double failureRate) {
            this.failureRate = failureRate;
        }

        public double getHangRate() {
            return hangRate;
        }

        public void setHangRate(double hangRate) {
            this.hangRate = hangRate;
        }

        public double getDurationJitter() {
            return durationJitter;
        }

        public void setDurationJitter(double durationJitter) {
            this.durationJitter = durationJitter;
        }

        public double getTimeScale() {
            return timeScale;
        }

        public void setTimeScale(double timeScale) {
            this.timeScale = timeScale;
        }

        public long getSeed() {
            return seed;
        }

        public void setSeed(long seed) {
            this.seed = seed;
        }

        public SensorFeed getSensorFeed() {
            return sensorFeed;
        }
    }

    public static class SensorFeed {
        private boolean enabled = true;
        private long intervalMs = 500;
        /** Sensor id -> random-walk channel definition. */
        private Map<String, SensorChannel> channels = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public Map<String, SensorChannel> getChannels() {
            return channels;
        }

        public void setChannels(Map<String, SensorChannel> channels) {
            this.channels = channels;
        }
    }

    public static class SensorChannel {
        private double min;
        private double max;
        private double start;
        private double step = 1.0;

        public double getMin() {
            return min;
        }

        public void setMin(double min) {
            this.min = min;
        }

        public double getMax() {
            return max;
        }

        public void setMax(double max) {
            this.max = max;
        }

        public double getStart() {
            return start;
        }

        public void setStart(double start) {
            this.start = start;
        }

        public double getStep() {
            return step;
        }

        public void setStep(double step) {
            this.step = step;
        }
    }

    public static class Diagnostics {
        /** Events retained per instance timeline. */
        private int timelineCapacity = 200;
        /** Events retained in the global recent-events buffer. */
        private int recentEventsCapacity = 500;
        /** Failures retained for the diagnostics summary. */
        private int recentFailuresCapacity = 50;

        public int getTimelineCapacity() {
            return timelineCapacity;
        }

        public void setTimelineCapacity(int timelineCapacity) {
            this.timelineCapacity = timelineCapacity;
        }

        public int getRecentEventsCapacity() {
            return recentEventsCapacity;
        }

        public void setRecentEventsCapacity(int recentEventsCapacity) {
            this.recentEventsCapacity = recentEventsCapacity;
        }

        public int getRecentFailuresCapacity() {
            return recentFailuresCapacity;
        }

        public void setRecentFailuresCapacity(int recentFailuresCapacity) {
            this.recentFailuresCapacity = recentFailuresCapacity;
        }
    }
}
