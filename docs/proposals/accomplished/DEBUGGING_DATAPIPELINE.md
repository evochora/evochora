# Evochora: System Architecture for Simulation & Debugging

This document describes the revised system architecture for the Evochora simulation and its accompanying debugging pipeline. It clearly distinguishes between what will be implemented in the first step **now** (a robust, local solution) and the **future** vision of a distributed cloud architecture.

## 1. Guiding Principles

This architecture is designed to meet four core objectives:

* **Performance:** The simulation engine must run at maximum speed, unaffected by I/O latencies or debugging tasks.
* **Scalability & Portability:** The system must be runnable on both a local developer notebook and in a distributed cloud environment.
* **Robustness:** The debugger must function reliably even if an organism's runtime code deviates from the original (due to mutation or self-modification).
* **Data Management:** The architecture must efficiently handle the generation and storage of potentially large amounts of simulation data.

---

## 2. Architecture Overview: The "Simulate-Index-Serve" Model

The entire process is divided into decoupled phases that communicate via clearly defined data stores (SQLite databases). This prevents bottlenecks and allows for a clean separation of responsibilities.

### What We Are Building NOW: The Local Pipeline

All processes run on a single machine and are controlled by a central Command Line Interface (CLI).

1.  **CLI** starts the **Compiler**.
2.  The **Compiler** generates `ProgramArtifacts`.
3.  The **CLI** starts the **SimulationEngine** and the **PersistenceService**.
4.  The **SimulationEngine** produces raw data -> **InMemoryQueue** -> **PersistenceService** writes to `raw.sqlite`, including the `ProgramArtifacts`.
5.  The **CLI** starts the **DebugIndexer**.
6.  The **DebugIndexer** reads `raw.sqlite` (including `ProgramArtifacts`) and writes to `debug.sqlite`.
7.  The **CLI** starts the **DebugServer**.
8.  The **DebugServer** reads `debug.sqlite` and serves the **Debug Client**.

### FUTURE Vision: The Distributed Cloud Pipeline

Each process can run on its own instance (e.g., AWS) and communicates via a central, shared storage.

1.  **Simulation Instance(s) (Hot Path)**: The instance starts, fetches source code from a repository, and runs the **Compiler** to generate `ProgramArtifacts`. The `SimulationEngine` then runs, using these artifacts. The `PersistenceService` writes to a local buffer, and a **`Replicator`** process uploads compressed raw data chunks to a **Shared Storage** (e.g., S3).
2.  **Indexer Instance(s) (Cold Path)**: The `DebugIndexer` fetches raw data and artifacts from Shared Storage, processes them, and writes the final `debug.sqlite` (or another optimized format) back to Shared Storage.
3.  **Server Instance(s) (Serving Layer)**: The `DebugServer` fetches the prepared debug data from Shared Storage to serve client requests.

---

## 3. Components & Workflow in Detail

### 3.1. The Control Center: Command Line Interface (CLI)

* **Component:** `CommandLineInterface.java`
* **Role:** The central orchestrator for all processes. It provides the ability to start, stop, pause, resume, and query the status of each process.
* **Configuration Priority:**
    1.  Command-line arguments (highest priority)
    2.  Values from the `config.json` file
    3.  Sensible default values (lowest priority)
* **Commands:**
    * `run [config.json]`: Starts the entire pipeline according to the configuration (Compiler -> Sim -> Indexer -> Serve).
    * `sim [config.json]`: Starts only the "Hot Path" (Compiler -> SimulationEngine + PersistenceService).
    * `index [raw_db_path]`: Starts only the indexing process.
    * `serve [debug_db_path]`: Starts only the debug web server.
    * `pause <process>` / `resume <process>`: Pauses or resumes a running process (e.g., `pause sim`).
    * `status`: Displays the status of all running processes.
    * `reset [process]`: Stops and resets a specific part of the pipeline and all subsequent parts.
        * `reset`: Restarts the entire pipeline from scratch. Stops all processes and deletes both raw and debug data.
        * `reset index`: Restarts the indexing process from scratch, leaving the raw simulation data untouched. Deletes debug data and restarts indexer and server.
        * `reset serve`: Restarts only the server process.

