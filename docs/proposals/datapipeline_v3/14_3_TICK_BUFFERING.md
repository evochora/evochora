# Phase 2.5.3: Tick Buffering Component

**Part of:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](./14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

**Status:** Ready for implementation

---

## Goal

Implement tick buffering component to allow flexible batch sizes for database inserts.

Indexers need to configure their own insert batch sizes independent of storage batch file sizes. This enables optimization for different indexer types (e.g., small frequent inserts vs. large bulk inserts).

## Scope

**In Scope:**
1. `TickBufferingComponent` for flexible tick buffering
2. Configurable `insertBatchSize` (can be smaller or larger than file batch size)
3. Configurable `flushTimeoutMs` (auto-flush if next batch delayed)
4. DummyIndexer v3 - uses tick buffering (still no actual processing)
5. Unit tests for TickBufferingComponent
6. Integration tests for DummyIndexer v3

**Out of Scope:**
- Gap detection (Phase 2.5.4)
- Batch processing loop (Phase 2.5.5)

## Success Criteria

1. TickBufferingComponent buffers ticks across multiple batch files
2. Flush triggered when buffer reaches insertBatchSize
3. Flush triggered when flushTimeoutMs exceeded since last flush
4. DummyIndexer v3 successfully buffers and flushes ticks
5. All tests pass with proper log validation
6. Metrics tracked with O(1) operations

## Prerequisites

- Phase 2.5.1: Metadata Reading (completed)
- Phase 2.5.2: Batch Coordination (completed)
- DummyIndexer v2

## Package Structure

```
org.evochora.datapipeline.services.indexers/
  - DummyIndexer.java                     # EXTEND - v3: Tick buffering
  └── components/
      - TickBufferingComponent.java       # NEW - Tick buffering with timeout
```

## TickBufferingComponent

```java
package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.TickData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Component for buffering ticks with configurable batch size and flush timeout.
 * <p>
 * Enables indexers to configure their own insert batch sizes independent of storage
 * batch file sizes. Supports both smaller and larger batch sizes than file batches.
 * <p>
 * <strong>Flush Triggers:</strong>
 * <ol>
 *   <li>Buffer reaches insertBatchSize</li>
 *   <li>flushTimeoutMs exceeded since last flush (prevents stale data)</li>
 *   <li>Manual flush() call (e.g., end of processing)</li>
 * </ol>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class TickBufferingComponent {
    private static final Logger log = LoggerFactory.getLogger(TickBufferingComponent.class);
    
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private final Consumer<List<TickData>> flushHandler;
    
    private final List<TickData> buffer = new ArrayList<>();
    private long lastFlushTimeMs;
    
    /**
     * Creates tick buffering component.
     *
     * @param insertBatchSize Number of ticks to buffer before auto-flush
     * @param flushTimeoutMs Maximum time between flushes (milliseconds)
     * @param flushHandler Callback invoked when buffer flushed
     */
    public TickBufferingComponent(int insertBatchSize, 
                                  long flushTimeoutMs, 
                                  Consumer<List<TickData>> flushHandler) {
        this.insertBatchSize = insertBatchSize;
        this.flushTimeoutMs = flushTimeoutMs;
        this.flushHandler = flushHandler;
        this.lastFlushTimeMs = System.currentTimeMillis();
    }
    
    /**
     * Adds a tick to the buffer.
     * <p>
     * Automatically flushes if insertBatchSize reached.
     *
     * @param tick Tick to buffer
     */
    public void addTick(TickData tick) {
        buffer.add(tick);
        
        if (buffer.size() >= insertBatchSize) {
            flush();
        }
    }
    
    /**
     * Checks if flush timeout exceeded and flushes if necessary.
     * <p>
     * Should be called periodically (e.g., between batch files) to ensure
     * buffered ticks don't remain unprocessed for too long.
     */
    public void checkTimeout() {
        if (buffer.isEmpty()) {
            return;
        }
        
        long elapsed = System.currentTimeMillis() - lastFlushTimeMs;
        if (elapsed >= flushTimeoutMs) {
            log.debug("Flush timeout: {}ms elapsed, flushing {} ticks", elapsed, buffer.size());
            flush();
        }
    }
    
    /**
     * Manually flushes all buffered ticks.
     * <p>
     * Invokes flushHandler with current buffer contents, then clears buffer.
     * Does nothing if buffer empty.
     */
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        
        log.debug("Flushing {} buffered ticks", buffer.size());
        
        flushHandler.accept(new ArrayList<>(buffer));
        buffer.clear();
        lastFlushTimeMs = System.currentTimeMillis();
    }
    
    /**
     * Gets the current buffer size.
     *
     * @return Number of buffered ticks
     */
    public int getBufferSize() {
        return buffer.size();
    }
    
    /**
     * Gets elapsed time since last flush.
     *
     * @return Milliseconds since last flush
     */
    public long getTimeSinceLastFlush() {
        return System.currentTimeMillis() - lastFlushTimeMs;
    }
}
```

## DummyIndexer v3 Implementation

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinator;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.services.indexers.components.BatchCoordinationComponent;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.evochora.datapipeline.services.indexers.components.TickBufferingComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating batch coordination and tick buffering infrastructure.
 * <p>
 * <strong>Phase 2.5.1 Scope:</strong> Metadata reading
 * <p>
 * <strong>Phase 2.5.2 Scope:</strong> Batch claiming and completion
 * <p>
 * <strong>Phase 2.5.3 Scope:</strong> Tick buffering with flexible batch size
 * <ul>
 *   <li>Buffers ticks across multiple batch files</li>
 *   <li>Flushes when insertBatchSize reached</li>
 *   <li>Flushes on timeout (prevents stale data)</li>
 *   <li>Still no actual processing (just counting)</li>
 * </ul>
 */
public class DummyIndexer extends AbstractIndexer implements IMonitorable {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    private final MetadataReadingComponent metadataComponent;
    private final BatchCoordinationComponent coordinationComponent;
    private final TickBufferingComponent bufferingComponent;
    private final IBatchStorageRead storage;
    
    private final int insertBatchSize;
    
    private final AtomicLong runsProcessed = new AtomicLong(0);
    private final AtomicLong batchesClaimed = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksObserved = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);
    private final AtomicLong ticksFlushed = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup metadata reading component (Phase 2.5.1)
        IMetadataReader metadataReader = getRequiredResource(IMetadataReader.class, "db-meta-read");
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        
        this.metadataComponent = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
        
        // Setup batch coordination component (Phase 2.5.2)
        IBatchCoordinator coordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator");
        IBatchCoordinatorReady coordinatorReady = coordinator.setIndexerClass(this.getClass().getName());
        this.coordinationComponent = new BatchCoordinationComponent(coordinatorReady);
        
        // Setup tick buffering component (Phase 2.5.3)
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 1000;
        long flushTimeoutMs = options.hasPath("flushTimeoutMs") ? options.getLong("flushTimeoutMs") : 5000;
        
        this.bufferingComponent = new TickBufferingComponent(
            insertBatchSize, 
            flushTimeoutMs, 
            this::flushTicks
        );
        
        // Storage for batch discovery
        this.storage = getRequiredResource(IBatchStorageRead.class, "storage");
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Load metadata (polls until available)
        metadataComponent.loadMetadata(runId);
        
        log.debug("Metadata available: runId={}, samplingInterval={}", 
                 runId, metadataComponent.getSamplingInterval());
        
        runsProcessed.incrementAndGet();
        
        // Process batches
        processBatches(runId);
        
        // Flush any remaining buffered ticks
        bufferingComponent.flush();
        
        log.info("Batch processing completed: runId={}, batches={}, flushes={}", 
                 runId, batchesProcessed.get(), flushCount.get());
    }
    
    private void processBatches(String runId) throws Exception {
        String batchPath = "tickdata/" + runId + "/";
        String continuationToken = null;
        int batchesInPage = 0;
        
        do {
            // List next page of batches (max 100 per page)
            var page = storage.listBatchFiles(batchPath, continuationToken, 100);
            batchesInPage = page.batchFiles().size();
            
            for (var batchFile : page.batchFiles()) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                // Parse tick range from filename
                long tickStart = batchFile.tickStart();
                long tickEnd = batchFile.tickEnd();
                String filename = batchFile.filename();
                
                // Try to claim batch
                if (coordinationComponent.tryClaim(filename, tickStart, tickEnd)) {
                    batchesClaimed.incrementAndGet();
                    
                    log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);
                    
                    // Process batch with buffering
                    processOneBatch(batchPath + filename, filename);
                    
                    // Check flush timeout (between batches)
                    bufferingComponent.checkTimeout();
                    
                    batchesProcessed.incrementAndGet();
                }
            }
            
            continuationToken = page.continuationToken();
            
        } while (continuationToken != null && batchesInPage > 0);
    }
    
    private void processOneBatch(String fullPath, String filename) throws Exception {
        // Read batch file
        byte[] data = storage.readBatchFile(fullPath);
        
        // Parse protobuf
        org.evochora.datapipeline.api.contracts.TickDataBatch batch =
            org.evochora.datapipeline.api.contracts.TickDataBatch.parseFrom(data);
        
        // Buffer ticks
        for (TickData tick : batch.getTicksList()) {
            ticksObserved.incrementAndGet();
            bufferingComponent.addTick(tick);
        }
        
        log.debug("Buffered {} ticks from {}, buffer size: {}", 
                 batch.getTicksCount(), filename, bufferingComponent.getBufferSize());
    }
    
    private void flushTicks(List<TickData> ticks) {
        // Just count flushed ticks (no actual processing)
        flushCount.incrementAndGet();
        ticksFlushed.addAndGet(ticks.size());
        
        log.debug("Flushed {} ticks, total: {}", ticks.size(), ticksFlushed.get());
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "runs_processed", runsProcessed.get(),
            "batches_claimed", batchesClaimed.get(),
            "batches_processed", batchesProcessed.get(),
            "ticks_observed", ticksObserved.get(),
            "flush_count", flushCount.get(),
            "ticks_flushed", ticksFlushed.get(),
            "buffer_size", bufferingComponent.getBufferSize()
        );
    }
    
    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }
    
    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }
    
    @Override
    public void clearErrors() {
        // No-op
    }
}
```

## Configuration

**Note:** Database resource `index-database` is already configured in `evochora.conf` - no changes needed.

### DummyIndexer v3

```hocon
dummy-indexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadataReader = "db-meta-read:index-database"
    coordinator = "db-coordinator:index-database"
  }
  
  options {
    # Metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000
    
    # Tick buffering (Phase 2.5.3)
    insertBatchSize = 1000      # Number of ticks to buffer before flush
    flushTimeoutMs = 5000       # Max time between flushes (milliseconds)
  }
}
```

## Testing Requirements

All tests must follow project standards:

### General Requirements
- Tag with `@Tag("unit")` or `@Tag("integration")`
- Use `@ExtendWith(LogWatchExtension.class)`
- **Log Assertions:**
  - DEBUG and INFO logs are always allowed - never use `@AllowLog` for these
  - WARN and ERROR logs MUST be explicitly expected with `@ExpectLog`
  - Use specific message patterns with wildcards only for variable parts
  - Example: `@ExpectLog(logger = "...", level = WARN, message = "Batch failed: *")`
  - **NEVER** use broad patterns like `message = "*"`
- **Never use `Thread.sleep`** - use Awaitility `await().atMost(...).until(...)` instead
- Leave no artifacts (in-memory H2, temp directories cleaned in `@AfterEach`)
- Use UUID-based database names for parallel test execution
- Verify no connection leaks in `@AfterEach`

### Unit Tests

**TickBufferingComponentTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class TickBufferingComponentTest {
    
    @Test
    void addTick_autoFlushOnSize() {
        // Create component with insertBatchSize=10
        // Add 10 ticks
        // Verify flush triggered automatically
        // Verify buffer cleared
    }
    
    @Test
    void addTick_noFlushIfBelowSize() {
        // Add 5 ticks (size=10)
        // Verify no flush
        // Verify buffer size = 5
    }
    
    @Test
    void checkTimeout_flushesAfterTimeout() {
        // Create component with flushTimeoutMs=100
        // Add 1 tick
        // Advance time by 150ms
        // Call checkTimeout()
        // Verify flush triggered
    }
    
    @Test
    void checkTimeout_noFlushBeforeTimeout() {
        // Add 1 tick
        // Advance time by 50ms (timeout=100ms)
        // Call checkTimeout()
        // Verify no flush
    }
    
    @Test
    void checkTimeout_emptyBuffer_noFlush() {
        // Don't add any ticks
        // Advance time past timeout
        // Call checkTimeout()
        // Verify no flush
    }
    
    @Test
    void flush_manual() {
        // Add 3 ticks
        // Call flush() manually
        // Verify flush triggered
        // Verify buffer cleared
    }
    
    @Test
    void flush_emptyBuffer_noOp() {
        // Call flush() on empty buffer
        // Verify no flush handler invoked
    }
}
```

