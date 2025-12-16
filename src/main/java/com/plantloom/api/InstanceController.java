package com.plantloom.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.plantloom.api.ApiDtos.BatchStartRequest;
import com.plantloom.api.ApiDtos.BatchStartResponse;
import com.plantloom.api.ApiDtos.CancelRequest;
import com.plantloom.api.ApiDtos.InstanceDetail;
import com.plantloom.api.ApiDtos.InstanceSummary;
import com.plantloom.api.ApiDtos.StartInstanceRequest;
import com.plantloom.diagnostics.DiagnosticsService;
import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.WorkflowInstance;
import com.plantloom.engine.CapacityExceededException;
import com.plantloom.engine.InstanceRepository;
import com.plantloom.engine.WorkflowOrchestrator;
import com.plantloom.event.WorkflowEvent;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final WorkflowOrchestrator orchestrator;
    private final InstanceRepository repository;
    private final DiagnosticsService diagnostics;

    public InstanceController(WorkflowOrchestrator orchestrator, InstanceRepository repository,
                              DiagnosticsService diagnostics) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.diagnostics = diagnostics;
    }

    @PostMapping
    public ResponseEntity<InstanceSummary> start(@Valid @RequestBody StartInstanceRequest request) {
        WorkflowInstance instance = orchestrator.start(request.definitionId(), request.variables());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(InstanceSummary.of(instance));
    }

    /** Starts many instances at once; useful for exercising the engine under concurrent load. */
    @PostMapping("/batch")
    public ResponseEntity<BatchStartResponse> startBatch(@Valid @RequestBody BatchStartRequest request) {
        List<String> ids = new ArrayList<>();
        int rejected = 0;
        for (int i = 0; i < request.count(); i++) {
            try {
                ids.add(orchestrator.start(request.definitionId(), request.variables()).getId());
            } catch (CapacityExceededException e) {
                rejected++;
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BatchStartResponse(request.count(), ids.size(), rejected, ids));
    }

    @GetMapping
    public List<InstanceSummary> list(@RequestParam(required = false) InstanceStatus status,
                                      @RequestParam(required = false) String definitionId,
                                      @RequestParam(defaultValue = "100") int limit) {
        return (status != null ? repository.byStatus(status) : repository.all()).stream()
                .filter(i -> definitionId == null || definitionId.equals(i.getDefinitionId()))
                .limit(Math.max(1, Math.min(limit, 1000)))
                .map(InstanceSummary::of)
                .toList();
    }

    @GetMapping("/{id}")
    public InstanceDetail get(@PathVariable String id) {
        return InstanceDetail.of(repository.require(id));
    }

    @GetMapping("/{id}/timeline")
    public List<WorkflowEvent> timeline(@PathVariable String id) {
        repository.require(id);
        return diagnostics.timeline(id);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<InstanceSummary> cancel(@PathVariable String id,
                                                  @RequestBody(required = false) CancelRequest request) {
        boolean cancelled = orchestrator.cancel(id, request != null ? request.reason() : null);
        InstanceSummary summary = InstanceSummary.of(repository.require(id));
        return ResponseEntity.status(cancelled ? HttpStatus.OK : HttpStatus.CONFLICT).body(summary);
    }
}
