# Delta Compression for Data Pipeline

## 1. Executive Summary

This document specifies the introduction of delta compression for environment snapshots in the data pipeline. The goal is to reduce both I/O throughput and storage requirements by transmitting only changes between ticks instead of full environment state.

**Problem:**
- Full snapshots grow linearly with environment density
- Example: 128KB at tick 0 vs 42MB at tick 15,000,000 (dense environment)
- This impacts queue throughput, storage costs, and database size

**Solution:**
- Track environment changes at the source (Environment class)
- Group ticks into self-contained chunks starting with a snapshot
- Store only deltas for subsequent ticks within a chunk
- Defer decompression until data is actually needed (on-demand)

**Expected Results:**
- 90%+ reduction in storage for dense environments
- Reduced I/O pressure on queues and storage
- Database also benefits (no decompression before indexing)

---

## 2. Configuration Parameters

### 2.1. Hierarchical Interval Structure

The compression uses a hierarchical configuration where each level is a multiple of the previous:

```
samplingInterval (existing)
    └── accumulatedDeltaInterval
            └── snapshotInterval
                    └── chunkInterval
```

| Parameter | Description | Example |
|-----------|-------------|---------|
| `samplingInterval` | Every Nth tick is sampled (existing) | 100 |
| `accumulatedDeltaInterval` | Every Nth sampled tick is an accumulated delta (vs incremental) | 10 |
| `snapshotInterval` | Every Nth accumulated delta is a full snapshot | 5 |
| `chunkInterval` | Every Nth snapshot starts a new chunk | 1 |

### 2.2. Tick Types

1. **Full Snapshot**: Complete state of all occupied cells
2. **Accumulated Delta**: All changes since the last snapshot (checkpoint-capable)
3. **Incremental Delta**: Only changes since the last sampled tick

### 2.3. Chunk Structure

A chunk is the unit of transmission through the pipeline:
- **Always starts with a full snapshot** (self-contained)
- Contains subsequent deltas until the next chunk begins
- Can be processed independently by competing consumers

### 2.4. Validation Rules

**Hard validation (throws ConfigurationException):**
- `accumulatedDeltaInterval` must be >= 1
- `snapshotInterval` must be >= 1
- `chunkInterval` must be >= 1
- Tick 0 is always a snapshot AND starts the first chunk

**Validation location:** SimulationEngine constructor

```java
// In SimulationEngine constructor
if (accumulatedDeltaInterval < 1) {
    throw new ConfigurationException("accumulatedDeltaInterval must be >= 1");
}
if (snapshotInterval < 1) {
    throw new ConfigurationException("snapshotInterval must be >= 1");
}
if (chunkInterval < 1) {
    throw new ConfigurationException("chunkInterval must be >= 1");
}
```

**No hard upper limit:** Large interval values (resulting in large chunks) are NOT rejected. Instead, `IMemoryEstimatable` handles this:
- `SimulationEngine.estimateWorstCaseMemory()` includes `params.estimateBytesPerChunk()`
- Large intervals → large chunk estimate → ServiceManager warns if total exceeds `-Xmx`
- This allows advanced users to consciously choose large chunks while still receiving memory warnings

### 2.5. Default Configuration

```hocon
simulation-engine {
  samplingInterval = 100           # Sample every 100th tick
  delta {
    accumulatedDeltaInterval = 5   # Every 5th sample is accumulated delta (for fast API)
    snapshotInterval = 20          # Every 20th accumulated delta is snapshot
    chunkInterval = 1              # Every snapshot starts new chunk
    estimatedDeltaRatio = 0.01     # 1% of cells change per tick (for heap estimation)
  }
}
```

**Result: 100 Ticks per Chunk**

With this configuration:
- Sampled ticks: 0, 100, 200, 300, ...
- Accumulated deltas: every 500 ticks (0, 500, 1000, ...)
- Snapshots: every 10,000 ticks (0, 10000, 20000, ...)
- Chunk boundaries: every 10,000 ticks (= snapshot boundaries)

**Trade-off Analysis:**

| Metric | Value | Rationale |
|--------|-------|-----------|
| Ticks/Chunk | 100 | Large chunks for I/O efficiency |
| Max incremental deltas to apply | 4 | Fast API (accumulatedDeltaInterval=5) |
| Heap (chunk build) | ~25MB | Acceptable for modern systems |
| Storage reduction | ~90% | Only 1 snapshot per 100 sampled ticks |
| API cold (cache miss) | ~16ms | Acceptable, rare with LRU cache |
| API warm (cache hit) | ~5ms | Dominant case during scrubbing |
| Amortized API latency | ~5.1ms | (16ms + 99×5ms) / 100 ticks |

**Why these values:**
- `accumulatedDeltaInterval = 5`: Keeps API fast (max 4 deltas to apply)
- `snapshotInterval = 20`: Large chunks for storage/I/O efficiency
- Large chunks + frequent accumulated = best of both worlds

---

## 3. Environment Change Tracking

### 3.1. Data Structure

Use `java.util.BitSet` for tracking changed cell indices:

```java
public class Environment {
    private final BitSet changedSinceLastReset;
    
    public void setMolecule(Molecule molecule, int ownerId, int[] coordinates) {
        int flatIndex = toFlatIndex(coordinates);
        // ... existing logic ...
        changedSinceLastReset.set(flatIndex);  // Track change
    }
    
    public BitSet getChangedIndices() {
        return changedSinceLastReset;
    }
    
    public void resetChangeTracking() {
        changedSinceLastReset.clear();
    }
}
```

### 3.2. Rationale for BitSet

| Aspect | BitSet | IntHashSet |
|--------|--------|------------|
| Memory | Fixed: 1 bit/cell (125KB for 1M cells) | Variable: ~8 bytes/change |
| Performance | O(1) set/check | O(1) amortized, but hash overhead |
| Iteration | `nextSetBit()` is very fast | Iterator overhead |
| Multithreading | `or()` merge is trivial | `addAll()` slower |

### 3.3. Future Multithreading Consideration

The simulation engine is prepared for multithreading with plan/resolve/execute phases. The change tracking design supports this:

- **Current (single-thread)**: One BitSet in Environment
- **Future (multi-thread)**: Thread-local BitSets, merged in a 4th phase via `result.or(threadLocalBitSet)`

The primitive int32 arrays in Environment remain unchanged for performance.

---

## 4. Protobuf Schema Changes

### 4.1. New Message Types

```protobuf
// Chunk containing one snapshot + multiple deltas (self-contained unit)
message TickDataChunk {
  // === Metadata (for PersistenceService routing, no content parsing needed) ===
  string simulation_run_id = 1;
  int64 first_tick = 2;
  int64 last_tick = 3;
  int32 tick_count = 4;
  
  // === Snapshot (complete state, always first tick in chunk) ===
  TickData snapshot = 5;
  
  // === Deltas (compact, only changes) ===
  repeated TickDelta deltas = 6;
}

// Compact representation of changes between ticks
message TickDelta {
  int64 tick_number = 1;
  int64 capture_time_ms = 2;
  DeltaType delta_type = 3;
  
  // Environment: ONLY changed cells (the big win!)
  CellDataColumns changed_cells = 4;
  
  // Organisms: Always complete (small, ~500 bytes per organism)
  repeated OrganismState organisms = 5;
  int64 total_organisms_created = 6;
  
  // RNG/Strategy: Only for ACCUMULATED deltas (checkpoint capability)
  bytes rng_state = 7;
  repeated StrategyState strategy_states = 8;
}

enum DeltaType {
  DELTA_TYPE_UNSPECIFIED = 0;
  INCREMENTAL = 1;   // Changes since last sampled tick
  ACCUMULATED = 2;   // Changes since last snapshot (checkpoint-capable)
}
```

### 4.2. Design Rationale

| Field | In Snapshot | In Incremental Delta | In Accumulated Delta |
|-------|-------------|---------------------|---------------------|
| `cell_columns` | All occupied cells | Only changed cells | Only changed cells |
| `organisms` | All alive | All alive | All alive |
| `rng_state` | Yes | No | Yes |
| `strategy_states` | Yes | No | Yes |

**Why organisms are always complete:**
- Small size (~500 bytes per organism)
- Change almost completely every tick (IP, energy, registers)
- Delta tracking overhead would exceed savings

**Why RNG/strategy only in accumulated deltas:**
- Enables checkpoint/resume from accumulated deltas
- Saves ~2.5KB per incremental delta

### 4.3. Storage Estimates

| Tick Type | Before (1M cells, 100% occupied) | After |
|-----------|----------------------------------|-------|
| Snapshot | ~42MB | ~42MB |
| Accumulated Delta (1% changes) | ~42MB | ~500KB |
| Incremental Delta (0.1% changes) | ~42MB | ~90KB |

**Chunk example (10 ticks: 1 snapshot + 9 deltas):**
- Before: 10 × 42MB = 420MB
- After: 42MB + 2×500KB + 7×90KB ≈ 43.6MB (90% reduction)

---

## 5. Component Changes

### 5.1. SimulationEngine

**Current behavior:**
- Creates `TickData` for each sampled tick
- Sends individual `TickData` to queue

**New behavior:**
- Tracks changes via `Environment.getChangedIndices()`
- Maintains `BitSet accumulatedSinceSnapshot` for accumulated deltas
- Builds `TickDataChunk` containing snapshot + deltas
- Sends complete chunks to queue (not individual ticks)

**Chunk building logic:**
```
for each tick:
    if samplingInterval hit:
        incrementalDelta = env.getChangedIndices()
        accumulatedSinceSnapshot.or(incrementalDelta)
        env.resetChangeTracking()
        
        if snapshot tick:
            finalize current chunk
            start new chunk with snapshot
            accumulatedSinceSnapshot.clear()
        else if accumulated delta tick:
            add TickDelta(ACCUMULATED, accumulatedSinceSnapshot, with RNG)
        else:
            add TickDelta(INCREMENTAL, incrementalDelta, without RNG)
        
        if chunk complete:
            send chunk to queue
```

**Graceful Shutdown Behavior:**

When the system shuts down gracefully, the existing drain behavior applies:

1. **SimulationEngine**: Stop computing new ticks, but flush any partial chunk currently being built (even if incomplete). A partial chunk still starts with a snapshot and is self-contained.

2. **Queue**: All chunks already enqueued will be delivered to consumers before the queue closes.

3. **PersistenceService**: Stop polling new chunks from the queue, but complete writing any chunks already claimed. This ensures no data loss for in-flight batches.

4. **Indexers**: Process any remaining chunks in their batch before stopping.

This is consistent with the existing pipeline's drain semantics - no additional logic required.

**Compression Metrics (O(1) recording):**

