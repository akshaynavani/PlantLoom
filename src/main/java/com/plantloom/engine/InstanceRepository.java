package com.plantloom.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.plantloom.domain.InstanceStatus;
import com.plantloom.domain.WorkflowInstance;

/**
 * In-memory store of workflow instances. Terminal instances are evicted oldest-first once the
 * store grows past {@link #MAX_RETAINED}, so long-running simulations do not leak memory.
 */
@Component
public class InstanceRepository {

    static final int MAX_RETAINED = 10_000;

    private final Map<String, WorkflowInstance> instances = new ConcurrentHashMap<>();

    public WorkflowInstance save(WorkflowInstance instance) {
        instances.put(instance.getId(), instance);
        if (instances.size() > MAX_RETAINED) {
            evictTerminal();
        }
        return instance;
    }

    public Optional<WorkflowInstance> find(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    public WorkflowInstance require(String id) {
        return find(id).orElseThrow(() -> new UnknownInstanceException(id));
    }

    public List<WorkflowInstance> all() {
        return instances.values().stream()
                .sorted(Comparator.comparing(WorkflowInstance::getCreatedAt).reversed())
                .toList();
    }

    public List<WorkflowInstance> byStatus(InstanceStatus status) {
        return all().stream().filter(i -> i.getStatus() == status).toList();
    }

    public List<WorkflowInstance> active() {
        return instances.values().stream().filter(i -> !i.isTerminal()).toList();
    }

    public Map<InstanceStatus, Long> countByStatus() {
        return instances.values().stream()
                .collect(Collectors.groupingBy(WorkflowInstance::getStatus, Collectors.counting()));
    }

    public int size() {
        return instances.size();
    }

    private synchronized void evictTerminal() {
        int excess = instances.size() - MAX_RETAINED;
        if (excess <= 0) {
            return;
        }
        instances.values().stream()
                .filter(WorkflowInstance::isTerminal)
                .sorted(Comparator.comparing(WorkflowInstance::getCreatedAt))
                .limit(excess)
                .map(WorkflowInstance::getId)
                .toList()
                .forEach(instances::remove);
    }
}
