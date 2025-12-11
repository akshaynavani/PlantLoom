# PlantLoom — Implementation Plan

**Event-Driven Industrial Workflow Orchestrator** (a.k.a. Industrial Workflow Orchestration Simulator /
Real-Time Monitoring & Control System)

**Tech:** Java 17, Spring Boot 3 (Web, Actuator, Validation), Spring application events, SpEL,
Micrometer, Jackson/YAML, JUnit 5 + AssertJ + Awaitility, Maven.

## Goal

A Spring Boot service that orchestrates multi-stage industrial processes (e.g. "mix -> heat ->
fill -> cap -> inspect") against a pool of simulated equipment. Stages are triggered by events,
gated by rule evaluation, run concurrently across many workflow instances, protected by
equipment-level concurrency control, bounded by timeouts with retry, and fully observable
through structured diagnostics, metrics and a live event stream.

## Key design decisions

- **In-process event bus** built on Spring's `ApplicationEventPublisher` + an `@Async` executor,
  so the engine is event-driven without needing an external broker. Every state change is an
  immutable `WorkflowEvent` record.
- **Workflow definitions** are declarative YAML (`src/main/resources/workflows/*.yml`): stages,
  required equipment type, duration, timeout, retry policy, `when` guard (SpEL) and `on`
  trigger events (e.g. wait for `SENSOR_READING` that satisfies a rule).
- **Rule evaluation** with SpEL against a restricted evaluation context (instance variables +
  latest sensor readings); no arbitrary type access.
- **Concurrency control**: equipment pool with a fair `Semaphore` per equipment type and
  per-unit `ReentrantLock`; per-instance serialization so an instance never runs two stages at
  once; global cap on concurrent instances.
- **Timeouts** via `CompletableFuture.orTimeout`, plus a scheduled watchdog that fails stuck
  instances; retry with exponential backoff.
- **Diagnostics**: per-instance timeline (bounded ring buffer), MDC-correlated structured logs,
  Micrometer counters/timers, custom Actuator health indicator, Server-Sent Events stream.

## Phases

### Phase 1 — Skeleton, domain model and workflow definitions
Deliverables: Maven build, Spring Boot app, domain model, YAML definition loader + validation,
sample workflows.
Files: `pom.xml`, `PlantLoomApplication.java`, `config/PlantLoomProperties.java`,
`domain/*` (`WorkflowDefinition`, `StageDefinition`, `RetryPolicy`, `WorkflowInstance`,
`StageExecution`, `InstanceStatus`, `StageStatus`), `definition/WorkflowDefinitionRegistry.java`,
`definition/DefinitionValidator.java`, `resources/application.yml`, `resources/workflows/*.yml`.

### Phase 2 — Event bus and orchestration engine core
Deliverables: event types, publisher, async executor config, instance repository, the
orchestrator state machine (start -> stage ready -> running -> completed/failed -> next stage).
Files: `event/*` (`WorkflowEvent`, `EventType`, `EventBus`), `config/AsyncConfig.java`,
`engine/InstanceRepository.java`, `engine/WorkflowOrchestrator.java`,
`engine/StageScheduler.java`.

### Phase 3 — Rules, equipment simulation, concurrency control, timeouts
Deliverables: SpEL rule engine, simulated equipment with fault injection, equipment pool with
semaphores/locks, stage executor with timeout + retry/backoff, watchdog, sensor event triggers.
Files: `rules/RuleEvaluator.java`, `equipment/*` (`Equipment`, `EquipmentPool`,
`EquipmentSimulator`, `EquipmentLease`), `engine/StageExecutor.java`,
`engine/TimeoutWatchdog.java`, `engine/ConcurrencyGuard.java`.

### Phase 4 — REST API, live monitoring and diagnostics
Deliverables: REST endpoints for definitions, instances (start/cancel/list/get timeline),
sensor ingestion, equipment status; SSE stream; Micrometer metrics; health indicator; MDC logs;
error handling.
Files: `api/*` (`WorkflowController`, `EquipmentController`, `SensorController`,
`MonitoringController`, DTOs, `ApiExceptionHandler`), `diagnostics/*` (`DiagnosticsService`,
`EngineMetrics`, `EngineHealthIndicator`, `Timeline`), `resources/logback-spring.xml`.

### Phase 5 — Tests, load simulation and docs
Deliverables: unit tests (rules, pool, validator), integration tests (end-to-end workflow,
timeout, retry, concurrency under load), a load-simulation runner, `.gitignore`, README.
Files: `src/test/java/**`, `simulation/LoadSimulationRunner.java`, `.gitignore`, `README.md`.
