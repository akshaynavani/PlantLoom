package com.plantloom.definition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.StageDefinition;
import com.plantloom.domain.WorkflowDefinition;

/**
 * Structural validation of workflow definitions. Fails fast at load time so a typo in a YAML
 * file never surfaces as a half-executed production run.
 */
@Component
public class DefinitionValidator {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final PlantLoomProperties properties;

    public DefinitionValidator(PlantLoomProperties properties) {
        this.properties = properties;
    }

    public void validate(WorkflowDefinition definition) {
        List<String> problems = collectProblems(definition);
        if (!problems.isEmpty()) {
            throw new DefinitionValidationException(definition.id(), problems);
        }
    }

    public List<String> collectProblems(WorkflowDefinition definition) {
        List<String> problems = new ArrayList<>();
        if (isBlank(definition.id())) {
            problems.add("workflow id is required");
        }
        if (definition.stages().isEmpty()) {
            problems.add("workflow must declare at least one stage");
        }

        Set<String> seen = new HashSet<>();
        Set<String> knownEquipment = properties.getEquipment().getUnits().keySet();
        for (int i = 0; i < definition.stages().size(); i++) {
            StageDefinition stage = definition.stages().get(i);
            String where = "stage[" + i + "]" + (stage.id() != null ? " '" + stage.id() + "'" : "");

            if (isBlank(stage.id())) {
                problems.add(where + ": id is required");
            } else if (!seen.add(stage.id())) {
                problems.add(where + ": duplicate stage id");
            }
            if (stage.durationMs() < 0) {
                problems.add(where + ": durationMs must be >= 0");
            }
            if (stage.timeoutMs() != null && stage.timeoutMs() <= 0) {
                problems.add(where + ": timeoutMs must be > 0");
            }
            if (stage.timeoutMs() != null && stage.timeoutMs() < stage.durationMs()) {
                problems.add(where + ": timeoutMs is shorter than the nominal duration");
            }
            if (stage.requiresEquipment() && !knownEquipment.contains(stage.equipmentType())) {
                problems.add(where + ": unknown equipment type '" + stage.equipmentType()
                        + "' (configured: " + knownEquipment + ")");
            }
            if (stage.hasGuard()) {
                checkExpression(where + ".when", stage.when(), problems);
            }
            if (stage.await() != null) {
                if (!stage.hasAwait()) {
                    problems.add(where + ": await.condition is required when await is declared");
                } else {
                    checkExpression(where + ".await.condition", stage.await().condition(), problems);
                }
                if (stage.await().timeoutMs() != null && stage.await().timeoutMs() <= 0) {
                    problems.add(where + ": await.timeoutMs must be > 0");
                }
            }
        }
        return problems;
    }

    private void checkExpression(String where, String expression, List<String> problems) {
        try {
            parser.parseExpression(expression);
        } catch (ParseException e) {
            problems.add(where + ": invalid expression '" + expression + "' (" + e.getSimpleMessage() + ")");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
