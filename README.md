# Evochora Simulation

Evochora is a digital playground for simulating the evolution of simple, programmable organisms in an n-dimensional environment. It provides a custom assembly language that allows organisms to interact with their environment, reproduce, and compete for resources.

## Project Purpose

The primary goal of this project is to create a flexible and extensible simulation environment for exploring concepts of artificial life and evolution. It allows users to design organisms with custom behaviors and observe how they adapt and evolve over generations.

## Architecture Overview

The simulation is built in Java and consists of several key components:

-   **World**: An n-dimensional grid where organisms live and interact. It contains energy sources and other symbols that organisms can manipulate.
-   **Organism**: An entity with a simple CPU, registers, and a program written in a custom assembly language. Organisms can move, execute instructions, and reproduce.
-   **Assembler**: A multi-pass assembler that translates human-readable assembly code into machine code that organisms can execute. It supports macros, routines, and labels.
-   **Simulation**: The main engine that manages the environment, orchestrates organism actions, and advances the simulation tick by tick.
-   **UI**: A JavaFX-based graphical user interface for visualizing the simulation, inspecting organisms, and controlling the simulation flow.

## How to Build & Test

The project uses Gradle for building and managing dependencies.

-   **Build the project**:
    ```bash
    ./gradlew build
    ```
-   **Run the tests**:
    ```bash
    ./gradlew test
    ```
-   **Run the simulation**:
    ```bash
    ./gradlew run
    ```
-   **Compile assembly files**:
    ```bash
    ./gradlew compile -Pfile="example.s"
    ```

## Configuration

### `--config` Parameter
You can specify a custom configuration file when starting the CLI:

**Interactive mode (CLI interface):**
```bash
# Use custom config file with Gradle
./gradlew run --args="--config my-config.jsonc"

# Use custom config file with JAR
java -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar --config my-config.jsonc
```

**Important notes:**
- `--config` only works in interactive mode (when no other commands are specified)
- If the specified config file is not found, the CLI will log an ERROR and continue with the fallback configuration
- If the config file has invalid JSON syntax, the CLI will log an ERROR with the line number and continue with the fallback configuration
- The fallback configuration includes basic logging setup (INFO for CLI/ServiceManager, WARN for others)

## Log Level Configuration

You can control logging verbosity when running the CLI or compiling assembly files using System Properties:

### Basic Log Level Control

**Set default log level for all services:**
```bash
./gradlew run -Dlog.level=DEBUG
./gradlew run -Dlog.level=INFO
./gradlew run -Dlog.level=WARN
./gradlew run -Dlog.level=ERROR
./gradlew run -Dlog.level=TRACE
```

**Set specific logger levels:**
```bash
# Different levels for different services (use full Java class names)
./gradlew run -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -Dlog.org.evochora.server.http.DebugServer=WARN

# Combine default and specific settings
./gradlew run -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.persistence.PersistenceService=WARN
```

**For compilation with debug output:**
```bash
./gradlew compile -Pfile="assembly/primordial/main.s" -Dlog.level=DEBUG
```

**For JAR execution:**
```bash
# Build the CLI JAR first
./gradlew cliJar

# Run with log level configuration
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Run with custom config file
java -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar --config my-config.jsonc

# Compile assembly with JAR and debug logging
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile assembly/primordial/main.s
```

### Available Log Levels

- `TRACE` - Most verbose, shows all operations and internal details
- `DEBUG` - Detailed debugging information for development
- `INFO` - General information messages (default)
- `WARN` - Warning messages only
- `ERROR` - Error messages only

### Available Loggers

**For System Properties (use full Java class names):**
- `log.org.evochora.server.engine.SimulationEngine` - Simulation engine logging
- `log.org.evochora.server.persistence.PersistenceService` - Persistence service logging  
- `log.org.evochora.server.indexer.DebugIndexer` - Debug indexer logging
- `log.org.evochora.server.http.DebugServer` - Web debug server logging
- `log.org.evochora.server.ServiceManager` - CLI interface logging

**For CLI commands (use short aliases):**
- `sim` - Simulation engine logging
- `persist` - Persistence service logging  
- `indexer` - Debug indexer logging
- `web` - Web debug server logging
- `cli` - CLI interface logging

### How It Works

1. **System Properties override** config.jsonc settings
2. **`log.level`** sets the default level for all loggers
3. **`log.<logger>`** sets specific logger levels
4. **Fallback to config.jsonc** if no System Properties are set

### Example Usage

**Gradle execution:**
```bash
# Start with debug logging for troubleshooting
./gradlew run -Dlog.level=DEBUG

# Detailed simulation logging with quiet other services
./gradlew run -Dlog.level=WARN -Dlog.org.evochora.server.engine.SimulationEngine=TRACE

# Compile with debug output to see compilation details
./gradlew compile -Pfile="assembly/test/main.s" -Dlog.level=DEBUG

# Mixed logging levels for different services
./gradlew run -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -Dlog.org.evochora.server.http.DebugServer=ERROR
```

**JAR execution:**
```bash
# Build the CLI JAR first
./gradlew cliJar

# Start with debug logging
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Start with custom config file
java -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar --config my-config.jsonc

# Mixed logging levels (use full Java class names)
java -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Compile assembly with debug output using JAR
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile assembly/test/main.s
```

You can also change log levels at runtime using the `loglevel` command within the CLI. See [docs/CLI_USAGE.md](docs/CLI_USAGE.md) for more details.

## Documentation

-   **Javadoc**: Detailed documentation for all public APIs can be generated by running `./gradlew javadoc`. The output will be in `build/docs/javadoc/`.
-   **Assembler Specification**: For a complete guide to the assembly language, including syntax, directives, and the full instruction set, please see [docs/ASSEMBLY_SPEC.md](ASSEMBLY_SPEC.md).
-   **Assembly Compile System**: For information on how to compile assembly files and use the system with AI assistants, see [docs/ASSEMBLY_COMPILE_USAGE.md](docs/ASSEMBLY_COMPILE_USAGE.md).

## vNext Highlights & Migration

What’s new:
- Stack-based S-variants: ADDS, SUBS, MULS, DIVS, MODS, ANDS, ORS, XORS, NADS, NOTS, SHLS, SHRS.
- Stack ops: DUP, SWAP, DROP, ROT.
- Separate Return-Stack (RS) for CALL/RET + configurable limits (DS_MAX_DEPTH/RS_MAX_DEPTH).
- PROC-local registers (PRs) with automatic save/restore across CALL/RET.
- SCAN/SEEK variants: SCNI (imm vec), SCNS (stack vec), SEKS (stack vec); alias SCNR -> SCAN.
- Includes: signature-deduplicating `.INCLUDE`, forced `.INCLUDE_STRICT`.
- Procs: `.PROC/.ENDP`, `.EXPORT`, `.REQUIRE`, `.IMPORT` with validation.

Migration tips:
- Prefer S-variants to reduce boilerplate PUSH/POP.
- Replace direct register SCAN/SEEK where convenient: use SCNI/SCNS/SEKS; SCNR works as alias for SCAN.
- For reusable code, use `.INCLUDE`; use `.INCLUDE_STRICT` only when a fresh instance is required.
- Declare libraries as `.PROC` with `.EXPORT`; call-sites use `.IMPORT LIB.NAME AS ALIAS`.
- Watch DS/RS depth in programs that recurse or use deep stacks; tune via Config.

Examples: see docs/examples/phase8_examples.s