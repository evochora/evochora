# Data Pipeline V3 - Persistence Service (Phase 2.3)

## Goal

Implement PersistenceService that drains TickData batches from queues and persists them to storage with optional deduplication, retry logic, and dead letter queue support. The service ensures no data loss during normal operation and graceful degradation during failures.

## Scope

**This phase implements ONLY:**
1. PersistenceService implementation using existing DeadLetterMessage for DLQ
2. Integration tests verifying end-to-end persistence flow

**This phase does NOT implement:**
- New protobuf message definitions (uses existing DeadLetterMessage from system_contracts.proto)
- Indexer services (future phase - reads batches from storage for analysis)
- Recovery tools for processing DeadLetterMessage from DLQ
- Storage cleanup/archival services
- These are shown in "Future Usage Examples" section for context only

## Success Criteria

Upon completion:
1. PersistenceService compiles and extends AbstractService
2. Drains TickData batches from input queue with configurable size and timeout
3. Extracts simulationRunId from first tick and generates storage keys
4. Validates first/last tick consistency (same simulationRunId)
5. Optional idempotency tracking detects duplicate ticks
6. Writes batches to storage with zero-padded filenames
7. Retry logic with exponential backoff for transient failures
8. Failed batches sent to DLQ with full recovery data
9. Comprehensive metrics tracking (batches, ticks, bytes, errors, duplicates)
10. Graceful shutdown writes partial batches without data loss
11. Integration tests verify SimulationEngine → Queue → PersistenceService → Storage flow
12. All tests pass with LogWatch, awaitility, proper tagging, and cleanup

## Prerequisites

- Phase 0: API Foundation (completed) - IResource, IMonitorable interfaces
- Phase 1.2: Core Resource Implementation (completed) - Queue resources
- Phase 2.0: Protobuf Setup (completed) - TickData message
- Phase 2.1: SimulationEngine (completed) - Produces TickData
- Phase 2.2: Storage Resource (completed) - Persists batches

## Architectural Context

### Data Flow

```
SimulationEngine → raw-tick-data queue → PersistenceService → Storage
                                              ↓ (on failure)
                                         persistence-dlq
```

**PersistenceService responsibilities:**
1. **Drain batches**: Pull up to N ticks from queue with timeout
2. **Deduplicate**: Check for duplicate ticks (if idempotency tracker configured)
3. **Persist**: Write batch to storage with atomic commit
4. **Retry**: Handle transient failures (network timeouts, temporary disk issues)
5. **DLQ**: Send unrecoverable failures to dead letter queue
6. **Metrics**: Track throughput, errors, duplicates

### Competing Consumers Pattern

Multiple PersistenceService instances can run concurrently:
- Each drains from the same queue (competing consumers)
- No coordination needed - queue handles distribution
- Shared idempotency tracker prevents duplicates across instances
- Shared DLQ centralizes failure tracking

**Configuration example:**
```hocon
services {
  persistence-1 {
    className = "org.evochora.datapipeline.services.PersistenceService"
    resources {
      input = "queue-in:raw-tick-data"
      storage = "storage-write:storage-main"
      idempotencyTracker = "persistence-idempotency"
      dlq = "queue-out:persistence-dlq"
    }
  }

  persistence-2 {
    className = "org.evochora.datapipeline.services.PersistenceService"
    resources {
      input = "queue-in:raw-tick-data"
      storage = "storage-write:storage-main"
      idempotencyTracker = "persistence-idempotency"  # Shared!
      dlq = "queue-out:persistence-dlq"                # Shared!
    }
  }
}
```

### Why Idempotency Tracking?

**Purpose: Bug detection, not recovery**

Idempotency tracking is **optional** and serves to detect bugs in:
- SimulationEngine sending same tick twice
- Queue implementation delivering duplicates
- PersistenceService logic errors

In a correctly functioning system, `duplicate_ticks_detected` metric should **always be zero**. Non-zero value indicates a critical bug requiring investigation.

**Performance overhead:** ~50 microseconds per 1000-tick batch (0.05% of total operation time)

