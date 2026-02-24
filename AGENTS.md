# AGENTS.md

## Project Overview

Evochora is an artificial life simulator for research into digital evolution. It features:
- Custom n-dimensional simulation environment with thermodynamic constraints
- Multi-pass EvoASM compiler converting assembly to VM machine code
- High-performance runtime with embodied virtual organisms
- Modular data pipeline separating hot execution from cold data processing
- Web-based visualization and analysis frontends

## Repository Layout

- `src/main/java/` – Application code (compiler, runtime, datapipeline, CLI, node)
- `src/main/proto/` – Protobuf definitions for data pipeline communication
- `src/main/resources/` – Configuration files, compiler messages, reference.conf
- `src/test/java/` – Unit and integration tests
- `src/test/resources/` – Test resources and configurations
- `src/testFixtures/` – JUnit extensions and test utilities
- `docs/` – Documentation (ASSEMBLY_SPEC.md, CLI_USAGE.md, proposals)
- `assembly/` – Assembly code examples and test files
- `config/` – User-facing configuration (evochora.conf, local.conf)
- `build.gradle.kts` – Gradle build configuration
- `gradlew`, `gradlew.bat`, `gradle/wrapper/` – Gradle wrapper

## Build & Run (Java/Gradle)

**Java 21 required.** Configure in IDE or use `JAVA_HOME`.

```bash
./gradlew build              # Full build with tests
./gradlew clean assemble     # Assemble without tests
./gradlew test               # All tests
./gradlew unit               # Fast unit tests only (@Tag("unit"))
./gradlew integration        # Integration tests only (@Tag("integration"))
./gradlew run --args="node run"  # Run simulation node
./gradlew run --args="--help"    # Show CLI help
./gradlew distZip distTar    # Create distribution archives
```

## Running the Application

**Start the simulation node:**
```bash
./gradlew run --args="node run"
```

**With custom configuration:**
```bash
./gradlew run --args="--config my-config.conf node run"
```

**Show available commands:**
```bash
./gradlew run --args="--help"
```

**Get help for specific command:**
```bash
./gradlew run --args="help compile"
./gradlew run --args="help node"
```

### HTTP API for Pipeline Control

When the node is running, it exposes a REST API for controlling and monitoring the data pipeline:

**Pipeline-wide control:**
- `GET /api/pipeline/status` - Get overall pipeline status
- `POST /api/pipeline/start` - Start all services
- `POST /api/pipeline/stop` - Stop all services
- `POST /api/pipeline/restart` - Restart all services
- `POST /api/pipeline/pause` - Pause all services
- `POST /api/pipeline/resume` - Resume all services

**Individual service control:**
- `GET /api/pipeline/service/{serviceName}/status` - Get service status
- `POST /api/pipeline/service/{serviceName}/start` - Start specific service
- `POST /api/pipeline/service/{serviceName}/stop` - Stop specific service
- `POST /api/pipeline/service/{serviceName}/restart` - Restart specific service
- `POST /api/pipeline/service/{serviceName}/pause` - Pause specific service
- `POST /api/pipeline/service/{serviceName}/resume` - Resume specific service

## Assembly Compile System
The compiler can be invoked in multiple equivalent ways. For details and examples, see the **Compile** section in `docs/CLI_USAGE.md`.

- Primary user-facing entry point: `bin/evochora compile --file=<path> [--env=<dimensions>[:<toroidal>]]`
- Developer entry point via JAR (after `./gradlew jar`):
  `java -jar build/libs/evochora.jar compile --file=<path> [--env=<dimensions>[:<toroidal>]]`

The compiler produces a JSON `ProgramArtifact` with machine code layout, labels, registers, procedures, environment properties, and source/ token maps that can be used for debugging and analysis.

## Configuration

### File Layout

- `config/evochora.conf` — User-facing experiment template (overrides selected defaults)
- `src/main/resources/reference.conf` — All defaults with documentation (embedded in JAR, loaded automatically)
- `config/local.conf` — Local development overrides (not checked in)

