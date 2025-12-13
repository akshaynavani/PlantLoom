package com.plantloom.equipment;

public class UnknownEquipmentException extends RuntimeException {

    public UnknownEquipmentException(String what) {
        super("Unknown equipment: " + what);
    }
}
