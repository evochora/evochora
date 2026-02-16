# Resume from Snapshot

## 1. Executive Summary

This document specifies the implementation of simulation resume functionality, allowing a simulation to continue from a previously persisted checkpoint instead of restarting from the beginning.

**Primary Use Cases:**
- **Spot Instance Tolerance**: Resume seamlessly on a new instance after preemption (AWS Spot, GCP Preemptible VMs)
- **Crash Recovery**: Continue simulation after unexpected termination

**Approach:**
- Load the last `TickDataChunk` for a given run-id from storage
- Find the last Accumulated Delta within that chunk (contains RNG state)
- Reconstruct simulation state: Snapshot + last Accumulated Delta
- Continue simulation from that tick; new ticks flow through normal pipeline

**Key Design Decisions:**
- Same run-id is reused (no fork)
- Simulation config comes from stored Metadata; pipeline config from current config
- Runtime remains independent of Datapipeline (no Protobuf imports in Runtime)
- All-fields constructors for state restoration instead of public setters
- Last chunk is truncated to remove ticks after the resume point (prevents overlapping data)

---

## 2. Architecture Overview

### 2.1. Dependency Direction (Unchanged)

The existing architecture maintains strict unidirectional dependencies:

```
              ┌──────────────────────┐
              │        CLI           │
              │   (knows all)        │
              └──────────┬───────────┘
                         │
              ┌──────────┴───────────┐
              │        NODE          │
              │ (knows datapipeline) │
              └──────────┬───────────┘
                         │
       ┌─────────────────┴─────────────────┐
       │                                   │
       ▼                                   ▼
┌─────────────────┐               ┌─────────────────┐
│   DATAPIPELINE  │               │    COMPILER     │
│ (knows runtime) │               │ (knows runtime) │
└────────┬────────┘               └────────┬────────┘
         │                                 │
         └────────────────┬────────────────┘
                          ▼
                ┌─────────────────┐
                │     RUNTIME     │
                │ (knows nothing) │
                └─────────────────┘
```

**This remains unchanged.** Runtime does NOT depend on Datapipeline. All Protobuf-to-Runtime conversion happens in Datapipeline.

### 2.2. Configuration Sources at Resume

| Aspect | Source | Rationale |
|--------|--------|-----------|
| **Simulation Config** (Environment shape, organism config, thermodynamics, tick plugins, programs) | `Metadata.resolved_config_json` | Scientific reproducibility – must be identical to original simulation |
| **Pipeline Config** (Storage paths, queue config, batch sizes, compression) | Current config file | May run on different infrastructure |
| **Sampling Intervals** | `Metadata.sampling_interval` | Must be consistent to avoid gaps/overlaps |

### 2.3. Run-ID Handling

The **same run-id** is reused. This is not a fork – the simulation continues deterministically from where it stopped. New tick data is appended to the existing storage path.

**Run-ID Sources:**

| Component | Normal Start | Resume |
|-----------|--------------|--------|
| SimulationEngine | Generates new run-id | CLI `--run-id` |
| Downstream Services (Indexer, etc.) | `pipeline.runId` from config | **CLI `--run-id` (config ignored)** |

**Resume Mode Override:**

During resume, the CLI `--run-id` overrides `pipeline.runId` for **all** services. This is implemented in `Node`:

```java
// Node.java - resume constructor
public Node(Config config, String resumeRunId) {
    if (resumeRunId != null) {
        // Override pipeline.runId so all services use the resumed run
        config = config.withValue("pipeline.runId",
            ConfigValueFactory.fromAnyRef(resumeRunId));
    }
    this.config = config;
    // ...
}
```

This ensures consistency – no manual config changes needed when resuming.

**Validation:** The `SnapshotLoader` verifies that the loaded metadata's `simulation_run_id` matches the CLI `--run-id`. Mismatch indicates corrupted storage or wrong path.

### 2.4. Storage Structure (Hierarchical Folders)

Batch files are stored in a hierarchical folder structure to avoid too many files in a single directory:

```
{runId}/
  raw/
    metadata.pb
    000/                          ← Level 1: tick / 100,000,000
      000/                        ← Level 2: (tick % 100,000,000) / 100,000
        batch_00000000000_00000000099.pb
        batch_00000000100_00000000199.pb
      001/
        batch_00000100000_00000100099.pb
    001/
      234/
        batch_00123400000_00123400099.pb
```

**Folder level divisors** are configurable (default: `[100_000_000, 100_000]`).

Example: Tick 123,456,789 → folder path `001/234/`

**Important:** The `listBatchFiles()` method searches recursively through this hierarchy, so the resume logic does not need to know the exact folder structure. It simply requests all batch files for a run-id and gets them in tick order.

---

## 3. Resume Algorithm

### 3.1. High-Level Flow

```
1. Parse --run-id from CLI
2. Load SimulationMetadata from storage: {runId}/raw/metadata.pb
3. Find last batch file: {runId}/raw/**/**/batch_*.pb (lexicographically last)
4. Read TickDataChunk(s) from last batch
5. Find last Accumulated Delta in last chunk (has RNG state)
6. Truncate chunk: Remove deltas after last Accumulated Delta
7. Write truncated chunk with new filename, move old batch to superseded/
8. Reconstruct state:
   a. Apply Snapshot (first tick in chunk)
   b. Apply last Accumulated Delta (contains all changes since snapshot)
9. Initialize Simulation with reconstructed state
10. Start simulation loop from (lastAccumulatedDeltaTick + 1)
11. New ticks flow through normal pipeline
```

### 3.2. Finding the Last Usable Checkpoint

```java
// Pseudocode for SnapshotLoader.loadLatestCheckpoint(runId)

// 1. List all batch files for this run
BatchFileListResult batches = storage.listBatchFiles(runId + "/raw/", null, 1000);

// 2. Find the last batch (lexicographic order = tick order due to zero-padded names)
StoragePath lastBatchPath = batches.getFilenames().getLast();

// 3. Read chunks from last batch
List<TickDataChunk> chunks = storage.readChunkBatch(lastBatchPath);
TickDataChunk lastChunk = chunks.getLast();

// 4. Find last Accumulated Delta in chunk (preferred) or fall back to Snapshot
TickDelta lastAccumulatedDelta = null;
for (TickDelta delta : lastChunk.getDeltasList()) {
    if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
        lastAccumulatedDelta = delta;
    }
}

// 5. Return checkpoint data
// - If Accumulated Delta found: use it (less tick loss)
// - If no Accumulated Delta: fall back to Snapshot (more tick loss, but still works)
return new ResumeCheckpoint(metadata, lastChunk.getSnapshot(), lastAccumulatedDelta);
```

### 3.3. State Reconstruction

Reconstructing the simulation state from the checkpoint depends on whether an accumulated delta exists:

**Case 1: Accumulated Delta Found (Preferred)**
- Less tick loss: Resume from the accumulated delta's tick
- Snapshot cells + accumulated delta changes → final cell state
- Organism states, RNG state, plugin states all come from accumulated delta

**Case 2: No Accumulated Delta (Fallback)**
- More tick loss: Resume from the snapshot's tick
- Snapshot cells only → cell state
- Organism states, RNG state, plugin states all come from snapshot

