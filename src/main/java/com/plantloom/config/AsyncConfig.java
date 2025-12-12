package com.plantloom.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.slf4j.MDC;

/**
 * Thread pools used by the engine.
 *
 * <ul>
 *   <li>{@code engineExecutor} - processes engine events (async {@code @EventListener}s)</li>
 *   <li>{@code taskScheduler} - delayed work: retry backoff, watchdog, sensor feed</li>
 *   <li>{@code equipmentExecutor} - runs simulated equipment work</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String ENGINE_EXECUTOR = "engineExecutor";
    public static final String EQUIPMENT_EXECUTOR = "equipmentExecutor";

    @Bean(name = ENGINE_EXECUTOR)
    public ThreadPoolTaskExecutor engineExecutor(PlantLoomProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int threads = Math.max(2, properties.getEngine().getWorkerThreads());
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(50_000);
        executor.setThreadNamePrefix("engine-");
        // Back-pressure: if the queue is ever full the publisher runs the handler itself.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcPropagating());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    @Bean(name = EQUIPMENT_EXECUTOR)
    public Executor equipmentExecutor(PlantLoomProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int units = properties.getEquipment().getUnits().values().stream().mapToInt(Integer::intValue).sum();
        int threads = Math.max(4, units);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("equipment-");
        executor.setTaskDecorator(mdcPropagating());
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /** Copies the caller's MDC (instanceId, stageId) onto the worker thread for correlated logs. */
    private static TaskDecorator mdcPropagating() {
        return runnable -> {
            var context = MDC.getCopyOfContextMap();
            return () -> {
                var previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
