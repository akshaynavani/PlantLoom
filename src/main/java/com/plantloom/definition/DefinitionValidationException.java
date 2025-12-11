package com.plantloom.definition;

import java.util.List;

public class DefinitionValidationException extends RuntimeException {

    private final List<String> problems;

    public DefinitionValidationException(String definitionId, List<String> problems) {
        super("Invalid workflow definition '" + definitionId + "': " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}
