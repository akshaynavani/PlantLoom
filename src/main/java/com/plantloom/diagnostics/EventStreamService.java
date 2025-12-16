package com.plantloom.diagnostics;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.plantloom.event.EventType;
import com.plantloom.event.WorkflowEvent;

import jakarta.annotation.PreDestroy;

/**
 * Fans engine events out to Server-Sent Events subscribers for live monitoring dashboards.
 * Delivery happens on a dedicated thread so a slow client can never stall the engine.
 */
@Service
public class EventStreamService {

    private static final Logger log = LoggerFactory.getLogger(EventStreamService.class);

    private record Subscriber(SseEmitter emitter, String instanceId, boolean includeSensors) {
        boolean wants(WorkflowEvent event) {
            if (event.type() == EventType.SENSOR_READING) {
                return includeSensors && instanceId == null;
            }
            return instanceId == null || instanceId.equals(event.instanceId());
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-delivery");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe(String instanceId, boolean includeSensors) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, instanceId, includeSensors);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
        log.info("SSE subscriber added (instance={}, sensors={}); total={}", instanceId, includeSensors,
                subscribers.size());
        return emitter;
    }

    @EventListener
    public void onEvent(WorkflowEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        delivery.execute(() -> {
            for (Subscriber subscriber : subscribers) {
                if (!subscriber.wants(event)) {
                    continue;
                }
                try {
                    subscriber.emitter().send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(event.type().name())
                            .data(event));
                } catch (IOException | IllegalStateException e) {
                    subscribers.remove(subscriber);
                    subscriber.emitter().completeWithError(e);
                }
            }
        });
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    @PreDestroy
    void shutdown() {
        subscribers.forEach(s -> s.emitter().complete());
        delivery.shutdownNow();
    }
}
