package com.plantloom.rules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import com.plantloom.domain.StageExecution;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.sensor.SensorStore;

/**
 * SpEL-based rule engine for stage guards and await triggers.
 *
 * <p>Expressions run in a {@link SimpleEvaluationContext} with read-only data binding: they can
 * read properties and index maps, but cannot construct types, call static methods or mutate
 * state - workflow files are configuration, not code. Parsed expressions are cached.
 */
@Component
public class RuleEvaluator implements ConditionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
    private final SensorStore sensors;

    public RuleEvaluator(SensorStore sensors) {
        this.sensors = sensors;
    }

    @Override
    public boolean test(String expression, WorkflowInstance instance) {
        Map<String, String> stages = new LinkedHashMap<>();
        for (StageExecution execution : instance.executions()) {
            stages.put(execution.getStageId(), execution.getStatus().name());
        }
        return evaluate(expression, new RuleContext(instance.variablesSnapshot(), sensors.values(), stages));
    }

    /** Evaluates against an explicit context; used by tests and the rule dry-run endpoint. */
    public boolean evaluate(String expression, RuleContext root) {
        try {
            Expression parsed = cache.computeIfAbsent(expression, parser::parseExpression);
            Boolean result = parsed.getValue(context, root, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (ExpressionException e) {
            throw new RuleEvaluationException(expression, e);
        }
    }
}
