# Data Pipeline V3 – Organism Indexer (Phase 16.1)

## Overview

This document specifies the **OrganismIndexer** for Data Pipeline V3.
The OrganismIndexer reads `TickData` messages from storage, extracts **organism state**
(`OrganismState` protobuf messages), and writes them into a **schema-per-run** H2 database
using a **row-per-organism-per-tick** model.

This phase covers the **write path only**:

- How organism data is **extracted** from `TickData`.
- How it is **normalized** into `organisms` (static) and `organism_states` (per-tick).
- How the indexer integrates with the **indexer foundation** (storage, topic, metadata).
- How **idempotency**, **error handling**, and **performance** are achieved.

Later phases will define:

- HTTP API endpoints for querying indexed organism data.
- Integration with the web visualizer (grid overlay, dropdowns, sidebars).

Those aspects are explicitly **out of scope** for Phase 16.1.

---

## Goal

Implement an OrganismIndexer that:

- Reads `TickData` batches from storage via the existing batch topic (competing consumer pattern).
- Extracts `OrganismState` messages for **all living organisms** in each tick.
- Separates **static** organism attributes from **per-tick** state:
  - Static attributes are written once into table `organisms`.
  - Per-tick state is written into table `organism_states`.
- Stores **all fields** from `OrganismState`:
  - Fields required for **grid** and **dropdown** views are exposed as **dedicated columns**.
  - All remaining fields are stored in **one column per Protobuf field**, using binary encoding where needed.
- Ensures **no redundancy** between `organisms` and `organism_states`:
  - `parent_id`, `birth_tick`, `program_id`, `initial_position` only exist in `organisms`, never duplicated.
- Uses `MERGE` on `(tick_number, organism_id)` to guarantee **idempotent writes** even with topic redeliveries.
- Reuses the **indexer foundation** (run discovery, metadata component, buffering component) so that OrganismIndexer looks and behaves like EnvironmentIndexer and MetadataIndexer.

---

## Success Criteria

Upon completion of Phase 16.1:

1. **Service Implementation**
   1. `OrganismIndexer` extends `AbstractBatchIndexer<ACK>` and uses:
      - `MetadataReadingComponent` (for `SimulationMetadata`).
      - `TickBufferingComponent` (for efficient batch writes).
   2. OrganismIndexer integrates with:
      - `IBatchStorageRead` for `TickData` batches.
      - `ITopicReader` for batch notifications.
      - `IMetadataReader` for run-level metadata.
      - `IOrganismDataWriter` for database writes.

2. **Database Schema**
   1. Schema-per-run design is followed:
      - Each simulation run maps to its own H2 schema (already defined in Phase 13).
      - No `runId` column is added to organism tables.
   2. Tables are created idempotently:
      - `organisms` – static organism metadata.
      - `organism_states` – per-tick dynamic organism state.
   3. Static fields (`parent_id`, `birth_tick`, `program_id`, `initial_position`) are **only** stored in `organisms`.

3. **Data Coverage**
   1. All fields of `OrganismState` are stored:
      - Grid/dropdown fields:
        - `ip`, `dv`, `data_pointers`, `active_dp_index`, `energy`.
      - Detailed fields:
        - Registers: `data_registers`, `procedure_registers`, `formal_param_registers`, `location_registers`.
        - Stacks: `data_stack`, `location_stack`, `call_stack`.
        - Failure status: `instruction_failed`, `failure_reason`, `failure_call_stack`.
   2. There is exactly **one column per Protobuf field**, i.e. no aggregate "full_state_blob".

4. **Life/Death Semantics**
   1. For each `organism_id`:
      - It exists if there is a row in `organisms`.
      - It is **not yet born** at tick `T` if `T < birth_tick`.
      - It is **alive** at tick `T` if `organism_states` contains `(tick_number = T, organism_id)`.
      - It is **dead or gone** at tick `T` if `T ≥ birth_tick` and no row exists in `organism_states` for this tick.
   2. In the **tick of death**, the organism has one last row in `organism_states`. From the next tick onward it has no rows.
   3. No `is_dead` flag is stored; life/death is derived from presence/absence of a per-tick row.

