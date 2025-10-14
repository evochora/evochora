# Phase 14.2.1: H2-Based Topic Infrastructure

**Status:** Open  
**Parent:** [14.2 Indexer Foundation](14_2_INDEXER_FOUNDATION.md)  
**Depends On:** Phase 2.4 (Metadata Indexer)

---

## 1. Overview

This specification defines a **H2 database-based topic infrastructure** as an alternative to Chronicle Queue for the in-process data pipeline. Unlike Chronicle Queue, H2 provides:

- **Multi-writer support** - No internal queue or writer thread needed
- **Explicit acknowledgment** - SQL-based ACK with DELETE operations
- **Consumer groups** - SQL-based filtering with WHERE clauses
- **Debuggability** - Direct SQL query access
- **Simplicity** - Standard JDBC operations, no API limitations

The H2 implementation uses **separate class files** instead of inner classes for better readability and maintainability.

---

## 2. Goals

1. **Replace Batch Discovery:** Eliminate polling, gap detection, and coordinator tables
2. **Publish/Subscribe Model:** Services publish notifications; indexers subscribe via consumer groups
3. **Persistence:** All messages survive process restarts (H2 file-based storage)
4. **At-Least-Once Delivery:** Explicit acknowledgment with SQL transactions
5. **Idempotency Support:** Consistent messageId and timestamp for duplicate detection
6. **Multi-Writer:** Direct database writes without internal queuing
7. **Modular Architecture:** Abstract interfaces allow future Kafka/cloud migration

---

## 3. Requirements

### 3.1 Functional Requirements

- **FR-1:** Topics accept Protobuf messages (`com.google.protobuf.Message`)
- **FR-2:** Messages wrapped in `TopicEnvelope` with messageId and timestamp
- **FR-3:** Consumer groups receive all messages independently
- **FR-4:** Explicit acknowledgment via `ack()` removes messages from queue
- **FR-5:** Messages ordered by write sequence (auto-incrementing ID)
- **FR-6:** Multiple concurrent writers supported (H2 MVCC)
- **FR-7:** Failed deserialization logged, message skipped

### 3.2 Non-Functional Requirements

- **NFR-1:** Performance: 1000+ msg/sec per topic (sufficient for evochora)
- **NFR-2:** Latency: <10ms per operation (acceptable for batch processing)
- **NFR-3:** Durability: H2 file-based persistence (survives restarts)
- **NFR-4:** Resource efficiency: Reuse DB connections, O(1) metrics
- **NFR-5:** Monitoring: Metrics for messages published/received/acknowledged

---

## 4. Architecture Overview

### 4.1 Component Structure

```
┌─────────────────────────────────────────────────────────────┐
│                     ServiceManager                           │
│  - Creates H2TopicResource instances from config            │
│  - Binds topic-write/topic-read to services                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ creates
                              ▼
┌─────────────────────────────────────────────────────────────┐
│               H2TopicResource<T, Long>                       │
│  - Manages H2 database connection                           │
│  - Creates H2TopicWriterDelegate / H2TopicReaderDelegate    │
│  - Maintains aggregate metrics                              │
│  - Template methods: createReaderDelegate, createWriterDelegate │
└─────────────────────────────────────────────────────────────┘
              │                                │
              │ creates                        │ creates
              ▼                                ▼
┌──────────────────────────┐      ┌──────────────────────────┐
│ H2TopicWriterDelegate<T> │      │ H2TopicReaderDelegate<T> │
│  - Wraps messages        │      │  - Reads with FOR UPDATE │
│  - Direct SQL INSERT     │      │  - Unwraps envelopes     │
│  - Updates metrics       │      │  - Consumer group filter │
└──────────────────────────┘      └──────────────────────────┘
```

### 4.2 Key Differences from Chronicle Queue

| Aspect | Chronicle Queue | H2 Database |
|--------|----------------|-------------|
| **Writers** | Single-writer (needs internal queue) | Multi-writer (MVCC) |
| **Acknowledgment** | Implicit (tailer advance) | Explicit (SQL DELETE) |
| **Consumer Groups** | Tailer per group (complex) | SQL WHERE clause (simple) |
| **API** | Custom (readBytes lambda) | Standard JDBC |
| **Debugging** | Binary files | SQL queries |
| **Complexity** | High (queue + thread) | Low (direct SQL) |

### 4.3 Data Flow

**Write Path:**
```
Service → ITopicWriter.send(BatchInfo)
         → AbstractTopicDelegateWriter.send() [wraps in TopicEnvelope]
         → H2TopicWriterDelegate.sendEnvelope()
         → SQL INSERT INTO topic_messages
```

**Read Path:**
```
Service ← ITopicReader.receive()
        ← AbstractTopicDelegateReader.receive() [unwraps envelope]
        ← H2TopicReaderDelegate.receiveEnvelope()
        ← SQL SELECT ... FOR UPDATE (with consumer group filter)
```

**Acknowledgment Path:**
```
Service → ITopicReader.ack(TopicMessage)
        → AbstractTopicDelegateReader.ack()
        → H2TopicReaderDelegate.acknowledgeMessage(rowId)
        → SQL DELETE FROM topic_messages WHERE id = ?
```

---

## 5. Database Schema

### 5.1 Topic Messages Table

```sql
CREATE TABLE IF NOT EXISTS topic_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(255) NOT NULL,
    timestamp BIGINT NOT NULL,
    envelope BINARY NOT NULL,
    consumer_group VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_consumer_group (consumer_group),
    INDEX idx_message_id (message_id)
);
```

**Column Descriptions:**
- `id`: Auto-incrementing sequence number (used as ACK token)
- `message_id`: UUID from TopicEnvelope (for idempotency)
- `timestamp`: Unix timestamp from TopicEnvelope
- `envelope`: Serialized TopicEnvelope (Protobuf binary)
- `consumer_group`: NULL initially; set on first read by group
- `created_at`: Database timestamp for debugging

### 5.2 Consumer Group Tracking

**Strategy:** Use `consumer_group` column to track which groups have read a message.

**Write:** INSERT with `consumer_group = NULL`
**Read:** SELECT WHERE `consumer_group IS NULL OR consumer_group = ?`
**ACK:** DELETE WHERE `id = ?`

**Note:** For true multi-consumer-group support, consider a junction table in future phases.

---

## 6. API Design

### 6.1 Core Interfaces

