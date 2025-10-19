# Data Pipeline V3 - Indexer Foundation (Phase 14.2)

## Overview

This document outlines the topic-based publish/subscribe architecture for the Data Pipeline V3 indexer infrastructure.

## Approach
```
PersistenceService → Storage.writeBatch()
                  → Topic.send(BatchInfo{filename, ticks})
                         ↓
Indexer → Topic.poll() [BLOCKING, no polling storage!]
       → MERGE-based idempotent processing (no duplicate inserts!)
       → Buffer ticks (cross-batch!)
       → Flush → MERGE to DB
       → Topic.ack() [ONLY for batches fully flushed!]
```

**Key Features:**
- ✅ Instant notification (blocking poll, no storage scanning)
- ✅ Reliable delivery (Topic guarantees)
- ✅ Competing consumers via Consumer Groups (built-in)
- ✅ At-least-once delivery + MERGE = Exactly-once semantics
- ✅ ACK only after ALL ticks of a batch are flushed (cross-batch buffering safe!)
- ✅ 100% idempotent via MERGE (IdempotencyComponent optional for performance)

## Architecture Principles

### Three-Level Architecture

```
AbstractIndexer (minimal core)
    ↓
    ├─ MetadataIndexer (direct, simple processing)
    │
    └─ AbstractBatchIndexer (with Components + Buffering logic)
           ↓
           ├─ DummyIndexer (log-only flush)
           ├─ EnvironmentIndexer (MERGE to environment_data)
           └─ OrganismIndexer (MERGE to organism_data)
```

### AbstractIndexer - Minimal Core

`AbstractIndexer` is intentionally **minimal** and contains **NO components**:

```java
public abstract class AbstractIndexer<T extends Message, ACK> extends AbstractService {
    // Core resources (injected)
    protected final IBatchStorageRead storage;
    protected final ITopicReader<T, ACK> topic;
    
    // Template method
    protected abstract void indexRun(String runId) throws Exception;
    
    // Optional hook
    protected void prepareSchema(String runId) throws Exception { }
    
    // Utilities
    protected String discoverRunId() { ... }
    private void setSchemaForAllDatabaseResources(String runId) { ... }
}
```

**Design Philosophy:**
- ✅ Single Responsibility: Run discovery, schema management, resource injection
- ✅ No business logic, no components, no conditional flows
- ✅ Used directly by `MetadataIndexer` (simple processing)
- ✅ Extended by `AbstractBatchIndexer` (complex processing with components)

### AbstractBatchIndexer - Batch Processing with Components

`AbstractBatchIndexer` extends `AbstractIndexer` and adds **all** batch-processing logic:

```java
public abstract class AbstractBatchIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {
    // Components (configured by subclass constructor!)
    private final MetadataReadingComponent metadataComponent;    // Optional
    private final TickBufferingComponent bufferingComponent;     // Required!
    private final IdempotencyComponent idempotencyComponent;     // Optional
    
    protected AbstractBatchIndexer(..., BatchIndexerComponents components) {
        super(...);
        // Components provided by subclass!
    }
    
    // FINAL implementation - topic loop, buffering, ACK tracking
    @Override
    protected final void indexRun(String runId) throws Exception { ... }
    
    // ONLY template method for subclass!
    protected abstract void flushTicks(List<TickData> ticks) throws Exception;
}
```

**Design Philosophy:**
- ✅ **All batch-processing logic in one place** (DRY!)
- ✅ **Components configured by subclass constructor** (flexible!)
- ✅ **Only `flushTicks()` is indexer-specific** (minimal subclass code!)
- ✅ Handles: Topic loop, storage read, buffering, ACK tracking, idempotency, error handling

### Component-Based Architecture

**All components are optional** - each batch indexer decides which ones to use in its constructor:

| Component | Purpose | Polls what? | When needed? |
|-----------|---------|-------------|---------------|
| `MetadataReadingComponent` | Wait for metadata | **DATABASE** (IMetadataReader) | Most batch indexers (optional) |
| `TickBufferingComponent` | Buffer ticks + ACK tracking | - | **Optional:** For large batch inserts |
| `IdempotencyComponent` | Performance optimization | **DATABASE** (IIdempotencyTracker) | Optional (skip duplicate batches) |

**Components live in `AbstractBatchIndexer`, configured by subclass constructor!**

**Two Processing Modes:**
1. **With Buffering:** Ticks buffered across batches, ACK only after ALL ticks of a batch are flushed (configurable `insertBatchSize`, e.g., 250)
2. **Without Buffering:** Each tick processed individually (`insertBatchSize=1`), **ACK sent once after ALL ticks from BatchFile are processed**

**CRITICAL:** 
- **With TickBufferingComponent:** Tracks which ticks belong to which batch, returns ACKs ONLY for fully flushed batches!
- **Without TickBufferingComponent:** Each tick processed separately (`flushTicks(List.of(tick))` called N times), then **ONE ACK** for the entire BatchFile
- **ACK Guarantee (both modes):** ACK is ONLY sent after ALL ticks from a BatchFile are successfully processed

**Why MetadataReadingComponent is needed:**
1. **Dependency Chain:** MetadataIndexer writes metadata → other indexers must wait
2. **Required Data:** Indexers need `samplingInterval`, `dimensions`, etc. from metadata
3. **Race Prevention:** Prevents indexers from starting before metadata is available

```
MetadataPersistenceService → metadata-topic → MetadataIndexer → Database
                                                                     ↓
                                              DummyIndexer/EnvironmentIndexer WAIT HERE
                                                                     ↓
                                                  MetadataReadingComponent.loadMetadata()
                                                                     ↓
                                                      Now process batches!
```

## Implementation Phases

### Phase 14.2.1: Topic Infrastructure ✅

**Status:** Completed

**Deliverables:**
- ✅ `ITopicWriter<T extends Message>`, `ITopicReader<T extends Message>` interfaces
- ✅ `TopicMessage<T extends Message>` wrapper
- ✅ Protobuf contracts: `BatchInfo`, `MetadataInfo`
- ✅ `AbstractTopicResource<T extends Message>` with Template Method pattern
- ✅ `AbstractTopicDelegate<P extends AbstractTopicResource<?>>` for type-safe parent access
- ✅ `H2TopicResource<T extends Message>` with inner delegates
- ✅ Consumer Groups for competing consumers
- ✅ O(1) monitoring with `SlidingWindowCounter`

**Resources Created:**
- `metadata-topic` (MetadataInfo messages)
- `batch-topic` (BatchInfo messages)

---

### Phase 14.2.2: Metadata Notification (Write) ✅

**Status:** Completed

**Goal:** `MetadataPersistenceService` publishes metadata availability to `metadata-topic`.

**Changes:**
- ✅ Add `ITopicWriter<MetadataInfo>` resource binding
- ✅ After successful `storage.writeMessage()`, send MetadataInfo notification
- ✅ Include: runId, storageKey, writtenAtMs

**Deliverables:**
- ✅ Updated `MetadataPersistenceService`
- ✅ Configuration changes
- ✅ Integration tests

---

### Phase 14.2.3: Metadata Notification (Read) ✅

**Status:** Completed

**Goal:** `MetadataIndexer` consumes from `metadata-topic` instead of scanning storage.

**Changes:**
- ✅ Replace storage scanning with `ITopicReader<MetadataInfo>`
- ✅ Consumer group: `"metadata-indexer"`
- ✅ Blocking poll (no storage scanning!)
- ✅ Acknowledge after processing

**Implementation:**
```java
public class MetadataIndexer<ACK> extends AbstractIndexer<MetadataInfo, ACK> {
    // NO MetadataReadingComponent (writes metadata, doesn't read it)
    // NO TickBufferingComponent (only 1 message per run)
    // NO IdempotencyComponent (metadata comes once per run)
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Simple: poll topic, write to database
        var message = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);
        if (message == null) {
            throw new TimeoutException("Metadata notification timeout");
        }
        
        // Read from storage, write to database
        // ...
        
        topic.acknowledge(message.acknowledgment());
    }
}
```

**Deliverables:**
- ✅ Updated `MetadataIndexer`
- ✅ Remove old storage scanning logic
- ✅ Integration tests

---

### Phase 14.2.4: Batch Notification (Write)

**Status:** ✅ Completed (ready for Phase 14.2.5)

**Goal:** `PersistenceService` publishes batch availability to `batch-topic`.

**Implementation:**
- `ITopicWriter<BatchInfo>` added as **optional** resource (warns if not configured)
- After successful `storage.writeBatch()`, sends BatchInfo notification to topic
- Topic initialized with `setSimulationRun()` on first batch
- Notification includes: `simulation_run_id`, `storage_key`, `tick_start`, `tick_end`, `written_at_ms`
- Metrics added: `notifications_sent`, `notifications_failed`

**Thread Safety:**
- Fixed race condition in `AbstractTopicResource.setSimulationRun()` with `synchronized` block
- Multiple `PersistenceService` instances can safely share same `batch-topic`
- All topics share same H2 database (partitioned by `topic_name` column)

**Testing:**
- Unit tests verify notification sending, retry logic, and error handling
- Integration test `PersistenceServiceBatchNotificationIntegrationTest`:
  - Single instance: verifies end-to-end batch notification flow
  - Multiple instances: verifies thread-safe concurrent topic access

**Deliverables:**
- ✅ Updated `PersistenceService` with optional topic support
- ✅ Configuration updated (`evochora.conf` includes `topic = "topic-write:batch-topic"`)
- ✅ Unit tests (4 tests in `PersistenceServiceTest`)
- ✅ Integration tests (2 tests in `PersistenceServiceBatchNotificationIntegrationTest`)

---

### Phase 14.2.5: AbstractBatchIndexer Foundation + DummyIndexer

**Status:** ✅ Completed

**Goal:** Create `AbstractBatchIndexer` with topic loop and storage-read logic. `DummyIndexer` only implements `getRequiredComponents()` and `flushTicks()`.

**Phase 14.2.5 Scope:**
- ✅ MetadataReadingComponent (wait for metadata before processing)
- ✅ Storage-read + tick-by-tick processing (no buffering yet)
- ✅ ACK after ALL ticks from batch are processed
- ❌ TickBufferingComponent (comes in Phase 14.2.6)
- ❌ IdempotencyComponent (comes in Phase 14.2.7)

**Changes:**
- Create `AbstractBatchIndexer` extending `AbstractIndexer`
- Add `BatchIndexerComponents` helper class (Phase 14.2.5: only metadata component)
- Topic loop + storage-read logic in `AbstractBatchIndexer.processBatchMessage()`
- Template method `getRequiredComponents()` declares which components to use
- Final method `createComponents()` creates components based on declaration
- `DummyIndexer` extends `AbstractBatchIndexer` and implements `getRequiredComponents()` and `flushTicks()`

**AbstractBatchIndexer Implementation:**

*Note: Requires `import java.util.Set; import java.util.EnumSet;`*