### 3.2. Phase 1: Compilation

* **Component:** `Compiler.java`
* **Role:** Translates `.s` source files into `ProgramArtifacts`. It is invoked as the first step by the `run` or `sim` command of the CLI.
* **Input:** Paths to the source files from the `config.json`.
* **Output:** `ProgramArtifact` objects in memory.

### 3.3. Phase 2: Simulation & Raw Persistence (Hot Path)

* **Components:** `SimulationEngine`, `InMemoryTickQueue`, `PersistenceService`
* **Role:** Executes the simulation at maximum performance and writes a raw, unaltered record into an SQLite database.
* **Data Flow:**
    1.  **`SimulationEngine`** receives the `ProgramArtifacts` from the CLI, initializes the world, and calculates the ticks. It produces raw `WorldStateMessage` objects for each tick.
    2.  The messages are placed into the **`InMemoryTickQueue`** to decouple the simulation from I/O latency.
    3.  The **`PersistenceService`** reads from the queue and writes the data into a `sim_run_raw.sqlite` database. It also writes the `ProgramArtifacts` into this database at the beginning of the run.
* **Schema (`sim_run_raw.sqlite`):**
    * **`program_artifacts`**: `(program_id TEXT PRIMARY KEY, artifact_json TEXT)`
    * **`raw_organism_states`**: `(tick, id, state_json)`
    * **`raw_cell_states`**: `(tick, pos, molecule, owner_id)`

### 3.4. Phase 3: Indexing (Cold Path)

* **Component:** `DebugIndexer` (to be created)
* **Role:** Reads the raw data and the `ProgramArtifacts` to generate the final, enriched debug data. All computationally intensive preparation happens here.
* **Input:** `sim_run_raw.sqlite` (which contains both raw states and the artifacts).
* **Output:** `sim_run_debug.sqlite`.
* **Schema (`sim_run_debug.sqlite`):**
    * **`prepared_ticks`**: `(tick_number, tick_data_json)`

### 3.5. Phase 4: Serving & Visualization

* **Components:** `DebugServer` (new), Web Frontend
* **Role:** The server delivers the prepared data to the client. The frontend is only responsible for rendering.
* **Data Flow:** The `DebugServer` serves the endpoint `GET /api/tick/:tickNumber` by reading the corresponding JSON blob from the `sim_run_debug.sqlite`.

### 3.6. Data Flow of the `ProgramArtifact`

The `ProgramArtifact` is the "common thread" that carries static information through the pipeline:

1.  The **Compiler** creates it.
2.  The **CLI** passes it to the **PersistenceService**.
3.  The **PersistenceService** writes it into the `sim_run_raw.sqlite` database, making the raw data self-contained.
4.  The **DebugIndexer** reads it from the `sim_run_raw.sqlite` to compare runtime code with the original source code and to generate annotations.

---

## 4. Analysis & Implementation Plan

* **Existing Components (largely reusable):**
    * `Compiler`: Remains as is.
    * `SimulationEngine`: The core remains the same, but it will only output raw data.
    * `InMemoryTickQueue`: Remains unchanged.
    * `Web Frontend`: Will be greatly simplified, as it will only display pre-rendered JSON.
* **Components to be Changed/Refactored:**
    * **`CommandLineInterface`**: Must be significantly extended to implement the new commands (`run`, `sim`, `index`, `serve`, `pause`, etc.) and process control.
    * **`PersistenceService`**: Will be completely rewritten to serve the new raw data schema, including a table for program artifacts. The JSON serialization logic becomes very simple.
    * **`WorldStateAdapter`**: The *logic* of this class will become the heart of the new `DebugIndexer`. The class itself will either be greatly simplified (only creating raw data DTOs) or removed entirely.
    * **`DebugServer` (formerly `AnalysisWebService`)**: The existing Javalin-based web server will be simplified to serve only the prepared debug data. Its core setup can be reused, but its API endpoint logic will be changed.