#### ITopicWriter

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/ITopicWriter.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Interface for writing messages to a topic.
 * <p>
 * This interface is technology-agnostic and can be implemented using:
 * <ul>
 *   <li>H2 Database (in-process, persistent)</li>
 *   <li>Chronicle Queue (in-process, memory-mapped files)</li>
 *   <li>Apache Kafka (distributed)</li>
 *   <li>AWS SQS/SNS (cloud-managed)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe for concurrent writers.
 * <p>
 * <strong>Blocking Behavior:</strong> The {@link #send(Message)} method MAY block briefly
 * during internal buffering or backpressure, depending on the implementation.
 * <p>
 * <strong>Implements:</strong> {@link IResource}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public interface ITopicWriter<T extends Message> extends IResource {
    
    /**
     * Sends a message to the topic.
     * <p>
     * The message is automatically wrapped in a {@code TopicEnvelope} with a unique
     * {@code messageId} (UUID) and {@code timestamp} before being written to the underlying
     * topic implementation.
     * <p>
     * <strong>Thread Safety:</strong> This method is thread-safe and can be called
     * concurrently by multiple threads.
     * <p>
     * <strong>Blocking:</strong> May block briefly if the underlying implementation
     * applies backpressure (e.g., H2 transaction lock, Kafka buffer full).
     *
     * @param message The message to send (must not be null).
     * @throws InterruptedException if interrupted while waiting for internal resources.
     * @throws NullPointerException if message is null.
     */
    void send(T message) throws InterruptedException;
}
```

#### ITopicReader

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/ITopicReader.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

import java.util.concurrent.TimeUnit;

/**
 * Interface for reading messages from a topic.
 * <p>
 * This interface supports consumer groups for competing consumers. Each reader
 * instance is bound to a single consumer group (specified via ResourceContext parameter).
 * <p>
 * <strong>Consumer Group Behavior:</strong>
 * <ul>
 *   <li>Each consumer group receives ALL messages independently</li>
 *   <li>Within a group, only ONE consumer processes each message</li>
 *   <li>Messages are delivered in order (FIFO per topic)</li>
 * </ul>
 * <p>
 * <strong>Acknowledgment:</strong>
 * Messages MUST be explicitly acknowledged via {@link #ack(TopicMessage)} after processing.
 * Unacknowledged messages may be redelivered (at-least-once semantics).
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe for a single reader.
 * Multiple readers in the same consumer group MAY conflict (implementation-specific).
 * <p>
 * <strong>Implements:</strong> {@link IResource}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public interface ITopicReader<T extends Message, ACK> extends IResource {
    
    /**
     * Receives the next message, blocking indefinitely until one is available.
     * <p>
     * <strong>Blocking:</strong> This method blocks until a message is available or
     * the thread is interrupted. For graceful shutdown, services should handle
     * {@link InterruptedException} and exit cleanly.
     * <p>
     * <strong>Thread Safety:</strong> Safe for single-threaded access per reader instance.
     *
     * @return The next message (never null in blocking mode).
     * @throws InterruptedException if interrupted while waiting.
     */
    TopicMessage<T, ACK> receive() throws InterruptedException;
    
    /**
     * Polls for the next message, waiting up to the specified timeout.
     * <p>
     * <strong>Non-Blocking:</strong> Returns {@code null} if no message is available
     * within the timeout period.
     * <p>
     * <strong>Thread Safety:</strong> Safe for single-threaded access per reader instance.
     *
     * @param timeout Maximum time to wait.
     * @param unit Time unit for the timeout.
     * @return The next message, or {@code null} if timeout expires.
     * @throws InterruptedException if interrupted while waiting.
     */
    TopicMessage<T, ACK> poll(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * Acknowledges a message, marking it as successfully processed.
     * <p>
     * <strong>Semantics:</strong>
     * <ul>
     *   <li>H2: Deletes the message row via SQL DELETE</li>
     *   <li>Kafka: Commits the offset</li>
     *   <li>SQS: Deletes the message via receipt handle</li>
     * </ul>
     * <p>
     * <strong>Idempotency:</strong> Acknowledging the same message multiple times is safe (no-op).
     * <p>
     * <strong>Thread Safety:</strong> Safe for concurrent acknowledgments.
     *
     * @param message The message to acknowledge (uses {@link TopicMessage#acknowledgeToken()}).
     * @throws NullPointerException if message is null.
     */
    void ack(TopicMessage<T, ACK> message);
}
```

#### TopicMessage

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/TopicMessage.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;

import java.util.Objects;

/**
 * Wrapper for messages read from a topic, including metadata and acknowledgment token.
 * <p>
 * This class uses a generic acknowledgment token type ({@code ACK}) to support
 * different topic implementations:
 * <ul>
 *   <li>H2 Database: {@code TopicMessage<BatchInfo, Long>} (row ID)</li>
 *   <li>Chronicle Queue: {@code TopicMessage<BatchInfo, Long>} (queue index)</li>
 *   <li>Kafka: {@code TopicMessage<BatchInfo, KafkaOffset>}</li>
 *   <li>SQS: {@code TopicMessage<BatchInfo, String>} (receipt handle)</li>
 * </ul>
 * <p>
 * <strong>Equality Semantics:</strong>
 * Two {@code TopicMessage} instances are considered equal if they have the same
 * {@code messageId} and {@code consumerGroup}, regardless of {@code acknowledgeToken}
 * or {@code timestamp}. This enables correct handling of message redelivery in collections.
 * <p>
 * <strong>Thread Safety:</strong> This class is immutable and thread-safe.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public final class TopicMessage<T extends Message, ACK> {
    
    private final T payload;
    private final long timestamp;
    private final String messageId;
    private final String consumerGroup;
    private final ACK acknowledgeToken;
    
    /**
     * Creates a new TopicMessage.
     *
     * @param payload The message payload (must not be null).
     * @param timestamp Unix timestamp in milliseconds when message was written.
     * @param messageId Unique identifier for this message.
     * @param consumerGroup Consumer group this message was read from.
     * @param acknowledgeToken Implementation-specific token for acknowledgment.
     */
    public TopicMessage(T payload, long timestamp, String messageId, String consumerGroup, ACK acknowledgeToken) {
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
        this.timestamp = timestamp;
        this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        this.acknowledgeToken = acknowledgeToken;
    }
    
    public T payload() { return payload; }
    public long timestamp() { return timestamp; }
    public String messageId() { return messageId; }
    public String consumerGroup() { return consumerGroup; }
    ACK acknowledgeToken() { return acknowledgeToken; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopicMessage<?, ?> that)) return false;
        return messageId.equals(that.messageId) && Objects.equals(consumerGroup, that.consumerGroup);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumerGroup);
    }
    
    @Override
    public String toString() {
        return String.format("TopicMessage{messageId=%s, consumerGroup=%s, timestamp=%d, payload=%s}",
            messageId, consumerGroup, timestamp, payload.getClass().getSimpleName());
    }
}
```

---

## 7. Protobuf Contracts

### 7.1 Topic Envelope

**File:** `src/main/proto/org/evochora/datapipeline/api/contracts/notification_contracts.proto`

```protobuf
syntax = "proto3";

