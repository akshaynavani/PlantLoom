package com.plantloom.diagnostics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.plantloom.event.WorkflowEvent;

/**
 * Bounded, thread-safe ring buffer of events. Oldest entries are dropped once capacity is hit,
 * so diagnostics memory stays flat no matter how long the plant runs.
 */
public class Timeline {

    private final int capacity;
    private final Deque<WorkflowEvent> events;
    private long dropped;

    public Timeline(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.events = new ArrayDeque<>(Math.min(this.capacity, 256));
    }

    public synchronized void add(WorkflowEvent event) {
        if (events.size() == capacity) {
            events.pollFirst();
            dropped++;
        }
        events.addLast(event);
    }

    public synchronized List<WorkflowEvent> snapshot() {
        return List.copyOf(events);
    }

    /** Events with a sequence strictly greater than {@code afterSequence}, up to {@code limit}. */
    public synchronized List<WorkflowEvent> after(long afterSequence, int limit) {
        return events.stream()
                .filter(e -> e.sequence() > afterSequence)
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized long dropped() {
        return dropped;
    }
}