```java
public abstract class AbstractBatchIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {
    
    private final BatchIndexerComponents components;
    private final int topicPollTimeoutMs;
    
    // Metrics (O(1) tracking)
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    
    protected AbstractBatchIndexer(String name, 
                                   Config options, 
                                   Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Template method: Let subclass configure components
        this.components = createComponents();
        
        // Phase 14.2.5: Simple configurable timeout
        // Phase 14.2.6: Will be automatically set to flushTimeout
        this.topicPollTimeoutMs = options.hasPath("topicPollTimeoutMs") 
            ? options.getInt("topicPollTimeoutMs") 
            : 5000;
    }
    
    /**
     * Component types available for batch indexers.
     * <p>
     * Used by {@link #getRequiredComponents()} to declare which components to use.
     * <p>
     * Phase 14.2.5 scope: Only METADATA available!
     */
    public enum ComponentType {
        /** Metadata reading component (polls DB for simulation metadata). */
        METADATA
        
        // Phase 14.2.6: BUFFERING will be added
        // Phase 14.2.8: DLQ will be added
    }
    
    /**
     * Template method: Declare which components this indexer uses.
     * <p>
     * Called once during construction. Subclasses can override to customize component usage.
     * <p>
     * <strong>Default:</strong> Returns METADATA only (Phase 14.2.5).
     * Phase 14.2.6+ default: METADATA + BUFFERING.
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * // Use default (no override needed for standard case!)
     * 
     * // Minimal: No components at all
     * @Override
     * protected Set&lt;ComponentType&gt; getRequiredComponents() {
     *     return EnumSet.noneOf(ComponentType.class);
     * }
     * </pre>
     *
     * @return set of component types to use (never null)
     */
    protected Set<ComponentType> getRequiredComponents() {
        // Phase 14.2.5: Default is METADATA only
        // Phase 14.2.6+: Default will be METADATA + BUFFERING
        return EnumSet.of(ComponentType.METADATA);
    }
    
    /**
     * Creates components based on {@link #getRequiredComponents()}.
     * <p>
     * <strong>FINAL:</strong> Subclasses must NOT override this method.
     * Instead, override {@link #getRequiredComponents()} to customize components.
     * <p>
     * All components use standardized config parameters:
     * <ul>
     *   <li>Metadata: {@code metadataPollIntervalMs}, {@code metadataMaxPollDurationMs}</li>
     * </ul>
     * <p>
     * Phase 14.2.5 scope: Only METADATA component!
     * Phase 14.2.6+: BUFFERING component added.
     * Phase 14.2.8+: DLQ component added.
     *
     * @return component configuration (may be null if no components requested)
     */
    protected final BatchIndexerComponents createComponents() {
        Set<ComponentType> required = getRequiredComponents();
        if (required.isEmpty()) return null;
        
        var builder = BatchIndexerComponents.builder();
        
        // Component 1: Metadata Reading (Phase 14.2.5)
        if (required.contains(ComponentType.METADATA)) {
            IMetadataReader metadataReader = getRequiredResource("metadata", IMetadataReader.class);
            int pollIntervalMs = indexerOptions.getInt("metadataPollIntervalMs");
            int maxPollDurationMs = indexerOptions.getInt("metadataMaxPollDurationMs");
            builder.withMetadata(new MetadataReadingComponent(
                metadataReader, pollIntervalMs, maxPollDurationMs));
        }
        
        // Phase 14.2.6: BUFFERING component handling will be added here
        // Phase 14.2.8: DLQ component handling will be added here
        
        return builder.build();
    }
    
    @Override
    protected final void indexRun(String runId) throws Exception {
        // Step 1: Wait for metadata (if component exists)
        if (components != null && components.metadata != null) {
            components.metadata.loadMetadata(runId);
            log.debug("Metadata loaded for run: {}", runId);
        }
        
        // Step 2: Topic loop
        while (!Thread.currentThread().isInterrupted()) {
            TopicMessage<BatchInfo, ACK> msg = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);
            
            if (msg == null) {
                // Phase 14.2.6: Will check buffering component for timeout-based flush here
                continue;
            }
            
            processBatchMessage(msg);
        }
        
        // Phase 14.2.6: Final flush of remaining buffered ticks here
    }
    
    private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
        BatchInfo batch = msg.payload();
        String batchId = batch.getStorageKey();
        
        log.debug("Received BatchInfo: storageKey={}, ticks=[{}-{}]", 
            batch.getStorageKey(), batch.getTickStart(), batch.getTickEnd());
        
        try {
            // Read from storage (GENERIC for all batch indexers!)
            // Storage handles length-delimited format automatically
            List<TickData> ticks = storage.readBatch(batch.getStorageKey());
            
            // WITHOUT buffering - tick-by-tick processing
            for (TickData tick : ticks) {
                    flushTicks(List.of(tick));
                ticksProcessed.incrementAndGet();  // Track each tick
                }
            
            // ACK after ALL ticks from batch are processed
            topic.ack(msg);
            batchesProcessed.incrementAndGet();  // Track each batch
                
                log.debug("Processed {} ticks from {} (tick-by-tick, no buffering)", 
                     ticks.size(), batch.getStorageKey());
            
        } catch (Exception e) {
            log.error("Failed to process batch: {}", batchId);
            throw e;  // NO acknowledge - redelivery!
        }
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        metrics.put("batches_processed", batchesProcessed.get());
        metrics.put("ticks_processed", ticksProcessed.get());
    }
    
    /**
     * Flush ticks to database/log.
     * <p>
     * Phase 14.2.5: Called per tick (list will always contain exactly 1 tick).
     * Phase 14.2.6+: Called per buffer flush (list can contain multiple ticks).
     * <p>
     * CRITICAL: Must use MERGE (not INSERT) for idempotency when writing to database!
     *
     * @param ticks Ticks to flush (Phase 14.2.5: always size=1)
     * @throws Exception if flush fails
     */
    protected abstract void flushTicks(List<TickData> ticks) throws Exception;
    
    /**
     * Component configuration for batch indexers.
     * <p>
     * Phase 14.2.5: Only contains MetadataReadingComponent.
     * Phase 14.2.6+: TickBufferingComponent, IdempotencyComponent added.
     * <p>
     * Use builder pattern for extensibility:
     * <pre>
     * BatchIndexerComponents.builder()
     *     .withMetadata(...)
     *     .withBuffering(...)
     *     .build()
     * </pre>
     */
    public static class BatchIndexerComponents {
        public final MetadataReadingComponent metadata;  // Optional
        
        private BatchIndexerComponents(MetadataReadingComponent metadata) {
            this.metadata = metadata;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private MetadataReadingComponent metadata;
            
            public Builder withMetadata(MetadataReadingComponent c) {
                this.metadata = c;
                return this;
            }
            
            public BatchIndexerComponents build() {
                return new BatchIndexerComponents(metadata);
            }
        }
    }
}
```

**DummyIndexer Implementation (Phase 14.2.5):**
```java
public class DummyIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    // No need to override getRequiredComponents() - default is METADATA! ✅
    
    @Override
    protected void flushTicks(List<TickData> ticks) {
        // Phase 14.2.5: Log-only (will always receive exactly 1 tick)
        // Phase 14.2.6+: Will receive multiple ticks from buffer flush
        log.debug("Flushed {} ticks (DummyIndexer: no DB writes)", ticks.size());
    }
}
```

**Configuration:**
```hocon
dummyIndexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storageRead:tickStorage"
    metadata = "dbMetaRead:indexDatabase"
    topic = "topicRead:batchTopic?consumerGroup=dummyIndexer"
    # Phase 14.2.7: idempotency = "dbIdempotency:indexDatabase"
  }
  
  options {
    runId = ${?pipeline.services.runId}
    
    # Metadata polling (standardized config names!)
    metadataPollIntervalMs = 1000
    metadataMaxPollDurationMs = 300000
    
    # Topic polling (Phase 14.2.5: simple timeout)
    topicPollTimeoutMs = 5000
    
    # Phase 14.2.6: insertBatchSize and flushTimeoutMs will be added
    # Phase 14.2.8: maxRetries will be added (for DLQ)
  }
}
```

**Deliverables:**
- ✅ `AbstractBatchIndexer` with topic loop + storage-read logic + metrics
  - `ComponentType` enum (Phase 14.2.5: only `METADATA`)
  - Template method `getRequiredComponents()` (default: `METADATA`)
  - Final `createComponents()` using `getRequiredComponents()`
  - `processBatchMessage()` with tick-by-tick processing
  - Metrics: `batches_processed`, `ticks_processed` (O(1) AtomicLong)
  - Override `addCustomMetrics()` to expose batch/tick counters
- ✅ `BatchIndexerComponents` helper class (Phase 14.2.5: only metadata field)
- ✅ Standardized config parameters: `metadataPollIntervalMs`, `metadataMaxPollDurationMs`, `topicPollTimeoutMs`
- ✅ `DummyIndexer` refactored to extend `AbstractBatchIndexer`
  - Only `flushTicks()` implemented (log-only, no DB writes)
  - Uses default `getRequiredComponents()` (no override needed)
  - All metadata/metric logic removed (now in AbstractBatchIndexer)
- ✅ Unit tests: `AbstractBatchIndexerTest` (6 tests)
  - ACK after successful processing
  - No ACK on storage read error
  - No ACK on flush error
  - Tick-by-tick processing (each tick flushed individually)
  - Metadata loaded before batch processing
  - Metrics tracking (batches_processed, ticks_processed)
- ✅ Integration tests: `DummyIndexerIntegrationTest` (5 tests)
  - Metadata reading (success, polling, parallel mode, timeout)
  - Batch processing end-to-end (BatchInfo → Storage → Ticks → ACK)
- ✅ Configuration: `evochora.conf` with full DummyIndexer config and comments

---

### Phase 14.2.6: TickBufferingComponent Implementation

**Status:** ✅ Completed

**Goal:** Add `TickBufferingComponent` with batch-level ACK tracking to `AbstractBatchIndexer`.

**Phase 14.2.6 Changes:**
- Add `ComponentType.BUFFERING` to enum
- Add `TickBufferingComponent` to `BatchIndexerComponents`
- Update final `createComponents()` to handle BUFFERING component
- Add buffering logic to `AbstractBatchIndexer.processBatchMessage()` (if-else branch)
- Add `flushAndAcknowledge()` helper method
- Add timeout-based flush check in `indexRun()` topic loop
- Add standardized config parameters: `insertBatchSize`, `flushTimeoutMs`
- Update `DummyIndexer.getRequiredComponents()` to include BUFFERING
- Automatically set `topicPollTimeoutMs = flushTimeoutMs`

**Note:** Storage-read logic already exists in Phase 14.2.5, only buffering logic is added here.

**AbstractBatchIndexer Changes (Phase 14.2.6):**

**Step 1: Add BUFFERING to ComponentType enum**
```java
public enum ComponentType {
    /** Metadata reading component (polls DB for simulation metadata). */
    METADATA,
    
    /** Tick buffering component (buffers ticks for batch inserts). Phase 14.2.6. */
    BUFFERING  // NEW!
    
    // Phase 14.2.8: DLQ will be added
}
```

**Step 2: Update getRequiredComponents() default**
```java
protected Set<ComponentType> getRequiredComponents() {
    // Phase 14.2.6: Default is METADATA + BUFFERING
    return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
}
```

**Step 3: Update createComponents() to handle BUFFERING**
```java
protected final BatchIndexerComponents createComponents() {
    Set<ComponentType> required = getRequiredComponents();
    if (required.isEmpty()) return null;
    
    var builder = BatchIndexerComponents.builder();
    
    // Component 1: Metadata Reading (Phase 14.2.5)
    if (required.contains(ComponentType.METADATA)) {
        IMetadataReader metadataReader = getRequiredResource("metadata", IMetadataReader.class);
        int pollIntervalMs = indexerOptions.getInt("metadataPollIntervalMs");
        int maxPollDurationMs = indexerOptions.getInt("metadataMaxPollDurationMs");
        builder.withMetadata(new MetadataReadingComponent(
            metadataReader, pollIntervalMs, maxPollDurationMs));
    }
    
    // Component 2: Tick Buffering (Phase 14.2.6 - NEW!)
    if (required.contains(ComponentType.BUFFERING)) {
        int insertBatchSize = indexerOptions.getInt("insertBatchSize");
        long flushTimeoutMs = indexerOptions.getLong("flushTimeoutMs");
        builder.withBuffering(new TickBufferingComponent(insertBatchSize, flushTimeoutMs));
    }
    
    // Phase 14.2.8: DLQ component handling will be added here
    
    return builder.build();
}
```