```java
// Determine data source based on accumulated delta availability
if (accumulatedDelta != null) {
    // Case 1: Use accumulated delta
    cellState.applySnapshot(snapshot.getCellColumns());
    cellState.applyDelta(accumulatedDelta.getChangedCells());
    organismStates = accumulatedDelta.getOrganismsList();
    rngState = accumulatedDelta.getRngState();
    pluginStates = accumulatedDelta.getPluginStatesList();
    resumeFromTick = accumulatedDelta.getTickNumber() + 1;
} else {
    // Case 2: Fallback to snapshot only
    cellState.applySnapshot(snapshot.getCellColumns());
    organismStates = snapshot.getOrganismsList();
    rngState = snapshot.getRngState();
    pluginStates = snapshot.getPluginStatesList();
    resumeFromTick = snapshot.getTickNumber() + 1;
}
```

**Key Insight:** Every chunk starts with a complete snapshot that includes RNG state, so even without an accumulated delta, we can still resume deterministically—we just lose ticks between the snapshot and the crash point.

### 3.4. Handling Overlapping Ticks (Chunk Truncation)

#### 3.4.1. The Problem

When resuming from an Accumulated Delta, there may be ticks AFTER that delta in the same chunk that were created in the previous run:

```
Last Chunk: batch_00000001000_00000001099.pb
├── Tick 1000: Snapshot
├── Tick 1010: Incremental Delta
├── Tick 1020: Accumulated Delta
├── Tick 1030: Incremental Delta
├── Tick 1040: Accumulated Delta  ← Resume point (last Accumulated Delta)
├── Tick 1050: Incremental Delta  ← These exist from previous run!
├── Tick 1060: Incremental Delta  ← These exist from previous run!
└── (Crash at tick 1065)

Resume from tick 1041:
- New encoder starts fresh → first tick becomes a Snapshot
- New ticks 1041, 1042, ... are written to NEW batch file
```

**Without truncation, we would have:**
- `batch_00000001000_00000001099.pb` (old chunk with ticks 1000-1099)
- `batch_00000001041_00000001140.pb` (new chunk starting at 1041)

**Problem:** Ticks 1041-1099 exist in BOTH files with DIFFERENT data! This causes:
- Duplicate ticks with inconsistent content
- Indexer confusion (which version is correct?)
- Visualizer showing wrong data for those ticks

#### 3.4.2. The Solution: Truncate and Supersede

Before resuming, the last chunk must be truncated to remove all deltas after the resume point.

```
Original: batch_00000001000_00000001099.pb
├── Tick 1000: Snapshot
├── Tick 1010: Incremental Delta
├── Tick 1020: Accumulated Delta
├── Tick 1030: Incremental Delta
├── Tick 1040: Accumulated Delta  ← Keep up to here
├── Tick 1050: Incremental Delta  ← REMOVE
└── Tick 1060: Incremental Delta  ← REMOVE

After truncation: batch_00000001000_00000001040.pb
├── Tick 1000: Snapshot
├── Tick 1010: Incremental Delta
├── Tick 1020: Accumulated Delta
├── Tick 1030: Incremental Delta
└── Tick 1040: Accumulated Delta  ← New last tick
```

**Steps:**
1. Write truncated batch (filename has different lastTick → `batch_...01040.pb`)
2. Move original to superseded

**Crash Handling via Load-Time Heuristic:**

If crash occurs between steps 1 and 2, both files exist:
```
raw/.../batch_00001000_00001099.pb  ← Original
raw/.../batch_00001000_00001040.pb  ← Truncated
```

The **`listBatchFiles()` method** handles this automatically: When multiple batch files have the same `firstTick`, it keeps only the one with the **smaller `lastTick`** (the truncated version) and logs a warning. This is correct because:
- Truncated file = intentionally shortened = authoritative
- Original file = stale, will be cleaned up on next successful resume

This deduplication happens at the storage layer, so all consumers (SnapshotLoader, Indexer, etc.) automatically get consistent, deduplicated results.

**Implementation:** This deduplication logic belongs in `listBatchFiles()` itself (not in each consumer), so all services (SnapshotLoader, Indexer, etc.) automatically get consistent behavior.

```java
// In IBatchStorageRead.listBatchFiles() implementation:
public BatchFileListResult listBatchFiles(String prefix, ...) {
    List<StoragePath> allFiles = listRawBatchFiles(prefix);

    // Deduplicate: if multiple files share firstTick, keep smallest lastTick
    Map<Long, StoragePath> byFirstTick = new LinkedHashMap<>();
    for (StoragePath path : allFiles) {
        long firstTick = parseBatchFirstTick(path);
        long lastTick = parseBatchLastTick(path);

        StoragePath existing = byFirstTick.get(firstTick);
        if (existing == null) {
            byFirstTick.put(firstTick, path);
        } else if (lastTick < parseBatchLastTick(existing)) {
            log.warn("Duplicate batch files detected for firstTick={}: " +
                "keeping {} (lastTick={}), ignoring {} (lastTick={}). " +
                "This indicates incomplete truncation from a previous crash.",
                firstTick, path, lastTick, existing, parseBatchLastTick(existing));
            byFirstTick.put(firstTick, path);
        } else {
            log.warn("Duplicate batch files detected for firstTick={}: " +
                "keeping {} (lastTick={}), ignoring {} (lastTick={}). " +
                "This indicates incomplete truncation from a previous crash.",
                firstTick, existing, parseBatchLastTick(existing), path, lastTick);
        }
    }

    return new BatchFileListResult(new ArrayList<>(byFirstTick.values()), ...);
}
```

This ensures:
- **Consistency**: All consumers see the same deduplicated view
- **Visibility**: Warning logged when duplicates found (crash recovery indicator)
- **No new interface methods**: Behavior change is internal to existing method

#### 3.4.3. Why Supersede Instead of Delete

Moving to `superseded/` instead of deleting provides:
- **Recovery**: If something goes wrong, original data is still available
- **Audit Trail**: Can see what was truncated
- **Safety**: No permanent data loss during resume

The `superseded/` folder is ignored by the pipeline (not matched by `batch_*.pb` pattern in raw/).

#### 3.4.4. Truncation Implementation

```java
/**
 * Truncates a chunk to remove all deltas after the specified tick.
 *
 * @param chunk The original chunk
 * @param lastValidTick The last tick to keep (inclusive)
 * @return Truncated chunk with updated metadata
 */
public static TickDataChunk truncateChunk(TickDataChunk chunk, long lastValidTick) {
    // Keep snapshot (always first tick)
    TickData snapshot = chunk.getSnapshot();

    // Filter deltas
    List<TickDelta> validDeltas = chunk.getDeltasList().stream()
        .filter(delta -> delta.getTickNumber() <= lastValidTick)
        .collect(Collectors.toList());

    // Calculate new tick count
    int newTickCount = 1 + validDeltas.size();  // 1 for snapshot

    return TickDataChunk.newBuilder()
        .setSimulationRunId(chunk.getSimulationRunId())
        .setFirstTick(chunk.getFirstTick())
        .setLastTick(lastValidTick)
        .setTickCount(newTickCount)
        .setSnapshot(snapshot)
        .addAllDeltas(validDeltas)
        .build();
}
```

---

## 4. Component Specifications

### 4.1. Runtime Changes

