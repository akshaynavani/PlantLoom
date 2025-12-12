package com.plantloom.event;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin facade over Spring's {@link ApplicationEventPublisher}. Assigns a global sequence number
 * to each event so consumers (timelines, SSE clients) can order and de-duplicate them.
 *
 * <p>Engine listeners are {@code @Async}, so publishing never blocks on stage processing.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final ApplicationEventPublisher publisher;
    private final AtomicLong sequence = new AtomicLong();

    public EventBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public WorkflowEvent publish(WorkflowEvent.Builder builder) {
        WorkflowEvent event = builder.build(sequence.incrementAndGet());
        if (log.isDebugEnabled()) {
            log.debug("event seq={} type={} instance={} stage={} attempt={} attrs={}",
                    event.sequence(), event.type(), event.instanceId(), event.stageId(),
                    event.attempt(), event.attributes());
        }
        publisher.publishEvent(event);
        return event;
    }

    public long lastSequence() {
        return sequence.get();
    }
}