```java
// AtomicLong counters for compression statistics
private final AtomicLong totalSnapshotBytes = new AtomicLong();
private final AtomicLong totalDeltaBytes = new AtomicLong();
private final AtomicLong totalCellsChanged = new AtomicLong();
private final AtomicLong chunksCreated = new AtomicLong();

// When creating chunk - all O(1) operations
void recordChunkMetrics(TickDataChunk chunk) {
    totalSnapshotBytes.addAndGet(chunk.getSnapshot().getSerializedSize());
    for (TickDelta delta : chunk.getDeltasList()) {
        totalDeltaBytes.addAndGet(delta.getSerializedSize());
        totalCellsChanged.addAndGet(delta.getChangedCells().getFlatIndicesCount());
    }
    chunksCreated.incrementAndGet();
}

// Exposed via IMonitorable for on-demand ratio calculation
public double getCompressionRatio() {
    long snapshots = totalSnapshotBytes.get();
    long deltas = totalDeltaBytes.get();
    return deltas > 0 ? (double) snapshots / deltas : 0;
}

public double getAvgCellsChangedPerDelta() {
    long chunks = chunksCreated.get();
    return chunks > 0 ? (double) totalCellsChanged.get() / (chunks * ticksPerChunk) : 0;
}
```

These metrics help tune interval configuration and validate compression effectiveness.

### 5.2. PersistenceService

**Changes: Minimal (blind pass-through)**

- Queue type: `IInputQueueResource<TickData>` → `IInputQueueResource<TickDataChunk>`
- Batch validation: Uses `chunk.getSimulationRunId()` (same logic)
- Tick range: Uses `chunk.getFirstTick()`, `chunk.getLastTick()` (top-level fields)
- Storage: `writeBatch(List<TickDataChunk>, ...)` instead of `List<TickData>`
- Metrics: `ticksWritten` counts `chunk.getTickCount()` sum

**No parsing of chunk contents required.**

**DLQ Handling:** Unchanged behavior. If a `TickDataChunk` cannot be written after max retries, the entire chunk (including all deltas) is sent to the DLQ. The chunk remains a self-contained unit.

**DLQ Memory Impact:** The DLQ now holds chunks instead of individual ticks. Users MUST reduce `dlq.maxSize` to maintain similar memory footprint. Example: If `dlq.maxSize = 100` was configured for 100 individual ticks (~10MB each = 1GB), and chunks now contain 100 ticks (~25MB each), reduce to `dlq.maxSize = 40` for similar memory (~1GB). The DLQ's `IMemoryEstimatable` implementation must be updated accordingly (see Section 13.10).

### 5.3. Storage Layer

**IBatchStorageWrite:**
```java
// Before
StoragePath writeBatch(List<TickData> batch, long firstTick, long lastTick);

// After
StoragePath writeBatch(List<TickDataChunk> batch, long firstTick, long lastTick);
```

**IBatchStorageRead:**
```java
// Before
List<TickData> readBatch(StoragePath path);

// After
List<TickDataChunk> readBatch(StoragePath path);
```

### 5.4. AnalyticsIndexer + Plugins

**Analysis of existing plugins:**

| Plugin | Stateful | Uses OrganismStates | Uses Environment (CellDataColumns) |
|--------|----------|---------------------|-----------------------------------|
| `PopulationMetricsPlugin` | No | Yes | No |
| `VitalStatsPlugin` | No | Yes | No |
| `GenerationDepthPlugin` | Yes | Yes | No |
| `EnvironmentCompositionPlugin` | No | No | **Yes** |
| `InstructionUsagePlugin` | No | Yes | No |
| `AgeDistributionPlugin` | No | Yes | No |

**Key insight:** Organisms are always complete in deltas (see Proto definition). Only environment cell data requires decompression for plugins that need full environment state.

**Strategy for AnalyticsIndexer:**

**Full decompression approach** - The indexer uses `DeltaCodec.decompressChunk()` to reconstruct complete `TickData` objects for plugin processing. This:
- **Preserves plugin API unchanged** - plugins continue to receive `TickData`
- **Provides correct semantics** - all plugins see complete environment state
- **Has negligible overhead** - decompression is O(changedCells) per tick (~microseconds)
- **Uses moderate heap** - one `int[]` of environment size for `MutableCellState`

**All plugins work unchanged:**
- `EnvironmentCompositionPlugin` receives complete cell data (decompressed)
- Organism-based plugins receive complete organism lists (already complete in deltas)
- No plugin code changes required

**Performance Analysis:**
```
Decompression per tick: O(changedCells), not O(totalCells)
At 1% change rate, 1M cells: ~10,000 array operations (~10-50 µs)
Per chunk (100 ticks): ~1-5 ms decompression overhead
Compared to DuckDB/Parquet I/O: negligible
```

**Heap Impact:**
```
MutableCellState = int[totalCells]
1000×1000 environment: 4 MB
5000×5000 environment: 100 MB
```
This is documented in `IMemoryEstimatable` and included in heap warnings.

**AbstractBatchIndexer changes:**

The base class is updated to work with chunks:

```java
// Before
List<TickData> ticks = storage.readBatch(storagePath);
flushTicks(ticks);

// After
List<TickDataChunk> chunks = storage.readChunkBatch(storagePath);
flushChunks(chunks);  // Each indexer handles chunks as needed
```

Template method signature:
```java
protected abstract void flushChunks(List<TickDataChunk> chunks) throws Exception;
```

**Indexer-specific handling:**
- **DummyIndexer**: Counts ticks for logging
- **OrganismIndexer**: Extracts organisms directly from chunks (no decompression needed - organisms are complete in deltas)
- **EnvironmentIndexer**: Stores chunks as-is (blob storage)
- **AnalyticsIndexer**: Uses `DeltaCodec.decompressChunk()` to provide plugins with complete `TickData`

**Plugin API unchanged:** Analytics plugins continue to receive `TickData` with full environment state. No breaking changes to plugin interface.

### 5.5. EnvironmentIndexer

**Key decision: Store compressed (deltas) in database**

The EnvironmentIndexer does NOT decompress. It stores chunks as-is to maximize storage savings.

**Strategy Interface (fully encapsulated):**

```java
public interface IH2EnvStorageStrategy {
    void createTables(Connection conn, int dimensions) throws SQLException;
    
    // Strategy decides how to store (schema is encapsulated)
    void writeChunk(Connection conn, TickDataChunk chunk, 
                    EnvironmentProperties envProps) throws SQLException;
    
    // Strategy handles reconstruction internally
    List<CellState> readTick(Connection conn, long tickNumber, 
                             SpatialRegion region, 
                             EnvironmentProperties envProps) 
                             throws SQLException, TickNotFoundException;
}
```

**Initial Strategy: RowPerChunkStrategy**

Schema:
```sql
CREATE TABLE environment_chunks (
  first_tick BIGINT PRIMARY KEY,
  last_tick BIGINT NOT NULL,
  chunk_blob BYTEA NOT NULL
)
```

| Aspect | Value |
|--------|-------|
| Storage | One row per chunk (~5MB compressed) |
| Rows at 15M ticks | ~300K (vs 15M with row-per-tick) |
| Index size | ~50MB (fits in RAM) |
| Write speed | Fast (1 MERGE per chunk) |
| Read speed | Must load entire chunk, reconstruct target tick |

**Rationale for Row-per-Chunk over Row-per-Tick:**

| Factor | Row-per-Chunk | Row-per-Tick |
|--------|---------------|--------------|
| MERGE complexity at 15M ticks | O(log 300K) = 18 levels | O(log 15M) = 24 levels |
| Index in RAM | Yes (~50MB) | Borderline (~2.5GB) |
| Write performance at scale | Stable | Degrades with size |
| Read (single tick) | Slower (load full chunk) | Faster (load only needed rows) |

The write scalability advantage outweighs the read disadvantage. Read performance can be optimized at the controller level if needed.

**Future alternative: RowPerTickStrategy**

Could be implemented later if read performance becomes critical:
```sql
CREATE TABLE environment_ticks (
  tick_number BIGINT PRIMARY KEY,
  tick_type TINYINT NOT NULL,
  base_tick BIGINT,
  cells_blob BYTEA NOT NULL
)
```

The encapsulated interface allows switching strategies without changing the indexer.

### 5.6. Environment Controller (HTTP API)

**Decision: Server-side decompression (in controller/strategy)**

When the frontend requests environment state for a specific tick:
1. Controller calls `strategy.readTick(tickNumber, region, envProps)`
2. Strategy loads chunk from database
3. Strategy reconstructs target tick using `DeltaCodec`
4. Strategy applies region filter (only viewport cells)
5. Controller returns filtered cells as JSON

**Why server-side decompression (not frontend):**

| Factor | Controller | Frontend |
|--------|------------|----------|
| Network transfer (100×100 viewport) | ~100KB | ~5MB (50× more!) |
| Server CPU | Higher | Lower |
| Client complexity | None | Needs DeltaCodec in JS |
| Region filtering | Before transfer | After transfer (wasteful) |

The viewport is typically 1-10% of the environment. Frontend decompression would transfer 10-100× more data.

**Performance optimization: LRU Chunk Cache**

To avoid re-loading the same chunk for consecutive tick queries (scrubbing):

```java
// In RowPerChunkStrategy (NOT in EnvironmentController - keeps controller thin)
private final Cache<Long, ReconstructedChunk> chunkCache = 
    Caffeine.newBuilder()
        .maximumSize(5)  // Cache 5 most recent chunks
        .build();

public List<CellState> readTick(long tickNumber, SpatialRegion region, ...) {
    long chunkFirstTick = findChunkContaining(tickNumber);
    
    ReconstructedChunk chunk = chunkCache.get(chunkFirstTick, 
        k -> loadAndDecompressChunk(k));
    
    return chunk.getCellsAt(tickNumber, region);
}
```

**Cache benefits:**
- Scrubbing within same chunk: ~5ms (cache hit) vs ~40ms (cache miss)
- Memory: ~5 chunks × ~50MB reconstructed = ~250MB max
- Cache location: Inside strategy (encapsulated) or controller (shared across strategies)

### 5.7. OrganismIndexer

**Minimal changes expected**

Organisms are always complete in both snapshots and deltas. The indexer extracts `OrganismState` from either `TickData.organisms` or `TickDelta.organisms`.

### 5.8. CLI Commands

**InspectStorageSubcommand:**
- Must understand chunk structure for display
- Should show chunk boundaries, delta types, compression ratios

**RenderVideoCommand + SimulationRenderer:**
- Must decompress to render frames
- Uses `DeltaCodec` utility for decompression

### 5.9. DeltaCodec Utility Class

**Central utility for all compression and decompression operations.**

Location: `org.evochora.datapipeline.utils.delta.DeltaCodec`

