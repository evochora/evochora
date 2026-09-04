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
- `gradle/pmd/ruleset.xml` – PMD rules for the static dead-code check

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
./gradlew jmhJar             # Build the JMH benchmark jar (see docs/BENCHMARKING.md)
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

- Primary user-facing entry point: `bin/evochora compile --source-root <root> --file=<path> [--env=<dimensions>[:<toroidal>]]`
- Developer entry point via JAR (after `./gradlew jar`):
  `java -jar build/libs/evochora.jar compile --source-root <root> --file=<path> [--env=<dimensions>[:<toroidal>]]`

Source roots define base directories (or HTTP URLs) from which module paths are resolved. Use `--source-root path:PREFIX` for named roots and `PREFIX:path` in directives to target them.

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
- **Code quality**: Prefer minimal diffs, write comprehensive tests, maintain existing code style. There is no "cosmetic" or "low" finding: a flaw found is a flaw fixed. After a change, every consequence is checked against the code by an explicit checklist — each mirrored definition (Java enum, JS constants, proto schema, DTO) by name
- **Communication**: Explain reasoning for changes, ask when uncertain about architectural decisions
- **Verify before claiming**: read the code before answering about its behaviour; re-check remembered facts and subagent results against the code; a claim about dependency direction, dead code or consumers is backed by a grep, never by the diff alone
- **Verify the goal**: a change is done when the stated goal is demonstrably achieved in the code paths, not when the build is green
- **Complete the check first**: a finding, analysis or recommendation is presented with its check complete — never with "still to verify" attached
- **Observation before interpretation**: state what the data shows, then what it might mean, then what would falsify that reading; a correlation is reported as a correlation, with the reverse direction and confounders considered before naming a cause
- **Scientific claims**: a statement about the simulation's behaviour is either verified or marked as an assumption; scientific terms only where their meaning and applicability are understood

# Architectural Principles

## Package Dependencies (all of `src/main/java/org/evochora/`)

Which top-level package may reference which other top-level package:

```text
cli          →  node, datapipeline, compiler, runtime
node         →  datapipeline, runtime
datapipeline →  compiler, runtime
compiler     →  runtime
runtime      →  (nothing)
```

- **Authority**: `PackageDependencyRulesTest` enforces this graph and is the single source of truth. The graph is repeated here for orientation only — when the two disagree, the test is right.
- **`runtime` depends on nothing**: the simulation core carries no outward surface. Every type it borrowed from another package would have to be carried along by a reimplementation in another language.
- **`compiler → runtime` is intended**: the instruction set is what the compiler targets. The compiler sees it through `IInstructionSet`; only the adapter in `compiler.isa` reads the runtime's declarations, and `EnvironmentProperties`, the world a program is laid out for, is the one other runtime type the compiler takes.
- **Process wrappers belong to `node`, domain logic to `datapipeline`**: node owns process lifecycles, not what runs inside them. An adapter joining the two lives on node's side, which is the permitted direction.
- **Adding an edge**: a new dependency the graph does not permit fails the test, which names the classes involved. Withdraw the import, or change the rule and let the change be reviewed. Editing the rule is legitimate — doing it by reflex is what destroys its value.

## Compiler (`src/main/java/org/evochora/compiler/`)

