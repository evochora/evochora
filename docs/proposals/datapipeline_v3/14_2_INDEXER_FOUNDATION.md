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
       → Buffer ticks (batch-overgreifend!)
       → Flush → MERGE to DB
       → Topic.ack() [ONLY for batches fully flushed!]
```

**Key Features:**
- ✅ Instant notification (blocking poll, no storage scanning)
- ✅ Reliable delivery (Topic guarantees)
- ✅ Competing consumers via Consumer Groups (built-in)
- ✅ At-least-once delivery + MERGE = Exactly-once semantics
- ✅ ACK only after ALL ticks of a batch are flushed (batch-overgreifend buffering safe!)
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

**Status:** Ready for implementation

**Goal:** `PersistenceService` publishes batch availability to `batch-topic`.

**Changes:**
- Add `ITopicWriter<BatchInfo>` resource binding
- After successful `storage.writeBatch()`, send BatchInfo notification
- Include: runId, storageKey, tickStart, tickEnd, writtenAtMs

**Implementation:**
```java
public class PersistenceService extends AbstractService {
    private final ITopicWriter<BatchInfo> batchTopic;
    
    private void writeBatchWithRetry(...) {
        // Write to storage
        storage.writeBatch(key, batch);
        
        // Send notification
        BatchInfo notification = BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStorageKey(key)
            .setTickStart(tickStart)
            .setTickEnd(tickEnd)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        batchTopic.send(notification);
    }
}
```

**Deliverables:**
- Updated `PersistenceService`
- Configuration changes (`resources.batch-out = "topic-write:batch-topic"`)
- Integration tests

---

### Phase 14.2.5: AbstractBatchIndexer Foundation + DummyIndexer

**Status:** Ready for implementation

**Goal:** Create `AbstractBatchIndexer` with complete batch-processing logic. `DummyIndexer` only implements `flushTicks()`.

**Changes:**
- Create `AbstractBatchIndexer` extending `AbstractIndexer`
- Add `BatchIndexerComponents` helper class for component configuration
- All topic loop, buffering, ACK tracking logic goes into `AbstractBatchIndexer`
- `DummyIndexer` extends `AbstractBatchIndexer` and only implements `flushTicks()`

**AbstractBatchIndexer Implementation:**
```java
public abstract class AbstractBatchIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {
    
    private final MetadataReadingComponent metadataComponent;
    private final TickBufferingComponent bufferingComponent;
    private final IdempotencyComponent idempotencyComponent;
    private final int topicPollTimeoutMs;
    
    protected AbstractBatchIndexer(String name, 
                                   Config options, 
                                   Map<String, List<IResource>> resources,
                                   BatchIndexerComponents components) {
        super(name, options, resources);
        
        // All components are optional!
        this.metadataComponent = components != null ? components.metadata : null;
        this.bufferingComponent = components != null ? components.buffering : null;
        this.idempotencyComponent = components != null ? components.idempotency : null;
        this.topicPollTimeoutMs = components != null ? components.topicPollTimeoutMs : 5000;
    }
    
    @Override
    protected final void indexRun(String runId) throws Exception {
        // Step 1: Wait for metadata (if component exists)
        if (metadataComponent != null) {
            metadataComponent.loadMetadata(runId);
            log.info("Metadata loaded for run: {}", runId);
        }
        
        // Step 2: Topic loop with buffering
        while (!Thread.currentThread().isInterrupted()) {
            TopicMessage<BatchInfo, ACK> msg = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);
            
            if (msg == null) {
                // Timeout - check flush
                if (bufferingComponent.shouldFlush()) {
                    flushAndAcknowledge();
                }
                continue;
            }
            
            processBatchMessage(msg);
        }
        
