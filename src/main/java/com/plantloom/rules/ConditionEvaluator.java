package com.plantloom.rules;

import com.plantloom.domain.WorkflowInstance;

/**
 * Evaluates guard and trigger expressions against an instance and the current plant state.
 */
public interface ConditionEvaluator {

    boolean test(String expression, WorkflowInstance instance);
}