```java
public class DeltaCodec {
    
    // ==================== Compression (SimulationEngine) ====================
    
    /**
     * Creates a TickDataChunk from a list of tick captures.
     * First capture must be a snapshot, rest are deltas.
     */
    public TickDataChunk createChunk(
        String simulationRunId,
        TickData snapshot,
        List<DeltaCapture> deltas
    );
    
    /**
     * Creates a delta from pre-extracted cell data.
     * SimulationEngine extracts changed cells from Environment + BitSet before calling.
     * This keeps DeltaCodec independent of runtime package.
     */
    public static TickDelta createDelta(
        long tickNumber,
        long captureTimeMs,
        DeltaType deltaType,
        CellDataColumns changedCells,       // Already extracted by SimulationEngine
        List<OrganismState> organisms,      // Already converted to protobuf
        long totalOrganismsCreated,
        ByteString rngState,                // Empty for INCREMENTAL
        List<StrategyState> strategyStates  // Empty for INCREMENTAL
    );
    
    // ==================== Decompression (Storage consumers) ====================
    
    /**
     * Decompresses an entire chunk to a list of full TickData objects.
     * Use when all ticks are needed (e.g., video rendering).
     */
    public List<TickData> decompressChunk(TickDataChunk chunk);
    
    /**
     * Decompresses a single tick from a chunk.
     * Optimized: jumps to nearest accumulated delta before target.
     */
    public TickData decompressTick(TickDataChunk chunk, long targetTick);
    
    // ==================== Low-level (Database consumers) ====================
    
    /**
     * Reconstructs environment state from a base snapshot and sequence of deltas.
     * Used by EnvironmentController when reading from database.
     * 
     * @param baseSnapshot The starting point (snapshot tick's CellDataColumns)
     * @param deltas Sequence of delta CellDataColumns to apply
     * @return Fully reconstructed environment state
     */
    public CellDataColumns reconstructEnvironment(
        CellDataColumns baseSnapshot,
        List<CellDataColumns> deltas
    );
    
    /**
     * Applies a single delta to a mutable environment state.
     * Building block for incremental reconstruction.
     */
    public void applyDelta(
        MutableCellState state,
        CellDataColumns delta
    );
}

/**
 * Mutable state for incremental environment reconstruction.
 * Used internally by DeltaCodec for decompression.
 */
public class MutableCellState {
    private final Int2IntOpenHashMap moleculeByIndex;
    private final Int2IntOpenHashMap ownerByIndex;
    
    public void applyChanges(CellDataColumns delta);
    public CellDataColumns toImmutable();
    public int getCellCount();
    public void clear();
}
```

**Usage by components:**

| Component | Methods Used | Notes |
|-----------|--------------|-------|
| SimulationEngine | `createChunk()`, `createDelta()` | Compression |
| AnalyticsIndexer | `decompressChunk()` | Full decompression for plugins |
| EnvironmentController | `decompressTick()` | On-demand single tick |
| VideoCommand | **None!** | Uses IncrementalRenderer directly |
| InspectCommand | `decompressTick()` | Single tick display |

**Design rationale:**
- Single source of truth for compression/decompression logic
- Low-level methods (`applyDelta`, `MutableCellState`) enable efficient incremental processing
- Database consumers can use the same core logic with different data sources

---

## 6. Decompression Logic

### 6.1. Reconstructing Full State

```java
public TickData decompress(TickDataChunk chunk, long targetTick) {
    // Start with snapshot
    TickData base = chunk.getSnapshot();
    if (base.getTickNumber() == targetTick) {
        return base;
    }
    
    // Build environment state from snapshot
    Map<Integer, Integer> cells = new HashMap<>();
    for (int i = 0; i < base.getCellColumns().getFlatIndicesCount(); i++) {
        cells.put(
            base.getCellColumns().getFlatIndices(i),
            base.getCellColumns().getMoleculeData(i)
        );
    }
    
    // Apply deltas sequentially until target tick
    for (TickDelta delta : chunk.getDeltasList()) {
        if (delta.getTickNumber() > targetTick) break;
        
        // Apply cell changes
        for (int i = 0; i < delta.getChangedCells().getFlatIndicesCount(); i++) {
            int flatIndex = delta.getChangedCells().getFlatIndices(i);
            int moleculeData = delta.getChangedCells().getMoleculeData(i);
            if (moleculeData == 0) {
                cells.remove(flatIndex);  // Cell cleared
            } else {
                cells.put(flatIndex, moleculeData);
            }
        }
        
        if (delta.getTickNumber() == targetTick) {
            return buildTickData(delta, cells);
        }
    }
    
    throw new IllegalArgumentException("Target tick not found in chunk");
}
```

### 6.2. Optimization: Jump to Accumulated Delta

When seeking to a specific tick, accumulated deltas allow skipping intermediate incremental deltas:

```java
// Find closest accumulated delta before target tick
TickDelta bestBase = null;
for (TickDelta delta : chunk.getDeltasList()) {
    if (delta.getTickNumber() > targetTick) break;
    if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
        bestBase = delta;
    }
}

// Apply from bestBase (or snapshot if none found)
```

### 6.3. Error Handling

**Design Decision:** Follow existing pattern from `CompressionException` - DeltaCodec throws checked exceptions, services handle them.

**Exception Class:**
```java
/**
 * Thrown when a TickDataChunk is corrupt or cannot be decompressed.
 * <p>
 * Callers should catch this exception and handle according to AGENTS.md:
 * <ul>
 *   <li>log.warn("msg", args) - NO exception parameter</li>
 *   <li>recordError(code, msg, details) - for health monitoring</li>
 *   <li>Continue processing (skip corrupt chunk)</li>
 * </ul>
 */
public class ChunkCorruptedException extends Exception {
    public ChunkCorruptedException(String message) { super(message); }
    public ChunkCorruptedException(String message, Throwable cause) { super(message, cause); }
}
```

**DeltaCodec throws checked exception:**
```java
public TickData decompressTick(TickDataChunk chunk, long targetTick) 
        throws ChunkCorruptedException {
    if (chunk.getSnapshot() == null) {
        throw new ChunkCorruptedException("Chunk missing snapshot (firstTick=" + chunk.getFirstTick() + ")");
    }
    if (targetTick < chunk.getFirstTick() || targetTick > chunk.getLastTick()) {
        throw new ChunkCorruptedException("Tick " + targetTick + " not in chunk range [" + 
            chunk.getFirstTick() + ", " + chunk.getLastTick() + "]");
    }
    // ... decompression logic
}
```

**Service handling (AnalyticsIndexer, EnvironmentIndexer, etc.):**
```java
// Following AGENTS.md: Transient Errors (service continues)
try {
    List<TickView> views = deltaCodec.createTickViews(chunk);
    processViews(views);
} catch (ChunkCorruptedException e) {
    // log.warn WITHOUT exception parameter (stack trace at DEBUG via framework)
    log.warn("Skipping corrupt chunk (firstTick={}): {}", chunk.getFirstTick(), e.getMessage());
    recordError("CHUNK_CORRUPT", "Corrupt chunk skipped", 
                "FirstTick: " + chunk.getFirstTick() + ", Reason: " + e.getMessage());
    // Continue with next chunk - simulation keeps running!
    chunksSkipped.incrementAndGet();
}
```

**Rationale:**
- **Checked exception**: Compiler enforces handling, consistent with `CompressionException`
- **Service decides**: Skip, DLQ, or retry - DeltaCodec doesn't make this decision
- **Never abort**: Long-running simulations (days) must survive corrupt chunks
- **Monitoring**: `recordError()` tracks failures for health status without stopping

---

## 7. Implementation Order

**Guiding principles:**
- Each step results in compilable code
- Run `./gradlew build` after each step
- Write tests before or with implementation
- Integration tests run against the full pipeline as early as possible

---

### Step 1: Protobuf Schema (compile after)

**Files:**
- `src/main/proto/.../tickdata_contracts.proto`

**Changes:**
```protobuf
// ADD to existing file:
enum DeltaType { ... }
message TickDelta { ... }
message TickDataChunk { ... }
```

**Verification:**
```bash
./gradlew build  # Protobuf generates Java classes
```

**Tests:** None yet (just schema)

---

### Step 2: ~~TickView Record + TickType Enum~~ ❌ REMOVED

**Status:** Removed in Step 13. TickView was not needed - AnalyticsIndexer uses `decompressChunk()` 
directly, providing plugins with complete `TickData`. No abstraction layer required.

---

### Step 3: DeltaCodec - Decompression Only (compile + test) ✅ COMPLETED

**Files:**
- `src/main/java/org/evochora/datapipeline/utils/delta/DeltaCodec.java` (NEW)
- `src/main/java/org/evochora/datapipeline/utils/delta/MutableCellState.java` (NEW)
- `src/main/java/org/evochora/datapipeline/api/delta/ChunkCorruptedException.java` (NEW)

**Changes:**
```java
public class ChunkCorruptedException extends Exception {
    public ChunkCorruptedException(String message) { super(message); }
    public ChunkCorruptedException(String message, Throwable cause) { super(message, cause); }
}

public class DeltaCodec {
    // Decompression methods - all throw ChunkCorruptedException for corrupt data
    public static List<TickData> decompressChunk(TickDataChunk chunk, int totalCells) throws ChunkCorruptedException;
    public static TickData decompressTick(TickDataChunk chunk, long targetTick, int totalCells) throws ChunkCorruptedException;
    public static CellDataColumns reconstructEnvironment(CellDataColumns base, List<CellDataColumns> deltas, int totalCells);
}
```

**Verification:**
```bash
./gradlew build
./gradlew test --tests "DeltaCodecDecompressionTest"
```

**Tests:**
- `DeltaCodecDecompressionTest.java`
  - Build test chunks manually in code
  - Test `decompressTick()` returns correct state
  - Test `decompressChunk()` returns all ticks with fully reconstructed environment
  - Test `reconstructEnvironment()` with additions, modifications, deletions
  - Edge cases: empty delta, single-tick chunk, tick 0

---

### Step 4: DeltaCodec - Compression (compile + test)

**Files:**
- `src/main/java/org/evochora/datapipeline/utils/delta/DeltaCodec.java` (extend)
- `src/main/java/org/evochora/datapipeline/utils/delta/DeltaCapture.java` (NEW - helper record)

**Changes:**
```java
public class DeltaCodec {
    // ADD compression methods (static, no runtime dependency)
    public static TickDataChunk createChunk(String runId, TickData snapshot, List<DeltaCapture> deltas);
    public static TickDelta createDelta(
        long tick, long captureMs, DeltaType type,
        CellDataColumns changedCells,      // Pre-extracted by SimulationEngine
        List<OrganismState> organisms,     // Already converted
        long totalOrganismsCreated,
        ByteString rngState,               // Empty for INCREMENTAL
        List<StrategyState> strategyStates // Empty for INCREMENTAL
    );
}

// Helper record - stores delta until chunk is complete
public record DeltaCapture(long tickNumber, long captureTimeMs, TickDelta delta) {}
```

**Verification:**
```bash
./gradlew build
./gradlew test --tests "DeltaCodecCompressionTest"
./gradlew test --tests "DeltaCodecRoundTripTest"
```

**Tests:**
- `DeltaCodecCompressionTest.java`
  - Test `createDelta()` builds valid TickDelta protobuf
  - Test `createChunk()` builds valid TickDataChunk
  - Test incremental vs accumulated delta types
- `DeltaCodecRoundTripTest.java` (**CRITICAL**)
  - Create CellDataColumns with known state
  - Create snapshot + deltas, build chunk
  - Decompress chunk
  - **Assert decompressed == original** (byte-for-byte for cells)

---

### Step 5: Environment Change Tracking (compile + test)

**Files:**
- `src/main/java/org/evochora/runtime/Environment.java` (modify)