### Batch Size vs Timeout Trade-off

PersistenceService uses `drainTo(maxBatchSize, batchTimeoutSeconds)`:

**Small batches (e.g., 100 ticks):**
- ✅ Lower latency (data persisted quickly)
- ✅ Smaller DLQ messages on failure
- ❌ More storage files (overhead)
- ❌ Lower throughput (more write operations)

**Large batches (e.g., 10000 ticks):**
- ✅ Higher throughput (fewer write operations)
- ✅ Fewer storage files
- ❌ Higher latency (waits longer to accumulate)
- ❌ Larger DLQ messages on failure

**Recommended:** 1000 ticks balances throughput and latency for most scenarios.

## Implementation Requirements

### Package Structure

```
services/
└── PersistenceService.java   (main implementation)
```

**Note:** PersistenceService uses the existing `DeadLetterMessage` from `system_contracts.proto` for DLQ functionality. No new protobuf definitions are required.

### Using DeadLetterMessage for Failed Batches

PersistenceService leverages the existing generic `DeadLetterMessage` (defined in `system_contracts.proto`) for DLQ functionality. This maintains clean architecture by avoiding infrastructure→domain dependencies.

**DeadLetterMessage structure (already defined):**
```protobuf
message DeadLetterMessage {
    bytes original_message = 1;                // Serialized List<TickData>
    string message_type = 2;                   // "List<TickData>"
    int64 first_failure_time_ms = 3;           // Timestamp
    int64 last_failure_time_ms = 4;            // Timestamp
    int32 retry_count = 5;                     // Number of retries
    string failure_reason = 6;                 // Error message
    string source_service = 7;                 // Service name
    string source_queue = 8;                   // Queue name
    repeated string stack_trace_lines = 9;     // Stack trace
    map<string, string> metadata = 10;         // Batch metadata
}
```

**Usage for failed batches:**
```java
// Serialize batch to bytes
ByteArrayOutputStream bos = new ByteArrayOutputStream();
for (TickData tick : batch) {
    tick.writeDelimitedTo(bos);
}

// Store batch metadata in metadata map
DeadLetterMessage dlqMessage = DeadLetterMessage.newBuilder()
    .setOriginalMessage(ByteString.copyFrom(bos.toByteArray()))
    .setMessageType("List<TickData>")
    .setFirstFailureTimeMs(System.currentTimeMillis())
    .setLastFailureTimeMs(System.currentTimeMillis())
    .setRetryCount(retryAttempts)
    .setFailureReason(errorMessage)
    .setSourceService(getName())
    .setSourceQueue(queueName)
    .addAllStackTraceLines(stackTraceLines)
    .putMetadata("simulationRunId", simulationRunId)
    .putMetadata("startTick", String.valueOf(startTick))
    .putMetadata("endTick", String.valueOf(endTick))
    .putMetadata("tickCount", String.valueOf(batch.size()))
    .putMetadata("storageKey", storageKey)
    .putMetadata("exceptionType", exception.getClass().getName())
    .build();
```

**Recovery process (future implementation):**
```java
// Deserialize batch from DLQ message
ByteArrayInputStream bis = new ByteArrayInputStream(dlqMessage.getOriginalMessage().toByteArray());
List<TickData> recoveredBatch = new ArrayList<>();
while (bis.available() > 0) {
    recoveredBatch.add(TickData.parseDelimitedFrom(bis));
}

// Access metadata
String simulationRunId = dlqMessage.getMetadataOrThrow("simulationRunId");
long startTick = Long.parseLong(dlqMessage.getMetadataOrThrow("startTick"));
```

### PersistenceService Implementation

**File:** `src/main/java/org/evochora/datapipeline/services/PersistenceService.java`

**Class Declaration:**
```java
public class PersistenceService extends AbstractService implements IMonitorable
```

**Required Resources:**
- `input` (IInputQueueResource<TickData>) - Queue to drain ticks from
- `storage` (IStorageWriteResource) - Storage to write batches to