        // Final flush
        if (bufferingComponent.getBufferSize() > 0) {
            flushAndAcknowledge();
        }
    }
    
    private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
        BatchInfo batch = msg.message();
        String batchId = batch.getBatchId();
        
        // Idempotency check (if enabled)
        if (idempotencyComponent != null && idempotencyComponent.isProcessed(batchId)) {
            log.debug("Skipping duplicate batch (performance optimization): {}", batchId);
            topic.acknowledge(msg.acknowledgment());
            return;
        }
        
        try {
            // Read from storage (GENERIC for all batch indexers!)
            byte[] data = storage.readBatchFile(batch.getStorageKey());
            TickDataBatch tickBatch = TickDataBatch.parseFrom(data);
            
            if (bufferingComponent != null) {
                // WITH buffering: Add to buffer, ACK after flush
                bufferingComponent.addTicksFromBatch(tickBatch.getTicksList(), batchId, msg);
                
                log.debug("Buffered {} ticks from {}, buffer size: {}", 
                         tickBatch.getTicksCount(), batch.getStorageKey(), 
                         bufferingComponent.getBufferSize());
                
                // Flush if needed
                if (bufferingComponent.shouldFlush()) {
                    flushAndAcknowledge();
                }
            } else {
                // WITHOUT buffering: insertBatchSize=1 (tick-by-tick processing)
                // Process each tick individually to avoid hidden buffering
                for (TickData tick : tickBatch.getTicksList()) {
                    flushTicks(List.of(tick));
                }
                topic.acknowledge(msg.acknowledgment());
                
                log.debug("Processed {} ticks from {} (tick-by-tick, no buffering)", 
                         tickBatch.getTicksCount(), batch.getStorageKey());
            }
            
            // Mark processed (if enabled)
            if (idempotencyComponent != null) {
                idempotencyComponent.markProcessed(batchId);
            }
            
        } catch (Exception e) {
            log.error("Failed to process batch: {}", batchId);
            throw e;  // NO acknowledge - redelivery!
        }
    }
    
    private void flushAndAcknowledge() throws Exception {
        TickBufferingComponent.FlushResult<ACK> result = bufferingComponent.flush();
        if (result.ticks().isEmpty()) return;
        
        // ONLY indexer-specific part!
        flushTicks(result.ticks());
        
        // ACK all completed batches (GENERIC!)
        for (TopicMessage<?, ACK> msg : result.completedMessages()) {
            topic.acknowledge(msg.acknowledgment());
        }
    }
    
    /**
     * Flush ticks to database.
     * <p>
     * CRITICAL: Must use MERGE (not INSERT) for idempotency!
     * <p>
     * Called by AbstractBatchIndexer when buffer is full or timeout reached.
     *
     * @param ticks Ticks to flush
     * @throws Exception if flush fails
     */
    protected abstract void flushTicks(List<TickData> ticks) throws Exception;
    
    /**
     * Component configuration for batch indexers.
     */
    public static class BatchIndexerComponents {
        public final MetadataReadingComponent metadata;     // Optional
        public final TickBufferingComponent buffering;      // Required!
        public final IdempotencyComponent idempotency;      // Optional
        public final int topicPollTimeoutMs;
        
        public BatchIndexerComponents(MetadataReadingComponent metadata,
                                      TickBufferingComponent buffering,
                                      IdempotencyComponent idempotency,
                                      int topicPollTimeoutMs) {
            this.metadata = metadata;
            this.buffering = buffering;
            this.idempotency = idempotency;
            this.topicPollTimeoutMs = topicPollTimeoutMs;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private MetadataReadingComponent metadata;
            private TickBufferingComponent buffering;
            private IdempotencyComponent idempotency;
            private int topicPollTimeoutMs = 5000;
            
            public Builder metadata(MetadataReadingComponent c) { this.metadata = c; return this; }
            public Builder buffering(TickBufferingComponent c) { this.buffering = c; return this; }
            public Builder idempotency(IdempotencyComponent c) { this.idempotency = c; return this; }
            public Builder topicPollTimeout(int ms) { this.topicPollTimeoutMs = ms; return this; }
            
            public BatchIndexerComponents build() {
                return new BatchIndexerComponents(metadata, buffering, idempotency, topicPollTimeoutMs);
            }
        }
    }
}
```

**DummyIndexer Implementation (Phase 14.2.5):**
```java
public class DummyIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources, createComponents(options, resources));
    }
    
    private static BatchIndexerComponents createComponents(Config options, Map<String, List<IResource>> resources) {
        var builder = BatchIndexerComponents.builder();
        
        // Component 1: Metadata Reading (DummyIndexer wants this!)
        IMetadataReader metadataReader = getResourceStatic(resources, "metadata", IMetadataReader.class);
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        builder.metadata(new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs));
        
        // Component 2: Tick Buffering (REQUIRED!)
        int insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 1000;
        long flushTimeoutMs = options.hasPath("flushTimeoutMs") ? options.getLong("flushTimeoutMs") : 5000;
        builder.buffering(new TickBufferingComponent(insertBatchSize, flushTimeoutMs));
        builder.topicPollTimeout((int) flushTimeoutMs);  // Auto-calculate!
        
        // Component 3: Idempotency (DummyIndexer wants this for testing!)
        if (options.hasPath("enableIdempotency") && options.getBoolean("enableIdempotency")) {
            IIdempotencyTracker tracker = getResourceStatic(resources, "idempotency", IIdempotencyTracker.class);
            builder.idempotency(new IdempotencyComponent(tracker, DummyIndexer.class.getName()));
        }
        
        return builder.build();
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) {
        log.debug("Flushed {} ticks (DummyIndexer: no DB writes)", ticks.size());
    }
}
```

**Configuration:**
```hocon
dummy-indexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadata = "db-meta-read:index-database"
    topic = "topic-read:batch-topic?consumerGroup=dummy-indexer"
    idempotency = "db-idempotency:index-database"  # Optional
  }
  
  options {
    runId = ${?pipeline.services.runId}
    
    # Metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000
    
    # Tick buffering
    insertBatchSize = 1000
    flushTimeoutMs = 5000
    # Note: topicPollTimeoutMs automatically set to flushTimeoutMs
    
    # Idempotency (optional)
    enableIdempotency = true
  }
}
```

**Deliverables:**
- `AbstractBatchIndexer` with complete batch-processing logic
- `BatchIndexerComponents` helper class
- `DummyIndexer` with only `flushTicks()` implementation
- Integration tests (all 3 components active)

---

### Phase 14.2.6: TickBufferingComponent Implementation

**Status:** Ready for implementation

**Goal:** Implement `TickBufferingComponent` with integrated batch-level ACK tracking.

**Note:** This component is already used by `AbstractBatchIndexer` (Phase 14.2.5). This phase documents its implementation details.

**The ACK Problem:**

```
Config: insertBatchSize=250, storage batches=100 ticks each