#### 4.1.1. Simulation.java

Add factory method for resume (no public setters):

```java
/**
 * Creates a Simulation instance from a previously saved snapshot.
 * This is a two-phase initialization: Simulation is created first,
 * then organisms are added via addOrganism().
 *
 * @param environment Pre-populated environment with restored cell state
 * @param currentTick The tick number to resume from
 * @param totalOrganismsCreated Total organisms created (for nextOrganismId)
 * @param policyManager Thermodynamic policy manager (from Metadata config)
 * @param organismConfig Organism configuration (from Metadata config)
 * @return Simulation ready for organism addition and resumption
 */
public static Simulation forResume(
        Environment environment,
        long currentTick,
        long totalOrganismsCreated,
        ThermodynamicPolicyManager policyManager,
        Config organismConfig) {

    Simulation sim = new Simulation(environment, policyManager, organismConfig);
    sim.currentTick = currentTick;
    sim.nextOrganismId = (int) totalOrganismsCreated + 1;
    return sim;
}
```

**Note:** Organisms are added after construction via existing `addOrganism()` method. This solves the chicken-and-egg problem (Organism constructor needs Simulation reference).

#### 4.1.2. Organism.java

Add a Builder for state restoration. This avoids a 22-parameter "monster constructor" and provides:
- Named setters for clarity
- Sensible defaults for optional fields
- Centralized validation in `build()`
- Better extensibility when adding new fields

```java
public class Organism {

    /**
     * Entry point for restoring an organism from serialized state.
     * Required fields (id, birthTick) are passed here; optional fields
     * are set via builder methods.
     *
     * @param id Unique organism identifier
     * @param birthTick Tick when organism was created
     * @return Builder for setting remaining fields
     */
    public static RestoreBuilder restore(int id, long birthTick) {
        return new RestoreBuilder(id, birthTick);
    }

    /**
     * Private constructor - only called by RestoreBuilder.build()
     */
    private Organism(RestoreBuilder b, Simulation simulation) {
        this.id = b.id;
        this.parentId = b.parentId;
        this.birthTick = b.birthTick;
        this.programId = b.programId;
        this.ip = Arrays.copyOf(b.ip, b.ip.length);
        this.dv = Arrays.copyOf(b.dv, b.dv.length);
        this.er = b.er;
        this.sr = b.sr;
        this.mr = b.mr;
        this.dps = deepCopyCoords(b.dps);
        this.activeDpIndex = b.activeDpIndex;
        this.drs = new ArrayList<>(b.drs);
        this.prs = new ArrayList<>(b.prs);
        this.fprs = new ArrayList<>(b.fprs);
        this.lrs = new ArrayList<>(b.lrs);
        this.dataStack = new ArrayDeque<>(b.dataStack);
        this.locationStack = new ArrayDeque<>(b.locationStack);
        this.callStack = new ArrayDeque<>(b.callStack);
        this.isDead = b.isDead;
        this.instructionFailed = b.instructionFailed;
        this.failureReason = b.failureReason;
        this.failureCallStack = b.failureCallStack != null
            ? new ArrayDeque<>(b.failureCallStack) : null;
        this.simulation = simulation;

        // Derived fields
        this.initialPosition = Arrays.copyOf(b.ip, b.ip.length);

        // Load limits from simulation config
        Config orgConfig = simulation.getOrganismConfig();
        this.maxEnergy = orgConfig.getInt("max-energy");
        this.maxEntropy = orgConfig.getInt("max-entropy");
        this.errorPenaltyCost = orgConfig.getInt("error-penalty-cost");

        // Initialize local random
        IRandomProvider baseProvider = simulation.getRandomProvider();
        if (baseProvider != null) {
            this.localRandom = new Random(baseProvider.nextInt());
        }
    }

    /**
     * Builder for restoring organism state from serialized data.
     * Use {@link Organism#restore(int, long)} to obtain an instance.
     */
    public static class RestoreBuilder {
        // Required fields (set in constructor)
        private final int id;
        private final long birthTick;

        // Fields with defaults
        private Integer parentId = null;
        private String programId = "";
        private int[] ip = new int[0];
        private int[] dv = new int[0];
        private int er = 0;
        private int sr = 0;
        private int mr = 0;
        private List<int[]> dps = new ArrayList<>();
        private int activeDpIndex = 0;
        private List<Object> drs = new ArrayList<>();
        private List<Object> prs = new ArrayList<>();
        private List<Object> fprs = new ArrayList<>();
        private List<Object> lrs = new ArrayList<>();
        private Deque<Object> dataStack = new ArrayDeque<>();
        private Deque<int[]> locationStack = new ArrayDeque<>();
        private Deque<ProcFrame> callStack = new ArrayDeque<>();
        private boolean isDead = false;
        private boolean instructionFailed = false;
        private String failureReason = null;
        private Deque<ProcFrame> failureCallStack = null;

        private RestoreBuilder(int id, long birthTick) {
            this.id = id;
            this.birthTick = birthTick;
        }

        public RestoreBuilder parentId(Integer parentId) {
            this.parentId = parentId; return this;
        }
        public RestoreBuilder programId(String programId) {
            this.programId = programId; return this;
        }
        public RestoreBuilder ip(int[] ip) {
            this.ip = ip; return this;
        }
        public RestoreBuilder dv(int[] dv) {
            this.dv = dv; return this;
        }
        public RestoreBuilder energy(int er) {
            this.er = er; return this;
        }
        public RestoreBuilder entropy(int sr) {
            this.sr = sr; return this;
        }
        public RestoreBuilder marker(int mr) {
            this.mr = mr; return this;
        }
        public RestoreBuilder dataPointers(List<int[]> dps) {
            this.dps = dps; return this;
        }
        public RestoreBuilder activeDpIndex(int idx) {
            this.activeDpIndex = idx; return this;
        }
        public RestoreBuilder dataRegisters(List<Object> drs) {
            this.drs = drs; return this;
        }
        public RestoreBuilder procRegisters(List<Object> prs) {
            this.prs = prs; return this;
        }
        public RestoreBuilder formalParamRegisters(List<Object> fprs) {
            this.fprs = fprs; return this;
        }
        public RestoreBuilder locationRegisters(List<Object> lrs) {
            this.lrs = lrs; return this;
        }
        public RestoreBuilder dataStack(Deque<Object> stack) {
            this.dataStack = stack; return this;
        }
        public RestoreBuilder locationStack(Deque<int[]> stack) {
            this.locationStack = stack; return this;
        }
        public RestoreBuilder callStack(Deque<ProcFrame> stack) {
            this.callStack = stack; return this;
        }
        public RestoreBuilder dead(boolean isDead) {
            this.isDead = isDead; return this;
        }
        public RestoreBuilder failed(boolean failed, String reason) {
            this.instructionFailed = failed;
            this.failureReason = reason;
            return this;
        }
        public RestoreBuilder failureCallStack(Deque<ProcFrame> stack) {
            this.failureCallStack = stack; return this;
        }

        /**
         * Builds the Organism instance.
         *
         * @param simulation The simulation this organism belongs to
         * @return Fully constructed Organism
         * @throws IllegalStateException if required fields are missing or invalid
         */
        public Organism build(Simulation simulation) {
            // Validation
            if (simulation == null) {
                throw new IllegalStateException("Simulation cannot be null");
            }
            if (ip.length == 0) {
                throw new IllegalStateException("IP must be set for restore");
            }
            if (er < 0) {
                throw new IllegalStateException("Energy cannot be negative: " + er);
            }
            if (sr < 0) {
                throw new IllegalStateException("Entropy cannot be negative: " + sr);
            }
            return new Organism(this, simulation);
        }
    }
}
```

