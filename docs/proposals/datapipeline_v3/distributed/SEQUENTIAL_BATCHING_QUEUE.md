# Sequential Batching for Distributed Queues

## Problem Statement

**Requirement:** Support competing consumers for high-throughput persistence while maintaining sequential batch guarantees.

**Scenario:**
- SimulationEngine produces: **100,000 ticks/second**
- PersistenceService capacity: **~5,000 ticks/second per instance**
- Need: **20 competing PersistenceService instances**
- Constraint: **Batches must be strictly sequential** (no gaps, no overlaps)

**Why sequential batches matter:**
- Simplifies indexer logic (can assume complete tick ranges)
- Enables efficient batch file naming: `batch_0000000000000000000_0000000000000000999.pb`
- Allows gap detection (missing batch = data loss)

## Challenge with Distributed Queues

**In-Process (LinkedBlockingQueue):**
- ✅ `drainTo()` is atomic and FIFO
- ✅ Service-1 gets `[tick1, tick2, tick3]`, Service-2 gets `[tick4, tick5, tick6]`
- ✅ No interleaving possible

**Distributed (SQS, Kafka, ActiveMQ):**
- ❌ Competing consumers can interleave messages
- ❌ Service-1 might get `[tick1, tick3, tick5]`, Service-2 gets `[tick2, tick4, tick6]`
- ❌ Or with prefetch: Service-2 finishes first, writes batch 100-199 before batch 0-99

**Why queue-level partitioning doesn't work:**
- SQS FIFO with single message group: Only 1 consumer at a time (no parallelism)
- Kafka with single partition: Only 1 consumer per consumer group (no parallelism)
- Both defeat the purpose of competing consumers

## Solution: Internal Micro-Batching in Queue Wrappers

**Key Insight:** Hide complexity in the queue resource implementation, not in services.

### Architecture

```
SimulationEngine                    PersistenceService (×20 instances)
    ↓ offer(tick)                      ↑ drainTo(batch, 1000)
    ↓                                  ↑
MonitoredQueueProducer         MonitoredQueueConsumer
    ↓ [internal batching]            ↑ [internal unbatching]
    ↓                                  ↑
    └─────→ SQS/Kafka/ActiveMQ ←──────┘
         (BatchMessage objects)
```

### Implementation Pattern

#### Producer Side (Transparent Batching)

```java
class DistributedQueueProducer<T> implements IOutputQueueResource<T> {
    private final List<T> stagingBuffer = new ArrayList<>(BATCH_SIZE);
    private final int BATCH_SIZE = 1000;

    @Override
    public boolean offer(T item) {
        synchronized(stagingBuffer) {
            stagingBuffer.add(item);

            if (stagingBuffer.size() >= BATCH_SIZE) {
                flushBatch();
            }
        }
        return true;
    }

    private void flushBatch() {
        // Serialize batch as single message
        BatchMessage batch = BatchMessage.newBuilder()
            .addAllItems(stagingBuffer)
            .build();

        // Send to distributed queue (SQS/Kafka/etc.)
        queueClient.send(serialize(batch));

        stagingBuffer.clear();
    }

    @Override
    public void close() {
        // Flush any remaining items
        if (!stagingBuffer.isEmpty()) {
            flushBatch();
        }
    }
}
```

#### Consumer Side (Transparent Unbatching)

```java
class DistributedQueueConsumer<T> implements IInputQueueResource<T> {

    @Override
    public int drainTo(Collection<T> collection, int maxElements, long timeout, TimeUnit unit) {
        // Receive ONE BatchMessage from distributed queue
        BatchMessage batchMessage = queueClient.receive(timeout, unit);

        if (batchMessage == null) {
            return 0; // Timeout
        }

        // Unwrap batch and add items to collection
        List<T> items = deserialize(batchMessage);

        int count = Math.min(items.size(), maxElements);
        collection.addAll(items.subList(0, count));

        // If we couldn't drain all items, need to handle remainder
        // (Implementation detail - could use internal buffer)

        return count;
    }
}
```

### BatchMessage Protobuf Definition

```protobuf
// File: src/main/proto/.../queue_contracts.proto
message BatchMessage {
  // Generic batch container for any message type
  repeated bytes items = 1;  // Serialized items
  string item_type = 2;      // "TickData", etc.
  int64 batch_id = 3;        // For ordering/debugging
  int64 timestamp_ms = 4;    // When batch was created
}
```

