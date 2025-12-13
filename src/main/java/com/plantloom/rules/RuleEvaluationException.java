package com.plantloom.rules;

public class RuleEvaluationException extends RuntimeException {

    public RuleEvaluationException(String expression, Throwable cause) {
        super("Failed to evaluate rule '" + expression + "': " + cause.getMessage(), cause);
    }
}
