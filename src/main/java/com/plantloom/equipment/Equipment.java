package com.plantloom.equipment;

import java.time.Instant;

/**
 * A single simulated equipment unit (e.g. {@code MIXER-1}). State changes are synchronized and
 * only performed by {@link EquipmentPool}, which owns allocation.
 */
public class Equipment {

    private final String id;
    private final String type;
    private EquipmentState state = EquipmentState.IDLE;
    private boolean maintenanceRequested;
    private String instanceId;
    private String stageId;
    private Instant busySince;
    private long completedJobs;
    private long faults;
    private long busyMillisTotal;

    public Equipment(String id, String type) {
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public synchronized EquipmentState getState() {
        return state;
    }

    public synchronized String getInstanceId() {
        return instanceId;
    }

    public synchronized String getStageId() {
        return stageId;
    }

    public synchronized Instant getBusySince() {
        return busySince;
    }

    public synchronized long getCompletedJobs() {
        return completedJobs;
    }

    public synchronized long getFaults() {
        return faults;
    }

    public synchronized long getBusyMillisTotal() {
        return busyMillisTotal;
    }

    public synchronized boolean isMaintenanceRequested() {
        return maintenanceRequested;
    }

    synchronized boolean isAvailable() {
        return state == EquipmentState.IDLE;
    }

    synchronized void claim(String instanceId, String stageId) {
        if (state != EquipmentState.IDLE) {
            throw new IllegalStateException(id + " is not idle (" + state + ")");
        }
        state = EquipmentState.BUSY;
        this.instanceId = instanceId;
        this.stageId = stageId;
        this.busySince = Instant.now();
    }

    /** Frees the unit; returns true if it is available for the next job. */
    synchronized boolean free() {
        if (busySince != null) {
            busyMillisTotal += Instant.now().toEpochMilli() - busySince.toEpochMilli();
        }
        instanceId = null;
        stageId = null;
        busySince = null;
        state = maintenanceRequested ? EquipmentState.MAINTENANCE : EquipmentState.IDLE;
        return state == EquipmentState.IDLE;
    }

    /** Requests or clears maintenance. A busy unit enters maintenance when its current job ends. */
    synchronized boolean setMaintenance(boolean enabled) {
        maintenanceRequested = enabled;
        if (enabled && state == EquipmentState.IDLE) {
            state = EquipmentState.MAINTENANCE;
        } else if (!enabled && state == EquipmentState.MAINTENANCE) {
            state = EquipmentState.IDLE;
        }
        return state == EquipmentState.IDLE;
    }

    synchronized void recordCompletion() {
        completedJobs++;
    }

    synchronized void recordFault() {
        faults++;
    }
}
