# Data Pipeline V3 - Topic Infrastructure (Phase 14.2.1)

## Goal

Implement a Topic-based pub/sub resource abstraction with Chronicle Queue backend for reliable, ordered message delivery between persistence services and indexers. Topics replace complex batch discovery and gap detection logic with a simple notification pattern where writers publish batch/metadata availability and readers consume these notifications via competing consumer groups.

## Scope

**This phase implements:**
1. Topic API interfaces: `ITopicWriter<T extends Message>`, `ITopicReader<T extends Message>` (type-safe, Protobuf-only)
2. `TopicMessage<T extends Message>` wrapper with payload + metadata
3. Protobuf message types: `BatchInfo`, `MetadataInfo`
4. `ChronicleTopicResource<T extends Message>` implementation with:
   - Single-writer Chronicle Queue (via internal `InMemoryBlockingQueue`)
   - Consumer Groups for competing consumers
   - At-least-once delivery semantics
   - Message acknowledgment
5. Resource context wrappers: `TopicWriterWrapper`, `TopicReaderWrapper`
6. O(1) monitoring metrics
7. Integration tests for single/multi-writer/reader scenarios

**This phase does NOT implement:**
- Service integration (MetadataPersistenceService, PersistenceService, Indexers) - Phase 14.2.2+
- Dead Letter Queue integration - Phase 14.2.7
- Idempotency checking - Phase 14.2.6
- Tick buffering - Phase 14.2.6
- Cloud-mode Topic implementations (Chronicle Queue is in-process only)

## Success Criteria

Upon completion:
1. `ITopicWriter<T extends Message>` and `ITopicReader<T extends Message>` interfaces (type-safe, Protobuf-only)
2. `ChronicleTopicResource<T extends Message>` working with Chronicle Queue v5.x
3. Multi-writer safety via internal `InMemoryBlockingQueue` → single Chronicle writer thread
4. Consumer Groups ensure each message consumed by ONE consumer per group
5. `TopicMessage<T extends Message>` includes: payload, timestamp, messageId, consumerGroup
6. Protobuf contracts: `BatchInfo` (runId, storageKey, tickStart, tickEnd), `MetadataInfo` (runId, storageKey)
7. Writers: single `send(T)` method (may block briefly on internal buffering)
8. Readers: blocking `receive()`, timeout-based `poll(timeout, unit)`, `ack(TopicMessage)`
9. Tests verify: single writer/reader, multi-writer (queue coalescing), multi-reader (consumer groups)
10. All tests pass with proper cleanup (Chronicle Queue directories)

## Prerequisites

- Phase 0: API Foundation (completed) - `IResource`, `IMonitorable`
- Phase 1.2: Core Resource Implementation (completed) - `AbstractResource`, `IContextualResource`
- Phase 1.3: Queue Resource (completed) - `InMemoryBlockingQueue` (for internal multi-writer queue)
- Chronicle Queue v5.x dependency in `build.gradle.kts`

## Architectural Context

### Problem: Why Topics Replace Batch Discovery

**Old Approach (Complex):**
```
PersistenceService → Storage.writeBatch()
                         ↓
Indexer → POLL Storage.listBatchFiles() every 1s
       → Parse filenames, detect gaps
       → coordinator_batches table (claim, status)
       → coordinator_gaps table (track missing ranges)
       → Gap filling logic (search storage by tick range)
```

**New Approach (Simple):**
```
PersistenceService → Storage.writeBatch()
                  → Topic.send(BatchInfo{filename, ticks})
                         ↓
Indexer → Topic.receive() [BLOCKING, no polling!]
       → IIdempotencyTracker.checkAndMarkProcessed()
       → Process batch
       → Topic.ack()
```

**Benefits:**
- ✅ No polling delays (instant notification)
- ✅ No gap detection logic (Topic guarantees delivery)
- ✅ No database coordination tables
- ✅ Competing consumers via Consumer Groups (built-in)
- ✅ At-least-once delivery + Idempotency = Exactly-once semantics

### Topic Types

**1. `metadata-topic`** (MetadataInfo messages)
- Writers: `MetadataPersistenceService` (1x per run)
- Readers: `MetadataIndexer` (1x per run)
- Message: `{runId, storageKey="runId/metadata.pb", writtenAtMs}`

**2. `batch-topic`** (BatchInfo messages)
- Writers: Multiple `PersistenceService` instances (competing producers)
- Readers: Multiple `DummyIndexer` / `EnvironmentIndexer` instances (competing consumers)
- Message: `{runId, storageKey="runId/batch_xxx.pb", tickStart, tickEnd, writtenAtMs}`

### Chronicle Queue Architecture

**Single-Writer Requirement:**
Chronicle Queue requires a single writer per queue. To support multiple `PersistenceService` instances writing to `batch-topic`, we use an internal pattern:

```
PersistenceService-1 ┐
PersistenceService-2 ├─→ InMemoryBlockingQueue ──→ Single Chronicle Writer Thread
PersistenceService-3 ┘
```

**Implementation:**
- `ChronicleTopicResource` creates an internal `InMemoryBlockingQueue<T>`
- Each `TopicWriterWrapper` writes to this queue
- A dedicated background thread drains the queue and writes to Chronicle Queue
- This ensures single-writer safety

**Consumer Groups:**
- Chronicle Queue supports Tailers (readers) with Consumer Groups
- Each consumer group gets its own independent read position
- Messages are consumed ONCE per group (competing consumers within a group)
- Example: `DummyIndexer-1` and `DummyIndexer-2` both in `"dummy-indexer-group"` → each message consumed by ONE of them

## Technical Design

### 1. Protobuf Message Contracts

**File:** `src/main/proto/org/evochora/datapipeline/api/contracts/notification_contracts.proto`

```protobuf
syntax = "proto3";

package org.evochora.datapipeline.api.contracts;

option java_package = "org.evochora.datapipeline.api.contracts";
option java_outer_classname = "NotificationContracts";

/**
 * Notification that a batch file has been written to storage.
 * Sent by PersistenceService after successful storage.writeBatch().
 */
message BatchInfo {
  /** Simulation run ID this batch belongs to. */
  string simulation_run_id = 1;
  
  /** Storage key for the batch file (e.g., "runId/batch_0000010000_0000019990.pb"). */
  string storage_key = 2;
  
  /** First tick number in the batch (inclusive). */
  int64 tick_start = 3;
  
  /** Last tick number in the batch (inclusive). */
  int64 tick_end = 4;
  
  /** Unix timestamp (milliseconds) when the batch was written. */
  int64 written_at_ms = 5;
}

/**
 * Notification that a metadata file has been written to storage.
 * Sent by MetadataPersistenceService after successful storage.writeMessage().
 */
message MetadataInfo {
  /** Simulation run ID for this metadata. */
  string simulation_run_id = 1;
  
  /** Storage key for the metadata file (always "{runId}/metadata.pb"). */
  string storage_key = 2;
  
  /** Unix timestamp (milliseconds) when the metadata was written. */
  int64 written_at_ms = 3;
}
```