5. **Idempotency**
   1. Writes to `organism_states` use:
      ```sql
      MERGE INTO organism_states (...columns...) KEY (tick_number, organism_id) VALUES (...);
      ```
   2. Writes to `organisms` use:
      ```sql
      MERGE INTO organisms (organism_id, ...) KEY (organism_id) VALUES (...);
      ```
   3. Reprocessing the same batch (e.g., after crash) yields the same final state (no duplicates, no loss).

6. **Error Handling & Recovery**
   1. Transient errors (e.g., `SQLException` from DB, `IOException` from storage) cause:
      - WARN log + `recordError()` on the resource.
      - No ACK → batch is redelivered by the topic after `claimTimeout`.
   2. Idempotent DB writes guarantee correct result after redelivery.
   3. Interrupted shutdown performs final flush where possible (reusing AbstractBatchIndexer semantics).

7. **Non-Functional Requirements**
   1. The design of OrganismIndexer and the database schema supports **theoretically unbounded**
      numbers of organisms per tick and ticks per run. The only practical limit is the available
      storage capacity:
      - In **in-process mode**, this is primarily the local filesystem size.
      - In **cloud/container mode**, this is the configured cloud storage capacity.
   2. Grid/dropdown queries in later phases must be able to get all required fields without parsing binary columns.
   3. All metric recording is **O(1)**; no unbounded memory growth in metrics or error tracking.

8. **Testing**
   1. Unit tests validate:
      - Schema creation.
      - Mapping logic from `OrganismState` to database rows.
      - Life/death semantics.
   2. Integration tests validate:
      - End-to-end pipeline: Storage → OrganismIndexer → H2 per-run schema.
      - Idempotent reprocessing after simulated failures.
      - Competing consumer behavior (multiple indexer instances).

---

## Prerequisites

Phase 16.1 builds on the following completed work:

1. **Indexing Foundation (Phase 14.2)**
   - `AbstractIndexer` and `AbstractBatchIndexer` implemented.
   - Run discovery modes:
     - Configured `runId`.
     - Timestamp-based discovery via `IBatchStorageRead.listRunIds(afterTimestamp)`.
   - Batch processing patterns:
     - Topic polling.
     - Tick buffering.
     - Batched database writes.

2. **Environment Indexer (Phase 14.3)**
   - `EnvironmentIndexer` implemented with `SingleBlobStrategy`.
   - Database integration via `IEnvironmentDataWriter`.
   - Pattern for:
     - Schema-per-run.
     - MERGE-based idempotent writes.
     - Competing consumers.

3. **Metadata Indexer (Phase 13)**
   - `IMetadataDatabase` and metadata indexing implemented.
   - Schema-per-run design established:
     - Schemas named `sim_<timestamp>_<uuid>`.
   - No `runId` column in tables; schema captures run.

4. **Runtime & Protobuf Contracts**
   - `Organism` runtime model implemented (`src/main/java/org/evochora/runtime/model/Organism.java`).
   - `OrganismState` protobuf defined in `tickdata_contracts.proto`:
     - `organism_id`, `parent_id`, `birth_tick`, `program_id`, `energy`.
     - IP/DV/DPs and indexes.
     - Registers, stacks, call stacks.
     - Failure state fields.
   - `SimulationEngine` populates `TickData.organisms` with complete organism state per tick.

5. **Persistence & Storage**
   - `PersistenceService` writes `TickData` batches to storage.
   - Storage exposes `IBatchStorageRead` for reading `TickData` by batch key.
   - Topic sends `BatchInfo` notifications (used by indexers).

---

## Architectural Context

### Component Architecture