1. poll() → batch_0000.pb (100 ticks) → buffer: [0-99]     (size: 100)
2. poll() → batch_0001.pb (100 ticks) → buffer: [0-199]   (size: 200) 
3. poll() → batch_0002.pb (100 ticks) → buffer: [0-299]   (size: 300)
   → shouldFlush() → TRUE → Flush 250 Ticks (0-249)
   → buffer: [250-299] (nur 50 Ticks aus batch_0002!)

FRAGE: Was wird jetzt geackt?
- batch_0000: ✅ Alle 100 Ticks geflusht → ACK ok
- batch_0001: ✅ Alle 100 Ticks geflusht → ACK ok  
- batch_0002: ❌ Nur 50 von 100 Ticks geflusht → ACK NICHT!

Wenn wir batch_0002 jetzt acken und dann crashen:
→ Topic gibt batch_0002 nicht mehr raus
→ 50 Ticks (tick_250-299) sind VERLOREN! ❌❌❌
```

**Lösung: TickBufferingComponent mit Batch-Flush-Tracking**

Component trackt:
- Welcher Tick gehört zu welchem Batch? → `batchIds` Array (parallel zu `buffer`)
- Wie viele Ticks eines Batches wurden geflusht? → `BatchFlushState.ticksFlushed`
- Welche TopicMessage muss geackt werden? → `BatchFlushState.message`

Component liefert bei `flush()`:
- Ticks zum Flushen → `List<TickData>`
- ACKs für **nur vollständig geflusht Batches** → `List<TopicMessage<?, ACK>>`

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
public class TickBufferingComponent {
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private final List<TickData> buffer = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>(); // Parallel zu buffer!
    private final Map<String, BatchFlushState> pendingBatches = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    
    static class BatchFlushState {
        final Object message; // TopicMessage - generisch!
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
                // Batch ist komplett geflusht → kann geackt werden!
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

**Deliverables:**
- `TickBufferingComponent` implementation with ACK tracking
- Integration tests (batch-overgreifend ACK, timeout-based flush, buffer loss recovery)

---

### Phase 14.2.7: IdempotencyComponent + MERGE Examples

**Status:** Ready for implementation

**Goal:** Implement optional `IdempotencyComponent` and document MERGE-based idempotency for production indexers.

**Note:** `AbstractBatchIndexer` (Phase 14.2.5) already handles idempotency checks. This phase documents component implementation and MERGE examples.

**Critical Architecture Decision: MERGE-Based Idempotency**

```
Problem: Batch-Level Idempotency funktioniert NICHT bei batch-übergreifendem Buffering!