package org.evochora.datapipeline.api.contracts;

import "google/protobuf/any.proto";

option java_package = "org.evochora.datapipeline.api.contracts";
option java_outer_classname = "NotificationContracts";

/**
 * Wrapper for all topic messages with consistent metadata.
 * Generated by writer, persisted with message.
 */
message TopicEnvelope {
    string message_id = 1;  // UUID generated at write time
    int64 timestamp = 2;     // Unix timestamp (ms) at write time
    google.protobuf.Any payload = 3;  // BatchInfo or MetadataInfo
}

/**
 * Notification that a new simulation batch is available.
 */
message BatchInfo {
    int64 simulation_id = 1;
    int64 batch_id = 2;
    int64 start_tick = 3;
    int64 end_tick = 4;
    string storage_path = 5;
}

/**
 * Notification that new simulation metadata is available.
 */
message MetadataInfo {
    int64 simulation_id = 1;
    string metadata_path = 2;
}
```

---

## 8. Abstract Base Classes

These classes are now in **separate files** (not inner classes).

### 8.1 AbstractTopicResource

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/AbstractTopicResource.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all topic resource implementations.
 * <p>
 * This class provides the Template Method pattern for creating reader and writer delegates,
 * ensuring consistent behavior across all topic implementations (H2, Chronicle, Kafka, Cloud).
 * <p>
 * <strong>Delegate Pattern:</strong>
 * Topics use delegate classes (not inner classes) because each delegate instance needs its own state:
 * <ul>
 *   <li><strong>Readers:</strong> Each has its own consumer group and read position</li>
 *   <li><strong>Writers:</strong> Each tracks its own send metrics per service</li>
 * </ul>
 * <p>
 * Delegates extend {@link AbstractTopicDelegate} (which extends {@link AbstractResource}) to inherit:
 * <ul>
 *   <li>{@link AbstractResource#recordError(String, String, String)} - delegate-specific errors</li>
 *   <li>{@link AbstractResource#addCustomMetrics(Map)} - delegate-specific metrics</li>
 *   <li>{@link AbstractResource#isHealthy()} - delegate-specific health</li>
 *   <li>Type-safe access to parent resource via generic parameter {@code <P>}</li>
 * </ul>
 * <p>
 * <strong>Lifecycle Management:</strong>
 * The parent resource tracks all active delegates and closes them on shutdown.
 * <p>
 * <strong>Aggregate Metrics:</strong>
 * The parent resource maintains aggregate metrics (messagesPublished, messagesReceived, messagesAcknowledged)
 * across ALL delegates. Individual delegates also track their own per-service metrics.
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * Implement {@link #createReaderDelegate(ResourceContext)} and {@link #createWriterDelegate(ResourceContext)}
 * to provide technology-specific readers and writers (H2, Chronicle, Kafka, etc.).
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., {@code Long} for H2/Chronicle).
 */
public abstract class AbstractTopicResource<T extends Message, ACK> extends AbstractResource implements IContextualResource, AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractTopicResource.class);
    
    // Delegate lifecycle management
    protected final Set<AutoCloseable> activeDelegates = ConcurrentHashMap.newKeySet();
    
    // Aggregate metrics (across ALL delegates/services)
    protected final AtomicLong messagesPublished = new AtomicLong(0);
    protected final AtomicLong messagesReceived = new AtomicLong(0);
    protected final AtomicLong messagesAcknowledged = new AtomicLong(0);
    
    /**
     * Creates a new AbstractTopicResource.
     *
     * @param name The resource name.
     * @param options The configuration options.
     */
    protected AbstractTopicResource(String name, Config options) {
        super(name, options);
    }
    
    /**
     * Creates a reader delegate for the specified context.
     * <p>
     * Template method - subclasses must implement this to return technology-specific readers.
     * <p>
     * The returned delegate MUST:
     * <ul>
     *   <li>Extend {@link AbstractTopicDelegateReader}</li>
     *   <li>Implement {@link ITopicReader}</li>
     *   <li>Implement {@link AutoCloseable}</li>
     *   <li>Extract consumer group from {@code context.parameters().get("consumerGroup")}</li>
     * </ul>
     *
     * @param context The resource context (includes consumerGroup parameter).
     * @return The reader delegate.
     * @throws IllegalArgumentException if consumerGroup parameter is missing or invalid.
     */
    protected abstract ITopicReader<T, ACK> createReaderDelegate(ResourceContext context);
    
    /**
     * Creates a writer delegate for the specified context.
     * <p>
     * Template method - subclasses must implement this to return technology-specific writers.
     * <p>
     * The returned delegate MUST:
     * <ul>
     *   <li>Extend {@link AbstractTopicDelegateWriter}</li>
     *   <li>Implement {@link ITopicWriter}</li>
     *   <li>Implement {@link AutoCloseable}</li>
     * </ul>
     *
     * @param context The resource context.
     * @return The writer delegate.
     */
    protected abstract ITopicWriter<T> createWriterDelegate(ResourceContext context);
    
    @Override
    public final IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Topic resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: topic-write, topic-read",
                getResourceName(), getResourceName()));
        }
        
        IWrappedResource delegate = switch (context.usageType()) {
            case "topic-write" -> (IWrappedResource) createWriterDelegate(context);
            case "topic-read" -> (IWrappedResource) createReaderDelegate(context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                context.usageType(), getResourceName()));
        };
        
        // Track delegate for lifecycle management
        if (delegate instanceof AutoCloseable) {
            activeDelegates.add((AutoCloseable) delegate);
        }
        
        log.debug("Created delegate for topic '{}': type={}, service={}",
            getResourceName(), context.usageType(), context.serviceName());
        
        return delegate;
    }
    
    @Override
    public final UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("UsageType cannot be null for topic '" + getResourceName() + "'");
        }
        
        return switch (usageType) {
            case "topic-write" -> getWriteUsageState();
            case "topic-read" -> getReadUsageState();
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                usageType, getResourceName()));
        };
    }
    
    /**
     * Template method for subclasses to provide write usage state logic.
     *
     * @return The current write usage state.
     */
    protected abstract UsageState getWriteUsageState();
    
    /**
     * Template method for subclasses to provide read usage state logic.
     *
     * @return The current read usage state.
     */
    protected abstract UsageState getReadUsageState();
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        metrics.put("messages_published", messagesPublished.get());
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("messages_acknowledged", messagesAcknowledged.get());
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing topic resource '{}' and {} active delegates", getResourceName(), activeDelegates.size());
        
        for (AutoCloseable delegate : activeDelegates) {
            try {
                delegate.close();
            } catch (Exception e) {
                log.warn("Failed to close delegate for topic '{}'", getResourceName());
                recordError("DELEGATE_CLOSE_FAILED", "Delegate close error", "Topic: " + getResourceName());
            }
        }
        
        activeDelegates.clear();
    }
}
```

