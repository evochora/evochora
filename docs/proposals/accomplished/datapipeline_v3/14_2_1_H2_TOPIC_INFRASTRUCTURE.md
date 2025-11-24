# Phase 14.2.1: H2-Based Topic Infrastructure

**Status:** Open  
**Parent:** [14.2 Indexer Foundation](14_2_INDEXER_FOUNDATION.md)  
**Depends On:** Phase 2.4 (Metadata Indexer)

**Recent Changes:**
- ✅ **Shared Table Structure per Run:** Each simulation run has its own schema with `topic_messages` and `topic_consumer_group` tables (no per-topic tables!)
- ✅ **Unlimited Topics per Run:** Add topics without creating new tables - `topic_name` column provides logical partitioning within each run's schema
- ✅ **Shared Trigger:** Single H2 trigger routes notifications by `topic_name` to correct queue
- ✅ **Composite Indexes:** All indexes include `topic_name` for partition-like performance
- ✅ **HikariCP Integration:** Added connection pooling following `H2Database` pattern
- ✅ **PreparedStatement Pooling:** Delegates prepare SQL statements once (lazy initialization after schema switch)
- ✅ **System-wide Connection Limits:** `maxPoolSize` config enforces resource limits across all topics
- ✅ **HikariCP Metrics:** Connection pool metrics exposed via MXBean (active/idle/total/awaiting)
- ✅ **Delegate Lifecycle:** Connections and PreparedStatements held for delegate lifetime, returned to pool on close
- ✅ **Single Validation Point:** Eliminated consumer group validation duplication (DRY principle)
- ✅ **Transaction Management:** Added explicit transactions for ACK (3-step atomic operation), writer uses auto-commit
- ✅ **Protobuf Contracts:** Fixed BatchInfo and MetadataInfo to match Chronicle spec (simulation_run_id, storage_key, written_at_ms)
- ✅ **Schema Management:** Added `ISimulationRunAwareTopic` interface and `H2SchemaUtil` for per-run schema isolation
- ✅ **H2SchemaUtil:** Centralized H2 schema operations (name sanitization, creation with bug workaround, switching)
- ✅ **H2-Compatible Indexes:** Fixed partial index syntax (H2 doesn't support `WHERE` clauses) - now using composite indexes
- ✅ **Table Naming:** Renamed `consumer_group_acks` to `topic_consumer_group` for consistent `topic_` prefix (avoids collisions with indexer tables)
- ✅ **Transaction Mode Management:** Fixed schema setup to save/restore autoCommit mode - prevents conflicts between H2SchemaUtil (manual) and delegate operations (auto/explicit)
- ✅ **Terminology Clarification:** Corrected "Centralized Tables" → "Shared Table Structure per Run" - tables are per-schema (per-run), NOT global across all runs
- ✅ **Lazy Trigger Registration:** Fixed run isolation bug - trigger now registered with schema-qualified key (`topicName:schemaName`) to prevent cross-run notification leakage
- ✅ **AutoCloseable Delegates:** Topic delegates implement `AutoCloseable` for flexible resource management - supports both long-lived (performance) and try-with-resources (safety) patterns
- ✅ **Claim Version (Stale ACK Prevention):** Added `claim_version` column in `topic_consumer_group` to prevent duplicate processing after stuck message reassignment - ensures at-most-once semantics within consumer group
- ✅ **Explicit Auto-Commit:** Writer delegate explicitly sets `autoCommit=true` in constructor for defensive programming (no assumptions about HikariCP defaults)
- ✅ **Atomic Claim Algorithm:** Replaced `atomic INSERT/UPDATE` with `INSERT`/conditional `UPDATE` loop over candidates (up to 10) - prevents Pub/Sub blocking, supports true multi-consumer-group semantics
- ✅ **Claim Conflict Ratio Metric:** Added O(1) sliding window metric (default 5s) to track failed claim attempts vs total attempts - helps identify contention
- ✅ **Idempotent setSimulationRun():** Prevents redundant schema setup calls and throws `IllegalStateException` if run ID is changed after being set
- ✅ **Performance Profiling:** Identified bottlenecks - HikariCP init (~2.7s, one-time), first send/ack (~85ms/18ms, PreparedStatement compilation), subsequent calls (<10ms)

---

## 1. Overview

This specification defines a **H2 database-based topic infrastructure** as an alternative to Chronicle Queue for the in-process data pipeline. Unlike Chronicle Queue, H2 provides:

- **Per-run schema isolation** - Each simulation run has its own schema (like `H2Database`), ensuring complete data isolation
- **Shared table structure** - Within each run's schema: single `topic_messages` and `topic_consumer_group` tables for all topics (no per-topic tables!)
- **Unlimited topics per run** - Add topics without creating new tables or indexes within a run's schema
- **Atomic claim-based delivery** - `INSERT`/conditional `UPDATE` loop over candidates prevents race conditions
- **Multi-writer support** - No internal queue or writer thread needed (H2 MVCC)
- **HikariCP connection pooling** - Efficient connection management with system-wide limits
- **PreparedStatement pooling** - Reuse SQL statements per delegate (lazy initialization after schema switch)
- **Event-driven delivery** - H2 triggers provide instant notifications (no polling!)
- **Explicit acknowledgment** - SQL-based ACK via junction table (not DELETE)
- **Consumer groups** - Proper isolation via `topic_consumer_group` junction table with per-group claim state
- **Competing consumers** - Atomic `INSERT`/`UPDATE` for automatic load balancing within consumer groups
- **Permanent storage** - Messages never deleted (enables historical replay)
- **In-flight tracking** - `claimed_by`, `claimed_at`, `claim_version` columns identify messages being processed
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
| **Message Delivery** | Tailer advance (implicit lock) | Atomic claim (INSERT/UPDATE loop) |
| **Race Conditions** | Possible (tailer + ACK not atomic) | Prevented (atomic INSERT/UPDATE per group) |
| **Acknowledgment** | Implicit (tailer advance) | Explicit (UPDATE in junction table) |
| **Consumer Groups** | Tailer per group (complex) | Junction table (simple SQL) |
| **Competing Consumers** | Manual coordination | Atomic INSERT/UPDATE (automatic) |
| **In-Flight Tracking** | None | `claimed_by`, `claimed_at`, `claim_version` in junction table |
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
        ← H2TopicReaderDelegate.tryReadMessage()
           ← SELECT up to 10 candidates (LEFT JOIN topic_consumer_group)
           ← For each candidate: INSERT or conditional UPDATE
           ← Return first successfully claimed message
```

**Acknowledgment Path (with Claim Release):**
```
Service → ITopicReader.ack(TopicMessage)
        → AbstractTopicDelegateReader.ack()
        → H2TopicReaderDelegate.acknowledgeMessage(AckToken)
        → SQL UPDATE topic_consumer_group SET acknowledged_at = NOW() WHERE claim_version = ?
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
- Each reader tries `SELECT` (up to 10 candidates) then `INSERT`/`UPDATE` loop
- First reader to successfully INSERT/UPDATE claims message → Others try next candidate
- This is correct behavior (automatic load distribution)

**Consumer Group Filtering:**
- Trigger notifies about ALL new messages
- Reader SQL filters by consumer group (LEFT JOIN to topic_consumer_group)
- Only selects messages that are: not acknowledged AND (not claimed OR claim expired)
- If no messages available → Reader waits for next notification

### 5.4 Stuck Message Detection and Recovery

**What are "Stuck Messages"?**

Messages that have been claimed in `topic_consumer_group` (`claimed_by IS NOT NULL`) but not acknowledged for an extended period. This can happen if:
- Consumer crashes after `receive()` but before `ack()`
- Consumer hangs indefinitely
- Application terminated ungracefully

**Detection Query:**
```sql
-- Find messages claimed more than 5 minutes ago (per consumer group)
SELECT tm.id, tm.message_id, cg.claimed_by, cg.claimed_at, cg.consumer_group
FROM topic_messages tm
JOIN topic_consumer_group cg ON tm.topic_name = cg.topic_name AND tm.message_id = cg.message_id
WHERE cg.claimed_by IS NOT NULL
AND cg.acknowledged_at IS NULL
AND cg.claimed_at < DATEADD('SECOND', -300, CURRENT_TIMESTAMP);
```

**Recovery Options:**

1. **Automatic Reassignment (Built-in):**
- Reader's `SELECT` query automatically includes expired claims
- Next reader to call `receive()` will take over the message via conditional `UPDATE`
- `claim_version` is incremented to prevent stale ACKs

2. **Manual Intervention:**
```sql
-- Admin query to inspect stuck messages
SELECT * FROM topic_consumer_group WHERE claimed_by IS NOT NULL AND acknowledged_at IS NULL;

-- Force release specific stuck message (reset claim)
UPDATE topic_consumer_group 
SET claimed_by = NULL, claimed_at = NULL 
WHERE topic_name = ? AND consumer_group = ? AND message_id = ?;
```

**Automatic Reassignment (Configurable Timeout):**

Phase 14.2.1 **DOES** include automatic stuck message reassignment via configurable `claimTimeout`:

```hocon
claimTimeout = 300  # Seconds (5 minutes), 0 = disabled
```

- **`claimTimeout > 0`:** Messages claimed longer than timeout are automatically reassigned to next consumer
- **`claimTimeout = 0`:** Automatic reassignment disabled (manual intervention required)
- **Idempotency:** Services MUST be idempotent (message may be processed multiple times)
- **At-Most-Once Guarantee:** Claim version prevents duplicate processing within a consumer group
- **Stale ACK Detection:** If consumer freezes (GC pause) and message is reassigned, original consumer's ACK is rejected
- **Logging:** Reassignment triggers WARN log + error recording
- **Metric:** `stuck_messages_reassigned` (O(1) AtomicLong) tracks reassignment count

**Manual Cleanup (Alternative):**

Automatic cleanup background jobs (e.g., periodic reset task) are **NOT** included. They can be added later as:
- A scheduled task in the ServiceManager
- A separate monitoring service
- An administrative endpoint in the HTTP API

---

## 6. Database Schema (Per Simulation Run)

### 6.1 Topic Messages Table (Shared by All Topics in Run's Schema)

```sql
CREATE TABLE IF NOT EXISTS topic_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_name VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    timestamp BIGINT NOT NULL,
    envelope BLOB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite unique constraint (topic + message)
    CONSTRAINT uk_topic_message UNIQUE (topic_name, message_id)
);

-- Performance index for topic-based queries
CREATE INDEX IF NOT EXISTS idx_topic_messages ON topic_messages (topic_name, id);
```

**Column Descriptions:**
- `id`: Auto-incrementing sequence number (used as ACK token rowId for internal tracking)
- `topic_name`: Name of the topic this message belongs to (enables logical partitioning)
- `message_id`: UUID from TopicEnvelope (unique per topic, for idempotency)
- `timestamp`: Unix timestamp from TopicEnvelope
- `envelope`: Serialized TopicEnvelope (Protobuf binary, BLOB type for H2 compatibility)
- `created_at`: Database timestamp for debugging

**Design Notes:**
- **Per-Run Isolation:** Table exists in each simulation run's schema (e.g., `SIM_20251006_UUID.topic_messages`)
- **Shared Structure:** Single table structure for all topics within a run (no per-topic tables!)
- **Logical Partitioning:** `topic_name` column enables topic-specific queries with composite indexes
- **Run Data Isolation:** Different runs have completely separate tables in different schemas - no data mixing
- **No Claim State:** This table only stores message payloads - claim/ack state is in `topic_consumer_group`
- **Permanent Storage:** Messages are NEVER deleted (enables historical replay)
- **Scalability:** Supports unlimited topics per run without creating new tables

### 6.2 Consumer Group State Table (Shared by All Topics in Run's Schema)

```sql
CREATE TABLE IF NOT EXISTS topic_consumer_group (
    topic_name VARCHAR(255) NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMP,
    claim_version INT DEFAULT 1,
    acknowledged_at TIMESTAMP,
    
    -- Composite primary key (topic + consumer group + message)
    PRIMARY KEY (topic_name, consumer_group, message_id)
);

-- Performance indexes for claim and acknowledgment queries
CREATE INDEX IF NOT EXISTS idx_topic_group_claimed ON topic_consumer_group (topic_name, claimed_by, id);
CREATE INDEX IF NOT EXISTS idx_topic_group_claim_time ON topic_consumer_group (topic_name, claimed_by, claimed_at);
```

**Column Descriptions:**
- `topic_name`: Name of the topic this entry belongs to
- `consumer_group`: Name of the consumer group
- `message_id`: Reference to `topic_messages.message_id`
- `claimed_by`: Service name that claimed this message for this consumer group (NULL = available)
- `claimed_at`: Timestamp when message was claimed (for stuck message detection)
- `claim_version`: Incremented on each claim (prevents stale ACK after reassignment)
- `acknowledged_at`: Timestamp when acknowledged by this group (NULL = not yet acknowledged)

**Design Rationale:**
- **Per-Group State:** Each consumer group tracks its own claim/ack state independently
- **Pub/Sub:** Each consumer group receives all messages independently per topic
- **Competing Consumers:** Multiple consumers within the same group compete via atomic `INSERT`/`UPDATE`
- **No Data Loss:** Acknowledgment by one group does not affect other groups
- **Stale ACK Prevention:** `claim_version` ensures ACKs from timed-out consumers are rejected
- **Permanent Storage:** Rows are never deleted (enables historical replay and new consumer groups)
- **Scalability:** Supports unlimited topics and consumer groups without creating new tables

### 6.4 Read and Acknowledgment Strategy (Claim-Based)

**Write:**
```sql
INSERT INTO topic_messages (topic_name, message_id, timestamp, envelope) 
VALUES (?, ?, ?, ?);
```

**Read (Two-Step: SELECT Candidates + Atomic Claim Loop):**
```sql
-- Step 1: SELECT up to 10 candidate messages (no locking!)
SELECT tm.id, tm.message_id, tm.envelope
FROM topic_messages tm
LEFT JOIN topic_consumer_group cg 
    ON tm.topic_name = cg.topic_name 
    AND tm.message_id = cg.message_id 
    AND cg.consumer_group = ?
WHERE tm.topic_name = ?
AND (
    cg.message_id IS NULL  -- Never seen by this group
    OR (cg.acknowledged_at IS NULL  -- Not acknowledged yet
        AND (cg.claimed_at IS NULL  -- Not claimed
             OR cg.claimed_at < DATEADD('SECOND', -?, CURRENT_TIMESTAMP)  -- Or claim expired
        )
    )
)
ORDER BY tm.id
LIMIT 10;

-- Step 2: For each candidate, try atomic claim via INSERT (new) or UPDATE (takeover)
-- Option A: Try INSERT first (new message for this group)
INSERT INTO topic_consumer_group (topic_name, consumer_group, message_id, claimed_by, claimed_at, claim_version)
VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 1);

-- Option B: If INSERT fails (duplicate key), try conditional UPDATE (takeover expired claim)
UPDATE topic_consumer_group
SET claimed_by = ?, claimed_at = CURRENT_TIMESTAMP, claim_version = claim_version + 1
WHERE topic_name = ? AND consumer_group = ? AND message_id = ?
AND acknowledged_at IS NULL
AND (claimed_at IS NULL OR claimed_at < DATEADD('SECOND', -?, CURRENT_TIMESTAMP));

-- If either INSERT or UPDATE succeeds: message claimed! Return it.
-- If both fail: try next candidate.
```

**Key Features:**
- ✅ **No Table Locking:** `SELECT` without `FOR UPDATE` allows true Pub/Sub (multiple groups can read same message)
- ✅ **Atomic Claim:** `INSERT` or conditional `UPDATE` is atomic per consumer group
- ✅ **Topic Isolation:** `WHERE tm.topic_name = ?` ensures each topic is independent
- ✅ **Consumer Group Filtering:** `LEFT JOIN` with precise `WHERE` clause only returns claimable messages
- ✅ **Competing Consumers:** Multiple consumers try to `INSERT`/`UPDATE` - only one succeeds per message per group
- ✅ **Batch Candidates:** `LIMIT 10` reduces retries under high contention
- ✅ **Performance Optimized:** Composite indexes on `(topic_name, claimed_by, id)` and `(topic_name, claimed_by, claimed_at)`

**Acknowledge:**
```sql
-- Step 1: Get message_id from rowId (lightweight SELECT by primary key)
SELECT message_id FROM topic_messages WHERE id = ?;

-- Step 2: Mark as acknowledged WITH VERSION CHECK (prevents stale ACK)
UPDATE topic_consumer_group
SET acknowledged_at = CURRENT_TIMESTAMP
WHERE topic_name = ? AND consumer_group = ? AND message_id = ?
AND claim_version = ?;
-- If 0 rows updated: claim was stale (message was reassigned), ACK rejected
```

**Note:** Messages are never deleted. They remain in the database permanently, allowing:
- New consumer groups to process historical messages
- Re-indexing without re-running simulations
- Audit trail of all published messages
- Detection of stuck messages (claimed but not acknowledged)

### 6.3 H2 Trigger Setup

The event-driven architecture requires an H2 trigger to be created:

```sql
CREATE TRIGGER IF NOT EXISTS topic_messages_notify_trigger
AFTER INSERT ON topic_messages
FOR EACH ROW
CALL "org.evochora.datapipeline.resources.topics.H2InsertTrigger";
```

**Trigger Lifecycle:**
1. Created during `H2TopicResource.setSimulationRun()` (lazy, per-schema)
2. Registered with static notification queue map using schema-qualified key (`topicName:schemaName`)
3. Fires on every INSERT (pushes message ID to queue)
4. Deregistered and dropped during `H2TopicResource.close()` via `H2SchemaUtil.cleanupRunSchema()`

**Schema Isolation:**
- Each simulation run's schema has its own trigger instance
- Trigger registration uses `topicName:schemaName` key to prevent cross-run notification leakage
- Multiple topics in same schema share the same trigger (single `topic_messages` table)

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
 * <strong>Resource Management:</strong> Implements {@link AutoCloseable} to support both:
 * <ul>
 *   <li>Long-lived pattern: Create once, use many times, close manually (best performance)</li>
 *   <li>Try-with-resources pattern: Auto-cleanup per operation (best safety)</li>
 * </ul>
 * <p>
 * <strong>Implements:</strong> {@link IResource}, {@link ISimulationRunAwareTopic}, {@link AutoCloseable}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public interface ITopicWriter<T extends Message> extends IResource, ISimulationRunAwareTopic, AutoCloseable {
    
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
    
    /**
     * Releases resources held by this writer.
     * <p>
     * <strong>Implementation Notes:</strong>
     * <ul>
     *   <li>H2: Returns database connection and PreparedStatements to HikariCP pool</li>
     *   <li>Chronicle: Stops writer thread and releases queue reference</li>
     * </ul>
     * <p>
     * <strong>Idempotency:</strong> Calling close() multiple times is safe (no-op).
     * <p>
     * <strong>Usage Patterns:</strong>
     * <pre>
     * // Pattern 1: Long-lived (best performance - recommended for services)
     * ITopicWriter writer = topic.createWriterDelegate();
     * writer.setSimulationRun(runId);
     * while (running) {
     *     writer.send(message);  // Fast! PreparedStatement cached
     * }
     * writer.close();  // Manual cleanup
     * 
     * // Pattern 2: Try-with-resources (best safety - for one-off operations)
     * try (ITopicWriter writer = topic.createWriterDelegate()) {
     *     writer.setSimulationRun(runId);
     *     writer.send(message);
     * }  // Auto cleanup
     * </pre>
     */
    @Override
    void close();
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
 * <strong>Resource Management:</strong> Implements {@link AutoCloseable} to support both:
 * <ul>
 *   <li>Long-lived pattern: Create once, use many times, close manually (best performance)</li>
 *   <li>Try-with-resources pattern: Auto-cleanup per operation (best safety)</li>
 * </ul>
 * <p>
 * <strong>Implements:</strong> {@link IResource}, {@link ISimulationRunAwareTopic}, {@link AutoCloseable}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public interface ITopicReader<T extends Message, ACK> extends IResource, ISimulationRunAwareTopic, AutoCloseable {
    
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
    
    /**
     * Releases resources held by this reader.
     * <p>
     * <strong>Implementation Notes:</strong>
     * <ul>
     *   <li>H2: Returns database connection to HikariCP pool</li>
     *   <li>Chronicle: Closes tailer and releases queue reference</li>
     * </ul>
     * <p>
     * <strong>Idempotency:</strong> Calling close() multiple times is safe (no-op).
     * <p>
     * <strong>Usage Patterns:</strong>
     * <pre>
     * // Pattern 1: Long-lived (best performance)
     * ITopicReader reader = topic.createReaderDelegate();
     * reader.setSimulationRun(runId);
     * while (running) {
     *     TopicMessage msg = reader.receive();
     *     process(msg);
     *     reader.ack(msg);
     * }
     * reader.close();  // Manual cleanup
     * 
     * // Pattern 2: Try-with-resources (best safety)
     * while (running) {
     *     try (ITopicReader reader = topic.createReaderDelegate()) {
     *         reader.setSimulationRun(runId);
     *         TopicMessage msg = reader.receive();
     *         process(msg);
     *         reader.ack(msg);
     *     }  // Auto cleanup
     * }
     * </pre>
     */
    @Override
    void close();
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
    
    /**
     * Returns the message payload.
     *
     * @return The Protobuf message payload.
     */
    public T payload() { return payload; }
    
    /**
     * Returns the message timestamp.
     *
     * @return Unix timestamp in milliseconds when the message was written.
     */
    public long timestamp() { return timestamp; }
    
    /**
     * Returns the unique message identifier.
     *
     * @return The UUID-based message ID.
     */
    public String messageId() { return messageId; }
    
    /**
     * Returns the consumer group that read this message.
     *
     * @return The consumer group name.
     */
    public String consumerGroup() { return consumerGroup; }
    
    /**
     * Returns the acknowledgment token for this message.
     * <p>
     * <strong>VISIBILITY WARNING:</strong>
     * This method is {@code public} only because {@link org.evochora.datapipeline.resources.topics.AbstractTopicDelegateReader}
     * resides in a different package (implementation package vs. API package).
     * <p>
     * <strong>DO NOT USE THIS METHOD DIRECTLY IN CLIENT CODE!</strong>
     * The acknowledgment token is an internal implementation detail. Services should only call
     * {@link ITopicReader#ack(TopicMessage)}, which uses this token internally.
     * <p>
     * This token is implementation-specific and opaque:
     * <ul>
     *   <li>H2: {@code AckToken(rowId, claimVersion)}</li>
     *   <li>Chronicle: {@code Long} (tailer index)</li>
     *   <li>Kafka: {@code Long} (offset)</li>
     * </ul>
     *
     * @return The acknowledgment token (for internal use by topic delegates only).
     */
    public ACK acknowledgeToken() { return acknowledgeToken; }
    
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
import org.evochora.datapipeline.api.resources.topics.ISimulationRunAwareTopic;
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
 * <p>
 * <strong>Simulation Run Awareness:</strong>
 * Implements {@link ISimulationRunAwareTopic} to store the simulation run ID and provide
 * access to subclasses via {@link #getSimulationRunId()}. Subclasses can override
 * {@link #onSimulationRunSet(String)} to perform run-specific initialization.
 *
 * @param <P> The parent topic resource type.
 */
public abstract class AbstractTopicDelegate<P extends AbstractTopicResource<?, ?>> extends AbstractResource implements IWrappedResource, ISimulationRunAwareTopic, AutoCloseable {
    
    protected final P parent;
    protected final String consumerGroup;  // Only used by readers
    
    /**
     * The simulation run ID for this delegate.
     * Set via {@link #setSimulationRun(String)} before sending/reading messages.
     */
    private String simulationRunId;
    
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
    public final void setSimulationRun(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isBlank()) {
            throw new IllegalArgumentException("Simulation run ID must not be null or blank");
        }
        this.simulationRunId = simulationRunId;
        onSimulationRunSet(simulationRunId);
    }
    
    /**
     * Returns the simulation run ID for this delegate.
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
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
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
 * <ul>
 *   <li>Implement {@link #sendEnvelope(TopicEnvelope)} to write the envelope to the underlying topic</li>
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup (inherited from {@link AbstractTopicDelegate})</li>
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
     * Creates a new writer delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegateWriter(P parent, ResourceContext context) {
        super(parent, context);
    }
    
    @Override
    public final void send(T message) throws InterruptedException {
        if (getSimulationRunId() == null) {
            throw new IllegalStateException("setSimulationRun() must be called before sending messages");
        }
        
        // Wrap message in envelope
        TopicEnvelope envelope = TopicEnvelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setPayload(Any.pack(message))
            .build();
        
        // Delegate to concrete implementation
        sendEnvelope(envelope);
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
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
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
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup (inherited from {@link AbstractTopicDelegate})</li>
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
            // Extract everything after the FIRST '/' to get full package.ClassName
            String typeUrl = anyPayload.getTypeUrl();
            String className = typeUrl.contains("/") 
                ? typeUrl.substring(typeUrl.indexOf('/') + 1)  // Full path after first '/'
                : typeUrl;  // Fallback if no domain prefix
            
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

import org.evochora.datapipeline.api.contracts.TopicEnvelope;

/**
 * Internal envelope for passing messages from concrete implementations to abstract readers.
 * <p>
 * This record bridges the gap between technology-specific reading logic and the abstract
 * {@link org.evochora.datapipeline.api.resources.topics.TopicMessage} that is returned to clients.
 * <p>
 * <strong>Purpose:</strong>
 * Concrete reader delegates (H2TopicReaderDelegate, ChronicleTopicReaderDelegate) use this to return:
 * <ul>
 *   <li>The raw {@link TopicEnvelope} (contains Protobuf Any payload)</li>
 *   <li>An implementation-specific acknowledgment token (row ID, tailer index, offset, etc.)</li>
 * </ul>
 * <p>
 * The abstract {@link AbstractTopicDelegateReader} then unwraps the envelope and creates a
 * {@link org.evochora.datapipeline.api.resources.topics.TopicMessage} with the typed payload.
 * <p>
 * <strong>Implementation Examples:</strong>
 * <ul>
 *   <li>H2: {@code ReceivedEnvelope<AckToken>} where {@code acknowledgeToken} is AckToken(rowId, claimVersion)</li>
 *   <li>Chronicle: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the tailer index</li>
 *   <li>Kafka: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the offset</li>
 * </ul>
 *
 * @param envelope The wrapped Protobuf envelope (contains message_id, timestamp, Any payload).
 * @param acknowledgeToken Implementation-specific token for acknowledgment.
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
 *   <li><strong>Competing Consumers:</strong> atomic INSERT/UPDATE for automatic load balancing</li>
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
public class H2TopicResource<T extends Message> extends AbstractTopicResource<T, AckToken> implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicResource.class);
    
    private final HikariDataSource dataSource;
    private final int claimTimeoutSeconds;  // 0 = disabled, > 0 = timeout for stuck message reassignment
    private final BlockingQueue<Long> messageNotifications;  // Event-driven notification from H2 trigger
    private final SlidingWindowCounter writeThroughput;
    private final SlidingWindowCounter readThroughput;
    private final AtomicLong stuckMessagesReassigned;  // O(1) metric for reassignments
    
    // Lazy trigger registration (schema-aware for run isolation)
    private volatile String currentSchemaName = null;  // Current registered schema (null = not registered)
    private final Object triggerLock = new Object();   // Synchronization for trigger registration
    
    // Centralized table names (shared by all topics)
    private static final String MESSAGES_TABLE = "topic_messages";
    private static final String ACKS_TABLE = "topic_consumer_group";
    
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
            
            // Note: Trigger registration is LAZY - happens in ensureTriggerRegistered()
            // when first delegate calls setSimulationRun(). This ensures schema is known.
            
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
     * Ensures the H2 trigger is registered for the given simulation run (lazy registration).
     * <p>
     * This method is called by delegates during {@code onSimulationRunSet()}. It registers
     * the trigger only once per schema, using a schema-qualified registry key for run isolation.
     * <p>
     * <strong>Thread Safety:</strong>
     * Multiple delegates may call this concurrently. The method uses double-check locking
     * to ensure the trigger is registered exactly once per schema.
     * <p>
     * <strong>Run Isolation:</strong>
     * Different simulation runs use different schemas (e.g., {@code SIM_RUN1}, {@code SIM_RUN2}).
     * The trigger registry key includes both topic name AND schema name to prevent cross-run
     * notification leakage.
     *
     * @param simulationRunId The simulation run ID (used to derive schema name).
     * @throws RuntimeException if trigger registration fails.
     */
    void ensureTriggerRegistered(String simulationRunId) {
        String schemaName = H2SchemaUtil.toSchemaName(simulationRunId);
        
        // Fast path: already registered for this schema
        if (schemaName.equals(currentSchemaName)) {
            return;
        }
        
        synchronized (triggerLock) {
            // Double-check: another thread may have registered while we waited
            if (schemaName.equals(currentSchemaName)) {
                return;
            }
            
            // Deregister old schema (if switching runs - unlikely but safe)
            if (currentSchemaName != null) {
                H2InsertTrigger.deregisterNotificationQueue(getResourceName(), currentSchemaName);
                log.debug("Deregistered trigger for topic '{}' from old schema '{}'", 
                    getResourceName(), currentSchemaName);
            }
            
            // Register for new schema
            try {
                registerInsertTrigger(schemaName);
                currentSchemaName = schemaName;
                log.debug("Registered trigger for topic '{}' in schema '{}'", 
                    getResourceName(), schemaName);
            } catch (SQLException e) {
                String msg = String.format(
                    "Failed to register H2 trigger for topic '%s' in schema '%s'",
                    getResourceName(), schemaName
                );
                log.error(msg);
                throw new RuntimeException(msg, e);
            }
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
     * <p>
     * <strong>Run Isolation:</strong>
     * The notification queue is registered with a schema-qualified key ({@code topicName:schemaName})
     * to ensure different simulation runs don't interfere with each other.
     *
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     * @throws SQLException if trigger creation fails.
     */
    private void registerInsertTrigger(String schemaName) throws SQLException {
        // With shared table structure, we use a single trigger for all topics
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
        
        // Register this topic's notification queue with schema-qualified key for run isolation
        H2InsertTrigger.registerNotificationQueue(getResourceName(), schemaName, messageNotifications);
        
        log.debug("Registered H2 trigger '{}' for topic '{}' in schema '{}' (event-driven notifications)", 
            triggerName, getResourceName(), schemaName);
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
                envelope BLOB NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT uk_topic_message UNIQUE (topic_name, message_id)
            )
            """;
        
        // Create index for messages table (H2 requires separate CREATE INDEX statements)
        String createIndexMessages = 
            "CREATE INDEX IF NOT EXISTS idx_topic_messages ON topic_messages (topic_name, id)";
        
        // Create centralized consumer group state table (shared by all topics)
        String createConsumerGroupSql = """
            CREATE TABLE IF NOT EXISTS topic_consumer_group (
                topic_name VARCHAR(255) NOT NULL,
                consumer_group VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                claimed_by VARCHAR(255),
                claimed_at TIMESTAMP,
                claim_version INT DEFAULT 1,
                acknowledged_at TIMESTAMP,
                
                PRIMARY KEY (topic_name, consumer_group, message_id)
            )
            """;
        
        // Create indexes for consumer group table
        String createIndexGroupClaimed = 
            "CREATE INDEX IF NOT EXISTS idx_topic_group_claimed ON topic_consumer_group (topic_name, claimed_by, id)";
        String createIndexGroupClaimTime = 
            "CREATE INDEX IF NOT EXISTS idx_topic_group_claim_time ON topic_consumer_group (topic_name, claimed_by, claimed_at)";
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesSql);
            stmt.execute(createIndexMessages);
            stmt.execute(createConsumerGroupSql);
            stmt.execute(createIndexGroupClaimed);
            stmt.execute(createIndexGroupClaimTime);
            log.debug("Initialized centralized topic tables for resource '{}'", getResourceName());
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
        metrics.put("messages_written_per_sec", writeThroughput.getRate());
        metrics.put("messages_read_per_sec", readThroughput.getRate());
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
        writeThroughput.recordCount();
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
        readThroughput.recordCount();
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
        
        // Deregister trigger notification queue (only if it was registered)
        if (currentSchemaName != null) {
            H2InsertTrigger.deregisterNotificationQueue(getResourceName(), currentSchemaName);
            log.debug("Deregistered trigger for topic '{}' from schema '{}'", 
                getResourceName(), currentSchemaName);
        }
        
        // Note: We do NOT drop the shared trigger here, as other topics may still be using it
        // The trigger is shared by all topics in the schema and should only be dropped when
        // the schema is dropped (or when the database is shut down)
        
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
    
    // Static registry: "topicName:schemaName" → notification queue
    // Schema-qualified key ensures run isolation (different runs = different schemas)
    private static final Map<String, BlockingQueue<Long>> notificationQueues = new ConcurrentHashMap<>();
    
    /**
     * Registers a notification queue for a specific topic in a specific schema.
     * <p>
     * Called by {@link H2TopicResource} during initialization.
     * <p>
     * <strong>Run Isolation:</strong>
     * The key includes both topic name AND schema name to ensure that different
     * simulation runs (which use different schemas) have separate notification queues.
     *
     * @param topicName The topic name (resource name).
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     * @param queue The notification queue.
     */
    public static void registerNotificationQueue(String topicName, String schemaName, BlockingQueue<Long> queue) {
        String key = topicName + ":" + schemaName;
        notificationQueues.put(key, queue);
        log.debug("Registered notification queue for topic '{}' in schema '{}'", topicName, schemaName);
    }
    
    /**
     * Deregisters a notification queue for a specific topic in a specific schema.
     * <p>
     * Called by {@link H2TopicResource} during shutdown.
     *
     * @param topicName The topic name (resource name).
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     */
    public static void deregisterNotificationQueue(String topicName, String schemaName) {
        String key = topicName + ":" + schemaName;
        notificationQueues.remove(key);
        log.debug("Deregistered notification queue for topic '{}' in schema '{}'", topicName, schemaName);
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
            // Shared table schema: id, topic_name, message_id, timestamp, envelope, ...
            Long messageId = (Long) newRow[0];      // Column 0: id (BIGINT AUTO_INCREMENT)
            String topicName = (String) newRow[1];  // Column 1: topic_name (VARCHAR)
            
            // Get current schema for run isolation
            String schemaName = conn.getSchema();
            String key = topicName + ":" + schemaName;
            
            // Look up the notification queue for this specific topic in this specific schema
            BlockingQueue<Long> queue = notificationQueues.get(key);
            
            if (queue != null) {
                boolean offered = queue.offer(messageId);
                
                if (!offered) {
                    // Queue full - should not happen with unbounded LinkedBlockingQueue
                    log.warn("Notification queue full for topic '{}' in schema '{}', message ID {} not notified", 
                        topicName, schemaName, messageId);
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
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
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
            
            // Explicitly set auto-commit mode for single-statement INSERTs (defensive programming)
            // HikariCP defaults to true, but we verify to ensure atomic writes work correctly
            connection.setAutoCommit(true);
            
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
            // H2SchemaUtil expects manual commit mode, but writer uses auto-commit for INSERTs
            // Save current mode, switch to manual for schema operations, then restore
            boolean wasAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);  // Switch to manual for schema creation
            
            try {
                // Create schema if it doesn't exist (safe for concurrent calls, H2 bug workaround included)
                H2SchemaUtil.createSchemaIfNotExists(connection, simulationRunId);
                
                // Switch to the schema for this simulation run
                H2SchemaUtil.setSchema(connection, simulationRunId);
                
                connection.setAutoCommit(wasAutoCommit);  // Restore original mode
                
                log.debug("H2 topic writer '{}' switched to schema for run: {}", 
                    parent.getResourceName(), simulationRunId);
                    
            } catch (SQLException schemaError) {
                connection.setAutoCommit(wasAutoCommit);  // Restore on error
                throw schemaError;
            }
            
            // Ensure parent has trigger registered for this schema (lazy, thread-safe)
            parent.ensureTriggerRegistered(simulationRunId);
                
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
            // Single INSERT is atomic (ACID guarantee). Connection is in auto-commit mode (set in constructor).
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
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
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
 *   <li>Acknowledgments tracked in {@code topic_consumer_group} table</li>
 *   <li>Each consumer group sees only messages NOT in their acks table</li>
 *   <li>Acknowledgment by one group does NOT affect other groups</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong>
 * Multiple consumers in the same consumer group use {@code atomic INSERT/UPDATE}
 * to automatically distribute work without coordination overhead.
 * <p>
 * <strong>Acknowledgment:</strong>
 * Acknowledged messages are recorded in {@code topic_consumer_group} table.
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
public class H2TopicReaderDelegate<T extends Message> extends AbstractTopicDelegateReader<H2TopicResource<T>, T, AckToken> {
    
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
                    SET claimed_by = ?, 
                        claimed_at = CURRENT_TIMESTAMP,
                        claim_version = claim_version + 1
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
                        atomic INSERT/UPDATE
                    )
                    RETURNING id, message_id, envelope, claimed_by AS previous_claim, claim_version
                    """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable(), claimTimeout);
                this.readStatementWithTimeout = connection.prepareStatement(sqlWithTimeout);
            } else {
                this.readStatementWithTimeout = null;
            }
            
            // Prepare READ statement WITHOUT stuck message reassignment (claimTimeout = 0)
                String sqlNoTimeout = String.format("""
                    UPDATE %s tm
                    SET claimed_by = ?, 
                        claimed_at = CURRENT_TIMESTAMP,
                        claim_version = claim_version + 1
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
                        atomic INSERT/UPDATE
                    )
                    RETURNING id, message_id, envelope, NULL AS previous_claim, claim_version
                    """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable());
            this.readStatementNoTimeout = connection.prepareStatement(sqlNoTimeout);
            
            // Prepare ACK MERGE statement (once, reused for all ACKs)
            // MERGE is idempotent - handles both new acknowledgments and duplicates gracefully
            String ackMergeSql = String.format(
                "MERGE INTO %s (topic_name, consumer_group, message_id) KEY(topic_name, consumer_group, message_id) VALUES (?, ?, ?)",
                parent.getAcksTable()
            );
            this.ackInsertStatement = connection.prepareStatement(ackMergeSql);
            
            // Prepare ACK RELEASE statement WITH VERSION CHECK (once, reused for all ACKs)
            String ackReleaseSql = String.format(
                "UPDATE %s SET claimed_by = NULL, claimed_at = NULL WHERE id = ? AND claim_version = ?",
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
            // H2SchemaUtil expects manual commit mode, but reader uses explicit transactions for ACK
            // Save current mode, switch to manual for schema operations, then restore
            boolean wasAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);  // Switch to manual for schema creation
            
            try {
                // Create schema if it doesn't exist (safe for concurrent calls, H2 bug workaround included)
                H2SchemaUtil.createSchemaIfNotExists(connection, simulationRunId);
                
                // Switch to the schema for this simulation run
                H2SchemaUtil.setSchema(connection, simulationRunId);
                
                connection.setAutoCommit(wasAutoCommit);  // Restore original mode
                
                log.debug("H2 topic reader '{}' (consumerGroup='{}') switched to schema for run: {}", 
                    parent.getResourceName(), consumerGroup, simulationRunId);
                    
            } catch (SQLException schemaError) {
                connection.setAutoCommit(wasAutoCommit);  // Restore on error
                throw schemaError;
            }
            
            // Ensure parent has trigger registered for this schema (lazy, thread-safe)
            parent.ensureTriggerRegistered(simulationRunId);
                
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
        // or might be consumed by a competing consumer in the same group (atomic INSERT/UPDATE).
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
     * Uses {@code SELECT + INSERT/UPDATE} with claim-based approach to ensure:
     * <ul>
     *   <li>Atomic claim - prevents race conditions (single SQL statement!)</li>
     *   <li>Only messages NOT acknowledged by this consumer group are returned</li>
     *   <li>Multiple consumers in same group compete via atomic INSERT/UPDATE</li>
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
                    int claimVersion = rs.getInt("claim_version");
                    
                    TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                    
                    lastReadId = id;
                    parent.recordRead();
                    
                    // Check if this was a stuck message reassignment
                    if (previousClaim != null && !previousClaim.equals(consumerId)) {
                        log.warn("Reassigned stuck message from topic '{}': messageId={}, previousClaim={}, newConsumer={}, claimVersion={}, timeout={}s", 
                            parent.getResourceName(), messageId, previousClaim, consumerId, claimVersion, claimTimeout);
                        recordError("STUCK_MESSAGE_REASSIGNED", "Message claim timeout expired", 
                            "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                            ", PreviousClaim: " + previousClaim + ", ClaimVersion: " + claimVersion + ", Timeout: " + claimTimeout + "s");
                        parent.recordStuckMessageReassignment();
                    } else {
                        log.debug("Claimed message from topic '{}': messageId={}, consumerId={}, claimVersion={}", 
                            parent.getResourceName(), messageId, consumerId, claimVersion);
                    }
                    
                    // ACK token contains row ID and claim version (for stale ACK detection)
                    AckToken ackToken = new AckToken(id, claimVersion);
                    return new ReceivedEnvelope<>(envelope, ackToken);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to claim message from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("CLAIM_FAILED", "SQL SELECT + INSERT/UPDATE failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse envelope from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("PARSE_FAILED", "Protobuf parse failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        }
        
        return null;
    }
    
    @Override
    protected void acknowledgeMessage(AckToken token) {
        long rowId = token.rowId();
        int claimVersion = token.claimVersion();
        
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
            
            // Step 2: MERGE into topic_consumer_group (marks as acknowledged for THIS group only)
            // MERGE is idempotent - handles both new acknowledgments and duplicates gracefully
            // Reuse prepared statement for performance
            try {
                ackInsertStatement.setString(1, parent.getResourceName());  // topic_name
                ackInsertStatement.setString(2, consumerGroup);
                ackInsertStatement.setString(3, messageId);
                ackInsertStatement.executeUpdate();
                
                log.debug("Acknowledged message in topic '{}': messageId={}, consumerGroup={}", 
                    parent.getResourceName(), messageId, consumerGroup);
                
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                log.warn("Failed to acknowledge message in topic '{}': messageId={}, consumerGroup={}", 
                    parent.getResourceName(), messageId, consumerGroup);
                recordError("ACK_FAILED", "Failed to merge into topic_consumer_group", 
                    "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + ", ConsumerGroup: " + consumerGroup);
                return;
            }
            
            // Step 3: Release claim (makes message available for other consumer groups)
            // CRITICAL: claim_version check prevents stale ACK (message was reassigned after timeout)
            // Reuse prepared statement for performance
            try {
                ackReleaseStatement.setLong(1, rowId);
                ackReleaseStatement.setInt(2, claimVersion);
                int rowsUpdated = ackReleaseStatement.executeUpdate();
                
                // Stale ACK Detection: If rowsUpdated == 0, claim_version mismatched
                if (rowsUpdated == 0) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                    log.warn("Rejected stale ACK for topic '{}': rowId={}, claimVersion={} (message was reassigned)", 
                        parent.getResourceName(), rowId, claimVersion);
                    recordError("STALE_ACK_REJECTED", "Claim version mismatch", 
                        "Topic: " + parent.getResourceName() + ", RowId: " + rowId + ", ClaimVersion: " + claimVersion);
                    return;
                }
                
                log.debug("Released claim for message in topic '{}': rowId={}, claimVersion={}", 
                    parent.getResourceName(), rowId, claimVersion);
                
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                log.warn("Failed to release claim for message in topic '{}': rowId={}, claimVersion={}", 
                    parent.getResourceName(), rowId, claimVersion);
                recordError("RELEASE_CLAIM_FAILED", "Failed to clear claimed_by", 
                    "Topic: " + parent.getResourceName() + ", RowId: " + rowId + ", ClaimVersion: " + claimVersion);
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
            log.warn("Transaction failed for acknowledgment in topic '{}': rowId={}, claimVersion={}", 
                parent.getResourceName(), rowId, claimVersion);
            recordError("ACK_TRANSACTION_FAILED", "Transaction rollback", 
                "Topic: " + parent.getResourceName() + ", RowId: " + rowId + ", ClaimVersion: " + claimVersion);
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
    
    @AfterEach
    void cleanup() throws Exception {
        // Delete all H2 test databases (MUST NOT leave artifacts!)
        Path testDataDir = Paths.get("./test-data");
        if (Files.exists(testDataDir)) {
            Files.walk(testDataDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore - best effort cleanup
                    }
                });
        }
    }
    
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
        ITopicReader<BatchInfo, AckToken> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When
        writer.send(message);
        TopicMessage<BatchInfo, AckToken> received = reader.poll(1, TimeUnit.SECONDS);
        
        // Then
        assertThat(received).isNotNull();
        assertThat(received.payload()).isEqualTo(message);
        assertThat(received.consumerGroup()).isEqualTo("test-group");
        assertThat(received.acknowledgeToken()).isNotNull();
        assertThat(received.acknowledgeToken().rowId()).isGreaterThan(0L);
        assertThat(received.acknowledgeToken().claimVersion()).isEqualTo(1);
        
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
        ITopicReader<BatchInfo, AckToken> readerA = topic.createReaderDelegate(readerContextA);
        ITopicReader<BatchInfo, AckToken> readerB = topic.createReaderDelegate(readerContextB);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When
        writer.send(message);
        
        TopicMessage<BatchInfo, AckToken> receivedA = readerA.poll(1, TimeUnit.SECONDS);
        TopicMessage<BatchInfo, AckToken> receivedB = readerB.poll(1, TimeUnit.SECONDS);
        
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
        
        ITopicReader<BatchInfo, AckToken> reader1 = topic.createReaderDelegate(readerContext);
        ITopicReader<BatchInfo, AckToken> reader2 = topic.createReaderDelegate(readerContext);
        ITopicReader<BatchInfo, AckToken> reader3 = topic.createReaderDelegate(readerContext);
        
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
    
    private void consumeMessages(ITopicReader<BatchInfo, AckToken> reader, Set<Long> processedIds, CountDownLatch latch) {
        try {
            while (latch.getCount() > 0) {
                TopicMessage<BatchInfo, AckToken> msg = reader.poll(100, TimeUnit.MILLISECONDS);
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
        ITopicReader<BatchInfo, AckToken> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Start reader in background thread (blocking on receive)
        CountDownLatch readerReady = new CountDownLatch(1);
        AtomicReference<TopicMessage<BatchInfo, AckToken>> receivedRef = new AtomicReference<>();
        AtomicLong elapsedRef = new AtomicLong(0);
        
        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try {
                readerReady.countDown();  // Signal that reader is ready
                long startTime = System.currentTimeMillis();
                TopicMessage<BatchInfo, AckToken> received = reader.receive();  // Blocking wait
                elapsedRef.set(System.currentTimeMillis() - startTime);
                receivedRef.set(received);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        // Wait for reader to be ready, then write message
        await().atMost(1, TimeUnit.SECONDS).until(() -> readerReady.getCount() == 0);
        long writeTime = System.currentTimeMillis();
        writer.send(message);
        
        // Wait for reader to receive message
        await().atMost(1, TimeUnit.SECONDS).until(() -> receivedRef.get() != null);
        readerFuture.join();
        
        // Then - Message received almost instantly via trigger (not polling)
        TopicMessage<BatchInfo, AckToken> received = receivedRef.get();
        assertThat(received).isNotNull();
        assertThat(received.payload().getSimulationRunId()).isEqualTo("20251014-143025-550e8400");
        assertThat(elapsedRef.get()).isLessThan(100);  // Instant notification (no polling delay)
        
        reader.ack(received);
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
        ITopicReader<BatchInfo, AckToken> reader = topic.createReaderDelegate(readerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("test-run-789")
            .setStorageKey("test-run-789/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Message written with google.protobuf.Any
        writer.send(message);
        TopicMessage<BatchInfo, AckToken> received = reader.poll(1, TimeUnit.SECONDS);
        
        // Then - Type correctly resolved from type URL
        assertThat(received).isNotNull();
        assertThat(received.payload()).isInstanceOf(BatchInfo.class);
        assertThat(received.payload().getSimulationRunId()).isEqualTo("test-run-789");
        assertThat(received.payload().getStorageKey()).isEqualTo("test-run-789/batch_0000000000_0000000100.pb");
        
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
    
    @AfterEach
    void cleanup() throws Exception {
        // Delete all H2 test databases (MUST NOT leave artifacts!)
        Path testDataDir = Paths.get("./test-data");
        if (Files.exists(testDataDir)) {
            Files.walk(testDataDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore - best effort cleanup
                    }
                });
        }
    }
    
    @Test
    @DisplayName("Should persist messages across topic restarts")
    void shouldPersistMessagesAcrossRestarts() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("dbPath = \"./test-data/h2-topic-persistence\"");
        H2TopicResource<BatchInfo> topic1 = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ITopicWriter<BatchInfo> writer = topic1.createWriterDelegate(writerContext);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("test-run-999")
            .setStorageKey("test-run-999/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Write message and close topic
        writer.send(message);
        topic1.close();
        
        // Then - Reopen topic and message is still there
        H2TopicResource<BatchInfo> topic2 = new H2TopicResource<>("test-topic", config);
        ResourceContext readerContext = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        ITopicReader<BatchInfo, AckToken> reader = topic2.createReaderDelegate(readerContext);
        
        TopicMessage<BatchInfo, AckToken> received = reader.poll(1, TimeUnit.SECONDS);
        
        assertThat(received).isNotNull();
        assertThat(received.payload().getSimulationRunId()).isEqualTo("test-run-999");
        
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
            writer.send(BatchInfo.newBuilder()
                .setSimulationRunId("test-run-" + i)
                .setStorageKey(String.format("test-run-%d/batch_0000000000_0000000100.pb", i))
                .setTickStart(0)
                .setTickEnd(100)
                .setWrittenAtMs(System.currentTimeMillis())
                .build());
        }
        
        // Group A processes all messages
        ResourceContext readerContextA = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-a"));
        ITopicReader<BatchInfo, AckToken> readerA = topic.createReaderDelegate(readerContextA);
        
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, AckToken> msg = readerA.poll(1, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            readerA.ack(msg);
        }
        
        // When - New consumer group B joins AFTER group A finished
        ResourceContext readerContextB = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "group-b"));
        ITopicReader<BatchInfo, AckToken> readerB = topic.createReaderDelegate(readerContextB);
        
        // Then - Group B can still process all historical messages
        Set<String> processedIds = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, AckToken> msg = readerB.poll(1, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            processedIds.add(msg.payload().getSimulationRunId());
            readerB.ack(msg);
        }
        
        assertThat(processedIds).containsExactlyInAnyOrder("test-run-1", "test-run-2", "test-run-3");
        
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
        ITopicReader<BatchInfo, AckToken> reader1 = topic.createReaderDelegate(reader1Context);
        ITopicReader<BatchInfo, AckToken> reader2 = topic.createReaderDelegate(reader2Context);
        
        // When - Reader1 claims message but NEVER acknowledges it
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        writer.send(message);
        
        TopicMessage<BatchInfo, AckToken> claimed = reader1.poll(1, TimeUnit.SECONDS);
        assertThat(claimed).isNotNull();
        // Deliberately DO NOT call reader1.ack(claimed) - simulate consumer crash!
        
        // Wait for claim timeout to expire (2 seconds + buffer)
        Thread.sleep(2500);
        
        // Then - Reader2 should be able to claim the stuck message
        TopicMessage<BatchInfo, AckToken> reassigned = reader2.poll(1, TimeUnit.SECONDS);
        assertThat(reassigned).isNotNull();
        assertThat(reassigned.payload()).isEqualTo(message);
        
        // Verify stuck message reassignment metric was incremented
        Map<String, Number> metrics = topic.getMetrics();
        assertThat(metrics.get("stuck_messages_reassigned")).isEqualTo(1L);
        
        // Cleanup
        reader2.ack(reassigned);
        topic.close();
    }
    
    @Test
    @DisplayName("Should reject stale ACK after message was reassigned")
    @ExpectLog(level = ExpectLog.Level.WARN, messagePattern = "Rejected stale ACK.*")
    void shouldRejectStaleAckAfterReassignment() throws Exception {
        // Given - Topic with 1-second claim timeout
        Config config = ConfigFactory.parseString("""
            dbPath = "./test-data/h2-topic-stale-ack"
            claimTimeout = 1
            """);
        H2TopicResource<BatchInfo> topic = new H2TopicResource<>("test-topic", config);
        
        ResourceContext writerContext = new ResourceContext("TestService", "topic-write", Map.of());
        ResourceContext reader1Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        ResourceContext reader2Context = new ResourceContext("TestService", "topic-read", 
            Map.of("consumerGroup", "test-group"));
        
        ITopicWriter<BatchInfo> writer = topic.createWriterDelegate(writerContext);
        ITopicReader<BatchInfo, AckToken> reader1 = topic.createReaderDelegate(reader1Context);
        ITopicReader<BatchInfo, AckToken> reader2 = topic.createReaderDelegate(reader2Context);
        
        // When - Reader1 claims message
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20251014-143025-550e8400")
            .setStorageKey("20251014-143025-550e8400/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        writer.send(message);
        
        TopicMessage<BatchInfo, AckToken> claimed = reader1.poll(1, TimeUnit.SECONDS);
        assertThat(claimed).isNotNull();
        assertThat(claimed.acknowledgeToken().claimVersion()).isEqualTo(1);  // First claim
        
        // Simulate Reader1 freeze (GC pause) - DO NOT ACK yet
        // Wait for timeout to expire (message gets reassigned)
        await().atMost(2, TimeUnit.SECONDS).pollDelay(1500, TimeUnit.MILLISECONDS).until(() -> true);
        
        // Reader2 claims the reassigned message
        TopicMessage<BatchInfo, AckToken> reassigned = reader2.poll(1, TimeUnit.SECONDS);
        assertThat(reassigned).isNotNull();
        assertThat(reassigned.acknowledgeToken().claimVersion()).isEqualTo(2);  // Second claim (incremented!)
        assertThat(reassigned.payload()).isEqualTo(message);
        
        // Reader2 successfully ACKs
        reader2.ack(reassigned);  // This succeeds (claim_version = 2 matches)
        
        // Then - Reader1 "wakes up" and tries to ACK with stale token (claim_version = 1)
        reader1.ack(claimed);  // This should be REJECTED (claim_version mismatch: 1 vs 2)
        
        // Verify error was recorded (STALE_ACK_REJECTED)
        Map<String, Number> metrics = topic.getMetrics();
        assertThat(metrics.get("error_count")).isGreaterThan(0);
        
        // Verify message is not re-readable (already ACKed by reader2)
        TopicMessage<BatchInfo, AckToken> shouldBeNull = reader1.poll(100, TimeUnit.MILLISECONDS);
        assertThat(shouldBeNull).isNull();
        
        // Verify stuck message reassignment metric was incremented
        assertThat(metrics.get("stuck_messages_reassigned")).isEqualTo(1L);
        
        // Cleanup
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
        ITopicReader<BatchInfo, AckToken> reader1 = topic.createReaderDelegate(reader1Context);
        ITopicReader<BatchInfo, AckToken> reader2 = topic.createReaderDelegate(reader2Context);
        
        // When - Reader1 claims message but never acknowledges
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("test-run-999")
            .setStorageKey("test-run-999/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        writer.send(message);
        
        TopicMessage<BatchInfo, AckToken> claimed = reader1.poll(1, TimeUnit.SECONDS);
        assertThat(claimed).isNotNull();
        // DO NOT ack - simulate crash
        
        // Then - Reader2 should NOT be able to claim (timeout disabled)
        // Use Awaitility to verify message stays claimed (no reassignment)
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            TopicMessage<BatchInfo, AckToken> notReassigned = reader2.poll(100, TimeUnit.MILLISECONDS);
            assertThat(notReassigned).isNull();
        });  // No message available!
        
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

- `messages_published` - Total messages written across all writers (AtomicLong)
- `messages_received` - Total messages read across all readers (AtomicLong)
- `messages_acknowledged` - Total messages acknowledged (AtomicLong)
- `write_throughput_per_sec` - Write rate (SlidingWindowCounter with configurable window, default 60s)
- `read_throughput_per_sec` - Read rate (SlidingWindowCounter with configurable window, default 60s)
- `stuck_messages_reassigned` - Count of messages reassigned after claim timeout (AtomicLong, H2-specific)

### 14.2 Delegate Metrics (per service)

**Writer Delegates (AbstractTopicDelegateWriter):**
- `messages_sent` - Messages sent by this delegate (AtomicLong)
- `write_throughput_per_sec` - Write rate for this delegate (SlidingWindowCounter)
- `write_errors` - Write errors (H2-specific, AtomicLong)

**Reader Delegates (AbstractTopicDelegateReader):**
- `messages_received` - Messages received by this delegate (AtomicLong)
- `read_throughput_per_sec` - Read rate for this delegate (SlidingWindowCounter)
- `read_errors` - Read errors (H2-specific, AtomicLong)
- `ack_errors` - ACK errors (H2-specific, AtomicLong)
- `stale_acks_rejected` - Stale ACKs rejected (H2-specific, AtomicLong)
- `claim_conflict_ratio` - Ratio of failed claim attempts to total attempts (O(1), sliding window, default 5s, H2-specific)

---

## 15. Implementation Notes

### 15.1 Key Design Decisions

1. **HikariCP Connection Pooling:** 
   - Follows same pattern as `H2Database` resource for consistency
   - System-wide connection limits via `maxPoolSize` configuration
   - Connection metrics via HikariCP MXBean
   - Each delegate holds one connection for its lifetime

2. **PreparedStatement Pooling:**
   - Each delegate prepares SQL statements ONCE after schema is set (lazy initialization in `onSimulationRunSet()`)
   - Writer: 1 statement (INSERT), Reader: 4 statements (SELECT, INSERT claim, UPDATE claim, ACK)
   - Eliminates SQL parsing overhead on every operation (first call ~85ms, subsequent <5ms)
   - Connection and statements held for delegate lifetime, returned to pool on close

3. **Atomic Claim-Based Delivery with Version Check:** 
   - Two-step algorithm: `SELECT` up to 10 candidates (no locking), then `INSERT`/conditional `UPDATE` loop
   - `claim_version` increment on each claim prevents stale ACKs
   - Version check on ACK ensures at-most-once semantics within consumer group
   - Stale ACKs (after reassignment) are rejected with WARN log and error recording

4. **Stuck Message Reassignment:** Configurable `claimTimeout` automatically reassigns stuck messages (idempotency required!)

5. **Performance Optimized Queries:** LEFT JOIN instead of NOT IN + composite indexes for O(log n) performance

6. **H2 over Chronicle:** Simpler architecture, multi-writer support, explicit ACK, HikariCP pooling

7. **Separate Files:** All classes in separate files (no inner classes)

8. **Event-Driven Delivery:** H2 triggers + BlockingQueue for instant notifications (no polling!)

9. **Direct SQL:** No internal queue or writer thread needed (H2 MVCC + HikariCP handle concurrency)

10. **Junction Table:** Proper consumer group isolation via `topic_consumer_group` table with per-group claim/ack state

11. **Competing Consumers:** Atomic `INSERT`/conditional `UPDATE` for automatic load balancing within consumer groups

12. **In-Flight Tracking:** `claimed_by`, `claimed_at`, and `claim_version` columns in `topic_consumer_group` for stuck message detection and reassignment

13. **Permanent Storage:** Messages never deleted (enables historical replay and new consumer groups)

14. **Dynamic Type Resolution:** Uses `google.protobuf.Any` type URL (no `messageType` config needed)

15. **Single Validation Point:** Consumer group validation occurs only in `AbstractTopicDelegateReader` constructor to avoid DRY violation and ensure consistent error messages across all implementations (H2, Chronicle, Kafka)

16. **Transaction Management:**
   - **Writer:** Auto-commit mode explicitly set in constructor (defensive programming, no HikariCP default assumptions)
   - **Single INSERT is atomic:** No explicit transaction needed (ACID guarantee)
   - **Reader Claim:** No transaction - `SELECT` (no locking), then atomic `INSERT` or conditional `UPDATE` per candidate
   - **Reader ACK:** Explicit transaction (2 SQL statements must be atomic: SELECT message_id + UPDATE with version check)
   - Ensures all-or-nothing semantics for acknowledgment (no partial ACKs)

17. **Shared Table Structure per Run (Not Cross-Run Centralization):**
   - **Per-run schema isolation:** Each simulation run has its own schema (e.g., `SIM_20251006_UUID`)
   - **Within each run's schema:**
     - **Single `topic_messages` table** for all topics in that run (no per-topic tables)
     - **Single `topic_consumer_group` table** for all topics and consumer groups in that run
   - **`topic_name` column** provides logical partitioning within each run's tables
   - **Composite indexes** include `topic_name` for partition-like performance
   - **Unlimited topics per run:** Add topics without schema changes or new tables
   - **Shared trigger per run:** Single H2 trigger routes notifications by `topic_name` (schema-qualified key)
   - **Data isolation:** Run 1's messages are in `SIM_RUN1.topic_messages`, Run 2's in `SIM_RUN2.topic_messages` - completely separate!

18. **Schema Management (Simulation Run Isolation):**
   - **`ISimulationRunAwareTopic` interface:** Topic-specific interface for run-aware resources
   - **`H2SchemaUtil` utility:** Centralized H2 schema operations (shared with `H2Database`)
   - **Per-run schemas:** Each simulation run uses its own H2 schema (e.g., `SIM_20251006143025_...`)
   - **Schema creation:** `createSchemaIfNotExists()` with H2 bug workaround (concurrent creation safe)
   - **Schema switching:** `setSchema()` called in `onSimulationRunSet()` template method
   - **Delegate lifecycle:** Schema set once during `setSimulationRun()`, before any read/write operations
   - **Consistent with H2Database:** Same schema naming and management logic across all H2 resources

19. **Resource Management (AutoCloseable Delegates):**
   - **Hybrid Pattern:** Delegates implement `AutoCloseable` to support both long-lived and try-with-resources patterns
   - **Long-lived (Recommended for Services):**
     - Create delegate once, use many times, close manually
     - Connection and PreparedStatements cached for delegate lifetime
     - Best performance: ~30-50% faster than connection-per-operation
     - Use case: PersistenceService, IndexerServices (continuous operation)
   - **Try-with-resources (Recommended for One-off Operations):**
     - Auto-cleanup per operation, no manual close() needed
     - Best safety: No connection leaks, even on exceptions
     - Performance overhead: ~1-2ms per operation (connection acquisition)
     - Use case: CLI tools, admin scripts, testing
   - **Performance Context:**
     - Topic message processing: ~0.5ms (0.1% of total batch processing time)
     - Even with 10x overhead (5ms), still negligible compared to storage I/O (~100ms) and DB inserts (~500ms)
     - Long-lived pattern recommended for services, but overhead is acceptable for safety-critical scenarios

### 15.2 Performance Optimizations

**Query Optimization:**
- **LEFT JOIN vs NOT IN:** LEFT JOIN allows index usage on `topic_consumer_group.message_id`
- **Composite Indexes (H2-Compatible):**
  - `idx_topic_unclaimed (topic_name, claimed_by, id)` - Fast unclaimed message lookup
  - `idx_topic_claimed (topic_name, claimed_by, claimed_at)` - Stuck message timeout checks
  - `idx_topic_claim_status (topic_name, claimed_by, claimed_at)` - OR-condition filtering
  - **Note:** H2 does NOT support partial indexes with `WHERE` clauses (PostgreSQL-only feature)
  - **Solution:** Include `claimed_by` as first indexed column - H2 optimizer efficiently uses these for `IS NULL` / `IS NOT NULL` predicates

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
- [ ] H2TopicResource initializes `topic_messages` (with claimed_by/claimed_at) and `topic_consumer_group` tables correctly
- [ ] **Performance indexes created:** `idx_claimed_at`, `idx_claim_status` (composite), `idx_unclaimed`
- [ ] **LEFT JOIN query:** Reader uses LEFT JOIN instead of NOT IN for better performance
- [ ] H2 trigger created and registered correctly for event-driven notifications
- [ ] Writers can send messages concurrently (multi-writer test)
- [ ] Readers receive instant notifications via H2 trigger (no polling delay)
- [ ] **Atomic claim:** `SELECT + INSERT/UPDATE` prevents race conditions (no duplicate processing!)
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
- [ ] `atomic INSERT/UPDATE` distributes load automatically
- [ ] Acknowledgment inserts into `topic_consumer_group` table (not DELETE from messages)
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
| **Message Delivery** | Polling (queue drain loop) | Atomic Claim (`SELECT + INSERT/UPDATE`) |
| **Race Conditions** | Possible (read + ACK not atomic) | Impossible (single SQL statement) |
| **Latency** | Ultra-fast (μs) | Instant notification (ms) |
| **Complexity** | High (inner classes, thread) | Low (separate files, JDBC, triggers) |
| **Consumer Groups** | Tailer per group (complex) | Junction table (simple SQL) |
| **Competing Consumers** | Manual coordination needed | `atomic INSERT/UPDATE` (automatic) |
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
   - `atomic INSERT/UPDATE` distributes messages automatically
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

## 20. Implementation Plan

### 20.1 Overview

**Strategy:** Bottom-Up + Test-First Development

**Principles:**
1. ✅ Compilable after every step - No broken builds
2. ✅ Testable as early as possible - Unit tests before integration tests
3. ✅ Incremental - Small, verifiable steps
4. ✅ Independently executable - Each phase can be tested in isolation

**Estimated Time:** 1-2 days (with comprehensive tests)

---

### 20.2 Phase 1: Foundation (Interfaces & Core Types)

**Goal:** Compilable skeleton without logic

#### Step 1.1: ISimulationRunAwareTopic Interface
```bash
./gradlew compileJava
```

**Files:**
- Create: `api/resources/topics/ISimulationRunAwareTopic.java`

**Verification:**
- ✅ Compiles successfully

---

#### Step 1.2: ITopicWriter & ITopicReader Interfaces
```bash
./gradlew compileJava
```

**Files:**
- Create: `api/resources/topics/ITopicWriter.java`
- Create: `api/resources/topics/ITopicReader.java`
- Both extend `ISimulationRunAwareTopic + AutoCloseable`

**Verification:**
- ✅ Compiles successfully

---

#### Step 1.3: TopicMessage Value Class
```bash
./gradlew compileJava
```

**Files:**
- Create: `api/resources/topics/TopicMessage.java`
- With all getters (including JavaDoc!)

**Verification:**
- ✅ Compiles successfully

---

#### Step 1.4: Protobuf Contracts
```bash
./gradlew generateProto compileJava
```

**Files:**
- Extend: `notification_contracts.proto`
- Add: `TopicEnvelope`, `BatchInfo`, `MetadataInfo`

**Verification:**
- ✅ Protobuf generated + compiles

**✅ Checkpoint 1:** All interfaces + value classes compile

---

### 20.3 Phase 2: Abstract Base Classes (Template Methods)

**Goal:** Reusable logic without backend specifics

#### Step 2.1: ReceivedEnvelope Record
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/ReceivedEnvelope.java`
- Simple `record ReceivedEnvelope<ACK>(TopicEnvelope envelope, ACK ackToken)`

**Verification:**
- ✅ Compiles

---

#### Step 2.2: AbstractTopicResource (Skeleton)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/AbstractTopicResource.java`
- Fields + constructor + abstract methods ONLY
- NO implementation yet

**Verification:**
- ✅ Compiles (abstract class)

---

#### Step 2.3: AbstractTopicDelegate (Skeleton)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/AbstractTopicDelegate.java`
- Constructor + `getWrappedResource()` only

**Verification:**
- ✅ Compiles

---

#### Step 2.4: AbstractTopicDelegateWriter (Template Method)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/AbstractTopicDelegateWriter.java`
- Implement: `send()` → `wrapMessage()` → `sendEnvelope()`
- `sendEnvelope()` = abstract

**Verification:**
- ✅ Compiles

---

#### Step 2.5: AbstractTopicDelegateReader (Template Method)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/AbstractTopicDelegateReader.java`
- Implement: `receive()` / `poll()` / `ack()`
- Template methods: `receiveEnvelope()`, `acknowledgeMessage()` = abstract
- `unwrapEnvelope()` with dynamic type resolution

**Verification:**
- ✅ Compiles

**✅ Checkpoint 2:** All abstract classes compile + template methods defined

---

### 20.4 Phase 3: H2 Schema Utilities

**Goal:** Schema management independently testable

#### Step 3.1: H2SchemaUtil (Unit Testable!)
```bash
./gradlew test --tests '*H2SchemaUtilTest'
```

**Files:**
- Create: `utils/H2SchemaUtil.java`
  - `toSchemaName()`
  - `createSchemaIfNotExists()`
  - `setSchema()`
- Create: `H2SchemaUtilTest.java` (in-memory H2)

**Tests:**
- `shouldSanitizeSchemaName()`
- `shouldCreateSchemaIfNotExists()`
- `shouldSwitchSchema()`

**Verification:**
- ✅ All tests pass (< 0.2s each)

**✅ Checkpoint 3:** Schema utils tested with in-memory H2

---

### 20.5 Phase 4: H2TopicResource (Core Infrastructure)

**Goal:** Resource without delegates, but with trigger

#### Step 4.1: H2TopicResource (Minimal)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/H2TopicResource.java`
- Constructor + HikariCP setup
- `createTables()` implementation
- NO delegates yet

**Verification:**
- ✅ Compiles

---

#### Step 4.2: H2InsertTrigger (Event-Driven)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/H2InsertTrigger.java`
- Trigger with `notificationQueues` registry
- `registerNotificationQueue()` / `deregisterNotificationQueue()`

**Verification:**
- ✅ Compiles

---

#### Step 4.3: H2TopicResource Tests (Unit)
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldInitializeDatabase'
```

**Files:**
- Create: `H2TopicResourceTest.java`

**Tests:**
- `shouldInitializeDatabase()`
  - Tables exist
  - Indexes exist
  - Trigger registered

**Verification:**
- ✅ Setup works (no messages yet!)

**✅ Checkpoint 4:** H2 database + trigger works (without messages)

---

### 20.6 Phase 5: H2 Writer (Send Messages)

**Goal:** Write messages + verify

#### Step 5.1: H2TopicWriterDelegate
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/H2TopicWriterDelegate.java`
- Implement: `sendEnvelope()`
- With PreparedStatement pooling

**Verification:**
- ✅ Compiles

---

#### Step 5.2: H2TopicResource.createWriterDelegate()
```bash
./gradlew compileJava
```

**Files:**
- Implement factory method in `H2TopicResource.java`

**Verification:**
- ✅ Compiles

---

#### Step 5.3: Writer Tests (Unit)
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldWriteMessage'
```

**Tests:**
- `shouldWriteMessage()`
  - Message in DB
  - Metrics updated
  - Trigger fired (queue not empty)

**Verification:**
- ✅ Test passes

**✅ Checkpoint 5:** Writer works, messages visible in DB

---

### 20.7 Phase 6: H2 Reader (Read Messages)

**Goal:** Read messages without ACK

#### Step 6.1: H2TopicReaderDelegate (Claim Only)
```bash
./gradlew compileJava
```

**Files:**
- Create: `resources/topics/H2TopicReaderDelegate.java`
- Implement: `receiveEnvelope()` (with claim)
- `acknowledgeMessage()` = empty stub (TODO)

**Verification:**
- ✅ Compiles

---

#### Step 6.2: H2TopicResource.createReaderDelegate()
```bash
./gradlew compileJava
```

**Files:**
- Implement factory method in `H2TopicResource.java`

**Verification:**
- ✅ Compiles

---

#### Step 6.3: Reader Tests (Claim Only)
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldReadMessage'
```

**Tests:**
- `shouldReadMessage()`
  - Message readable
  - `claimed_by` set
  - `claim_version` = 1

**Verification:**
- ✅ Test passes

**✅ Checkpoint 6:** Reader can claim messages (but not ACK yet)

---

### 20.8 Phase 7: Acknowledgment (Complete Workflow)

**Goal:** Full message lifecycle

#### Step 7.1: H2TopicReaderDelegate.acknowledgeMessage()
```bash
./gradlew compileJava
```

**Files:**
- Implement full ACK (3 SQL statements) in `H2TopicReaderDelegate.java`
- With transaction management
- With stale ACK detection

**Verification:**
- ✅ Compiles

---

#### Step 7.2: ACK Tests (Unit)
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldAcknowledgeMessage'
```

**Tests:**
- `shouldAcknowledgeMessage()`
  - Message in `topic_consumer_group`
  - `claimed_by` = NULL (released)
  - Metrics updated

**Verification:**
- ✅ Test passes

---

#### Step 7.3: End-to-End Test
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldWriteAndReadMessage'
```

**Tests:**
- `shouldWriteAndReadMessage()`
  - Write → Read → ACK → No longer readable

**Verification:**
- ✅ Full lifecycle works

**✅ Checkpoint 7:** Complete message lifecycle functional

---

### 20.9 Phase 8: Consumer Groups (Pub/Sub)

**Goal:** Multiple consumer groups independently

#### Step 8.1: Consumer Group Tests
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldSupportMultipleConsumerGroups'
```

**Tests:**
- Both groups receive message
- ACK from group A doesn't affect group B

**Verification:**
- ✅ Test passes

**✅ Checkpoint 8:** Consumer groups isolated

---

### 20.10 Phase 9: Competing Consumers (Load Balancing)

**Goal:** `atomic INSERT/UPDATE` works

#### Step 9.1: Competing Consumer Tests
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldHandleCompetingConsumers'
```

**Tests:**
- 10 messages, 3 readers, all messages exactly once

**Verification:**
- ✅ Test passes

**✅ Checkpoint 9:** Load balancing functional

---

### 20.11 Phase 10: Stuck Message Reassignment

**Goal:** Timeout + claim version

#### Step 10.1: Reassignment Tests
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldReassignStuckMessages'
```

**Tests:**
- Message reassignable after timeout
- Metric `stuck_messages_reassigned` incremented

**Verification:**
- ✅ Test passes

---

#### Step 10.2: Stale ACK Tests
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldRejectStaleAckAfterReassignment'
```

**Tests:**
- Old consumer cannot ACK
- `STALE_ACK_REJECTED` error

**Verification:**
- ✅ Test passes

**✅ Checkpoint 10:** Stuck message handling works

---

### 20.12 Phase 11: Schema Isolation (Simulation Runs)

**Goal:** Each run has own schema

#### Step 11.1: Schema Tests
```bash
./gradlew test --tests '*H2TopicResourceTest.shouldIsolateSimulationRuns'
```

**Tests:**
- Run1 sees only own messages
- Trigger registry per schema

**Verification:**
- ✅ Test passes

**✅ Checkpoint 11:** Run isolation functional

---

### 20.13 Phase 12: Integration Tests

**Goal:** Persistence, historical replay

#### Step 12.1: Persistence Test
```bash
./gradlew test --tests '*H2TopicIntegrationTest.shouldPersistMessagesAcrossRestarts'
```

**Tests:**
- Write → Close → Reopen → Read

**Verification:**
- ✅ Test passes

---

#### Step 12.2: Historical Replay Test
```bash
./gradlew test --tests '*H2TopicIntegrationTest.shouldAllowNewConsumerGroupsToProcessHistoricalMessages'
```

**Tests:**
- Group A processes all
- Group B joins later, still processes all

**Verification:**
- ✅ Test passes

**✅ Checkpoint 12:** All integration tests pass

---

### 20.14 Phase 13: Performance & Cleanup

#### Step 13.1: Performance Tests
```bash
./gradlew test --tests '*H2TopicPerformanceTest'
```

**Tests:**
- 1000 messages write < 1s
- Competing consumers load balance

**Verification:**
- ✅ Performance acceptable

---

#### Step 13.2: Final Review
```bash
./gradlew clean build
```

**Verification:**
- ✅ All tests pass
- ✅ No warnings
- ✅ JaCoCo coverage > 60%

**✅ Final Checkpoint:** Production-ready!

---

### 20.15 Test Execution Order (Fast Feedback)

```bash
# Phase 1-4: Foundation (fast, no I/O)
./gradlew test --tests '*H2SchemaUtilTest'

# Phase 5-7: Core Workflow
./gradlew test --tests '*H2TopicResourceTest.shouldWriteMessage'
./gradlew test --tests '*H2TopicResourceTest.shouldReadMessage'
./gradlew test --tests '*H2TopicResourceTest.shouldWriteAndReadMessage'

# Phase 8-11: Advanced Features
./gradlew test --tests '*H2TopicResourceTest.shouldSupport*'
./gradlew test --tests '*H2TopicResourceTest.shouldHandle*'
./gradlew test --tests '*H2TopicResourceTest.shouldReassign*'
./gradlew test --tests '*H2TopicResourceTest.shouldReject*'

# Phase 12: Integration
./gradlew test --tests '*H2TopicIntegrationTest'

# Final: Everything
./gradlew clean build
```

---

### 20.16 Benefits of This Strategy

1. ✅ **Compiles after every step** - No "broken build" state
2. ✅ **Unit tests first** - Fast feedback (< 0.2s per test)
3. ✅ **Integration tests later** - Only when unit tests pass
4. ✅ **Incremental** - Each phase builds on previous
5. ✅ **Independent** - Parallel development possible (e.g., Writer + Reader)
6. ✅ **Early detection** - Errors caught immediately, not at the end

---

### 20.17 Parallel Development Opportunities

**Team Size 2:**
- Developer A: Phases 1-4 (Foundation + Infrastructure)
- Developer B: Phase 3 (H2SchemaUtil) in parallel

**Team Size 3:**
- Developer A: Phases 1-2 (Interfaces + Abstract Classes)
- Developer B: Phases 3-4 (Schema Utils + H2TopicResource)
- Developer C: Write tests ahead of implementation

**Solo Development:**
- Follow phases sequentially
- Frequent commits after each checkpoint
- Run tests continuously

---

*End of Specification*