Szenario (Crash):
1. Flush: tick_0-249 (aus batch_0000, batch_0001, batch_0002 teilweise) → DB ✅
2. Crash → Buffer verliert tick_250-299 (Rest von batch_0002) ❌
3. Restart → batch_0002 wird neu verarbeitet (kein ACK war)
   → Batch-Level Idempotency: "batch_0002 processed" → SKIP! ❌
   → tick_250-299 sind VERLOREN! ❌❌❌

Lösung: Tick-Level Idempotency via MERGE!
- Jeder Indexer verwendet MERGE (nicht INSERT!) → 100% idempotent
- Bei Redelivery: MERGE findet existing rows → UPDATE (noop) oder INSERT (fehlende)
- IdempotencyComponent NUR für Performance (skip storage read), NICHT für Correctness!
```

**Changes:**
- **PFLICHT:** Alle production indexers verwenden MERGE statt INSERT in `flushTicks()`!
- **OPTIONAL:** Implement `IdempotencyComponent` für Performance (skip storage reads)
- Already implemented in `AbstractBatchIndexer`: Error handling, no ACK on failure

**How IdempotencyComponent is used by AbstractBatchIndexer:**
```java
// In AbstractBatchIndexer.processBatchMessage():
if (idempotencyComponent != null && idempotencyComponent.isProcessed(batchId)) {
    log.debug("Skipping duplicate batch (performance optimization): {}", batchId);
    topic.acknowledge(msg.acknowledgment());
    return;  // Skip storage read!
}

// ... read storage, buffer ticks, flush ...