### Config Resolution Cascade (in `ConfigLoader.resolve()`)

1. `--config` CLI option
2. `-Dconfig.file` system property
3. `config/evochora.conf` in CWD
4. `APP_HOME/config/evochora.conf` (detected from JAR location)
5. Classpath `reference.conf` only (fallback)

### Tuning Profiles

Services share pipeline tuning parameters via HOCON substitutions:
- `pipeline.tuning = ${profiles.detailed}` (or `sampled`, `sparse`)
- Services consume via `${pipeline.tuning.samplingInterval}`, `${pipeline.tuning.insertBatchSize}`, etc.
- Override in experiment config or `local.conf`: `pipeline.tuning = ${profiles.sampled}`

### Config Priority (within a single file)

System properties > Environment variables > User config file > reference.conf defaults

## Agent Guidelines

There is a central document for AI agent guidelines that defines architectural principles and review standards. All agents, automated workflows, and human developers should adhere to these rules.

- **Single Source of Truth:** [`/.agents/architecture-guidelines.md`](/.agents/architecture-guidelines.md)

### General Principles
- **Allowed changes**: Refactors, bug fixes, unit tests, documentation improvements, safe dependency updates (patch/minor versions)
- **Avoid without explicit request**: Core configuration changes, architectural modifications, breaking changes
- **Code quality**: Prefer minimal diffs, write comprehensive tests, maintain existing code style
- **Communication**: Explain reasoning for changes, ask when uncertain about architectural decisions

# Architectural Principles

## Compiler (`src/main/java/org/evochora/compiler/`)

- **Immutability**: Compiler phases are immutable - each phase creates an immutable object and passes it to the next
- **Single Execution**: Every compiler phase runs exactly once; no phase may access a previous phase
- **No Direct Calls**: Compiler phases never call other compiler phases directly
- **Multi-Pass Pipeline**: Preprocessor → Lexer → Parser → Semantic Analyzer → IR Generator → Layout Engine → Linker → Emitter
- **Handler Pattern**: Phase main classes (PreProcessor, Parser, SemanticAnalyzer, IrGenerator, LayoutEngine, Linker, Emitter) must use their corresponding handlers/plugins system
- **Thin Orchestrators**: All logic goes into handlers/plugins; main classes stay clean as distributors
- **Registry-Based**: Use DirectiveHandlerRegistry, IrConverterRegistry, LayoutDirectiveRegistry, LinkingRegistry, EmissionRegistry for extensibility

## Runtime (`src/main/java/org/evochora/runtime/`)

- **Plan-Execute Separation**: VM separates instruction planning from execution to enable future multithreading
- **Conflict Resolution**: Simulation tick follows: plan → resolve conflicts → execute winning instructions
- **Organism Autonomy**: Each Organism is a self-contained VM with own registers, stacks, and energy
- **Embodied Organisms**: Organisms have an instruction pointer (IP) and data pointers (DPs) navigating the n-D grid
- **Instruction Registry**: All instructions register via `Instruction.init()` with unique IDs and planners
- **Immutable Environment**: Environment is read-only during conflict resolution
- **Energy-First**: Every action costs energy; zero energy = organism death

## Data Pipeline (`src/main/java/org/evochora/datapipeline/`)

**Architecture**: Services use Resources to communicate with each other. Resources abstract away the underlying transport (queues, storage, databases), allowing services to focus on business logic.

**Core Concepts**:
- **Services**: Long-running components that process data (e.g., SimulationEngine, PersistenceService)
- **Resources**: Abstractions for I/O (queues, databases, storage) with consistent lifecycle management
- **ServiceManager**: Orchestrates service lifecycle, creates resources, manages bindings
- **Flow**: SimulationEngine → Queue → PersistenceService → Storage → IndexerService → Database