### Integration Tests

**DummyIndexerV3IntegrationTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerV3IntegrationTest {
    
    @Test
    void testTickBuffering_SmallerBatchSize() throws Exception {
        // Create test run with 5 batch files (100 ticks each = 500 total)
        String runId = createTestRun(numBatches = 5, ticksPerBatch = 100);
        
        DummyIndexer indexer = createIndexer(runId, insertBatchSize = 50);
        indexer.start();
        
        // Wait for all batches processed
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 5);
        
        indexer.stop();
        
        // Verify 10 flushes (500 / 50)
        assertEquals(10, indexer.getMetrics().get("flush_count").intValue());
        assertEquals(500, indexer.getMetrics().get("ticks_flushed").intValue());
    }
    
    @Test
    void testTickBuffering_LargerBatchSize() throws Exception {
        // Create test run with 5 batch files (100 ticks each = 500 total)
        String runId = createTestRun(numBatches = 5, ticksPerBatch = 100);
        
        DummyIndexer indexer = createIndexer(runId, insertBatchSize = 300);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 5);
        
        indexer.stop();
        
        // Verify 2 flushes (300 + 200)
        assertEquals(2, indexer.getMetrics().get("flush_count").intValue());
    }
    
    @Test
    void testTickBuffering_FlushTimeout() throws Exception {
        // Create test run with 1 batch file (50 ticks)
        String runId = createTestRun(numBatches = 1, ticksPerBatch = 50);
        
        DummyIndexer indexer = createIndexer(runId, 
            insertBatchSize = 1000, flushTimeoutMs = 100);
        indexer.start();
        
        // Wait for timeout flush
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("flush_count").intValue() >= 1);
        
        indexer.stop();
        
        // Verify all 50 ticks flushed via timeout
        assertEquals(50, indexer.getMetrics().get("ticks_flushed").intValue());
    }
    
    @Test
    void testTickBuffering_FinalFlush() throws Exception {
        // Create test run with batches totaling 250 ticks
        String runId = createTestRun(totalTicks = 250);
        
        DummyIndexer indexer = createIndexer(runId, insertBatchSize = 100);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify 3 flushes (100 + 100 + 50)
        assertEquals(3, indexer.getMetrics().get("flush_count").intValue());
    }
}
```

## Logging Strategy

### Principles

**❌ NEVER:**
- INFO in loops
- Multi-line logs
- Phase prefixes
- Log buffer operations that metrics track

**✅ ALWAYS:**
- INFO very sparingly (completion summary only)
- DEBUG for buffer operations
- Single line per event

### Log Levels

**INFO:**
- Processing completed with summary

**DEBUG:**
- Ticks buffered, flushed
- Flush timeout triggers
- Buffer size

**WARN/ERROR:**
- Not used in Phase 2.5.3

### Examples

```java
// ✅ GOOD - INFO only for completion
log.info("Batch processing completed: runId={}, batches={}, flushes={}", 
         runId, batchesProcessed, flushCount);

