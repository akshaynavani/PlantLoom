package com.plantloom.rules;

import java.util.Map;

/**
 * Root object exposed to rule expressions. Expressions can read:
 * <ul>
 *   <li>{@code vars['name']} - instance variables</li>
 *   <li>{@code sensors['tank.level']} - latest plant sensor values</li>
 *   <li>{@code stages['mix']} - status name of another stage, e.g. {@code 'COMPLETED'}</li>
 * </ul>
 * Plain getters (rather than a record) keep it readable by SpEL's data-binding accessor.
 */
public final class RuleContext {

    private final Map<String, Object> vars;
    private final Map<String, Double> sensors;
    private final Map<String, String> stages;

    public RuleContext(Map<String, Object> vars, Map<String, Double> sensors, Map<String, String> stages) {
        this.vars = vars == null ? Map.of() : vars;
        this.sensors = sensors == null ? Map.of() : sensors;
        this.stages = stages == null ? Map.of() : stages;
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public Map<String, Double> getSensors() {
        return sensors;
    }

    public Map<String, String> getStages() {
        return stages;
    }
}
