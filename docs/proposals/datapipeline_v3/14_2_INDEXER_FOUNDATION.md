# Data Pipeline V3 - Indexer Foundation (Phase 14.2)

## Overview

This document outlines the topic-based publish/subscribe architecture for the Data Pipeline V3 indexer infrastructure.

## Approach
```
PersistenceService → Storage.writeBatch()
                  → Topic.send(BatchInfo{filename, ticks})
                         ↓
Indexer → Topic.receive() [BLOCKING, no polling!]
       → IIdempotencyTracker.checkAndMarkProcessed()
       → Process batch
       → Topic.ack()
```

**Key Features:**
- ✅ Instant notification (blocking receive, no polling)
- ✅ Reliable delivery (Topic guarantees)
- ✅ Competing consumers via Consumer Groups (built-in)
- ✅ At-least-once delivery + Idempotency = Exactly-once semantics

## Implementation Phases

### Phase 14.2.1: Topic Infrastructure

**Goal:** Implement the core topic abstraction with Chronicle Queue backend.

**Deliverables:**
- `ITopicWriter<T extends Message>`, `ITopicReader<T extends Message>` interfaces
- `TopicMessage<T extends Message>` wrapper
- Protobuf contracts: `BatchInfo`, `MetadataInfo`
- `AbstractTopicResource<T extends Message>` with Template Method pattern
- `AbstractTopicDelegate<P extends AbstractTopicResource<?>>` for type-safe parent access
- `ChronicleTopicResource<T extends Message>` with inner delegates
- Multi-writer safety via internal queue
- Consumer Groups for competing consumers
- O(1) monitoring with `SlidingWindowCounter`

**Resources Created:**
- `metadata-topic` (MetadataInfo messages)
- `batch-topic` (BatchInfo messages)

**Spec:** [14_2_1_TOPIC_INFRASTRUCTURE.md](14_2_1_TOPIC_INFRASTRUCTURE.md)

---

### Phase 14.2.2: Metadata Notification (Write)

**Goal:** `MetadataPersistenceService` publishes metadata availability to `metadata-topic`.

**Changes:**
- Add `ITopicWriter<MetadataInfo>` resource binding
- After successful `storage.writeMessage()`, send MetadataInfo notification
- Include: runId, storageKey, writtenAtMs

**Deliverables:**
- Updated `MetadataPersistenceService`
- Configuration changes
- Integration tests

---

### Phase 14.2.3: Metadata Notification (Read)

**Goal:** `MetadataIndexer` consumes from `metadata-topic` instead of scanning storage.

**Changes:**
- Replace storage scanning with `ITopicReader<MetadataInfo>`
- Consumer group: `"metadata-indexer"`
- Blocking receive (no polling!)
- Acknowledge after processing

**Deliverables:**
- Updated `MetadataIndexer`
- Remove old storage scanning logic
- Integration tests

---

### Phase 14.2.4: Batch Notification (Write)

**Goal:** `PersistenceService` publishes batch availability to `batch-topic`.

**Changes:**
- Add `ITopicWriter<BatchInfo>` resource binding
- After successful `storage.writeBatch()`, send BatchInfo notification
- Include: runId, storageKey, tickStart, tickEnd, writtenAtMs

**Deliverables:**
- Updated `PersistenceService`
- Configuration changes
- Integration tests

---

### Phase 14.2.5: DummyIndexer Topic Read + Loop

**Goal:** `DummyIndexer` reads from `batch-topic` in a continuous loop (log-only, no storage read yet).

**Changes:**
- Add `ITopicReader<BatchInfo>` resource binding
- Consumer group: `"dummy-indexer"`
- `indexRun()` becomes a loop: `while (!interrupted) { receive() → LOG → ack() }`
- **NO storage read, NO buffering, NO idempotency yet**

**Deliverables:**
- Updated `DummyIndexer` with continuous loop
- DEBUG logs for received BatchInfo
- Integration tests

---

### Phase 14.2.6: Tick Buffering

**Goal:** `DummyIndexer` reads actual batch data from storage and uses `TickBufferingComponent`.

**Changes:**
- Add `TickBufferingComponent` to `DummyIndexer`
- After `receive()`, read batch from storage using `storageKey`
- Buffer ticks, flush when complete tick received
- DEBUG logs for buffered/flushed ticks

**Deliverables:**
- `TickBufferingComponent` (if not exists)
- Updated `DummyIndexer` with storage reads
- Integration tests

---

### Phase 14.2.7: Idempotency + DLQ

**Goal:** Add idempotency checks and Dead Letter Queue for failed batches.

**Changes:**
- Add `IdempotencyComponent` to `DummyIndexer`
- Before processing, check `idempotency.checkAndMarkProcessed()`
- Skip duplicates, log appropriately
- Add `IDeadLetterQueueResource` binding
- On processing failure: send to DLQ, log error, continue loop
- **DO NOT stop on errors** - resilient continuous processing