**Usage in SimulationRestorer:**

```java
Organism organism = Organism.restore(state.getId(), state.getBirthTick())
    .parentId(state.hasParentId() ? state.getParentId() : null)
    .programId(state.getProgramId())
    .ip(toIntArray(state.getIp()))
    .dv(toIntArray(state.getDv()))
    .energy(state.getEr())
    .entropy(state.getSr())
    .marker(state.getMr())
    .dataPointers(convertCoords(state.getDataPointersList()))
    .activeDpIndex(state.getActiveDpIndex())
    .dataRegisters(convertRegisterValues(state.getDataRegistersList()))
    .procRegisters(convertRegisterValues(state.getProcedureRegistersList()))
    .formalParamRegisters(convertRegisterValues(state.getFormalParamRegistersList()))
    .locationRegisters(convertCoords(state.getLocationRegistersList()))
    .dataStack(convertDataStack(state.getDataStackList()))
    .locationStack(convertLocationStack(state.getLocationStackList()))
    .callStack(convertCallStack(state.getCallStackList()))
    .dead(state.getIsDead())
    .failed(state.getInstructionFailed(), state.getFailureReason())
    .failureCallStack(convertCallStack(state.getFailureCallStackList()))
    .build(simulation);
```

#### 4.1.3. Environment.java

No changes needed. Environment is reconstructed by:
1. Creating with `new Environment(props, labelMatchingStrategy)`
2. Calling `setMoleculeInt(index, moleculeInt, ownerId)` for each cell
3. Calling `resetChangeTracking()` after all cells are set

The `LabelIndex` is automatically built during `setMoleculeInt()` calls.

---

### 4.2. Datapipeline Changes

#### 4.2.0. Storage Interface Extension

The `IBatchStorageWrite` interface needs a new method to move files to a superseded folder:

Location: `org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite`

```java
/**
 * Moves a batch file to the superseded folder.
 * <p>
 * Used during resume to preserve the original chunk before writing a truncated version.
 * The file is moved to: {runId}/raw/superseded/{originalFilename}
 * <p>
 * Files in the superseded folder are ignored by listBatchFiles() and other
 * discovery methods, effectively removing them from the active dataset while
 * preserving them for recovery or audit purposes.
 *
 * @param path The storage path of the file to supersede
 * @throws IOException If the move operation fails
 * @throws IllegalArgumentException If path is null or doesn't exist
 */
void moveToSuperseded(StoragePath path) throws IOException;
```

**Implementation Notes:**
- Filesystem: `Files.move(source, target)` to `superseded/` subfolder
- S3: `CopyObject` + `DeleteObject` (S3 has no native move)
- The `superseded/` folder is automatically excluded from `listBatchFiles()` results

**Crash Handling Note:**
The `SnapshotLoader` must handle the case where both original and truncated files exist (crash between write and move). See section 3.4.2 for the load-time heuristic that prefers the file with smaller `lastTick`.

#### 4.2.1. SnapshotLoader.java (New Class)

Location: `org.evochora.datapipeline.resume.SnapshotLoader`

```java
package org.evochora.datapipeline.resume;

/**
 * Loads simulation checkpoints from storage for resume functionality.
 *
 * This class finds the last usable checkpoint for a simulation run,
 * truncates the last chunk to remove ticks after the resume point,
 * and provides all data needed to reconstruct the simulation state.
 */
public class SnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLoader.class);

    private final IBatchStorageRead storageRead;
    private final IBatchStorageWrite storageWrite;

    public SnapshotLoader(IBatchStorageRead storageRead, IBatchStorageWrite storageWrite) {
        this.storageRead = storageRead;
        this.storageWrite = storageWrite;
    }

    /**
     * Loads the latest checkpoint for the given simulation run.
     * <p>
     * This method also truncates the last chunk to remove any ticks after
     * the resume point, preventing overlapping data when the simulation continues.
     *
     * @param runId The simulation run ID to resume
     * @return ResumeCheckpoint containing all data needed for resume
     * @throws ResumeException if no valid checkpoint exists
     * @throws IOException if storage access fails
     */
    public ResumeCheckpoint loadLatestCheckpoint(String runId) throws IOException {
        // 1. Load metadata
        Optional<StoragePath> metadataPath = storageRead.findMetadataPath(runId);
        if (metadataPath.isEmpty()) {
            throw new ResumeException("Metadata not found for run: " + runId);
        }
        SimulationMetadata metadata = storageRead.readMessage(
            metadataPath.get(), SimulationMetadata.parser());

        // 2. Find last batch file
        BatchFileListResult batches = storageRead.listBatchFiles(
            runId + "/raw/", null, Integer.MAX_VALUE);
        if (batches.getFilenames().isEmpty()) {
            throw new ResumeException("No tick data found for run: " + runId);
        }

        // Note: listBatchFiles() already handles deduplication for crash recovery
        // (if two files share firstTick, it keeps the one with smaller lastTick)

        // Get last batch (list is already sorted by tick order)
        List<StoragePath> sortedPaths = new ArrayList<>(batches.getFilenames());
        Collections.sort(sortedPaths);
        StoragePath lastBatchPath = sortedPaths.get(sortedPaths.size() - 1);

        // 3. Read all chunks from last batch
        List<TickDataChunk> chunks = storageRead.readChunkBatch(lastBatchPath);
        if (chunks.isEmpty()) {
            throw new ResumeException("Empty batch file: " + lastBatchPath);
        }
        TickDataChunk lastChunk = chunks.get(chunks.size() - 1);

        // 4. Find last accumulated delta
        TickDelta lastAccumulatedDelta = null;
        for (TickDelta delta : lastChunk.getDeltasList()) {
            if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
                lastAccumulatedDelta = delta;
            }
        }

        // Note: lastAccumulatedDelta may be null - that's OK, we fall back to snapshot

        // 5. Determine resume point tick (from accumulated delta or snapshot)
        long resumePointTick = (lastAccumulatedDelta != null)
            ? lastAccumulatedDelta.getTickNumber()
            : lastChunk.getSnapshot().getTickNumber();

        // 6. Check if truncation is needed (only if there are ticks after resume point)
        boolean needsTruncation = lastChunk.getLastTick() > resumePointTick;

        if (needsTruncation) {
            log.info("Truncating last chunk: removing ticks {} to {} (resume point: {})",
                resumePointTick + 1, lastChunk.getLastTick(), resumePointTick);

            // 6a. Truncate the last chunk
            TickDataChunk truncatedChunk = truncateChunk(lastChunk, resumePointTick);

            // 6b. Truncate earlier chunks in same batch if they extend beyond resume point
            List<TickDataChunk> truncatedBatch = new ArrayList<>();
            for (TickDataChunk chunk : chunks) {
                if (chunk == lastChunk) {
                    truncatedBatch.add(truncatedChunk);
                } else if (chunk.getLastTick() <= resumePointTick) {
                    truncatedBatch.add(chunk);  // Keep unchanged
                }
                // Chunks entirely after resume point are dropped
            }

            // 6c. Write truncated batch (different filename due to different lastTick)
            long newFirstTick = truncatedBatch.get(0).getFirstTick();
            long newLastTick = truncatedBatch.get(truncatedBatch.size() - 1).getLastTick();
            StoragePath truncatedPath = storageWrite.writeChunkBatch(
                truncatedBatch, newFirstTick, newLastTick);
            log.info("Wrote truncated batch: {} (ticks {}-{})",
                truncatedPath, newFirstTick, newLastTick);

            // 6d. Move original to superseded (if crash before this, load-time
            //     heuristic will prefer truncated file with smaller lastTick)
            storageWrite.moveToSuperseded(lastBatchPath);
            log.info("Moved original batch to superseded: {}", lastBatchPath);

            // Use truncated chunk for state reconstruction
            lastChunk = truncatedChunk;
        }

        // Log resume point info
        if (lastAccumulatedDelta != null) {
            log.info("Resuming from accumulated delta at tick {}", resumePointTick);
        } else {
            log.info("No accumulated delta found, resuming from snapshot at tick {}",
                resumePointTick);
        }

        return new ResumeCheckpoint(
            metadata,
            lastChunk.getSnapshot(),
            lastAccumulatedDelta
        );
    }

    /**
     * Truncates a chunk to remove all deltas after the specified tick.
     */
    private TickDataChunk truncateChunk(TickDataChunk chunk, long lastValidTick) {
        // Keep snapshot (always first tick in chunk)
        TickData snapshot = chunk.getSnapshot();

        // Filter deltas: keep only those <= lastValidTick
        List<TickDelta> validDeltas = chunk.getDeltasList().stream()
            .filter(delta -> delta.getTickNumber() <= lastValidTick)
            .collect(Collectors.toList());

        // Calculate new tick count: 1 (snapshot) + number of valid deltas
        int newTickCount = 1 + validDeltas.size();

        return TickDataChunk.newBuilder()
            .setSimulationRunId(chunk.getSimulationRunId())
            .setFirstTick(chunk.getFirstTick())
            .setLastTick(lastValidTick)
            .setTickCount(newTickCount)
            .setSnapshot(snapshot)
            .addAllDeltas(validDeltas)
            .build();
    }
}
```

