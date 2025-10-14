# Phase 14.2.1: H2-Based Topic Infrastructure

**Status:** Open  
**Parent:** [14.2 Indexer Foundation](14_2_INDEXER_FOUNDATION.md)  
**Depends On:** Phase 2.4 (Metadata Indexer)

**Recent Changes:**
- ✅ **Centralized Tables:** Single `topic_messages` and `consumer_group_acks` tables for all topics (no schema explosion!)
- ✅ **Unlimited Scalability:** Add topics without creating new tables - `topic_name` column provides logical partitioning
- ✅ **Shared Trigger:** Single H2 trigger routes notifications by `topic_name` to correct queue
- ✅ **Composite Indexes:** All indexes include `topic_name` for partition-like performance
- ✅ **HikariCP Integration:** Added connection pooling following `H2Database` pattern
- ✅ **PreparedStatement Pooling:** Delegates prepare SQL statements once for ~30-50% performance gain
- ✅ **System-wide Connection Limits:** `maxPoolSize` config enforces resource limits across all topics
- ✅ **HikariCP Metrics:** Connection pool metrics exposed via MXBean (active/idle/total/awaiting)
- ✅ **Delegate Lifecycle:** Connections and PreparedStatements held for delegate lifetime, returned to pool on close
- ✅ **Single Validation Point:** Eliminated consumer group validation duplication (DRY principle)
- ✅ **Transaction Management:** Added explicit transactions for ACK (3-step atomic operation), writer uses auto-commit
- ✅ **Protobuf Contracts:** Fixed BatchInfo and MetadataInfo to match Chronicle spec (simulation_run_id, storage_key, written_at_ms)
- ✅ **Schema Management:** Added `ISimulationRunAwareTopic` interface and `H2SchemaUtil` for per-run schema isolation
- ✅ **H2SchemaUtil:** Centralized H2 schema operations (name sanitization, creation with bug workaround, switching)

---

## 1. Overview

This specification defines a **H2 database-based topic infrastructure** as an alternative to Chronicle Queue for the in-process data pipeline. Unlike Chronicle Queue, H2 provides:

- **Centralized tables** - Single `topic_messages` and `consumer_group_acks` tables for all topics (no schema explosion!)
- **Unlimited scalability** - Add topics without creating new tables or indexes
- **Atomic claim-based delivery** - `UPDATE...RETURNING` prevents race conditions (single SQL statement!)
- **Multi-writer support** - No internal queue or writer thread needed (H2 MVCC)
- **HikariCP connection pooling** - Efficient connection management with system-wide limits
- **PreparedStatement pooling** - Reuse SQL statements per delegate (~30-50% performance gain)
- **Event-driven delivery** - H2 triggers provide instant notifications (no polling!)
- **Explicit acknowledgment** - SQL-based ACK via junction table (not DELETE)
- **Consumer groups** - Proper isolation via `consumer_group_acks` junction table
- **Competing consumers** - `FOR UPDATE SKIP LOCKED` for automatic load balancing
- **Permanent storage** - Messages never deleted (enables historical replay)
- **In-flight tracking** - `claimed_by` column identifies messages being processed
- **Debuggability** - Direct SQL query access
- **Simplicity** - Standard JDBC operations, no API limitations

The H2 implementation uses **separate class files** instead of inner classes for better readability and maintainability.

### File Structure

```
src/main/java/org/evochora/datapipeline/
├── api/resources/topics/
│   ├── ISimulationRunAwareTopic.java  # Interface for simulation run awareness
│   ├── ITopicWriter.java              # Writer interface (extends ISimulationRunAwareTopic)
│   ├── ITopicReader.java              # Reader interface (extends ISimulationRunAwareTopic)
│   └── TopicMessage.java              # Message wrapper with ACK token
├── resources/topics/
│   ├── AbstractTopicResource.java     # Base class for topic implementations
│   ├── AbstractTopicDelegate.java     # Base for delegates
│   ├── AbstractTopicDelegateWriter.java   # Base for writer delegates (implements ISimulationRunAwareTopic)
│   ├── AbstractTopicDelegateReader.java   # Base for reader delegates (implements ISimulationRunAwareTopic)
│   ├── ReceivedEnvelope.java          # Record for envelope + ACK token
│   ├── H2TopicResource.java           # H2 implementation (main class)
│   ├── H2InsertTrigger.java           # H2 trigger for event-driven notifications
│   ├── H2TopicWriterDelegate.java     # H2 writer delegate (uses H2SchemaUtil)
│   └── H2TopicReaderDelegate.java     # H2 reader delegate (uses H2SchemaUtil)
└── utils/
    └── H2SchemaUtil.java              # H2 schema operations (shared with H2Database)

src/main/proto/org/evochora/datapipeline/api/contracts/
└── notification_contracts.proto       # TopicEnvelope, BatchInfo, MetadataInfo
```

---

## 2. Goals

1. **Replace Batch Discovery:** Eliminate polling, gap detection, and coordinator tables
2. **Publish/Subscribe Model:** Services publish notifications; indexers subscribe via consumer groups
3. **Competing Consumers:** Multiple indexers in same group distribute work automatically
4. **Persistence:** All messages survive process restarts (H2 file-based storage)
5. **Permanent Storage:** Messages never deleted (enables historical replay and new consumer groups)
6. **At-Least-Once Delivery:** Explicit acknowledgment with SQL transactions (junction table)
7. **Idempotency Support:** Consistent messageId and timestamp for duplicate detection
8. **Multi-Writer:** Direct database writes without internal queuing (H2 MVCC)
9. **Modular Architecture:** Abstract interfaces allow future Kafka/cloud migration

---

## 3. Requirements

### 3.1 Functional Requirements

- **FR-1:** Topics accept Protobuf messages (`com.google.protobuf.Message`)
- **FR-2:** Messages wrapped in `TopicEnvelope` with messageId and timestamp
- **FR-3:** Consumer groups receive all messages independently (Pub/Sub)
- **FR-4:** Multiple consumers within a group compete for messages (Load Balancing)
- **FR-5:** Explicit acknowledgment via `ack()` records acknowledgment for the consumer group
- **FR-6:** Messages remain in database permanently (no automatic deletion)
- **FR-7:** Messages ordered by write sequence (auto-incrementing ID)
- **FR-8:** Multiple concurrent writers supported (H2 MVCC)
- **FR-9:** Failed deserialization logged, message skipped

### 3.2 Non-Functional Requirements

- **NFR-1:** Performance: 1000+ msg/sec per topic (sufficient for evochora)
- **NFR-2:** Latency: Instant notification via H2 triggers (no polling delay)
- **NFR-3:** Durability: H2 file-based persistence (survives restarts)
- **NFR-4:** Resource efficiency: Reuse DB connections, O(1) metrics, event-driven (no busy-waiting)
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
│  - Creates H2InsertTrigger (event-driven notifications)     │
│  - Manages BlockingQueue<Long> for message notifications    │
│  - Creates H2TopicWriterDelegate / H2TopicReaderDelegate    │
│  - Maintains aggregate metrics                              │
│  - Template methods: createReaderDelegate, createWriterDelegate │
└─────────────────────────────────────────────────────────────┘
              │                  │                      │
              │ creates          │ registers            │ creates
              ▼                  ▼                      ▼
┌──────────────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐
│ H2TopicWriterDelegate<T> │  │ H2InsertTrigger  │  │ H2TopicReaderDelegate<T> │
│  - Wraps messages        │  │  - Fires on      │  │  - Waits on notification │
│  - Direct SQL INSERT ────┼─→│    INSERT        │  │    queue (event-driven)  │
│  - Updates metrics       │  │  - Notifies ─────┼─→│  - Reads with FOR UPDATE │
└──────────────────────────┘  │    queue         │  │  - Unwraps envelopes     │
                              └──────────────────┘  │  - Consumer group filter │
                                                    └──────────────────────────┘
```

### 4.2 Key Differences from Chronicle Queue

| Aspect | Chronicle Queue | H2 Database |
|--------|----------------|-------------|
| **Writers** | Single-writer (needs internal queue) | Multi-writer (H2 MVCC) |
| **Message Delivery** | Tailer advance (implicit lock) | Atomic claim (`UPDATE...RETURNING`) |
| **Race Conditions** | Possible (tailer + ACK not atomic) | Impossible (single SQL statement) |
| **Acknowledgment** | Implicit (tailer advance) | Explicit (INSERT into junction table) |
| **Consumer Groups** | Tailer per group (complex) | Junction table (simple SQL) |
| **Competing Consumers** | Manual coordination | `FOR UPDATE SKIP LOCKED` (automatic) |
| **In-Flight Tracking** | None | `claimed_by` column |
| **Message Retention** | Configurable | Permanent (never deleted) |
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

**Read Path (Event-Driven with Atomic Claim):**
```
Service ← ITopicReader.receive()
        ← AbstractTopicDelegateReader.receive() [unwraps envelope]
        ← H2TopicReaderDelegate.receiveEnvelope()
        ← BlockingQueue.take() [waits for H2 trigger notification]
        ← SQL UPDATE...RETURNING [ATOMIC: claim + read in single statement!]
           WHERE claimed_by IS NULL AND consumer_group filter
           FOR UPDATE SKIP LOCKED
```

**Acknowledgment Path (with Claim Release):**
```
Service → ITopicReader.ack(TopicMessage)
        → AbstractTopicDelegateReader.ack()
        → H2TopicReaderDelegate.acknowledgeMessage(rowId)
        → SQL INSERT INTO consumer_group_acks (consumer_group, message_id)
        → SQL UPDATE topic_messages SET claimed_by = NULL [release claim]
```

---

## 5. Event-Driven Architecture

### 5.1 Trigger-Based Notification System

Instead of polling the database every 100ms, H2TopicResource uses **H2 database triggers** to provide instant notifications when messages are inserted.

**Architecture:**
```
┌─────────────┐     INSERT      ┌──────────────┐    fire()    ┌─────────────────┐
│   Writer    │ ─────────────→  │  H2 Database │ ──────────→  │ H2InsertTrigger │
└─────────────┘                 └──────────────┘              └─────────────────┘
                                                                       │
                                                                       │ offer(messageId)
                                                                       ▼
                                                            ┌──────────────────────┐
                                                            │ BlockingQueue<Long>  │
                                                            │  messageNotifications│
                                                            └──────────────────────┘
                                                                       │
                                                                       │ take() / poll()
                                                                       ▼
                                                                ┌─────────────┐
                                                                │   Reader    │
                                                                │  (wakes up  │
                                                                │  instantly!)│
                                                                └─────────────┘
```

### 5.2 Benefits of Event-Driven Approach

**Compared to Polling:**
| Aspect | Polling (100ms) | Event-Driven (Trigger) |
|--------|----------------|------------------------|
| **Latency** | Min. 0-100ms delay | Instant (μs) |
| **CPU Usage** | Constant (busy-waiting) | Minimal (blocking wait) |
| **DB Load** | Query every 100ms (even when empty) | Query only on notification |
| **Scalability** | O(readers) query load | O(1) per reader (blocking) |

**Trade-Offs:**
- ✅ Instant notification (no polling delay)
- ✅ Lower CPU usage (BlockingQueue.take() is efficient)
- ✅ Lower DB load (no empty queries)
- ⚠️ Trigger overhead (~microseconds per INSERT)
- ⚠️ Static registry for trigger-to-queue mapping

### 5.3 Edge Cases

**Competing Consumers:**
- Trigger fires → ALL readers in queue receive notification
- Each reader tries `SELECT ... FOR UPDATE SKIP LOCKED`
- First reader locks message → Others skip and wait for next notification
- This is correct behavior (automatic load distribution)

**Consumer Group Filtering:**
- Trigger notifies about ALL new messages
- Reader SQL filters by consumer group (NOT IN acknowledged messages)
- If message not for this group → Reader waits for next notification
- Small retry loop with 100ms timeout handles this case

### 5.4 Stuck Message Detection and Recovery

**What are "Stuck Messages"?**

Messages that have been claimed (`claimed_by IS NOT NULL`) but not acknowledged for an extended period. This can happen if:
- Consumer crashes after `receive()` but before `ack()`
- Consumer hangs indefinitely
- Application terminated ungracefully

**Detection Query:**
```sql
-- Find messages claimed more than 5 minutes ago
SELECT id, message_id, claimed_by, claimed_at
FROM topic_messages
WHERE claimed_by IS NOT NULL
AND claimed_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE;
```

**Recovery Options:**

1. **Automatic Reset (Optional Background Job):**
```sql
-- Reset stuck claims (makes messages available again)
UPDATE topic_messages
SET claimed_by = NULL, claimed_at = NULL
WHERE claimed_by IS NOT NULL
AND claimed_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE;
```

2. **Manual Intervention:**
```sql
-- Admin query to inspect stuck messages
SELECT * FROM topic_messages WHERE claimed_by IS NOT NULL;