* **New Components (to be built from scratch):**
    * **`DebugIndexer`**: A new, standalone application/class that implements the indexing process.
    * **Data DTOs**: New Java `record` classes for the raw and prepared data.

---

## 5. Definition of Data Structures

This section defines the Java `record` classes that serve as the data contracts between the different phases of the pipeline.

### 5.1. Raw Data Structures (Hot Path Output)

These records are designed for speed and minimal overhead. They are created by the `SimulationEngine` and written by the `PersistenceService` into the `raw.sqlite` database. They contain only raw, unprocessed data.

```java
// Namespace: org.evochora.server.contracts.raw

/**
 * Top-level container for all raw state data for a single tick.
 */
public record RawTickState(
    long tickNumber,
    List<RawOrganismState> organisms,
    List<RawCellState> cells
) implements IQueueMessage {}

/**
 * Contains the complete, raw state of a single organism.
 * All registers and stacks store the direct runtime objects (Integer or int[]).
 */
public record RawOrganismState(
    int id,
    String programId,
    Integer parentId,
    long birthTick,
    long energy,
    int[] ip,
    int[] dv,
    List<int[]> dps,
    int activeDpIndex,
    List<Object> drs,
    List<Object> prs,
    List<Object> fprs,
    List<Object> lrs,
    Deque<Object> dataStack,
    Deque<int[]> locationStack,
    Deque<Organism.ProcFrame> callStack
) {}

/**
 * Represents a single, non-empty cell in the world.
 */
public record RawCellState(
    int[] pos,
    int molecule, // The raw 32-bit integer value of the molecule
    int ownerId
) {}
```

### 5.2. Prepared Data Structures (Cold Path Output)

This is the final, enriched data structure created by the `DebugIndexer`. An instance of `PreparedTickState` is serialized to a single JSON document for each tick and stored in the `debug.sqlite` database. It contains all information required by any debug client in a pre-formatted, ready-to-display format.

```java
// Namespace: org.evochora.server.contracts.debug

/**
* Top-level container for all prepared debug data for a single tick.
* This is the object that gets serialized to JSON.
  */
  public record PreparedTickState(
  String mode, // "debug" or "performance"
  long tickNumber,
  WorldMeta worldMeta,
  WorldState worldState,
  Map<String, OrganismDetails> organismDetails
  ) {
  // Static metadata about the world
  public record WorldMeta(int[] shape) {}

  // Data for the visual representation of the world grid
  public record WorldState(
  List<Cell> cells,
  List<OrganismBasic> organisms
  ) {}

  public record Cell(List<Integer> position, String type, int value, int ownerId, String opcodeName) {}
  public record OrganismBasic(int id, String programId, List<Integer> position, long energy, List<List<Integer>> dps, List<Integer> dv) {}

  // All details for a single organism, typically displayed in a sidebar
  public record OrganismDetails(
  BasicInfo basicInfo,
  NextInstruction nextInstruction,
  InternalState internalState,
  SourceView sourceView
  ) {}

  public record BasicInfo(int id, String programId, Integer parentId, long birthTick, long energy, List<Integer> ip, List<Integer> dv) {}

  public record NextInstruction(String disassembly, String sourceFile, Integer sourceLine, String runtimeStatus) {} // e.g., runtimeStatus = "OK" or "CODE_MISMATCH"

  public record InternalState(
  List<RegisterValue> dataRegisters,
  List<RegisterValue> procRegisters,
  List<RegisterValue> locationRegisters,
  List<String> dataStack,
  List<String> locationStack,
  List<String> callStack,
  List<List<Integer>> dps
  ) {}

  public record RegisterValue(String id, String alias, String value) {}

  // Represents the annotated source code view
  public record SourceView(
  String fileName,
  int currentLine,
  List<SourceLine> lines,
  List<InlineSpan> inlineSpans
  ) {}

  public record SourceLine(int number, String content, boolean isCurrent) {}

  // A single piece of runtime information injected into a source line
  public record InlineSpan(int lineNumber, int startColumn, String text, String kind) {} // kind: "register", "jump", "call-param"
  }
```