### 2. Topic API Interfaces

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/ITopicWriter.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Interface for writing messages to a topic.
 * <p>
 * Writers publish messages to a topic which are then delivered to all consumer groups.
 * Within a consumer group, each message is consumed by exactly one consumer (competing consumers).
 * <p>
 * <strong>Architectural Note:</strong> Unlike queues, topics do not have a bounded capacity
 * concept (they scale with disk/cloud storage). Therefore, this interface provides only a
 * single {@link #send(Object)} method that may block briefly during internal buffering,
 * but does not have separate blocking/non-blocking variants like {@link IOutputQueueResource}.
 * <p>
 * <strong>Blocking Behavior:</strong> The {@link #send(Object)} method may block briefly
 * depending on the implementation:
 * <ul>
 *   <li><strong>Chronicle Queue:</strong> Blocks on internal queue during multi-writer coalescing.</li>
 *   <li><strong>Kafka:</strong> Blocks if producer buffer is full (configurable).</li>
 *   <li><strong>Cloud (Kinesis/SQS):</strong> Blocks during retry on transient errors.</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> The {@link #send(Object)} method is thread-safe.
 * Multiple writers can send concurrently.
 * <p>
 * <strong>Usage Type:</strong> {@code topic-write}
 *
 * @param <T> The type of messages to write (must be a Protobuf {@link Message}).
 */
public interface ITopicWriter<T extends Message> extends IResource {
    
    /**
     * Sends a message to the topic.
     * <p>
     * This method may block briefly depending on the implementation:
     * <ul>
     *   <li><strong>Chronicle Queue:</strong> Blocks on internal queue during multi-writer coalescing.
     *       The internal queue has bounded capacity (configured via {@code internalQueueCapacity}).</li>
     *   <li><strong>Kafka:</strong> Blocks if producer buffer is full (configurable via Kafka settings).</li>
     *   <li><strong>Cloud (Kinesis/SQS):</strong> Blocks during retry on transient errors.</li>
     * </ul>
     * <p>
     * Unlike {@link IOutputQueueResource#put(Object)}, this method does not have separate
     * blocking/non-blocking variants because topics do not have a "bounded capacity" as a
     * first-class concept. Internal buffering is an implementation detail.
     * <p>
     * <strong>Error Handling:</strong> Persistent write failures (e.g., disk full, network
     * errors) are tracked via {@link IMonitorable#getErrors()} and may cause the resource
     * to enter an unhealthy state ({@link IMonitorable#isHealthy()} returns false).
     *
     * @param message The message to send (must not be null).
     * @throws InterruptedException if interrupted while waiting for internal resources.
     * @throws NullPointerException if message is null.
     */
    void send(T message) throws InterruptedException;
}
```

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/ITopicReader.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Interface for reading messages from a topic.
 * <p>
 * Readers consume messages from a topic using consumer groups. Each consumer specifies
 * a consumer group name in the resource binding URI parameters. Messages are consumed
 * ONCE per consumer group, but within a group, each message is consumed by only ONE consumer
 * (competing consumers).
 * <p>
 * <strong>Architectural Note:</strong> This interface follows the same pattern as
 * {@link IInputQueueResource} for consistency. Topic resources implement {@link IContextualResource}
 * and provide wrappers that implement this interface.
 * <p>
 * <strong>Acknowledgment:</strong> Messages MUST be acknowledged using {@link #ack(TopicMessage)}
 * after successful processing. Unacknowledged messages will be redelivered after restart.
 * <p>
 * <strong>Consumer Group:</strong> Specified via {@code consumerGroup} parameter in
 * resource binding URI (e.g., {@code "topic-read:batch-topic?consumerGroup=dummy-indexer"}).
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe. Multiple readers in the
 * same consumer group will compete for messages (each message consumed by ONE reader).
 * <p>
 * <strong>Usage Type:</strong> {@code topic-read}
 *
 * @param <T> The type of messages to read (must be a Protobuf {@link Message}).
 */
public interface ITopicReader<T extends Message> extends IResource {
    
    /**
     * Receives a message from the topic, waiting if necessary until a message is available.
     * <p>
     * This is a blocking operation. Blocks until a message is available or the thread is interrupted.
     * <p>
     * <strong>IMPORTANT:</strong> Callers MUST call {@link #ack(TopicMessage)} after successful
     * processing to mark the message as consumed. Failure to acknowledge will result in redelivery.
     *
     * @return The next message from the topic, wrapped in {@link TopicMessage}.
     * @throws InterruptedException if interrupted while waiting.
     */
    TopicMessage<T> receive() throws InterruptedException;
    
    /**
     * Receives a message from the topic, waiting up to the specified time for a message to be available.
     * <p>
     * This operation blocks for at most the specified timeout.
     * <p>
     * <strong>IMPORTANT:</strong> Callers MUST call {@link #ack(TopicMessage)} on the returned
     * message (if present) after successful processing.
     *
     * @param timeout How long to wait before giving up, in units of {@code unit}.
     * @param unit The time unit of the timeout parameter.
     * @return An {@link Optional} containing the message if available, or empty if timeout elapsed.
     * @throws InterruptedException if interrupted while waiting.
     * @throws NullPointerException if unit is null.
     */
    Optional<TopicMessage<T>> poll(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * Acknowledges that a message has been successfully processed.
     * <p>
     * This method MUST be called after processing each message received via {@link #receive()}
     * or {@link #poll(long, TimeUnit)}. Acknowledged messages will not be redelivered.
     * <p>
     * <strong>Idempotency Note:</strong> It is safe to acknowledge the same message multiple times.
     * Duplicate acknowledgments are ignored.
     *
     * @param message The message to acknowledge (must not be null).
     * @throws NullPointerException if message is null.
     */
    void ack(TopicMessage<T> message);
}
```

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/TopicMessage.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;

import java.util.Objects;

/**
 * Wrapper for messages read from a topic, including metadata for acknowledgment.
 * <p>
 * This class is immutable and thread-safe.
 * <p>
 * <strong>Consumer Group Access:</strong> The consumer group name is available via
 * {@link #consumerGroup()}. This is the recommended way to access the consumer group
 * information, as {@link ITopicReader} does not expose it directly (it's an
 * implementation detail).
 *
 * @param <T> The type of the message payload (must be a Protobuf {@link Message}).
 */
public final class TopicMessage<T extends Message> {
    
    private final T payload;
    private final long timestamp;
    private final String messageId;
    private final String consumerGroup;
    private final long index; // Chronicle Queue index for ack
    
    /**
     * Creates a new TopicMessage.
     *
     * @param payload The message payload (must not be null).
     * @param timestamp Unix timestamp in milliseconds when message was written.
     * @param messageId Unique identifier for this message.
     * @param consumerGroup Consumer group this message was read from.
     * @param index Internal index for acknowledgment (Chronicle Queue index).
     */
    public TopicMessage(T payload, long timestamp, String messageId, String consumerGroup, long index) {
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
        this.timestamp = timestamp;
        this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        this.index = index;
    }
    
    /**
     * Gets the message payload.
     *
     * @return The payload.
     */
    public T payload() {
        return payload;
    }
    
    /**
     * Gets the timestamp when the message was written to the topic.
     *
     * @return Unix timestamp in milliseconds.
     */
    public long timestamp() {
        return timestamp;
    }
    
    /**
     * Gets the unique message identifier.
     *
     * @return The message ID.
     */
    public String messageId() {
        return messageId;
    }
    
    /**
     * Gets the consumer group this message was read from.
     * <p>
     * <strong>Note:</strong> This is the recommended way to access the consumer group name.
     * {@link ITopicReader} does not expose {@code getConsumerGroup()} because the consumer
     * group is an implementation detail that services typically don't need. If needed,
     * it's available here in the message metadata.
     *
     * @return The consumer group name.
     */
    public String consumerGroup() {
        return consumerGroup;
    }
    
    /**
     * Gets the internal index for acknowledgment.
     * <p>
     * This is package-private as it's only used internally by the topic implementation.
     *
     * @return The Chronicle Queue index.
     */
    long index() {
        return index;
    }
    
    @Override
    public String toString() {
        return String.format("TopicMessage{messageId=%s, consumerGroup=%s, timestamp=%d, payload=%s}",
            messageId, consumerGroup, timestamp, payload.getClass().getSimpleName());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopicMessage<?> that)) return false;
        return timestamp == that.timestamp &&
               index == that.index &&
               Objects.equals(messageId, that.messageId) &&
               Objects.equals(consumerGroup, that.consumerGroup);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumerGroup, timestamp, index);
    }
}
```

### 3. Chronicle Queue Implementation

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/ChronicleTopicResource.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.datapipeline.resources.topics.wrappers.TopicReaderWrapper;
import org.evochora.datapipeline.resources.topics.wrappers.TopicWriterWrapper;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chronicle Queue-based topic implementation for in-process pub/sub messaging.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Single-writer Chronicle Queue (enforced via internal {@link InMemoryBlockingQueue})</li>
 *   <li>Multiple independent consumer groups (each with own Tailer)</li>
 *   <li>At-least-once delivery semantics</li>
 *   <li>Ordered message delivery within a writer</li>
 *   <li>Persistent messages (survive restarts)</li>
 * </ul>
 * <p>
 * <strong>Multi-Writer Support:</strong>
 * Chronicle Queue requires a single writer. To support multiple {@link ITopicWriter} instances:
 * <ol>
 *   <li>All writers send to an internal {@link InMemoryBlockingQueue}</li>
 *   <li>A dedicated background thread drains the queue and writes to Chronicle Queue</li>
 *   <li>This ensures single-writer safety while allowing concurrent writers</li>
 * </ol>
 * <p>
 * <strong>Consumer Groups:</strong>
 * Each consumer group gets its own Chronicle Tailer (reader). Tailers maintain independent
 * read positions, allowing multiple groups to consume all messages. Within a group, multiple
 * readers compete (though Chronicle's single-file design means only one reader is efficient).
 * <p>
 * <strong>Lifecycle:</strong>
 * Resources are contextual and provide wrappers via {@link #getWrappedResource(ResourceContext)}.
 * The background writer thread is started on first write and stopped when all wrappers are closed.
 * <p>
 * <strong>Storage:</strong>
 * Chronicle Queue stores messages in {@code queueDirectory} (configured, default: {@code data/topics/{name}}).
 * Old queue files are cleaned up based on {@code retentionDays} (default: 7 days).
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe.
 * <p>
 * <strong>Contextual Resource:</strong> Implements {@link IContextualResource} and provides
 * {@link ITopicWriter} and {@link ITopicReader} wrappers based on usage type.
 *
 * @param <T> The type of messages (must be a Protobuf {@link Message}).
 */
public class ChronicleTopicResource<T extends Message> extends AbstractResource implements IContextualResource, AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(ChronicleTopicResource.class);
    
    private final Class<T> messageType;
    private final Path queueDirectory;
    private final int retentionDays;
    private final int internalQueueCapacity;
    private final int metricsWindowSeconds;
    
    // Chronicle Queue instances
    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private final Map<String, ExcerptTailer> tailers = new ConcurrentHashMap<>();
    
    // Multi-writer support
    private final InMemoryBlockingQueue<T> internalQueue;
    private final Thread writerThread;
    private final AtomicBoolean writerThreadRunning = new AtomicBoolean(false);
    
    // Metrics - O(1) monitoring
    private final AtomicLong messagesWritten = new AtomicLong(0);
    private final AtomicLong messagesRead = new AtomicLong(0);
    private final AtomicLong messagesAcknowledged = new AtomicLong(0);
    private final SlidingWindowCounter writeThroughput;
    private final SlidingWindowCounter readThroughput;
    
    /**
     * Creates a new ChronicleTopicResource.
     * <p>
     * <strong>Configuration Options:</strong>
     * <ul>
     *   <li>{@code messageType} (required): Fully qualified class name of the Protobuf message type</li>
     *   <li>{@code queueDirectory} (optional): Path to Chronicle Queue directory (default: {@code data/topics/{name}})</li>
     *   <li>{@code retentionDays} (optional): Days to retain old queue files (default: 7)</li>
     *   <li>{@code internalQueueCapacity} (optional): Capacity of internal multi-writer queue (default: 10000)</li>
     *   <li>{@code metricsWindowSeconds} (optional): Sliding window for throughput metrics (default: 5)</li>
     * </ul>
     *
     * @param name The resource name.
     * @param options The configuration options.
     * @throws IllegalArgumentException if configuration is invalid or messageType cannot be loaded.
     */
    @SuppressWarnings("unchecked")
    public ChronicleTopicResource(String name, Config options) {
        super(name, options);
        
        Config defaults = ConfigFactory.parseMap(Map.of(
            "queueDirectory", "data/topics/" + name,
            "retentionDays", 7,
            "internalQueueCapacity", 10000,
            "metricsWindowSeconds", 5
        ));
        Config finalConfig = options.withFallback(defaults);
        
        try {
            // Load message type class
            String messageTypeName = finalConfig.getString("messageType");
            this.messageType = (Class<T>) Class.forName(messageTypeName);
            if (!Message.class.isAssignableFrom(this.messageType)) {
                throw new IllegalArgumentException("messageType must be a Protobuf Message: " + messageTypeName);
            }
            
            this.queueDirectory = Paths.get(finalConfig.getString("queueDirectory"));
            this.retentionDays = finalConfig.getInt("retentionDays");
            this.internalQueueCapacity = finalConfig.getInt("internalQueueCapacity");
            this.metricsWindowSeconds = finalConfig.getInt("metricsWindowSeconds");
            
            // Validation
            if (retentionDays < 1) {
                throw new IllegalArgumentException("retentionDays must be >= 1 for topic '" + name + "'");
            }
            if (internalQueueCapacity < 100) {
                throw new IllegalArgumentException("internalQueueCapacity must be >= 100 for topic '" + name + "'");
            }
            if (metricsWindowSeconds < 1) {
                throw new IllegalArgumentException("metricsWindowSeconds must be >= 1 for topic '" + name + "'");
            }
            
            // Create queue directory
            Files.createDirectories(queueDirectory);
            
            // Initialize Chronicle Queue
            this.queue = SingleChronicleQueueBuilder.single(queueDirectory.toFile()).build();
            this.appender = queue.acquireAppender();
            
            // Create internal queue for multi-writer support
            Config queueConfig = ConfigFactory.parseMap(Map.of(
                "capacity", internalQueueCapacity,
                "metricsWindowSeconds", 5,
                "disableTimestamps", false
            ));
            this.internalQueue = new InMemoryBlockingQueue<>(name + "-internal", queueConfig);
            
            // Start background writer thread
            this.writerThread = new Thread(this::runWriterThread, name + "-writer");
            this.writerThread.setDaemon(true);
            
            // Initialize throughput trackers (O(1) monitoring)
            this.writeThroughput = new SlidingWindowCounter(metricsWindowSeconds);
            this.readThroughput = new SlidingWindowCounter(metricsWindowSeconds);
            
            log.info("ChronicleTopicResource '{}' initialized: directory={}, retention={}d, internalQueueCapacity={}, metricsWindow={}s",
                name, queueDirectory, retentionDays, internalQueueCapacity, metricsWindowSeconds);
            
        } catch (ConfigException e) {
            throw new IllegalArgumentException("Invalid configuration for ChronicleTopicResource '" + name + "'", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot load messageType class for topic '" + name + "'", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot create queue directory for topic '" + name + "'", e);
        }
    }
    
    /**
     * Background thread that drains the internal queue and writes to Chronicle Queue.
     * <p>
     * This ensures single-writer semantics for Chronicle Queue while allowing multiple
     * {@link ITopicWriter} instances to write concurrently to the internal queue.
     */
    private void runWriterThread() {
        log.debug("Writer thread started for topic '{}'", getResourceName());
        writerThreadRunning.set(true);
        
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Block waiting for messages (1 second timeout to check interrupt)
                    T message = internalQueue.take();
                    
                    // Write to Chronicle Queue
                    appender.writeBytes(b -> {
                        try {
                            byte[] bytes = message.toByteArray();
                            b.writeInt(bytes.length);
                            b.write(bytes);
                        } catch (Exception e) {
                            log.warn("Failed to serialize message to Chronicle Queue for topic '{}'", getResourceName());
                            recordError("SERIALIZATION_ERROR", "Failed to serialize message", e.getMessage());
                        }
                    });
                    
                    messagesWritten.incrementAndGet();
                    writeThroughput.recordCount();
                    log.debug("Wrote message to Chronicle Queue for topic '{}'", getResourceName());
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            writerThreadRunning.set(false);
            log.debug("Writer thread stopped for topic '{}'", getResourceName());
        }
    }
    
    /**
     * Starts the background writer thread if not already running.
     * <p>
     * Called by {@link TopicWriterWrapper} on first write.
     */
    void startWriterThread() {
        if (!writerThreadRunning.get() && !writerThread.isAlive()) {
            writerThread.start();
        }
    }
    
    /**
     * Gets the internal queue for writing messages.
     * <p>
     * Package-private, used by {@link TopicWriterWrapper}.
     *
     * @return The internal queue.
     */
    InMemoryBlockingQueue<T> getInternalQueue() {
        return internalQueue;
    }
    
    /**
     * Reads a message from the topic for the specified consumer group.
     * <p>
     * This method blocks until a message is available or the timeout elapses.
     * <p>
     * Package-private, used by {@link TopicReaderWrapper}.
     *
     * @param consumerGroup The consumer group name.
     * @param timeout Timeout duration.
     * @param unit Timeout unit.
     * @return The message, or null if timeout elapsed.
     */
    T readMessage(String consumerGroup, long timeout, TimeUnit unit) throws InterruptedException {
        ExcerptTailer tailer = tailers.computeIfAbsent(consumerGroup, cg -> {
            log.debug("Creating Chronicle Tailer for consumer group '{}' in topic '{}'", cg, getResourceName());
            return queue.createTailer(cg);
        });
        
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        
        while (System.nanoTime() < deadlineNanos) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while reading from topic '" + getResourceName() + "'");
            }
            
            boolean hasMessage = tailer.readBytes(b -> {
                try {
                    int length = b.readInt();
                    byte[] bytes = new byte[length];
                    b.read(bytes);
                    
                    // Deserialize using Protobuf
                    @SuppressWarnings("unchecked")
                    T message = (T) messageType.getMethod("parseFrom", byte[].class).invoke(null, (Object) bytes);
                    
                    messagesRead.incrementAndGet();
                    readThroughput.recordCount();
                    log.debug("Read message from Chronicle Queue for consumer group '{}' in topic '{}'", consumerGroup, getResourceName());
                    
                    // Note: We can't return the message directly from this lambda
                    // This is a limitation of Chronicle's API
                    // Solution: Store in a ThreadLocal or use a different approach
                    
                } catch (Exception e) {
                    log.warn("Failed to deserialize message from Chronicle Queue for topic '{}'", getResourceName());
                    recordError("DESERIALIZATION_ERROR", "Failed to deserialize message", e.getMessage());
                }
            });
            
            if (hasMessage) {
                // TODO: Return the actual message (Chronicle API limitation workaround needed)
                // For now, return null and handle in wrapper
                return null;
            }
            
            // Sleep briefly before retrying
            Thread.sleep(10);
        }
        
        return null; // Timeout
    }
    
    /**
     * Acknowledges a message for the specified consumer group.
     * <p>
     * For Chronicle Queue, acknowledgment is implicit (Tailer position is advanced on read).
     * This method is a no-op but kept for API consistency.
     * <p>
     * Package-private, used by {@link TopicReaderWrapper}.
     *
     * @param consumerGroup The consumer group name.
     * @param index The message index.
     */
    void acknowledgeMessage(String consumerGroup, long index) {
        messagesAcknowledged.incrementAndGet();
        log.debug("Acknowledged message {} for consumer group '{}' in topic '{}'", index, consumerGroup, getResourceName());
    }
    
    /**
     * Gets the message type class for this topic.
     * <p>
     * Package-private, used internally for deserialization.
     *
     * @return The message type class.
     */
    Class<T> getMessageType() {
        return messageType;
    }
    
    /**
     * Records an error for this topic.
     * <p>
     * Package-private, used by wrappers to delegate error recording to the underlying resource.
     * This allows wrappers to participate in the resource's error tracking and health monitoring.
     *
     * @param code Error code for categorization.
     * @param message Human-readable error message.
     * @param details Additional context about the error.
     */
    void recordTopicError(String code, String message, String details) {
        recordError(code, message, details);
    }
    
    @Override
    public UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("Topic resource '" + getResourceName() + "' requires a non-null usageType");
        }
        
        return switch (usageType) {
            case "topic-write" -> internalQueue.getUsageState("queue-out"); // Writer writes to internal queue
            case "topic-read" -> UsageState.ACTIVE; // Chronicle Queue is always readable
            default -> throw new IllegalArgumentException(String.format(
                "Unknown usageType '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                usageType, getResourceName()));
        };
    }
    
    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Topic resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: topic-write, topic-read",
                getResourceName(), getResourceName()));
        }
        
        return switch (context.usageType()) {
            case "topic-write" -> new TopicWriterWrapper<>(this, context);
            case "topic-read" -> {
                // Consumer group is REQUIRED for readers
                String consumerGroup = context.parameters().get("consumerGroup");
                if (consumerGroup == null || consumerGroup.isBlank()) {
                    throw new IllegalArgumentException(String.format(
                        "Topic reader for '%s' requires 'consumerGroup' parameter. " +
                        "Example: 'topic-read:%s?consumerGroup=my-group'",
                        getResourceName(), getResourceName()));
                }
                yield new TopicReaderWrapper<>(this, context, consumerGroup);
            }
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                context.usageType(), getResourceName()));
        };
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("messages_written", messagesWritten.get());
        metrics.put("messages_read", messagesRead.get());
        metrics.put("messages_acknowledged", messagesAcknowledged.get());
        metrics.put("write_throughput_per_sec", writeThroughput.getRate());
        metrics.put("read_throughput_per_sec", readThroughput.getRate());
        metrics.put("internal_queue_size", internalQueue.getMetrics().get("current_size"));
        metrics.put("consumer_groups", tailers.size());
        metrics.put("writer_thread_running", writerThreadRunning.get() ? 1 : 0);
    }
    
    /**
     * Closes the topic and releases all resources.
     * <p>
     * This method:
     * <ul>
     *   <li>Stops the background writer thread</li>
     *   <li>Closes all Chronicle Tailers</li>
     *   <li>Closes the Chronicle Queue</li>
     *   <li>Optionally deletes queue directory (if configured)</li>
     * </ul>
     * <p>
     * Called automatically when the resource is no longer needed, or via try-with-resources.
     * <p>
     * <strong>Implements:</strong> {@link AutoCloseable}
     */
    @Override
    public void close() {
        log.debug("Closing ChronicleTopicResource '{}'", getResourceName());
        
        // Stop writer thread
        writerThread.interrupt();
        try {
            writerThread.join(5000); // Wait up to 5 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for writer thread to stop for topic '{}'", getResourceName());
        }
        
        // Close tailers
        tailers.values().forEach(tailer -> {
            try {
                tailer.close();
            } catch (Exception e) {
                log.debug("Error closing tailer for topic '{}': {}", getResourceName(), e.getMessage());
            }
        });
        tailers.clear();
        
        // Close queue
        try {
            queue.close();
        } catch (Exception e) {
            log.debug("Error closing Chronicle Queue for topic '{}': {}", getResourceName(), e.getMessage());
        }
        
        log.info("ChronicleTopicResource '{}' closed", getResourceName());
    }
}
```

