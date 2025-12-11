package com.plantloom.definition;

public class UnknownDefinitionException extends RuntimeException {

    public UnknownDefinitionException(String id) {
        super("No workflow definition with id '" + id + "'");
    }
}