OrganismIndexer uses the generic indexer foundation:

```text
AbstractIndexer
    ↓
AbstractBatchIndexer
    ↓
OrganismIndexer
```

**Responsibilities:**

- `AbstractIndexer`:
  - Discover run ID (configured or discovered).
  - Set the correct DB schema for the run.
  - Call `prepareSchema(runId)` on the concrete indexer.

- `AbstractBatchIndexer`:
  - Subscribe to topic with `BatchInfo` messages.
  - Read corresponding `TickData` batches from storage.
  - Buffer ticks via `TickBufferingComponent`.
  - Call `flushTicks(List<TickData> ticks)` when buffer is ready.

- `OrganismIndexer` (this phase):
  - Define **which components** are used (METADATA + BUFFERING).
  - Extract `OrganismState` data from `TickData`.
  - Handle schema creation for `organisms` and `organism_states`.
  - Map `OrganismState` into row-per-organism-per-tick structure.
  - Delegate writes to `IOrganismDataWriter`.

### Resources and Components

**Resources (per service configuration):**

- `topic` – `ITopicReader<BatchInfo, ACK>`:
  - Partitions batches among competing OrganismIndexer instances.
  - At-least-once delivery with claim timeouts.
- `storage` – `IBatchStorageRead`:
  - Reads `TickData` batches by key.
- `metadata` – `IMetadataReader`:
  - Reads `SimulationMetadata` needed for run/schema context (e.g., for validation).
- `database` – `IOrganismDataWriter` (new):
  - Provides schema creation and write operations for organism tables.

**Components:**

- `MetadataReadingComponent`:
  - Waits until `SimulationMetadata` is available.
  - Exposes `getMetadata()` to the indexer.
- `TickBufferingComponent`:
  - Buffers `TickData` messages until:
    - `insertBatchSize` ticks are collected, or
    - `flushTimeoutMs` elapses.
  - Calls `flushTicks()` with a list of ticks.

### Data Flow

```text
SimulationEngine → PersistenceService → storage (TickData, metadata)
                                     → topic (BatchInfo)
                                                   ↓
                                           OrganismIndexer
                                                   ↓
                                        run discovery (AbstractIndexer)
                                                   ↓
                                    setSimulationRun(runId) on database
                                    prepareSchema(runId): create tables
                                                   ↓
                          [topic poll] → BatchInfo → storage.readTickData(batchKey)
                                                   ↓
                                         buffer ticks (TickBufferingComponent)
                                                   ↓
                                       flushTicks(List<TickData> ticks)
                                                   ↓
                       extract OrganismState list per tick → map to rows
                                                   ↓
                                 write via IOrganismDataWriter (MERGE)
                                                   ↓
                                          ACK BatchInfo on success
```

---

## Data Model

### Design Principles

1. **Zero redundancy across tables**
   - `organisms` stores only static organism data:
     - `organism_id`, `parent_id`, `birth_tick`, `program_id`, `initial_position`.
   - `organism_states` stores only per-tick state.
   - Static fields are **not duplicated** in `organism_states`.

2. **Row-per-organism-per-tick**
   - Each row in `organism_states` represents:
     - One tick (`tick_number`).
     - One organism (`organism_id`).
     - All dynamic state needed for that tick.

3. **Life/Death semantics without `is_dead` flag**
   - `birth_tick` lives in `organisms`.
   - Per tick:
     - Row exists ⇒ organism is alive in that tick.
     - Row does not exist and `tick_number ≥ birth_tick` ⇒ organism is dead or removed.

4. **Grid/dropdown fields as dedicated columns**
   - All data required for:
     - Header dropdown (list of living organisms per tick with energy).
     - Environment grid overlays (IP, DV, all DPs, active DP index).
   - Must be available as **direct columns** in `organism_states`, **not inside a generic blob**.