// Mark processed AFTER buffering
if (idempotencyComponent != null) {
    idempotencyComponent.markProcessed(batchId);
}
```

**IdempotencyComponent Implementation:**
```java
/**
 * Component for optional idempotency tracking (performance optimization).
 * <p>
 * Wraps IIdempotencyTracker with indexer-specific context and provides convenient
 * methods for duplicate detection. This is a PERFORMANCE optimization only - 
 * correctness is guaranteed by MERGE statements in indexers.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
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
dummy-indexer {
  resources {
    storage = "storage-read:tick-storage"
    metadata = "db-meta-read:index-database"
    topic = "topic-read:batch-topic?consumerGroup=dummy-indexer"
    idempotency = "db-idempotency:index-database"  # NEW
  }
  
  options {
    runId = ${?pipeline.services.runId}
    
    # Metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000
    
    # Tick buffering
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
Crash-Szenario mit MERGE:
1. Buffer: [tick_0-99 (batch_0000), tick_100-199 (batch_0001), tick_200-299 (batch_0002)]
2. Flush 250 ticks → MERGE (tick_0-249) → DB ✅
3. Buffer: [tick_250-299 (batch_0002)]
4. CRASH! → Buffer verloren, keine ACKs gesendet
5. Topic redelivery: batch_0000, batch_0001, batch_0002
6. Indexer-2 verarbeitet alle 3 batches:
   - Read storage → 300 ticks
   - Flush → MERGE (tick_0-299)
     - tick_0-249: MERGE findet existing rows → UPDATE (noop, weil gleiche Daten) ✅
     - tick_250-299: MERGE findet keine rows → INSERT (fehlende Ticks!) ✅
7. Ergebnis: ALLE 300 Ticks in DB, KEINE Duplikate! ✅✅✅

Ohne MERGE (nur INSERT):
→ tick_0-249 würden als Duplikate fehlschlagen oder doppelt eingefügt ❌
```

**Deliverables:**
- `IdempotencyComponent` implementation (OPTIONAL - Performance only!)
- Updated `DummyIndexer` with MERGE-based idempotency + error handling
- Integration tests (MERGE idempotency, duplicates, failures, redelivery, buffer-loss recovery)

**Note:** `IIdempotencyTracker` interface and H2 implementation with `idempotency_tracking` table already exist!

---

## Component Architecture Summary

### Components Overview

| Component | Purpose | Location | When to use? |
|-----------|---------|----------|--------------|
| `MetadataReadingComponent` | Polls DB for metadata, caches `samplingInterval` | In `AbstractBatchIndexer` | **Optional:** Most batch indexers need it |
| `TickBufferingComponent` | Buffers ticks + ACK tracking, triggers flush | In `AbstractBatchIndexer` | **Optional:** For large batch inserts |
| `IdempotencyComponent` | Skip duplicate batches (performance) | In `AbstractBatchIndexer` | **Optional:** Performance only, not correctness! |

**All components are optional!** Each batch indexer decides in its constructor which components to use.

**Critical Notes:**
- **TickBufferingComponent:** If used, tracks which ticks belong to which batch, returns ACKs ONLY for fully flushed batches (configurable `insertBatchSize`, e.g., 250)
- **Without TickBufferingComponent:** Each **tick** processed individually (`insertBatchSize=1`), then **ONE ACK** sent after all ticks from BatchFile are processed
- **ACK Guarantee:** In both modes, ACK is sent ONLY after ALL ticks from a BatchFile are successfully flushed to DB (atomicity per BatchFile!)
- **IdempotencyComponent:** ONLY for performance (skip storage read), NOT for correctness (MERGE guarantees idempotency!)
- **MERGE:** ALL indexers MUST use MERGE (not INSERT) for 100% idempotent processing
- **insertBatchSize is independent of batchFileSize:** Buffering can combine ticks from multiple BatchFiles (e.g., 3x 100-tick files → 1x 250-tick flush)

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

**DummyIndexer (extends AbstractBatchIndexer, only flushTicks!):**
```java
public class DummyIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources, createComponents(options, resources));
    }
    
    private static BatchIndexerComponents createComponents(Config options, Map<String, List<IResource>> resources) {
        var builder = BatchIndexerComponents.builder();
        
        // Component selection in constructor!
        IMetadataReader metadataReader = getResourceStatic(resources, "metadata", IMetadataReader.class);
        builder.metadata(new MetadataReadingComponent(metadataReader, 1000, 300000));
        
        int insertBatchSize = options.getInt("insertBatchSize");
        long flushTimeoutMs = options.getLong("flushTimeoutMs");
        builder.buffering(new TickBufferingComponent(insertBatchSize, flushTimeoutMs));
        builder.topicPollTimeout((int) flushTimeoutMs);
        
        if (options.getBoolean("enableIdempotency", false)) {
            IIdempotencyTracker tracker = getResourceStatic(resources, "idempotency", IIdempotencyTracker.class);
            builder.idempotency(new IdempotencyComponent(tracker, DummyIndexer.class.getName()));
        }
        
        return builder.build();
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
        super(name, options, resources, createComponents(options, resources));
        this.database = getRequiredResource("database", IEnvironmentDatabase.class);
    }
    
    private static BatchIndexerComponents createComponents(Config options, Map<String, List<IResource>> resources) {
        // Same as DummyIndexer! (could be shared helper)
        var builder = BatchIndexerComponents.builder();
        
        IMetadataReader metadataReader = getResourceStatic(resources, "metadata", IMetadataReader.class);
        builder.metadata(new MetadataReadingComponent(metadataReader, 1000, 300000));
        
        int insertBatchSize = options.getInt("insertBatchSize");
        long flushTimeoutMs = options.getLong("flushTimeoutMs");
        builder.buffering(new TickBufferingComponent(insertBatchSize, flushTimeoutMs));
        builder.topicPollTimeout((int) flushTimeoutMs);
        
        if (options.getBoolean("enableIdempotency", false)) {
            IIdempotencyTracker tracker = getResourceStatic(resources, "idempotency", IIdempotencyTracker.class);
            builder.idempotency(new IdempotencyComponent(tracker, EnvironmentIndexer.class.getName()));
        }
        
        return builder.build();
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws SQLException {
        // CRITICAL: MERGE statt INSERT - 100% idempotent!
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
        super(name, options, resources, createComponents(options, resources));
        this.database = getRequiredResource("database", ISimpleDatabase.class);
    }
    
    private static BatchIndexerComponents createComponents(Config options, Map<String, List<IResource>> resources) {
        var builder = BatchIndexerComponents.builder();
        
        // Metadata component (optional, but useful)
        IMetadataReader metadataReader = getResourceStatic(resources, "metadata", IMetadataReader.class);
        builder.metadata(new MetadataReadingComponent(metadataReader, 1000, 300000));
        
        // NO buffering! Process each batch immediately
        // NO idempotency component (MERGE handles it)
        
        builder.topicPollTimeout(5000);  // Standard timeout (5s)
        
        return builder.build();
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws SQLException {
        // Called per tick (insertBatchSize=1 due to no buffering component)
        // ticks.size() will always be 1!
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
        super(name, options, resources, null);  // No components!
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) {
        // Called per tick (insertBatchSize=1 due to no buffering component)
        // ticks.size() will always be 1!
        log.info("Processed {} ticks from batch", ticks.size());
    }
}
```

### Design Benefits

✅ **3-Level Architecture:** Clear separation: AbstractIndexer (core) → AbstractBatchIndexer (batch logic) → Concrete Indexers (only `flushTicks()`)  
✅ **Zero Boilerplate:** Batch indexers only implement `flushTicks()` + component selection in constructor  
✅ **DRY:** All batch-processing logic in one place (`AbstractBatchIndexer`)  
✅ **Maximum Flexibility:** All components optional - from minimal (no components) to full-featured (all components)  
✅ **Two Modes:** With buffering (batch-overgreifend) or without buffering (direct processing)  
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
  
  # Reader (Phase 14.2.5-14.2.7)
  dummy-indexer {
    className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
    resources {
      topic = "topic-read:batch-topic?consumerGroup=dummy-indexer"
      storage = "storage-read:tick-storage"
      metadata = "db-meta-read:index-database"
      idempotency = "db-idempotency:index-database"
    }
    options {
      runId = ${?pipeline.services.runId}
      
      # Metadata polling (for MetadataReadingComponent)
      pollIntervalMs = 1000
      maxPollDurationMs = 300000
      
      # Tick buffering (Phase 14.2.6)
      insertBatchSize = 1000
      flushTimeoutMs = 5000
      # Note: topicPollTimeoutMs automatically set to flushTimeoutMs (no manual config)
      
      # Idempotency (Phase 14.2.7)
      enableIdempotency = true
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
- Phase 14.2.5: Metadata wait + Topic loop (log-only)
- Phase 14.2.6: Add storage reads + buffering
- Phase 14.2.7: Add idempotency + error handling

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
DEBUG Skipping duplicate batch: batch_0000000000_0000009999.pb
```

## Success Criteria

Upon completion of all phases:
- ✅ `MetadataPersistenceService` → `metadata-topic` → `MetadataIndexer`
- ✅ `PersistenceService` → `batch-topic` → `DummyIndexer`
- ✅ DummyIndexer waits for metadata before processing batches
- ✅ DummyIndexer runs continuously (blocking poll, not one-shot)
- ✅ **MERGE-based idempotency ensures exactly-once processing (even with buffer loss + redelivery!)**
- ✅ **ACK only after ALL ticks of a batch are flushed (batch-overgreifend safe!)**
- ✅ Error handling: failed batches are redelivered, MERGE prevents duplicates
- ✅ All tests pass (<1 minute total)
- ✅ Monitoring: O(1) metrics for topics, components
- ✅ **Buffer-loss recovery:** Crash + redelivery works correctly (MERGE re-inserts missing ticks)

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
- Phase 14.2.4: Batch Notification Write - Ready
- Phase 14.2.5: DummyIndexer Topic Loop - Ready
- Phase 14.2.6: Tick Buffering - Ready
- Phase 14.2.7: Idempotency + Error Handling - Ready