**Deliverables:**
- `IdempotencyComponent` (if not exists)
- `IDeadLetterQueueResource` integration
- Updated `DummyIndexer` with error handling
- Integration tests (duplicates, failures)

---

## Component Architecture

**Indexer Components (Composable):**
- `MetadataReadingComponent` - Loads simulation metadata (existing)
- `TopicConsumerComponent` - Consumes from topic (implicit via `ITopicReader` binding)
- `IdempotencyComponent` - Tracks processed batches (Phase 14.2.7)
- `TickBufferingComponent` - Buffers incomplete ticks (Phase 14.2.6)
- `StorageReadingComponent` - Reads batches from storage (Phase 14.2.6)

**Indexer Types:**
- `MetadataIndexer` - Uses: MetadataReadingComponent + TopicConsumer
- `DummyIndexer` - Uses: All components (for testing)
- `EnvironmentIndexer` - Uses: All components + environment indexing logic (future)

## Configuration Example

```hocon
resources {
  # Topics
  metadata-topic {
    className = "org.evochora.datapipeline.resources.topics.ChronicleTopicResource"
    options {
      messageType = "org.evochora.datapipeline.api.contracts.NotificationContracts$MetadataInfo"
      queueDirectory = "data/topics/metadata"
      retentionDays = 7
      internalQueueCapacity = 1000
      metricsWindowSeconds = 5
    }
  }
  
  batch-topic {
    className = "org.evochora.datapipeline.resources.topics.ChronicleTopicResource"
    options {
      messageType = "org.evochora.datapipeline.api.contracts.NotificationContracts$BatchInfo"
      queueDirectory = "data/topics/batches"
      retentionDays = 7
      internalQueueCapacity = 10000
      metricsWindowSeconds = 5
    }
  }
}

services {
  # Writer (Phase 14.2.2)
  metadata-persistence-service {
    className = "org.evochora.datapipeline.services.persistence.MetadataPersistenceService"
    resources {
      metadata-out = "topic-write:metadata-topic"
    }
  }
  
  # Reader (Phase 14.2.3)
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
      batch-in = "topic-read:batch-topic?consumerGroup=dummy-indexer"
      dlq = "queue-out:dead-letter-queue"  # Phase 14.2.7
    }
  }
}
```

## Testing Strategy

**Each phase includes:**
- Unit tests for changed components
- Integration tests for end-to-end flow
- No `Thread.sleep()` - use Awaitility
- Proper cleanup (Chronicle Queue directories, database state)
- Specific `@ExpectLog` patterns for WARN/ERROR

**DummyIndexer Evolution:**
- Phase 14.2.5: Log-only loop (`receive() → LOG → ack()`)
- Phase 14.2.6: Add storage reads + buffering
- Phase 14.2.7: Add idempotency + DLQ + error handling

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
DEBUG Received BatchInfo from topic 'batch-topic': runId={}, tickStart={}, tickEnd={}
DEBUG Skipping duplicate batch: storageKey={}
DEBUG Flushed buffered tick {} with {} organisms
WARN  Failed to process batch {}: {}, sending to DLQ
```

## Success Criteria

Upon completion of all phases:
- ✅ `MetadataPersistenceService` → `metadata-topic` → `MetadataIndexer`
- ✅ `PersistenceService` → `batch-topic` → `DummyIndexer`
- ✅ DummyIndexer runs continuously (loop, not one-shot)
- ✅ Idempotency ensures exactly-once processing
- ✅ DLQ handles failed batches
- ✅ All tests pass (<1 minute total)
- ✅ Monitoring: O(1) metrics for topics, components

## Implementation Notes

**No backward compatibility** - this is a new implementation.

**Implementation steps:**
1. Implement all phases sequentially
2. Test each phase independently
3. Update documentation as needed

**Rollback strategy:**
- Each phase is self-contained and testable
- If issues arise, revert to previous phase
- Chronicle Queue provides message persistence (no data loss)

---

**Phase Specs:**
- [14_2_1_TOPIC_INFRASTRUCTURE.md](14_2_1_TOPIC_INFRASTRUCTURE.md)
- [14_2_2_METADATA_NOTIFICATION_WRITE.md](14_2_2_METADATA_NOTIFICATION_WRITE.md) - TBD
- [14_2_3_METADATA_NOTIFICATION_READ.md](14_2_3_METADATA_NOTIFICATION_READ.md) - TBD
- [14_2_4_BATCH_NOTIFICATION_WRITE.md](14_2_4_BATCH_NOTIFICATION_WRITE.md) - TBD
- [14_2_5_DUMMYINDEXER_TOPIC_LOOP.md](14_2_5_DUMMYINDEXER_TOPIC_LOOP.md) - TBD
- [14_2_6_TICK_BUFFERING.md](14_2_6_TICK_BUFFERING.md) - TBD
- [14_2_7_IDEMPOTENCY_DLQ.md](14_2_7_IDEMPOTENCY_DLQ.md) - TBD