5. **Detailed fields grouped into a single runtime-state blob**
   - All remaining `OrganismState` fields that are only needed for detailed inspection
     (registers, stacks, call stacks, failure details) are grouped into a single Protobuf
     structure and stored in one binary column per row. This blob can be compressed using
     the same pluggable compression infrastructure as the Environment indexer.

---

## Table: `organisms`

**Scope:** Static organism metadata; one row per organism per run schema.

### Columns

- `organism_id`
  - Type: `INT`
  - Source: `OrganismState.organism_id` / `Organism.getId()`
  - Constraints:
    - `PRIMARY KEY (organism_id)`
    - Unique per run schema.

- `parent_id`
  - Type: `INT`
  - Source: `OrganismState.parent_id` / `Organism.getParentId()`
  - Constraints:
    - `NULL` allowed (no parent).

- `birth_tick`
  - Type: `BIGINT`
  - Source: `OrganismState.birth_tick` / `Organism.getBirthTick()`
  - Constraints:
    - `NOT NULL`.

- `program_id`
  - Type: `TEXT` (PostgreSQL-compatible, no fixed length limit)
  - Source: `OrganismState.program_id` / `Organism.getProgramId()`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - Logical foreign key to ProgramArtifact in metadata (no DB-enforced FK).

- `initial_position`
  - Type: `BINARY`
  - Encoding:
    - Protobuf `Vector` with `repeated int32 components`.
  - Source: `OrganismState.initial_position` / `Organism.getInitialPosition()`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - Relative coordinate in program/environment space at birth.
    - Dimension length equals environment dimensions (from metadata).

### Population & Idempotency

- When an organism appears **for the first time** in `TickData.organisms`:
  - Indexer writes/merges static data into `organisms`.
- Subsequent appearances of the same `organism_id`:
  - Static data must remain consistent; `MERGE` ensures idempotent upsert.

**Suggested SQL:**

```sql
MERGE INTO organisms (
    organism_id,
    parent_id,
    birth_tick,
    program_id,
    initial_position
)
KEY (organism_id)
VALUES (?, ?, ?, ?, ?);
```

---

## Table: `organism_states`

**Scope:** Per-tick organism state; one row per living organism per tick.

### Key Columns

- `tick_number`
  - Type: `BIGINT`
  - Source: `TickData.tick_number`
  - Constraints:
    - `NOT NULL`.

- `organism_id`
  - Type: `INT`
  - Source: `OrganismState.organism_id`
  - Constraints:
    - `NOT NULL`.
    - `FOREIGN KEY (organism_id)` referencing `organisms(organism_id)` (optional in H2; at least logically).

- Primary key:

```sql
PRIMARY KEY (tick_number, organism_id)
```

### Columns Used by Grid & Dropdown (Directly Accessible)

These columns are **read frequently** and must not require parsing a generic state blob.

#### Energy (Dropdown & Sidebar)

- `energy`
  - Type: `INT`
  - Source: `OrganismState.energy` / `Organism.getEr()`
  - Constraints:
    - `NOT NULL`.

#### Instruction Pointer (Grid)

- `ip`
  - Type: `BINARY`
  - Encoding:
    - Protobuf `Vector` with `repeated int32 components`.
  - Source: `OrganismState.ip`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - Relative IP coordinate; environment position can be derived when combined with `initial_position` and environment metadata.

#### Direction Vector (Grid)

- `dv`
  - Type: `BINARY`
  - Encoding:
    - Protobuf `Vector`.
  - Source: `OrganismState.dv`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - Direction in which the IP advances; used directly in grid visualization (arrow/orientation).

#### Data Pointers (Grid)

- `data_pointers`
  - Type: `BINARY`
  - Encoding:
    - Protobuf message encoding `repeated Vector` exactly as in `OrganismState.data_pointers`.
    - Example: a helper message `DataPointerList { repeated Vector data_pointers = 1; }` (internal detail).
  - Source: `OrganismState.data_pointers`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - All DPs in one column; visualizer can decode and render each DP in the grid.