**Design Principles**:
- **Dual-Mode Deployment**: Supports in-process (InMemoryBlockingQueue, H2/SQLite) and cloud (message buses, PostgreSQL, S3)
- **Service Lifecycle**: All services implement `IService` with states: STOPPED, RUNNING, PAUSED, ERROR
- **Resource Abstraction**: Resources implement `IResource` with usage-specific states (ACTIVE, WAITING, FAILED)
- **Constructor DI**: Services receive `(String serviceName, Map<String, List<IResource>> resources, Config options)`
- **Abstract Base**: Services extend `AbstractService` for common lifecycle, thread management, error tracking
- **Resource Helpers**: Use `getRequiredResource()` and `getOptionalResource()` from AbstractService
- **Config Validation**: Validate all config parameters in constructor with clear error messages
- **Contextual Resources**: Resources may implement `IContextualResource` for service-specific wrapping
- **Monitoring**: All services and resources expose operational state via `IMonitorable`
- **Competing Consumers**: All services except SimulationEngine must have the capability to operate as competing consumers
- **Atomic Artifacts**: All created artifacts must be created atomically to ensure resume functionality can always start from final artifacts

**Error Handling & Logging**:
- **Transient Errors** (service/resource continues): `log.warn("msg", args)` + `recordError(code, msg, details)` - NO exception thrown
- **Fatal Errors** (service/resource must stop): `log.error("msg", args)` - NO exception parameter - THROW exception
- **Normal Shutdown** (InterruptedException): `log.debug("msg", args)` - re-throw exception - NO recordError()
- **Retry Logic**: Use `log.debug()` during retries, then follow transient/fatal rules after exhaustion
- **Stack Traces**: NEVER use `log.error(..., exception)` - stack traces logged at DEBUG level by framework
- **Health Status**: Services/Resources are unhealthy if `errors.isEmpty() == false` or state == ERROR
- **No Fallbacks**: Never hide problematic states or errors with fallback behavior — always fail early!

**Monitoring & Metrics**:
- **O(1) Recording**: All metric recording MUST be O(1) - no lists, no sorting, no iteration
- **Use utils.monitoring**: `SlidingWindowCounter` for throughput/counts, `PercentileTracker` for latencies
- **AtomicLong Counters**: For simple counts (messages processed, bytes transferred, errors)
- **No Overhead**: Metric collection must not impact critical path performance

## Node (`src/main/java/org/evochora/node/`)

- **Process Pattern**: All long-running components implement `IProcess` (start/stop methods)
- **Dependency Injection**: Node resolves process dependencies via topological sort and constructor injection
- **Constructor Signature**: Processes receive `(String processName, Map<String, Object> dependencies, Config options)`
- **Lifecycle Order**: Start in dependency order, stop in reverse order (LIFO)
- **Graceful Shutdown**: Shutdown hook ensures all processes stop cleanly
- **Service Registry**: Use ServiceRegistry for sharing services between processes
- **Abstract Base**: Processes extend `AbstractProcess` for common dependency resolution
- **HTTP Controllers**: Controllers extend `AbstractController`, register routes via `registerRoutes(Javalin, String basePath)`
- **HTTP API**: Endpoints at `/api/visualizer/*`, `/api/analyzer/*`, `/api/pipeline/*`

## CLI (`src/main/java/org/evochora/cli/`)

- **Entry Point**: `CommandLineInterface.main()`
- **PicoCLI**: Use PicoCLI annotations for commands and options
- **Command Pattern**: Each command implements `Callable<Integer>` with exit codes (0=success, 1=error, 2=system error)
- **Subcommands**: `node run`, `compile`, `inspect`, `video`, `cleanup`
- **Help System**: Support `--help`, `help [command]` for all commands

## Architectural Review

For significant changes, an architecture review agent is available. Key principles enforced:
- Dual-mode deployment compatibility (in-process and cloud)
- Services communicate through abstract resource interfaces only
- All data-consuming services must be idempotent
- Serialization happens at resource layer, not in services

See `.agents/architecture-guidelines.md` for full review criteria.

## Key Libraries