### 8.2 AbstractTopicDelegate

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/AbstractTopicDelegate.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.Map;

/**
 * Abstract base class for all topic delegates (readers and writers).
 * <p>
 * This class provides type-safe access to the parent topic resource and integrates
 * with {@link AbstractResource} for error tracking, metrics, and health monitoring.
 * <p>
 * <strong>Type Safety:</strong>
 * The generic parameter {@code <P>} ensures compile-time type safety when accessing
 * parent resource methods.
 * <p>
 * <strong>Error Tracking:</strong>
 * Delegates track their own errors independently from the parent resource. Use
 * {@link #recordError(String, String, String)} for delegate-specific errors.
 * <p>
 * <strong>Metrics:</strong>
 * Delegates override {@link #addCustomMetrics(Map)} to expose parent's aggregate
 * metrics plus their own delegate-specific metrics.
 * <p>
 * <strong>Consumer Group (Readers Only):</strong>
 * Reader delegates extract the consumer group from {@code context.parameters().get("consumerGroup")}.
 * Writer delegates do not use consumer groups.
 *
 * @param <P> The parent topic resource type.
 */
public abstract class AbstractTopicDelegate<P extends AbstractTopicResource<?, ?>> extends AbstractResource implements IWrappedResource, AutoCloseable {
    
    protected final P parent;
    protected final String consumerGroup;  // Only used by readers
    
    /**
     * Creates a new topic delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegate(P parent, ResourceContext context) {
        super(context.serviceName() + "-" + context.usageType(), parent.getOptions());
        this.parent = parent;
        this.consumerGroup = context.parameters().get("consumerGroup");  // May be null for writers
    }
    
    @Override
    public final AbstractResource getWrappedResource() {
        return parent;
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        // Include parent's aggregate metrics (type-safe access!)
        metrics.put("parent_messages_published", parent.messagesPublished.get());
        metrics.put("parent_messages_received", parent.messagesReceived.get());
        metrics.put("parent_messages_acknowledged", parent.messagesAcknowledged.get());
    }
}
```

### 8.3 AbstractTopicDelegateWriter

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/AbstractTopicDelegateWriter.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;

import java.util.UUID;

/**
 * Abstract base class for topic writer delegates.
 * <p>
 * This class implements the {@link ITopicWriter#send(Message)} method to automatically
 * wrap the payload in a {@link TopicEnvelope} before delegating to the concrete implementation.
 * <p>
 * <strong>Message Wrapping:</strong>
 * The {@code send()} method is {@code final} to enforce the following flow:
 * <ol>
 *   <li>Generate unique {@code messageId} (UUID)</li>
 *   <li>Capture current {@code timestamp} (System.currentTimeMillis())</li>
 *   <li>Wrap payload in {@link TopicEnvelope} using {@code google.protobuf.Any}</li>
 *   <li>Delegate to {@link #sendEnvelope(TopicEnvelope)} for technology-specific writing</li>
 * </ol>
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * Implement {@link #sendEnvelope(TopicEnvelope)} to write the envelope to the underlying
 * topic (H2, Chronicle, Kafka, etc.).
 * <p>
 * <strong>Thread Safety:</strong>
 * Subclasses MUST implement {@code sendEnvelope} to be thread-safe for concurrent writers.
 *
 * @param <P> The parent topic resource type.
 * @param <T> The message type.
 */
public abstract class AbstractTopicDelegateWriter<P extends AbstractTopicResource<T, ?>, T extends Message> 
    extends AbstractTopicDelegate<P> implements ITopicWriter<T> {
    
    /**
     * Creates a new writer delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegateWriter(P parent, ResourceContext context) {
        super(parent, context);
    }
    
    /**
     * Sends a message to the topic.
     * <p>
     * This method is {@code final} to enforce automatic {@link TopicEnvelope} wrapping.
     * The wrapped envelope is then passed to {@link #sendEnvelope(TopicEnvelope)}.
     *
     * @param message The message to send (must not be null).
     * @throws InterruptedException if interrupted while waiting.
     */
    @Override
    public final void send(T message) throws InterruptedException {
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
        }
        
        TopicEnvelope envelope = wrapMessage(message);
        sendEnvelope(envelope);
    }
    
    /**
     * Wraps a message in a TopicEnvelope with unique messageId and timestamp.
     *
     * @param message The message to wrap.
     * @return The wrapped envelope.
     */
    private TopicEnvelope wrapMessage(T message) {
        return TopicEnvelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setPayload(Any.pack(message))
            .build();
    }
    
    /**
     * Sends a wrapped envelope to the underlying topic implementation.
     * <p>
     * <strong>Subclass Implementation:</strong>
     * <ul>
     *   <li>H2: Execute SQL INSERT</li>
     *   <li>Chronicle: Write to queue file</li>
     *   <li>Kafka: Produce to partition</li>
     * </ul>
     * <p>
     * <strong>Thread Safety:</strong> MUST be thread-safe for concurrent writers.
     *
     * @param envelope The envelope to send.
     * @throws InterruptedException if interrupted while waiting.
     */
    protected abstract void sendEnvelope(TopicEnvelope envelope) throws InterruptedException;
    
