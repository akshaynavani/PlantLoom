package com.plantloom.equipment;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;

/**
 * Allocates equipment units to stages.
 *
 * <p>Each equipment type has its own pool with a FIFO queue of waiters. Acquisition is
 * <em>non-blocking</em>: callers get a {@link CompletableFuture} that completes with a lease when
 * a unit frees up, so engine threads are never parked waiting for a machine. Callers bound the
 * wait with {@code orTimeout}; a waiter whose future is already done (timed out or cancelled) is
 * skipped and the unit goes to the next one in line. This guarantees:
 * <ul>
 *   <li>a unit is never leased to two stages at once (mutual exclusion)</li>
 *   <li>waiters are served in arrival order (fairness, no starvation)</li>
 *   <li>abandoned waiters never leak a unit</li>
 * </ul>
 */
@Component
public class EquipmentPool {

    private static final Logger log = LoggerFactory.getLogger(EquipmentPool.class);

    /** Point-in-time utilisation of one equipment type. */
    public record TypeStats(String type, int total, int idle, int busy, int maintenance, int queued) {
    }

    private record Waiter(String instanceId, String stageId, CompletableFuture<EquipmentLease> future,
                          Instant since) {
    }

    private record Handoff(CompletableFuture<EquipmentLease> future, EquipmentLease lease) {
    }

    private static final class TypePool {
        final String type;
        final List<Equipment> units = new ArrayList<>();
        final Deque<Waiter> waiters = new ArrayDeque<>();

        TypePool(String type) {
            this.type = type;
        }

        Equipment firstAvailable() {
            for (Equipment unit : units) {
                if (unit.isAvailable()) {
                    return unit;
                }
            }
            return null;
        }
    }

    private final Map<String, TypePool> pools = new LinkedHashMap<>();
    private final Map<String, Equipment> byId = new LinkedHashMap<>();

    public EquipmentPool(PlantLoomProperties properties) {
        properties.getEquipment().getUnits().forEach((type, count) -> {
            TypePool pool = new TypePool(type);
            for (int i = 1; i <= Math.max(0, count); i++) {
                Equipment unit = new Equipment(type + "-" + i, type);
                pool.units.add(unit);
                byId.put(unit.getId(), unit);
            }
            pools.put(type, pool);
        });
        log.info("Equipment pool initialised: {}", properties.getEquipment().getUnits());
    }

    /**
     * Requests a unit of the given type. The returned future completes with a lease as soon as
     * a unit is available. Cancel or time out the future to give up the place in the queue.
     */
    public CompletableFuture<EquipmentLease> acquire(String type, String instanceId, String stageId) {
        TypePool pool = pool(type);
        synchronized (pool) {
            pool.waiters.removeIf(w -> w.future().isDone());
            if (pool.waiters.isEmpty()) {
                Equipment unit = pool.firstAvailable();
                if (unit != null) {
                    unit.claim(instanceId, stageId);
                    return CompletableFuture.completedFuture(new EquipmentLease(unit, this));
                }
            }
            Waiter waiter = new Waiter(instanceId, stageId, new CompletableFuture<>(), Instant.now());
            pool.waiters.addLast(waiter);
            return waiter.future();
        }
    }

    void release(Equipment unit) {
        TypePool pool = pool(unit.getType());
        List<Handoff> handoffs;
        synchronized (pool) {
            if (!unit.free()) {
                return;
            }
            handoffs = dispatch(pool);
        }
        complete(handoffs);
    }

    /** Puts a unit into (or out of) maintenance. Busy units switch once their job finishes. */
    public Equipment setMaintenance(String unitId, boolean enabled) {
        Equipment unit = unit(unitId);
        TypePool pool = pool(unit.getType());
        List<Handoff> handoffs = List.of();
        synchronized (pool) {
            if (unit.setMaintenance(enabled)) {
                handoffs = dispatch(pool);
            }
        }
        complete(handoffs);
        log.info("Equipment {} maintenance={} state={}", unitId, enabled, unit.getState());
        return unit;
    }

    public void recordCompletion(Equipment unit) {
        unit.recordCompletion();
    }

    public void recordFault(Equipment unit) {
        unit.recordFault();
    }

    /** Must be called while holding the pool monitor; completions happen outside it. */
    private List<Handoff> dispatch(TypePool pool) {
        List<Handoff> handoffs = new ArrayList<>();
        Equipment unit;
        while ((unit = pool.firstAvailable()) != null) {
            Waiter waiter = pool.waiters.pollFirst();
            if (waiter == null) {
                break;
            }
            if (waiter.future().isDone()) {
                continue;
            }
            unit.claim(waiter.instanceId(), waiter.stageId());
            handoffs.add(new Handoff(waiter.future(), new EquipmentLease(unit, this)));
        }
        return handoffs;
    }

    /**
     * Completes waiter futures outside the pool lock so their continuations cannot re-enter it.
     * If a waiter gave up concurrently the lease is returned and passed on.
     */
    private void complete(List<Handoff> handoffs) {
        for (Handoff handoff : handoffs) {
            if (!handoff.future().complete(handoff.lease())) {
                handoff.lease().release();
            }
        }
    }

    public Equipment unit(String unitId) {
        Equipment unit = byId.get(unitId);
        if (unit == null) {
            throw new UnknownEquipmentException("unit '" + unitId + "'");
        }
        return unit;
    }

    public Optional<Equipment> findUnit(String unitId) {
        return Optional.ofNullable(byId.get(unitId));
    }

    public Collection<Equipment> units() {
        return List.copyOf(byId.values());
    }

    public boolean hasType(String type) {
        return pools.containsKey(type);
    }

    public List<TypeStats> stats() {
        List<TypeStats> stats = new ArrayList<>();
        for (TypePool pool : pools.values()) {
            synchronized (pool) {
                int idle = 0;
                int busy = 0;
                int maintenance = 0;
                for (Equipment unit : pool.units) {
                    switch (unit.getState()) {
                        case IDLE -> idle++;
                        case BUSY -> busy++;
                        case MAINTENANCE -> maintenance++;
                    }
                }
                int queued = (int) pool.waiters.stream().filter(w -> !w.future().isDone()).count();
                stats.add(new TypeStats(pool.type, pool.units.size(), idle, busy, maintenance, queued));
            }
        }
        return stats;
    }

    private TypePool pool(String type) {
        TypePool pool = pools.get(type);
        if (pool == null) {
            throw new UnknownEquipmentException("type '" + type + "'");
        }
        return pool;
    }
}