**Changes:**
```java
public class Environment {
    private final BitSet changedSinceLastReset;  // NEW
    
    public void setMolecule(...) {
        // existing logic
        changedSinceLastReset.set(flatIndex);  // ADD
    }
    
    public BitSet getChangedIndices() { return changedSinceLastReset; }
    public void resetChangeTracking() { changedSinceLastReset.clear(); }
}
```

**Verification:**
```bash
./gradlew build
./gradlew test --tests "EnvironmentChangeTrackingTest"
```

**CRITICAL: Run full test suite (modifying central runtime class):**
```bash
./gradlew test
```

**Tests:**
- `EnvironmentChangeTrackingTest.java`
  - Set molecules, verify BitSet contains correct indices
  - Reset, verify BitSet is empty
  - Multiple changes to same cell, verify only one bit set
  - Clear cell (set to 0), verify bit still set (change is a change)

---

### Step 6: SimulationParameters Extensions (compile + test)

**Files:**
- `src/main/java/org/evochora/datapipeline/api/memory/SimulationParameters.java` (modify)

**Changes:**
- Add delta compression fields
- Add `ticksPerChunk()`, `estimateBytesPerChunk()`, etc.

**Verification:**
```bash
./gradlew build
./gradlew test --tests "SimulationParametersTest"
```

**Tests:**
- `SimulationParametersTest.java`
  - Test derived values with various configurations
  - Test `estimateCompressionRatio()` returns sensible values

---

### Step 7: ChunkBuilder + SimulationEngine Integration (compile + test) ✅ COMPLETED

**Files:**
- `src/main/java/org/evochora/datapipeline/utils/delta/ChunkBuilder.java` (NEW)
- `src/main/java/org/evochora/datapipeline/services/SimulationEngine.java` (modified - minimal)
- `src/test/java/org/evochora/datapipeline/utils/delta/ChunkBuilderTest.java` (NEW)
- `evochora.conf` (updated with delta compression parameters)

**Architecture Decision:** Extract chunk-building logic into separate `ChunkBuilder` helper class to keep SimulationEngine clean and make the logic independently testable.

**ChunkBuilder (NEW):**
```java
public class ChunkBuilder {
    // Configuration
    private final String runId;
    private final int accumulatedDeltaInterval;
    private final int snapshotInterval;
    private final int chunkInterval;
    
    // State
    private TickData currentSnapshot;
    private List<DeltaCapture> currentDeltas = new ArrayList<>();
    private BitSet accumulatedSinceSnapshot;
    private int sampledTicksInChunk = 0;
    
    // Main entry point - SimulationEngine calls only this
    public Optional<TickDataChunk> captureTick(
            long tick, Environment env, List<OrganismState> organisms,
            long totalOrganismsCreated, ByteString rngState, List<StrategyState> strategies);
    
    // For graceful shutdown - flush partial chunk
    public Optional<TickDataChunk> flushPartialChunk();
}
```

**SimulationEngine changes:**
- Added delta compression configuration fields (accumulatedDeltaInterval, snapshotInterval, chunkInterval)
- Added ChunkBuilder instance (prepared for Step 10 integration)
- Added helper methods: `extractOrganismStates()`, `extractStrategyStates()`
- TEMPORARY: Continues using `captureTickData()` for backwards compatibility
- Added `env.resetChangeTracking()` after each sample

**evochora.conf updates:**
- Added comprehensive documentation for delta compression parameters
- Added commented-out defaults for accumulatedDeltaInterval, snapshotInterval, chunkInterval, estimatedDeltaRatio

**Note:** Full ChunkBuilder integration deferred to Step 10 when queue switches to TickDataChunk. Current implementation maintains backwards compatibility while preparing all components.

**Verification:**
```bash
./gradlew build                              # ✅ Passed
./gradlew test --tests "ChunkBuilderTest"    # ✅ All 14 tests passed
./gradlew test                               # ✅ All 1191 tests passed
```

**Tests:**
- `ChunkBuilderTest.java` (NEW - 14 tests)
  - Configuration validation
  - Snapshot at tick 0
  - Incremental delta creation
  - Accumulated delta at correct intervals
  - Accumulated delta contains all changes since snapshot
  - Chunk completion triggers Optional.of()
  - Multi-chunk sequences
  - flushPartialChunk()
  - Change tracking reset

---

### Step 8: Storage Layer - Dual Support (compile + test) ✅ COMPLETED

**Files:**
- `src/main/java/org/evochora/datapipeline/api/resources/storage/IBatchStorageWrite.java` (modified)
- `src/main/java/org/evochora/datapipeline/api/resources/storage/IBatchStorageRead.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/storage/AbstractBatchStorageResource.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/storage/FileSystemStorageResource.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/storage/wrappers/MonitoredBatchStorageReader.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/storage/wrappers/MonitoredBatchStorageWriter.java` (modified)
- `src/test/java/org/evochora/datapipeline/resources/storage/FileSystemStorageResourceTest.java` (added chunk tests)

**Changes:**
Added NEW methods alongside existing ones (temporary backwards compatibility):
```java
// NEW methods (alongside existing)
StoragePath writeChunkBatch(List<TickDataChunk> batch, long firstTick, long lastTick);
List<TickDataChunk> readChunkBatch(StoragePath path);

// Mark existing methods as deprecated (to be removed in Step 18):
@Deprecated(forRemoval = true)
StoragePath writeBatch(List<TickData> batch, long firstTick, long lastTick);
@Deprecated(forRemoval = true)
List<TickData> readBatch(StoragePath path);
```

Also added abstract method `writeChunkAtomicStreaming()` for backend-specific implementations.

**Note:** Deprecated methods will be removed in Step 18 after full migration.

**Verification:**
```bash
./gradlew build                                      # ✅ Passed (with deprecation warnings)
./gradlew test --tests "FileSystemStorageResourceTest"  # ✅ All 17 tests passed
./gradlew test                                       # ✅ All tests passed
```

**Tests Added:**
- `testWriteChunkBatch_ReadChunkBatch_RoundTrip()` - Write chunks, read back, verify content matches
- `testWriteChunkBatch_EmptyBatch_Throws()` - Empty batch validation
- `testWriteChunkBatch_InvalidTickOrder_Throws()` - Tick order validation
- `testReadChunkBatch_NotFound()` - Not found handling

---

### Step 9 + 10: Queue Message Type Switch ✅ COMPLETED

**Note:** Steps 9 and 10 were implemented together because the queue message type change requires both producer (SimulationEngine) and consumer (PersistenceService) to be updated atomically.

**Files Changed:**
- `src/main/java/org/evochora/datapipeline/services/PersistenceService.java`
- `src/main/java/org/evochora/datapipeline/services/SimulationEngine.java`
- `src/test/java/org/evochora/datapipeline/services/PersistenceServiceTest.java`
- `src/test/java/org/evochora/datapipeline/services/SimulationEngineIntegrationTest.java`
- `src/test/java/org/evochora/datapipeline/services/SimulationToPersistenceIntegrationTest.java`
- `src/testFixtures/java/org/evochora/test/utils/FileUtils.java`

**SimulationEngine Changes:**
- Queue type changed: `IOutputQueueResource<TickDataChunk>` (was `TickData`)
- Now uses `ChunkBuilder.captureTick()` to build chunks with delta compression
- Sends complete `TickDataChunk` when chunk is ready
- Flushes partial chunks on shutdown via `ChunkBuilder.flushPartialChunk()`

**PersistenceService Changes:**
- Queue type changed: `IInputQueueResource<TickDataChunk>` (was `TickData`)
- Uses `storage.writeChunkBatch()` instead of deprecated `writeBatch()`
- Updated metrics to count `chunk.getTickCount()` for actual tick counts
- Updated deduplication to use chunk's `firstTick` as idempotency key
- Updated DLQ message type to `List<TickDataChunk>`
- All log messages updated: "tick" → "chunk" where appropriate

**Test Changes:**
- All tests updated to work with `TickDataChunk`
- `FileUtils.readAllTicksFromBatches()` now reads chunks and extracts snapshots
- Added new `FileUtils.readAllChunksFromBatches()` for direct chunk access
- Test configs updated with delta compression parameters:
  - `accumulatedDeltaInterval: 1`
  - `snapshotInterval: 1`  
  - `chunkInterval: 1`
  - This ensures 1 sample = 1 chunk for predictable test behavior

**Verification:**
```bash
./gradlew build                              # ✅ Passed
./gradlew test                               # ✅ All 1191 tests passed
```

**IMemoryEstimatable Updates (added as part of Step 9+10):**

The following components had their memory estimation updated to account for chunk-based processing:

1. **SimulationEngine** - Added ChunkBuilder memory estimation:
   - Current snapshot: 1 full TickData
   - Accumulated deltas: up to (samplesPerChunk - 1) deltas  
   - Change tracking BitSet: totalCells / 8 bytes

2. **PersistenceService** - Changed from tick-based to chunk-based:
   - Now uses `params.estimateBytesPerChunk()` instead of `estimateBytesPerTick()`
   - `maxBatchSize` is in CHUNKS, not ticks

3. **InMemoryBlockingQueue** - Updated default estimation:
   - Now uses `params.estimateBytesPerChunk()` as default (was `estimateBytesPerTick()`)
   - Main queue holds TickDataChunk after delta compression
   - Custom `estimatedBytesPerItem` config still available for other queue types

---

### Step 11: AbstractBatchIndexer + ChunkBuffering (compile + test) ✅ COMPLETED

**Architectural Decision:**
- `insertBatchSize` means **number of CHUNKS** for ALL indexers (consistent semantics)
- Buffering is chunk-based: buffer holds chunks, flush when chunk count >= insertBatchSize or timeout
- Each indexer implements `flushChunks(List<TickDataChunk>)` and decides how to process chunks
- AnalyticsIndexer uses `DeltaCodec.decompressChunk()` to provide plugins with complete `TickData`
- **Plugins remain unchanged** - they continue to receive `TickData` with full environment state

**Files:**
- `src/main/java/org/evochora/datapipeline/services/indexers/components/ChunkBufferingComponent.java` (renamed from TickBufferingComponent)
- `src/main/java/org/evochora/datapipeline/services/indexers/AbstractBatchIndexer.java`
- `src/main/java/org/evochora/datapipeline/services/indexers/DummyIndexer.java`
- `src/main/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexer.java`
- `src/main/java/org/evochora/datapipeline/services/indexers/OrganismIndexer.java`
- `src/main/java/org/evochora/datapipeline/services/indexers/AnalyticsIndexer.java`
- `src/test/java/org/evochora/datapipeline/services/indexers/components/ChunkBufferingComponentTest.java` (renamed)
- `evochora.conf` - update insertBatchSize documentation and defaults