// ✅ GOOD - DEBUG in loop
log.debug("Buffered {} ticks from {}, buffer size: {}", tickCount, filename, bufferSize);

// ✅ GOOD - Timeout trigger
log.debug("Flush timeout: {}ms elapsed, flushing {} ticks", elapsed, bufferSize);

// ❌ BAD - Multi-line
log.debug("Flushing remaining buffered ticks");
bufferingComponent.flush();
```

## Monitoring Requirements

### DummyIndexer v3 Metrics

```java
metrics.put("runs_processed", runsProcessed.get());      // O(1)
metrics.put("batches_claimed", batchesClaimed.get());    // O(1)
metrics.put("batches_processed", batchesProcessed.get()); // O(1)
metrics.put("ticks_observed", ticksObserved.get());      // O(1)
metrics.put("flush_count", flushCount.get());            // O(1) - NEW
metrics.put("ticks_flushed", ticksFlushed.get());        // O(1) - NEW
metrics.put("buffer_size", bufferingComponent.getBufferSize()); // O(1) - NEW
```

## JavaDoc Requirements

All public classes, interfaces, and methods must have comprehensive JavaDoc:

**Required Elements:**
- Class/Interface purpose
- Thread safety guarantees
- Usage examples (for public APIs)
- Parameter descriptions (@param)
- Return value descriptions (@return)
- Exception documentation (@throws)
- Implementation notes where relevant
- **Capability mapping (for AbstractDatabaseResource methods):** 
  - All new `do*()` methods in AbstractDatabaseResource must document which capability interface they belong to using `<strong>Capability:</strong> {@link InterfaceName#methodName()}` in the JavaDoc
  - **Implementations (e.g., H2Database) should also include minimal JavaDoc with capability link** even with `@Override`, for immediate visibility without navigating to parent class
  - Example: `/** Implements {@link IBatchCoordinatorReady#recordGap(long, long)}. */`

Examples shown in code sections above demonstrate proper JavaDoc structure.

## Implementation Checklist

- [ ] Create TickBufferingComponent
- [ ] Extend DummyIndexer to v3
- [ ] Write unit tests for TickBufferingComponent
- [ ] Write integration tests for DummyIndexer v3 (smaller, larger, timeout, final flush)
- [ ] Verify all tests pass
- [ ] Verify JavaDoc complete
- [ ] Verify logging follows strategy

---

**Previous Phase:** [14_2_BATCH_COORDINATION.md](./14_2_BATCH_COORDINATION.md)  
**Next Phase:** [14_4_GAP_DETECTION.md](./14_4_GAP_DETECTION.md)