**Optional Resources:**
- `dlq` (IOutputQueueResource<DeadLetterMessage>) - Dead letter queue for failed batches
- `idempotencyTracker` (IIdempotencyTracker<String>) - Duplicate detection tracker

**Configuration Options:**
- `maxBatchSize` (int, default: 1000) - Maximum ticks per batch
- `batchTimeoutSeconds` (int, default: 5) - Maximum wait time for batch accumulation
- `maxRetries` (int, default: 3) - Retry attempts before sending to DLQ
- `retryBackoffMs` (int, default: 1000) - Initial retry delay, doubles each attempt

**Configuration Example:**
```hocon
persistence-service {
  className = "org.evochora.datapipeline.services.PersistenceService"
  resources {
    input = "queue-in:raw-tick-data"
    storage = "storage-write:storage-main"
    dlq = "queue-out:persistence-dlq"              # Optional
    idempotencyTracker = "persistence-idempotency" # Optional
  }
  options {
    maxBatchSize = 1000           # Ticks per batch
    batchTimeoutSeconds = 5       # Max wait for batch
    maxRetries = 3                # Retry attempts
    retryBackoffMs = 1000         # 1s, 2s, 4s backoff
  }
}
```

**Full Implementation:**

```java
package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.DeadLetterMessage;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that drains TickData batches from queues and persists them to storage.
 * <p>
 * PersistenceService provides reliable batch persistence with:
 * <ul>
 *   <li>Configurable batch size and timeout for flexible throughput/latency trade-offs</li>
 *   <li>Optional idempotency tracking to detect duplicate ticks (bug detection)</li>
 *   <li>Retry logic with exponential backoff for transient failures</li>
 *   <li>Dead letter queue support for unrecoverable failures</li>
 *   <li>Graceful shutdown that persists partial batches without data loss</li>
 * </ul>
 * <p>
 * Multiple instances can run concurrently as competing consumers on the same queue.
 * All instances should share the same idempotencyTracker and dlq resources.
 * <p>
 * <strong>Thread Safety:</strong> Each instance runs in its own thread. No synchronization
 * needed between instances - queue handles distribution, idempotency tracker is thread-safe.
 */
public class PersistenceService extends AbstractService implements IMonitorable {

    // Required resources
    private final IInputQueueResource<TickData> inputQueue;
    private final IStorageWriteResource storage;

    // Optional resources
    private final IOutputQueueResource<DeadLetterMessage> dlq;
    private final IIdempotencyTracker<String> idempotencyTracker;

    // Configuration
    private final int maxBatchSize;
    private final int batchTimeoutSeconds;
    private final int maxRetries;
    private final int retryBackoffMs;

    // Metrics
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong ticksWritten = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong batchesFailed = new AtomicLong(0);
    private final AtomicLong duplicateTicksDetected = new AtomicLong(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(0);

    public PersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Required resources
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);

        // Optional resources
        this.dlq = getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);
        this.idempotencyTracker = getOptionalResource("idempotencyTracker", IIdempotencyTracker.class).orElse(null);

        // Configuration with defaults
        this.maxBatchSize = options.hasPath("maxBatchSize") ? options.getInt("maxBatchSize") : 1000;
        this.batchTimeoutSeconds = options.hasPath("batchTimeoutSeconds") ? options.getInt("batchTimeoutSeconds") : 5;
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;
        this.retryBackoffMs = options.hasPath("retryBackoffMs") ? options.getInt("retryBackoffMs") : 1000;

        // Validation
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (batchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("batchTimeoutSeconds must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs cannot be negative");
        }

        log.info("PersistenceService initialized: maxBatchSize={}, batchTimeout={}s, maxRetries={}, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, maxRetries, idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            List<TickData> batch = new ArrayList<>();

            try {
                // Drain batch from queue with timeout
                int count = inputQueue.drainTo(batch, maxBatchSize, batchTimeoutSeconds, TimeUnit.SECONDS);

                if (count == 0) {
                    continue; // No data available, loop again
                }

                currentBatchSize.set(count);
                log.debug("Drained {} ticks from queue", count);

                // Process and persist batch
                processBatch(batch);

            } catch (InterruptedException e) {
                // Shutdown signal received during drain
                if (!batch.isEmpty()) {
                    log.info("Shutdown requested, writing partial batch of {} ticks", batch.size());
                    try {
                        processBatch(batch);
                    } catch (Exception ex) {
                        log.error("Failed to write partial batch during shutdown", ex);
                    }
                }
                throw e; // Re-throw to signal clean shutdown
            }
        }
    }

    private void processBatch(List<TickData> batch) {
        if (batch.isEmpty()) {
            return;
        }

        // Validate batch consistency (first and last tick must have same simulationRunId)
        String firstSimRunId = batch.get(0).getSimulationRunId();
        String lastSimRunId = batch.get(batch.size() - 1).getSimulationRunId();

        if (!firstSimRunId.equals(lastSimRunId)) {
            log.error("Batch consistency violation: first tick simulationRunId='{}', last tick simulationRunId='{}'",
                firstSimRunId, lastSimRunId);
            sendToDLQ(batch, "Batch contains mixed simulationRunIds", 0,
                new IllegalStateException("Mixed simulationRunIds"));
            batchesFailed.incrementAndGet();
            return;
        }

        // Optional: Check for duplicate ticks (bug detection)
        List<TickData> dedupedBatch = batch;
        if (idempotencyTracker != null) {
            dedupedBatch = deduplicateBatch(batch);
            if (dedupedBatch.isEmpty()) {
                log.warn("Entire batch was duplicates, skipping");
                return;
            }
        }

        // Generate storage key
        String simulationRunId = dedupedBatch.get(0).getSimulationRunId();
        long startTick = dedupedBatch.get(0).getTickNumber();
        long endTick = dedupedBatch.get(dedupedBatch.size() - 1).getTickNumber();
        String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
        String key = simulationRunId + "/" + filename;

        // Write batch with retry logic
        writeBatchWithRetry(key, dedupedBatch);
    }

    private List<TickData> deduplicateBatch(List<TickData> batch) {
        List<TickData> deduped = new ArrayList<>(batch.size());

        for (TickData tick : batch) {
            String idempotencyKey = tick.getSimulationRunId() + ":" + tick.getTickNumber();

            if (idempotencyTracker.contains(idempotencyKey)) {
                log.error("DUPLICATE TICK DETECTED: {} - This indicates a bug!", idempotencyKey);
                duplicateTicksDetected.incrementAndGet();
                continue; // Skip duplicate
            }

            idempotencyTracker.mark(idempotencyKey);
            deduped.add(tick);
        }

        return deduped;
    }

    private void writeBatchWithRetry(String key, List<TickData> batch) {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                writeBatch(key, batch);

                // Success
                batchesWritten.incrementAndGet();
                ticksWritten.addAndGet(batch.size());
                log.debug("Successfully wrote batch {} with {} ticks", key, batch.size());
                return;

            } catch (IOException e) {
                lastException = e;
                attempt++;

                if (attempt <= maxRetries) {
                    log.warn("Failed to write batch {} (attempt {}/{}): {}, retrying in {}ms",
                        key, attempt, maxRetries, e.getMessage(), backoff);

                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.info("Interrupted during retry backoff, aborting retries");
                        break;
                    }

                    backoff *= 2; // Exponential backoff
                } else {
                    log.error("Failed to write batch {} after {} retries: {}", key, maxRetries, e.getMessage());
                }
            }
        }

        // All retries exhausted
        sendToDLQ(batch, lastException.getMessage(), attempt - 1, lastException);
        batchesFailed.incrementAndGet();
    }

    private void writeBatch(String key, List<TickData> batch) throws IOException {
        long bytesInBatch = 0;

        try (MessageWriter writer = storage.openWriter(key)) {
            for (TickData tick : batch) {
                writer.writeMessage(tick);
                bytesInBatch += tick.getSerializedSize();
            }
        }

        bytesWritten.addAndGet(bytesInBatch);
    }

    private void sendToDLQ(List<TickData> batch, String errorMessage, int retryAttempts, Exception exception) {
        if (dlq == null) {
            log.error("Failed batch has no DLQ configured, data will be lost: {} ticks", batch.size());
            return;
        }

        try {
            FailedBatch failedBatch = FailedBatch.newBuilder()
                .setSimulationRunId(batch.get(0).getSimulationRunId())
                .setStartTick(batch.get(0).getTickNumber())
                .setEndTick(batch.get(batch.size() - 1).getTickNumber())
                .setTickCount(batch.size())
                .setStorageKey(batch.get(0).getSimulationRunId() + "/batch_" +
                    String.format("%019d_%019d.pb", batch.get(0).getTickNumber(),
                    batch.get(batch.size() - 1).getTickNumber()))
                .setErrorMessage(errorMessage)
                .setFailureTimestampMs(System.currentTimeMillis())
                .setRetryAttempts(retryAttempts)
                .setServiceName(getName())
                .setExceptionType(exception != null ? exception.getClass().getName() : "Unknown")
                .addAllTicks(batch)
                .build();

            if (dlq.offer(failedBatch)) {
                log.info("Sent failed batch to DLQ: {} ticks", batch.size());
            } else {
                log.error("DLQ is full, failed batch lost: {} ticks", batch.size());
            }

        } catch (Exception e) {
            log.error("Failed to send batch to DLQ", e);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "batches_written", batchesWritten.get(),
            "ticks_written", ticksWritten.get(),
            "bytes_written", bytesWritten.get(),
            "batches_failed", batchesFailed.get(),
            "duplicate_ticks_detected", duplicateTicksDetected.get(),
            "current_batch_size", currentBatchSize.get()
        );
    }

    @Override
    public boolean isHealthy() {
        return true;
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

### Coding Standards

#### Documentation Requirements
- **All public classes**: Comprehensive Javadoc with usage examples
- **All public methods**: Javadoc with @param, @return, @throws documentation
- **Thread safety**: Explicitly document in Javadoc
- **Shutdown behavior**: Document graceful shutdown guarantees
- **Configuration options**: Document defaults and validation rules

#### Naming Conventions
- **Classes**: PascalCase (PersistenceService, FailedBatch)
- **Methods**: camelCase (processBatch, writeBatchWithRetry, deduplicateBatch)
- **Constants**: UPPER_SNAKE_CASE
- **Variables**: camelCase

#### Error Handling
- **IOException**: Used for storage failures (write errors, disk full)
- **InterruptedException**: Used for shutdown coordination
- **IllegalArgumentException**: Used for invalid configuration
- **IllegalStateException**: Used for batch consistency violations
- **Error messages**: Must include context (batch info, retry count, tick counts)
- **Error recording**: Failed operations increment batchesFailed metric

#### Configuration Handling
- **TypeSafe Config**: Use Config.hasPath() before accessing optional config
- **Required validation**: Throw IllegalArgumentException with clear message if validation fails
- **Default values**: Provide sensible defaults for all optional configuration
- **Resource validation**: Throw IllegalArgumentException if required resources missing

#### Logging Requirements
- **INFO level**: Service lifecycle (started, stopped, shutdown events)
- **DEBUG level**: Batch operations (drained, written, sizes)
- **WARN level**: Retries, duplicate detection during normal operation
- **ERROR level**: Batch failures, DLQ send failures, consistency violations, duplicate ticks

**Required log messages:**
- Service initialization with configuration summary
- Batch drain size (DEBUG)
- Successful batch writes with size (DEBUG)
- Retry attempts with backoff (WARN)
- Batch write failures with reason (ERROR)
- Duplicate tick detection with key (ERROR) - **This should never happen in production!**
- Batch consistency violations (ERROR)
- DLQ send success/failure (INFO/ERROR)
- Partial batch on shutdown (INFO)

#### Performance Requirements
- **Batch processing**: Should sustain 10,000+ ticks/second per instance
- **Idempotency checking**: Must be ≤ 0.1% of total operation time
- **Metrics overhead**: Must be ≤ 1% of total operation time
- **Memory usage**: O(maxBatchSize) - no unbounded growth
- **No background threads**: All work done in service's run() thread

### Test Requirements

#### General Testing Guidelines

All tests for this implementation MUST follow these strict guidelines:

**1. Log Assertion with LogWatch:**
- **MANDATORY:** All tests MUST use `LogWatch` to explicitly allow or expect logs
- Tests will **FAIL** if any log is generated that was not explicitly allowed or expected
- **DO NOT** broadly allow all logs - only allow/expect logs that the test actually provokes
- This ensures tests fail if unexpected behavior (errors, warnings) occurs
- Example: If a test writes a batch, it MUST expect the corresponding DEBUG log

```java
@Test
@AllowLog(level = LogLevel.DEBUG, loggerPattern = ".*PersistenceService",
          messagePattern = "Successfully wrote batch.*")
