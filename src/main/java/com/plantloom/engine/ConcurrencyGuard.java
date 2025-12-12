package com.plantloom.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;

/**
 * Two layers of concurrency control for the engine:
 *
 * <ol>
 *   <li><b>Admission control</b> - a semaphore caps the number of simultaneously active
 *       instances so a burst of start requests cannot exhaust the worker pools.</li>
 *   <li><b>Per-instance serialization</b> - every state transition of an instance runs under
 *       that instance's lock, so a stage completion, a watchdog timeout and an operator cancel
 *       racing on different threads are applied one at a time.</li>
 * </ol>
 */
@Component
public class ConcurrencyGuard {

    private final Semaphore admission;
    private final int limit;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ConcurrencyGuard(PlantLoomProperties properties) {
        this.limit = Math.max(1, properties.getEngine().getMaxConcurrentInstances());
        this.admission = new Semaphore(limit, true);
    }

    public void admit() {
        if (!admission.tryAcquire()) {
            throw new CapacityExceededException(limit);
        }
    }

    public void release(String instanceId) {
        admission.release();
        locks.remove(instanceId);
    }

    public int activeCount() {
        return limit - admission.availablePermits();
    }

    public int limit() {
        return limit;
    }

    public void runExclusive(String instanceId, Runnable action) {
        callExclusive(instanceId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callExclusive(String instanceId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(instanceId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