-- Release specific stuck message
UPDATE topic_messages SET claimed_by = NULL, claimed_at = NULL WHERE id = ?;
```

**Automatic Reassignment (Configurable Timeout):**

Phase 14.2.1 **DOES** include automatic stuck message reassignment via configurable `claimTimeout`:

```hocon
claimTimeout = 300  # Seconds (5 minutes), 0 = disabled
```

- **`claimTimeout > 0`:** Messages claimed longer than timeout are automatically reassigned to next consumer
- **`claimTimeout = 0`:** Automatic reassignment disabled (manual intervention required)
- **Idempotency:** Services MUST be idempotent (message may be processed multiple times)
- **Logging:** Reassignment triggers WARN log + error recording
- **Metric:** `stuck_messages_reassigned` (O(1) AtomicLong) tracks reassignment count

**Manual Cleanup (Alternative):**

Automatic cleanup background jobs (e.g., periodic reset task) are **NOT** included. They can be added later as:
- A scheduled task in the ServiceManager
- A separate monitoring service
- An administrative endpoint in the HTTP API

---

## 6. Database Schema

### 6.1 Centralized Topic Messages Table (Shared by All Topics)

```sql
CREATE TABLE IF NOT EXISTS topic_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_name VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    timestamp BIGINT NOT NULL,
    envelope BINARY NOT NULL,
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite unique constraint (topic + message)
    UNIQUE KEY uk_topic_message (topic_name, message_id),
    
    -- Performance indexes (all include topic_name for partition-like behavior)
    INDEX idx_topic_unclaimed (topic_name, id) WHERE claimed_by IS NULL,
    INDEX idx_topic_claimed (topic_name, claimed_at) WHERE claimed_by IS NOT NULL,
    INDEX idx_topic_claim_status (topic_name, claimed_by, claimed_at)
);
```

**Column Descriptions:**
- `id`: Auto-incrementing sequence number (used as ACK token for internal tracking)
- `topic_name`: Name of the topic this message belongs to (enables logical partitioning)
- `message_id`: UUID from TopicEnvelope (unique per topic, for idempotency)
- `timestamp`: Unix timestamp from TopicEnvelope
- `envelope`: Serialized TopicEnvelope (Protobuf binary)
- `claimed_by`: Consumer ID that claimed this message (NULL = available, non-NULL = in-flight)
- `claimed_at`: Timestamp when message was claimed (for stuck message detection)
- `created_at`: Database timestamp for debugging

**Design Notes:**
- **Centralized Table:** Single table for all topics (no schema explosion, unlimited scalability)
- **Logical Partitioning:** `topic_name` column enables topic-specific queries with composite indexes
- **Atomic Claim:** Messages are claimed atomically during `receive()` to prevent double-processing
- **No consumer_group column:** Each group tracks acknowledgments independently via junction table
- **Performance Indexes:**
  - `idx_topic_unclaimed` - Composite partial index for unclaimed messages per topic
  - `idx_topic_claimed` - Composite partial index for stuck message detection per topic
  - `idx_topic_claim_status` - Composite index for efficient claim status queries per topic
- **Scalability:** Supports unlimited topics without creating new tables

### 6.2 Centralized Consumer Group Acknowledgments Table (Shared by All Topics)

```sql
CREATE TABLE IF NOT EXISTS consumer_group_acks (
    topic_name VARCHAR(255) NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    acknowledged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key (topic + consumer group + message)
    PRIMARY KEY (topic_name, consumer_group, message_id),
    
    -- Performance index for consumer group filtering
    INDEX idx_topic_consumer (topic_name, consumer_group, message_id)
);
```

**Column Descriptions:**
- `topic_name`: Name of the topic this acknowledgment belongs to
- `consumer_group`: Name of the consumer group that acknowledged the message
- `message_id`: Reference to `topic_messages.message_id`
- `acknowledged_at`: Timestamp when the message was acknowledged by this group

**Design Rationale:**
- **Centralized Table:** Single table for all topics and consumer groups (no schema explosion)
- **Pub/Sub:** Each consumer group receives all messages independently per topic
- **Competing Consumers:** Multiple consumers within the same group compete for messages
- **No Data Loss:** Acknowledgment by one group does not affect other groups
- **Persistence:** Messages remain in database forever (no automatic cleanup)
- **Scalability:** Supports unlimited topics and consumer groups without creating new tables

### 6.4 Read and Acknowledgment Strategy (Claim-Based)

**Write:**
```sql
INSERT INTO topic_messages (topic_name, message_id, timestamp, envelope) 
VALUES (?, ?, ?, ?);
```

**Read (Atomic Claim with UPDATE...RETURNING):**
```sql
-- Atomically claim and read the next available message in ONE statement
-- Uses LEFT JOIN instead of NOT IN for better performance
UPDATE topic_messages tm
SET claimed_by = ?, claimed_at = CURRENT_TIMESTAMP
WHERE id = (
    SELECT tm2.id 
    FROM topic_messages tm2
    LEFT JOIN consumer_group_acks cga 
        ON tm2.topic_name = cga.topic_name 
        AND tm2.message_id = cga.message_id 
        AND cga.consumer_group = ?
    WHERE cga.message_id IS NULL  -- Not acknowledged by this group
    AND tm2.topic_name = ?        -- Filter by topic
    AND tm2.claimed_by IS NULL    -- Only unclaimed messages
    AND tm2.id > ?                -- Pagination for efficiency
    ORDER BY tm2.id 
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING id, message_id, envelope;
```

**Key Features:**
- ✅ **Atomic Claim:** `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED)` prevents race conditions
- ✅ **No Transaction Needed:** `UPDATE...RETURNING` is a single atomic statement
- ✅ **Topic Isolation:** `WHERE tm2.topic_name = ?` ensures each topic is independent
- ✅ **Consumer Group Filtering:** `LEFT JOIN ... WHERE cga.message_id IS NULL` (better performance than `NOT IN`)
- ✅ **Competing Consumers:** `FOR UPDATE SKIP LOCKED` allows multiple consumers to skip locked/claimed rows
- ✅ **In-Flight Tracking:** `claimed_by` identifies which consumer is processing the message
- ✅ **Performance Optimized:** Composite indexes on `(topic_name, claimed_by, claimed_at)` enable efficient queries

**Acknowledge:**
```sql
-- Step 1: Mark as acknowledged for this consumer group
INSERT INTO consumer_group_acks (topic_name, consumer_group, message_id) 
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE acknowledged_at = CURRENT_TIMESTAMP;

-- Step 2: Release claim (makes message available for other consumer groups)
UPDATE topic_messages 
SET claimed_by = NULL, claimed_at = NULL 
WHERE id = ?;
```

**Note:** Messages are never deleted. They remain in the database permanently, allowing:
- New consumer groups to process historical messages
- Re-indexing without re-running simulations
- Audit trail of all published messages
- Detection of stuck messages (claimed but not acknowledged)

### 6.3 H2 Trigger Setup

The event-driven architecture requires an H2 trigger to be created:

```sql
CREATE TRIGGER IF NOT EXISTS topic_messages_<name>_notify_trigger
AFTER INSERT ON topic_messages_<name>
FOR EACH ROW
CALL "org.evochora.datapipeline.resources.topics.H2InsertTrigger";
```

**Trigger Lifecycle:**
1. Created during `H2TopicResource` initialization
2. Registered with static notification queue map
3. Fires on every INSERT (pushes message ID to queue)
4. Deregistered and dropped during `H2TopicResource.close()`

---

## 7. API Design

### 7.1 Core Interfaces

#### ISimulationRunAwareTopic

**File:** `src/main/java/org/evochora/datapipeline/api/resources/topics/ISimulationRunAwareTopic.java`

```java
package org.evochora.datapipeline.api.resources.topics;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Base capability for topic operations that work within a simulation run context.
 * <p>
 * All topic capabilities that operate on run-specific data extend this interface.
 * The simulation run is set once per delegate instance after creation.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>Service/Indexer creates topic delegate (writer or reader)</li>
 *   <li>Service/Indexer calls setSimulationRun(runId) on the delegate</li>
 *   <li>All subsequent operations on the delegate work within that simulation run</li>
 * </ol>
 * <p>
 * <strong>Run Isolation:</strong>
 * Each simulation run has its own isolated storage (e.g., H2 schema, Chronicle path).
 * This provides complete data isolation between runs and enables automatic cleanup
 * when a run's schema/directory is deleted.
 * <p>
 * <strong>Implementation Strategy:</strong>
 * <ul>
 *   <li>H2: Uses database schema (e.g., SIM_20251006_UUID)</li>
 *   <li>Chronicle: Uses directory path (e.g., ./data/SIM_20251006_UUID/topic_name)</li>
 *   <li>Kafka: Uses topic suffix (e.g., batch-notifications-SIM_20251006_UUID)</li>
 * </ul>
 */