**NOTE:** The Chronicle Queue implementation above has a known API limitation in the `readMessage()` method where we cannot directly return the deserialized message from the `readBytes()` lambda. This will be resolved using a ThreadLocal or alternative pattern in the actual implementation. For the spec, this demonstrates the architecture.

### 4. Context Wrappers

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/wrappers/TopicWriterWrapper.java`

```java
package org.evochora.datapipeline.resources.topics.wrappers;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.topics.ChronicleTopicResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper that provides {@link ITopicWriter} capability for {@link ChronicleTopicResource}.
 * <p>
 * This wrapper delegates write operations to the topic's internal {@link InMemoryBlockingQueue},
 * which is then drained by a background thread that writes to Chronicle Queue (ensuring single-writer semantics).
 * <p>
 * <strong>Architecture Note:</strong> This follows the same pattern as {@link MonitoredQueueProducer}
 * for consistency across all contextual resource wrappers.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe.
 * <p>
 * <strong>Metrics:</strong> Tracks messages sent, failures, and delegates to internal queue metrics.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public class TopicWriterWrapper<T extends Message> implements ITopicWriter<T> {
    
    private static final Logger log = LoggerFactory.getLogger(TopicWriterWrapper.class);
    
    private final ChronicleTopicResource<T> topic;
    private final ResourceContext context;
    
    // Metrics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    
    /**
     * Creates a new TopicWriterWrapper.
     *
     * @param topic The underlying Chronicle topic resource.
     * @param context The resource context.
     */
    public TopicWriterWrapper(ChronicleTopicResource<T> topic, ResourceContext context) {
        this.topic = topic;
        this.context = context;
        
        // Start the background writer thread if not already running
        topic.startWriterThread();
        
        log.debug("TopicWriterWrapper created for service '{}', port '{}', topic '{}'",
            context.serviceName(), context.portName(), topic.getResourceName());
    }
    
    @Override
    public void send(T message) throws InterruptedException {
        if (message == null) {
            throw new NullPointerException("message cannot be null");
        }
        
        try {
            topic.getInternalQueue().put(message);
            messagesSent.incrementAndGet();
            log.debug("Sent message to topic '{}' from service '{}'", topic.getResourceName(), context.serviceName());
        } catch (InterruptedException e) {
            messagesFailed.incrementAndGet();
            log.debug("Interrupted while sending message to topic '{}' from service '{}'",
                topic.getResourceName(), context.serviceName());
            throw e;
        }
    }
    
    @Override
    public UsageState getUsageState(String usageType) {
        return topic.getUsageState("topic-write");
    }
    
    @Override
    public String getResourceName() {
        return topic.getResourceName();
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new java.util.LinkedHashMap<>();
        metrics.put("messages_sent", messagesSent.get());
        metrics.put("messages_failed", messagesFailed.get());
        
        // Include internal queue metrics
        Map<String, Number> queueMetrics = topic.getInternalQueue().getMetrics();
        queueMetrics.forEach((key, value) -> metrics.put("internal_queue_" + key, value));
        
        return metrics;
    }
}
```

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/wrappers/TopicReaderWrapper.java`