**Step 4: Update constructor to auto-set topicPollTimeout**
```java
protected AbstractBatchIndexer(...) {
    super(...);
    this.components = createComponents();
    
    // Phase 14.2.6: Automatically set topicPollTimeout to flushTimeout if buffering enabled
    if (components != null && components.buffering != null) {
        this.topicPollTimeoutMs = (int) components.buffering.getFlushTimeoutMs();
    } else {
        this.topicPollTimeoutMs = options.hasPath("topicPollTimeoutMs") 
            ? options.getInt("topicPollTimeoutMs") 
            : 5000;
    }
}
```

**Step 5: Update indexRun() and processBatchMessage()**
```java
public abstract class AbstractBatchIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {
    
    @Override
    protected final void indexRun(String runId) throws Exception {
        if (components != null && components.metadata != null) {
            components.metadata.loadMetadata(runId);
            log.debug("Metadata loaded for run: {}", runId);
        }
        
        while (!Thread.currentThread().isInterrupted()) {
            TopicMessage<BatchInfo, ACK> msg = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);
            
            if (msg == null) {
                // Check buffering component for timeout-based flush
                if (components != null && components.buffering != null 
                    && components.buffering.shouldFlush()) {
                    flushAndAcknowledge();
                }
                continue;
            }
            
            processBatchMessage(msg);
        }
        
        // Final flush of remaining buffered ticks
        if (components != null && components.buffering != null 
            && components.buffering.getBufferSize() > 0) {
            flushAndAcknowledge();
        }
    }
    
    private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
        BatchInfo batch = msg.payload();
        String batchId = batch.getStorageKey();
        
        log.debug("Received BatchInfo: storageKey={}, ticks=[{}-{}]", 
            batch.getStorageKey(), batch.getTickStart(), batch.getTickEnd());
        
        try {
            // Storage handles length-delimited format automatically
            List<TickData> ticks = storage.readBatch(batch.getStorageKey());
            
            if (components != null && components.buffering != null) {
                // WITH buffering: Add to buffer, ACK after flush
                components.buffering.addTicksFromBatch(ticks, batchId, msg);
                
                log.debug("Buffered {} ticks from {}, buffer size: {}", 
                    ticks.size(), batch.getStorageKey(), 
                    components.buffering.getBufferSize());
                
                // Flush if needed
                if (components.buffering.shouldFlush()) {
                    flushAndAcknowledge();
                }
            } else {
                // WITHOUT buffering: tick-by-tick processing
                for (TickData tick : ticks) {
                    flushTicks(List.of(tick));
                }
                topic.ack(msg);
                
                log.debug("Processed {} ticks from {} (tick-by-tick, no buffering)", 
                         ticks.size(), batch.getStorageKey());
            }
            
        } catch (Exception e) {
            log.error("Failed to process batch: {}", batchId);
            throw e;  // NO acknowledge - redelivery!
        }
    }
    
    private void flushAndAcknowledge() throws Exception {
        TickBufferingComponent.FlushResult<ACK> result = components.buffering.flush();
        if (result.ticks().isEmpty()) return;
        
        // Call indexer-specific flush
        flushTicks(result.ticks());
        
        // ACK all completed batches
        for (TopicMessage<?, ACK> msg : result.completedMessages()) {
            topic.ack(msg);
        }
    }
}
```

**BatchIndexerComponents (Phase 14.2.6):**
```java
public static class BatchIndexerComponents {
    public final MetadataReadingComponent metadata;     // Optional
    public final TickBufferingComponent buffering;      // Optional (Phase 14.2.6)
    
    private BatchIndexerComponents(MetadataReadingComponent metadata,
                                   TickBufferingComponent buffering) {
        this.metadata = metadata;
        this.buffering = buffering;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private MetadataReadingComponent metadata;
        private TickBufferingComponent buffering;
        
        public Builder withMetadata(MetadataReadingComponent c) {
            this.metadata = c;
            return this;
        }
        
        public Builder withBuffering(TickBufferingComponent c) {
            this.buffering = c;
            return this;
        }
        
        public BatchIndexerComponents build() {
            return new BatchIndexerComponents(metadata, buffering);
        }
    }
}
```

**DummyIndexer (Phase 14.2.6):**
```java
// NO CHANGES needed! ✅
// Default is now METADATA + BUFFERING, which is exactly what DummyIndexer wants!
```

**The ACK Problem:**

```
Config: insertBatchSize=250, storage batches=100 ticks each

1. poll() → batch_0000.pb (100 ticks) → buffer: [0-99]     (size: 100)
2. poll() → batch_0001.pb (100 ticks) → buffer: [0-199]   (size: 200) 
3. poll() → batch_0002.pb (100 ticks) → buffer: [0-299]   (size: 300)
   → shouldFlush() → TRUE → Flush 250 Ticks (0-249)
   → buffer: [250-299] (only 50 ticks from batch_0002!)

QUESTION: What gets ACKed now?
- batch_0000: ✅ All 100 ticks flushed → ACK ok
- batch_0001: ✅ All 100 ticks flushed → ACK ok  
- batch_0002: ❌ Only 50 of 100 ticks flushed → NO ACK!

If we ACK batch_0002 now and then crash:
→ Topic won't redeliver batch_0002
→ 50 Ticks (tick_250-299) are LOST! ❌❌❌
```

**Solution: TickBufferingComponent with Batch-Flush-Tracking**

Component tracks:
- Which tick belongs to which batch? → `batchIds` Array (parallel to `buffer`)
- How many ticks of a batch were flushed? → `BatchFlushState.ticksFlushed`
- Which TopicMessage must be ACKed? → `BatchFlushState.message`

Component returns on `flush()`:
- Ticks to flush → `List<TickData>`
- ACKs for **only fully flushed batches** → `List<TopicMessage<?, ACK>>`

**Topic Poll Timeout Strategy:**

For batch-processing indexers, `topicPollTimeout` is **automatically set to `flushTimeout`** to guarantee timely flush:

```
Scenario: insertBatchSize=250, storage batches=100 ticks each, flushTimeout=5s

1. poll(5000ms) → batch_0000.pb (100 ticks) → buffer: 100
2. poll(5000ms) → batch_0001.pb (100 ticks) → buffer: 200
3. poll(5000ms) → batch_0002.pb (100 ticks) → buffer: 300
   → FLUSH (250 ticks), buffer: 50 remaining
   → ACK: batch_0000 ✅, batch_0001 ✅, batch_0002 ❌ (incomplete!)
4. poll(5000ms) → timeout after 5s (no more messages)
   → shouldFlush() returns true (elapsed ≥ flushTimeout) → FLUSH (50 ticks) ✅
   → ACK: batch_0002 ✅ (now complete!)
```

**Implementation Strategy:**
- `topicPollTimeout = flushTimeout` (automatically calculated by `AbstractBatchIndexer`)
- Component used by `AbstractBatchIndexer.processBatchMessage()` and `flushAndAcknowledge()`

**TickBufferingComponent (with ACK Tracking):**
```java
/**
 * Component for buffering ticks across batches to enable efficient bulk inserts.
 * <p>
 * Tracks which ticks belong to which batch and returns ACK tokens only for
 * batches that have been fully flushed to the database. This ensures that
 * no batch is acknowledged before ALL its ticks are persisted.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@link AbstractBatchIndexer} instance creates
 * its own {@code TickBufferingComponent} in {@code createComponents()}. Components
 * are never shared between service instances or threads.
 * <p>
 * <strong>Design Rationale:</strong>
 * <ul>
 *   <li>Each service instance runs in exactly one thread</li>
 *   <li>Each service instance has its own component instances</li>
 *   <li>Underlying resources (DB, topics) are thread-safe and shared</li>
 *   <li>No need for synchronization overhead in components</li>
 * </ul>
 * <p>
 * <strong>Example:</strong> 3x DummyIndexer (competing consumers) each has own
 * TickBufferingComponent, but all share the same H2TopicReader and IMetadataReader.
 */
public class TickBufferingComponent {
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private final List<TickData> buffer = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>(); // Parallel to buffer!
    private final Map<String, BatchFlushState> pendingBatches = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    
    static class BatchFlushState {
        final Object message; // TopicMessage - generic!
        final int totalTicks;
        int ticksFlushed = 0;
        
        BatchFlushState(Object message, int totalTicks) {
            this.message = message;
            this.totalTicks = totalTicks;
        }
        
        boolean isComplete() {
            return ticksFlushed >= totalTicks;
        }
    }
    
    public TickBufferingComponent(int insertBatchSize, long flushTimeoutMs) {
        this.insertBatchSize = insertBatchSize;
        this.flushTimeoutMs = flushTimeoutMs;
    }
    
    /**
     * Add ticks from a batch and track the message for later ACK.
     */
    public <ACK> void addTicksFromBatch(List<TickData> ticks, String batchId, TopicMessage<?, ACK> message) {
        // Track batch for ACK
        if (!pendingBatches.containsKey(batchId)) {
            pendingBatches.put(batchId, new BatchFlushState(message, ticks.size()));
        }
        
        // Add ticks to buffer with batch tracking
        for (TickData tick : ticks) {
            buffer.add(tick);
            batchIds.add(batchId);
        }
    }
    
    /**
     * Check if we should flush (size or timeout).
     */
    public boolean shouldFlush() {
        if (buffer.size() >= insertBatchSize) {
            return true;
        }
        if (!buffer.isEmpty() && (System.currentTimeMillis() - lastFlushMs) >= flushTimeoutMs) {
            return true;
        }
        return false;
    }
    
    /**
     * Get ticks to flush and return completed batches for ACK.
     */
    public <ACK> FlushResult<ACK> flush() {
        if (buffer.isEmpty()) {
            return new FlushResult<>(Collections.emptyList(), Collections.emptyList());
        }
        
        int ticksToFlush = Math.min(buffer.size(), insertBatchSize);
        
        // Extract ticks and their batch IDs
        List<TickData> ticksForFlush = new ArrayList<>(buffer.subList(0, ticksToFlush));
        List<String> batchIdsForFlush = new ArrayList<>(batchIds.subList(0, ticksToFlush));
        
        // Remove from buffer
        buffer.subList(0, ticksToFlush).clear();
        batchIds.subList(0, ticksToFlush).clear();
        
        // Count ticks per batch
        Map<String, Integer> batchTickCounts = new HashMap<>();
        for (String batchId : batchIdsForFlush) {
            batchTickCounts.merge(batchId, 1, Integer::sum);
        }
        
        // Update batch flush counts and collect completed batches
        List<TopicMessage<?, ACK>> completedMessages = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : batchTickCounts.entrySet()) {
            String batchId = entry.getKey();
            int ticksFlushed = entry.getValue();
            
            BatchFlushState state = pendingBatches.get(batchId);
            state.ticksFlushed += ticksFlushed;
            
            if (state.isComplete()) {
                // Batch is fully flushed → can be ACKed!
                completedMessages.add((TopicMessage<?, ACK>) state.message);
                pendingBatches.remove(batchId);
            }
        }
        
        lastFlushMs = System.currentTimeMillis();
        
        return new FlushResult<>(ticksForFlush, completedMessages);
    }
    
    public int getBufferSize() {
        return buffer.size();
    }
    
    /**
     * Result of a flush operation.
     */
    public static class FlushResult<ACK> {
        private final List<TickData> ticks;
        private final List<TopicMessage<?, ACK>> completedMessages;
        
        public FlushResult(List<TickData> ticks, List<TopicMessage<?, ACK>> completedMessages) {
            this.ticks = List.copyOf(ticks);
            this.completedMessages = List.copyOf(completedMessages);
        }
        
        public List<TickData> ticks() { return ticks; }
        public List<TopicMessage<?, ACK>> completedMessages() { return completedMessages; }
    }
}
```

