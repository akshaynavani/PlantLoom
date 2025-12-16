package com.plantloom.api;

import java.util.Collection;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.plantloom.api.ApiDtos.RuleEvaluationRequest;
import com.plantloom.api.ApiDtos.RuleEvaluationResponse;
import com.plantloom.api.ApiDtos.SensorReadingRequest;
import com.plantloom.rules.RuleContext;
import com.plantloom.rules.RuleEvaluator;
import com.plantloom.sensor.SensorReading;
import com.plantloom.sensor.SensorStore;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class SensorController {

    private final SensorStore sensors;
    private final RuleEvaluator rules;

    public SensorController(SensorStore sensors, RuleEvaluator rules) {
        this.sensors = sensors;
        this.rules = rules;
    }

    @GetMapping("/sensors")
    public Collection<SensorReading> list() {
        return sensors.all();
    }

    /** Ingests a reading (e.g. from a PLC gateway); may release stages waiting on a trigger. */
    @PostMapping("/sensors")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SensorReading ingest(@Valid @RequestBody SensorReadingRequest request) {
        return sensors.record(request.sensorId(), request.value(), "api");
    }

    /** Dry-runs a rule expression so workflow authors can check guards before deploying. */
    @PostMapping("/rules/evaluate")
    public RuleEvaluationResponse evaluate(@Valid @RequestBody RuleEvaluationRequest request) {
        var sensorValues = request.sensors() != null ? request.sensors() : sensors.values();
        boolean result = rules.evaluate(request.expression(),
                new RuleContext(request.vars(), sensorValues, request.stages()));
        return new RuleEvaluationResponse(request.expression(), result);
    }
}
