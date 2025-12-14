package com.plantloom.equipment;

/** Simulated hardware fault reported by an equipment unit during a job. */
public class EquipmentFaultException extends RuntimeException {

    private final String unitId;
    private final String code;

    public EquipmentFaultException(String unitId, String code, String message) {
        super(unitId + " fault " + code + ": " + message);
        this.unitId = unitId;
        this.code = code;
    }

    public String getUnitId() {
        return unitId;
    }

    public String getCode() {
        return code;
    }
}