**Why topicPollTimeout = flushTimeout (automatic):**
- **Guarantees timely flush:** Buffered ticks flushed within `flushTimeout` even when no new messages arrive
- **Example:** If storage batch has 100 ticks but `insertBatchSize=250`, remaining 50 ticks after 2 batches will be flushed after `flushTimeout`
- **No manual configuration:** Always correct, no misconfiguration possible (set by `AbstractBatchIndexer`)

**Configuration (Phase 14.2.6):**
```hocon
dummyIndexer {
  resources {
    storage = "storageRead:tickStorage"
    metadata = "dbMetaRead:indexDatabase"
    topic = "topicRead:batchTopic?consumerGroup=dummyIndexer"
  }
  
  options {
    runId = ${?pipeline.services.runId}
    
    # Metadata polling (standardized!)
    metadataPollIntervalMs = 1000
    metadataMaxPollDurationMs = 300000
    
    # Tick buffering (NEW in Phase 14.2.6)
    insertBatchSize = 1000
    flushTimeoutMs = 5000
    # Note: topicPollTimeoutMs automatically set to flushTimeoutMs
    
    # Phase 14.2.7: enableIdempotency will be added
  }
}
```

**Deliverables:**
- ✅ `ComponentType.BUFFERING` enum value added to `AbstractBatchIndexer`
- ✅ `TickBufferingComponent` implementation with ACK tracking and **Thread-Safety JavaDoc**
  - `BatchFlushState` inner class for per-batch completion tracking
  - `FlushResult<ACK>` with ticks and completedMessages
  - `addTicksFromBatch()`, `shouldFlush()`, `flush()`, `getBufferSize()`, `getFlushTimeoutMs()`
- ✅ Updated final `createComponents()` to create buffering component from config
- ✅ Updated `AbstractBatchIndexer` with buffering logic (if-else branch in `processBatchMessage()`)
- ✅ Added `flushAndAcknowledge()` helper method with metric updates
- ✅ Added timeout flush check in `indexRun()` topic loop (`msg == null` case)
- ✅ Added final flush in finally block (guarantees flush on shutdown, even on interrupt!)
- ✅ Updated `BatchIndexerComponents` with buffering field and builder method
- ✅ Standardized config parameters: `insertBatchSize`, `flushTimeoutMs`
- ✅ Updated default `getRequiredComponents()` to return METADATA + BUFFERING
- ✅ Auto-set `topicPollTimeoutMs = flushTimeoutMs` when buffering enabled
- ✅ **DummyIndexer needs NO changes** - uses new default automatically!
- ✅ Unit tests: `TickBufferingComponentTest` (5 tests)
  - Size-triggered flush
  - Timeout-triggered flush
  - Cross-batch ACK tracking
  - Partial batch not ACKed
  - Empty flush
- ✅ Integration tests: `DummyIndexerIntegrationTest` (4 tests for buffering)
  - Normal flush (size trigger)
  - Timeout flush
  - Cross-batch ACK tracking (5 batches, 2 flushes)
  - Final flush on shutdown
- ⚠️ **Deferred test:** Buffer Loss Recovery (crash simulation without graceful shutdown)
  - Reason: DummyIndexer has no DB writes → cannot test MERGE idempotency
  - Will be implemented for real indexers (EnvironmentIndexer, OrganismIndexer)
  - See spec lines 2395-2434 for implementation when needed
- ✅ Configuration: `evochora.conf` with buffering config uncommented and documented

---

### Phase 14.2.7: IdempotencyComponent (Optional Performance Optimization)

**Status:** ✅ **Completed**

**Implementation Notes:**
- IdempotencyComponent is **only a performance optimization** (skip duplicate storage reads)
- MERGE already guarantees correctness (tick-level idempotency)
- Component is **optional** - DummyIndexer default remains METADATA + BUFFERING only
- Performance gain is minimal (only on rare Topic redeliveries)
- **Critical safety:** markProcessed() called ONLY AFTER ACK to prevent data loss
- Configuration provided as commented example in evochora.conf

**Goal:** Add optional `IdempotencyComponent` for performance optimization (skip duplicate storage reads).

**Critical Implementation Requirement:**

IdempotencyComponent with buffering has a **dangerous race condition** if implemented incorrectly:

```
❌ WRONG (data loss possible):
1. Batch B received → ticks added to buffer
2. markProcessed(B) called → written to DB
3. CRASH before flush/ACK
4. Topic redelivers B → isProcessed(B) returns true → storage read skipped
5. Result: Ticks lost!

✅ CORRECT (safe with buffering):
1. Batch B received → ticks added to buffer
2. Flush ticks to DB
3. ACK message
4. markProcessed(B) called → ONLY AFTER ACK!
5. CRASH anywhere: Safe (MERGE handles duplicates)
```

**Correct Implementation (when needed):**

**Step 1: Extend TickBufferingComponent.FlushResult to track completed batch IDs:**

```java
public static class FlushResult<ACK> {
    private final List<TickData> ticks;
    private final List<TopicMessage<?, ACK>> completedMessages;
    private final List<String> completedBatchIds;  // NEW: Track which batches are fully flushed
    
    public FlushResult(List<TickData> ticks, 
                       List<TopicMessage<?, ACK>> completedMessages,
                       List<String> completedBatchIds) {
        this.ticks = List.copyOf(ticks);
        this.completedMessages = List.copyOf(completedMessages);
        this.completedBatchIds = List.copyOf(completedBatchIds);
    }
    
    public List<TickData> ticks() { return ticks; }
    public List<TopicMessage<?, ACK>> completedMessages() { return completedMessages; }
    public List<String> completedBatchIds() { return completedBatchIds; }  // NEW
}
```

**Step 2: Update AbstractBatchIndexer.flushAndAcknowledge() - markProcessed AFTER ACK:**

```java
private void flushAndAcknowledge() throws Exception {
    TickBufferingComponent.FlushResult<ACK> result = components.buffering.flush();
    if (result.ticks().isEmpty()) return;
    
    // 1. Flush ticks to DB
    flushTicks(result.ticks());
    
    // 2. ACK completed batches
    for (TopicMessage<?, ACK> msg : result.completedMessages()) {
        topic.ack(msg);
    }
    
    // 3. CRITICAL: Mark processed ONLY AFTER ACK (safe!)
    if (components != null && components.idempotency != null) {
        for (String batchId : result.completedBatchIds()) {
            components.idempotency.markProcessed(batchId);
        }
    }
}
```

**Step 3: Add idempotency check in processBatchMessage (before storage read):**

```java
private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
    BatchInfo batch = msg.payload();
    String batchId = batch.getStorageKey();
    
    log.debug("Received BatchInfo: storageKey={}, ticks=[{}-{}]", ...);
    
    // Idempotency check (skip storage read if already processed)
    if (components != null && components.idempotency != null) {
        if (components.idempotency.isProcessed(batchId)) {
    log.debug("Skipping duplicate batch (performance optimization): {}", batchId);
            topic.ack(msg);
            return;
        }
    }
    
    try {
        // Storage handles length-delimited format automatically
        List<TickData> ticks = storage.readBatch(batch.getStorageKey());
        
        if (components.buffering != null) {
            components.buffering.addTicksFromBatch(ticks, batchId, msg);
            if (components.buffering.shouldFlush()) {
                flushAndAcknowledge();  // This will mark processed after ACK
            }
        } else {
            // WITHOUT buffering: Safe to mark immediately after ACK
            for (TickData tick : ticks) {
                flushTicks(List.of(tick));
            }
            topic.ack(msg);
            
            if (components.idempotency != null) {
                components.idempotency.markProcessed(batchId);
            }
        }
    } catch (Exception e) {
        log.error("Failed to process batch: {}", batchId);
        throw e;
    }
}
```

**Why this is safe:**
- `markProcessed()` only called **after flush + ACK**
- If crash before ACK: Topic redelivers → storage read → MERGE handles duplicates ✅
- If crash after ACK but before markProcessed: Next startup no redelivery, marking skipped (OK)
- If Topic bug delivers duplicate after ACK: `isProcessed()` returns true → skip storage read ✅

---

**MERGE-Based Idempotency (The Real Foundation)**

```
Problem: Batch-Level Idempotency does NOT work with cross-batch buffering!

Scenario (Crash):
1. Flush: tick_0-249 (from batch_0000, batch_0001, batch_0002 partially) → DB ✅
2. Crash → Buffer loses tick_250-299 (rest of batch_0002) ❌
3. Restart → batch_0002 is reprocessed (no ACK was sent)
   → Batch-Level Idempotency: "batch_0002 processed" → SKIP! ❌
   → tick_250-299 are LOST! ❌❌❌

Solution: Tick-Level Idempotency via MERGE!
- Every indexer uses MERGE (not INSERT!) → 100% idempotent
- On redelivery: MERGE finds existing rows → UPDATE (noop) or INSERT (missing)
- IdempotencyComponent ONLY for performance (skip storage read), NOT for correctness!
```

**Implementation Priority:**

**Phase 14.2.7 is deferred - NOT implemented initially.** Focus on MERGE-based idempotency.

**IdempotencyComponent Implementation:**
```java
/**
 * Component for optional idempotency tracking (performance optimization).
 * <p>
 * Wraps IIdempotencyTracker with indexer-specific context and provides convenient
 * methods for duplicate detection. This is a PERFORMANCE optimization only - 
 * correctness is guaranteed by MERGE statements in indexers.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Competing Consumer Pattern:</strong> Multiple service instances (competing
 * consumers) each have their own {@code IdempotencyComponent} instance. The underlying
 * {@link IIdempotencyTracker} resource IS thread-safe and coordinates duplicate
 * detection across all consumers.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@link AbstractBatchIndexer} instance creates
 * its own {@code IdempotencyComponent} in {@code createComponents()}. Components are
 * never shared between service instances or threads.
 * <p>
 * <strong>Important:</strong> This component is deferred (YAGNI). MERGE statements
 * provide 100% correctness without it. Only implement if performance monitoring shows
 * storage reads as bottleneck.
 */
public class IdempotencyComponent {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyComponent.class);
    
    private final IIdempotencyTracker tracker;
    private final String indexerClass;
    
    /**
     * Creates idempotency component.
     *
     * @param tracker Database capability for idempotency tracking (must not be null)
     * @param indexerClass Class name of the indexer for tracking scope (must not be null/blank)
     * @throws IllegalArgumentException if tracker is null or indexerClass is null/blank
     */
    public IdempotencyComponent(IIdempotencyTracker tracker, String indexerClass) {
        if (tracker == null) {
            throw new IllegalArgumentException("Tracker must not be null");
        }
        if (indexerClass == null || indexerClass.isBlank()) {
            throw new IllegalArgumentException("Indexer class must not be null or blank");
        }
        this.tracker = tracker;
        this.indexerClass = indexerClass;
    }
    
    /**
     * Checks if batch was already processed.
     * <p>
     * This is a performance optimization to skip storage reads for duplicate batches.
     * Even if this returns false, MERGE statements ensure no duplicates in database.
     * <p>
     * If the tracker fails (e.g. database error), returns false as safe default.
     * MERGE will handle duplicates anyway.
     *
     * @param batchId Batch identifier (usually storageKey)
     * @return true if batch was already processed, false otherwise or on error
     */
    public boolean isProcessed(String batchId) {
        try {
            return tracker.isProcessed(indexerClass, batchId);
        } catch (Exception e) {
            // If tracker fails, assume NOT processed (safe default)
            // MERGE will handle duplicates anyway
            log.debug("Idempotency check failed for batch {}, assuming not processed: {}", 
                     batchId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Marks batch as processed.
     * <p>
     * Should be called after ticks are buffered but before final flush. This ensures
     * future redeliveries can be skipped (performance optimization).
     * <p>
     * If marking fails (e.g. database error), logs but continues. This is not critical
     * because MERGE ensures correctness even without tracking.
     *
     * @param batchId Batch identifier (usually storageKey)
     */
    public void markProcessed(String batchId) {
        try {
            tracker.markProcessed(indexerClass, batchId);
        } catch (Exception e) {
            // If marking fails, log but continue (not critical)
            // MERGE ensures correctness even without tracking
            log.debug("Failed to mark batch {} as processed: {}", batchId, e.getMessage());
        }
    }
}
```