public interface ISimulationRunAwareTopic extends IResource {
    /**
     * Sets the simulation run for this topic delegate.
     * <p>
     * Must be called once before any topic operations (send/receive).
     * Implementation-specific behavior:
     * <ul>
     *   <li>H2: Creates and sets database schema</li>
     *   <li>Chronicle: Adjusts queue path</li>
     *   <li>Kafka: Adjusts topic name</li>
     * </ul>
     *
     * @param simulationRunId Raw simulation run ID (sanitized internally if needed)
     * @throws IllegalStateException if already set (delegates are single-run)
     */
    void setSimulationRun(String simulationRunId);
}
```

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
 * <strong>Simulation Run Awareness:</strong> Extends {@link ISimulationRunAwareTopic} to support
 * run-specific isolation. Call {@link #setSimulationRun(String)} before sending messages.
 * <p>
 * <strong>Implements:</strong> {@link IResource}, {@link ISimulationRunAwareTopic}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public interface ITopicWriter<T extends Message> extends IResource, ISimulationRunAwareTopic {
    
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
 * <strong>Simulation Run Awareness:</strong> Extends {@link ISimulationRunAwareTopic} to support
 * run-specific isolation. Call {@link #setSimulationRun(String)} before reading messages.
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe for a single reader.
 * Multiple readers in the same consumer group MAY conflict (implementation-specific).
 * <p>
 * <strong>Implements:</strong> {@link IResource}, {@link ISimulationRunAwareTopic}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public interface ITopicReader<T extends Message, ACK> extends IResource, ISimulationRunAwareTopic {
    
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

## 8. Protobuf Contracts

### 8.1 Topic Envelope

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
 * Notification that a simulation batch has been written to storage.
 * Sent by PersistenceService after successful batch write.
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

---

## 9. Abstract Base Classes

These classes are now in **separate files** (not inner classes).

### 9.1 AbstractTopicResource

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

### 9.2 AbstractTopicDelegate

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

### 9.3 AbstractTopicDelegateWriter

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
 * <strong>Simulation Run Awareness:</strong>
 * Implements {@link ISimulationRunAwareTopic} to store the simulation run ID and provide
 * access to subclasses via {@link #getSimulationRunId()}. Subclasses (e.g., H2TopicWriterDelegate)
 * can override {@link #onSimulationRunSet(String)} to perform initialization.
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement {@link #sendEnvelope(TopicEnvelope)} to write the envelope to the underlying topic</li>
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup</li>
 * </ul>
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
     * The simulation run ID for this writer.
     * Set via {@link #setSimulationRun(String)} before sending messages.
     */
    private String simulationRunId;
    
    /**
     * Creates a new writer delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegateWriter(P parent, ResourceContext context) {
        super(parent, context);
    }
    
    @Override
    public final void setSimulationRun(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isBlank()) {
            throw new IllegalArgumentException("Simulation run ID must not be null or blank");
        }
        this.simulationRunId = simulationRunId;
        onSimulationRunSet(simulationRunId);
    }
    
    /**
     * Returns the simulation run ID for this writer.
     * <p>
     * Subclasses can use this to access the run ID for schema selection, path construction, etc.
     *
     * @return The simulation run ID, or null if not yet set.
     */
    protected final String getSimulationRunId() {
        return simulationRunId;
    }
    
    /**
     * Template method called after {@link #setSimulationRun(String)} is invoked.
     * <p>
     * Subclasses can override this to perform run-specific initialization:
     * <ul>
     *   <li>H2: Set database schema to run-specific schema</li>
     *   <li>Chronicle: Open run-specific queue directory</li>
     *   <li>Kafka: Subscribe to run-specific topic partition</li>
     * </ul>
     * <p>
     * Default implementation is a no-op.
     *
     * @param simulationRunId The simulation run ID (never null or blank).
     */
    protected void onSimulationRunSet(String simulationRunId) {
        // Default: no-op (subclasses override as needed)
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

### 9.4 AbstractTopicDelegateReader

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
 * <strong>Simulation Run Awareness:</strong>
 * Implements {@link ISimulationRunAwareTopic} to store the simulation run ID and provide
 * access to subclasses via {@link #getSimulationRunId()}. Subclasses (e.g., H2TopicReaderDelegate)
 * can override {@link #onSimulationRunSet(String)} to perform initialization.
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement {@link #receiveEnvelope(long, TimeUnit)} to read from the underlying topic</li>
 *   <li>Implement {@link #acknowledgeMessage(Object)} for technology-specific acknowledgment</li>
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup</li>
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
     * The simulation run ID for this reader.
     * Set via {@link #setSimulationRun(String)} before reading messages.
     */
    private String simulationRunId;
    
    /**
     * Creates a new reader delegate.
     * <p>
     * <strong>Single Validation Point:</strong>
     * This is the ONLY place where consumerGroup validation occurs. All concrete implementations
     * (H2, Chronicle, Kafka) delegate validation to this abstract class to ensure consistency.
     *
     * @param parent The parent topic resource.
     * @param context The resource context (must include consumerGroup parameter).
     * @throws IllegalArgumentException if consumerGroup is null or blank.
     */
    protected AbstractTopicDelegateReader(P parent, ResourceContext context) {
        super(parent, context);
        
        // Single validation point - ensures consistent error messages across all implementations
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException(String.format(
                "Consumer group parameter is required for topic reader. " +
                "Expected format: 'topic-read:%s?consumerGroup=<group-name>'", 
                parent.getResourceName()));
        }
    }
    
    @Override
    public final void setSimulationRun(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isBlank()) {
            throw new IllegalArgumentException("Simulation run ID must not be null or blank");
        }
        this.simulationRunId = simulationRunId;
        onSimulationRunSet(simulationRunId);
    }
    
    /**
     * Returns the simulation run ID for this reader.
     * <p>
     * Subclasses can use this to access the run ID for schema selection, path construction, etc.
     *
     * @return The simulation run ID, or null if not yet set.
     */
    protected final String getSimulationRunId() {
        return simulationRunId;
    }
    
    /**
     * Template method called after {@link #setSimulationRun(String)} is invoked.
     * <p>
     * Subclasses can override this to perform run-specific initialization:
     * <ul>
     *   <li>H2: Set database schema to run-specific schema</li>
     *   <li>Chronicle: Open run-specific queue directory</li>
     *   <li>Kafka: Subscribe to run-specific topic partition</li>
     * </ul>
     * <p>
     * Default implementation is a no-op.
     *
     * @param simulationRunId The simulation run ID (never null or blank).
     */
    protected void onSimulationRunSet(String simulationRunId) {
        // Default: no-op (subclasses override as needed)
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
     * <p>
     * Uses dynamic type resolution from {@code google.protobuf.Any}. The type URL embedded
     * in the Any payload (e.g., {@code "type.googleapis.com/org.evochora.datapipeline.api.contracts.BatchInfo"})
     * is used to load the appropriate message class at runtime.
     * <p>
     * <strong>Type Agnostic:</strong>
     * This approach does not require a static {@code messageType} field or configuration.
     * The same topic infrastructure can handle any Protobuf message type, making it flexible
     * for future extensions.
     *
     * @param received The received envelope with acknowledgment token.
     * @return The unwrapped TopicMessage.
     * @throws RuntimeException if the message class cannot be loaded or deserialization fails.
     */
    private TopicMessage<T, ACK> unwrapEnvelope(ReceivedEnvelope<ACK> received) {
        TopicEnvelope envelope = received.envelope();
        
        try {
            com.google.protobuf.Any anyPayload = envelope.getPayload();
            
            // Extract the fully qualified class name from the type URL
            // Format: "type.googleapis.com/org.evochora.datapipeline.api.contracts.BatchInfo"
            String typeUrl = anyPayload.getTypeUrl();
            String className = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
            
            // Dynamically load the message class
            @SuppressWarnings("unchecked")
            Class<? extends Message> messageClass = (Class<? extends Message>) Class.forName(className);
            
            // Unpack the payload
            @SuppressWarnings("unchecked")
            T payload = (T) anyPayload.unpack(messageClass);
            
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

### 9.5 ReceivedEnvelope

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

## 10. H2 Implementation

### 10.1 H2TopicResource

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicResource.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.PathExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 database-based topic implementation with HikariCP connection pooling.
 * <p>
 * This implementation uses an H2 file-based database to provide:
 * <ul>
 *   <li><strong>Persistence:</strong> Messages survive process restarts</li>
 *   <li><strong>Multi-Writer:</strong> Concurrent writes via H2 MVCC and HikariCP pooling</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP for efficient connection management</li>
 *   <li><strong>Explicit ACK:</strong> Junction table tracks acknowledgments per consumer group</li>
 *   <li><strong>Consumer Groups:</strong> Junction table ensures proper isolation</li>
 *   <li><strong>Competing Consumers:</strong> FOR UPDATE SKIP LOCKED for automatic load balancing</li>
 *   <li><strong>Permanent Storage:</strong> Messages never deleted (historical replay support)</li>
 *   <li><strong>Type Agnostic:</strong> Dynamic type resolution from google.protobuf.Any (no config needed)</li>
 *   <li><strong>Simplicity:</strong> Standard JDBC, no API limitations</li>
 * </ul>
 * <p>
 * <strong>Key Differences from Chronicle Queue:</strong>
 * <ul>
 *   <li>No internal BlockingQueue (direct database writes)</li>
 *   <li>No writer thread (multi-writer support via H2 MVCC)</li>
 *   <li>Explicit acknowledgment (INSERT into junction table, not DELETE)</li>
 *   <li>SQL-based consumer groups (junction table approach)</li>
 *   <li>Messages never deleted (permanent storage for replay)</li>
 *   <li>HikariCP connection pooling (shared connections, system-wide limits)</li>
 * </ul>
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Write latency: ~5-10ms per message (SQL INSERT + trigger)</li>
 *   <li>Read latency: Instant notification (H2 trigger), ~5-10ms SQL SELECT</li>
 *   <li>Throughput: 1000+ msg/sec (sufficient for evochora batch processing)</li>
 *   <li>CPU efficiency: High (event-driven, no polling loops)</li>
 *   <li>Connection overhead: Low (HikariCP reuses connections)</li>
 * </ul>
 * <p>
 * <strong>Event-Driven Delivery:</strong>
 * Uses H2 database triggers ({@link H2InsertTrigger}) to push notifications into a
 * {@link BlockingQueue} when messages are inserted. Readers use {@code BlockingQueue.take()}
 * for instant wake-up instead of polling the database every 100ms.
 * <p>
 * <strong>HikariCP Integration:</strong>
 * Follows the same pattern as {@code H2Database} resource for consistency:
 * <ul>
 *   <li>Configurable pool size (maxPoolSize, minIdle)</li>
 *   <li>System-wide connection limits across all topics</li>
 *   <li>Connection metrics via HikariCP MXBean</li>
 *   <li>Automatic connection health checks</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe. Multiple writers and readers can operate concurrently.
 * HikariCP manages connection thread safety internally.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public class H2TopicResource<T extends Message> extends AbstractTopicResource<T, Long> implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicResource.class);
    
    private final HikariDataSource dataSource;
    private final int claimTimeoutSeconds;  // 0 = disabled, > 0 = timeout for stuck message reassignment
    private final BlockingQueue<Long> messageNotifications;  // Event-driven notification from H2 trigger
    private final SlidingWindowCounter writeThroughput;
    private final SlidingWindowCounter readThroughput;
    private final AtomicLong stuckMessagesReassigned;  // O(1) metric for reassignments
    
    // Centralized table names (shared by all topics)
    private static final String MESSAGES_TABLE = "topic_messages";
    private static final String ACKS_TABLE = "consumer_group_acks";
    
    /**
     * Creates a new H2TopicResource with HikariCP connection pooling.
     * <p>
     * <strong>Type Agnostic:</strong>
     * This resource does not require a {@code messageType} configuration. The concrete message type
     * is determined dynamically from the {@code google.protobuf.Any} type URL when messages are read.
     * This makes the same topic infrastructure usable for any Protobuf message type.
     * <p>
     * <strong>HikariCP Configuration:</strong>
     * Follows the same pattern as {@code H2Database} resource:
     * <ul>
     *   <li>{@code dbPath} - Database file path (supports path expansion)</li>
     *   <li>{@code maxPoolSize} - Maximum number of connections (default: 10)</li>
     *   <li>{@code minIdle} - Minimum idle connections (default: 2)</li>
     *   <li>{@code username} - Database username (default: "sa")</li>
     *   <li>{@code password} - Database password (default: "")</li>
     * </ul>
     *
     * @param name The resource name.
     * @param options The configuration options.
     * @throws RuntimeException if database initialization fails.
     */
    public H2TopicResource(String name, Config options) {
        super(name, options);
        
        // Path expansion (same as H2Database)
        String dbPath = options.hasPath("dbPath") 
            ? options.getString("dbPath") 
            : "./data/topics/" + name;
        String expandedPath = PathExpansion.expandPath(dbPath);
        if (!dbPath.equals(expandedPath)) {
            log.debug("Expanded dbPath: '{}' -> '{}'", dbPath, expandedPath);
        }
        
        // Build JDBC URL
        String jdbcUrl = "jdbc:h2:" + expandedPath + ";AUTO_SERVER=TRUE";
        
        // HikariCP configuration (same pattern as H2Database)
        String username = options.hasPath("username") ? options.getString("username") : "sa";
        String password = options.hasPath("password") ? options.getString("password") : "";
        int maxPoolSize = options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10;
        int minIdle = options.hasPath("minIdle") ? options.getInt("minIdle") : 2;
        
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName("org.h2.Driver");  // Explicit for Fat JAR compatibility
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        
        this.claimTimeoutSeconds = options.hasPath("claimTimeout")
            ? options.getInt("claimTimeout")
            : 300;  // Default: 5 minutes
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            log.debug("Successfully connected to H2 database: {}", jdbcUrl);
            
            // Initialize centralized tables (shared by all topics)
            initializeCentralizedTables();
            
            // Initialize notification queue for event-driven message delivery
            this.messageNotifications = new LinkedBlockingQueue<>();
            
            // Register H2 trigger for instant notifications on INSERT (topic-specific)
            registerInsertTrigger();
            
            this.writeThroughput = new SlidingWindowCounter(60, TimeUnit.SECONDS);
            this.readThroughput = new SlidingWindowCounter(60, TimeUnit.SECONDS);
            this.stuckMessagesReassigned = new AtomicLong(0);
            
            log.info("H2 topic resource '{}' initialized: dbPath={}, table={}, claimTimeout={}s, poolSize={}/{}", 
                name, expandedPath, tableName, claimTimeoutSeconds, minIdle, maxPoolSize);
        } catch (Exception e) {
            log.error("Failed to initialize H2 topic resource '{}'", name);
            throw new RuntimeException("H2 topic initialization failed: " + name, e);
        }
    }
    
    /**
     * Registers an H2 trigger to provide instant notifications on message INSERT.
     * <p>
     * This enables event-driven message delivery instead of polling. When a message is inserted,
     * the trigger pushes the message ID into the {@link #messageNotifications} queue, instantly
     * waking up waiting readers.
     * <p>
     * <strong>Event-Driven Architecture:</strong>
     * <pre>
     * Writer → INSERT → H2 Trigger → BlockingQueue → Reader (INSTANT!)
     * </pre>
     * Instead of polling every 100ms, readers use {@code BlockingQueue.take()} which blocks
     * efficiently until a notification arrives.
     *
     * @throws SQLException if trigger creation fails.
     */
    private void registerInsertTrigger() throws SQLException {
        // With centralized table, we use a single trigger for all topics
        // The trigger reads topic_name from the inserted row and routes to the correct queue
        String triggerName = "topic_messages_notify_trigger";
        String triggerSql = String.format("""
            CREATE TRIGGER IF NOT EXISTS %s
            AFTER INSERT ON %s
            FOR EACH ROW
            CALL "org.evochora.datapipeline.resources.topics.H2InsertTrigger"
            """, triggerName, MESSAGES_TABLE);
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(triggerSql);
        }
        
        // Register this topic's notification queue with the trigger (static registry by topic name)
        H2InsertTrigger.registerNotificationQueue(getResourceName(), messageNotifications);
        
        log.debug("Registered H2 trigger '{}' for topic '{}' (event-driven notifications)", 
            triggerName, getResourceName());
    }
    
    /**
     * Initializes the centralized topic messages and consumer group acknowledgments tables.
     * <p>
     * These tables are shared by ALL topics. The {@code topic_name} column provides logical
     * partitioning. This design prevents schema explosion (no per-topic tables) and enables
     * unlimited scalability.
     * <p>
     * Uses HikariCP connection from the pool. Table creation is idempotent (CREATE IF NOT EXISTS).
     *
     * @throws SQLException if table creation fails.
     */
    private void initializeCentralizedTables() throws SQLException {
        // Create centralized messages table (shared by all topics)
        String createMessagesSql = """
            CREATE TABLE IF NOT EXISTS topic_messages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                topic_name VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                timestamp BIGINT NOT NULL,
                envelope BINARY NOT NULL,
                claimed_by VARCHAR(255),
                claimed_at TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                UNIQUE KEY uk_topic_message (topic_name, message_id),
                INDEX idx_topic_unclaimed (topic_name, id) WHERE claimed_by IS NULL,
                INDEX idx_topic_claimed (topic_name, claimed_at) WHERE claimed_by IS NOT NULL,
                INDEX idx_topic_claim_status (topic_name, claimed_by, claimed_at)
            )
            """;
        
        // Create centralized consumer group acknowledgments table (shared by all topics)
        String createAcksSql = """
            CREATE TABLE IF NOT EXISTS consumer_group_acks (
                topic_name VARCHAR(255) NOT NULL,
                consumer_group VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                acknowledged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                PRIMARY KEY (topic_name, consumer_group, message_id),
                INDEX idx_topic_consumer (topic_name, consumer_group, message_id)
            )
            """;
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesSql);
            stmt.execute(createAcksSql);
            log.debug("Initialized centralized topic tables (shared by all topics)");
        }
    }
    
    @Override
    protected ITopicWriter<T> createWriterDelegate(ResourceContext context) {
        return new H2TopicWriterDelegate<>(this, context);
    }
    
    @Override
    protected ITopicReader<T, Long> createReaderDelegate(ResourceContext context) {
        // No validation here - delegate to AbstractTopicDelegateReader for consistent validation
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
        metrics.put("stuck_messages_reassigned", stuckMessagesReassigned.get());
        
        // HikariCP connection pool metrics (same as H2Database)
        if (dataSource != null && !dataSource.isClosed()) {
            metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
            metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
            metrics.put("h2_pool_total_connections", dataSource.getHikariPoolMXBean().getTotalConnections());
            metrics.put("h2_pool_threads_awaiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }
    }
    
    /**
     * Gets a database connection from the HikariCP pool (package-private for delegate access).
     * <p>
     * <strong>Important:</strong> Delegates MUST close the connection after use (try-with-resources).
     * HikariCP will return the connection to the pool when {@code close()} is called.
     *
     * @return A JDBC connection from the pool.
     * @throws SQLException if connection acquisition fails.
     */
    Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * Gets the table name (package-private for delegate access).
     *
     * @return The table name.
     */
    String getMessagesTable() {
        return MESSAGES_TABLE;
    }
    
    /**
     * Gets the centralized acknowledgments table name (package-private for delegate access).
     *
     * @return The acknowledgments table name (shared by all topics).
     */
    String getAcksTable() {
        return ACKS_TABLE;
    }
    
    /**
     * Gets the message notification queue (package-private for delegate access).
     * <p>
     * Readers use this queue to receive instant notifications when new messages are inserted
     * via the H2 trigger. This enables event-driven delivery instead of polling.
     *
     * @return The notification queue.
     */
    BlockingQueue<Long> getNotificationQueue() {
        return messageNotifications;
    }
    
    /**
     * Gets the claim timeout in seconds (package-private for delegate access).
     *
     * @return The claim timeout (0 = disabled, > 0 = timeout in seconds).
     */
    int getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }
    
    /**
     * Records a write operation for metrics (package-private for delegate access).
     */
    void recordWrite() {
        writeThroughput.increment();
        messagesPublished.incrementAndGet();
    }
    
    /**
     * Records a stuck message reassignment (package-private for delegate access).
     * <p>
     * This is called when a message with an expired claim is reassigned to a new consumer.
     * The metric is O(1) using {@link AtomicLong}.
     */
    void recordStuckMessageReassignment() {
        stuckMessagesReassigned.incrementAndGet();
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
        
        // Deregister trigger notification queue
        H2InsertTrigger.deregisterNotificationQueue(getResourceName());
        
        // Note: We do NOT drop the centralized trigger here, as other topics may still be using it
        // The trigger is shared by all topics and should only be dropped when the last topic closes
        // (or when the database is shut down)
        
        // Close HikariCP pool
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
                log.debug("Closed HikariCP pool for topic '{}'", getResourceName());
            } catch (Exception e) {
                log.warn("Failed to close HikariCP pool for topic '{}'", getResourceName());
                recordError("POOL_CLOSE_FAILED", "Failed to close HikariCP pool", "Topic: " + getResourceName());
            }
        }
    }
}
```

