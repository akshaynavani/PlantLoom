package com.plantloom.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.AwaitCondition;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.WorkflowDefinition;

class DefinitionValidatorTest {

    private DefinitionValidator validator;

    @BeforeEach
    void setUp() {
        PlantLoomProperties properties = new PlantLoomProperties();
        properties.getEquipment().setUnits(Map.of("MIXER", 1));
        validator = new DefinitionValidator(properties);
    }

    @Test
    void acceptsWellFormedDefinition() {
        WorkflowDefinition definition = workflow(
                stage("mix", "MIXER", 100, 500L, "vars['x'] == 1", null),
                stage("gate", null, 0, null, null, new AwaitCondition("sensors['s'] > 1", 1000L)));

        assertThat(validator.collectProblems(definition)).isEmpty();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        WorkflowDefinition definition = workflow(
                stage("mix", "LASER", -1, 0L, "vars['x'] ==", null),
                stage("mix", null, 10, 5L, null, new AwaitCondition(" ", -1L)));

        List<String> problems = validator.collectProblems(definition);

        assertThat(problems).anyMatch(p -> p.contains("unknown equipment type 'LASER'"));
        assertThat(problems).anyMatch(p -> p.contains("durationMs must be >= 0"));
        assertThat(problems).anyMatch(p -> p.contains("timeoutMs must be > 0"));
        assertThat(problems).anyMatch(p -> p.contains("invalid expression"));
        assertThat(problems).anyMatch(p -> p.contains("duplicate stage id"));
        assertThat(problems).anyMatch(p -> p.contains("shorter than the nominal duration"));
        assertThat(problems).anyMatch(p -> p.contains("await.condition is required"));
        assertThat(problems).anyMatch(p -> p.contains("await.timeoutMs must be > 0"));
    }

    @Test
    void rejectsEmptyWorkflow() {
        WorkflowDefinition definition = new WorkflowDefinition("empty", null, null, 1, null, List.of());

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(DefinitionValidationException.class)
                .hasMessageContaining("at least one stage");
    }

    private static WorkflowDefinition workflow(StageDefinition... stages) {
        return new WorkflowDefinition("wf", "Workflow", null, 1, null, List.of(stages));
    }

    private static StageDefinition stage(String id, String equipment, long duration, Long timeout, String when,
                                         AwaitCondition await) {
        return new StageDefinition(id, null, equipment, duration, timeout, null, when, await, null);
    }
}