void testBatchWrite() {
    // Test code that generates exactly this log
}
```

**2. State Polling with Awaitility:**
- **MANDATORY:** Use `awaitility` (already in dependencies) for waiting/polling
- **NEVER** use `Thread.sleep()` in tests - it makes tests slow and brittle
- Example: Wait for metrics to update, batch to be written, service to stop

```java
@Test
void testBatchPersistence() {
    service.startAsync();

    // CORRECT: Poll until condition is met
    await().atMost(10, SECONDS)
        .pollInterval(100, MILLISECONDS)
        .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

    // WRONG: Never do this!
    // Thread.sleep(1000);
}
```

**3. Test Tagging (Unit vs Integration):**

All tests MUST be tagged with exactly one tag:

```java
@Tag("unit")
class PersistenceServiceTest { }

@Tag("integration")
class SimulationToPersistenceIntegrationTest { }
```

**Criteria:**

| Aspect | Unit Test | Integration Test |
|--------|-----------|------------------|
| **Dependencies** | Mocked resources (Mockito) | Real resources (queues, storage) |
| **Speed** | < 100ms per test | May take seconds |
| **Scope** | Single class behavior | Multiple components working together |
| **Example** | `PersistenceServiceTest` (mocked queue, storage) | `SimulationToPersistenceIntegrationTest` (ServiceManager, real components) |

**Rule of thumb:** If the test uses Mockito mocks, it's unit. If it uses ServiceManager with real resources, it's integration.

**4. Artifact Cleanup:**
- **MANDATORY:** All tests that create files, directories, or other artifacts MUST clean up
- Use JUnit 5 `@TempDir` for temporary directories (auto-cleanup)
- If manual cleanup needed, use `@AfterEach` or try-with-resources

```java
@Test
void testWithStorage(@TempDir Path tempDir) {
    Config storageConfig = ConfigFactory.parseMap(
        Map.of("rootDirectory", tempDir.toString())
    );

    // Test code - tempDir automatically cleaned up after test
}
```

**5. Test Isolation:**
- Each test MUST be independent - no shared state between tests
- Tests MUST pass when run individually AND when run as a suite
- Use `@BeforeEach` to initialize fresh resources for each test

#### Unit Tests

**PersistenceServiceTest.java:**

Test with mocked dependencies (Mockito):

1. **Test batch draining and writing:**
   - Mock queue returns batch of ticks
   - Mock storage accepts writes
   - Verify correct storage key generated
   - Verify metrics updated correctly

2. **Test simulationRunId extraction:**
   - Verify extracted from first tick
   - Verify used in storage key

3. **Test batch validation:**
   - First and last tick have same simulationRunId → success
   - First and last tick differ → batch sent to DLQ, error logged

4. **Test idempotency checking (optional):**
   - When tracker configured: duplicates detected, skipped, metric incremented
   - When tracker not configured: no duplicate checking

5. **Test retry logic:**
   - First write fails → retry with backoff
   - Retry succeeds → metrics show success
   - All retries fail → batch sent to DLQ

6. **Test DLQ handling:**
   - When DLQ configured: failed batch sent with all fields populated
   - When DLQ not configured: error logged, data lost

7. **Test graceful shutdown:**
   - Interrupt during drainTo → partial batch written
   - Verify no data loss

8. **Test configuration validation:**
   - Invalid maxBatchSize → IllegalArgumentException
   - Invalid batchTimeout → IllegalArgumentException
   - Missing required resources → IllegalArgumentException

**Example unit test structure:**
```java
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class PersistenceServiceTest {

    private IInputQueueResource<TickData> mockQueue;
    private IStorageWriteResource mockStorage;
    private IOutputQueueResource<FailedBatch> mockDLQ;
    private IIdempotencyTracker mockTracker;
    private PersistenceService service;

    @BeforeEach
    void setUp() {
        mockQueue = mock(IInputQueueResource.class);
        mockStorage = mock(IStorageWriteResource.class);
        mockDLQ = mock(IOutputQueueResource.class);
        mockTracker = mock(IIdempotencyTracker.class);

        // Setup service with mocked resources
    }

    @Test
    @AllowLog(level = LogLevel.DEBUG, loggerPattern = ".*PersistenceService")
    void testSuccessfulBatchWrite() throws Exception {
        // Test implementation
    }
}
```

#### Integration Tests

**SimulationToPersistenceIntegrationTest.java:**

Test with ServiceManager and real resources:

1. **End-to-end flow:**
   - Start SimulationEngine → produces TickData to queue
   - Start PersistenceService → drains from queue, writes to storage
   - Verify batch files created with correct names
   - Verify tick count matches (SimulationEngine ticks = persisted ticks)

2. **Multiple persistence instances (competing consumers):**
   - Start 2 PersistenceService instances
   - Shared idempotency tracker
   - Verify no duplicates written
   - Verify all ticks persisted exactly once

3. **DLQ functionality:**
   - Configure persistence with DLQ
   - Simulate storage failure (invalid root directory)
   - Verify FailedBatch appears in DLQ with correct data

4. **Graceful shutdown:**
   - Start SimulationEngine + PersistenceService
   - Stop PersistenceService while ticks in queue
   - Verify all drained ticks persisted (no data loss)

**Example integration test structure:**
```java
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class SimulationToPersistenceIntegrationTest {

    @TempDir
    Path tempStorageDir;

    private ServiceManager serviceManager;

    @AfterEach
    void tearDown() {
        if (serviceManager != null) {
            serviceManager.stopAll();
        }
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    void testEndToEndPersistence() {
        // Create config with SimulationEngine, Queue, PersistenceService, Storage
        Config config = createIntegrationConfig();

        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        // Wait for batches to be written
        await().atMost(30, SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) > 0);

        // Verify data integrity
        verifyAllTicksPersisted();
    }
}
```

**Required test coverage:**
- ✅ Successful batch persistence
- ✅ Batch validation (simulationRunId consistency)
- ✅ Idempotency checking (with and without tracker)
- ✅ Retry logic with exponential backoff
- ✅ DLQ handling for failed batches
- ✅ Graceful shutdown with partial batches
- ✅ Configuration validation
- ✅ Metrics accuracy
- ✅ End-to-end integration with SimulationEngine
- ✅ Competing consumers with shared idempotency

## Future Usage Examples (Not Implemented in This Phase)

**Note:** The following examples show how PersistenceService will be used in production. These configurations are NOT part of this phase.

### Production Configuration

```hocon
pipeline {
  resources {
    # Storage
    storage-main {
      className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
      options {
        rootDirectory = "/data/evochora/simulations"
      }
    }

    # Queue from SimulationEngine
    raw-tick-data {
      className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
      options {
        capacity = 10000
      }
    }

    # DLQ for failed batches
    persistence-dlq {
      className = "org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue"
      options {
        capacity = 100
      }
    }

    # Shared idempotency tracker
    persistence-idempotency {
      className = "org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker"
      options {
        ttlSeconds = 3600        # 1 hour retention
        cleanupThresholdMessages = 100000
        cleanupIntervalSeconds = 300
      }
    }
  }

  services {
    # SimulationEngine (already configured)
    simulation-engine {
      className = "org.evochora.datapipeline.services.SimulationEngine"
      resources {
        tickData = "queue-out:raw-tick-data"
      }
      options {
        # ... simulation config ...
      }
    }

    # Primary persistence service
    persistence-1 {
      className = "org.evochora.datapipeline.services.PersistenceService"
      resources {
        input = "queue-in:raw-tick-data"
        storage = "storage-write:storage-main"
        dlq = "queue-out:persistence-dlq"
        idempotencyTracker = "persistence-idempotency"
      }
      options {
        maxBatchSize = 1000
        batchTimeoutSeconds = 5
        maxRetries = 3
        retryBackoffMs = 1000
      }
    }

    # Secondary persistence service (competing consumer for higher throughput)
    persistence-2 {
      className = "org.evochora.datapipeline.services.PersistenceService"
      resources {
        input = "queue-in:raw-tick-data"
        storage = "storage-write:storage-main"
        dlq = "queue-out:persistence-dlq"              # Shared
        idempotencyTracker = "persistence-idempotency" # Shared
      }
      options {
        maxBatchSize = 1000
        batchTimeoutSeconds = 5
        maxRetries = 3
        retryBackoffMs = 1000
      }
    }
  }

  startupSequence = ["simulation-engine", "persistence-1", "persistence-2"]
}
```

### Monitoring and Alerting

**Critical metrics to monitor:**

```
persistence-1.batches_failed > 0      → ALERT: Investigate storage/network issues
persistence-1.duplicate_ticks_detected > 0 → ALERT: Critical bug detected!
persistence-dlq.current_size > 10     → WARN: Multiple batch failures
persistence-1.current_batch_size < 100 → WARN: Queue starvation (simulation slow?)
```

**Dashboard queries:**
```
# Total persistence throughput
sum(rate(persistence-*.ticks_written[5m]))