#### 4.2.2. ResumeCheckpoint.java (New Record)

Location: `org.evochora.datapipeline.resume.ResumeCheckpoint`

```java
package org.evochora.datapipeline.resume;

/**
 * Contains all data needed to resume a simulation from a checkpoint.
 *
 * @param metadata Complete simulation metadata (config, programs, etc.)
 * @param snapshot The snapshot TickData from the beginning of the chunk
 * @param accumulatedDelta The last accumulated delta, or null if resuming from snapshot only
 */
public record ResumeCheckpoint(
    SimulationMetadata metadata,
    TickData snapshot,
    @Nullable TickDelta accumulatedDelta
) {
    /**
     * Returns the tick number to resume from.
     * If accumulated delta exists: accumulated delta tick + 1
     * If no accumulated delta: snapshot tick + 1
     */
    public long getResumeFromTick() {
        if (accumulatedDelta != null) {
            return accumulatedDelta.getTickNumber() + 1;
        }
        return snapshot.getTickNumber() + 1;
    }

    /**
     * Returns true if resuming from accumulated delta (preferred),
     * false if falling back to snapshot only.
     */
    public boolean hasAccumulatedDelta() {
        return accumulatedDelta != null;
    }
}
```

#### 4.2.3. SimulationRestorer.java (New Class)

Location: `org.evochora.datapipeline.resume.SimulationRestorer`

This class handles all Protobuf-to-Runtime conversions:

```java
package org.evochora.datapipeline.resume;

/**
 * Restores Runtime objects from Protobuf state.
 *
 * This class is the boundary where Protobuf knowledge ends and Runtime begins.
 * All conversion logic is centralized here.
 */
public class SimulationRestorer {

    /**
     * Restores a complete Simulation from a checkpoint.
     */
    public static Simulation restore(
            ResumeCheckpoint checkpoint,
            IRandomProvider randomProvider) {

        SimulationMetadata metadata = checkpoint.metadata();
        TickData snapshot = checkpoint.snapshot();
        @Nullable TickDelta delta = checkpoint.accumulatedDelta();  // May be null!

        // 1. Parse config from metadata
        Config resolvedConfig = ConfigFactory.parseString(metadata.getResolvedConfigJson());
        Config runtimeConfig = resolvedConfig.getConfig("runtime");
        Config organismConfig = runtimeConfig.getConfig("organism");
        Config thermoConfig = runtimeConfig.getConfig("thermodynamics");

        // 2. Create ThermodynamicPolicyManager
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(thermoConfig);

        // 3. Create LabelMatchingStrategy
        ILabelMatchingStrategy labelStrategy = Environment.createLabelMatchingStrategy(
            runtimeConfig.hasPath("label-matching")
                ? runtimeConfig.getConfig("label-matching")
                : null);

        // 4. Create and populate Environment
        EnvironmentConfig envConfig = metadata.getEnvironment();
        int[] shape = envConfig.getWorldShapeList().stream().mapToInt(i -> i).toArray();
        boolean toroidal = envConfig.getIsToroidal();
        EnvironmentProperties envProps = new EnvironmentProperties(shape, toroidal);

        Environment environment = new Environment(envProps, labelStrategy);
        restoreEnvironmentCells(environment, snapshot, delta);  // delta may be null
        environment.resetChangeTracking();

        // 5. Determine currentTick and totalOrganismsCreated
        //    - If accumulated delta exists: use its values
        //    - If no accumulated delta: use snapshot's values
        long currentTick;
        long totalOrganismsCreated;
        ByteString rngState;
        List<OrganismState> organismStates;
        List<PluginState> pluginStates;

        if (delta != null) {
            currentTick = delta.getTickNumber();
            totalOrganismsCreated = delta.getTotalOrganismsCreated();
            rngState = delta.getRngState();
            organismStates = delta.getOrganismsList();
            pluginStates = delta.getPluginStatesList();
        } else {
            // Fallback to snapshot
            currentTick = snapshot.getTickNumber();
            totalOrganismsCreated = snapshot.getTotalOrganismsCreated();
            rngState = snapshot.getRngState();
            organismStates = snapshot.getOrganismsList();
            pluginStates = snapshot.getPluginStatesList();
        }

        // 6. Create Simulation (without organisms yet)
        Simulation simulation = Simulation.forResume(
            environment,
            currentTick,
            totalOrganismsCreated,
            policyManager,
            organismConfig
        );

        // 7. Set RandomProvider with restored state
        randomProvider.loadState(rngState.toByteArray());
        simulation.setRandomProvider(randomProvider);

        // 8. Restore and set ProgramArtifacts
        Map<String, ProgramArtifact> programs = new HashMap<>();
        for (var protoProg : metadata.getProgramsList()) {
            programs.put(protoProg.getProgramId(), convertProgramArtifact(protoProg));
        }
        simulation.setProgramArtifacts(programs);

        // 9. Restore Organisms (now Simulation exists for reference)
        for (OrganismState orgState : organismStates) {
            Organism organism = restoreOrganism(orgState, simulation);
            simulation.addOrganism(organism);
        }

        // 10. Restore and register TickPlugins
        List<ITickPlugin> plugins = restoreTickPlugins(
            metadata.getTickPluginsList(),
            pluginStates,
            randomProvider
        );
        for (ITickPlugin plugin : plugins) {
            simulation.addTickPlugin(plugin);
        }

        return simulation;
    }

    /**
     * Restores environment cells from snapshot + accumulated delta (if present).
     *
     * @param env The environment to populate
     * @param snapshot The snapshot TickData (always present)
     * @param delta The accumulated delta, or null if falling back to snapshot only
     */
    private static void restoreEnvironmentCells(
            Environment env, TickData snapshot, @Nullable TickDelta delta) {

        // Apply snapshot cells first
        CellDataColumns snapshotCells = snapshot.getCellColumns();
        for (int i = 0; i < snapshotCells.getFlatIndicesCount(); i++) {
            env.setMoleculeInt(
                snapshotCells.getFlatIndices(i),
                snapshotCells.getMoleculeData(i),
                snapshotCells.getOwnerIds(i)
            );
        }

        // Apply delta changes if present (overwrites snapshot where changed)
        if (delta != null) {
            CellDataColumns deltaCells = delta.getChangedCells();
            for (int i = 0; i < deltaCells.getFlatIndicesCount(); i++) {
                env.setMoleculeInt(
                    deltaCells.getFlatIndices(i),
                    deltaCells.getMoleculeData(i),
                    deltaCells.getOwnerIds(i)
                );
            }
        }
    }

    /**
     * Restores a single Organism from OrganismState protobuf using the Builder.
     */
    private static Organism restoreOrganism(OrganismState state, Simulation sim) {
        return Organism.restore(state.getId(), state.getBirthTick())
            .parentId(state.hasParentId() ? state.getParentId() : null)
            .programId(state.getProgramId())
            .ip(toIntArray(state.getIp()))
            .dv(toIntArray(state.getDv()))
            .energy(state.getEr())
            .entropy(state.getSr())
            .marker(state.getMr())
            .dataPointers(state.getDataPointersList().stream()
                .map(SimulationRestorer::toIntArray)
                .collect(Collectors.toList()))
            .activeDpIndex(state.getActiveDpIndex())
            .dataRegisters(convertRegisterValues(state.getDataRegistersList()))
            .procRegisters(convertRegisterValues(state.getProcedureRegistersList()))
            .formalParamRegisters(convertRegisterValues(state.getFormalParamRegistersList()))
            .locationRegisters(state.getLocationRegistersList().stream()
                .map(SimulationRestorer::toIntArray)
                .collect(Collectors.toList()))
            .dataStack(convertDataStack(state.getDataStackList()))
            .locationStack(convertLocationStack(state.getLocationStackList()))
            .callStack(convertCallStack(state.getCallStackList()))
            .dead(state.getIsDead())
            .failed(state.getInstructionFailed(),
                state.hasFailureReason() ? state.getFailureReason() : null)
            .failureCallStack(state.getFailureCallStackList().isEmpty() ? null
                : convertCallStack(state.getFailureCallStackList()))
            .build(sim);
    }

    /**
     * Restores tick plugins from metadata config and saved states.
     */
    private static List<ITickPlugin> restoreTickPlugins(
            List<TickPluginConfig> configs,
            List<PluginState> states,
            IRandomProvider random) {

        // Build map of class name -> saved state
        Map<String, byte[]> stateByClass = new HashMap<>();
        for (PluginState ps : states) {
            stateByClass.put(ps.getPluginClass(), ps.getStateBlob().toByteArray());
        }

        List<ITickPlugin> plugins = new ArrayList<>();
        for (TickPluginConfig config : configs) {
            try {
                // Parse plugin options from JSON
                Config options = ConfigFactory.parseString(config.getConfigJson());

                // Instantiate via reflection (same pattern as SimulationEngine)
                ITickPlugin plugin = (ITickPlugin) Class.forName(config.getPluginClass())
                    .getConstructor(IRandomProvider.class, Config.class)
                    .newInstance(random, options);

                // Restore saved state if available
                byte[] savedState = stateByClass.get(config.getPluginClass());
                if (savedState != null) {
                    plugin.loadState(savedState);
                }

                plugins.add(plugin);
            } catch (Exception e) {
                throw new ResumeException(
                    "Failed to restore tick plugin: " + config.getPluginClass(), e);
            }
        }
        return plugins;
    }

    /**
     * Converts protobuf ProgramArtifact to runtime ProgramArtifact.
     */
    private static ProgramArtifact convertProgramArtifact(
            org.evochora.datapipeline.api.contracts.ProgramArtifact proto) {
        // Implementation converts all fields from protobuf to runtime record
        // ... (detailed field-by-field conversion)
    }

    // Helper conversion methods
    private static int[] toIntArray(Vector v) {
        return v.getCoordinatesList().stream().mapToInt(i -> i).toArray();
    }

    private static List<Object> convertRegisterValues(List<RegisterValue> values) {
        return values.stream()
            .map(rv -> rv.hasScalarValue()
                ? rv.getScalarValue()
                : toIntArray(rv.getVectorValue()))
            .collect(Collectors.toList());
    }

    private static Deque<Object> convertDataStack(List<RegisterValue> values) {
        Deque<Object> stack = new ArrayDeque<>();
        for (RegisterValue rv : values) {
            stack.push(rv.hasScalarValue()
                ? rv.getScalarValue()
                : toIntArray(rv.getVectorValue()));
        }
        return stack;
    }

    private static Deque<int[]> convertLocationStack(List<Vector> values) {
        Deque<int[]> stack = new ArrayDeque<>();
        for (Vector v : values) {
            stack.push(toIntArray(v));
        }
        return stack;
    }

    private static Deque<Organism.ProcFrame> convertCallStack(List<ProcFrame> frames) {
        Deque<Organism.ProcFrame> stack = new ArrayDeque<>();
        for (ProcFrame pf : frames) {
            Map<Integer, Integer> fprBindings = new HashMap<>(pf.getFprBindingsMap());
            stack.push(new Organism.ProcFrame(
                pf.getProcName(),
                toIntArray(pf.getAbsoluteReturnIp()),
                toIntArray(pf.getAbsoluteCallIp()),
                convertRegisterValues(pf.getSavedPrsList()).toArray(),
                convertRegisterValues(pf.getSavedFprsList()).toArray(),
                fprBindings
            ));
        }
        return stack;
    }
}
```

#### 4.2.4. ResumeException.java (New Class)

Location: `org.evochora.datapipeline.resume.ResumeException`

```java
package org.evochora.datapipeline.resume;

/**
 * Thrown when simulation resume fails.
 */
public class ResumeException extends RuntimeException {
    public ResumeException(String message) {
        super(message);
    }

    public ResumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

### 4.3. CLI Changes

#### 4.3.1. NodeResumeCommand.java (New Class)

Location: `org.evochora.cli.commands.node.NodeResumeCommand`

```java
package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import org.evochora.node.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(
    name = "resume",
    description = "Resume simulation from last checkpoint"
)
public class NodeResumeCommand implements Callable<Integer> {