    @Override
    public final UsageState getUsageState(String usageType) {
        if (!"topic-write".equals(usageType)) {
            throw new IllegalArgumentException(String.format(
                "Writer delegate only supports 'topic-write', got: '%s'", usageType));
        }
        return parent.getWriteUsageState();
    }
}
```

### 8.4 AbstractTopicDelegateReader

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/AbstractTopicDelegateReader.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for topic reader delegates.
 * <p>
 * This class implements {@link ITopicReader#receive()} and {@link ITopicReader#poll(long, TimeUnit)}
 * to automatically unwrap {@link TopicEnvelope} before returning to the service.
 * <p>
 * <strong>Message Unwrapping:</strong>
 * The {@code receive()} and {@code poll()} methods are {@code final} to enforce the following flow:
 * <ol>
 *   <li>Delegate to {@link #receiveEnvelope(long, TimeUnit)} for technology-specific reading</li>
 *   <li>Extract {@link TopicEnvelope} and acknowledgment token from {@link ReceivedEnvelope}</li>
 *   <li>Unpack {@code google.protobuf.Any} payload using dynamic type resolution</li>
 *   <li>Return {@link TopicMessage} with unwrapped payload and acknowledgment token</li>
 * </ol>
 * <p>
 * <strong>Consumer Group Validation:</strong>
 * The consumer group is validated in the constructor (defense-in-depth).
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement {@link #receiveEnvelope(long, TimeUnit)} to read from the underlying topic</li>
 *   <li>Implement {@link #acknowledgeMessage(Object)} for technology-specific acknowledgment</li>
 * </ul>
 *
 * @param <P> The parent topic resource type.
 * @param <T> The message type.
 * @param <ACK> The acknowledgment token type.
 */
public abstract class AbstractTopicDelegateReader<P extends AbstractTopicResource<T, ACK>, T extends Message, ACK> 
    extends AbstractTopicDelegate<P> implements ITopicReader<T, ACK> {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractTopicDelegateReader.class);
    
    /**
     * Creates a new reader delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context (must include consumerGroup parameter).
     * @throws IllegalArgumentException if consumerGroup is null or blank.
     */
    protected AbstractTopicDelegateReader(P parent, ResourceContext context) {
        super(parent, context);
        
        // Defense-in-depth validation (should already be validated by createReaderDelegate)
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException(
                "AbstractTopicDelegateReader requires non-blank consumerGroup parameter. " +
                "This should have been validated by createReaderDelegate()."
            );
        }
    }
    
    @Override
    public final TopicMessage<T, ACK> receive() throws InterruptedException {
        ReceivedEnvelope<ACK> received = receiveEnvelope(0, null);  // Block indefinitely
        if (received == null) {
            throw new IllegalStateException("receiveEnvelope() returned null in blocking mode");
        }
        return unwrapEnvelope(received);
    }
    
    @Override
    public final TopicMessage<T, ACK> poll(long timeout, TimeUnit unit) throws InterruptedException {
        ReceivedEnvelope<ACK> received = receiveEnvelope(timeout, unit);
        return received != null ? unwrapEnvelope(received) : null;
    }
    
    @Override
    public final void ack(TopicMessage<T, ACK> message) {
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
        }
        acknowledgeMessage(message.acknowledgeToken());
    }
    
    /**
     * Receives a wrapped envelope from the underlying topic implementation.
     * <p>
     * <strong>Subclass Implementation:</strong>
     * <ul>
     *   <li>H2: Execute SQL SELECT ... FOR UPDATE, return ReceivedEnvelope with row ID</li>
     *   <li>Chronicle: Read from tailer, return ReceivedEnvelope with queue index</li>
     *   <li>Kafka: Poll consumer, return ReceivedEnvelope with offset</li>
     * </ul>
     * <p>
     * <strong>Blocking Behavior:</strong>
     * <ul>
     *   <li>If {@code timeout == 0} and {@code unit == null}: Block indefinitely</li>
     *   <li>Otherwise: Wait up to {@code timeout} and return {@code null} if no message</li>
     * </ul>
     *
     * @param timeout Maximum time to wait (0 for indefinite blocking).
     * @param unit Time unit (null for indefinite blocking).
     * @return The received envelope with acknowledgment token, or null if timeout.
     * @throws InterruptedException if interrupted while waiting.
     */
    protected abstract ReceivedEnvelope<ACK> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * Acknowledges a message using the implementation-specific token.
     * <p>
     * <strong>Subclass Implementation:</strong>
     * <ul>
     *   <li>H2: Execute SQL DELETE WHERE id = ?</li>
     *   <li>Chronicle: No-op (implicit acknowledgment via tailer advance)</li>
     *   <li>Kafka: Commit offset</li>
     * </ul>
     *
     * @param acknowledgeToken The acknowledgment token.
     */
    protected abstract void acknowledgeMessage(ACK acknowledgeToken);
    
    /**
     * Unwraps a TopicEnvelope to extract the payload and create a TopicMessage.
     *
     * @param received The received envelope with acknowledgment token.
     * @return The unwrapped TopicMessage.
     */
    private TopicMessage<T, ACK> unwrapEnvelope(ReceivedEnvelope<ACK> received) {
        TopicEnvelope envelope = received.envelope();
        
        try {
            // Dynamic type resolution using google.protobuf.Any
            String typeUrl = envelope.getPayload().getTypeUrl();
            String className = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
            String fullClassName = "org.evochora.datapipeline.api.contracts.NotificationContracts$" + className;
            
            @SuppressWarnings("unchecked")
            Class<T> messageClass = (Class<T>) Class.forName(fullClassName);
            
            T payload = envelope.getPayload().unpack(messageClass);
            
            return new TopicMessage<>(
                payload,
                envelope.getTimestamp(),
                envelope.getMessageId(),
                consumerGroup,
                received.acknowledgeToken()
            );
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to deserialize message from topic '{}'", parent.getResourceName());
            recordError("DESERIALIZATION_ERROR", "Protobuf deserialization failed", 
                "Topic: " + parent.getResourceName() + ", MessageId: " + envelope.getMessageId());
            throw new RuntimeException("Deserialization failed for messageId: " + envelope.getMessageId(), e);
        } catch (ClassNotFoundException e) {
            log.error("Unknown message type in topic '{}'", parent.getResourceName());
            recordError("UNKNOWN_TYPE", "Message type not found", 
                "Topic: " + parent.getResourceName() + ", TypeUrl: " + envelope.getPayload().getTypeUrl());
            throw new RuntimeException("Unknown message type: " + envelope.getPayload().getTypeUrl(), e);
        }
    }
    
    @Override
    public final UsageState getUsageState(String usageType) {
        if (!"topic-read".equals(usageType)) {
            throw new IllegalArgumentException(String.format(
                "Reader delegate only supports 'topic-read', got: '%s'", usageType));
        }
        return parent.getReadUsageState();
    }
}
```

### 8.5 ReceivedEnvelope

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/ReceivedEnvelope.java`

```java
package org.evochora.datapipeline.resources.topics;

import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;

