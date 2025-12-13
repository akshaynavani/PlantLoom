package com.plantloom.equipment;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exclusive right to use one equipment unit. Releasing is idempotent, so the normal completion
 * path, the timeout path and a cancel can all call {@link #release()} safely.
 */
public final class EquipmentLease implements AutoCloseable {

    private final Equipment unit;
    private final EquipmentPool pool;
    private final Instant acquiredAt = Instant.now();
    private final AtomicBoolean released = new AtomicBoolean();

    EquipmentLease(Equipment unit, EquipmentPool pool) {
        this.unit = unit;
        this.pool = pool;
    }

    public Equipment unit() {
        return unit;
    }

    public Instant acquiredAt() {
        return acquiredAt;
    }

    public boolean isReleased() {
        return released.get();
    }

    public void release() {
        if (released.compareAndSet(false, true)) {
            pool.release(unit);
        }
    }

    @Override
    public void close() {
        release();
    }
}
