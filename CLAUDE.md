# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Evochora is an artificial life simulator for research into digital evolution. It features:
- Custom n-dimensional simulation environment with thermodynamic constraints
- Multi-pass EvoASM compiler converting assembly to VM machine code
- High-performance runtime with embodied virtual organisms
- Modular data pipeline separating hot execution from cold data processing
- Web-based visualization and analysis frontends

## Build Commands

```bash
./gradlew build              # Full build with tests
./gradlew test               # All tests (excludes benchmarks)
./gradlew unit               # Fast unit tests only (@Tag("unit"))
./gradlew integration        # Integration tests only (@Tag("integration"))
./gradlew benchmark          # Performance benchmarks (@Tag("benchmark"))
./gradlew run --args="node run"  # Run simulation node
./gradlew run --args="--help"    # Show CLI help
./gradlew distZip distTar    # Create distribution archives
```

**Java 21 required.** Configure in IDE or use `JAVA_HOME`.

## Architecture Overview

### Five Core Components

1. **Compiler** (`src/main/java/org/evochora/compiler/`)
   - Multi-pass immutable pipeline: Preprocessor → Lexer → Parser → Semantic Analyzer → IR Generator → Layout Engine → Linker → Emitter
   - Each phase creates an immutable object passed to the next; no phase accesses previous phases
   - Extensible via handler registries (DirectiveHandlerRegistry, IrConverterRegistry, LayoutDirectiveRegistry, LinkingRegistry, EmissionRegistry)

2. **Runtime** (`src/main/java/org/evochora/runtime/`)
   - Plan-Execute-Resolve loop: instructions plan → conflicts resolve → winners execute
   - Energy-first design: every action costs energy, zero energy = death
   - Embodied organisms with instruction pointer (IP) and data pointers (DPs) navigating n-D grid
   - Instruction registry pattern via `Instruction.init()`

3. **Data Pipeline** (`src/main/java/org/evochora/datapipeline/`)
   - Flow: SimulationEngine → Queue → PersistenceService → Storage → Indexers → Database
   - Services extend `AbstractService` with constructor: `(String serviceName, Map<String, List<IResource>> resources, Config options)`
   - Resources abstract I/O (IQueueResource, IStorageResource, IDatabaseResource, ITopicResource)
   - Supports dual-mode deployment: in-process (InMemoryBlockingQueue, H2/SQLite) and cloud (message buses, PostgreSQL, S3)
   - All services expect SimulationEngine must have the capability to operate as a competing consumers
   - All created artifacts must be created atomically to make sure resume functionality can always start from final artifacts

4. **Node** (`src/main/java/org/evochora/node/`)
   - Orchestrates processes via topological sort of dependencies
   - HTTP API at `/api/visualizer/*`, `/api/analyzer/*`, `/api/pipeline/*`
   - Processes extend `AbstractProcess` with constructor: `(String processName, Map<String, Object> dependencies, Config options)`

5. **CLI** (`src/main/java/org/evochora/cli/`)
   - Entry point: `CommandLineInterface.main()`
   - PicoCLI-based commands: `node run`, `compile`, `inspect`, `video`
   - Config priority: command-line > config file > defaults

### Key Configuration

- `evochora.conf` - HOCON configuration defining resources, services, and simulation parameters
- `local.conf` - Local development overrides (not checked in)

## Testing Conventions

**Test Tags:**
- `@Tag("unit")` - <0.2s, no I/O
- `@Tag("integration")` - <1s, may use I/O
- `@Tag("benchmark")` - Performance tests, excluded from regular runs

**LogWatchExtension:** Automatically fails tests on WARN/ERROR logs. Use `@ExpectLog(level=WARN, messagePattern="...")` only for logs you explicitly provoke.

**Critical Rules:**
- Never use `Thread.sleep()` - use Awaitility: `await().atMost(...).until(...)`
- Call `Instruction.init()` before compiler/runtime tests
- Inline assembly code and Protobuf messages in tests, not separate files
- Tests must not leave artifacts; use `@AfterEach` cleanup

## Coding Conventions

**Logging:**
- Use SLF4J: `LoggerFactory.getLogger(this.getClass())`
- Never `System.out.println()`
- Transient errors: `log.warn()` + `recordError()`, no exception thrown
- Fatal errors: `log.error()` without exception parameter, then throw
- Shutdown/interrupts: `log.debug()`, re-throw InterruptedException
- Never log stack traces at WARN/ERROR; framework logs at DEBUG

**Error Handling in Services:**
- Transient (recoverable): warn + recordError, continue
- Fatal (unrecoverable): error + throw exception
- Normal shutdown: debug + re-throw InterruptedException

**Documentation:**
- Complete JavaDoc required for all non-private members
- Include: purpose, @param, @return, @throws, thread safety

## Change Management

**Before Proposing Solutions:**
- Verify the solution is fundamentally possible given system constraints
- For architectural changes: confirm the approach solves the problem without creating new ones
- For performance claims (heap, CPU, latency): verify with evidence or explicitly state uncertainty

**Before Implementing Changes:**
- Interface changes: analyze full impact (all implementations, all call sites, all tests)
- Multi-file changes: present plan and get explicit approval before writing code
- Never silently rewrite working code - explain what and why first

**Before taking decision with potentially high influence**
- Stop and ask the the user for feedback regaring proposed decission

## Architectural Review

For significant changes, an architecture review agent is available. Key principles enforced:
- Dual-mode deployment compatibility (in-process and cloud)
- Services communicate through abstract resource interfaces only
- All data-consuming services must be idempotent
- Serialization happens at resource layer, not in services

See `.agents/architecture-guidelines.md` for full review criteria.