**Changes (TickBufferingComponent renamed to ChunkBufferingComponent):**
```java
// Rename and refactor to buffer chunks instead of ticks
public class ChunkBufferingComponent {
    private final List<TickDataChunk> buffer = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>(); // Parallel to buffer
    private final Map<String, BatchFlushState> pendingBatches = new LinkedHashMap<>();
    
    public <ACK> void addChunksFromBatch(List<TickDataChunk> chunks, String batchId, 
                                          TopicMessage<?, ACK> message) {
        // Track batch for ACK
        if (!pendingBatches.containsKey(batchId)) {
            pendingBatches.put(batchId, new BatchFlushState(message, chunks.size()));
        }
        for (TickDataChunk chunk : chunks) {
            buffer.add(chunk);
            batchIds.add(batchId);
        }
    }
    
    public boolean shouldFlush() {
        if (buffer.size() >= insertBatchSize) return true;
        if (!buffer.isEmpty() && (System.currentTimeMillis() - lastFlushMs) >= flushTimeoutMs) return true;
        return false;
    }
    
    public <ACK> FlushResult<ACK> flush() {
        // Same logic as before, but returns List<TickDataChunk>
    }
}
```

**Changes in AbstractBatchIndexer:**
```java
// Change storage call:
List<TickDataChunk> chunks = storage.readChunkBatch(path);  // was: storage.readBatch(path)

// Change buffering:
components.buffering.addChunksFromBatch(chunks, batchId, msg);  // was: addTicksFromBatch

// Change template method:
protected abstract void flushChunks(List<TickDataChunk> chunks) throws Exception;  // was: flushTicks
```

**Changes in evochora.conf:**
- Update all insertBatchSize comments to reflect CHUNKS semantics
- Adjust default values for chunk-based batching

**Verification:**
```bash
./gradlew build  # Will fail until all indexers updated!
```

---

### Step 12: DummyIndexer + OrganismIndexer - flushChunks (compile + test) ✅ COMPLETED (merged into Step 11)

**Files:**
- `src/main/java/org/evochora/datapipeline/services/indexers/DummyIndexer.java`
- `src/main/java/org/evochora/datapipeline/services/indexers/OrganismIndexer.java`
- Tests

**Changes in DummyIndexer:**
```java
@Override
protected void flushChunks(List<TickDataChunk> chunks) {
    // Log-only: count total ticks across all chunks
    int totalTicks = chunks.stream().mapToInt(TickDataChunk::getTickCount).sum();
    log.debug("Flushed {} chunks ({} ticks)", chunks.size(), totalTicks);
}
```

**Changes in OrganismIndexer:**
```java
@Override
protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
    List<OrganismRow> rows = new ArrayList<>();
    
    for (TickDataChunk chunk : chunks) {
        // Extract from snapshot
        TickData snapshot = chunk.getSnapshot();
        for (OrganismState org : snapshot.getOrganismsList()) {
            rows.add(createRow(snapshot.getTickNumber(), org));
        }
        
        // Extract from deltas
        for (TickDelta delta : chunk.getDeltasList()) {
            for (OrganismState org : delta.getOrganismsList()) {
                rows.add(createRow(delta.getTickNumber(), org));
            }
        }
    }
    
    database.batchMerge(rows);
}
```

**Verification:**
```bash
./gradlew build
./gradlew test --tests "OrganismIndexerTest"
./gradlew test --tests "DummyIndexer*"
```

---

### Step 13: AnalyticsIndexer - Full Decompression (compile + test) ✅ COMPLETED

**Problem:** Step 11 introduced a temporary workaround in `AnalyticsIndexer.flushChunks()` that converts 
`TickDelta` to `TickData`. This is **semantically incorrect** because:
- `TickDelta.getChangedCells()` contains ONLY changed cells, not all cells
- Plugins calling `tick.getCellColumnsCount()` get wrong results for deltas

**Solution:** Use `DeltaCodec.decompressChunk()` to fully reconstruct environment state for each tick.
This approach:
- **Preserves plugin API unchanged** - plugins continue to receive `TickData`
- **No breaking changes** - all existing plugins work without modification
- **Correct semantics** - plugins always see complete environment state
- **TickView is NOT needed** for AnalyticsIndexer (it exists for other use cases if needed)

**Performance Impact:**
- Decompression is O(changedCells) per tick, not O(totalCells)
- At 1% change rate and 1M cells: ~10,000 operations per tick (~microseconds)
- **Negligible overhead** compared to DuckDB/Parquet processing

**Heap Impact:**
- `MutableCellState` holds one `int[]` of environment size
- 1000×1000 environment: 4 MB additional heap
- 5000×5000 environment: 100 MB additional heap
- This is acceptable and documented in `IMemoryEstimatable`

**Files:**
- `src/main/java/org/evochora/datapipeline/services/indexers/AnalyticsIndexer.java`

**Changes in AnalyticsIndexer:**
```java
@Override
protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
    if (chunks.isEmpty() || plugins.isEmpty()) {
        return;
    }

    // Calculate total cells from metadata for decompression
    int totalCells = calculateTotalCells();

    // Decompress all chunks to get fully reconstructed TickData
    List<TickData> ticks = new ArrayList<>();
    for (TickDataChunk chunk : chunks) {
        try {
            ticks.addAll(DeltaCodec.decompressChunk(chunk, totalCells));
        } catch (ChunkCorruptedException e) {
            log.warn("Skipping corrupt chunk: {}", e.getMessage());
            recordError("CORRUPT_CHUNK", e.getMessage(), 
                String.format("firstTick=%d, lastTick=%d", chunk.getFirstTick(), chunk.getLastTick()));
            continue;
        }
    }

    // Rest of processing unchanged - plugins receive complete TickData
    // ... existing plugin processing logic ...
}

private int calculateTotalCells() {
    SimulationMetadata metadata = getMetadata();
    if (metadata == null || !metadata.hasEnvironment()) {
        return 0;  // Fallback - plugins handle gracefully
    }
    int total = 1;
    for (int dim : metadata.getEnvironment().getShapeList()) {
        total *= dim;
    }
    return total;
}
```

**No changes required in:**
- `IAnalyticsPlugin` interface - remains `extractRows(TickData tick)`
- Any plugin implementations - they continue to work as before
- `EnvironmentCompositionPlugin` - receives fully reconstructed environment

**Verification:**
```bash
./gradlew build
./gradlew test --tests "AnalyticsIndexer*"
./gradlew test  # Full test suite to ensure no regressions
```

---

### Step 14: EnvironmentIndexer - RowPerChunkStrategy ✅ DONE

**Files:**
- `src/main/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexer.java`
- `src/main/java/org/evochora/datapipeline/resources/database/h2/IH2EnvStorageStrategy.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/database/h2/RowPerChunkStrategy.java` (NEW - replaces SingleBlobStrategy)
- `src/main/java/org/evochora/datapipeline/api/resources/database/IEnvironmentDataWriter.java` (modified)
- `src/main/java/org/evochora/datapipeline/api/resources/database/IEnvironmentDataReader.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/database/AbstractDatabaseResource.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/database/H2Database.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/database/H2DatabaseReader.java` (modified)
- `src/main/java/org/evochora/datapipeline/resources/database/EnvironmentDataWriterWrapper.java` (modified)

**Changes:**
- Interface changed from tick-based to chunk-based:
  - `IH2EnvStorageStrategy`: `writeChunks()`, `readChunkContaining()`
  - `IEnvironmentDataWriter`: `writeEnvironmentChunks()` 
  - `IEnvironmentDataReader`: `readChunkContaining()`, `readEnvironmentRegion()` (convenience)
- `SingleBlobStrategy` replaced by `RowPerChunkStrategy`:
  - Schema: `environment_chunks (first_tick, last_tick, chunk_blob)`
  - Stores entire TickDataChunks as BLOBs
- EnvironmentIndexer calls `writeEnvironmentChunks()` directly (no snapshot extraction)
- H2DatabaseReader implements `readChunkContaining()` + internal decompression for `readEnvironmentRegion()`

**Verification:**
```bash
./gradlew build
./gradlew test --tests "RowPerChunkStrategyTest"
./gradlew test --tests "EnvironmentIndexerTest"
./gradlew test --tests "EnvironmentDataWriterWrapperTest"
```

---

### Step 15: EnvironmentController - Decompression + Cache ✅ DONE

**Files:**
- `src/main/java/org/evochora/node/processes/http/api/visualizer/EnvironmentController.java`
- `build.gradle.kts` (Caffeine dependency added)
- `evochora.conf` (cache configuration)

**Changes:**
- Added Caffeine LRU cache for `TickDataChunk` objects:
  - Key: `runId:firstTick`
  - Configurable: `cache.maximum-size` (default: 100), `cache.expire-after-access` (default: 300s)
- Controller flow:
  1. Check cache for chunk containing requested tick
  2. On miss: Load via `reader.readChunkContaining()`
  3. Cache the chunk
  4. Decompress specific tick using `DeltaCodec.decompressTick()`
  5. Convert to `CellWithCoordinates` and return JSON
- Also caches `EnvironmentProperties` per runId

**Verification:**
```bash
./gradlew build
./gradlew test --tests "EnvironmentControllerTest"
./gradlew test --tests "EnvironmentControllerIntegrationTest"
```

---

### Step 16: CLI Commands (compile + test)

**Files:**
- `src/main/java/org/evochora/cli/commands/InspectStorageSubcommand.java`
- `src/main/java/org/evochora/cli/commands/RenderVideoCommand.java`
- `src/main/java/org/evochora/cli/rendering/IncrementalSimulationRenderer.java` (NEW)

**Changes:**
- InspectStorage: Use `DeltaCodec.decompressTick()`, add `--format=chunk-info`
- RenderVideo: Use `IncrementalSimulationRenderer` (no decompression!)

**Verification:**
```bash
./gradlew build
./gradlew test --tests "InspectStorageSubcommandTest"
./gradlew test --tests "IncrementalSimulationRendererTest"
```

---

### Step 17: IMemoryEstimatable Updates (compile + test)

**Files:**
- All `IMemoryEstimatable` implementations
- `InMemoryDeadLetterQueue` (or DLQ implementation)

**Changes:**
- Update memory calculations per Section 13
- **DLQ**: Update to estimate chunks instead of ticks (Section 13.10)

**Verification:**
```bash
./gradlew build
./gradlew test --tests "*MemoryEstimat*"
./gradlew test --tests "*DeadLetterQueue*"
```

---

### Step 18: Full Integration Test + Cleanup

**Final verification:**
```bash
./gradlew clean build
./gradlew test
```

**Integration tests:**
- Full pipeline: SimulationEngine → Queue → PersistenceService → Storage → All Indexers
- Environment API returns correct data for any tick
- Video rendering produces correct output
- Memory estimates match actual usage (within 20%)

**Dual-Mode Compatibility Verification:**
Cloud mode is not yet implemented, but we MUST ensure our changes don't break future cloud deployment:
- [ ] All services communicate ONLY through abstract resource interfaces (`IBatchStorageWrite/Read`, `IInputQueueResource`, `IDatabaseReader`, etc.)
- [ ] No direct implementation references (e.g., no `FileSystemStorageResource` in service code)
- [ ] `TickDataChunk` is a Protobuf message → serialization-transparent
- [ ] Storage operations use `StoragePath` abstraction → works with S3/cloud storage
- [ ] Queue operations use `IInputQueueResource<TickDataChunk>` → works with message bus
- [ ] Database operations use `IH2EnvStorageStrategy` abstraction → can be implemented for PostgreSQL