```java
package org.evochora.datapipeline.resources.topics.wrappers;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.resources.topics.ChronicleTopicResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper that provides {@link ITopicReader} capability for {@link ChronicleTopicResource}.
 * <p>
 * This wrapper reads messages from Chronicle Queue using a consumer-group-specific Tailer.
 * <p>
 * <strong>Architecture Note:</strong> This follows the same pattern as {@link MonitoredQueueConsumer}
 * for consistency across all contextual resource wrappers.
 * <p>
 * <strong>Consumer Group:</strong> Specified in resource binding URI via {@code consumerGroup} parameter.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe.
 * <p>
 * <strong>Metrics:</strong> Tracks messages received, acknowledged, and read errors.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public class TopicReaderWrapper<T extends Message> implements ITopicReader<T> {
    
    private static final Logger log = LoggerFactory.getLogger(TopicReaderWrapper.class);
    
    private final ChronicleTopicResource<T> topic;
    private final ResourceContext context;
    private final String consumerGroup;
    
    // Metrics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesAcknowledged = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    
    /**
     * Creates a new TopicReaderWrapper.
     *
     * @param topic The underlying Chronicle topic resource.
     * @param context The resource context.
     * @param consumerGroup The consumer group name (from URI parameters).
     */
    public TopicReaderWrapper(ChronicleTopicResource<T> topic, ResourceContext context, String consumerGroup) {
        this.topic = topic;
        this.context = context;
        this.consumerGroup = consumerGroup;
        
        log.debug("TopicReaderWrapper created for service '{}', port '{}', topic '{}', consumerGroup '{}'",
            context.serviceName(), context.portName(), topic.getResourceName(), consumerGroup);
    }
    
    @Override
    public TopicMessage<T> receive() throws InterruptedException {
        // Block indefinitely (use a large timeout and loop)
        while (!Thread.currentThread().isInterrupted()) {
            Optional<TopicMessage<T>> message = poll(1, TimeUnit.SECONDS);
            if (message.isPresent()) {
                return message.get();
            }
        }
        throw new InterruptedException("Interrupted while receiving from topic '" + topic.getResourceName() + "'");
    }
    
    @Override
    public Optional<TopicMessage<T>> poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit cannot be null");
        }
        
        try {
            T payload = topic.readMessage(consumerGroup, timeout, unit);
            if (payload == null) {
                return Optional.empty(); // Timeout
            }
            
            // Wrap in TopicMessage
            long timestamp = System.currentTimeMillis();
            String messageId = java.util.UUID.randomUUID().toString();
            long index = 0; // TODO: Get actual Chronicle Queue index
            
            TopicMessage<T> message = new TopicMessage<>(payload, timestamp, messageId, consumerGroup, index);
            messagesReceived.incrementAndGet();
            
            log.debug("Received message from topic '{}' for consumer group '{}' in service '{}'",
                topic.getResourceName(), consumerGroup, context.serviceName());
            
            return Optional.of(message);
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            topic.recordTopicError("READ_ERROR", 
                "Error reading from topic for consumer group '" + consumerGroup + "'", 
                "Service: " + context.serviceName() + ", Error: " + e.getMessage());
            log.warn("Error reading from topic '{}' for consumer group '{}' in service '{}'",
                topic.getResourceName(), consumerGroup, context.serviceName());
            throw e;
        }
    }
    
    @Override
    public void ack(TopicMessage<T> message) {
        if (message == null) {
            throw new NullPointerException("message cannot be null");
        }
        
        topic.acknowledgeMessage(consumerGroup, message.index());
        messagesAcknowledged.incrementAndGet();
        
        log.debug("Acknowledged message {} from topic '{}' for consumer group '{}' in service '{}'",
            message.messageId(), topic.getResourceName(), consumerGroup, context.serviceName());
    }
    
    @Override
    public UsageState getUsageState(String usageType) {
        return topic.getUsageState("topic-read");
    }
    
    @Override
    public String getResourceName() {
        return topic.getResourceName();
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new java.util.LinkedHashMap<>();
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("messages_acknowledged", messagesAcknowledged.get());
        metrics.put("read_errors", readErrors.get());
        return metrics;
    }
}
```