/**
 * Container for a received TopicEnvelope and its implementation-specific acknowledgment token.
 * <p>
 * This record is used internally by {@link AbstractTopicDelegateReader} to pass both
 * the envelope and the acknowledgment token from the concrete implementation
 * (e.g., {@link H2TopicReaderDelegate}) to the abstract unwrapping layer.
 * <p>
 * <strong>Examples:</strong>
 * <ul>
 *   <li>H2: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the row ID</li>
 *   <li>Chronicle: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the queue index</li>
 *   <li>Kafka: {@code ReceivedEnvelope<KafkaOffset>} where {@code acknowledgeToken} is the offset</li>
 * </ul>
 *
 * @param <ACK> The acknowledgment token type.
 */
public record ReceivedEnvelope<ACK>(TopicEnvelope envelope, ACK acknowledgeToken) {
}
```

---

## 9. H2 Implementation

### 9.1 H2TopicResource

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicResource.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * H2 database-based topic implementation.
 * <p>
 * This implementation uses an H2 file-based database to provide:
 * <ul>
 *   <li><strong>Persistence:</strong> Messages survive process restarts</li>
 *   <li><strong>Multi-Writer:</strong> Concurrent writes via H2 MVCC</li>
 *   <li><strong>Explicit ACK:</strong> SQL DELETE for acknowledgment</li>
 *   <li><strong>Consumer Groups:</strong> SQL WHERE clause filtering</li>
 *   <li><strong>Simplicity:</strong> Standard JDBC, no API limitations</li>
 * </ul>
 * <p>
 * <strong>Key Differences from Chronicle Queue:</strong>
 * <ul>
 *   <li>No internal BlockingQueue (direct database writes)</li>
 *   <li>No writer thread (multi-writer support)</li>
 *   <li>Explicit acknowledgment (DELETE statement)</li>
 *   <li>SQL-based consumer groups (simpler implementation)</li>
 * </ul>
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Write latency: ~5-10ms per message</li>
 *   <li>Read latency: ~5-10ms per message</li>
 *   <li>Throughput: 1000+ msg/sec (sufficient for evochora batch processing)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe. Multiple writers and readers can operate concurrently.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public class H2TopicResource<T extends Message> extends AbstractTopicResource<T, Long> implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicResource.class);
    
    private final Connection connection;
    private final String tableName;
    private final SlidingWindowCounter writeThroughput;
    private final SlidingWindowCounter readThroughput;
    
    /**
     * Creates a new H2TopicResource.
     *
     * @param name The resource name.
     * @param options The configuration options.
     * @throws RuntimeException if database initialization fails.
     */
    public H2TopicResource(String name, Config options) {
        super(name, options);
        
        String dbPath = options.hasPath("dbPath") 
            ? options.getString("dbPath") 
            : "./data/topics/" + name;
        
        this.tableName = "topic_messages_" + name.replaceAll("[^a-zA-Z0-9_]", "_");
        
        try {
            this.connection = DriverManager.getConnection("jdbc:h2:" + dbPath + ";AUTO_SERVER=TRUE");
            initializeTable();
            
            this.writeThroughput = new SlidingWindowCounter(60, TimeUnit.SECONDS);
            this.readThroughput = new SlidingWindowCounter(60, TimeUnit.SECONDS);
            
            log.info("H2 topic resource '{}' initialized: dbPath={}, table={}", name, dbPath, tableName);
        } catch (SQLException e) {
            log.error("Failed to initialize H2 topic resource '{}'", name);
            throw new RuntimeException("H2 topic initialization failed: " + name, e);
        }
    }
    
    /**
     * Initializes the topic messages table.
     */
    private void initializeTable() throws SQLException {
        String createTableSql = String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id VARCHAR(255) NOT NULL,
                timestamp BIGINT NOT NULL,
                envelope BINARY NOT NULL,
                consumer_group VARCHAR(255),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_consumer_group (consumer_group),
                INDEX idx_message_id (message_id)
            )
            """, tableName);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSql);
        }
    }
    
    @Override
    protected ITopicWriter<T> createWriterDelegate(ResourceContext context) {
        return new H2TopicWriterDelegate<>(this, context);
    }
    
    @Override
    protected ITopicReader<T, Long> createReaderDelegate(ResourceContext context) {
        String consumerGroup = context.parameters().get("consumerGroup");
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException(String.format(
                "Consumer group parameter is required for topic reader. " +
                "Expected format: 'topic-read:%s?consumerGroup=<group-name>'", getResourceName()));
        }
        
        return new H2TopicReaderDelegate<>(this, context);
    }
    
    @Override
    protected UsageState getWriteUsageState() {
        // H2 is always ready for writes (MVCC)
        return UsageState.ACTIVE;
    }
    
    @Override
    protected UsageState getReadUsageState() {
        // H2 is always ready for reads
        return UsageState.ACTIVE;
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        metrics.put("write_throughput_per_minute", writeThroughput.getCount());
        metrics.put("read_throughput_per_minute", readThroughput.getCount());
    }
    
    /**
     * Gets the database connection (package-private for delegate access).
     *
     * @return The JDBC connection.
     */
    Connection getConnection() {
        return connection;
    }
    
    /**
     * Gets the table name (package-private for delegate access).
     *
     * @return The table name.
     */
    String getTableName() {
        return tableName;
    }
    
    /**
     * Records a write operation for metrics (package-private for delegate access).
     */
    void recordWrite() {
        writeThroughput.increment();
        messagesPublished.incrementAndGet();
    }
    
    /**
     * Records a read operation for metrics (package-private for delegate access).
     */
    void recordRead() {
        readThroughput.increment();
        messagesReceived.incrementAndGet();
    }
    
    /**
     * Records an acknowledgment for metrics (package-private for delegate access).
     */
    void recordAck() {
        messagesAcknowledged.incrementAndGet();
    }
    
    @Override
    public void close() throws Exception {
        super.close();  // Close all delegates
        
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.debug("Closed H2 connection for topic '{}'", getResourceName());
        }
    }
}
```