**Cleanup:**
- Remove deprecated `writeBatch(List<TickData>)` methods from `IBatchStorageWrite` and implementations
- Remove deprecated `readBatch()` methods from `IBatchStorageRead` and implementations
- Remove deprecated `flushTicks(List<TickData>)` methods
- Remove `captureTickData()` from SimulationEngine (replaced by ChunkBuilder)
- Update documentation

### Step 19: Documentation Update (REQUIRED)

**CRITICAL:** After implementation, all documentation must be updated to reflect delta compression changes.

**Files to update:**

1. **evochora.conf comments:**
   - `simulation-engine` section: Add delta compression settings with explanations
   - DLQ settings: Note that DLQ now holds chunks (adjust `maxSize` accordingly)
   - Any comments referencing "TickData" that should now say "TickDataChunk"
   - **CRITICAL - Batch Size Semantics:**
     - `persistence-service.maxBatchSize`: Update comment to clarify unit is now **Chunks** (not ticks), new default 5
     - `environment-indexer.insertBatchSize`: Update comment to clarify unit is now **Chunks**, new default 10
     - `organism-indexer.insertBatchSize`: Update comment, unit remains TickViews, new default 500
     - `analytics-indexer.insertBatchSize`: Update comment, unit remains TickViews, new default 500
     - All batch size comments must include heap estimation formula

2. **reference.conf (defaults):**
   - Add `simulation-engine.delta.accumulatedDeltaInterval = 5`
   - Add `simulation-engine.delta.snapshotInterval = 20`
   - Add `simulation-engine.delta.chunkInterval = 1`
   - Add `simulation-engine.delta.estimatedDeltaRatio = 0.01`
   - Update `persistence-service.maxBatchSize = 5`
   - Update `environment-indexer.insertBatchSize = 10`
   - Update `organism-indexer.insertBatchSize = 500`
   - Update `analytics-indexer.insertBatchSize = 500`

3. **CLI help texts:**
   - `inspect storage` command: Document new `--format=chunk-info` option
   - `video` command: Note incremental rendering behavior

4. **API documentation (if any):**
   - Environment API: Note that data is reconstructed from compressed chunks

**Verification checklist:**
```bash
# Search for outdated references
grep -r "TickData" evochora.conf     # Should find updated comments
grep -r "per tick" evochora.conf     # Review if "per chunk" is more accurate
```

**Example evochora.conf updates:**

```hocon
# === SimulationEngine Delta Compression ===
simulation-engine {
  samplingInterval = 100
  
  delta {
    accumulatedDeltaInterval = 5    # Checkpoint every 5 samples (fast API)
    snapshotInterval = 20           # Full snapshot every 20 accumulated (100 ticks/chunk)
    chunkInterval = 1
    estimatedDeltaRatio = 0.01
  }
}

# === PersistenceService (BREAKING: unit changed!) ===
persistence-service {
  # Number of CHUNKS (not ticks!) to batch before writing to storage.
  # With 100 ticks/chunk: maxBatchSize=5 → 500 ticks per storage write.
  # Heap: maxBatchSize × ~25MB/chunk
  # Recommended: 5 (default), 10 for high-throughput with 16GB+ heap
  maxBatchSize = 5
}

# === EnvironmentIndexer (BREAKING: unit changed!) ===
environment-indexer {
  # Number of CHUNKS per DB transaction.
  # With RowPerChunkStrategy: 1 chunk = 1 DB row (BLOB).
  # Heap: insertBatchSize × ~25MB/chunk (during processing)
  # Recommended: 5 (default), 10 for high-throughput with 16GB+ heap
  insertBatchSize = 5
}

# === OrganismIndexer (BREAKING: unit changed!) ===
organism-indexer {
  # Number of CHUNKS per DB batch.
  # Each chunk contains ~100 ticks, organisms extracted from all ticks.
  # Heap: insertBatchSize × ~25MB/chunk
  # Recommended: 5 (default)
  insertBatchSize = 5
}

# === AnalyticsIndexer (BREAKING: unit changed!) ===
analytics-indexer {
  # Number of CHUNKS per plugin processing batch.
  # Each chunk contains ~100 ticks, processed via TickView internally.
  # Heap: insertBatchSize × ~25MB/chunk
  # Recommended: 5 (default)
  insertBatchSize = 5
}
```

### 7.1. Batch Size Semantics (BREAKING CHANGE)

**CRITICAL:** The unit for batch sizes changes from "ticks" to "chunks" for ALL batch-processing services!

**Rationale for uniform CHUNKS semantics:**
1. Chunks are the natural transport and storage unit (from PersistenceService → Storage → Indexers)
2. Chunks are indivisible (a chunk always starts with a snapshot)
3. Timeout-based flush works cleanly: flush all buffered chunks, no rounding needed
4. Simpler code: no different semantics per indexer

#### PersistenceService.maxBatchSize

**Semantik:** Number of **Chunks** from queue per storage write

```
maxBatchSize = 5  →  5 Chunks × 100 Ticks = 500 Ticks per storage file
Heap: 5 × 25MB = ~125MB
```

**New Default:** `5` (reduced from 20)

#### EnvironmentIndexer.insertBatchSize

**Semantik:** Number of **Chunks** per DB transaction

With `RowPerChunkStrategy`: 1 Chunk = 1 DB-Row (BLOB)

```
insertBatchSize = 5  →  5 Chunks = 5 DB-Inserts per Transaction
Heap: 5 × 25MB = ~125MB (during processing)
```

**New Default:** `5` (reduced from 20)

#### OrganismIndexer.insertBatchSize

**Semantik:** Number of **Chunks** per DB batch

Organisms are extracted from all ticks within the chunks.

```
insertBatchSize = 5  →  5 Chunks × 100 Ticks = 500 Organism snapshots
Heap: 5 × 25MB = ~125MB (chunks in buffer)
```

**New Default:** `5` (reduced from 100)

#### AnalyticsIndexer.insertBatchSize

**Semantik:** Number of **Chunks** per plugin processing batch

Chunks are converted to TickViews internally for plugin API.

```
insertBatchSize = 5  →  5 Chunks × 100 Ticks = 500 TickViews processed
Heap: 5 × 25MB = ~125MB (chunks in buffer)
```

**New Default:** `5` (reduced from 20)

#### Summary Table

| Service | Unit | Old Default | New Default | Heap |
|---------|------|-------------|-------------|------|
| PersistenceService | **Chunks** | 20 | **5** | ~125MB |
| EnvironmentIndexer | **Chunks** | 20 | **5** | ~125MB |
| OrganismIndexer | **Chunks** | 100 | **5** | ~125MB |
| AnalyticsIndexer | **Chunks** | 20 | **5** | ~125MB |

**Note:** All indexers now have consistent semantics and similar heap requirements.

---

## 8. Testing Strategy

### 8.1. Unit Tests (per step above)

Each step includes specific unit tests. Key test classes:
- `DeltaCodecDecompressionTest`
- `DeltaCodecCompressionTest`
- `DeltaCodecRoundTripTest` (**most critical**)
- `EnvironmentChangeTrackingTest`
- `SimulationEngineChunkBuildingTest`
- `RowPerChunkStrategyTest`
- `IncrementalSimulationRendererTest`

### 8.2. Integration Tests

- `SimulationEngineToPersistenceIntegrationTest` (Step 10)
- `FullPipelineIntegrationTest` (Step 18)
- `EnvironmentControllerIntegrationTest` (Step 15)

### 8.3. Round-Trip Tests (**CRITICAL**)

The most important tests verify data integrity through compression/decompression:

```java
@Test
void roundTripPreservesAllData() {
    // Setup
    Environment env = createTestEnvironment();
    List<Organism> organisms = createTestOrganisms();
    
    // Simulate N ticks, capturing changes
    for (int i = 0; i < ticksPerChunk; i++) {
        simulateTick(env, organisms);
    }
    
    // Compress
    TickDataChunk chunk = simulationEngine.buildChunk();
    
    // Decompress
    List<TickData> decompressed = deltaCodec.decompressChunk(chunk);
    
    // Verify each tick
    for (int i = 0; i < ticksPerChunk; i++) {
        assertEnvironmentEquals(expectedStates[i], decompressed.get(i));
        assertOrganismsEquals(expectedOrganisms[i], decompressed.get(i));
    }
}
```

### 8.4. Performance Tests

- Compression ratio vs configuration
- Decompression speed for EnvironmentController
- Incremental rendering speed vs full rendering
- Memory usage matches estimates

---

## 9. Related Documents

- `DELTA_ABSTRACTION.md`: Low-level state projection implementation details
- `CLI_USAGE.md`: Command-line interface documentation
- `ASSEMBLY_SPEC.md`: Assembly language specification

---

## 10. Open Questions

**All questions resolved.** See Decisions Made table below.

## 11. Decisions Made

| Question | Decision | Rationale |
|----------|----------|-----------|
| Where to decompress for Analytics? | No full decompression; extract OrganismStates directly | Most plugins don't need environment data |
| EnvironmentCompositionPlugin? | Snapshot-only mode (Option 3) | Simplest solution, plugin unchanged, API ready for Option 2 |
| Database storage format? | Store compressed (deltas) | Maximizes storage savings |
| Decompression location? | DeltaCodec utility class | Single source of truth, reusable by all consumers |
| EnvironmentIndexer strategy? | Row-per-Chunk (RowPerChunkStrategy) | Better write scalability at 15M+ ticks, smaller index |
| Strategy interface? | Chunk-based (`writeChunk`, `readTick`) | Fully encapsulated, allows future Row-per-Tick alternative |
| Environment Controller decompression? | Server-side (in strategy) | 10-100× less network transfer than frontend decompression |
| Environment Controller caching? | LRU cache (`maximumSize=5`), no TTL | Improves scrubbing (5ms vs 40ms); size limit prevents unbounded growth |
| RenderVideoCommand decompression? | **None - incremental rendering** | ~100× faster for large environments |
| Partial chunk on shutdown? | Flush partial chunk | Consistent with existing drain behavior; no data loss |
| Frontend decompression? | **No** - Server-side only | Keeps frontend simple; 10-100× less network transfer |
| Streaming decompression? | **No** - Build full state in memory | Not needed; environments fit in heap with current limits |
| Cross-chunk seeking cache? | LRU cache (`maximumSize=5`) | Handles scrubbing; size limit prevents unbounded growth |
| Error handling in DeltaCodec? | Checked `ChunkCorruptedException` | Services catch, log.warn, recordError, continue (never abort) |
| TickView type safety? | Runtime-safe accessors (`allCells()`, `changedCells()`) | Throws if called on wrong tick type |
| Batch size semantics? | Chunks for PersistenceService/EnvironmentIndexer, TickViews for others | See Section 7.1 |
| Compression metrics? | O(1) AtomicLong counters in SimulationEngine | Helps tune configuration |
| Chunk checksum? | **No** - Rely on Zstd + Protobuf + storage layer | Existing integrity checks sufficient |
| LRU cache location? | In `RowPerChunkStrategy` (not EnvironmentController) | Keeps controller thin |

## 12. Remaining Components

### 12.1. OrganismIndexer