# Failure rate
sum(rate(persistence-*.batches_failed[5m])) / sum(rate(persistence-*.batches_written[5m]))

# Average batch size (indicator of queue pressure)
avg(persistence-*.current_batch_size)
```

### DLQ Recovery Procedure

**Manual recovery process (implemented in future phase):**

1. **Inspect DLQ:**
   ```
   GET /api/pipeline/resources/persistence-dlq/status
   ```

2. **Extract FailedBatch messages:**
   ```java
   // Future recovery tool
   List<FailedBatch> failures = dlqReader.readAll();
   for (FailedBatch failed : failures) {
       if (isRecoverable(failed)) {
           retryWrite(failed);
       }
   }
   ```

3. **Analyze failure patterns:**
   - Check `exception_type` for common causes
   - Check `storage_key` for conflicting writes
   - Check `service_name` for specific instance issues

4. **Reprocess:**
   - Fix underlying issue (disk space, permissions, etc.)
   - Extract ticks from FailedBatch
   - Write to storage manually or re-enqueue

## Future Extensions (Deferred)

These features are explicitly deferred to maintain YAGNI principle:

1. **Batch compression** (gzip batches before writing)
2. **Batch checksums** (validate integrity on read)
3. **Smart batching** (combine small batches, split large batches)
4. **Priority queues** (persist critical simulations first)
5. **Batch metadata** (separate metadata.pb per simulation)
6. **Recovery service** (automatic DLQ reprocessing)
7. **Batch merging** (consolidate small files in background)
8. **Cloud storage support** (S3, GCS for persistence)

These can be added later without breaking the current API.

## Dependencies

**Compile dependencies:**
```
implementation("com.google.protobuf:protobuf-java:3.25.3")
implementation("com.typesafe:config:1.4.3")
implementation("org.slf4j:slf4j-api:2.0.13")
```

**Test dependencies:**
```
testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
testImplementation("org.mockito:mockito-core:5.12.0")
testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
testImplementation("org.awaitility:awaitility:4.2.1")
```

All dependencies already present in build.gradle.kts.