- **Twelve-Phase Pipeline**: Dependency Scanning (0) → Lexer (1) → PreProcessor (2) → Parser (3) → Semantic Analyzer (4) → Token Map Generator (5) → AST Post-Processor (6) → IR Generator (7) → IR Rewriting (8) → Layout Engine (9) → Linker (10) → Emitter (11). `Compiler.java` is the only class that knows this order.
- **Package Layout**: `frontend/` and `backend/` are siblings under `compiler/`, each holding its phase orchestrators, registries and handler interfaces; `features/` holds one package per feature, `model/` the three data formats. A handler interface lives in the package of the phase it serves - there is no separate `spi` package. Handler and registration interfaces carry the `I` prefix (`IPreProcessorHandler`, `ILinkingRule`); the data-format roots `AstNode` and `IrItem` do not
- **Immutability**: Compiler phases are immutable - each phase creates an immutable object and passes it to the next
- **Single Execution**: Every phase runs exactly once for all modules of a compilation - there is no per-module loop, and no phase may access a previous phase
- **No Direct Calls**: Phases never call other phases, and handlers never call other handlers. A handler that needs another feature's work reads it from the phase's input data, never from the other handler
- **Results, Not Side Channels**: A phase returns its result to the Compiler; it never exposes results through getters for a later phase to pull. From phase 7 on, the IR is the single source of truth - what is not in the IR does not exist for the backend
- **Thin Orchestrators**: A phase class walks its input and dispatches. Logic lives in the handlers features register, or for the kinds the core fixes, in a class next to the phase. `Compiler.java` runs the phases in order and does nothing else
- **Registry-Based Dispatch**: What features extend goes through the phase's registry; what the core fixes is `sealed` and handled by an exhaustive `switch`; what a node can do is asked through a capability interface, never by feature type. One registry per phase: `PreProcessorHandlerRegistry`, `ParserStatementRegistry`, `ModuleSetupRegistry` and `AnalysisHandlerRegistry`, `TokenMapContributorRegistry`, `PostProcessHandlerRegistry`, `IrConverterRegistry`, `RewriteRegistry`, `LayoutDirectiveRegistry`, `LinkingRegistry` and `LinkingDirectiveRegistry`, `EmissionContributorRegistry`. Features fill them through `IFeatureRegistrationContext`
- **Feature-Slicing**: Features (not phases) are the unit of code organization. Each feature is a self-contained package with all its components (parser handler, AST node, semantics handler, IR converter, etc.). Features register themselves into phase registries. Reason: a feature is the unit of change ("add procedures", "add imports"), so its components belong together even though they run in different phases; organized by phase, every change would spread across the whole pipeline
- **Feature-Agnostic Core**: Core infrastructure (phases, SymbolTable, data formats) must never reference specific features. Features depend on phases, not vice versa. The only place that knows which features exist is `StandardFeatures.java`
- **Three Pure Data Formats**: Token (`model/token/`), AST (`model/ast/`), IR (`model/ir/`) are strictly separated. No cross-dependencies between them. `SourceInfo` is the only shared type: an AST node never holds a `Token`, it carries the token's `SourceInfo`
- **Fixed Kinds**: The core fixes the three IR item kinds and the five operand forms. A feature extends the IR through directives and their handlers, or by subtyping `IrInstruction`; it never adds a kind
- **Stateless Features**: Features and their handlers hold no compilation state. Compilation data flows through phase contexts (e.g., PreProcessorContext, IrGenContext), not through features. Phase-internal state (register alias scopes while parsing, the macro table while preprocessing) lives in that phase's context and dies with the phase; data that crosses a phase boundary is returned to the Compiler and handed to the next phase as input
- **Pure Data Records**: Core data types (Symbol, AstNode subtypes, IR items) are pure records. Placement/scoping knowledge lives in the SymbolTable and phase contexts, not in the data records themselves.
- **Output Equivalence**: A compiler change that must not alter generated code passes `CompilerOutputEquivalenceTest` unchanged: it compiles the reference program under `src/test/resources` and compares the artifact with the checked-in one. An intended change regenerates the reference artifact and says so in the pull request. Passing tests alone do not prove identical output

## Runtime (`src/main/java/org/evochora/runtime/`)

- **Snapshot Tick Semantics**: All organisms act against the environment as it was at the start of the tick. Wave 1 (plan + organism-local instructions) runs on the worker pool or inline; wave 2 (environment-modifying instructions) runs sequentially after conflict resolution. The thread count (`runtime.parallelism`, `parallelism-scaling`) changes speed only, never the result.
- **Conflict Resolution**: Contenders for one cell are ranked by a per-tick priority computed from seed, tick and organism ID (`OrganismRandom.tickStreamSeed()`); the loser is booked as a failed instruction (`"Lost write conflict"`, error penalty) and retries with its instruction pointer held.
- **Two Kinds of Randomness**: The root `IRandomProvider` serves the sequential parts of a tick (tick plugins, birth/death handlers) and is checkpointed. Anything executing for an organism inside the parallel wave — instructions, interceptors, label matching — uses `Organism.getRandom()`, whose values are computed from seed, tick, organism ID and draw index and need no persistence. Drawing from the root provider inside the wave throws; `ParallelWave.isActive()` is the guard.
- **Organism Autonomy**: Each Organism is a self-contained VM with own registers, stacks, and energy
- **Self-Contained Machine Code**: Execution reads nothing from the program artifact. Everything a `CALL`, a jump or a parameter binding needs is in the molecules the compiler placed, so code that arose by mutation runs by the same rules as compiled code. The artifact serves the compiler's consumers, the visualizer and the data pipeline, never the runtime.
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
- **Transient Errors** (service/resource continues): `log.warn("msg", args)` + `recordError(code, msg, details)` - throw only if the caller must handle the failed operation
- **Fatal Errors** (service/resource cannot serve any caller): `log.error("msg", args)` - `recordError(code, msg, details)` + THROW exception. Recording is required: a resource does not stop itself, so its state is invisible unless it is recorded
- **Normal Shutdown** (InterruptedException): `log.debug("msg", args)` - re-throw exception - NO recordError()
- **Retry Logic**: Use `log.debug()` during retries, then follow transient/fatal rules after exhaustion
- **Stack Traces**: pass the exception ONLY for bugs and system faults (see below) - never for expected errors
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