**Current implementation (simplified):**
```java
protected void flushTicks(List<TickData> ticks) throws Exception {
    database.writeOrganismStates(ticks);
    int totalOrganisms = ticks.stream()
        .mapToInt(TickData::getOrganismsCount)
        .sum();
}
```

**Changes required:**
- Signature: `flushTickViews(List<TickView> tickViews)`
- OrganismStates are always complete in both snapshots and deltas
- Extract from `tickView.organisms()` instead of `tick.getOrganismsList()`

**Updated implementation:**
```java
protected void flushTickViews(List<TickView> tickViews) throws Exception {
    database.writeOrganismStatesFromViews(tickViews);
    int totalOrganisms = tickViews.stream()
        .mapToInt(tv -> tv.organisms().size())
        .sum();
}
```

**IResourceSchemaAwareOrganismDataWriter change:**
```java
// Before
void writeOrganismStates(List<TickData> ticks);

// After
void writeOrganismStatesFromViews(List<TickView> tickViews);
```

### 12.2. CLI Commands

**InspectStorageSubcommand:**

Current flow:
1. `storage.readBatch(path)` → `List<TickData>`
2. `ticks.stream().filter(t -> t.getTickNumber() == targetTick).findFirst()`
3. `outputTickData(targetTick, format)`

New flow:
1. `storage.readBatch(path)` → `List<TickDataChunk>`
2. Find chunk containing target tick
3. `DeltaCodec.decompressTick(chunk, targetTick)` → `TickData`
4. `outputTickData(targetTick, format)`

**New features to add:**
- `--format=chunk-info` - Show chunk metadata without decompression:
  - First/last tick numbers
  - Tick count in chunk
  - Delta type distribution (snapshot, incremental, accumulated)
  - Compression ratio (chunk size vs estimated uncompressed size)
- `--list-chunks` - List all chunks for a run with their tick ranges

**Example output for chunk-info:**
```
=== Chunk Info ===
File: batch_0_4900.pb.zstd
Tick Range: 0 - 4900 (50 ticks)
Snapshot: tick 0
Accumulated Deltas: ticks 1000, 2000, 3000, 4000 (4 deltas)
Incremental Deltas: 45 deltas
Chunk Size: 2.3 MB (compressed)
Estimated Uncompressed: 21 MB
Compression Ratio: 9.1x
```

**RenderVideoCommand:**

**Key insight: No decompression needed!**

The video command processes ticks sequentially. Instead of decompressing to full `TickData` objects, we can render deltas directly using an **incremental renderer**:

**Current renderer problem:**
```java
public int[] render(TickData tick) {
    Arrays.fill(frameBuffer, colorEmptyBg);  // Clears previous state!
    renderAllCells(tick.getCellColumns());   // O(n) - renders ALL cells
    renderOrganisms(tick.getOrganismsList());
}
```

**New IncrementalSimulationRenderer:**
```java
public class IncrementalSimulationRenderer {
    private final int[] frameBuffer;
    private final int[] cellColorBuffer;  // Remembers cell colors under organisms
    private List<OrganismState> previousOrganisms = List.of();
    
    // Snapshot: Clear + render all cells
    public int[] renderSnapshot(CellDataColumns cells, List<OrganismState> organisms) {
        Arrays.fill(frameBuffer, colorEmptyBg);
        Arrays.fill(cellColorBuffer, colorEmptyBg);
        renderCells(cells);                    // O(n)
        saveCellColorsUnder(organisms);
        renderOrganisms(organisms);
        previousOrganisms = organisms;
        return frameBuffer;
    }
    
    // Delta: Only render changes! O(Δ) instead of O(n)
    public int[] renderDelta(CellDataColumns changedCells, List<OrganismState> organisms) {
        // 1. Restore cell colors where previous organisms were
        restoreCellsUnder(previousOrganisms);  // O(prevOrganisms)
        
        // 2. Render only changed cells
        renderCells(changedCells);             // O(Δ) ← THE BIG WIN!
        updateCellColorBuffer(changedCells);
        
        // 3. Save colors under new organisms & render them
        saveCellColorsUnder(organisms);
        renderOrganisms(organisms);
        previousOrganisms = organisms;
        return frameBuffer;
    }
}
```

**RenderVideoCommand new flow:**
```java
List<TickDataChunk> chunks = storage.readBatch(path);
IncrementalSimulationRenderer renderer = new IncrementalSimulationRenderer(envProps, cellSize);

for (TickDataChunk chunk : chunks) {
    // Snapshot
    TickData snapshot = chunk.getSnapshot();
    byte[] frame = renderer.renderSnapshot(
        snapshot.getCellColumns(), 
        snapshot.getOrganismsList()
    );
    writeFrame(frame);
    
    // Deltas - NO DECOMPRESSION!
    for (TickDelta delta : chunk.getDeltasList()) {
        frame = renderer.renderDelta(
            delta.getChangedCells(),    // Only changes, not full state
            delta.getOrganismsList()    // Always complete
        );
        writeFrame(frame);
    }
}
```

**Performance comparison (1000×1000 environment, ~1% changes/tick):**

| Approach | Cell rendering | Memory | Speed |
|----------|---------------|--------|-------|
| Full decompression | O(1M) per tick | O(1M × ticks) | Baseline |
| Streaming decompression | O(1M) per tick | O(1M) | Same speed, less memory |
| **Incremental rendering** | **O(10K) per tick** | **O(1M)** | **~100× faster** |

**Implementation notes:**
- `cellColorBuffer` is needed because organisms overlay cells. When an organism moves, we need to restore the cell color at its old position.
- `previousOrganisms` tracks organism positions from the last frame to know what to restore.
- Organisms are always complete in deltas, so no state tracking needed for them.

### 12.3. Storage Read API Change

```java
// IBatchStorageRead - Before
List<TickData> readBatch(StoragePath path);

// IBatchStorageRead - After
List<TickDataChunk> readBatch(StoragePath path);
```

All consumers must be updated:
- `AnalyticsIndexer` → uses `DeltaCodec.createTickViews()`
- `EnvironmentIndexer` → passes chunks to strategy
- `OrganismIndexer` → uses `TickView.organisms()`
- `InspectStorageSubcommand` → uses `DeltaCodec.decompressTick()`
- `RenderVideoCommand` → uses `DeltaCodec.decompressChunk()`

### 12.4. Migration Strategy

Since the storage format changes fundamentally:

1. **Backwards compatibility**: Not supported. Old data must be re-indexed.
2. **Version marker**: Add `format_version` to storage metadata to detect old format.
3. **Clear error message**: If old format detected, instruct user to re-run simulation or use older CLI version.

## 13. IMemoryEstimatable Changes

The `IMemoryEstimatable` interface and `SimulationParameters` need updates for delta compression.

**CRITICAL: Runtime calculation from actual configuration!**

The memory estimation MUST use the actual runtime configuration values, NOT hardcoded defaults. The `SimulationParameters` record is created by `ServiceManager` at startup by reading the actual HOCON configuration.

### 13.1. ServiceManager: Creating SimulationParameters from Config

```java
// In ServiceManager.start() or similar initialization
private SimulationParameters createSimulationParameters(Config pipelineConfig) {
    // Read environment config
    Config envConfig = pipelineConfig.getConfig("simulation-engine.environment");
    int[] shape = envConfig.getIntList("shape").stream().mapToInt(Integer::intValue).toArray();
    int totalCells = Arrays.stream(shape).reduce(1, (a, b) -> a * b);
    int maxOrganisms = pipelineConfig.getInt("simulation-engine.maxOrganisms");
    
    // Read delta compression config (NEW)
    Config deltaConfig = pipelineConfig.getConfig("simulation-engine.delta");
    int samplingInterval = pipelineConfig.getInt("simulation-engine.samplingInterval");
    int accumulatedDeltaInterval = deltaConfig.getInt("accumulatedDeltaInterval");
    int snapshotInterval = deltaConfig.getInt("snapshotInterval");
    int chunkInterval = deltaConfig.getInt("chunkInterval");
    double avgDeltaRatio = deltaConfig.getDouble("estimatedDeltaRatio"); // User-provided estimate
    
    return new SimulationParameters(
        shape,
        totalCells,
        maxOrganisms,
        samplingInterval,
        accumulatedDeltaInterval,
        snapshotInterval,
        chunkInterval,
        avgDeltaRatio
    );
}

// Then pass to all IMemoryEstimatable components:
for (IResource resource : resources.values()) {
    if (resource instanceof IMemoryEstimatable estimatable) {
        List<MemoryEstimate> estimates = estimatable.estimateWorstCaseMemory(simulationParams);
        // ... aggregate estimates
    }
}
```

### 13.2. Example Configuration (HOCON)

```hocon
simulation-engine {
  samplingInterval = 100        # Existing: sample every 100th tick
  maxOrganisms = 10000
  
  environment {
    shape = [1000, 1000]        # 1M cells
  }
  
  # NEW: Delta compression settings
  delta {
    accumulatedDeltaInterval = 10   # Every 10th sampled tick
    snapshotInterval = 5            # Every 5th accumulated delta
    chunkInterval = 1               # Every snapshot starts new chunk
    estimatedDeltaRatio = 0.01      # Estimated 1% cells change per tick
  }
}
```

The `estimatedDeltaRatio` is a user-provided estimate based on expected simulation dynamics. It can be tuned after observing actual simulation behavior.

### 13.3. SimulationParameters Record

The memory estimation must account for the actual compression configuration:

```java
public record SimulationParameters(
    int[] environmentShape,
    int totalCells,
    int maxOrganisms,
    
    // NEW: Delta compression configuration (from simulation-engine config)
    int samplingInterval,           // e.g., 100 (existing)
    int accumulatedDeltaInterval,   // e.g., 10 (every 10th sampled tick)
    int snapshotInterval,           // e.g., 5 (every 5th accumulated delta)
    int chunkInterval,              // e.g., 1 (every snapshot starts chunk)
    double avgDeltaRatio            // e.g., 0.01 (1% of cells change per tick)
) {
    // Existing constants
    public static final int BYTES_PER_CELL = 56;
    public static final int BYTES_PER_ORGANISM = 800;
    public static final int TICKDATA_WRAPPER_OVERHEAD = 500;
    
    // NEW: Delta-specific constants
    public static final int TICKDELTA_WRAPPER_OVERHEAD = 200;
    public static final int RNG_STATE_BYTES = 2500;  // Only in accumulated deltas
    
    // ==================== Derived Values ====================
    
    /**
     * Number of sampled ticks per chunk.
     * Example: accumulatedDeltaInterval=10, snapshotInterval=5, chunkInterval=1
     *          → ticksPerChunk = 10 * 5 * 1 = 50
     */
    public int ticksPerChunk() {
        return accumulatedDeltaInterval * snapshotInterval * chunkInterval;
    }
    
    /**
     * Number of snapshots per chunk.
     * Example: chunkInterval=1 → 1 snapshot per chunk
     *          chunkInterval=2 → 2 snapshots per chunk
     */
    public int snapshotsPerChunk() {
        return chunkInterval;
    }
    
    /**
     * Number of accumulated deltas per chunk (excluding snapshots).
     * Example: snapshotInterval=5, chunkInterval=1 → 4 accumulated deltas
     */
    public int accumulatedDeltasPerChunk() {
        return (snapshotInterval - 1) * chunkInterval;
    }
    
    /**
     * Number of incremental deltas per chunk.
     * = total ticks - snapshots - accumulated deltas
     */
    public int incrementalDeltasPerChunk() {
        return ticksPerChunk() - snapshotsPerChunk() - accumulatedDeltasPerChunk();
    }
    
    // ==================== Size Estimations ====================
    
    /**
     * Bytes for a full snapshot (all cells + all organisms + RNG/strategies).
     */
    public long estimateBytesPerSnapshot() {
        return estimateEnvironmentBytesPerTick() 
             + estimateOrganismBytesPerTick() 
             + TICKDATA_WRAPPER_OVERHEAD;
    }
    
    /**
     * Bytes for an accumulated delta (delta cells + all organisms + RNG/strategies).
     * Accumulated deltas are larger than incremental because they include RNG state.
     */
    public long estimateBytesPerAccumulatedDelta() {
        // Accumulated delta: changes since last snapshot (could be up to snapshotInterval × avgDeltaRatio)
        // Worst case: all incremental changes accumulated
        double accumulatedRatio = Math.min(1.0, avgDeltaRatio * accumulatedDeltaInterval * (snapshotInterval - 1));
        long cellBytes = (long) (totalCells * accumulatedRatio * BYTES_PER_CELL);
        return cellBytes 
             + estimateOrganismBytesPerTick()  // Organisms always complete
             + RNG_STATE_BYTES                 // RNG + strategy states
             + TICKDELTA_WRAPPER_OVERHEAD;
    }
    
    /**
     * Bytes for an incremental delta (delta cells + all organisms, NO RNG).
     */
    public long estimateBytesPerIncrementalDelta() {
        long cellBytes = (long) (totalCells * avgDeltaRatio * BYTES_PER_CELL);
        return cellBytes 
             + estimateOrganismBytesPerTick()  // Organisms always complete
             + TICKDELTA_WRAPPER_OVERHEAD;     // No RNG state
    }
    
    /**
     * Total bytes for one complete chunk.
     */
    public long estimateBytesPerChunk() {
        return snapshotsPerChunk() * estimateBytesPerSnapshot()
             + accumulatedDeltasPerChunk() * estimateBytesPerAccumulatedDelta()
             + incrementalDeltasPerChunk() * estimateBytesPerIncrementalDelta();
    }
    
    /**
     * Average bytes per tick within a chunk.
     * Useful for comparing with pre-delta-compression estimates.
     */
    public long estimateAvgBytesPerTickInChunk() {
        return estimateBytesPerChunk() / ticksPerChunk();
    }
    
    /**
     * Compression ratio compared to full snapshots.
     */
    public double estimateCompressionRatio() {
        long uncompressedBytes = ticksPerChunk() * estimateBytesPerSnapshot();
        return (double) uncompressedBytes / estimateBytesPerChunk();
    }
}
```

### 13.4. Component Memory Changes

| Component | Before | After | Change |
|-----------|--------|-------|--------|
| **SimulationEngine** | Environment + Organisms | + BitSets + ChunkBuffer | **+20%** |
| **PersistenceService** | n × TickData | n × Chunk | **-80%** |
| **AnalyticsIndexer** | n × TickData | n × TickView | **-70%** |
| **EnvironmentIndexer** | n × Environment | n × CompressedChunk | **-85%** |
| **EnvironmentController** | (none) | LRU Cache | **NEW: 250MB** |
| **IncrementalRenderer** | (none) | cellColorBuffer | **NEW: 4MB** |

### 13.5. SimulationEngine Memory Update

```java
@Override
public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
    List<MemoryEstimate> estimates = new ArrayList<>();
    
    // Existing: Environment cells array
    long environmentBytes = (long) params.totalCells() * 8;
    estimates.add(new MemoryEstimate(serviceName + " (Environment)", environmentBytes, ...));
    
    // Existing: Organisms in memory
    long organismsBytes = (long) params.maxOrganisms() * 2 * 1024;
    estimates.add(new MemoryEstimate(serviceName + " (Organisms)", organismsBytes, ...));
    
    // NEW: BitSets for change tracking
    long bitSetBytes = (params.totalCells() / 8) * 2;  // changedSince + accumulated
    estimates.add(new MemoryEstimate(
        serviceName + " (Change Tracking BitSets)",
        bitSetBytes,
        String.format("2 × BitSet(%d cells) = %s", params.totalCells(), formatBytes(bitSetBytes)),
        MemoryEstimate.Category.SERVICE_BATCH
    ));
    
    // NEW: Chunk buffer - size depends on configuration!
    // Chunk contains: snapshots + accumulated deltas + incremental deltas
    long chunkBufferBytes = params.estimateBytesPerChunk();
    estimates.add(new MemoryEstimate(
        serviceName + " (Chunk Buffer)",
        chunkBufferBytes,
        String.format("%d ticks/chunk (%d snapshots + %d accumulated + %d incremental), compression: %.1f×",
            params.ticksPerChunk(),
            params.snapshotsPerChunk(),
            params.accumulatedDeltasPerChunk(),
            params.incrementalDeltasPerChunk(),
            params.estimateCompressionRatio()),
        MemoryEstimate.Category.SERVICE_BATCH
    ));
    
    return estimates;
}
```

### 13.6. PersistenceService Memory Update

```java
@Override
public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
    // CHANGED: Chunks instead of TickData
    long bytesPerChunk = params.estimateBytesPerChunk();
    long totalBytes = (long) maxBatchSize * bytesPerChunk;
    
    String explanation = String.format(
        "%d maxBatchSize × %s/chunk (~%d%% of full ticks due to delta compression)",
        maxBatchSize,
        SimulationParameters.formatBytes(bytesPerChunk),
        (int) (100.0 * bytesPerChunk / (params.ticksPerChunk() * params.estimateBytesPerTick()))
    );
    
    return List.of(new MemoryEstimate(serviceName, totalBytes, explanation, ...));
}
```

### 13.7. EnvironmentController Cache Memory (NEW)

```java
// In EnvironmentController or RowPerChunkStrategy
@Override
public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
    // LRU cache for reconstructed chunks
    int cacheSize = 5;  // From config: environment.cache.maxChunks
    
    // Reconstructed chunk = full environment state for all ticks in chunk
    // Worst case: each tick has full environment (100% occupancy)
    long bytesPerReconstructedChunk = (long) params.ticksPerChunk() * params.estimateBytesPerTick();
    long totalCacheBytes = (long) cacheSize * bytesPerReconstructedChunk;
    
    return List.of(new MemoryEstimate(
        "EnvironmentController (LRU Cache)",
        totalCacheBytes,
        String.format("%d cached chunks × %d ticks × %s/tick",
            cacheSize, params.ticksPerChunk(), formatBytes(params.estimateBytesPerTick())),
        MemoryEstimate.Category.CACHE
    ));
}
```

### 13.8. IncrementalRenderer Memory (NEW - CLI only)

```java
// In RenderVideoCommand or IncrementalSimulationRenderer
public long estimateMemory(SimulationParameters params) {
    // frameBuffer: width × height × 4 bytes (BGRA)
    long frameBufferBytes = (long) params.totalCells() * cellSize * cellSize * 4;
    
    // cellColorBuffer: one int per cell to remember colors under organisms
    long cellColorBufferBytes = (long) params.totalCells() * 4;
    
    // previousOrganisms: list of OrganismState references
    long prevOrganismsBytes = (long) params.maxOrganisms() * 8;  // References only
    
    return frameBufferBytes + cellColorBufferBytes + prevOrganismsBytes;
}
```

### 13.10. DLQ Memory Update

The Dead Letter Queue now holds `TickDataChunk` instead of `TickData`. Its `IMemoryEstimatable` implementation must be updated:

```java
// In InMemoryDeadLetterQueue or similar
@Override
public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
    // CHANGED: Chunks instead of individual ticks
    long bytesPerChunk = params.estimateBytesPerChunk();
    long totalBytes = (long) maxSize * bytesPerChunk;
    
    String explanation = String.format(
        "%d maxSize × %s/chunk (%d ticks/chunk)",
        maxSize,
        SimulationParameters.formatBytes(bytesPerChunk),
        params.ticksPerChunk()
    );
    
    return List.of(new MemoryEstimate(
        resourceName + " (DLQ)",
        totalBytes,
        explanation,
        MemoryEstimate.Category.RESOURCE_BUFFER
    ));
}
```

**Configuration guidance:** If `dlq.maxSize = 100` was configured for 100 individual ticks before, and chunks now contain 50 ticks each, the user could reduce to `dlq.maxSize = 2` to hold a similar number of ticks (100 ticks) while using similar memory.

---

### 13.11. Summary: Memory Impact

**Configuration matters significantly!**

Example environment: 1000×1000 (1M cells), 10K organisms, 1% delta ratio

**Configuration A: Frequent snapshots (snapshotInterval=5)**
- samplingInterval=100, accumulatedDeltaInterval=10, snapshotInterval=5, chunkInterval=1
- ticksPerChunk = 10 × 5 × 1 = 50
- Snapshots: 1, Accumulated: 4, Incremental: 45
- Chunk size: ~48MB (vs 2.1GB uncompressed) → **44× compression**

**Configuration B: Rare snapshots (snapshotInterval=100)**
- samplingInterval=100, accumulatedDeltaInterval=10, snapshotInterval=100, chunkInterval=1
- ticksPerChunk = 10 × 100 × 1 = 1000
- Snapshots: 1, Accumulated: 99, Incremental: 900
- Chunk size: ~85MB (vs 42GB uncompressed) → **494× compression**

| Component | Before | Config A | Config B |
|-----------|--------|----------|----------|
| SimulationEngine | 12 MB | 14 MB | 15 MB |
| PersistenceService (10 chunks) | 420 MB | 480 MB | 850 MB |
| AnalyticsIndexer (100 ticks) | 4.2 GB | 960 MB | 170 MB |
| EnvironmentIndexer (100 ticks) | 2.8 GB | 640 MB | 113 MB |
| EnvironmentController Cache (5 chunks) | 0 | 250 MB | 450 MB |
| **Total Pipeline** | **~7.5 GB** | **~2.3 GB** | **~1.6 GB** |
| **Compression Ratio** | 1× | **44×** | **494×** |

**Key insight:** Rarer snapshots = better compression but larger chunks to cache.

**Trade-offs by configuration:**

| Setting | Effect on Memory | Effect on Features |
|---------|------------------|-------------------|
| Higher `snapshotInterval` | Smaller chunks, better compression | Slower random access (more deltas to apply) |
| Higher `accumulatedDeltaInterval` | Fewer accumulated deltas (smaller) | Longer checkpoint intervals |
| Higher `chunkInterval` | Larger chunks | Fewer storage files |
| Lower `avgDeltaRatio` | Much smaller deltas | N/A (depends on simulation dynamics) |

**Recommendation:** Start with Configuration A (moderate compression, faster access), then tune based on actual simulation behavior.