### 9.2 H2TopicWriterDelegate

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicWriterDelegate.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * H2-based writer delegate for topic messages.
 * <p>
 * This delegate writes {@link TopicEnvelope} messages directly to the H2 database
 * using SQL INSERT statements. Multiple writers can operate concurrently thanks
 * to H2's MVCC (Multi-Version Concurrency Control).
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe for concurrent writes. Each write operation uses
 * a separate SQL statement with auto-commit.
 * <p>
 * <strong>Error Handling:</strong>
 * SQL errors are logged and recorded via {@link #recordError(String, String, String)}.
 *
 * @param <T> The message type.
 */
public class H2TopicWriterDelegate<T extends Message> extends AbstractTopicDelegateWriter<H2TopicResource<T>, T> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicWriterDelegate.class);
    
    /**
     * Creates a new H2 writer delegate.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context.
     */
    public H2TopicWriterDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
    }
    
    @Override
    protected void sendEnvelope(TopicEnvelope envelope) throws InterruptedException {
        String sql = String.format(
            "INSERT INTO %s (message_id, timestamp, envelope, consumer_group) VALUES (?, ?, ?, NULL)",
            parent.getTableName()
        );
        
        try (PreparedStatement stmt = parent.getConnection().prepareStatement(sql)) {
            stmt.setString(1, envelope.getMessageId());
            stmt.setLong(2, envelope.getTimestamp());
            stmt.setBytes(3, envelope.toByteArray());
            
            stmt.executeUpdate();
            
            parent.recordWrite();
            
            log.debug("Wrote message to topic '{}': messageId={}", parent.getResourceName(), envelope.getMessageId());
            
        } catch (SQLException e) {
            log.warn("Failed to write message to topic '{}'", parent.getResourceName());
            recordError("WRITE_FAILED", "SQL INSERT failed", 
                "Topic: " + parent.getResourceName() + ", MessageId: " + envelope.getMessageId());
            throw new RuntimeException("Failed to write message to H2 topic", e);
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 writer delegate for topic '{}'", parent.getResourceName());
        // No resources to close (connection managed by parent)
    }
}
```

### 9.3 H2TopicReaderDelegate

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicReaderDelegate.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * H2-based reader delegate for topic messages.
 * <p>
 * This delegate reads {@link TopicEnvelope} messages from the H2 database using
 * SQL SELECT ... FOR UPDATE to provide pessimistic locking for consumer groups.
 * <p>
 * <strong>Consumer Group Logic:</strong>
 * Messages are read with {@code WHERE consumer_group IS NULL OR consumer_group = ?}
 * to ensure each consumer group processes all messages independently.
 * <p>
 * <strong>Acknowledgment:</strong>
 * Acknowledged messages are deleted via SQL DELETE (removes message from all groups).
 * <p>
 * <strong>Thread Safety:</strong>
 * Each reader instance should be used by a single thread. Multiple readers in the
 * same consumer group may conflict on FOR UPDATE locks.
 *
 * @param <T> The message type.
 */
public class H2TopicReaderDelegate<T extends Message> extends AbstractTopicDelegateReader<H2TopicResource<T>, T, Long> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicReaderDelegate.class);
    
    private long lastReadId = 0;  // Track last read ID for this consumer group
    
    /**
     * Creates a new H2 reader delegate.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context (must include consumerGroup parameter).
     */
    public H2TopicReaderDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
    }
    
    @Override
    protected ReceivedEnvelope<Long> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = (timeout > 0 && unit != null) 
            ? System.currentTimeMillis() + unit.toMillis(timeout)
            : Long.MAX_VALUE;
        
        while (System.currentTimeMillis() < endTime) {
            ReceivedEnvelope<Long> envelope = tryReadMessage();
            if (envelope != null) {
                return envelope;
            }
            
            // Sleep briefly to avoid busy-waiting
            Thread.sleep(100);
            
            // Check for interruption
            if (Thread.interrupted()) {
                throw new InterruptedException("Reader interrupted while waiting for message");
            }
        }
        
        return null;  // Timeout
    }
    
    /**
     * Attempts to read the next message from the database.
     *
     * @return The received envelope, or null if no message available.
     */
    private ReceivedEnvelope<Long> tryReadMessage() {
        String sql = String.format(
            "SELECT id, envelope FROM %s WHERE id > ? AND (consumer_group IS NULL OR consumer_group = ?) ORDER BY id LIMIT 1 FOR UPDATE",
            parent.getTableName()
        );
        
        try (PreparedStatement stmt = parent.getConnection().prepareStatement(sql)) {
            stmt.setLong(1, lastReadId);
            stmt.setString(2, consumerGroup);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    byte[] envelopeBytes = rs.getBytes("envelope");
                    
                    TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                    
                    lastReadId = id;
                    parent.recordRead();
                    
                    log.debug("Read message from topic '{}': messageId={}, consumerGroup={}", 
                        parent.getResourceName(), envelope.getMessageId(), consumerGroup);
                    
                    return new ReceivedEnvelope<>(envelope, id);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to read message from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("READ_FAILED", "SQL SELECT failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse envelope from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("PARSE_FAILED", "Protobuf parse failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        }
        
        return null;
    }
    
    @Override
    protected void acknowledgeMessage(Long rowId) {
        String sql = String.format("DELETE FROM %s WHERE id = ?", parent.getTableName());
        
        try (PreparedStatement stmt = parent.getConnection().prepareStatement(sql)) {
            stmt.setLong(1, rowId);
            int deleted = stmt.executeUpdate();
            
            if (deleted > 0) {
                parent.recordAck();
                log.debug("Acknowledged message in topic '{}': rowId={}, consumerGroup={}", 
                    parent.getResourceName(), rowId, consumerGroup);
            } else {
                log.debug("Message already acknowledged in topic '{}': rowId={}", parent.getResourceName(), rowId);
            }
            
        } catch (SQLException e) {
            log.warn("Failed to acknowledge message in topic '{}': rowId={}, consumerGroup={}", 
                parent.getResourceName(), rowId, consumerGroup);
            recordError("ACK_FAILED", "SQL DELETE failed", 
                "Topic: " + parent.getResourceName() + ", RowId: " + rowId + ", ConsumerGroup: " + consumerGroup);
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 reader delegate for topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
        // No resources to close (connection managed by parent)
    }
}
```

---

## 10. Configuration

### 10.1 Resource Declaration

**File:** `src/main/resources/reference.conf` (excerpt)

```hocon
evochora {
  datapipeline {
    resources {
      # Metadata notification topic (H2-based)
      metadata-topic {
        type = "org.evochora.datapipeline.resources.topics.H2TopicResource"
        dbPath = "./data/topics/metadata"  # H2 database file path
      }
      
      # Batch notification topic (H2-based)
      batch-topic {
        type = "org.evochora.datapipeline.resources.topics.H2TopicResource"
        dbPath = "./data/topics/batches"
      }
    }
  }
}
```

### 10.2 Service Bindings

**Example:** MetadataIndexer reading from metadata-topic

```hocon
evochora {
  datapipeline {
    services {
      MetadataIndexer {
        resources {
          metadata-reader = "topic-read:metadata-topic?consumerGroup=metadata-indexers"
        }
      }
    }
  }
}
```

---