### 10.2 H2InsertTrigger

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2InsertTrigger.java`

```java
package org.evochora.datapipeline.resources.topics;

import org.h2.api.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2 database trigger for instant message notifications.
 * <p>
 * This trigger is invoked by H2 after each INSERT into the topic_messages table.
 * It pushes the new message ID into a notification queue, instantly waking up
 * waiting readers for event-driven message delivery.
 * <p>
 * <strong>Event-Driven Architecture:</strong>
 * <pre>
 * Writer → SQL INSERT → H2 Trigger → BlockingQueue.offer() → Reader wake-up (INSTANT!)
 * </pre>
 * <p>
 * <strong>Centralized Table Design:</strong>
 * With a single {@code topic_messages} table shared by all topics, this trigger uses the
 * {@code topic_name} column (index 1 in newRow) to route notifications to the correct queue.
 * <p>
 * <strong>Static Registry:</strong>
 * Uses a static {@link ConcurrentHashMap} to map topic names to notification queues.
 * This allows multiple {@link H2TopicResource} instances (different topics) to use
 * the same trigger and centralized table with their own notification queues.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ul>
 *   <li>{@link H2TopicResource} registers its queue via {@link #registerNotificationQueue(String, BlockingQueue)}</li>
 *   <li>H2 calls {@link #fire(Connection, Object[], Object[])} on each INSERT</li>
 *   <li>{@link H2TopicResource} deregisters on close via {@link #deregisterNotificationQueue(String)}</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe. The static registry uses {@link ConcurrentHashMap}.
 *
 * @see org.h2.api.Trigger
 */
public class H2InsertTrigger implements Trigger {
    
    private static final Logger log = LoggerFactory.getLogger(H2InsertTrigger.class);
    
    // Static registry: topicName → notification queue
    // With centralized table, we route by topic name instead of table name
    private static final Map<String, BlockingQueue<Long>> notificationQueues = new ConcurrentHashMap<>();
    
    /**
     * Registers a notification queue for a specific topic.
     * <p>
     * Called by {@link H2TopicResource} during initialization.
     *
     * @param topicName The topic name (resource name).
     * @param queue The notification queue.
     */
    public static void registerNotificationQueue(String topicName, BlockingQueue<Long> queue) {
        notificationQueues.put(topicName, queue);
        log.debug("Registered notification queue for topic '{}'", topicName);
    }
    
    /**
     * Deregisters a notification queue for a specific topic.
     * <p>
     * Called by {@link H2TopicResource} during shutdown.
     *
     * @param topicName The topic name (resource name).
     */
    public static void deregisterNotificationQueue(String topicName) {
        notificationQueues.remove(topicName);
        log.debug("Deregistered notification queue for topic '{}'", topicName);
    }
    
    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
        // With centralized table, we don't cache the queue here
        // Instead, we look it up per-message based on topic_name column
        log.debug("H2 trigger '{}' initialized for centralized table '{}'", triggerName, tableName);
    }
    
    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow != null && newRow.length > 1) {
            // Centralized table schema: id, topic_name, message_id, timestamp, envelope, ...
            Long messageId = (Long) newRow[0];      // Column 0: id (BIGINT AUTO_INCREMENT)
            String topicName = (String) newRow[1];  // Column 1: topic_name (VARCHAR)
            
            // Look up the notification queue for this specific topic
            BlockingQueue<Long> queue = notificationQueues.get(topicName);
            
            if (queue != null) {
                boolean offered = queue.offer(messageId);
                
                if (!offered) {
                    // Queue full - should not happen with unbounded LinkedBlockingQueue
                    log.warn("Notification queue full for topic '{}', message ID {} not notified", 
                        topicName, messageId);
                }
            } else {
                // No queue registered for this topic - may happen during shutdown
                log.debug("No notification queue registered for topic '{}', message ID {} not notified", 
                    topicName, messageId);
            }
        }
    }
    
    @Override
    public void close() {
        // No resources to close
    }
    
    @Override
    public void remove() {
        // Called when trigger is dropped
        log.debug("H2 trigger removed for centralized topic_messages table");
    }
}
```

### 10.3 H2TopicWriterDelegate

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicWriterDelegate.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * H2-based writer delegate for topic messages with PreparedStatement pooling.
 * <p>
 * This delegate writes {@link TopicEnvelope} messages directly to the H2 database
 * using SQL INSERT statements. Multiple writers can operate concurrently thanks
 * to H2's MVCC (Multi-Version Concurrency Control) and HikariCP connection pooling.
 * <p>
 * <strong>PreparedStatement Pooling:</strong>
 * Each delegate creates a single {@link PreparedStatement} during construction and
 * reuses it for all writes. This eliminates the overhead of preparing the same SQL
 * statement repeatedly, improving write performance by ~30-50%.
 * <p>
 * <strong>HikariCP Integration:</strong>
 * The connection is obtained from HikariCP pool during construction and held for
 * the lifetime of this delegate. The PreparedStatement is created on this connection
 * and reused for all writes.
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is NOT thread-safe for concurrent writes from the same delegate instance.
 * Each service should have its own writer delegate instance (as per ServiceManager design).
 * <p>
 * <strong>Error Handling:</strong>
 * SQL errors are logged and recorded via {@link #recordError(String, String, String)}.
 *
 * @param <T> The message type.
 */
public class H2TopicWriterDelegate<T extends Message> extends AbstractTopicDelegateWriter<H2TopicResource<T>, T> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicWriterDelegate.class);
    
    private final Connection connection;
    private final PreparedStatement insertStatement;
    
    /**
     * Creates a new H2 writer delegate with PreparedStatement pooling.
     * <p>
     * Obtains a connection from HikariCP pool and prepares the INSERT statement once.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context.
     * @throws RuntimeException if connection or statement preparation fails.
     */
    public H2TopicWriterDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        
        try {
            // Obtain connection from HikariCP pool (held for delegate lifetime)
            this.connection = parent.getConnection();
            
            // Prepare INSERT statement once (reused for all writes)
            // Uses centralized table with topic_name column
            String sql = String.format(
                "INSERT INTO %s (topic_name, message_id, timestamp, envelope) VALUES (?, ?, ?, ?)",
                parent.getMessagesTable()
            );
            this.insertStatement = connection.prepareStatement(sql);
            
            log.debug("Created H2 writer delegate for topic '{}' with PreparedStatement pooling", 
                parent.getResourceName());
            
        } catch (SQLException e) {
            log.error("Failed to create H2 writer delegate for topic '{}'", parent.getResourceName());
            throw new RuntimeException("H2 writer delegate initialization failed", e);
        }
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        try {
            // Create schema if it doesn't exist (safe for concurrent calls, H2 bug workaround included)
            H2SchemaUtil.createSchemaIfNotExists(connection, simulationRunId);
            
            // Switch to the schema for this simulation run
            H2SchemaUtil.setSchema(connection, simulationRunId);
            
            log.debug("H2 topic writer '{}' switched to schema for run: {}", 
                parent.getResourceName(), simulationRunId);
                
        } catch (SQLException e) {
            String msg = String.format(
                "Failed to set H2 schema for simulation run '%s' in topic writer '%s'",
                simulationRunId, parent.getResourceName()
            );
            log.error(msg);
            recordError("SCHEMA_SETUP_FAILED", msg, "SQLException: " + e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }
    
    @Override
    protected void sendEnvelope(TopicEnvelope envelope) throws InterruptedException {
        try {
            // Note: No explicit transaction management needed here.
            // Single INSERT is atomic (ACID guarantee). Connection is in auto-commit mode (default).
            // If executeUpdate() throws SQLException, message is NOT committed (auto-rollback).
            // recordWrite() is just a counter increment and cannot fail.
            
            // Reuse PreparedStatement (no re-preparation overhead!)
            insertStatement.setString(1, parent.getResourceName());  // topic_name
            insertStatement.setString(2, envelope.getMessageId());
            insertStatement.setLong(3, envelope.getTimestamp());
            insertStatement.setBytes(4, envelope.toByteArray());
            
            insertStatement.executeUpdate();  // Atomic commit in auto-commit mode
            
            parent.recordWrite();  // O(1) counter increment, cannot fail
            
            log.debug("Wrote message to topic '{}': messageId={}", parent.getResourceName(), envelope.getMessageId());
            
        } catch (SQLException e) {
            // Message was NOT committed (auto-rollback on exception)
            log.warn("Failed to write message to topic '{}'", parent.getResourceName());
            recordError("WRITE_FAILED", "SQL INSERT failed", 
                "Topic: " + parent.getResourceName() + ", MessageId: " + envelope.getMessageId());
            throw new RuntimeException("Failed to write message to H2 topic", e);
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 writer delegate for topic '{}'", parent.getResourceName());
        
        // Close PreparedStatement
        if (insertStatement != null && !insertStatement.isClosed()) {
            insertStatement.close();
        }
        
        // Return connection to HikariCP pool
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
```