#### Active DP Index (Grid)

- `active_dp_index`
  - Type: `INT`
  - Source: `OrganismState.active_dp_index`
  - Constraints:
    - `NOT NULL`.
  - Semantics:
    - Index into `data_pointers`; used to highlight active DP.

### Detailed Runtime State (Single Blob Column)

All remaining `OrganismState` fields that are not needed for grid/dropdown, but only for
per-organism inspection, are grouped into a single Protobuf message and stored in one
binary column:

- `runtime_state_blob`
  - Type: `BINARY` / `BYTEA`
  - Encoding:
    - Protobuf message `OrganismRuntimeState` with fields:
      - `repeated RegisterValue data_registers`
      - `repeated RegisterValue procedure_registers`
      - `repeated RegisterValue formal_param_registers`
      - `repeated Vector location_registers`
      - `repeated RegisterValue data_stack`
      - `repeated Vector location_stack`
      - `repeated ProcFrame call_stack`
      - `bool instruction_failed`
      - `string failure_reason`
      - `repeated ProcFrame failure_call_stack`
    - The message is serialized using Protobuf and **may be compressed** using the same
      pluggable compression infrastructure as the Environment indexer (see below).
  - Source:
    - All corresponding fields from `OrganismState` (`data_registers`, `procedure_registers`,
      `formal_param_registers`, `location_registers`, `data_stack`, `location_stack`,
      `call_stack`, `instruction_failed`, `failure_reason`, `failure_call_stack`).
  - Constraints:
    - `NOT NULL` (an organism that has no registers/stacks still has an empty runtime state).

---

## Idempotency Strategy

### Key Choice

- Primary key: `(tick_number, organism_id)` for `organism_states`.
- Primary key: `(organism_id)` for `organisms`.

### MERGE Operations

All writes use `MERGE` to achieve **idempotent upserts**:

#### Static Organisms

```sql
MERGE INTO organisms (
    organism_id,
    parent_id,
    birth_tick,
    program_id,
    initial_position
)
KEY (organism_id)
VALUES (?, ?, ?, ?, ?);
```

#### Per-Tick Organism State