## 11. Testing Requirements

### 11.1 Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/resources/topics/H2TopicResourceTest.java`

```java
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class H2TopicResourceTest {
    
    @Test
    @DisplayName("Should write and read message through H2 topic")
    void shouldWriteAndReadMessage() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-test\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationId(123)
            .setBatchId(456)
            .setStartTick(0)
            .setEndTick(100)
            .setStoragePath("/path/to/batch")
            .build();
        
        // When
        writer.send(message);
        TopicMessage<BatchInfo, Long> received = reader.poll(1, TimeUnit.SECONDS);
        
        // Then
        assertThat(received).isNotNull();
        assertThat(received.payload()).isEqualTo(message);
        assertThat(received.consumerGroup()).isEqualTo("test-group");
        assertThat(received.acknowledgeToken()).isGreaterThan(0L);
        
        // Cleanup
        reader.ack(received);
        topic.close();
    }
    
    @Test
    @DisplayName("Should support multiple consumer groups independently")
    void shouldSupportMultipleConsumerGroups() throws Exception {
        // Test consumer group isolation
        // ...
    }
    
    @Test
    @DisplayName("Should handle concurrent writes from multiple threads")
    void shouldHandleConcurrentWrites() throws Exception {
        // Test multi-writer scenario
        // ...
    }
}
```

### 11.2 Integration Tests

**File:** `src/test/java/org/evochora/datapipeline/resources/topics/H2TopicIntegrationTest.java`

```java
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2TopicIntegrationTest {
    
    @Test
    @DisplayName("Should persist messages across topic restarts")
    void shouldPersistMessagesAcrossRestarts() throws Exception {
        // Test persistence
        // ...
    }
}
```

### 11.3 Test Guidelines

1. **No Thread.sleep()** - Use Awaitility for async conditions
2. **Log Assertions** - Use `@ExpectLog` for expected WARN/ERROR logs
3. **Cleanup** - Always close resources in `@AfterEach`
4. **Fast Execution** - Unit tests <0.2s, integration tests <1s

---

## 12. Logging Requirements

### 12.1 Log Levels

**INFO:**
- Topic resource initialization
- Topic resource closure

**DEBUG:**
- Message write operations
- Message read operations
- Acknowledgment operations
- Delegate creation/closure

**WARN:**
- Transient errors (with `recordError()`)
- Failed SQL operations

**ERROR:**
- Fatal errors (with exception throw)

### 12.2 Log Format

```java
// Good - Single-line, contextual
log.debug("Read message from topic '{}': messageId={}, consumerGroup={}", 
    topicName, messageId, consumerGroup);

// Good - WARN with recordError
log.warn("Failed to write message to topic '{}'", topicName);
recordError("WRITE_FAILED", "SQL INSERT failed", "Topic: " + topicName);

// Bad - Multi-line
log.info("Message:\n  ID: {}\n  Group: {}", id, group);  // DON'T DO THIS
```

---

## 13. Metrics

### 13.1 Aggregate Metrics (AbstractTopicResource)

- `messages_published` - Total messages written across all writers
- `messages_received` - Total messages read across all readers
- `messages_acknowledged` - Total messages acknowledged
- `write_throughput_per_minute` - SlidingWindowCounter (60s)
- `read_throughput_per_minute` - SlidingWindowCounter (60s)

### 13.2 Delegate Metrics (per service)

- `parent_messages_published` - Reference to parent aggregate
- `parent_messages_received` - Reference to parent aggregate
- `parent_messages_acknowledged` - Reference to parent aggregate
- `error_count` - Delegate-specific errors

---

## 14. Implementation Notes

### 14.1 Key Design Decisions

1. **H2 over Chronicle:** Simpler architecture, multi-writer support, explicit ACK
2. **Separate Files:** All classes in separate files (no inner classes)
3. **Direct SQL:** No internal queue or writer thread needed
4. **Consumer Groups:** SQL-based filtering (simple, effective)

### 14.2 Future Enhancements

1. **Junction Table:** For true multi-consumer-group support (Phase 14.3)
2. **Connection Pooling:** For higher throughput (if needed)
3. **Batch Operations:** Batch INSERT/DELETE for efficiency
4. **Dead Letter Queue:** Failed messages to DLQ resource (Phase 14.2.7)

### 14.3 Migration Path

To switch from H2 to Kafka/SQS:
1. Implement `KafkaTopicResource` extending `AbstractTopicResource`
2. Implement `KafkaTopicWriterDelegate` and `KafkaTopicReaderDelegate`
3. Update config: `type = "...KafkaTopicResource"`
4. **No service code changes!** (Interface abstraction works)

---

## 15. Acceptance Criteria

- [ ] All interfaces and abstract classes compile without errors
- [ ] H2TopicResource initializes database table correctly
- [ ] Writers can send messages concurrently (multi-writer test)
- [ ] Readers receive messages in order (FIFO test)
- [ ] Consumer groups receive messages independently (isolation test)
- [ ] Acknowledgment removes messages from database (ACK test)
- [ ] Messages persist across topic restarts (persistence test)
- [ ] All unit tests pass (<0.2s each)
- [ ] All integration tests pass (<1s each)
- [ ] Zero WARN/ERROR logs without `@ExpectLog`
- [ ] Metrics updated correctly (aggregate + delegate)
- [ ] Configuration loads from reference.conf
- [ ] JavaDoc complete for all public/protected members

---

## 16. Dependencies

### 16.1 Build Configuration

**File:** `build.gradle.kts` (excerpt)

```kotlin
dependencies {
    // Existing dependencies...
    
    // H2 Database (already present for MetadataIndexer)
    implementation("com.h2database:h2:2.2.224")
    
    // Protobuf (already present)
    implementation("com.google.protobuf:protobuf-java:3.24.3")
}
```

**Note:** H2 is already a dependency, no new libraries needed!

---

## 17. Comparison: Chronicle vs H2

| Aspect | Chronicle Queue | H2 Database |
|--------|----------------|-------------|
| **Architecture** | Queue + Thread + BlockingQueue | Direct SQL |
| **Writers** | Single (needs workaround) | Multi (MVCC) |
| **Complexity** | High (inner classes, thread) | Low (JDBC) |
| **ACK** | Implicit (tailer advance) | Explicit (DELETE) |
| **Debugging** | Binary files | SQL queries |
| **Performance** | Ultra-fast (μs) | Fast (ms) |
| **Best For** | Ultra-low latency | Batch processing |

**Recommendation:** H2 is simpler and sufficient for evochora's batch notification use case.

---

*End of Specification*

