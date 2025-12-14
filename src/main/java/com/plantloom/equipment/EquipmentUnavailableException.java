package com.plantloom.equipment;

/** No unit of the requested type became free within the acquisition timeout. */
public class EquipmentUnavailableException extends RuntimeException {

    public EquipmentUnavailableException(String type, long waitedMs) {
        super("No " + type + " available after " + waitedMs + " ms");
    }
}