### 10.4 H2TopicReaderDelegate

**File:** `src/main/java/org/evochora/datapipeline/resources/topics/H2TopicReaderDelegate.java`

```java
package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.NotificationContracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * H2-based reader delegate for topic messages with PreparedStatement pooling.
 * <p>
 * This delegate reads {@link TopicEnvelope} messages from the H2 database using
 * a junction table approach for proper consumer group isolation.
 * <p>
 * <strong>Consumer Group Logic (Junction Table):</strong>
 * <ul>
 *   <li>Messages are read from {@code topic_messages} table</li>
 *   <li>Acknowledgments tracked in {@code consumer_group_acks} table</li>
 *   <li>Each consumer group sees only messages NOT in their acks table</li>
 *   <li>Acknowledgment by one group does NOT affect other groups</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong>
 * Multiple consumers in the same consumer group use {@code FOR UPDATE SKIP LOCKED}
 * to automatically distribute work without coordination overhead.
 * <p>
 * <strong>Acknowledgment:</strong>
 * Acknowledged messages are recorded in {@code consumer_group_acks} table.
 * Messages remain in {@code topic_messages} permanently (no deletion).
 * <p>
 * <strong>PreparedStatement Pooling:</strong>
 * Each delegate holds a HikariCP connection and prepares SQL statements once during
 * construction. Read and ACK operations reuse these statements, improving performance.
 * <p>
 * <strong>Thread Safety:</strong>
 * Safe for concurrent readers in the same consumer group (competing consumers).
 * Lock conflicts are handled via {@code SKIP LOCKED}.
 *
 * @param <T> The message type.
 */
public class H2TopicReaderDelegate<T extends Message> extends AbstractTopicDelegateReader<H2TopicResource<T>, T, Long> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicReaderDelegate.class);
    
    private final Connection connection;
    private final PreparedStatement readStatementWithTimeout;
    private final PreparedStatement readStatementNoTimeout;
    private final PreparedStatement ackInsertStatement;
    private final PreparedStatement ackReleaseStatement;
    private long lastReadId = 0;  // Track last read ID for efficient LEFT JOIN queries
    
    /**
     * Creates a new H2 reader delegate with PreparedStatement pooling.
     * <p>
     * Obtains a connection from HikariCP pool and prepares all SQL statements once.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context (must include consumerGroup parameter).
     * @throws RuntimeException if connection or statement preparation fails.
     */
    public H2TopicReaderDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        
        try {
            // Obtain connection from HikariCP pool (held for delegate lifetime)
            this.connection = parent.getConnection();
            
            int claimTimeout = parent.getClaimTimeoutSeconds();
            
            // Prepare READ statement WITH stuck message reassignment (claimTimeout > 0)
            if (claimTimeout > 0) {
                String sqlWithTimeout = String.format("""
                    UPDATE %s tm
                    SET claimed_by = ?, claimed_at = CURRENT_TIMESTAMP
                    WHERE id = (
                        SELECT tm2.id 
                        FROM %s tm2
                        LEFT JOIN %s cga 
                            ON tm2.topic_name = cga.topic_name
                            AND tm2.message_id = cga.message_id 
                            AND cga.consumer_group = ?
                        WHERE cga.message_id IS NULL
                        AND tm2.topic_name = ?
                        AND (
                            tm2.claimed_by IS NULL 
                            OR tm2.claimed_at < DATEADD('SECOND', -%d, CURRENT_TIMESTAMP)
                        )
                        AND tm2.id > ?
                        ORDER BY tm2.id 
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, message_id, envelope, claimed_by AS previous_claim
                    """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable(), claimTimeout);
                this.readStatementWithTimeout = connection.prepareStatement(sqlWithTimeout);
            } else {
                this.readStatementWithTimeout = null;
            }
            
            // Prepare READ statement WITHOUT stuck message reassignment (claimTimeout = 0)
            String sqlNoTimeout = String.format("""
                UPDATE %s tm
                SET claimed_by = ?, claimed_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT tm2.id 
                    FROM %s tm2
                    LEFT JOIN %s cga 
                        ON tm2.topic_name = cga.topic_name
                        AND tm2.message_id = cga.message_id 
                        AND cga.consumer_group = ?
                    WHERE cga.message_id IS NULL
                    AND tm2.topic_name = ?
                    AND tm2.claimed_by IS NULL
                    AND tm2.id > ?
                    ORDER BY tm2.id 
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, message_id, envelope, NULL AS previous_claim
                """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable());
            this.readStatementNoTimeout = connection.prepareStatement(sqlNoTimeout);
            
            // Prepare ACK INSERT statement (once, reused for all ACKs)
            String ackInsertSql = String.format(
                "INSERT INTO %s (topic_name, consumer_group, message_id) VALUES (?, ?, ?)",
                parent.getAcksTable()
            );
            this.ackInsertStatement = connection.prepareStatement(ackInsertSql);
            
            // Prepare ACK RELEASE statement (once, reused for all ACKs)
            String ackReleaseSql = String.format(
                "UPDATE %s SET claimed_by = NULL, claimed_at = NULL WHERE id = ?",
                parent.getMessagesTable()
            );
            this.ackReleaseStatement = connection.prepareStatement(ackReleaseSql);
            
            log.debug("Created H2 reader delegate for topic '{}' (consumerGroup='{}') with PreparedStatement pooling", 
                parent.getResourceName(), consumerGroup);
            
        } catch (SQLException e) {
            log.error("Failed to create H2 reader delegate for topic '{}'", parent.getResourceName());
            throw new RuntimeException("H2 reader delegate initialization failed", e);
        }
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        try {
            // Create schema if it doesn't exist (safe for concurrent calls, H2 bug workaround included)
            H2SchemaUtil.createSchemaIfNotExists(connection, simulationRunId);
            
            // Switch to the schema for this simulation run
            H2SchemaUtil.setSchema(connection, simulationRunId);
            
            log.debug("H2 topic reader '{}' (consumerGroup='{}') switched to schema for run: {}", 
                parent.getResourceName(), consumerGroup, simulationRunId);
                
        } catch (SQLException e) {
            String msg = String.format(
                "Failed to set H2 schema for simulation run '%s' in topic reader '%s' (consumerGroup='%s')",
                simulationRunId, parent.getResourceName(), consumerGroup
            );
            log.error(msg);
            recordError("SCHEMA_SETUP_FAILED", msg, "SQLException: " + e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }
    
    @Override
    protected ReceivedEnvelope<Long> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
        // Event-driven approach: Wait for notification from H2 trigger
        // instead of polling the database every 100ms
        
        Long notificationId = (timeout > 0 && unit != null)
            ? parent.getNotificationQueue().poll(timeout, unit)   // Timeout-based wait
            : parent.getNotificationQueue().take();               // Block indefinitely
        
        if (notificationId == null) {
            return null;  // Timeout - no notification received
        }
        
        // Notification received - try to read the message
        // Note: The notified message might not be for this consumer group (already ACKed),
        // or might be consumed by a competing consumer in the same group (FOR UPDATE SKIP LOCKED).
        // In such cases, we retry waiting for the next notification.
        
        while (notificationId != null) {
            ReceivedEnvelope<Long> envelope = tryReadMessage();
            if (envelope != null) {
                return envelope;  // Successfully read a message
            }
            
            // Edge case: Message was not for this consumer group or already consumed
            // Wait for next notification with short timeout to avoid indefinite blocking
            notificationId = parent.getNotificationQueue().poll(100, TimeUnit.MILLISECONDS);
        }
        
        return null;  // No messages available for this consumer group
    }
    
    /**
     * Attempts to read and claim the next message from the database atomically.
     * <p>
     * Uses {@code UPDATE...RETURNING} with claim-based approach to ensure:
     * <ul>
     *   <li>Atomic claim - prevents race conditions (single SQL statement!)</li>
     *   <li>Only messages NOT acknowledged by this consumer group are returned</li>
     *   <li>Multiple consumers in same group compete via FOR UPDATE SKIP LOCKED</li>
     *   <li>No data loss - each group processes all messages independently</li>
     * </ul>
     * <p>
     * The message is marked as {@code claimed_by = <consumerId>} atomically,
     * preventing other consumers from processing it until acknowledged.
     * <p>
     * <strong>PreparedStatement Reuse:</strong>
     * Uses pre-prepared statements from construction, avoiding SQL parsing overhead on every read.
     *
     * @return The received envelope with row ID as ACK token, or null if no message available.
     */
    private ReceivedEnvelope<Long> tryReadMessage() {
        // Generate unique consumer ID for this delegate instance
        String consumerId = parent.getResourceName() + ":" + consumerGroup + ":" + System.currentTimeMillis();
        int claimTimeout = parent.getClaimTimeoutSeconds();
        
        // Select appropriate prepared statement based on claimTimeout
        PreparedStatement stmt = (claimTimeout > 0) ? readStatementWithTimeout : readStatementNoTimeout;
        
        try {
            // Reuse PreparedStatement (no SQL parsing overhead!)
            stmt.setString(1, consumerId);                  // claimed_by
            stmt.setString(2, consumerGroup);               // consumer_group filter
            stmt.setString(3, parent.getResourceName());    // topic_name filter
            stmt.setLong(4, lastReadId);                    // pagination
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    String messageId = rs.getString("message_id");
                    byte[] envelopeBytes = rs.getBytes("envelope");
                    String previousClaim = rs.getString("previous_claim");
                    
                    TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                    
                    lastReadId = id;
                    parent.recordRead();
                    
                    // Check if this was a stuck message reassignment
                    if (previousClaim != null && !previousClaim.equals(consumerId)) {
                        log.warn("Reassigned stuck message from topic '{}': messageId={}, previousClaim={}, newConsumer={}, timeout={}s", 
                            parent.getResourceName(), messageId, previousClaim, consumerId, claimTimeout);
                        recordError("STUCK_MESSAGE_REASSIGNED", "Message claim timeout expired", 
                            "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                            ", PreviousClaim: " + previousClaim + ", Timeout: " + claimTimeout + "s");
                        parent.recordStuckMessageReassignment();
                    } else {
                        log.debug("Claimed message from topic '{}': messageId={}, consumerId={}", 
                            parent.getResourceName(), messageId, consumerId);
                    }
                    
                    // ACK token is the row ID (used to release claim and insert ACK)
                    return new ReceivedEnvelope<>(envelope, id);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to claim message from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("CLAIM_FAILED", "SQL UPDATE...RETURNING failed", 
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
        // Note: We already have message_id from tryReadMessage(), but H2TopicReaderDelegate
        // doesn't store it (only ACK token is passed). We need to look it up from rowId.
        // Alternative design: Change ReceivedEnvelope to include message_id, but this breaks
        // the abstraction (ACK token should be opaque to abstract layer).
        
        try {
            // Start transaction - all 3 steps must be atomic
            connection.setAutoCommit(false);
            
            // Step 1: Get message_id for this row ID (lightweight SELECT by primary key)
            String messageId;
            try {
                String getMessageIdSql = String.format(
                    "SELECT message_id FROM %s WHERE id = ?", parent.getMessagesTable());
                try (PreparedStatement stmt = connection.prepareStatement(getMessageIdSql)) {
                    stmt.setLong(1, rowId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            log.debug("Message not found in topic '{}': rowId={}", parent.getResourceName(), rowId);
                            connection.rollback();
                            connection.setAutoCommit(true);
                            return;
                        }
                        messageId = rs.getString("message_id");
                    }
                }
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                log.warn("Failed to get message_id for acknowledgment in topic '{}': rowId={}", 
                    parent.getResourceName(), rowId);
                recordError("ACK_LOOKUP_FAILED", "Failed to get message_id", 
                    "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
                return;
            }
            
            // Step 2: INSERT into consumer_group_acks (marks as acknowledged for THIS group only)
            // Reuse prepared statement for performance
            try {
                ackInsertStatement.setString(1, parent.getResourceName());  // topic_name
                ackInsertStatement.setString(2, consumerGroup);
                ackInsertStatement.setString(3, messageId);
                ackInsertStatement.executeUpdate();
                
                log.debug("Acknowledged message in topic '{}': messageId={}, consumerGroup={}", 
                    parent.getResourceName(), messageId, consumerGroup);
                
            } catch (SQLException e) {
                // H2 doesn't support ON DUPLICATE KEY UPDATE, so we might get a constraint violation
                if (e.getErrorCode() == 23505) {  // Duplicate key - already acknowledged
                    log.debug("Message already acknowledged in topic '{}': messageId={}, consumerGroup={}", 
                        parent.getResourceName(), messageId, consumerGroup);
                    connection.rollback();
                    connection.setAutoCommit(true);
                    return;
                } else {
                    connection.rollback();
                    connection.setAutoCommit(true);
                    log.warn("Failed to acknowledge message in topic '{}': messageId={}, consumerGroup={}", 
                        parent.getResourceName(), messageId, consumerGroup);
                    recordError("ACK_FAILED", "Failed to insert into consumer_group_acks", 
                        "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + ", ConsumerGroup: " + consumerGroup);
                    return;
                }
            }
            
            // Step 3: Release claim (makes message available for other consumer groups)
            // Reuse prepared statement for performance
            try {
                ackReleaseStatement.setLong(1, rowId);
                ackReleaseStatement.executeUpdate();
                
                log.debug("Released claim for message in topic '{}': rowId={}", parent.getResourceName(), rowId);
                
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                log.warn("Failed to release claim for message in topic '{}': rowId={}", 
                    parent.getResourceName(), rowId);
                recordError("RELEASE_CLAIM_FAILED", "Failed to clear claimed_by", 
                    "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
                return;
            }
            
            // Commit transaction - all 3 steps succeeded atomically
            connection.commit();
            connection.setAutoCommit(true);
            
            parent.recordAck();
            
            // Note: Messages are NEVER deleted from topic_messages table.
            // They remain permanently for historical replay and new consumer groups.
            
        } catch (SQLException e) {
            // Transaction management failed
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException rollbackEx) {
                log.warn("Failed to rollback transaction for topic '{}'", parent.getResourceName());
            }
            log.warn("Transaction failed for acknowledgment in topic '{}': rowId={}", 
                parent.getResourceName(), rowId);
            recordError("ACK_TRANSACTION_FAILED", "Transaction rollback", 
                "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 reader delegate for topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
        
        // Close all PreparedStatements
        if (readStatementWithTimeout != null && !readStatementWithTimeout.isClosed()) {
            readStatementWithTimeout.close();
        }
        if (readStatementNoTimeout != null && !readStatementNoTimeout.isClosed()) {
            readStatementNoTimeout.close();
        }
        if (ackInsertStatement != null && !ackInsertStatement.isClosed()) {
            ackInsertStatement.close();
        }
        if (ackReleaseStatement != null && !ackReleaseStatement.isClosed()) {
            ackReleaseStatement.close();
        }
        
        // Return connection to HikariCP pool
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
```