    @ParentCommand
    private NodeCommand parent;

    @Option(names = "--run-id", required = true,
            description = "Simulation run ID to resume")
    private String runId;

    @Override
    public Integer call() throws Exception {
        final Config config = parent.getParent().getConfig();

        parent.getParent().showWelcomeMessage();

        // Create Node with resume mode
        final Node node = new Node(config, runId);  // New constructor overload
        node.start();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }
}
```

#### 4.3.2. NodeCommand.java Update

Add the new subcommand:

```java
@Command(
    name = "node",
    description = "Evochora Node operations",
    subcommands = {
        NodeRunCommand.class,
        NodeResumeCommand.class  // Add this
    }
)
public class NodeCommand { ... }
```

---

### 4.4. Node Changes

#### 4.4.1. Node.java Update

Add constructor overload for resume mode:

```java
public class Node {
    private final String resumeRunId;  // null for normal start

    // Existing constructor
    public Node(Config config) {
        this(config, null);
    }

    // New constructor for resume
    public Node(Config config, String resumeRunId) {
        this.config = config;
        this.resumeRunId = resumeRunId;
        // ... rest of initialization
    }

    // In start() or process initialization, check resumeRunId:
    // if (resumeRunId != null) {
    //     // Create SimulationEngine in resume mode
    // }
}
```

---

### 4.5. SimulationEngine Changes

#### 4.5.1. Resume-Aware Initialization

The SimulationEngine needs to support being initialized with pre-existing state:

```java
public class SimulationEngine extends AbstractService {

    // New field to track if this is a resume
    private final boolean isResume;
    private final long startTick;  // 0 for normal, > 0 for resume

    // Existing constructor for normal start
    public SimulationEngine(String name, Config options,
            Map<String, List<IResource>> resources) {
        this(name, options, resources, null);
    }

    // New constructor for resume
    public SimulationEngine(String name, Config options,
            Map<String, List<IResource>> resources,
            ResumedSimulationState resumeState) {

        super(name, options, resources);

        if (resumeState != null) {
            this.isResume = true;
            this.simulation = resumeState.simulation();
            this.randomProvider = resumeState.randomProvider();
            this.tickPlugins = resumeState.tickPlugins();
            this.runId = resumeState.runId();
            this.startTick = resumeState.startTick();
            this.currentTick = new AtomicLong(startTick - 1);

            // Initialize encoder fresh (first tick will be snapshot)
            this.chunkEncoder = new DeltaCodec.Encoder(...);

            // DO NOT send metadata again (already exists in storage)
        } else {
            this.isResume = false;
            this.startTick = 0;
            // ... existing initialization logic
        }
    }
}

/**
 * Holds pre-restored state for SimulationEngine resume.
 */
public record ResumedSimulationState(
    Simulation simulation,
    IRandomProvider randomProvider,
    List<PluginWithConfig> tickPlugins,
    String runId,
    long startTick
) {}
```

---

## 5. Error Handling

### 5.1. Resume Errors

| Error Condition | Behavior |
|-----------------|----------|
| Metadata not found | Throw `ResumeException` with clear message |
| No batch files found | Throw `ResumeException` |
| Empty last batch | Throw `ResumeException` |
| No accumulated delta in last chunk | Fall back to snapshot (not an error) |
| Corrupt protobuf data | Let protobuf exception propagate with context |
| Plugin class not found | Throw `ResumeException` with class name |

### 5.2. No Fallback to Earlier Checkpoints

If the last checkpoint is unusable, the resume fails. There is no automatic fallback to earlier checkpoints because:
1. It adds complexity
2. A corrupt last chunk likely indicates a broader problem
3. User can manually investigate and restart from a specific point if needed (future feature)

---

## 6. Validation

### 6.1. Minimal Validation at Resume

For the initial implementation, validation is minimal:
- Metadata must exist and be parseable
- At least one batch file must exist
- Last chunk must have a snapshot (always true by design)

### 6.2. No Instruction-Set Compatibility Check

If the instruction set has changed between the original run and resume, the simulation will fail at runtime. This is acceptable for v1. A future version could add:
- Version field in metadata
- Opcode compatibility check at resume

---

## 7. Testing Strategy

### 7.1. Unit Tests

- `SnapshotLoaderTest`: Mock storage, verify correct batch/delta selection
- `SimulationRestorerTest`: Verify Protobuf-to-Runtime conversion for all types
- `Organism` resume constructor: Verify all fields are correctly set

### 7.2. Integration Tests

- Full resume cycle: Start simulation → Stop → Resume → Verify determinism
- Spot interruption simulation: SIGTERM during run → Resume → Compare with uninterrupted run

### 7.3. Determinism Verification

After resume, the simulation must produce identical results as if it had never stopped. Test by:
1. Run simulation to tick N, save checkpoint
2. Continue to tick M, save reference output
3. Start new JVM, resume from tick N
4. Run to tick M, compare output with reference

---

## 8. File Locations Summary

| File | Package | Type |
|------|---------|------|
| `Simulation.java` | `org.evochora.runtime` | Modify (add `forResume()`) |
| `Organism.java` | `org.evochora.runtime.model` | Modify (add restore constructor) |
| `IBatchStorageWrite.java` | `org.evochora.datapipeline.api.resources.storage` | Modify (add `moveToSuperseded()`) |
| `AbstractBatchStorageResource.java` | `org.evochora.datapipeline.resources.storage` | Modify (implement `moveToSuperseded()`) |
| `SnapshotLoader.java` | `org.evochora.datapipeline.resume` | New |
| `ResumeCheckpoint.java` | `org.evochora.datapipeline.resume` | New |
| `SimulationRestorer.java` | `org.evochora.datapipeline.resume` | New |
| `ResumeException.java` | `org.evochora.datapipeline.resume` | New |
| `ResumedSimulationState.java` | `org.evochora.datapipeline.services` | New |
| `SimulationEngine.java` | `org.evochora.datapipeline.services` | Modify (add resume constructor) |
| `NodeResumeCommand.java` | `org.evochora.cli.commands.node` | New |
| `NodeCommand.java` | `org.evochora.cli.commands.node` | Modify (add subcommand) |
| `Node.java` | `org.evochora.node` | Modify (add resume constructor) |

---

## 9. Usage

### 9.1. CLI Usage

```bash
# Normal start
evochora node run

# Resume from checkpoint
evochora node resume --run-id 2025012614302512-550e8400-e29b-41d4-a716-446655440000
```

### 9.2. Example Output

```
[INFO] Resuming simulation: run-id=2025012614302512-550e8400-e29b-41d4-a716-446655440000
[INFO] Loaded metadata: seed=42, environment=[100, 100, TORUS]
[INFO] Found checkpoint at tick 15000 (accumulated delta)
[INFO] Restored: 847 organisms, 12847 cells, 3 tick plugins
[INFO] Resuming from tick 15001...
[INFO] SimulationEngine started: world=[100×100, TORUS], organisms=847, ...
```

---

## 10. Implementation Plan

The implementation is structured in phases to enable **early error detection**. Each phase is independently testable before proceeding to the next.

### Phase 1: Runtime State Restoration (Foundation)

**Goal:** Verify we can correctly reconstruct Runtime objects from primitive data.

**Files:**
- `Organism.java` – Add `RestoreBuilder` inner class
- `Simulation.java` – Add `forResume()` factory method

**Tests:**
- Unit test `RestoreBuilder` with all field combinations
- Unit test `Simulation.forResume()` with mock Environment
- Verify `nextOrganismId` calculation: `totalOrganismsCreated + 1`

**Validation Checkpoint:**
```java
// Can we round-trip an Organism?
Organism original = createTestOrganism();
Organism restored = Organism.restore(original.getId(), original.getBirthTick())
    .energy(original.getEr())
    // ... all fields
    .build(simulation);
