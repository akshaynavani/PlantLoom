package com.plantloom.engine;

import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.WorkflowInstance;

/**
 * Executes a ready stage (lease equipment, run, apply outputs) and reports the outcome back as
 * events. Split out from the scheduler so execution strategy can be swapped or stubbed in tests.
 */
public interface StageRunner {

    void run(WorkflowInstance instance, StageDefinition stage);

    /** Aborts any in-flight work for the instance and releases equipment it holds. */
    void cancel(String instanceId);
}