- **Typesafe Config**: Application configuration with environment variable support (`Config` objects)
- **SLF4J + Logback**: Structured logging (`LoggerFactory.getLogger()` instead of `System.out.println`)
- **PicoCLI**: CLI framework with annotations (`@Command`, `@Option`, `@Parameters`)
- **Javalin**: HTTP server for REST APIs (`Javalin.create().start()`)
- **Protobuf**: Data pipeline communication (binary serialization)
- **JUnit 5**: Testing with tags (`@Tag("unit")` or `@Tag("integration")`)

## Testing Guidelines

**Framework & Tagging:**
- Use JUnit 5 with `@Tag("unit")` or `@Tag("integration")`
- **Unit tests**: <0.2s runtime, no I/O (filesystem, network, database)
- **Integration tests**: Everything else, but MUST still be fast (target: <1s per test)

**Cleanup & Artifacts:**
- Tests MUST NOT leave any artifacts (files, directories, processes)
- Use `@AfterEach` with proper cleanup logic
- If database needed: use in-memory (e.g., in-memory H2)

**Assertions & Timing:**
- Use Awaitility for async conditions: `await().atMost(...).until(...)`
- **NEVER use `Thread.sleep()` in tests**

**Test Data:**
- Assembly code: inline in tests, NOT in separate files
- Protobuf messages: construct inline using builders
- Instruction set: Call `Instruction.init()` before compiler/runtime tests

**Log Assertions (LogWatchExtension):**
- **CRITICAL**: LogWatchExtension fails tests automatically on any WARN/ERROR logs
- DEBUG/INFO logs: allowed by default (can optionally assert with `@ExpectLog`)
- WARN/ERROR logs: MUST use `@ExpectLog(level=WARN/ERROR, messagePattern="...")` if explicitly provoked
- **NEVER use `@AllowLog(level=WARN/ERROR)` without patterns** - this defeats the purpose of LogWatchExtension
- Only use `@ExpectLog` for logs you explicitly provoked in the test

**Coverage Goal:**
- Optional: 60%+ line coverage (JaCoCo)

## Logging Guidelines

**Framework:**
- Use SLF4J + Logback: `LoggerFactory.getLogger(this.getClass())`
- NEVER use `System.out.println()` or `System.err.println()`

**Log Levels by Component:**