```sql
MERGE INTO organism_states (
    tick_number,
    organism_id,
    energy,
    ip,
    dv,
    data_pointers,
    active_dp_index,
    data_registers,
    procedure_registers,
    formal_param_registers,
    location_registers,
    data_stack,
    location_stack,
    call_stack,
    instruction_failed,
    failure_reason,
    failure_call_stack
)
KEY (tick_number, organism_id)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

### Redelivery Behavior

**Scenario:**

1. OrganismIndexer processes a batch, writes rows, but crashes before ACK.
2. Topic re-delivers the batch after `claimTimeout`.
3. OrganismIndexer processes the same `TickData` again.

**Result:**

- Static rows in `organisms`:
  - Existing entries matched on `organism_id` are updated with the same values (no duplication).
- Dynamic rows in `organism_states`:
  - Existing `(tick_number, organism_id)` rows updated with the same values.
  - Any rows missed during first attempt are inserted.

Idempotency is guaranteed purely by DB semantics, no additional indexer-side idempotency tracking is required.

---

## Thread Safety

- OrganismIndexer runs one main processing thread inside a single JVM.
- All stateful components used by OrganismIndexer (buffering, metadata, DB writer) are single-threaded from the indexer’s perspective.
- Multiple OrganismIndexer instances are supported via:
  - Topic consumer group for `BatchInfo`.
  - Each instance uses its own `IOrganismDataWriter` with dedicated DB connection.

No mutable shared state exists between different indexer instances.

---

## Error Handling & Recovery

Error handling follows the same conventions as `EnvironmentIndexer` and `MetadataIndexer`.
Logging and error classification MUST follow the global data-pipeline rules from `AGENTS.md`
(`Error Handling & Logging`), in particular:

- Transient errors: `log.warn("...", args)` + `recordError(code, msg, details)`, no exception
  parameter.
- Fatal errors: `log.error("...", args)` without exception parameter, then throw an exception
  so that the service transitions into ERROR state.
- Normal shutdown (`InterruptedException`): `log.debug("...", args)` and re-throw.
- Never use `System.out.println()` / `System.err.println()`, stack traces only at DEBUG level.

### flushTicks() Error Propagation

`OrganismIndexer.flushTicks(List<TickData> ticks)`:

- Delegates writes to `IOrganismDataWriter.writeOrganismStates(ticks)`.
- Does **not** swallow exceptions.
- Any `Exception` is propagated to `AbstractBatchIndexer.processBatchMessage()`.

### AbstractBatchIndexer Behavior

When `flushTicks()` throws:

- **For `SQLException` (database error):**
  - Log: `WARN` with concise message (no stack trace at ERROR).
  - Record operational error via `recordError(...)` on the DB resource.
  - **No ACK** is sent for the batch.
  - Batch is returned to the topic and redelivered after `claimTimeout`.

- **For `IOException` (storage error):**
  - Same pattern: `WARN` + `recordError`, no ACK → redeliver.

- **For `RuntimeException`:**
  - Treated as transient for recovery logic:
    - `WARN` + `recordError`.
    - No ACK → redeliver.

- **For `InterruptedException`:**
  - Interpreted as shutdown.
  - Re-thrown, service transitions to STOPPED or ERROR depending on context.

### Guarantees

- **No data loss:**
  - Batches that fail during flush are not ACKed and will be redelivered.

- **No duplication:**
  - MERGE semantics guarantee idempotent writes on redelivery.

- **Service continuity:**
  - Indexer can continue processing other batches after a transient failure.

---

## Non-Functional Requirements

### Storage Considerations

Each `organism_states` row stores:

- A small set of scalar/vector fields for grid/dropdown:
  - `tick_number`, `organism_id`, `energy`, `ip`, `dv`, `data_pointers`, `active_dp_index`.
- One binary blob (`runtime_state_blob`) containing all remaining runtime state needed
  only for detailed inspection.

Implications:

- The total size of `organism_states` will be dominated by `runtime_state_blob`, since it
  aggregates registers, stacks, and call stack data. Grid/dropdown fields remain small.
- To keep storage usage manageable over very long runs, the `runtime_state_blob` SHOULD
  support compression, analogous to the Environment indexer’s `CellStateList` BLOB.
- Compression is particularly important for:
  - Very long simulations (many ticks).
  - High organism counts.
  - Debug runs where sidebar data is rarely inspected, but fully recorded.

**Key decision:**  
All full runtime state (registers, stacks, call stacks, failure details) is taken from
`TickData` and stored in DB **once per organism/tick** in `runtime_state_blob`. No additional
“expanded” or derived views are stored in Phase 16.1. Compression of this blob is allowed
and recommended; grid/dropdown fields remain uncompressed for fast access.

### Performance

- `insertBatchSize` and `flushTimeoutMs` control batching.
- All DB writes:
  - Use JDBC batch semantics where appropriate.
  - Perform a single commit per flush.
- Expected behavior:
  - Write throughput comparable to EnvironmentIndexer (same infrastructure).
  - Scaling via multiple competing OrganismIndexer instances.
  - Optional compression of `runtime_state_blob` adds CPU overhead in the indexer, but
    does not affect the SimulationEngine or PersistenceService.

### Memory

- Buffering:
  - `insertBatchSize` ticks buffered at most.
  - Each tick carries a list of `OrganismState` messages.
  - For high organism counts per tick, the batch size must be tuned to avoid excessive heap usage.

- DB wrapper:
  - Uses streaming writes where possible.
  - Avoids building large intermediate collections from ticks beyond what `TickData` already contains.

### Metrics

`IOrganismDataWriter` wrapper must expose:

- Counters:
  - `organism_rows_written` – total `(tick, organism_id)` rows written.
  - `ticks_written` – total ticks written.
  - `write_errors` – failed write operations.
- Throughput (moving window):
  - `organisms_per_second`.
  - `ticks_per_second`.
- Latency:
  - `write_latency_p50_ms`, `write_latency_p95_ms`, `write_latency_p99_ms`, `write_latency_avg_ms`.

All metrics must be O(1) to record (e.g., using `SlidingWindowCounter`/`SlidingWindowPercentiles`).

---

### Compression Semantics for `runtime_state_blob`

Compression of `runtime_state_blob` MUST follow the same architectural pattern as the
Environment indexer’s SingleBlobStrategy:

- **Configuration:**
  - Compression codec and options are configured via HOCON (e.g., `compression { enabled, codec, level }`).
  - Configuration is applied only for **new writes**. Existing rows remain encoded with the
    codec that was active when they were written.

- **On write:**
  - Organism runtime state is first serialized as a Protobuf `OrganismRuntimeState` message.
  - The serializer then wraps the output stream with an `ICompressionCodec` selected from
    the configured strategy (e.g., `none`, `zstd`, `gzip`), and writes:
    - A small codec header or magic bytes that unambiguously identify the codec and version.
    - The compressed payload.
  - This is analogous to the environment `CellStateList` BLOB, which uses a strategy-specific
    codec wrapper.

- **On read:**
  - The reader **must not rely on current configuration** to decide how to decompress.
  - Instead, it:
    - Reads the header/magic bytes from `runtime_state_blob`.
    - Uses a codec factory (e.g., `CompressionCodecFactory.detect(...)`) to select the correct
      `ICompressionCodec` implementation based on the header alone.
    - Decompresses the payload via the detected codec.
    - Parses the resulting bytes as `OrganismRuntimeState` Protobuf.
  - This guarantees that decompression continues to work even if the configured codec changes
    between simulation runs, or between writing and reading.

- **Forward compatibility:**
  - New codec variants can be introduced by:
    - Adding new magic bytes / header format to the codec factory.
    - Ensuring old codecs remain available for reading (even if they are no longer used for writing).
  - The DB never needs to be migrated when the compression configuration changes; only new
    rows use the new codec.

---

## Testing Strategy

All tests for OrganismIndexer and its helpers MUST follow the global testing guidelines in
`AGENTS.md` (`Testing Guidelines`), including:

- Use JUnit 5 with `@Tag("unit")` for unit tests and `@Tag("integration")` for integration tests.
- Unit tests: no filesystem/network/database I/O, runtime < 0.2s.
- Integration tests: use in-memory H2 where a DB is needed, ensure cleanup in `@AfterEach`,
  and do not leave artifacts.
- Use Awaitility for waiting on asynchronous conditions; never use `Thread.sleep()`.
- Use `LogWatchExtension` with precise `@ExpectLog` / `@AllowLog` patterns; unexpected WARN/ERROR
  logs must fail tests.

### Unit Tests

**OrganismIndexerTest** (unit):

- `testFlushTicks_WritesAllOrganismsPerTick()`:
  - Given a `TickData` with N organisms, assert N calls/rows for N `(tick, organism_id)` pairs.
- `testLifeCycle_BirthAndDeath()`:
  - Create an organism with `birth_tick = 10`.
  - Provide `TickData` for ticks 8, 10, 11; ensure:
    - No `organism_states` rows for tick 8.
    - Rows for ticks 10 and 11.
    - No rows for tick 12+ when organism is not in `TickData`.
- `testStaticTable_PopulatedOnce()`:
  - Same `organism_id` appearing across multiple ticks must result in one row in `organisms` and multiple rows in `organism_states`.

**OrganismDataWriterWrapperTest** (unit):

- `testMetricsCounters()`:
  - Verify counters (`organism_rows_written`, `ticks_written`, `write_errors`) update correctly.
- `testLatencyMetrics()`:
  - Verify `write_latency_*` metrics reflect observed durations (within tolerance).

**H2DatabaseOrganismWriteTest** (unit/integration boundary):

- `testCreateOrganismTables_Idempotent()`:
  - Multiple calls to `createOrganismTables()` succeed without errors.
- `testWriteOrganismStates_MergeIdempotent()`:
  - Writing the same `(tick, organism_id)` twice results in a single row with consistent values.

### Integration Tests

**OrganismIndexerEndToEndTest** (integration):

- Setup:
  - In-memory H2 schema for test run.
  - Fake topic and storage.
  - Write a few batches with controlled `TickData`:
    - Multiple organisms, some born later, some disappearing (death).
- Run:
  - Start OrganismIndexer.
  - Wait for processing.
- Verify:
  - `organisms` table contains exactly the static organisms present.
  - `organism_states` rows match expected `(tick, organism_id)` pairs.
  - Life/death semantics hold per spec.

**OrganismIndexerIdempotencyTest** (integration):

- Send a batch, let indexer process it.
- Simulate redelivery of the same batch.
- Verify:
  - `organisms` row counts do not increase.
  - `organism_states` row counts remain stable.

**OrganismIndexerCompetingConsumersTest** (integration):

- Start 2 OrganismIndexer instances with the same consumer group.
- Send multiple batches.
- Verify:
  - All expected rows are present, exactly once each.
  - Work is distributed between indexers (optional metric check).

---

## Implementation Phases

The implementation is split into small, verifiable steps, similar to Phase 14.3.

### Phase 1 – Data Model & Schema

**Goal:** Introduce organism tables and their schema-aware DB interface.

**Deliverables:**

- `IOrganismDataWriter` interface in `api.resources.database`:
  - `createOrganismTables()`
  - `writeOrganismStates(List<TickData> ticks)`
- Schema creation logic in H2 implementation:
  - DDL for `organisms` and `organism_states`.
  - Idempotent `CREATE TABLE IF NOT EXISTS` with H2 race-condition helper.

**Tests:**

- Unit tests for schema creation (`CREATE TABLE` idempotency).

### Phase 2 – DB Wrapper & Metrics

**Goal:** Wrap H2 database for organism operations and add metrics.

**Deliverables:**

- `OrganismDataWriterWrapper` extending existing database wrapper base class.
- Metric counters and latency tracking for organism writes.

**Tests:**

- Unit tests for metric correctness and error handling.

### Phase 3 – OrganismIndexer Service

**Goal:** Implement the indexer service using the foundation.

**Deliverables:**

- `OrganismIndexer`:
  - Constructor wiring `database`, `storage`, `topic`, `metadata`.
  - `prepareSchema(runId)`:
    - Resolves schema via `setSimulationRun(runId)`.
    - Calls `createOrganismTables()`.
  - `flushTicks(List<TickData> ticks)`:
    - Delegates to `writeOrganismStates(ticks)`.
    - Logging and error propagation only, no additional idempotency logic.

**Tests:**

- Unit tests verifying interaction with `IOrganismDataWriter`.

### Phase 4 – Integration & Validation

**Goal:** Verify end-to-end behavior and non-functional requirements.

**Deliverables:**

- Integration tests for:
  - End-to-end indexing.
  - Idempotency on redelivery.
  - Competing consumers.
- Configuration updates in `evochora.conf` for OrganismIndexer.

**Success:** All tests pass; indexer behaves as specified.

---

## Status

- Phase 16.1 is currently **specified** and ready for implementation.
- Future phases (16.2+) will cover:
  - Organism-specific database read APIs.
  - HTTP controllers for organism data.
  - Visualizer integration (dropdown, grid overlays, sidebars).