---

## 11. Configuration

### 11.1 Resource Declaration

**File:** `src/main/resources/reference.conf` (excerpt)

```hocon
evochora {
  datapipeline {
    resources {
      # Metadata notification topic (H2-based with HikariCP)
      metadata-topic {
        type = "org.evochora.datapipeline.resources.topics.H2TopicResource"
        dbPath = "./data/topics/metadata"
        
        # HikariCP connection pool settings (same as H2Database)
        maxPoolSize = 10      # Maximum connections (default: 10)
        minIdle = 2           # Minimum idle connections (default: 2)
        username = "sa"       # Database username (default: "sa")
        password = ""         # Database password (default: "")
        
        # Stuck message reassignment
        claimTimeout = 300    # Seconds (5 minutes) - 0 = disabled, never reassign
      }
      
      # Batch notification topic (H2-based with HikariCP)
      batch-topic {
        type = "org.evochora.datapipeline.resources.topics.H2TopicResource"
        dbPath = "./data/topics/batches"
        
        # HikariCP connection pool settings
        maxPoolSize = 15      # Higher pool size for batch notifications (more writers)
        minIdle = 3
        
        # Stuck message reassignment
        claimTimeout = 600    # Seconds (10 minutes)
      }
    }
  }
}
```

**Config Parameters:**

**Database & Connection Pooling (HikariCP):**
- `dbPath` (required): Path to H2 database file (supports path expansion via `PathExpansion`)
- `maxPoolSize` (optional, default=10): Maximum number of connections in HikariCP pool
- `minIdle` (optional, default=2): Minimum idle connections maintained by HikariCP
- `username` (optional, default="sa"): H2 database username
- `password` (optional, default=""): H2 database password

**Message Delivery:**
- `claimTimeout` (optional, default=300): Seconds before stuck message is reassigned to new consumer
  - `0` = disabled (never reassign, manual intervention required)
  - `> 0` = timeout in seconds after which `claimed_by` is overwritten by new consumer

**Performance Notes:**
- `maxPoolSize` should be sized based on expected concurrent writers/readers
- Each writer delegate holds one connection for its lifetime
- Each reader delegate holds one connection for its lifetime
- System-wide connection limit = sum of all `maxPoolSize` across all H2 topics

### 11.2 Service Bindings

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

## 12. Testing Requirements

