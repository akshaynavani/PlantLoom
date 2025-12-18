package com.plantloom.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.WorkflowDefinition;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.event.EventBus;
import com.plantloom.sensor.SensorStore;

class RuleEvaluatorTest {

    private final SensorStore sensors = new SensorStore(new EventBus(event -> { }));
    private final RuleEvaluator evaluator = new RuleEvaluator(sensors);

    @Test
    void readsVariablesSensorsAndStageStatuses() {
        RuleContext context = new RuleContext(
                Map.of("qualityCheck", true, "finish", "fine", "batchSize", 500),
                Map.of("tank.level", 42.0),
                Map.of("mix", "COMPLETED"));

        assertThat(evaluator.evaluate("vars['qualityCheck'] == true", context)).isTrue();
        assertThat(evaluator.evaluate("vars['finish'] == 'fine' && vars['batchSize'] > 100", context)).isTrue();
        assertThat(evaluator.evaluate("sensors['tank.level'] >= 30", context)).isTrue();
        assertThat(evaluator.evaluate("sensors['tank.level'] < 30", context)).isFalse();
        assertThat(evaluator.evaluate("stages['mix'] == 'COMPLETED'", context)).isTrue();
    }

    @Test
    void missingValuesAreFalsyRatherThanErrors() {
        RuleContext context = new RuleContext(Map.of(), Map.of(), Map.of());

        assertThat(evaluator.evaluate("vars['missing'] == true", context)).isFalse();
        assertThat(evaluator.evaluate("sensors['missing'] >= 30", context)).isFalse();
    }

    @Test
    void evaluatesAgainstLiveInstanceAndSensorState() {
        WorkflowDefinition definition = new WorkflowDefinition("wf", null, null, 1, Map.of("mode", "auto"),
                List.of(new StageDefinition("only", null, null, 0, null, null, null, null, null)));
        WorkflowInstance instance = new WorkflowInstance("i-1", definition, Map.of("operator", "ana"));
        sensors.record("line.pressure", 1.4, "test");

        assertThat(evaluator.test("vars['mode'] == 'auto' && vars['operator'] == 'ana'", instance)).isTrue();
        assertThat(evaluator.test("sensors['line.pressure'] > 1.0", instance)).isTrue();
        assertThat(evaluator.test("stages['only'] == 'PENDING'", instance)).isTrue();
    }

    @Test
    void rejectsTypeReferencesAndMethodCalls() {
        RuleContext context = new RuleContext(Map.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate("T(java.lang.Runtime).getRuntime() != null", context))
                .isInstanceOf(RuleEvaluationException.class);
        assertThatThrownBy(() -> evaluator.evaluate("new java.io.File('/tmp').exists()", context))
                .isInstanceOf(RuleEvaluationException.class);
    }
}