---

## 6. ProgramArtifact Linearization for Jackson Serialization

### 6.1. Problem

The original `ProgramArtifact` uses `int[]` arrays as Map keys (e.g., in `machineCodeLayout` and `initialWorldObjects`). Jackson cannot reliably serialize/deserialize these arrays because:

1. **Serialization**: `int[]` gets converted to String (e.g., `"[1,2,3]"`)
2. **Deserialization**: String cannot be uniquely converted back to `int[]`
3. **HashCode/Equals**: Arrays don't have meaningful `equals()`/`hashCode()` implementations

### 6.2. Solution: Bidirectional Linearization

We implement a **bidirectional conversion** between `ProgramArtifact` (with `int[]` keys) and `LinearizedProgramArtifact` (with `Integer` keys):

#### **CoordinateConverter**
```java
public class CoordinateConverter {
    // Converts int[] coordinates to/from linearized Integer indices
    public <V> Map<Integer, V> linearizeMap(Map<int[], V> original)
    public <V> Map<int[], V> delinearizeMap(Map<Integer, V> original)
}
```

#### **LinearizedProgramArtifact**
```java
public record LinearizedProgramArtifact(
    // All maps with int[] keys are linearized to Integer keys
    Map<Integer, Integer> machineCodeLayout,        // Linearized
    Map<Integer, PlacedMolecule> initialWorldObjects, // Linearized
    // Other maps remain unchanged
    Map<Integer, SourceInfo> sourceMap,             // Unchanged
    // ...
) {
    public static LinearizedProgramArtifact from(ProgramArtifact, int[] worldShape)
    public ProgramArtifact toProgramArtifact()
}
```

#### **ProgramArtifact Conversion**
```java
public record ProgramArtifact(
    // ... existing fields ...
) {
    public LinearizedProgramArtifact toLinearized(int[] worldShape)
    public static ProgramArtifact fromLinearized(LinearizedProgramArtifact)
}
```

### 6.3. Usage in the Pipeline

#### **Serialization (PersistenceService)**
```java
// Conversion to linearized format for Jackson
LinearizedProgramArtifact linearized = artifact.toLinearized(worldShape);
String json = objectMapper.writeValueAsString(linearized);
```

#### **Deserialization (DebugIndexer)**
```java
// Deserialization to LinearizedProgramArtifact
LinearizedProgramArtifact linearized = objectMapper.readValue(json, LinearizedProgramArtifact.class);

// Conversion back to ProgramArtifact
ProgramArtifact artifact = linearized.toProgramArtifact();
```

### 6.4. Advantages

- **Jackson-compatible**: Integer keys work seamlessly
- **No Breaking Changes**: External API remains unchanged
- **Coordinate-friendly**: WebDebugger can still work with `int[]` coordinates
- **Performance**: Linearization only when needed (serialization)
- **Maintainable**: Clear separation between internal and external API

### 6.5. Performance Characteristics

Based on performance tests:

- **2D Linearization (100 entries)**: ~7.9 ms
- **3D Linearization (1000 entries)**: ~1.4 ms
- **4D Linearization (10000 entries)**: ~6.8 ms
- **Roundtrip (1000 entries)**: ~2.9 ms
- **Memory Overhead**: ~544 KB for 10000 entries

Linearization is **only required during serialization/deserialization** and does not impact the runtime performance of the simulation.