## Configuration

### evochora.conf

```hocon
# Topic Resources
resources {
  
  # Metadata notification topic (MetadataPersistenceService → MetadataIndexer)
  metadata-topic {
    className = "org.evochora.datapipeline.resources.topics.ChronicleTopicResource"
    options {
      messageType = "org.evochora.datapipeline.api.contracts.NotificationContracts$MetadataInfo"
      queueDirectory = "data/topics/metadata"
      retentionDays = 7
      internalQueueCapacity = 1000  # Lower capacity for metadata (low volume)
      metricsWindowSeconds = 5      # Sliding window for throughput metrics
    }
  }
  
  # Batch notification topic (PersistenceService → Indexers)
  batch-topic {
    className = "org.evochora.datapipeline.resources.topics.ChronicleTopicResource"
    options {
      messageType = "org.evochora.datapipeline.api.contracts.NotificationContracts$BatchInfo"
      queueDirectory = "data/topics/batches"
      retentionDays = 7
      internalQueueCapacity = 10000  # Higher capacity for batches (high volume)
      metricsWindowSeconds = 5       # Sliding window for throughput metrics
    }
  }
}
```

## Build Configuration

### build.gradle.kts

Add Chronicle Queue dependency:

```kotlin
dependencies {
    // ... existing dependencies ...
    
    // Chronicle Queue for Topic implementation
    implementation("net.openhft:chronicle-queue:5.24.2")
}
```