assertEquals(original.getEr(), restored.getEr());
// ... assert all fields match
```

**Why first:** If state restoration is broken, nothing else works. Better to find out immediately.

---

### Phase 2: Storage Layer Extensions

**Goal:** Enable truncation workflow and crash-safe deduplication.

**Files:**
- `IBatchStorageWrite.java` – Add `moveToSuperseded(StoragePath)` method
- `AbstractBatchStorageResource.java` – Implement `moveToSuperseded()`
- `IBatchStorageRead.java` / Implementation – Add deduplication logic to `listBatchFiles()`

**Tests:**
- Integration test: `moveToSuperseded()` moves file correctly
- Integration test: `listBatchFiles()` deduplicates when two files have same firstTick
- Verify warning is logged on deduplication

**Validation Checkpoint:**
```java
// Create two batch files with same firstTick, different lastTick
storage.writeChunkBatch(chunks, 1000, 1099);  // Original
storage.writeChunkBatch(truncatedChunks, 1000, 1040);  // Truncated

List<StoragePath> files = storage.listBatchFiles(runId + "/raw/", ...);
assertEquals(1, files.size());  // Deduplicated!
assertTrue(files.get(0).contains("01040"));  // Smaller lastTick wins
```

**Why second:** SnapshotLoader depends on these methods. Test storage in isolation first.

---

### Phase 3: Checkpoint Loading (SnapshotLoader)

**Goal:** Correctly identify and load the resume checkpoint from storage.

**Files:**
- `SnapshotLoader.java` (new)
- `ResumeCheckpoint.java` (new)
- `ResumeException.java` (new)

**Tests:**
- Unit test with mock storage: correct batch/chunk/delta selection
- Test fallback to snapshot when no accumulated delta
- Test truncation logic (write truncated, move original)
- Test error cases: missing metadata, no batches, empty batch

**Validation Checkpoint:**
```java
// Create test data with known structure
// batch_1000_1099.pb: Snapshot@1000, AccDelta@1020, AccDelta@1040, Delta@1050
ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(runId);

assertEquals(1040, checkpoint.accumulatedDelta().getTickNumber());
assertEquals(1041, checkpoint.getResumeFromTick());
// Verify truncated file exists, original in superseded/
```

**Why third:** SnapshotLoader orchestrates storage operations. Must work before SimulationRestorer can use it.

---

### Phase 4: State Conversion (SimulationRestorer)

**Goal:** Convert Protobuf checkpoint data to Runtime objects.

**Files:**
- `SimulationRestorer.java` (new)

**Tests:**
- Test `restoreEnvironmentCells()` – snapshot only and snapshot+delta
- Test `restoreOrganism()` – all field types (scalars, vectors, stacks)
- Test `restoreTickPlugins()` – instantiation and state loading
- Test full `restore()` with realistic checkpoint data

**Validation Checkpoint:**
```java
// Load real checkpoint from test fixture
ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(testRunId);
Simulation sim = SimulationRestorer.restore(checkpoint, new XorShiftRandom());

// Verify simulation state matches expected
assertEquals(1040, sim.getCurrentTick());
assertEquals(expectedOrganismCount, sim.getOrganisms().size());
// Verify RNG produces expected sequence
assertEquals(expectedNextRandom, sim.getRandomProvider().nextInt());
```

**Why fourth:** Depends on Phase 1 (RestoreBuilder) and Phase 3 (ResumeCheckpoint). Now we test the full conversion pipeline.

---

### Phase 5: Engine Integration

**Goal:** SimulationEngine can start in resume mode and continue simulation.

**Files:**
- `SimulationEngine.java` – Add resume constructor
- `ResumedSimulationState.java` (new record)

**Tests:**
- Test that resumed engine continues from correct tick
- Test that first tick after resume creates new snapshot (fresh encoder)
- Test that new tick data is written correctly
- Test graceful shutdown still works after resume

**Validation Checkpoint:**
```java
// Resume from tick 1040
SimulationEngine engine = new SimulationEngine(name, config, resources, resumeState);
engine.start();

// Run a few ticks
await().until(() -> engine.getCurrentTick() >= 1045);
engine.stop();

// Verify new data written starting at 1041
List<StoragePath> batches = storage.listBatchFiles(...);
// New batch should start at 1041 with a snapshot
```

**Why fifth:** This is where resume actually "runs". Validates the full stack up to here.

---

### Phase 6: Node & CLI Integration

**Goal:** Complete user-facing resume functionality.

**Files:**
- `Node.java` – Add resume constructor with config override
- `NodeResumeCommand.java` (new)
- `NodeCommand.java` – Register subcommand

**Tests:**
- Test config override: `pipeline.runId` is overwritten
- Test CLI argument parsing
- Integration test: full `node resume --run-id ...` flow

**Validation Checkpoint:**
```bash
# Start simulation, let it run, kill it
evochora node run &
sleep 10
kill -TERM $!

# Resume and verify
evochora node resume --run-id $RUN_ID &
sleep 5
# Check logs for "Resuming from tick X"
# Check storage for new batch files continuing from X+1
```

---

### Phase 7: End-to-End Validation

**Goal:** Verify determinism and crash recovery.

**Tests:**

1. **Determinism Test:**
   ```
   Run A: tick 0 → 10000 (uninterrupted)
   Run B: tick 0 → 5000, stop, resume → 10000

   Assert: State at tick 10000 identical in both runs
   ```

2. **Crash Recovery Test:**
   ```
   Run simulation, SIGKILL mid-tick
   Resume
   Verify: No data corruption, simulation continues
   ```

3. **Multiple Resume Test:**
   ```
   Run → crash → resume → crash → resume
   Verify: Works correctly after multiple cycles
   ```

---

### Implementation Order Summary

```
Phase 1: Organism.RestoreBuilder, Simulation.forResume()
    ↓
Phase 2: moveToSuperseded(), listBatchFiles() deduplication
    ↓
Phase 3: SnapshotLoader, ResumeCheckpoint, ResumeException
    ↓
Phase 4: SimulationRestorer
    ↓
Phase 5: SimulationEngine resume mode
    ↓
Phase 6: Node resume constructor, NodeResumeCommand
    ↓
Phase 7: End-to-End tests
```

**Estimated Effort:** Each phase should be completable and testable independently. If a phase reveals design issues, they're caught before dependent code is written.

---

## 11. Future Extensions (Out of Scope)

The following are explicitly NOT part of this proposal:

- `--from-tick` flag (requires truncate functionality)
- Automatic fallback to earlier checkpoints
- Version/compatibility validation
- Truncate command for manual checkpoint selection
- Resume from specific batch file