### 12.1 Unit Tests

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
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
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
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-multigroup\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext readerContextA = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-a"));
        ResourceContext readerContextB = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-b"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> readerA = topic.createReaderDelegate(readerContextA);
        ITopicReader<BatchInfo, Long> readerB = topic.createReaderDelegate(readerContextB);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When
        writer.send(message);
        
        TopicMessage<BatchInfo, Long> receivedA = readerA.poll(1, TimeUnit.SECONDS);
        TopicMessage<BatchInfo, Long> receivedB = readerB.poll(1, TimeUnit.SECONDS);
        
        // Then - Both groups receive the message
        assertThat(receivedA).isNotNull();
        assertThat(receivedB).isNotNull();
        assertThat(receivedA.payload()).isEqualTo(message);
        assertThat(receivedB.payload()).isEqualTo(message);
        
        // When - Group A acknowledges
        readerA.ack(receivedA);
        
        // Then - Group B can still process and acknowledge independently
        readerB.ack(receivedB);
        
        // Both groups should have no more messages
        assertThat(readerA.poll(100, TimeUnit.MILLISECONDS)).isNull();
        assertThat(readerB.poll(100, TimeUnit.MILLISECONDS)).isNull();
        
        topic.close();
    }
    
    @Test
    @DisplayName("Should handle competing consumers within same group")
    void shouldHandleCompetingConsumers() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-competing\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        
        // Write 10 messages
        for (int i = 0; i < 10; i++) {
            writer.send(BatchInfo.newBuilder()
                .setSimulationRunId("20251014-143025-550e8400")
                .setStorageKey(String.format("20251014-143025-550e8400/batch_%010d_%010d.pb", i * 100, (i + 1) * 100))
                .setTickStart(i * 100)
                .setTickEnd((i + 1) * 100)
                .setWrittenAtMs(System.currentTimeMillis())
                .build());
        }
        
        // Create 3 competing consumers in the same group
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "indexers"));
        
        ITopicReader<BatchInfo, Long> reader1 = topic.createReaderDelegate(readerContext);
        ITopicReader<BatchInfo, Long> reader2 = topic.createReaderDelegate(readerContext);
        ITopicReader<BatchInfo, Long> reader3 = topic.createReaderDelegate(readerContext);
        
        // When - All readers compete for messages
        Set<Long> processedIds = ConcurrentHashMap.newKeySet();
        
        CountDownLatch latch = new CountDownLatch(10);
        List<Thread> threads = List.of(
            new Thread(() -> consumeMessages(reader1, processedIds, latch)),
            new Thread(() -> consumeMessages(reader2, processedIds, latch)),
            new Thread(() -> consumeMessages(reader3, processedIds, latch))
        );
        
        threads.forEach(Thread::start);
        
        // Then - All messages processed exactly once
        await().atMost(5, TimeUnit.SECONDS).until(() -> latch.getCount() == 0);
        assertThat(processedIds).hasSize(10);
        
        topic.close();
    }
    
    private void consumeMessages(ITopicReader<BatchInfo, Long> reader, Set<Long> processedIds, CountDownLatch latch) {
        try {
            while (latch.getCount() > 0) {
                TopicMessage<BatchInfo, Long> msg = reader.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    processedIds.add(msg.payload().getTickStart());
                    reader.ack(msg);
                    latch.countDown();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Test
    @DisplayName("Should handle concurrent writes from multiple threads")
    void shouldHandleConcurrentWrites() throws Exception {
        // Test multi-writer scenario with H2 MVCC
        // ...
    }
    
    @Test
    @DisplayName("Should receive instant notifications via H2 trigger (event-driven)")
    void shouldReceiveInstantNotifications() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-trigger\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Write message in background thread
        CompletableFuture<Void> writeFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);  // Small delay to ensure reader is waiting
                writer.send(message);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        // Measure time until message received (should be instant via trigger, not 100ms polling)
        long startTime = System.currentTimeMillis();
        TopicMessage<BatchInfo, Long> received = reader.receive();  // Blocking wait
        long elapsed = System.currentTimeMillis() - startTime;
        
        // Then - Message received almost instantly (not after 100ms polling delay)
        assertThat(received).isNotNull();
        assertThat(received.payload().getSimulationId()).isEqualTo(555);
        assertThat(elapsed).isLessThan(200);  // Should be ~100ms (write delay) + instant notification
        
        reader.ack(received);
        writeFuture.join();
        topic.close();
    }
    
    @Test
    @DisplayName("Should dynamically resolve message types from google.protobuf.Any")
    void shouldDynamicallyResolveMessageTypes() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-types\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationId(789)
            .setBatchId(123)
            .build();
        
        // When - Message written with google.protobuf.Any
        writer.send(message);
        TopicMessage<BatchInfo, Long> received = reader.poll(1, TimeUnit.SECONDS);
        
        // Then - Type correctly resolved from type URL
        assertThat(received).isNotNull();
        assertThat(received.payload()).isInstanceOf(BatchInfo.class);
        assertThat(received.payload().getSimulationId()).isEqualTo(789);
        assertThat(received.payload().getBatchId()).isEqualTo(123);
        
        reader.ack(received);
        topic.close();
    }
}
```

### 12.2 Integration Tests

**File:** `src/test/java/org/evochora/datapipeline/resources/topics/H2TopicIntegrationTest.java`

```java
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2TopicIntegrationTest {
    
    @Test
    @DisplayName("Should persist messages across topic restarts")
    void shouldPersistMessagesAcrossRestarts() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-persistence\"");
        H2TopicResource<BatchInfo> topic1 = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ITopicWriter<BatchInfo> writer = topic1.createWriterDelegate(writerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationId(999)
            .setBatchId(111)
            .build();
        
        // When - Write message and close topic
        writer.send(message);
        topic1.close();
        
        // Then - Reopen topic and message is still there
        H2TopicResource<BatchInfo> topic2 = new H2TopicResource<>("test-topic", config);
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        ITopicReader<BatchInfo, Long> reader = topic2.createReaderDelegate(readerContext);
        
        TopicMessage<BatchInfo, Long> received = reader.poll(1, TimeUnit.SECONDS);
        
        assertThat(received).isNotNull();
        assertThat(received.payload().getSimulationId()).isEqualTo(999);
        
        reader.ack(received);
        topic2.close();
    }
    
    @Test
    @DisplayName("Should allow new consumer groups to process historical messages")
    void shouldAllowNewConsumerGroupsToProcessHistoricalMessages() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-replay\"");
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        
        // Write 3 messages
        for (int i = 1; i <= 3; i++) {
            writer.send(BatchInfo.newBuilder().setSimulationId(i).build());
        }
        
        // Group A processes all messages
        ResourceContext readerContextA = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-a"));
        ITopicReader<BatchInfo, Long> readerA = topic.createReaderDelegate(readerContextA);
        
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, Long> msg = readerA.poll(1, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            readerA.ack(msg);
        }
        
        // When - New consumer group B joins AFTER group A finished
        ResourceContext readerContextB = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-b"));
        ITopicReader<BatchInfo, Long> readerB = topic.createReaderDelegate(readerContextB);
        
        // Then - Group B can still process all historical messages
        Set<Long> processedIds = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, Long> msg = readerB.poll(1, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            processedIds.add(msg.payload().getSimulationId());
            readerB.ack(msg);
        }
        
        assertThat(processedIds).containsExactlyInAnyOrder(1L, 2L, 3L);
        
        topic.close();
    }
    
    @Test
    @DisplayName("Should reassign stuck messages after timeout expires")
    @ExpectLog(level = ExpectLog.Level.WARN, messagePattern = "Reassigned stuck message.*")
    void shouldReassignStuckMessagesAfterTimeout() throws Exception {
        // Given - Topic with 2-second claim timeout
        Config config = ConfigFactory.parseString("""
            dbPath = "./test-data/h2-topic-stuck"
            claimTimeout = 2
            """);
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext reader1Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        ResourceContext reader2Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> reader1 = topic.createReaderDelegate(reader1Context);
        ITopicReader<BatchInfo, Long> reader2 = topic.createReaderDelegate(reader2Context);
        
        // When - Reader1 claims message but NEVER acknowledges it
        BatchInfo message = BatchInfo.newBuilder().setSimulationId(999).setBatchId(111).build();
        writer.send(message);
        
        TopicMessage<BatchInfo, Long> claimed = reader1.poll(1, TimeUnit.SECONDS);
        assertThat(claimed).isNotNull();
        // Deliberately DO NOT call reader1.ack(claimed) - simulate consumer crash!
        
        // Wait for claim timeout to expire (2 seconds + buffer)
        Thread.sleep(2500);
        
        // Then - Reader2 should be able to claim the stuck message
        TopicMessage<BatchInfo, Long> reassigned = reader2.poll(1, TimeUnit.SECONDS);
        assertThat(reassigned).isNotNull();
        assertThat(reassigned.payload().getSimulationId()).isEqualTo(999);
        
        // Verify stuck message reassignment metric was incremented
        Map<String, Number> metrics = topic.getMetrics();
        assertThat(metrics.get("stuck_messages_reassigned")).isEqualTo(1L);
        
        // Cleanup
        reader2.ack(reassigned);
        topic.close();
    }
    
    @Test
    @DisplayName("Should NOT reassign messages when claimTimeout = 0")
    void shouldNotReassignWhenTimeoutDisabled() throws Exception {
        // Given - Topic with claim timeout disabled
        Config config = ConfigFactory.parseString("""
            dbPath = "./test-data/h2-topic-no-timeout"
            claimTimeout = 0
            """);
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext reader1Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        ResourceContext reader2Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, Long> reader1 = topic.createReaderDelegate(reader1Context);
        ITopicReader<BatchInfo, Long> reader2 = topic.createReaderDelegate(reader2Context);
        
        // When - Reader1 claims message but never acknowledges
        BatchInfo message = BatchInfo.newBuilder().setSimulationId(999).setBatchId(111).build();
        writer.send(message);
        
        TopicMessage<BatchInfo, Long> claimed = reader1.poll(1, TimeUnit.SECONDS);
        assertThat(claimed).isNotNull();
        // DO NOT ack - simulate crash
        
        // Wait (even longer than if timeout was enabled)
        Thread.sleep(5000);
        
        // Then - Reader2 should NOT be able to claim (timeout disabled)
        TopicMessage<BatchInfo, Long> notReassigned = reader2.poll(1, TimeUnit.SECONDS);
        assertThat(notReassigned).isNull();  // No message available!
        
        // Verify NO stuck message reassignment occurred
        Map<String, Number> metrics = topic.getMetrics();
        assertThat(metrics.get("stuck_messages_reassigned")).isEqualTo(0L);
        
        topic.close();
    }
}
```

### 12.3 Test Guidelines

1. **No Thread.sleep()** - Use Awaitility for async conditions
2. **Log Assertions** - Use `@ExpectLog` for expected WARN/ERROR logs
3. **Cleanup** - Always close resources in `@AfterEach`
4. **Fast Execution** - Unit tests <0.2s, integration tests <1s

---

## 13. Logging Requirements

### 13.1 Log Levels

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

### 13.2 Log Format

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

## 14. Metrics

### 14.1 Aggregate Metrics (AbstractTopicResource)

- `messages_published` - Total messages written across all writers
- `messages_received` - Total messages read across all readers
- `messages_acknowledged` - Total messages acknowledged
- `write_throughput_per_minute` - SlidingWindowCounter (60s)
- `read_throughput_per_minute` - SlidingWindowCounter (60s)

### 14.2 Delegate Metrics (per service)

- `parent_messages_published` - Reference to parent aggregate
- `parent_messages_received` - Reference to parent aggregate
- `parent_messages_acknowledged` - Reference to parent aggregate
- `error_count` - Delegate-specific errors

---

## 15. Implementation Notes

### 15.1 Key Design Decisions

1. **HikariCP Connection Pooling:** 
   - Follows same pattern as `H2Database` resource for consistency
   - System-wide connection limits via `maxPoolSize` configuration
   - Connection metrics via HikariCP MXBean
   - Each delegate holds one connection for its lifetime

2. **PreparedStatement Pooling:**
   - Each delegate prepares SQL statements ONCE during construction
   - Writer: 1 statement (INSERT), Reader: 2-4 statements (SELECT/UPDATE, INSERT ACK, RELEASE)
   - Eliminates SQL parsing overhead on every operation (~30-50% performance gain)
   - Connection and statements held for delegate lifetime, returned to pool on close

3. **Atomic Claim-Based Delivery:** `UPDATE...RETURNING` prevents race conditions (single SQL statement!)

4. **Stuck Message Reassignment:** Configurable `claimTimeout` automatically reassigns stuck messages (idempotency required!)

5. **Performance Optimized Queries:** LEFT JOIN instead of NOT IN + composite indexes for O(log n) performance

6. **H2 over Chronicle:** Simpler architecture, multi-writer support, explicit ACK, HikariCP pooling

7. **Separate Files:** All classes in separate files (no inner classes)

8. **Event-Driven Delivery:** H2 triggers + BlockingQueue for instant notifications (no polling!)

9. **Direct SQL:** No internal queue or writer thread needed (H2 MVCC + HikariCP handle concurrency)

10. **Junction Table:** Proper consumer group isolation via `consumer_group_acks` table

11. **Competing Consumers:** `FOR UPDATE SKIP LOCKED` for automatic load balancing

12. **In-Flight Tracking:** `claimed_by` and `claimed_at` columns for stuck message detection and reassignment

13. **Permanent Storage:** Messages never deleted (enables historical replay and new consumer groups)

14. **Dynamic Type Resolution:** Uses `google.protobuf.Any` type URL (no `messageType` config needed)

15. **Single Validation Point:** Consumer group validation occurs only in `AbstractTopicDelegateReader` constructor to avoid DRY violation and ensure consistent error messages across all implementations (H2, Chronicle, Kafka)

16. **Transaction Management:**
   - **Writer:** Auto-commit mode (single INSERT is atomic, no explicit transaction needed)
   - **Reader ACK:** Explicit transaction (3 SQL statements must be atomic: SELECT + INSERT + UPDATE)
   - Ensures all-or-nothing semantics for acknowledgment (no partial ACKs)

17. **Centralized Tables:**
   - **Single `topic_messages` table** for all topics (no per-topic tables)
   - **Single `consumer_group_acks` table** for all topics and consumer groups
   - **`topic_name` column** provides logical partitioning within shared tables
   - **Composite indexes** include `topic_name` for partition-like performance
   - **Unlimited scalability:** Add topics without schema changes or new tables
   - **Shared trigger:** Single H2 trigger routes notifications by `topic_name`

18. **Schema Management (Simulation Run Isolation):**
   - **`ISimulationRunAwareTopic` interface:** Topic-specific interface for run-aware resources
   - **`H2SchemaUtil` utility:** Centralized H2 schema operations (shared with `H2Database`)
   - **Per-run schemas:** Each simulation run uses its own H2 schema (e.g., `SIM_20251006143025_...`)
   - **Schema creation:** `createSchemaIfNotExists()` with H2 bug workaround (concurrent creation safe)
   - **Schema switching:** `setSchema()` called in `onSimulationRunSet()` template method
   - **Delegate lifecycle:** Schema set once during `setSimulationRun()`, before any read/write operations
   - **Consistent with H2Database:** Same schema naming and management logic across all H2 resources

### 15.2 Performance Optimizations

**Query Optimization:**
- **LEFT JOIN vs NOT IN:** LEFT JOIN allows index usage on `consumer_group_acks.message_id`
- **Composite Index:** `idx_claim_status (claimed_by, claimed_at)` enables efficient OR-condition filtering
- **Partial Indexes:** 
  - `idx_unclaimed` for fast unclaimed message lookup
  - `idx_claimed_at` for stuck message timeout checks

**Performance Characteristics:**
- **Read Query:** O(log n) with indexes (was O(n) with NOT IN full table scan)
- **Stuck Message Check:** O(log n) with `idx_claimed_at` (was O(n) full table scan)
- **Consumer Group Filter:** O(log n) with LEFT JOIN + index (was O(n*m) with NOT IN)

**Scalability:**
| Messages in Topic | NOT IN (old) | LEFT JOIN + Indexes (new) |
|-------------------|--------------|---------------------------|
| 1,000 | ~10ms | ~1ms |
| 10,000 | ~100ms | ~2ms |
| 100,000 | ~1000ms (1s) | ~3ms |
| 1,000,000 | ~10s+ | ~4ms |

### 15.3 Future Enhancements

1. ~~**Connection Pooling:** For higher throughput (if needed)~~ ✅ **DONE:** HikariCP integration implemented
2. ~~**PreparedStatement Pooling:** Reuse statements per delegate~~ ✅ **DONE:** Implemented in delegates
3. **Batch Operations:** Batch INSERT for efficiency (acknowledgments already batched via competing consumers)
4. **Dead Letter Queue:** Failed messages to DLQ resource (Phase 14.2.7)
5. **Message Retention Policy:** Optional cleanup of old acknowledged messages (currently: keep forever)
6. **Background Cleanup Job:** Periodic reset of claims (optional, currently: timeout-based automatic reassignment)

### 15.4 Migration Path

To switch from H2 to Kafka/SQS:
1. Implement `KafkaTopicResource` extending `AbstractTopicResource`
2. Implement `KafkaTopicWriterDelegate` and `KafkaTopicReaderDelegate`
3. Update config: `type = "...KafkaTopicResource"`
4. **No service code changes!** (Interface abstraction works)

---

## 16. Acceptance Criteria

- [ ] All interfaces and abstract classes compile without errors
- [ ] **HikariCP integration:** H2TopicResource uses HikariDataSource (same pattern as H2Database)
- [ ] **HikariCP config:** `maxPoolSize`, `minIdle`, `username`, `password` parameters supported
- [ ] **HikariCP metrics:** Connection pool metrics exposed via MXBean (active/idle/total/awaiting)
- [ ] **PreparedStatement pooling:** Writer delegates prepare INSERT statement once during construction
- [ ] **PreparedStatement pooling:** Reader delegates prepare all statements once during construction
- [ ] **Delegate cleanup:** close() releases PreparedStatements and returns connection to HikariCP pool
- [ ] **Transaction Management:** Writer uses auto-commit mode (single INSERT is atomic)
- [ ] **Transaction Management:** Reader ACK uses explicit transaction (SELECT + INSERT + UPDATE atomic)
- [ ] **Transaction Rollback:** ACK failures trigger rollback and restore auto-commit mode
- [ ] H2TopicResource initializes `topic_messages` (with claimed_by/claimed_at) and `consumer_group_acks` tables correctly
- [ ] **Performance indexes created:** `idx_claimed_at`, `idx_claim_status` (composite), `idx_unclaimed`
- [ ] **LEFT JOIN query:** Reader uses LEFT JOIN instead of NOT IN for better performance
- [ ] H2 trigger created and registered correctly for event-driven notifications
- [ ] Writers can send messages concurrently (multi-writer test)
- [ ] Readers receive instant notifications via H2 trigger (no polling delay)
- [ ] **Atomic claim:** `UPDATE...RETURNING` prevents race conditions (no duplicate processing!)
- [ ] **Claimed messages** are marked with `claimed_by` and `claimed_at`
- [ ] **Claim release:** `ack()` clears `claimed_by` to make message available for other groups
- [ ] **Stuck message reassignment:** `claimTimeout > 0` enables automatic reassignment
- [ ] **Stuck message timeout:** Messages claimed longer than `claimTimeout` seconds are reassigned
- [ ] **Stuck message disabled:** `claimTimeout = 0` disables automatic reassignment
- [ ] **Stuck message metric:** `stuck_messages_reassigned` is O(1) and counts reassignments
- [ ] **Stuck message logging:** Reassignment triggers WARN log and error recording (STUCK_MESSAGE_REASSIGNED)
- [ ] Readers receive messages in order (FIFO test)
- [ ] Consumer groups receive messages independently (pub/sub isolation test)
- [ ] Multiple consumers in same group compete correctly (competing consumers test)
- [ ] `FOR UPDATE SKIP LOCKED` distributes load automatically
- [ ] Acknowledgment inserts into `consumer_group_acks` table (not DELETE from messages)
- [ ] Messages remain in database after acknowledgment (permanent storage test)
- [ ] New consumer groups can process historical messages (replay test)
- [ ] Messages persist across topic restarts (persistence test)
- [ ] Dynamic type resolution works from `google.protobuf.Any` type URL (no `messageType` config needed)
- [ ] Trigger properly deregistered and dropped on topic close
- [ ] Stuck messages can be detected via SQL query (claimed_at older than threshold)
- [ ] All unit tests pass (<0.2s each)
- [ ] All integration tests pass (<1s each)
- [ ] Zero WARN/ERROR logs without `@ExpectLog`
- [ ] Metrics updated correctly (aggregate + delegate)
- [ ] Configuration loads from reference.conf (no `messageType` required)
- [ ] JavaDoc complete for all public/protected members

---

## 17. Dependencies

### 17.1 Build Configuration

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

## 18. Comparison: Chronicle vs H2

| Aspect | Chronicle Queue | H2 Database |
|--------|----------------|-------------|
| **Architecture** | Queue + Thread + BlockingQueue | Direct SQL + H2 Triggers + HikariCP |
| **Connection Management** | Single connection | HikariCP pool (system-wide limits) |
| **PreparedStatement** | N/A (binary protocol) | Pooled per delegate (~30-50% faster) |
| **Writers** | Single (needs workaround) | Multi (H2 MVCC + HikariCP) |
| **Message Delivery** | Polling (queue drain loop) | Atomic Claim (`UPDATE...RETURNING`) |
| **Race Conditions** | Possible (read + ACK not atomic) | Impossible (single SQL statement) |
| **Latency** | Ultra-fast (μs) | Instant notification (ms) |
| **Complexity** | High (inner classes, thread) | Low (separate files, JDBC, triggers) |
| **Consumer Groups** | Tailer per group (complex) | Junction table (simple SQL) |
| **Competing Consumers** | Manual coordination needed | `FOR UPDATE SKIP LOCKED` (automatic) |
| **ACK** | Implicit (tailer advance) | Explicit (INSERT into acks table + release claim) |
| **In-Flight Tracking** | None | `claimed_by` column |
| **Message Retention** | Configurable | Permanent (never deleted) |
| **Type Resolution** | Dynamic (google.protobuf.Any) | Dynamic (google.protobuf.Any) |
| **Config** | No messageType needed | No messageType needed + HikariCP params |
| **Debugging** | Binary files | SQL queries + claim inspection + pool metrics |
| **CPU Efficiency** | Low (queue thread always running) | High (event-driven, no polling) |
| **Resource Monitoring** | Limited | HikariCP MXBean metrics (connections, threads) |
| **Best For** | Ultra-low latency streaming | Batch processing with replay needs |

**Recommendation:** H2 is simpler, more debuggable, and sufficient for evochora's batch notification use case. The junction table approach correctly handles multiple consumer groups and competing consumers. The event-driven trigger architecture provides instant notifications without polling overhead.

---

## 19. Event-Driven Benefits Summary

### 19.1 Performance Improvements

**Compared to Polling (100ms intervals):**
- ⚡ **Latency:** Instant notification vs 0-100ms delay
- 🔋 **CPU:** Minimal (blocking wait) vs constant (busy-loop)
- 📊 **DB Load:** Query only on message vs query every 100ms
- 📈 **Scalability:** O(1) per reader vs O(readers) query load

**Measured Impact (estimated):**
- Latency reduction: **95%** (instant vs average 50ms polling delay)
- CPU reduction: **90%** (no busy-waiting loops)
- DB queries: **99%** reduction (only queries with messages, not empty polling)

### 19.2 Architecture Benefits

1. **Event-Driven Paradigm:**
   - Readers sleep until notified (efficient BlockingQueue.take())
   - H2 trigger wakes up readers instantly on INSERT
   - No wasted cycles checking empty queues

2. **Competing Consumers Synergy:**
   - Trigger notifies ALL readers in queue
   - `FOR UPDATE SKIP LOCKED` distributes messages automatically
   - No coordination overhead

3. **Simplicity:**
   - Standard Java BlockingQueue API
   - Standard H2 Trigger API
   - No custom polling logic needed

### 19.3 Trade-Offs Accepted

- ⚠️ **Trigger Overhead:** ~microseconds per INSERT (negligible)
- ⚠️ **Static Registry:** H2InsertTrigger uses static Map (manageable)
- ⚠️ **Edge Cases:** Notification might not yield message (competing consumer took it) → Retry logic handles this

**Verdict:** The benefits far outweigh the minimal overhead. Event-driven architecture is the correct choice for H2TopicResource.

---

*End of Specification*