**Isolation:**
- The suite runs in two JVMs. Gradle distributes classes, never methods, so every class must pass in whichever JVM and order it lands in; nothing may depend on another class having run first
- No fixed ports, directories or database names: bind port 0 or ask the OS for a free port, use `@TempDir` or `Files.createTempDirectory`, give in-memory databases a UUID name
- No wall-clock assertions ("finished within 10 ms"): assert behaviour and let Awaitility bound the wait

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

**Defect tests:**
- **Test first**: a bugfix starts with a test that reproduces the defect and fails; the fix makes it pass. No fix without the red test before it

**Coverage:**
- `jacocoTestCoverageVerification` fails the build below 50% line coverage (JaCoCo); the goal remains 60%+
- Raising the bound is a deliberate change, made once the suite has grown past it

**Benchmarks:**
- JMH benchmarks live in `src/jmh/`; they are relative before/after measurements, never absolute references
- Procedure, required environment conditions, and validity criteria: [docs/BENCHMARKING.md](docs/BENCHMARKING.md)

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
- `ERROR`: Fatal errors (connection pool failed, delegate creation failed, schema setup failed, connection acquisition failed) - always with `recordError()`
- `DEBUG`: All operations (connection pool started/closed, schema setup, delegate creation, message claim/ack, wrapper close, compression setup, sampling)

**Format:**
- Single-line logs only (no multi-line output)
- No phase/version prefixes in log messages
- Include context: service name, resource name, consumer group, relevant parameters
- For orchestration logs: use ServiceManager/Node for INFO, keep service/resource details at DEBUG

**Stack Traces:**
- **Expected errors** (configuration, user input, known failure modes): NO stack trace
  - `log.warn("msg", args)` or `log.error("msg", args)` without exception parameter
  - A stack trace adds nothing: the message already states the cause
- **Bugs** (invariant violations, should-never-happen conditions): WITH stack trace
  - `log.error("msg", args, new IllegalStateException("Invariant violation"))`
  - These indicate bugs that need immediate attention and debugging
- **System faults** (cause lies outside the application: pool cannot create connections, storage gone, broker unreachable): WITH stack trace
  - `log.error("msg", args, exception)` - the chained causes carry the diagnosis, and no message can replace them
  - At ERROR, not DEBUG: production runs at INFO, and a cause that is only visible at DEBUG is not visible when it matters
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
- JavaDoc MUST be self-contained: never reference proposals, plan steps, ticket numbers, or conversation context (e.g., "will be added in step C4", "created in ticket #123"). A reader must understand the comment without any external context. Describe what the code *is* and *does*, not what *changed* or *will change*.

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

**Flagship documents:**
- `README.md` and `docs/SCIENTIFIC_OVERVIEW.md` are the project's public face. Edits derive from the document's structure and purpose, never from "a place where it fits"; exact wording is proposed before editing and approved hunk by hunk; links point to durable targets only, never to proposals; index entries are timeless; claims stay scientifically restrained

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

- CI: GitHub Actions that run `./gradlew build` on Ubuntu & Windows (includes the PMD gate).
- PR must include: a summary of what changed and why, the verification that was performed, green CI.
- Branch naming: short and purpose-describing, e.g. `feature/<topic>`.

### Pull Request Workflow

**Nothing is pushed without the maintainer's explicit approval** — not the first push, not a
follow-up, not a one-line fix, however obvious the change looks. Approval for one push is never
approval for the next; each one is asked for separately.

1. Before every push: fetch and merge `origin/main` (parallel work happens on this repository);
   when new commits arrived, merge and test first — never push unmerged.
2. Before every push: run `./gradlew check`, not just `test` — the CI has a PMD gate.
3. After every push: wait for CI **and** for all configured automated reviews before
   anything else happens.
4. Verify every review finding against the code yourself (reviews can contradict each other),
   then put **every** finding to the maintainer, one at a time and none left out. How much a
   finding matters is the maintainer's judgement: calling one negligible, or bundling several so
   that some disappear into a summary, takes that decision away from them.
5. A round ends only when every one of its points has been settled. Until then the branch stays
   untouched; then all agreed changes are applied together, approval to push is asked for, and
   the push happens once. Pushing in the middle of a round restarts every automated review on an
   unfinished state and spends a round for nothing — CodeRabbit grants one review per hour, so
   the second reviewer is simply absent from the round that follows.
6. Small findings — from a review or noticed in passing — are fixed on the branch in their own
   commit, never deferred to other PRs. Only a finding with real scope or risk is raised for a
   decision instead.
7. Whether a change goes through a PR or is pushed directly is the maintainer's call.
8. Merging happens only after the maintainer's approval.