## Testing Requirements

### General Test Requirements

**All tests in this phase MUST follow these requirements:**

1. **Tagging:**
   - Tests using Chronicle Queue (filesystem) → `@Tag("integration")`
   - Tests completing in <0.2s with no I/O → `@Tag("unit")`

2. **Cleanup:**
   - All Chronicle Queue directories MUST be deleted after each test
   - Use `@AfterEach` with recursive directory deletion
   - Tests MUST NOT leave any artifacts

3. **Assertions:**
   - Use Awaitility for asynchronous conditions (`await().atMost(...).until(...)`)
   - **NEVER use `Thread.sleep()` in tests**

4. **Logging Assertions:**
   - DEBUG and INFO logs → Use `@ExpectLog` if you want to assert specific logs, otherwise they're allowed by default
   - WARN and ERROR logs → MUST use specific `@ExpectLog(level=WARN/ERROR, messagePattern="...")` patterns
   - **NO broad `@AllowLog(level=WARN/ERROR)` without specific patterns**

5. **Concurrency:**
   - Multi-threaded tests MUST use Awaitility with appropriate timeouts
   - Use `CountDownLatch` or `AtomicBoolean` for synchronization

6. **Protobuf:**
   - All test messages MUST be valid Protobuf messages
   - Use `BatchInfo.newBuilder()...build()` pattern