**ServiceManager & Node (INFO-level orchestration):**
- `INFO`: Service/resource lifecycle (starting, stopping, closing), batch control operations
- `WARN`: Operation failures (service didn't stop in time, resource close failed)
- `DEBUG`: Process initialization details, topology sorting, dependency injection

**Services (INFO only for user-visible events):**
- `INFO`:
  - Service started with configuration (via `logStarted()` override - each service logs its own config)
  - User-visible events: DLQ writes, auto-pause, max limit reached, simulation loop finished
  - Explicit runId configuration
- `WARN`: Transient errors (always with `recordError()` call) - duplicate detection, retries exhausted, resource unavailable, DLQ full, configuration warnings
- `ERROR`: Fatal initialization/runtime errors (schema setup failed, discovery timeout, indexing failed)
- `DEBUG`: All operational details (batch processing, retries, interrupts, shutdown sequences, drain operations)

**AbstractService (automatic lifecycle logs):**
- `INFO`: `paused`, `resumed` (automatic via base class)
- `DEBUG`: `stopped`, `Service thread interrupted`, `Service thread terminated` (automatic via base class)
- Note: `started` log is replaced by service's `logStarted()` override

**Resources (DEBUG-only operations, WARN/ERROR for problems):**
- `INFO`: NEVER log at INFO level (all orchestration goes through ServiceManager)
- `WARN`: Transient operational errors (query failed, parse error, rollback failed, claim conflict reassignment, sampler errors) - always with `recordError()`
- `ERROR`: Fatal initialization errors (connection pool failed, delegate creation failed, schema setup failed)
- `DEBUG`: All operations (connection pool started/closed, schema setup, delegate creation, message claim/ack, wrapper close, compression setup, sampling)

**Format:**
- Single-line logs only (no multi-line output)
- No phase/version prefixes in log messages
- Include context: service name, resource name, consumer group, relevant parameters
- For orchestration logs: use ServiceManager/Node for INFO, keep service/resource details at DEBUG

**Stack Traces:**
- **Expected errors** (configuration, user input, known failure modes): NO stack trace
  - `log.warn("msg", args)` or `log.error("msg", args)` without exception parameter
  - Stack trace available at DEBUG level if needed for support
- **Unexpected errors / Bugs** (invariant violations, should-never-happen conditions): WITH stack trace
  - `log.error("msg", args, new IllegalStateException("Invariant violation"))`
  - These indicate bugs that need immediate attention and debugging
- For transient errors: `log.warn("msg", args)` without exception parameter
- For fatal errors: `log.error("msg", args)` then throw exception
- For interruption/shutdown: `log.debug("msg", args)` then re-throw

**Examples:**
```java
// Good - ServiceManager orchestration (INFO)
log.info("Starting service '{}'...", serviceName);
log.info("Closed resource: {}", resourceName);

// Good - Service startup (INFO, via logStarted())
log.info("PersistenceService started: batch=[size={}, timeout={}s], retry=[max={}, backoff={}ms], dlq={}, idempotency={}",
    maxBatchSize, batchTimeoutSeconds, maxRetries, retryBackoffMs, dlq != null ? "configured" : "none",
    idempotencyTracker != null ? "enabled" : "disabled");

// Good - Service operation (DEBUG)
log.debug("Successfully wrote batch {} with {} ticks", storageKey, batch.size());

// Good - Resource operation (DEBUG)
log.debug("H2 database '{}' connection pool started (max={}, minIdle={})", name, maxPoolSize, minIdle);

// Good - Transient error (WARN)
log.warn("Failed to send message to queue '{}'", queueName);
recordError("SEND_FAILED", "Queue full", "Queue: " + queueName);

// Good - Fatal error (ERROR) - expected, no stack trace
log.error("Cannot initialize database connection pool for '{}'", dbName);
throw new RuntimeException("Database initialization failed");

// Good - Bug detection (ERROR) - unexpected, WITH stack trace
if (type == TYPE_CODE && value == 0 && marker != 0) {
    log.error("CODE:0 with marker={} - fixing to marker=0", marker,
              new IllegalStateException("Invariant violation: CODE:0 must have marker=0"));
    marker = 0;
}

// Good - Interruption (DEBUG)
log.debug("Service '{}' interrupted during queue.take()", serviceName);
throw new InterruptedException();
```

## Documentation Guidelines

**JavaDoc Requirements:**
- ALL non-private members (public, protected, package-private) MUST have complete JavaDoc in **English**
- Private members: JavaDoc optional but recommended for complex logic

**Class-Level Documentation:**
- Purpose and responsibility
- Key features (bullet list with `<ul><li>`)
- Architectural notes (patterns used, design decisions)
- Thread safety guarantees
- Relationship to parent/child classes

**Method-Level Documentation:**
- Purpose (what the method does, not how)
- All parameters with validation rules (`@param`)
- Return value semantics (`@return`)
- All exceptions with conditions (`@throws`)
- Thread safety if method-specific
- For interface methods: which capability/interface it belongs to

**Template Methods:**
- Document subclass responsibilities clearly
- Specify contract (what must be implemented/extended)
- Provide examples for complex patterns

**Example:**
```java
/**
 * Sends a message to the topic.
 * <p>
 * This method may block briefly during internal buffering. The blocking behavior
 * depends on the underlying implementation (Chronicle Queue, Kafka, cloud).
 * <p>
 * <strong>Thread Safety:</strong> This method is thread-safe and can be called
 * concurrently by multiple threads.
 *
 * @param message The message to send (must not be null).
 * @throws InterruptedException if interrupted while waiting for internal resources.
 * @throws NullPointerException if message is null.
 */
void send(T message) throws InterruptedException;
```

## CI & PR Expectations

- CI: GitHub Actions that run `./gradlew build` on Ubuntu & Windows.
- PR must include: summary, changelog, follow-up suggestions, green CI.
- Branch naming: `jules/<short-purpose>`.
