package com.plantloom.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.plantloom.config.PlantLoomProperties;

class EquipmentPoolTest {

    private static EquipmentPool pool(int mixers) {
        PlantLoomProperties properties = new PlantLoomProperties();
        properties.getEquipment().setUnits(Map.of("MIXER", mixers));
        return new EquipmentPool(properties);
    }

    @Test
    void leasesIdleUnitsImmediatelyThenQueues() {
        EquipmentPool pool = pool(2);

        CompletableFuture<EquipmentLease> a = pool.acquire("MIXER", "i1", "s");
        CompletableFuture<EquipmentLease> b = pool.acquire("MIXER", "i2", "s");
        CompletableFuture<EquipmentLease> c = pool.acquire("MIXER", "i3", "s");

        assertThat(a).isDone();
        assertThat(b).isDone();
        assertThat(c).isNotDone();
        assertThat(a.join().unit()).isNotSameAs(b.join().unit());

        a.join().release();
        assertThat(c).isDone();
        assertThat(c.join().unit().getInstanceId()).isEqualTo("i3");
    }

    @Test
    void servesWaitersInArrivalOrder() {
        EquipmentPool pool = pool(1);
        EquipmentLease first = pool.acquire("MIXER", "i0", "s").join();
        List<CompletableFuture<EquipmentLease>> waiters = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            waiters.add(pool.acquire("MIXER", "i" + i, "s"));
        }

        List<String> order = new ArrayList<>();
        EquipmentLease current = first;
        for (CompletableFuture<EquipmentLease> waiter : waiters) {
            current.release();
            current = waiter.join();
            order.add(current.unit().getInstanceId());
        }

        assertThat(order).containsExactly("i1", "i2", "i3", "i4", "i5");
    }

    @Test
    void abandonedWaitersAreSkipped() {
        EquipmentPool pool = pool(1);
        EquipmentLease held = pool.acquire("MIXER", "holder", "s").join();
        CompletableFuture<EquipmentLease> gaveUp = pool.acquire("MIXER", "impatient", "s");
        CompletableFuture<EquipmentLease> patient = pool.acquire("MIXER", "patient", "s");

        gaveUp.orTimeout(1, TimeUnit.MILLISECONDS);
        gaveUp.handle((l, e) -> null).join();
        held.release();

        assertThat(patient).isDone();
        assertThat(patient.join().unit().getInstanceId()).isEqualTo("patient");
    }

    @Test
    void releaseIsIdempotent() {
        EquipmentPool pool = pool(1);
        EquipmentLease lease = pool.acquire("MIXER", "i1", "s").join();
        CompletableFuture<EquipmentLease> next = pool.acquire("MIXER", "i2", "s");
        CompletableFuture<EquipmentLease> afterNext = pool.acquire("MIXER", "i3", "s");

        lease.release();
        lease.release();

        assertThat(next).isDone();
        assertThat(afterNext).isNotDone();
    }

    @Test
    void maintenanceTakesUnitsOutOfRotationAndBack() {
        EquipmentPool pool = pool(1);
        pool.setMaintenance("MIXER-1", true);

        CompletableFuture<EquipmentLease> waiting = pool.acquire("MIXER", "i1", "s");
        assertThat(waiting).isNotDone();
        assertThat(pool.stats().get(0).maintenance()).isEqualTo(1);

        pool.setMaintenance("MIXER-1", false);
        assertThat(waiting).isDone();
    }

    @Test
    void busyUnitEntersMaintenanceAfterItsJob() {
        EquipmentPool pool = pool(1);
        EquipmentLease lease = pool.acquire("MIXER", "i1", "s").join();

        pool.setMaintenance("MIXER-1", true);
        assertThat(lease.unit().getState()).isEqualTo(EquipmentState.BUSY);

        lease.release();
        assertThat(lease.unit().getState()).isEqualTo(EquipmentState.MAINTENANCE);
    }

    @Test
    void neverLeasesMoreUnitsThanExistUnderContention() throws Exception {
        int units = 3;
        int jobs = 200;
        EquipmentPool pool = pool(units);
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger maxInUse = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(jobs);
        ExecutorService threads = Executors.newFixedThreadPool(16);

        try {
            for (int i = 0; i < jobs; i++) {
                String id = "i" + i;
                threads.submit(() -> pool.acquire("MIXER", id, "s").thenAcceptAsync(lease -> {
                    int now = inUse.incrementAndGet();
                    maxInUse.accumulateAndGet(now, Math::max);
                    Thread.onSpinWait();
                    inUse.decrementAndGet();
                    completed.incrementAndGet();
                    lease.release();
                    done.countDown();
                }, threads));
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }

        assertThat(completed.get()).isEqualTo(jobs);
        assertThat(maxInUse.get()).isLessThanOrEqualTo(units);
        assertThat(pool.stats().get(0).idle()).isEqualTo(units);
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> pool(1).acquire("LASER", "i", "s"))
                .isInstanceOf(UnknownEquipmentException.class);
    }
}