### Test Classes

#### 1. ChronicleTopicResourceTest

**Purpose:** Unit tests for `ChronicleTopicResource`

**Tests:**
- `constructor_ValidConfig_Success`: Resource creation with valid config
- `constructor_InvalidMessageType_ThrowsException`: Rejects non-Protobuf message types
- `constructor_InvalidRetentionDays_ThrowsException`: Rejects retentionDays < 1
- `constructor_InvalidInternalQueueCapacity_ThrowsException`: Rejects capacity < 100
- `getMessageType_ReturnsCorrectClass`: Verifies message type
- `getUsageState_TopicWrite_DelegatesToInternalQueue`: Verifies writer state
- `getUsageState_TopicRead_ReturnsActive`: Verifies reader state always active
- `getWrappedResource_TopicWrite_ReturnsWriter`: Verifies writer wrapper creation
- `getWrappedResource_TopicRead_WithConsumerGroup_ReturnsReader`: Verifies reader wrapper creation
- `getWrappedResource_TopicRead_WithoutConsumerGroup_ThrowsException`: Requires consumerGroup parameter
- `getWrappedResource_InvalidUsageType_ThrowsException`: Rejects unknown usage types
- `metrics_IncludeAllExpectedMetrics`: Verifies metrics (messages_written, messages_read, write_throughput_per_sec, read_throughput_per_sec, etc.)
- `close_StopsWriterThreadAndClosesTailers`: Verifies cleanup and AutoCloseable

**Cleanup:** Delete Chronicle Queue directory in `@AfterEach`

#### 2. TopicWriterWrapperTest

**Purpose:** Unit tests for `TopicWriterWrapper`

**Tests:**
- `send_ValidMessage_Success`: Send succeeds and increments metrics (messages_sent)
- `send_NullMessage_ThrowsException`: Rejects null
- `send_InterruptedWhileWaiting_ThrowsInterruptedException`: Handles interruption gracefully (increments messages_failed)
- `send_InternalQueueFull_BlocksUntilSpace`: Verifies blocking behavior on full internal queue
- `metrics_TrackMessagesSentAndFailed`: Verifies metrics (messages_sent, messages_failed, internal_queue_*)

**Cleanup:** Delete Chronicle Queue directory in `@AfterEach`

#### 3. TopicReaderWrapperTest

**Purpose:** Unit tests for `TopicReaderWrapper`

**Tests:**
- `receive_MessageAvailable_ReturnsMessage`: Blocking receive succeeds
- `poll_MessageAvailable_WithinTimeout_ReturnsMessage`: Timeout-based poll succeeds
- `poll_NoMessage_Timeout_ReturnsEmpty`: Poll returns empty on timeout
- `poll_NullTimeUnit_ThrowsException`: Rejects null TimeUnit
- `ack_ValidMessage_Success`: Acknowledgment succeeds
- `ack_NullMessage_ThrowsException`: Rejects null
- `topicMessage_ContainsConsumerGroup`: Verifies consumer group is available in TopicMessage
- `metrics_TrackMessagesReceivedAndAcknowledged`: Verifies metrics

**Cleanup:** Delete Chronicle Queue directory in `@AfterEach`

#### 4. ChronicleTopicIntegrationTest

**Purpose:** Integration tests for end-to-end topic functionality

**Tests:**
- `singleWriterSingleReader_MessagesDelivered`: Verifies basic write → read flow
- `multiWriter_MessagesCoalesced`: Verifies multi-writer safety (internal queue)
- `multiReader_SameConsumerGroup_CompetingConsumers`: Each message consumed ONCE per group
- `multiReader_DifferentConsumerGroups_AllReceiveMessages`: Each group receives ALL messages
- `acknowledgment_DoesNotAffectOtherGroups`: Ack in one group doesn't affect others
- `writerThread_StartsOnFirstWrite`: Verifies lazy thread start
- `writerThread_StopsOnClose`: Verifies thread cleanup
- `persistenceAcrossRestart_MessagesRetained`: Verifies Chronicle Queue persistence

**Annotations:**
- `@Tag("integration")` (uses filesystem)
- `@Timeout(30)` for multi-threaded tests

**Cleanup:** Delete Chronicle Queue directory in `@AfterEach`

### Example Test

```java
package org.evochora.datapipeline.resources.topics;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.NotificationContracts;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ChronicleTopicResource}.
 */
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class ChronicleTopicIntegrationTest {
    
    private ChronicleTopicResource<NotificationContracts.BatchInfo> topic;
    private Path queueDirectory;
    
    @AfterEach
    void cleanup() throws IOException {
        if (topic != null) {
            topic.close();
        }
        
        // Delete Chronicle Queue directory
        if (queueDirectory != null && Files.exists(queueDirectory)) {
            Files.walk(queueDirectory)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }
    
    @Test
    @Timeout(10)
    void singleWriterSingleReader_MessagesDelivered() throws Exception {
        // Arrange
        queueDirectory = Paths.get("target/test-queues/single-writer-reader-" + System.currentTimeMillis());
        Config config = ConfigFactory.parseMap(Map.of(
            "messageType", "org.evochora.datapipeline.api.contracts.NotificationContracts$BatchInfo",
            "queueDirectory", queueDirectory.toString(),
            "retentionDays", 7,
            "internalQueueCapacity", 1000,
            "metricsWindowSeconds", 5
        ));
        
        topic = new ChronicleTopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext(
            "test-service", "batch-out", "topic-write", "test-topic", Map.of()
        );
        ResourceContext readerContext = new ResourceContext(
            "test-service", "batch-in", "topic-read", "test-topic", Map.of("consumerGroup", "test-group")
        );
        
        ITopicWriter<NotificationContracts.BatchInfo> writer =
            (ITopicWriter<NotificationContracts.BatchInfo>) topic.getWrappedResource(writerContext);
        
        ITopicReader<NotificationContracts.BatchInfo> reader =
            (ITopicReader<NotificationContracts.BatchInfo>) topic.getWrappedResource(readerContext);
        
        NotificationContracts.BatchInfo message = NotificationContracts.BatchInfo.newBuilder()
            .setSimulationRunId("test-run")
            .setStorageKey("test-run/batch_0000010000_0000019990.pb")
            .setTickStart(10000)
            .setTickEnd(19990)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // Act
        writer.send(message);
        
        // Assert - Wait for message to be available
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                var received = reader.poll(100, TimeUnit.MILLISECONDS);
                return received.isPresent();
            } catch (Exception e) {
                return false;
            }
        });
        
        TopicMessage<NotificationContracts.BatchInfo> received = reader.receive();
        assertNotNull(received);
        assertEquals("test-run", received.payload().getSimulationRunId());
        assertEquals(10000, received.payload().getTickStart());
        assertEquals(19990, received.payload().getTickEnd());
        
        // Verify consumer group is available in TopicMessage (not via ITopicReader.getConsumerGroup())
        assertEquals("test-group", received.consumerGroup());
        
        // Acknowledge
        reader.ack(received);
        
        // Verify metrics
        Map<String, Number> metrics = topic.getMetrics();
        assertEquals(1, metrics.get("messages_written").intValue());
        assertEquals(1, metrics.get("messages_read").intValue());
        assertEquals(1, metrics.get("messages_acknowledged").intValue());
        assertTrue(metrics.get("write_throughput_per_sec").doubleValue() > 0);
        assertTrue(metrics.get("read_throughput_per_sec").doubleValue() > 0);
    }
}
```

