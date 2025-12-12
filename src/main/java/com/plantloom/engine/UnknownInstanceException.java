package com.plantloom.engine;

public class UnknownInstanceException extends RuntimeException {

    public UnknownInstanceException(String id) {
        super("No workflow instance with id '" + id + "'");
    }
}
