package com.plantloom.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.plantloom.diagnostics.DiagnosticsService;
import com.plantloom.diagnostics.EventStreamService;
import com.plantloom.engine.StageScheduler;
import com.plantloom.event.WorkflowEvent;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final DiagnosticsService diagnostics;
    private final EventStreamService stream;
    private final StageScheduler scheduler;

    public MonitoringController(DiagnosticsService diagnostics, EventStreamService stream,
                                StageScheduler scheduler) {
        this.diagnostics = diagnostics;
        this.stream = stream;
        this.scheduler = scheduler;
    }

    /** Live event feed. Optionally scoped to one instance; sensor readings are opt-in. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String instanceId,
                             @RequestParam(defaultValue = "false") boolean sensors) {
        return stream.subscribe(instanceId, sensors);
    }

    @GetMapping("/diagnostics")
    public DiagnosticsService.Summary diagnostics() {
        return diagnostics.summary();
    }

    /** Polling alternative to SSE: events after a given sequence number. */
    @GetMapping("/events")
    public List<WorkflowEvent> events(@RequestParam(defaultValue = "0") long after,
                                      @RequestParam(defaultValue = "200") int limit) {
        return diagnostics.recentEvents(after, Math.min(limit, 1000));
    }

    @GetMapping("/waiting")
    public List<StageScheduler.WaitingStage> waiting() {
        return scheduler.waitingStages();
    }
}