## Non-Functional Requirements

### Logging

**Levels:**
- **INFO:** Resource lifecycle (creation, closure), consumer group creation
- **DEBUG:** Message write/read, acknowledgment, normal shutdown/interruption
- **WARN:** Transient errors (serialization/deserialization failures, read errors)
- **ERROR:** (None expected - all errors are transient and tracked via `recordError()`)

**Format:**
- Single-line logs only
- No phase/version prefixes
- Include service name, topic name, consumer group (where applicable)

**Examples:**
```
INFO  ChronicleTopicResource '{}' initialized: directory={}, retention={}d, internalQueueCapacity={}, metricsWindow={}s
DEBUG Wrote message to Chronicle Queue for topic '{}'
DEBUG Read message from Chronicle Queue for consumer group '{}' in topic '{}'
DEBUG Acknowledged message {} for consumer group '{}' in topic '{}'
DEBUG Interrupted while sending message to topic '{}' from service '{}'
DEBUG Closing ChronicleTopicResource '{}'
DEBUG Error closing tailer for topic '{}': {}
WARN  Failed to serialize message to Chronicle Queue for topic '{}'
WARN  Failed to deserialize message from Chronicle Queue for topic '{}'
WARN  Error reading from topic '{}' for consumer group '{}' in service '{}'
```

### JavaDoc

**All public classes, interfaces, and methods MUST have complete JavaDoc in English:**

1. **Class-level:**
   - Purpose and responsibility
   - Key features (bullet list)
   - Architectural notes (in-process vs cloud)
   - Thread safety guarantees
   - Usage examples (for complex classes)

2. **Method-level:**
   - Purpose (what it does)
   - Parameters (with validation rules)
   - Return value (semantics)
   - Exceptions (when and why)
   - Thread safety (if method-specific)

3. **Interface-level:**
   - Capability contract (which ITopic capability)
   - Usage type (topic-write, topic-read)
   - Consumer group requirements (for readers)

**Example:**

```java
/**
 * Sends a message to the topic.
 * <p>
 * This method may block briefly depending on the implementation:
 * <ul>
 *   <li><strong>Chronicle Queue:</strong> Blocks on internal queue during multi-writer coalescing.
 *       The internal queue has bounded capacity (configured via {@code internalQueueCapacity}).</li>
 *   <li><strong>Kafka:</strong> Blocks if producer buffer is full (configurable via Kafka settings).</li>
 *   <li><strong>Cloud (Kinesis/SQS):</strong> Blocks during retry on transient errors.</li>
 * </ul>
 * <p>
 * Unlike {@link IOutputQueueResource#put(Object)}, this method does not have separate
 * blocking/non-blocking variants because topics do not have a "bounded capacity" as a
 * first-class concept. Internal buffering is an implementation detail.
 *
 * @param message The message to send (must not be null).
 * @throws InterruptedException if interrupted while waiting for internal resources.
 * @throws NullPointerException if message is null.
 */
void send(T message) throws InterruptedException;
```

## Implementation Notes

### Chronicle Queue API Limitation

The current Chronicle Queue API has a limitation where messages cannot be directly returned from the `readBytes()` lambda. The spec shows this limitation with a `TODO` comment. The actual implementation will use one of these workarounds:

1. **ThreadLocal Pattern:** Store deserialized message in ThreadLocal, retrieve after `readBytes()`
2. **AtomicReference Pattern:** Use AtomicReference to pass message out of lambda
3. **Custom Chronicle Wire:** Implement custom Wire for more control

This limitation does not affect the API design—only the internal implementation.

### Multi-Writer Safety

Chronicle Queue's single-writer requirement is handled transparently via the internal `InMemoryBlockingQueue`. Services can use multiple `ITopicWriter` instances concurrently without needing to know about this internal detail.

### Consumer Group Semantics

Chronicle Queue supports Tailers with named consumer groups. Each Tailer maintains its own read position. However, Chronicle's single-file design means multiple Tailers in the same group reading concurrently is not efficient. For true competing consumers, consider:
1. Using a single reader per consumer group (simplest)
2. Implementing custom coordination (future)
3. Migrating to Kafka/Kinesis in cloud mode (future)

For Phase 14.2, we assume single reader per consumer group in tests.

## Migration Path

**From:** Complex batch discovery with `coordinator_batches`, `coordinator_gaps`, polling, gap detection

**To:** Simple topic notifications with at-least-once delivery + idempotency

**Steps:**
1. Phase 14.2.1 (this phase): Implement topic infrastructure
2. Phase 14.2.2-14.2.5: Migrate services to use topics
3. Phase 14.2.6: Add idempotency checks for exactly-once semantics
4. Phase 14.2.7: Remove old batch discovery logic

**No backward compatibility needed** - this is a complete architectural shift.

## Success Metrics

Upon completion, verify:
- ✅ All tests pass with no `Thread.sleep()`
- ✅ Chronicle Queue directories cleaned up after tests
- ✅ Multi-writer scenario works (messages coalesced via internal queue)
- ✅ Consumer groups work (same group competes, different groups all receive)
- ✅ O(1) monitoring: `SlidingWindowCounter` for throughput, counters for totals
- ✅ `AutoCloseable` implemented for try-with-resources support
- ✅ `recordError()` guidelines followed (WARN for transient, DEBUG for shutdown)
- ✅ Metrics track: messages written/read/acknowledged + throughput (write_throughput_per_sec, read_throughput_per_sec)
- ✅ JavaDoc complete for all public API
- ✅ Logging follows new standards (single-line, no prefixes, correct levels)

---

**Next Phase:** [14_2_2_METADATA_NOTIFICATION_WRITE.md](14_2_2_METADATA_NOTIFICATION_WRITE.md) - Integrate MetadataPersistenceService with metadata-topic