**Component Benefits:**
- ✅ **Convenience:** Encapsulates `indexerClass`, no boilerplate in every indexer
- ✅ **Error Handling:** Safe defaults if tracker fails (tracker errors don't break indexer!)
- ✅ **Validation:** Constructor ensures valid parameters
- ✅ **Logging:** Debug logs for troubleshooting tracker issues
- ✅ **Documentation:** Clear JavaDoc explaining it's performance-only

**Note:** `IIdempotencyTracker` interface already exists in the codebase and does NOT need to be created!

**Configuration:**
```hocon
dummyIndexer {
  resources {
    storage = "storageRead:tickStorage"
    metadata = "dbMetaRead:indexDatabase"
    topic = "topicRead:batchTopic?consumerGroup=dummyIndexer"
    idempotency = "dbIdempotency:indexDatabase"  # NEW
  }
  
  options {
    runId = ${?pipeline.services.runId}
    
    # Metadata polling (standardized!)
    metadataPollIntervalMs = 1000
    metadataMaxPollDurationMs = 300000
    
    # Tick buffering (standardized!)
    insertBatchSize = 1000
    flushTimeoutMs = 5000
    # topicPollTimeoutMs automatically set to flushTimeoutMs
    
    # Idempotency (Phase 14.2.7)
    enableIdempotency = true
  }
}
```

**MERGE Examples (Real Indexers):**

```java
// EnvironmentIndexer - 100% idempotent via MERGE
private void flushTicks(List<TickData> ticks) throws SQLException {
    String mergeSql = """
        MERGE INTO environment_data (tick_number, cell_id, x, y, z, state)
        KEY (tick_number, cell_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    
    try (PreparedStatement stmt = connection.prepareStatement(mergeSql)) {
        for (TickData tick : ticks) {
            stmt.setLong(1, tick.getTickNumber());
            stmt.setString(2, tick.getCellId());
            stmt.setInt(3, tick.getX());
            stmt.setInt(4, tick.getY());
            stmt.setInt(5, tick.getZ());
            stmt.setInt(6, tick.getState());
            stmt.addBatch();
        }
        stmt.executeBatch();
    }
}

// OrganismIndexer - 100% idempotent via MERGE
private void flushTicks(List<TickData> ticks) throws SQLException {
    String mergeSql = """
        MERGE INTO organism_data (tick_number, organism_id, x, y, energy, age)
        KEY (tick_number, organism_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    // ... similar to above
}
```

**Why MERGE is critical:**

```
Crash scenario with MERGE:
1. Buffer: [tick_0-99 (batch_0000), tick_100-199 (batch_0001), tick_200-299 (batch_0002)]
2. Flush 250 ticks → MERGE (tick_0-249) → DB ✅
3. Buffer: [tick_250-299 (batch_0002)]
4. CRASH! → Buffer lost, no ACKs sent
5. Topic redelivery: batch_0000, batch_0001, batch_0002
6. Indexer restarts and processes all 3 batches:
   - Read storage → 300 ticks
   - Flush → MERGE (tick_0-299)
     - tick_0-249: MERGE finds existing rows → UPDATE (noop, same data) ✅
     - tick_250-299: MERGE finds no rows → INSERT (missing ticks!) ✅
7. Result: ALL 300 ticks in DB, NO duplicates! ✅✅✅

Without MERGE (only INSERT):
→ tick_0-249 would fail as duplicates or be inserted twice ❌
```

**Deliverables:**
- ✅ `IdempotencyComponent` implementation with **Thread-Safety JavaDoc**
  - Wraps `IIdempotencyTracker<String>` with indexer-specific scoping
  - Safe error handling (returns false on failure, never throws)
  - Constructor validation (null checks)
- ✅ Extended `TickBufferingComponent.FlushResult` with `completedBatchIds`
  - Parallel list to track which batch IDs are fully flushed
  - Used for safe markProcessed() AFTER ACK
- ✅ Updated `AbstractBatchIndexer.flushAndAcknowledge()` - markProcessed AFTER ACK
  - Critical safety: Flush → ACK → markProcessed (prevents data loss)
- ✅ Updated `AbstractBatchIndexer.processBatchMessage()` - idempotency check before storage read
  - Skips duplicate batches (performance optimization)
  - ACKs immediately if already processed
- ✅ Updated `BatchIndexerComponents` with idempotency field and Builder
  - Added `ComponentType.IDEMPOTENCY` enum value
  - Extended component creation logic in `createComponents()`
- ✅ Unit tests: `IdempotencyComponentTest` (10 tests)
  - Constructor validation, normal operation, error handling
  - All tests pass
- ✅ Unit tests: `TickBufferingComponentTest` extended (2 new tests)
  - `completedBatchIds` verification
  - Partial batch exclusion
- ✅ Configuration: Commented examples in `evochora.conf`
  - Resource definition for `index-idempotency`
  - Service resource binding examples
- ⚠️ Integration tests: Buffer loss recovery deferred
  - Requires real indexer with DB writes (not DummyIndexer)
  - Will be implemented with EnvironmentIndexer/OrganismIndexer
  - MERGE idempotency is the real safety guarantee

**Note:** `IIdempotencyTracker` interface and `InMemoryIdempotencyTracker` implementation already exist and are used by PersistenceService!

---

### Phase 14.2.8: DlqComponent (Optional Retry Limit)

**Status:** ⚠️ **Specification ready - Implementation deferred (YAGNI)**

**Rationale for deferral:**
- Current architecture is sufficient (Topic reassignment + Service ERROR state)
- DLQ only needed for persistent, message-specific failures (poison messages)
- Added complexity not justified without real-world failure patterns
- **Recommendation:** Implement only if monitoring shows specific batches failing repeatedly

**Goal (if implemented later):** Add optional `DlqComponent` to move poison messages to DLQ after max retries.

**Use Case:**
```
Scenario: Specific batch corrupted in storage (only this batch fails, DB is healthy)

Without DLQ:
1. Consumer A: poll() → batch_X → parse error → Exception → NO ACK
2. Topic: claimTimeout → reassign to Consumer B
3. Consumer B: poll() → batch_X → parse error → Exception → NO ACK
4. → Endless rotation, poison message blocks consumers periodically

With DLQ Component:
1. Consumer A: poll() → batch_X → Exception → retry_count=1 → NO ACK
2. Consumer B: poll() → batch_X → Exception → retry_count=2 → NO ACK
3. Consumer C: poll() → batch_X → Exception → retry_count=3 → DLQ!
4. batch_X moved to DLQ, ACKed from topic → pipeline continues ✅
```

**Architecture:**

DlqComponent requires **shared retry tracking** across competing consumers:

```
3x DummyIndexer (competing consumers)
        ↓
  RetryTracker (shared DB/memory)
        ↓
    count=1, count=2, count=3 → DLQ
```

**New Resource Interface:**

```java
/**
 * Resource for tracking retry counts across competing consumers.
 * <p>
 * Backed by shared storage (in-memory or database) to coordinate
 * retry counting between multiple service instances.
 * <p>
 * <strong>Thread Safety:</strong> Implementations must be thread-safe for
 * concurrent access from multiple consumers.
 */
public interface IRetryTracker extends IResource {
    
    /**
     * Increments and returns retry count for a message.
     * <p>
     * Thread-safe across competing consumers. Each call increments the
     * shared counter, regardless of which consumer instance makes the call.
     *
     * @param messageId Unique message identifier
     * @return Current retry count after increment
     * @throws Exception if tracking fails
     */
    int incrementAndGetRetryCount(String messageId) throws Exception;
    
    /**
     * Gets current retry count without incrementing.
     *
     * @param messageId Unique message identifier
     * @return Current retry count
     * @throws Exception if tracking fails
     */
    int getRetryCount(String messageId) throws Exception;
    
    /**
     * Marks message as moved to DLQ (stops further tracking).
     *
     * @param messageId Unique message identifier
     * @throws Exception if marking fails
     */
    void markMovedToDlq(String messageId) throws Exception;
    
    /**
     * Resets retry count after successful processing.
     * <p>
     * Called when message is successfully processed to prevent false
     * positives from earlier transient failures.
     *
     * @param messageId Unique message identifier
     * @throws Exception if reset fails
     */
    void resetRetryCount(String messageId) throws Exception;
}
```

**InMemoryRetryTracker Implementation:**

```java
/**
 * In-memory implementation of retry tracker.
 * <p>
 * Suitable for single-instance deployments and development/testing.
 * <p>
 * <strong>Limitation:</strong> Retry counts are lost on restart.
 * For production with multiple instances, use H2RetryTracker.
 */
public class InMemoryRetryTracker extends AbstractResource implements IRetryTracker {
    
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRetryAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> movedToDlq = new ConcurrentHashMap<>();
    
    public InMemoryRetryTracker(String name, Config options) {
        super(name, options);
    }
    
    @Override
    public int incrementAndGetRetryCount(String messageId) {
        lastRetryAt.put(messageId, System.currentTimeMillis());
        return retryCounts.computeIfAbsent(messageId, k -> new AtomicInteger(0))
            .incrementAndGet();
    }
    
    @Override
    public int getRetryCount(String messageId) {
        AtomicInteger count = retryCounts.get(messageId);
        return count != null ? count.get() : 0;
    }
    
    @Override
    public void markMovedToDlq(String messageId) {
        movedToDlq.put(messageId, true);
    }
    
    @Override
    public void resetRetryCount(String messageId) {
        retryCounts.remove(messageId);
        lastRetryAt.remove(messageId);
    }
    
    @Override
    protected void onStart() {
        log.debug("InMemoryRetryTracker '{}' started", getResourceName());
    }
    
    @Override
    protected void onClose() {
        log.debug("InMemoryRetryTracker '{}' closed, tracked {} messages", 
            getResourceName(), retryCounts.size());
        retryCounts.clear();
        lastRetryAt.clear();
        movedToDlq.clear();
    }
}
```

**DlqComponent Implementation:**

```java
/**
 * Component for moving poison messages to Dead Letter Queue after max retries.
 * <p>
 * Tracks retry counts across competing consumers using shared {@link IRetryTracker}.
 * When a message fails repeatedly, moves it to DLQ to prevent blocking the pipeline.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Competing Consumer Pattern:</strong> Multiple service instances (competing
 * consumers) each have their own {@code DlqComponent} instance. The underlying
 * {@link IRetryTracker} and {@link IDeadLetterQueue} resources ARE thread-safe
 * and coordinate retry counting across all consumers.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@link AbstractBatchIndexer} instance creates
 * its own {@code DlqComponent} in {@code createComponents()}. Components are never
 * shared between service instances or threads.
 */
public class DlqComponent<T extends Message, ACK> {
    private static final Logger log = LoggerFactory.getLogger(DlqComponent.class);
    
    private final IRetryTracker retryTracker;
    private final IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq;
    private final int maxRetries;
    private final String indexerName;
    
    /**
     * Creates DLQ component.
     *
     * @param retryTracker Shared retry tracker (must not be null)
     * @param dlq Dead letter queue resource (must not be null)
     * @param maxRetries Maximum retries before moving to DLQ (must be positive)
     * @param indexerName Name of indexer for metadata (must not be null/blank)
     */
    public DlqComponent(IRetryTracker retryTracker,
                        IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq,
                        int maxRetries,
                        String indexerName) {
        if (retryTracker == null) {
            throw new IllegalArgumentException("RetryTracker must not be null");
        }
        if (dlq == null) {
            throw new IllegalArgumentException("DLQ must not be null");
        }
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("MaxRetries must be positive");
        }
        if (indexerName == null || indexerName.isBlank()) {
            throw new IllegalArgumentException("Indexer name must not be null or blank");
        }
        
        this.retryTracker = retryTracker;
        this.dlq = dlq;
        this.maxRetries = maxRetries;
        this.indexerName = indexerName;
    }
    
    /**
     * Check if message should be moved to DLQ.
     * <p>
     * Increments retry count (shared across competing consumers).
     * Returns true if retry limit exceeded.
     * <p>
     * If tracking fails, returns false as safe default (retry again).
     *
     * @param messageId Unique message identifier
     * @return true if message should be moved to DLQ
     */
    public boolean shouldMoveToDlq(String messageId) {
        try {
            int retries = retryTracker.incrementAndGetRetryCount(messageId);
            return retries > maxRetries;
        } catch (Exception e) {
            log.debug("Retry tracking failed for {}, assuming not at limit: {}", 
                messageId, e.getMessage());
            return false;  // Safe default - retry again
        }
    }
    
    /**
     * Move message to DLQ with error metadata.
     * <p>
     * Wraps original message in DeadLetterMessage with error details
     * and retry count. Uses existing DLQ resource infrastructure.
     *
     * @param message Original topic message
     * @param error Exception that caused the failure
     * @param storageKey Storage key for metadata
     * @throws InterruptedException if DLQ write is interrupted
     */
    public void moveToDlq(TopicMessage<T, ACK> message, 
                          Exception error,
                          String storageKey) throws InterruptedException {
        try {
            int retries = retryTracker.getRetryCount(storageKey);
            
            // Build stack trace (limit to 10 lines)
            List<String> stackTraceLines = new ArrayList<>();
            if (error != null) {
                for (StackTraceElement element : error.getStackTrace()) {
                    stackTraceLines.add(element.toString());
                    if (stackTraceLines.size() >= 10) break;
                }
            }
            
            // Build DLQ message (reuse existing DeadLetterMessage protocol!)
            SystemContracts.DeadLetterMessage dlqMsg = 
                SystemContracts.DeadLetterMessage.newBuilder()
                    .setOriginalMessage(message.payload().toByteString())
                    .setMessageType(message.payload().getClass().getName())
                    .setFailureReason(error.getClass().getName() + ": " + error.getMessage())
                    .setRetryCount(retries)
                    .setSourceService(indexerName)
                    .setFirstFailureTimeMs(System.currentTimeMillis())  // Approximation
                    .setLastFailureTimeMs(System.currentTimeMillis())
                    .addAllStackTraceLines(stackTraceLines)
                    .build();
            
            dlq.put(dlqMsg);
            retryTracker.markMovedToDlq(storageKey);
            
            log.warn("Moved message to DLQ after {} retries: {}", retries, storageKey);
            
        } catch (Exception e) {
            log.warn("Failed to move message to DLQ: {}", storageKey);
            throw new InterruptedException("DLQ write failed");
        }
    }
    
    /**
     * Reset retry count after successful processing.
     * <p>
     * Should be called after successful flush to clear retry history.
     * This prevents false positives from earlier transient failures.
     *
     * @param messageId Unique message identifier
     */
    public void resetRetryCount(String messageId) {
        try {
            retryTracker.resetRetryCount(messageId);
        } catch (Exception e) {
            log.debug("Failed to reset retry count for {}: {}", messageId, e.getMessage());
            // Not critical - next successful processing will reset
        }
    }
}
```

**AbstractBatchIndexer Integration:**

```java
private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
    BatchInfo batch = msg.payload();
    String batchId = batch.getStorageKey();
    
    log.debug("Received BatchInfo: storageKey={}, ticks=[{}-{}]", ...);
    
    try {
        // Storage handles length-delimited format automatically
        List<TickData> ticks = storage.readBatch(batch.getStorageKey());
        
        if (components != null && components.buffering != null) {
            components.buffering.addTicksFromBatch(ticks, batchId, msg);
            if (components.buffering.shouldFlush()) {
                flushAndAcknowledge();
            }
        } else {
            for (TickData tick : ticks) {
                flushTicks(List.of(tick));
            }
            topic.ack(msg);
        }
        
        // Success - reset retry count if DLQ component exists
        if (components != null && components.dlq != null) {
            components.dlq.resetRetryCount(batchId);
        }
        
    } catch (Exception e) {
        log.error("Failed to process batch: {}", batchId);
        
        // DLQ check (if component configured)
        if (components != null && components.dlq != null) {
            if (components.dlq.shouldMoveToDlq(batchId)) {
                log.warn("Moving batch to DLQ after max retries: {}", batchId);
                components.dlq.moveToDlq(msg, e, batchId);
                topic.ack(msg);  // ACK original - now in DLQ
                return;  // Don't rethrow
            }
        }
        
        throw e;  // NO ACK - redelivery
    }
}
```

**BatchIndexerComponents (Phase 14.2.8):**

```java
public static class BatchIndexerComponents {
    public final MetadataReadingComponent metadata;     // Optional
    public final TickBufferingComponent buffering;      // Optional
    public final DlqComponent dlq;                      // Optional (Phase 14.2.8)
    
    private BatchIndexerComponents(MetadataReadingComponent metadata,
                                   TickBufferingComponent buffering,
                                   DlqComponent dlq) {
        this.metadata = metadata;
        this.buffering = buffering;
        this.dlq = dlq;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private MetadataReadingComponent metadata;
        private TickBufferingComponent buffering;
        private DlqComponent dlq;
        
        public Builder withMetadata(MetadataReadingComponent c) {
            this.metadata = c;
            return this;
        }
        
        public Builder withBuffering(TickBufferingComponent c) {
            this.buffering = c;
            return this;
        }
        
        public Builder withDlq(DlqComponent c) {
            this.dlq = c;
            return this;
        }
        
        public BatchIndexerComponents build() {
            return new BatchIndexerComponents(metadata, buffering, dlq);
        }
    }
}
```

**Configuration:**

```hocon
resources {
  # Shared retry tracker (single instance for all indexers)
  indexerRetries {
    className = "org.evochora.datapipeline.resources.InMemoryRetryTracker"
    options {
      # No maxRetries here - that's service-level policy!
    }
  }
  
  # DLQ (reuse existing implementation!)
  indexerDlq {
    className = "org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue"
    options {
      capacity = 10000
    }
  }
}

services {
  dummyIndexer {
    resources {
      topic = "topicRead:batchTopic?consumerGroup=dummyIndexer"
      storage = "storageRead:tickStorage"
      metadata = "dbMetaRead:indexDatabase"
      retryTracker = "retryTracker:indexerRetries"  # NEW
      dlq = "dlq:indexerDlq"                        # NEW
    }
    
    options {
      # Metadata polling (standardized!)
      metadataPollIntervalMs = 1000
      metadataMaxPollDurationMs = 300000
      
      # Buffering (standardized!)
      insertBatchSize = 1000
      flushTimeoutMs = 5000
      
      # DLQ (standardized - service-level policy!)
      maxRetries = 3  # After 3 retries across ALL consumers → DLQ
    }
  }
  
  environmentIndexer {
    resources {
      # ... same retryTracker, same dlq ...
    }
    options {
      # Same standardized config names!
      metadataPollIntervalMs = 1000
      metadataMaxPollDurationMs = 300000
      insertBatchSize = 1000
      flushTimeoutMs = 5000
      maxRetries = 5  # Different policy for environment data!
    }
  }
}
```

**DummyIndexer (Phase 14.2.8):**

```java
@Override
protected Set<ComponentType> getRequiredComponents() {
    // Phase 14.2.8: Add DLQ to standard defaults
    return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING, ComponentType.DLQ);
}

// Note: To use DLQ, the service must:
// 1. Configure retryTracker and dlq resources
// 2. Set maxRetries in options
// The final createComponents() in AbstractBatchIndexer automatically creates DlqComponent
```

**Why maxRetries in Service Options (not Resource):**
- ✅ **Flexible:** Different indexers can have different retry policies
- ✅ **Separation of Concerns:** RetryTracker counts, Service decides policy
- ✅ **Reusable:** One RetryTracker shared by all indexers with different limits
- ✅ **Standard Pattern:** Like database timeout (DB resource shared, timeout per service)

**Deliverables (if implemented later):**
- `IRetryTracker` interface with **Thread-Safety JavaDoc**
- `InMemoryRetryTracker` implementation (single-instance, development)
- `DlqComponent` implementation with **Thread-Safety JavaDoc** (uses existing DLQ infrastructure!)
- Updated `AbstractBatchIndexer.processBatchMessage()` with DLQ logic
- Updated `BatchIndexerComponents` with DLQ field
- Integration tests (poison message handling, competing consumer retry tracking, DLQ overflow)

**Current Priority:** Focus on Phase 14.2.5 and 14.2.6. Phase 14.2.8 only if monitoring shows poison messages.

**Note:** 
- `IOutputQueueResource<DeadLetterMessage>` interface already exists!
- `InMemoryDeadLetterQueue` implementation already exists!
- Only `IRetryTracker` and `DlqComponent` are new

---

## Component Architecture Summary

### Components Overview

| Component | Purpose | Location | When to use? |
|-----------|---------|----------|--------------|
| `MetadataReadingComponent` | Polls DB for metadata, caches `samplingInterval` | In `AbstractBatchIndexer` | **Optional:** Most batch indexers need it |
| `TickBufferingComponent` | Buffers ticks + ACK tracking, triggers flush | In `AbstractBatchIndexer` | **Optional:** For large batch inserts |
| `DlqComponent` | Move poison messages to DLQ after max retries | In `AbstractBatchIndexer` | **Optional (deferred):** Only for poison message handling |

**All components are optional!** Each batch indexer decides in its constructor which components to use.

**Critical Notes:**
- **TickBufferingComponent:** If used, tracks which ticks belong to which batch, returns ACKs ONLY for fully flushed batches (configurable `insertBatchSize`, e.g., 250)
- **Without TickBufferingComponent:** Each **tick** processed individually (`insertBatchSize=1`), then **ONE ACK** sent after all ticks from BatchFile are processed
- **ACK Guarantee:** In both modes, ACK is sent ONLY after ALL ticks from a BatchFile are successfully flushed to DB (atomicity per BatchFile!)
- **MERGE:** ALL indexers MUST use MERGE (not INSERT) for 100% idempotent processing
- **insertBatchSize is independent of batchFileSize:** Buffering can combine ticks from multiple BatchFiles (e.g., 3x 100-tick files → 1x 250-tick flush)

**Thread Safety:**
- **Components (NOT thread-safe):** `MetadataReadingComponent`, `TickBufferingComponent`, `DlqComponent`, `IdempotencyComponent` are **NOT thread-safe**
- **Design Pattern:** Each service instance creates its own component instances in `createComponents()` - never shared between threads
- **Resources (thread-safe):** Underlying resources (`IMetadataReader`, `IRetryTracker`, `IDeadLetterQueue`, `H2TopicReader`) **ARE thread-safe** and shared across competing consumers
- **Why this works:** Each indexer service runs in exactly one thread with its own components, accessing shared thread-safe resources
- **Example:** 3x DummyIndexer (competing consumers) → each has own `TickBufferingComponent`, all share same `IMetadataReader` ✅

### Component Decision Guide

**What components should my indexer use?**

| Indexer Type | METADATA | BUFFERING | DLQ | Example |
|--------------|----------|-----------|-----|---------|
| **Production Indexer** | ✅ Yes (default) | ✅ Yes (default) | ⚠️ If needed | EnvironmentIndexer, OrganismIndexer |
| **Simple Indexer** | ✅ Yes (default) | ❌ No | ❌ No | LoggingIndexer, MetricsIndexer |
| **Test/Debug Indexer** | ✅ Yes (default) | ✅ Yes (default) | ❌ No | DummyIndexer |
| **Minimal Indexer** | ❌ No | ❌ No | ❌ No | CustomBatchProcessor |

**Component Usage Patterns:**

```java
// Pattern 1: Standard (METADATA + BUFFERING) - Most common!
@Override
protected Set<ComponentType> getRequiredComponents() {
    return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
}

// Pattern 2: Metadata only (no buffering)
@Override
protected Set<ComponentType> getRequiredComponents() {
    return EnumSet.of(ComponentType.METADATA);
}

// Pattern 3: With DLQ (for poison message handling)
@Override
protected Set<ComponentType> getRequiredComponents() {
    return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING, ComponentType.DLQ);
}

// Pattern 4: Minimal (no components)
@Override
protected Set<ComponentType> getRequiredComponents() {
    return EnumSet.noneOf(ComponentType.class);
}
```

**When to use each component:**

| Component | Use when... | Skip when... |
|-----------|-------------|--------------|
| **METADATA** | Indexer needs simulation metadata (dimensions, sampling interval) | Indexer is self-contained (no metadata dependency) |
| **BUFFERING** | High throughput, batch inserts, production environment | Low throughput, simplicity preferred, debugging |
| **DLQ** | Poison messages possible (corrupted data, parsing errors) | All data is trusted, no message-specific failures expected |

**Standardized Config Parameters:**

```hocon
indexer {
  options {
    # METADATA component
    metadataPollIntervalMs = 1000      # How often to poll for metadata
    metadataMaxPollDurationMs = 300000 # Max wait for metadata (5 min)
    
    # BUFFERING component
    insertBatchSize = 1000             # Flush after N ticks
    flushTimeoutMs = 5000              # Flush after N ms
    
    # DLQ component
    maxRetries = 3                     # Move to DLQ after N retries
  }
}
```

### Indexer Examples

**MetadataIndexer (direct from AbstractIndexer):**
```java
public class MetadataIndexer<ACK> extends AbstractIndexer<MetadataInfo, ACK> {
    private final IMetadataWriter database;
    private final int topicPollTimeoutMs;
    
    public MetadataIndexer(...) {
        super(...);
        this.database = getRequiredResource("database", IMetadataWriter.class);
        this.topicPollTimeoutMs = options.getInt("topicPollTimeoutMs", 30000);
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Simple: poll topic, write to database, ACK immediately
        TopicMessage<MetadataInfo, ACK> msg = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);
        if (msg == null) {
            throw new TimeoutException("Metadata notification timeout");
        }
        
        MetadataInfo info = msg.message();
        byte[] data = storage.readMetadataFile(info.getStorageKey());
        SimulationMetadata metadata = SimulationMetadata.parseFrom(data);
        database.insertMetadata(runId, metadata);
        topic.acknowledge(msg.acknowledgment());
    }
}
```

**DummyIndexer (extends AbstractBatchIndexer, implements getRequiredComponents + flushTicks):**
```java
public class DummyIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // Use standard defaults: METADATA + BUFFERING
        return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) {
        log.debug("Flushed {} ticks (DummyIndexer: no DB writes)", ticks.size());
    }
}
```

**EnvironmentIndexer (production with buffering):**
```java
public class EnvironmentIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    private final IEnvironmentDatabase database;
    
    public EnvironmentIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IEnvironmentDatabase.class);
    }
    
    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // Use standard defaults: METADATA + BUFFERING
        return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws SQLException {
        // CRITICAL: MERGE instead of INSERT - 100% idempotent!
        String mergeSql = """
            MERGE INTO environment_data (tick_number, cell_id, x, y, z, state)
            KEY (tick_number, cell_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = database.getConnection().prepareStatement(mergeSql)) {
            for (TickData tick : ticks) {
                stmt.setLong(1, tick.getTickNumber());
                stmt.setString(2, tick.getCellId());
                stmt.setInt(3, tick.getX());
                stmt.setInt(4, tick.getY());
                stmt.setInt(5, tick.getZ());
                stmt.setInt(6, tick.getState());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }
}
```

**SimpleBatchIndexer (without buffering - direct processing):**
```java
public class SimpleBatchIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    private final ISimpleDatabase database;
    
    public SimpleBatchIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", ISimpleDatabase.class);
    }
    
    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // Metadata only, no buffering
        return EnumSet.of(ComponentType.METADATA);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws SQLException {
        // Called per tick (ticks.size() will always be 1 without buffering)
        // MERGE ensures idempotency
        String mergeSql = """
            MERGE INTO simple_data (tick_number, value)
            KEY (tick_number)
            VALUES (?, ?)
            """;
        try (PreparedStatement stmt = database.getConnection().prepareStatement(mergeSql)) {
            for (TickData tick : ticks) {
                stmt.setLong(1, tick.getTickNumber());
                stmt.setInt(2, tick.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }
}
```

**MinimalBatchIndexer (no components at all):**
```java
public class MinimalBatchIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    public MinimalBatchIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // No components - minimal indexer
        return EnumSet.noneOf(ComponentType.class);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) {
        // Called per tick (ticks.size() will always be 1 without buffering)
        log.info("Processed {} ticks from batch", ticks.size());
    }
}
```

### Design Benefits

✅ **3-Level Architecture:** Clear separation: AbstractIndexer (core) → AbstractBatchIndexer (batch logic) → Concrete Indexers (only `getRequiredComponents()` + `flushTicks()`)  
✅ **Zero Boilerplate:** Batch indexers only implement two template methods, no complex constructor logic  
✅ **Standardized Config:** All indexers use same config parameter names (`metadataPollIntervalMs`, `insertBatchSize`, `maxRetries`)  
✅ **Component Declaration Pattern:** Subclasses declare which components to use via EnumSet, AbstractBatchIndexer creates them  
✅ **DRY (Don't Repeat Yourself):** Component creation logic and batch-processing logic centralized in `AbstractBatchIndexer`  
✅ **Builder Pattern:** Fluent API with `final` fields - new components can be added without breaking existing indexers  
✅ **Maximum Flexibility:** All components optional - from minimal (no components) to full-featured (metadata + buffering + DLQ)  
✅ **Two Modes:** With buffering (cross-batch) or without buffering (tick-by-tick processing)  
✅ **Simplicity:** `AbstractIndexer` stays minimal (no business logic)  
✅ **Testability:** Components tested separately, `AbstractBatchIndexer` tested once  
✅ **100% Idempotent:** MERGE guarantees no duplicates, even after crash + redelivery  
✅ **Safe ACKs:** With buffering: ACK only after FULL batch flush; Without: ACK immediately  
✅ **Performance:** Optional IdempotencyComponent skips storage reads for duplicates

### Topic Poll Strategy: `poll()` vs. `receive()`

**All indexers use `topic.poll(timeout)` instead of `topic.receive()`:**

| Indexer | Poll Timeout | Configuration |
|---------|--------------|---------------|
| `MetadataIndexer` | 30s (default) | `topicPollTimeoutMs` (configurable for fail-fast) |
| `DummyIndexer` / others | = `flushTimeout` | **Automatic:** `topicPollTimeoutMs = flushTimeoutMs` (no manual config) |

**Why NOT `receive()` (blocks indefinitely)?**

1. **Flush Problem:** Cannot trigger timeout-based flush with unbounded blocking
   ```
   Storage: 3 batches × 100 ticks, insertBatchSize=250
   
   With poll(flushTimeout):
   - Process 2 batches → buffer: 200 ticks
   - poll() timeout → flush 200 ticks ✅
   
   With receive():
   - Process 2 batches → buffer: 200 ticks
   - receive() blocks forever (no messages) → 200 ticks stuck! ❌
   ```

2. **Shutdown Problem:** Unpredictable shutdown time
   ```
   With poll(5s): Max 5s until shutdown ✅
   With receive(): Waits until next message (could be hours!) ❌
   ```

3. **Consistency:** Uniform pattern for all indexers

**topicPollTimeout Calculation:**

- **MetadataIndexer:** Explicitly configured (`topicPollTimeoutMs`, default: 30s) for fail-fast
- **Batch Indexers:** Automatically set to `flushTimeout` (no manual configuration)
  - `topicPollTimeoutMs = flushTimeoutMs` in constructor
  - Guarantees buffered ticks flushed within `flushTimeout` even without new messages
  - Simplifies configuration and prevents misconfiguration

## Configuration Example

```hocon
resources {
  # Topics (H2-based for Phase 14.2)
  metadata-topic {
    className = "org.evochora.datapipeline.resources.topics.H2TopicResource"
    options {
      jdbcUrl = "jdbc:h2:${user.home}/evochora/data/evochora;MODE=PostgreSQL;AUTO_SERVER=TRUE"
      username = "sa"
      password = ""
      claimTimeout = 300
    }
  }
  
  batch-topic {
    className = "org.evochora.datapipeline.resources.topics.H2TopicResource"
    options {
      jdbcUrl = "jdbc:h2:${user.home}/evochora/data/evochora;MODE=PostgreSQL;AUTO_SERVER=TRUE"
      username = "sa"
      password = ""
      claimTimeout = 300
    }
  }
}

services {
  # Writer (Phase 14.2.2) ✅
  metadata-persistence-service {
    className = "org.evochora.datapipeline.services.MetadataPersistenceService"
    resources {
      metadata-out = "topic-write:metadata-topic"
    }
  }
  
  # Reader (Phase 14.2.3) ✅
  metadata-indexer {
    className = "org.evochora.datapipeline.services.indexers.MetadataIndexer"
    resources {
      metadata-in = "topic-read:metadata-topic?consumerGroup=metadata-indexer"
    }
  }
  
  # Writer (Phase 14.2.4)
  persistence-service-1 {
    className = "org.evochora.datapipeline.services.PersistenceService"
    resources {
      batch-out = "topic-write:batch-topic"
    }
  }
  
  # Reader (Phase 14.2.5-14.2.6)
  dummyIndexer {
    className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
    resources {
      topic = "topicRead:batchTopic?consumerGroup=dummyIndexer"
      storage = "storageRead:tickStorage"
      metadata = "dbMetaRead:indexDatabase"
    }
    options {
      runId = ${?pipeline.services.runId}
      
      # Metadata polling (standardized!)
      metadataPollIntervalMs = 1000
      metadataMaxPollDurationMs = 300000
      
      # Tick buffering (Phase 14.2.6, standardized!)
      insertBatchSize = 1000
      flushTimeoutMs = 5000
      # Note: topicPollTimeoutMs automatically set to flushTimeoutMs (no manual config)
    }
  }
}
```

## Testing Strategy

**Each phase includes:**
- Unit tests for changed components
- Integration tests for end-to-end flow
- No `Thread.sleep()` - use Awaitility
- Proper cleanup (H2 databases, temp files)
- Specific `@ExpectLog` patterns for WARN/ERROR

**DummyIndexer Evolution:**
- Phase 14.2.5: ✅ Metadata wait + Storage read + Tick-by-tick processing (log-only)
- Phase 14.2.6: ✅ Buffering component enabled by default (cross-batch ACK tracking)

### Critical Test Cases for Phase 14.2.6 (Buffering)

**1. Normal Flush (Size Trigger):**
- Send batches until buffer size >= insertBatchSize
- Verify flush triggered automatically
- Verify only completed batches are ACKed

**2. Timeout Flush:**
- Send partial batch (< insertBatchSize)
- Wait for flushTimeoutMs
- Verify flush triggered by timeout
- Verify incomplete batch is NOT ACKed

**3. Cross-Batch ACK Tracking:**
- Send 3x 100-tick batches (insertBatchSize=250)
- Verify flush after 3rd batch
- Verify batch_0 and batch_1 ACKed (fully flushed)
- Verify batch_2 NOT ACKed (only 50 ticks flushed)
- Send more ticks to complete batch_2
- Verify batch_2 ACKed after completion

**4. Final Flush (Service Shutdown) - HIGH PRIORITY ⚠️**
- Send partial batch (< insertBatchSize, < flushTimeoutMs)
- Stop service (Thread.interrupt())
- Verify final flush executed
- Verify all ticks persisted
- Verify partial batches NOT ACKed (correct behavior!)
- Verify redelivery works after restart

**Rationale:** This is the most critical test because:
- ✅ Prevents data loss on graceful shutdown
- ✅ Production-relevant: services restart frequently
- ✅ Edge-case: normal flow (full buffer) is tested automatically, but shutdown is not
- ✅ ACK semantics: verifies partial batches are correctly NOT acknowledged

**5. Buffer Loss Recovery (Crash Simulation) - ⚠️ Deferred for Real Indexers:**
- **Status:** Not implemented for DummyIndexer (no DB writes to test MERGE)
- **When to implement:** When creating EnvironmentIndexer or OrganismIndexer
- **Test scenario:**
  - Send 3 batches, add to buffer
  - "Crash" before flush (don't call flush, just exit)
  - Restart service
  - Verify batches redelivered (not ACKed)
  - **Verify MERGE prevents duplicates** (requires real DB writes!)
  - Verify all ticks present exactly once in database

**Test Example (Final Flush on Shutdown):**

```java
@Test
@Tag("integration")
void testFinalFlushOnShutdown() throws Exception {
    // Setup: insertBatchSize=1000, send 3x 100-tick batches = 300 ticks in buffer
    Config config = ConfigFactory.parseString("""
        insertBatchSize = 1000
        flushTimeoutMs = 10000
        metadataPollIntervalMs = 100
        metadataMaxPollDurationMs = 5000
        """);
    
    DummyIndexer indexer = createIndexer(config);
    indexer.start();
    
    // Send metadata first
    sendMetadata(runId);
    await().atMost(2, SECONDS).until(() -> metadataLoaded());
    
    // Send 3 small batches (total 300 ticks, < insertBatchSize=1000)
    sendBatch(runId, "batch_0", 0, 100);    // 100 ticks
    sendBatch(runId, "batch_1", 100, 100);  // 200 ticks
    sendBatch(runId, "batch_2", 200, 100);  // 300 ticks
    
    // Wait for all batches to be buffered
    await().atMost(2, SECONDS).until(() -> getBufferSize() == 300);
    
    // Verify NO flush yet (buffer not full, timeout not reached)
    assertThat(getFlushedTickCount()).isEqualTo(0);
    assertThat(getAckedBatchIds()).isEmpty();
    
    // Stop service gracefully (triggers final flush)
    indexer.stop();
    await().atMost(2, SECONDS).until(() -> indexer.getState() == ServiceState.STOPPED);
    
    // CRITICAL: Verify final flush executed
    assertThat(getFlushedTickCount()).isEqualTo(300);
    
    // CRITICAL: Verify partial batches NOT ACKed
    // (Each batch has 100 ticks but only 300/1000 of insertBatchSize flushed)
    // Batches are incomplete → must be redelivered on restart
    assertThat(getAckedBatchIds()).isEmpty();
    
    // Verify redelivery works after restart
    indexer.start();
    await().atMost(2, SECONDS).until(() -> getBufferSize() == 300);  // Re-buffered
    
    // CRITICAL: Verify MERGE prevents duplicates (ticks already in DB from final flush)
    // After second flush, all ticks should exist exactly once
    sendBatch(runId, "batch_3", 300, 700);  // Complete to 1000 → trigger flush
    await().atMost(2, SECONDS).until(() -> getFlushedTickCount() == 1000);
    
    // Verify no duplicates in database (if this was real DB indexer)
    // For DummyIndexer: just verify flush was called with correct tick count
}
```

**Test Example (Buffer Loss Recovery) - ⚠️ Implement for Real Indexers:**

```java
// TODO: Implement this test for EnvironmentIndexer/OrganismIndexer
// Cannot test MERGE idempotency with DummyIndexer (no DB writes)
@Test
@Tag("integration")
void testBufferLossRecovery() throws Exception {
    DummyIndexer indexer = createIndexer();
    indexer.start();
    
    sendMetadata(runId);
    await().until(() -> metadataLoaded());
    
    // Send 3 batches, add to buffer
    sendBatch(runId, "batch_0", 0, 100);
    sendBatch(runId, "batch_1", 100, 100);
    sendBatch(runId, "batch_2", 200, 100);
    await().until(() -> getBufferSize() == 300);
    
    // CRASH before flush (kill thread without graceful shutdown)
    indexer.stopImmediately();  // No final flush!
    
    // Verify ticks NOT flushed, batches NOT ACKed
    assertThat(getFlushedTickCount()).isEqualTo(0);
    assertThat(getAckedBatchIds()).isEmpty();
    
    // Restart service
    indexer = createIndexer();
    indexer.start();
    await().until(() -> metadataLoaded());
    
    // Verify batches redelivered (topic sees they were never ACKed)
    await().atMost(5, SECONDS).until(() -> getBufferSize() == 300);
    
    // Complete buffer to trigger flush
    sendBatch(runId, "batch_3", 300, 700);
    await().until(() -> getFlushedTickCount() == 1000);
    
    // CRITICAL: Verify MERGE ensures no duplicates
    // (In real indexer: query DB and verify each tick exists exactly once)
}
```

**Test Example (Cross-Batch ACK Tracking):**

```java
@Test
@Tag("integration")
void testCrossBatchAckTracking() throws Exception {
    // insertBatchSize=250, batches are 100 ticks each
    Config config = ConfigFactory.parseString("insertBatchSize = 250");
    DummyIndexer indexer = createIndexer(config);
    indexer.start();
    
    sendMetadata(runId);
    await().until(() -> metadataLoaded());
    
    // Send batch_0: 100 ticks (buffer: 100)
    sendBatch(runId, "batch_0", 0, 100);
    await().until(() -> getBufferSize() == 100);
    assertThat(getAckedBatchIds()).isEmpty();  // No flush yet
    
    // Send batch_1: 100 ticks (buffer: 200)
    sendBatch(runId, "batch_1", 100, 100);
    await().until(() -> getBufferSize() == 200);
    assertThat(getAckedBatchIds()).isEmpty();  // Still no flush
    
    // Send batch_2: 100 ticks (buffer: 300 > 250 → FLUSH!)
    sendBatch(runId, "batch_2", 200, 100);
    await().atMost(2, SECONDS).until(() -> getFlushedTickCount() == 250);
    
    // CRITICAL: Verify only FULLY flushed batches ACKed
    await().until(() -> getAckedBatchIds().size() == 2);
    assertThat(getAckedBatchIds()).containsExactlyInAnyOrder("batch_0", "batch_1");
    assertThat(getAckedBatchIds()).doesNotContain("batch_2");  // Only 50/100 flushed!
    
    // Remaining buffer: 50 ticks from batch_2
    assertThat(getBufferSize()).isEqualTo(50);
    
    // Send batch_3: 100 ticks (buffer: 150)
    sendBatch(runId, "batch_3", 300, 100);
    await().until(() -> getBufferSize() == 150);
    
    // Send batch_4: 100 ticks (buffer: 250 → FLUSH!)
    sendBatch(runId, "batch_4", 400, 100);
    await().until(() -> getFlushedTickCount() == 500);
    
    // CRITICAL: Now batch_2 should be ACKed (all 100 ticks flushed)
    await().until(() -> getAckedBatchIds().contains("batch_2"));
    assertThat(getAckedBatchIds()).contains("batch_0", "batch_1", "batch_2");
    assertThat(getAckedBatchIds()).doesNotContain("batch_3", "batch_4");
}
```

## Logging Strategy

**All phases follow:**
- Single-line logs only
- No phase/version prefixes
- DEBUG for normal operations (in loops!)
- INFO for lifecycle only (start/stop)
- WARN for recoverable errors
- ERROR only for fatal errors

**Example:**
```
DEBUG Received BatchInfo: storageKey=batch_0000000000_0000009999.pb, ticks=[0, 9999]
DEBUG Buffered 10000 ticks from batch_0000000000_0000009999.pb, buffer size: 10000
DEBUG Flushed 10000 ticks (DummyIndexer: no DB writes)
```

## Success Criteria

Upon completion of Phase 14.2.5 and 14.2.6:
- ✅ `MetadataPersistenceService` → `metadata-topic` → `MetadataIndexer`
- ✅ `PersistenceService` → `batch-topic` → `DummyIndexer`
- ✅ DummyIndexer waits for metadata before processing batches
- ✅ DummyIndexer runs continuously (blocking poll, not one-shot)
- ✅ **MERGE-based idempotency ensures exactly-once processing (even with buffer loss + redelivery!)**
- ✅ **ACK only after ALL ticks of a batch are flushed (cross-batch safe!)**
- ✅ Error handling: failed batches are redelivered, MERGE prevents duplicates
- ✅ All tests pass (<1 minute total)
- ✅ Monitoring: O(1) metrics for topics, components
- ✅ **Buffer-loss recovery:** Crash + redelivery works correctly (MERGE re-inserts missing ticks)

**Deferred Phases:**
- **Phase 14.2.8 (deferred):** DlqComponent only if monitoring shows specific batches (poison messages) failing repeatedly

**Note:** Phase 14.2.7 (IdempotencyComponent) has been completed despite YAGNI concerns. It is available as an optional component for future use.

## Implementation Notes

**No backward compatibility** - this is a new implementation.

**Implementation steps:**
1. Implement all phases sequentially
2. Test each phase independently
3. Update documentation as needed

**Rollback strategy:**
- Each phase is self-contained and testable
- If issues arise, revert to previous phase
- H2 Topics provide message persistence (no data loss)

---

**Phase Specs:**
- Phase 14.2.1: Topic Infrastructure ✅ Completed
- Phase 14.2.2: Metadata Notification Write ✅ Completed
- Phase 14.2.3: Metadata Notification Read ✅ Completed
- Phase 14.2.4: Batch Notification Write ✅ Completed
- Phase 14.2.5: AbstractBatchIndexer + DummyIndexer (Metadata + Storage-Read + Tick-by-Tick) ✅ Completed
- Phase 14.2.6: TickBufferingComponent (Cross-Batch Buffering) ✅ Completed
- Phase 14.2.7: IdempotencyComponent (Performance Optimization) ✅ Completed
- Phase 14.2.8: DlqComponent (Poison Message Handling) - ⚠️ **Specification ready - Implementation deferred (YAGNI)**

