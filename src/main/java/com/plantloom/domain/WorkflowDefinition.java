package com.plantloom.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Declarative description of a multi-stage process, loaded from YAML.
 * Stages are executed in order; guards may skip stages and await conditions may hold them.
 */
public record WorkflowDefinition(
        String id,
        String name,
        String description,
        int version,
        Map<String, Object> variables,
        List<StageDefinition> stages) {

    public WorkflowDefinition {
        if (version <= 0) {
            version = 1;
        }
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public Optional<StageDefinition> stage(int index) {
        return index >= 0 && index < stages.size() ? Optional.of(stages.get(index)) : Optional.empty();
    }

    public int indexOf(String stageId) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).id().equals(stageId)) {
                return i;
            }
        }
        return -1;
    }
}