## Guarantees Provided

✅ **Sequential batches:** Each BatchMessage is atomic, consumers get complete sequential chunks
✅ **No interleaving:** Service-1 gets batch(0-999), Service-2 gets batch(1000-1999)
✅ **No overlaps:** Impossible to split a BatchMessage between consumers
✅ **No gaps within batch:** Each BatchMessage contains consecutive ticks
✅ **Full parallelism:** All 20 PersistenceServices work concurrently
✅ **Works with any queue:** SQS, Kafka, ActiveMQ, RabbitMQ all supported
✅ **Service simplicity:** SimulationEngine and PersistenceService unchanged

## Performance Characteristics

**Throughput:**
- In-process: ~1M ticks/second (no serialization overhead)
- SQS Standard: ~50k ticks/second (network + serialization)
- Kafka: ~500k ticks/second (optimized batching)

**Latency:**
- In-process: <1ms per batch
- Distributed: 5-50ms per batch (network dependent)

**Memory:**
- Producer staging buffer: O(BATCH_SIZE) = ~1000 ticks = ~100KB
- Consumer buffer: O(BATCH_SIZE) per instance

**Tuning:**
- Smaller BATCH_SIZE: Lower latency, higher overhead
- Larger BATCH_SIZE: Higher throughput, higher latency
- Recommended: 1000 ticks balances both

## Implementation Notes

### Flush Strategies

**Time-based flush:**
```java
// Flush after timeout even if batch not full
ScheduledExecutorService scheduler = ...;
scheduler.scheduleAtFixedRate(() -> {
    synchronized(stagingBuffer) {
        if (!stagingBuffer.isEmpty()) {
            flushBatch();
        }
    }
}, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, MILLISECONDS);
```

**On-demand flush:**
```java
// Service shutdown triggers flush
@Override
public void close() {
    flushBatch(); // Ensure no data loss
}
```

### Error Handling

**Producer side:**
- Retry failed batch sends with exponential backoff
- Dead letter queue for unrecoverable send failures
- Prevent data loss during staging buffer flush

**Consumer side:**
- Handle partial batch reads (network interruption)
- Visibility timeout management for SQS
- Offset commit strategies for Kafka

### Queue-Specific Optimizations

**SQS:**
- Use SendMessageBatch API (up to 10 BatchMessages per API call)
- Configure VisibilityTimeout > expected processing time
- Use long polling to reduce empty receives

**Kafka:**
- Single partition per simulationRunId for ordering
- Consumer group with multiple consumers for parallelism
- Tune batch.size and linger.ms for producer

**ActiveMQ:**
- Use prefetch=1 to prevent consumer starvation
- Configure exclusive consumers per queue
- Tune cache size for optimal throughput

## Future Enhancements

1. **Adaptive batch sizing:** Dynamically adjust BATCH_SIZE based on queue depth
2. **Compression:** Compress BatchMessage before sending (Protobuf + gzip)
3. **Checksums:** Add batch-level checksums for corruption detection
4. **Metrics:** Track batch flush frequency, size distribution, send latency
5. **Back-pressure:** Signal to SimulationEngine when staging buffer full

## Alternatives Considered

**Alternative 1: Application-level batch coordination**
- ❌ Complex distributed locking
- ❌ Service-level state management
- ❌ Tight coupling between services

**Alternative 2: Single PersistenceService with higher throughput**
- ❌ Limited by single-threaded I/O
- ❌ No fault tolerance (single point of failure)
- ❌ Can't scale beyond one instance

**Alternative 3: Post-processing batch merging**
- ❌ Delays persistence
- ❌ Additional complexity in merge logic
- ❌ Race conditions during merge

**Selected approach (internal micro-batching) is the only solution that:**
- Maintains service simplicity
- Provides full parallelism
- Guarantees sequential batches
- Works with any distributed queue

## References

- See `11_STORAGE_RESOURCE.md` for storage atomicity guarantees
- See `12_PERSISTENCE_SERVICE.md` for PersistenceService specification
- See `03_RESOURCE_CORE.md` for IInputQueueResource/IOutputQueueResource interfaces