# Distributed Setup Architecture

**Status**: Planned (Phase 2)
**Prerequisites**: Indexers, API Service, Web Client (Phase 1)
**Date**: 2025-10-06

## Table of Contents
1. [Overview](#overview)
2. [Architecture Comparison](#architecture-comparison)
3. [Technology Decisions](#technology-decisions)
4. [Performance Analysis](#performance-analysis)
5. [Interface Validation](#interface-validation)
6. [Testing Strategy](#testing-strategy)
7. [Implementation Plan](#implementation-plan)
8. [Configuration](#configuration)

---

## Overview

This document describes the distributed deployment architecture for Evochora's data pipeline, enabling:
- **Horizontal scaling** of persistence and indexing services across multiple machines
- **Separation of concerns** between compute-intensive simulation and I/O-intensive persistence
- **High throughput** handling of large simulation data streams (200-1500 MB/sec uncompressed)

### Key Principle: Identical Service Code

Services (PersistenceService, Indexers) run **identical code** in both in-process and distributed scenarios. Only resource implementations change (queue and storage backends).

---

## Architecture Comparison

### Scenario A: In-Process (Current)

```
Single Machine (16-64 cores)
┌─────────────────────────────────────────────────┐
│                                                 │
│  SimulationEngine (1 instance)                  │
│    ↓ put(tickData)                              │
│                                                 │
│  InMemoryBlockingQueue<TickData>               │
│    - Stores: Java objects (unserialized)        │
│    - Memory: ~4 GB per 1000 ticks               │
│    ↓ drainTo(batch, 1000, 5sec)                 │
│                                                 │
│  PersistenceService (3 instances, competing)    │
│    - Competing consumers on same queue          │
│    - Each uses 1 core                           │
│    ↓ openWriter() + writeMessage()              │
│                                                 │
│  FileSystemStorageResource                      │
│    - Serializes: TickData → protobuf bytes      │
│    - Compresses: Zstd level 3 (~145x ratio)     │
│    - Writes: /data/sim-123/batch_000_999.pb.zst │
│                                                 │
└─────────────────────────────────────────────────┘

Throughput: ~1,000 ticks/sec (limited by single machine I/O)
Memory: ~12 GB (3 instances × 4 GB)
Suitable for: Development, small simulations, testing
```

### Scenario B: Distributed (Planned)

```
Machine 1 (All cores for simulation)
┌─────────────────────────────────────────────────┐
│  SimulationEngine (1 instance)                  │
│    ↓ put(tickData) ← Fast: just buffer add      │
│                                                 │
│  RedpandaQueueAdapter (producer)                │
│    Background thread (uses ~2 cores):           │
│    - Serialization: TickData → protobuf bytes   │
│    - Batching: Collect ~280 ticks (1 MB batch)  │
│    - Compression: Zstd level 1 (fast)           │
│    - Network send: ~25 KB compressed per batch  │
└────────┬────────────────────────────────────────┘
         │ Network (1 Gbps = 125 MB/sec)
         │ With compression: ~18 GB/sec uncompressed capacity
         ▼
┌─────────────────────────────────────────────────┐
│  Redpanda Broker (Separate machine/container)   │
│    - Stores: Compressed batches                 │
│    - Single partition: Total ordering preserved │
│    - Consumer group: "persistence-service"      │
└────────┬────────────────────────────────────────┘
         │ Network
         │
         ▼
Machines 2, 3, 4... (Dedicated to persistence/indexing)
┌─────────────────────────────────────────────────┐
│  RedpandaQueueAdapter (consumer)                │
│    - Decompression (automatic)                  │
│    - Deserialization: bytes → TickData objects  │
│    ↓ drainTo(batch, 1000, 5sec)                 │
│                                                 │
│  PersistenceService (N instances, competing)    │
│    - Same code as in-process!                   │
│    - Competing consumers via consumer group     │
│    ↓ openWriter() + writeMessage()              │
│                                                 │
│  S3StorageResource                              │
│    - Serializes: TickData → protobuf bytes      │
│    - Compresses: Zstd level 3 (~145x ratio)     │
│    - Writes: s3://bucket/sim-123/batch_*.pb.zst │
│    - Multipart upload: Atomic commits           │
└─────────────────────────────────────────────────┘

Throughput: 10,000+ ticks/sec (scales with persistence instances)
Memory: 25 MB per persistence instance (vs 4 GB in-process)
Suitable for: Production, large simulations, high throughput
```

---

## Technology Decisions

### Queue: Redpanda (Preferred) over Kafka, Redis Streams, ActiveMQ

**Decision**: Use **Redpanda** with Kafka-compatible API

| Feature | Redpanda | Kafka | Redis Streams | ActiveMQ |
|---------|----------|-------|---------------|----------|
| **Throughput** | 100s MB/sec ✅ | 100s MB/sec ✅ | 100s MB/sec ✅ | 10s MB/sec ❌ |
| **Competing Consumers** | Consumer groups ✅ | Consumer groups ✅ | Consumer groups ✅ | Yes ✅ |
| **Total Ordering** | Single partition ✅ | Single partition ✅ | Single stream ✅ | Yes ✅ |
| **Compression** | Built-in (zstd) ✅ | Built-in (zstd) ✅ | No ❌ | Limited ⚠️ |
| **Implementation** | C++ (native) ✅✅ | JVM ⚠️ | C ✅ | JVM ⚠️ |
| **Dependencies** | None ✅✅ | Zookeeper/KRaft ❌ | None ✅ | None ✅ |
| **Memory** | ~200 MB ✅ | ~500 MB ⚠️ | ~50 MB ✅✅ | ~300 MB ⚠️ |
| **Latency** | 1-10ms ✅✅ | 10-50ms ⚠️ | <1ms ✅✅✅ | 10-50ms ⚠️ |
| **Startup Time** | ~2 seconds ✅✅ | ~10 seconds ❌ | <1 second ✅✅✅ | ~5 seconds ⚠️ |

**Why Redpanda?**
1. **Kafka-compatible API**: Use standard `kafka-clients` library, minimal adapter code
2. **C++ implementation**: 50% lower memory, 10× lower latency than Kafka
3. **Zero dependencies**: Single binary, no Zookeeper/KRaft cluster
4. **Built-in compression**: Transparent zstd compression for network transport
5. **Testcontainers support**: Fast integration tests (~2 second startup)

**Why not Kafka?**
- Heavy (requires JVM + coordination service)
- Slower startup (10+ seconds)
- Higher resource usage

**Why not Redis Streams?**
- No built-in compression (must implement manually)
- Durability concerns (AOF can lose data on crash)
- Different API (not Kafka-compatible)

**Why not ActiveMQ?**
- Lower throughput (10-50 MB/sec vs 100s MB/sec)
- JMS protocol complexity

### Storage: S3 over Local Filesystem

**Decision**: Use **S3** (or S3-compatible like MinIO) for distributed storage

**Why S3?**
1. **Shared access**: All persistence and indexer instances can read/write
2. **Scalability**: No single machine I/O bottleneck
3. **Durability**: Built-in replication, no data loss on machine failure
4. **Atomic writes**: Multipart upload provides atomic commits (same as temp file rename)
5. **Simple operations**: No need for NFS mounts or distributed filesystems

**Current interface already supports S3**:
- `openWriter(key)` → CreateMultipartUpload (start)
- `writeMessage()` → UploadPart (accumulate 5MB parts)
- `close()` → CompleteMultipartUpload (atomic commit!)
- `readMessage()` / `openReader()` → GetObject (stream)
- `listKeys(prefix)` → ListObjectsV2

**In-process fallback**: FileSystemStorageResource still works for development/testing

---

## Performance Analysis

### Tick Size vs TPS Formula

**Tick size** (uncompressed):
```
tick_size = (grid_width × grid_height × density × 7 bytes) + (num_organisms × 1200 bytes)
```

**TPS** (rough estimate):
```
TPS ≈ K / (grid_width × grid_height)

where K = constant (~1,000,000 for modern CPU)
```

**Key Insight**: `bytes/sec = TPS × tick_size` stays roughly constant because grid_size² appears in both terms (tick_size ∝ grid², TPS ∝ 1/grid²).

### Example Scenarios

| Grid Size | Density | Organisms | Tick Size | TPS | Bytes/sec (uncompressed) |
|-----------|---------|-----------|-----------|-----|--------------------------|
| 100×100 | 50% | 100 | 155 KB | ~10,000 | **1.55 GB/sec** |
| 1000×1000 | 50% | 100 | 3.62 MB | ~100 | **362 MB/sec** |
| 10000×10000 | 50% | 100 | 350 MB | ~1 | **350 MB/sec** |

**Result**: Most scenarios produce 200-1500 MB/sec uncompressed data.

### Network Bottleneck Analysis

**Without compression** (1 Gbps network):
```
125 MB/sec ÷ 3.62 MB/tick = 34 ticks/sec ❌ BOTTLENECK!
```

**With compression** (145× ratio):
```
125 MB/sec compressed = 18 GB/sec uncompressed capacity
18 GB/sec ÷ 3.62 MB/tick = 5,000 ticks/sec ✅
```

**Conclusion**: Compression is **essential** for distributed setup. Without it, network becomes the hard bottleneck at 34 TPS.

### CPU Cost of Compression on Simulation Machine

**Per tick costs**:
- Serialization: 3.62 MB ÷ 500 MB/sec = **7 ms CPU**
- Compression (zstd level 1): 3.62 MB ÷ 400 MB/sec = **9 ms CPU**
- Total: **16 ms per tick**

**At 1000 TPS**:
- CPU needed: 1000 × 16ms = 16 seconds/sec = **1.6 CPU cores**

**Trade-off decision**: **Accept the CPU cost!**
- Spend 2 cores → Enable 5,000 TPS over network ✅
- Don't spend cores → Stuck at 34 TPS forever ❌

**Redpanda producer configuration handles this**:
- Serialization: Background thread (non-blocking put())
- Batching: Automatic (batch.size, linger.ms)
- Compression: Background I/O thread (compression.type=zstd, level=1)

### PersistenceService Throughput

**CPU work per PersistenceService** (processing X MB/sec uncompressed):
1. Decompression: X ÷ 400 MB/sec = cores
2. Deserialization: X ÷ 500 MB/sec = cores
3. Re-serialization: X ÷ 500 MB/sec = cores
4. Re-compression: X ÷ 400 MB/sec = cores
5. **Total: ~4 cores per 400 MB/sec throughput**

**Scaling calculation**:

| Scenario | Bytes/sec | Instances Needed | Cores per Instance | Total Cores |
|----------|-----------|------------------|-------------------|-------------|
| 100×100 @ 10K TPS | 1.55 GB/sec | 4 | 4 | 16 |
| 1000×1000 @ 100 TPS | 362 MB/sec | 1 | 4 | 4 |
| 10000×10000 @ 1 TPS | 350 MB/sec | 1 | 4 | 4 |

**Conclusion**: Typical scenarios need **1-4 PersistenceService instances** using **4-16 total cores** on persistence machines.

### Why "Double Serialization" is Acceptable

**In distributed architecture, work happens on DIFFERENT machines**:

```
Machine 1 (Simulation):
  - Serialization for network transport (minimal, non-blocking)
  - 2 CPU cores for queue adapter

Machine 2+ (Persistence):
  - Deserialization + re-serialization for storage
  - 4 CPU cores per instance
  - Does NOT affect simulation speed!
```

**Key principle**: Separating concerns across machines means "redundant" work doesn't hurt. SimulationEngine stays fast, heavy work happens on dedicated persistence machines.

---

## Interface Validation

### Current Interfaces Support Both Scenarios

**No interface changes needed!** Services are already designed for abstraction:

```java
// SimulationEngine (UNCHANGED)
IOutputQueueResource<TickData> queue;
queue.put(tickData);  // Works for InMemory AND Redpanda

// PersistenceService (UNCHANGED)
IInputQueueResource<TickData> inputQueue;
IStorageWriteResource storage;

List<TickData> batch = new ArrayList<>();
inputQueue.drainTo(batch, maxBatchSize, timeout);  // Works for InMemory AND Redpanda

try (MessageWriter writer = storage.openWriter(key)) {
    for (TickData tick : batch) {
        writer.writeMessage(tick);  // Works for FileSystem AND S3
    }
}
```

### Queue Interface Mapping

| Interface Method | InMemoryBlockingQueue | RedpandaQueueAdapter |
|------------------|----------------------|----------------------|
| `put(element)` | LinkedBlockingQueue.put() | KafkaProducer.send() (async) |
| `drainTo(collection, max, timeout)` | BlockingQueue.drainTo() | consumer.poll() in loop |

**Redpanda adapter drainTo() implementation**:
```java
@Override
public int drainTo(Collection<? super T> out, int max, long timeout, TimeUnit unit) {
    long deadline = System.nanoTime() + unit.toNanos(timeout);

    while (out.size() < max && System.nanoTime() < deadline) {
        ConsumerRecords<K,V> records = consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<K,V> record : records) {
            out.add(record.value());
            if (out.size() >= max) break;
        }
    }
    consumer.commitSync();  // Acknowledge consumed messages
    return out.size();
}
```

### Storage Interface Mapping

| Interface Method | FileSystemStorageResource | S3StorageResource |
|------------------|---------------------------|-------------------|
| `openWriter(key)` | new FileOutputStream(tempFile) | s3.createMultipartUpload() |
| `writeMessage()` | writeDelimitedTo(stream) | Buffer + uploadPart() |
| `close()` | tempFile.renameTo(finalFile) | completeMultipartUpload() |
| `readMessage()` | FileInputStream + parseDelimitedFrom() | s3.getObject() + parse |
| `openReader()` | FileInputStream + iterator | s3.getObject() + iterator |
| `exists()` | file.exists() | s3.headObject() |
| `listKeys()` | Files.walk() + filter | s3.listObjectsV2() |

**Both provide atomic writes**:
- FileSystem: Write to `.tmp` file, rename on success
- S3: Multipart upload, parts invisible until CompleteMultipartUpload

### Multi-Instance Competing Consumers

**Already works in-process!** See `evochora.conf`:
```hocon
startupSequence = [
  "metadata-persistence-service",
  "persistence-service-1",  # ← Competing consumer 1
  "persistence-service-2",  # ← Competing consumer 2
  "persistence-service-3",  # ← Competing consumer 3
  "simulation-engine"
]
```

All three instances read from same `raw-tick-data` InMemoryBlockingQueue and compete for messages.

**In distributed**: Same config, different queue implementation (Redpanda consumer group instead of BlockingQueue).

---

## Testing Strategy

### Three Test Levels

#### 1. Unit Tests (No Containers)
Mock underlying clients to test adapter logic:

```java
@Test
void testRedpandaQueueAdapter_PutCallsProducer() {
    KafkaProducer<String, TickData> mockProducer = mock(KafkaProducer.class);
    RedpandaQueueAdapter queue = new RedpandaQueueAdapter(mockProducer, ...);

    TickData tick = createTickData(1);
    queue.put(tick);

    verify(mockProducer).send(argThat(record ->
        record.value().equals(tick)
    ));
}

@Test
void testS3StorageResource_WriterUsesMultipart() {
    S3Client mockClient = mock(S3Client.class);
    S3StorageResource storage = new S3StorageResource(mockClient, ...);

    try (MessageWriter writer = storage.openWriter("test/batch.pb")) {
        writer.writeMessage(createTickData(1));
    }

    verify(mockClient).createMultipartUpload(any());
    verify(mockClient).completeMultipartUpload(any());
}
```

**Fast**: ~10ms per test, run on every build

#### 2. Integration Tests (Testcontainers)
Test against real services using Testcontainers:

```java
@Tag("integration")
@Testcontainers
class RedpandaQueueResourceIT {

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(
        DockerImageName.parse("redpandadata/redpanda:v23.3.3")
    );

    @Test
    void testProducerConsumerRoundTrip() throws Exception {
        String bootstrapServers = redpanda.getBootstrapServers();

        // Producer
        Config producerConfig = ConfigFactory.parseString(String.format("""
            bootstrapServers = "%s"
            topic = "test-ticks"
            """, bootstrapServers));
        RedpandaQueueAdapter producer = new RedpandaQueueAdapter(
            "producer", producerConfig, ProducerMode
        );

        // Send ticks
        List<TickData> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            TickData tick = createTickData(i);
            sent.add(tick);
            producer.put(tick);
        }

        // Consumer
        RedpandaQueueAdapter consumer = new RedpandaQueueAdapter(
            "consumer", producerConfig, ConsumerMode
        );

        // Receive ticks
        List<TickData> received = new ArrayList<>();
        consumer.drainTo(received, 100, 10, TimeUnit.SECONDS);

        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals(sent.get(i).getTickNumber(), received.get(i).getTickNumber());
        }
    }

    @Test
    void testCompetingConsumers() throws Exception {
        // Test multiple consumers competing for messages
        // Each message should go to exactly ONE consumer
    }
}
```

```java
@Tag("integration")
@Testcontainers
class S3StorageResourceIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2023-09-04")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    @BeforeEach
    void setUp() {
        // Create S3 client
        S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(minio.getS3URL()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    minio.getUserName(),
                    minio.getPassword()
                )
            ))
            .region(Region.US_EAST_1)
            .build();

        // Create bucket
        s3.createBucket(b -> b.bucket("test-bucket"));
    }

    @Test
    void testWriteAndReadBatch() throws Exception {
        Config config = ConfigFactory.parseString(String.format("""
            endpoint = "%s"
            accessKey = "%s"
            secretKey = "%s"
            bucket = "test-bucket"
            region = "us-east-1"
            """,
            minio.getS3URL(),
            minio.getUserName(),
            minio.getPassword()
        ));

        S3StorageResource storage = new S3StorageResource("test-storage", config);

        // Write batch
        List<TickData> originalTicks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            originalTicks.add(createTickData(i));
        }

        try (MessageWriter writer = storage.openWriter("sim-123/batch_000_999.pb")) {
            for (TickData tick : originalTicks) {
                writer.writeMessage(tick);
            }
        }

        // Read batch
        List<TickData> readTicks = new ArrayList<>();
        try (MessageReader<TickData> reader = storage.openReader(
                "sim-123/batch_000_999.pb.zst",  // Note: .zst extension added
                TickData.parser())) {
            reader.forEachRemaining(readTicks::add);
        }

        assertEquals(originalTicks.size(), readTicks.size());
        assertEquals(originalTicks, readTicks);
    }

    @Test
    void testAtomicCommit() throws Exception {
        // Test that incomplete writes don't appear in storage
    }

    @Test
    void testListKeys() throws Exception {
        // Test listing with prefix works correctly
    }
}
```

**Moderate speed**: ~2-5 seconds per test (container startup), run on pre-commit

#### 3. System Tests (Full Pipeline)
Test complete system with all services and containers:

```java
@Tag("system")
@Testcontainers
class FullDistributedPipelineIT {

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(...);

    @Container
    static MinIOContainer minio = new MinIOContainer(...);

    @Test
    void testEndToEndDistributedPipeline() throws Exception {
        // 1. Start SimulationEngine (writes to Redpanda)
        // 2. Start multiple PersistenceService instances (competing consumers)
        // 3. Run simulation for 1000 ticks
        // 4. Verify all ticks persisted to S3
        // 5. Verify no duplicates
        // 6. Verify competing consumers distributed work
    }
}
```

**Slow**: ~30-60 seconds, run on CI/CD only

### Gradle Configuration

```kotlin
// build.gradle.kts

dependencies {
    // Kafka/Redpanda client
    implementation("org.apache.kafka:kafka-clients:3.6.1")

    // AWS S3 SDK
    implementation("software.amazon.awssdk:s3:2.21.0")

    // Testcontainers for integration tests
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:kafka:1.19.3")      // Works for Redpanda!
    testImplementation("org.testcontainers:minio:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "system")  // Skip by default
    }
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
}

tasks.register<Test>("systemTest") {
    useJUnitPlatform {
        includeTags("system")
    }
}
```

**Run tests**:
```bash
./gradlew test              # Unit tests only (~1 minute)
./gradlew integrationTest   # + Integration tests (~5 minutes)
./gradlew systemTest        # Full system tests (~10 minutes)
./gradlew check             # All tests
```

---

## Implementation Plan

### Phase 1: Complete In-Process Pipeline (2-3 weeks) ← **DO THIS FIRST**

**Goal**: Prove the entire value chain works end-to-end

1. **Indexer Service** (1 week)
   - Read batches from FileSystemStorage
   - Build indexes (organism evolution, tick statistics, etc.)
   - Store indexes to another storage resource
   - REST API for queries

2. **Query API Service** (1 week)
   - HTTP endpoints for querying indexed data
   - Read from index storage
   - Return JSON responses
   - Integrate with existing HttpServerProcess

3. **Web Client** (1 week)
   - Basic visualization of simulation results
   - Query interface to search indexed data
   - Real-time display of simulation progress
   - Static files served via HttpServerProcess

**Milestone**: Run simulations, see indexed results in web UI, understand if system works!

**Benefits**:
- ✅ Proves complete value chain
- ✅ Discovers indexer/API requirements in simple setup
- ✅ Can actually use the system to run experiments
- ✅ Tests competing consumers in-process (3 PersistenceService instances already configured)

### Phase 2: Add Distributed Infrastructure (1-2 weeks) ← **DO THIS SECOND**

**Goal**: Enable scaling across multiple machines

4. **Redpanda Queue Resource** (3-4 days)
   - Implement `RedpandaQueueAdapter` (producer and consumer modes)
   - Protobuf serializer/deserializer
   - Consumer group configuration
   - Unit tests (mocked KafkaProducer/Consumer)
   - Integration tests (Testcontainers)

5. **S3 Storage Resource** (3-4 days)
   - Implement `S3StorageResource`
   - Multipart upload for writes
   - Streaming reads
   - Unit tests (mocked S3Client)
   - Integration tests (MinIO via Testcontainers)

6. **Distributed Deployment** (2-3 days)
   - Docker Compose configuration
   - Environment-based configuration (dev vs prod)
   - Test with real Redpanda + MinIO
   - Verify competing consumers work across machines
   - Performance benchmarking

**Milestone**: Same system, now scales horizontally!

**Benefits**:
- ✅ Proven architecture (Phase 1 validated requirements)
- ✅ Services unchanged (just swap resource implementations)
- ✅ Independent scaling (simulation on one machine, persistence on others)

### Why Phase 1 Before Phase 2?

1. **Validate requirements early**: Indexers will reveal data access patterns, query needs, metadata requirements
2. **Simpler debugging**: Find issues in simple in-process setup before adding distributed complexity
3. **Incremental value**: Have a working system after Phase 1, distributed is enhancement
4. **Risk management**: If distributed is hard, still have usable system
5. **Test competing consumers now**: 3 PersistenceService instances already configured in evochora.conf

---

## Configuration

### Redpanda Producer Configuration

```hocon
resources {
  raw-tick-data {
    className = "org.evochora.datapipeline.resources.queues.RedpandaQueueResource"
    options {
      # Connection
      bootstrapServers = "localhost:9092"  # Or "${REDPANDA_BOOTSTRAP_SERVERS}"
      topic = "evochora-ticks"

      # Serialization
      keySerializer = "org.apache.kafka.common.serialization.StringSerializer"
      valueSerializer = "org.evochora.datapipeline.resources.queues.ProtobufSerializer"

      # Performance: Batching (trades latency for throughput)
      batch.size = 1048576              # 1 MB batches (~280 ticks at 3.62 MB each)
      linger.ms = 10                    # Wait max 10ms to fill batch (low latency)
      buffer.memory = 67108864          # 64 MB producer buffer

      # Performance: Compression (essential for network bandwidth!)
      compression.type = "zstd"         # Zstandard compression
      compression.level = 1             # Fast compression (balance CPU vs size)

      # Reliability
      acks = "1"                        # Wait for leader acknowledgment (not all replicas)
      retries = 3                       # Retry failed sends
      max.in.flight.requests.per.connection = 5

      # Ordering
      enable.idempotence = true         # Prevent duplicates on retry
      max.in.flight.requests.per.connection = 5  # With idempotence, can be >1
    }
  }
}
```

### Redpanda Consumer Configuration

```hocon
resources {
  raw-tick-data {
    className = "org.evochora.datapipeline.resources.queues.RedpandaQueueResource"
    options {
      # Connection
      bootstrapServers = "localhost:9092"
      topic = "evochora-ticks"

      # Consumer group (enables competing consumers)
      group.id = "persistence-service-group"

      # Deserialization
      keyDeserializer = "org.apache.kafka.common.serialization.StringDeserializer"
      valueDeserializer = "org.evochora.datapipeline.resources.queues.ProtobufDeserializer"

      # Performance
      max.poll.records = 1000           # Match PersistenceService maxBatchSize
      fetch.min.bytes = 1048576         # Wait for 1 MB before returning (throughput)
      fetch.max.wait.ms = 100           # But don't wait more than 100ms (latency)

      # Reliability
      enable.auto.commit = false        # Manual commit (after successful persistence)
      auto.offset.reset = "earliest"    # Start from beginning if no offset

      # Isolation
      isolation.level = "read_committed" # Only read committed messages (if using transactions)
    }
  }
}
```

### S3 Storage Configuration

```hocon
resources {
  tick-storage {
    className = "org.evochora.datapipeline.resources.storage.S3StorageResource"
    options {
      # S3 Connection (use environment variables for credentials!)
      endpoint = "${?AWS_ENDPOINT}"              # Optional: for MinIO/localstack
      region = "${?AWS_REGION}"                  # Default: us-east-1
      accessKey = "${?AWS_ACCESS_KEY_ID}"
      secretKey = "${?AWS_SECRET_ACCESS_KEY}"

      # Bucket
      bucket = "evochora-simulations"

      # Multipart Upload Settings
      multipartUpload {
        partSize = 5242880                       # 5 MB (minimum for S3)
        maxConcurrentParts = 4                   # Parallel part uploads
      }

      # Compression (same as FileSystem)
      compression {
        enabled = true
        codec = "zstd"
        level = 3                                # Storage: higher ratio (not speed-critical)
      }

      # Client Configuration
      client {
        maxConnections = 50                      # HTTP connection pool
        connectionTimeout = 5000                 # 5 seconds
        requestTimeout = 30000                   # 30 seconds
      }
    }
  }
}
```

### Environment Variables (Production)

```bash
# Redpanda/Kafka
export REDPANDA_BOOTSTRAP_SERVERS="redpanda-1:9092,redpanda-2:9092,redpanda-3:9092"

# AWS S3
export AWS_REGION="us-east-1"
export AWS_ACCESS_KEY_ID="your-access-key"
export AWS_SECRET_ACCESS_KEY="your-secret-key"

# Or for MinIO (development)
export AWS_ENDPOINT="http://minio:9000"
export AWS_ACCESS_KEY_ID="minioadmin"
export AWS_SECRET_ACCESS_KEY="minioadmin"
```

### Docker Compose Example

```yaml
version: '3.8'

services:
  redpanda:
    image: redpandadata/redpanda:v23.3.3
    command:
      - redpanda
      - start
      - --smp 1
      - --memory 1G
      - --reserve-memory 0M
      - --overprovisioned
      - --node-id 0
      - --check=false
      - --kafka-addr PLAINTEXT://0.0.0.0:9092
      - --advertise-kafka-addr PLAINTEXT://redpanda:9092
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD", "rpk", "cluster", "health"]
      interval: 5s
      timeout: 3s
      retries: 10

  minio:
    image: minio/minio:RELEASE.2023-09-04
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 3s
      retries: 10

  simulation-engine:
    build: .
    environment:
      REDPANDA_BOOTSTRAP_SERVERS: "redpanda:9092"
      AWS_ENDPOINT: "http://minio:9000"
      AWS_ACCESS_KEY_ID: "minioadmin"
      AWS_SECRET_ACCESS_KEY: "minioadmin"
    depends_on:
      redpanda:
        condition: service_healthy
      minio:
        condition: service_healthy
    command: ["java", "-jar", "evochora.jar"]

  persistence-service:
    build: .
    environment:
      REDPANDA_BOOTSTRAP_SERVERS: "redpanda:9092"
      AWS_ENDPOINT: "http://minio:9000"
      AWS_ACCESS_KEY_ID: "minioadmin"
      AWS_SECRET_ACCESS_KEY: "minioadmin"
    depends_on:
      redpanda:
        condition: service_healthy
      minio:
        condition: service_healthy
    command: ["java", "-jar", "evochora.jar", "--service", "persistence-service"]
    deploy:
      replicas: 3  # 3 competing consumers

volumes:
  minio-data:
```

---

## Key Takeaways

1. **Current interfaces already support distributed** - No service code changes needed
2. **Redpanda is the optimal queue** - Kafka-compatible but faster, lighter, simpler
3. **S3 provides shared storage** - Multipart upload = atomic commits, scales independently
4. **Compression is essential** - Without it, network is bottleneck (34 TPS vs 5000 TPS)
5. **CPU trade-off is worth it** - 2 cores on simulation machine enables 150× throughput
6. **Build indexers first** - Validate requirements in simple setup before adding distributed complexity
7. **Testcontainers for integration testing** - Real Redpanda + MinIO in ~2 seconds
8. **Competing consumers work now** - 3 PersistenceService instances already configured in-process

---

## References

- [Redpanda Documentation](https://docs.redpanda.com/)
- [Kafka Clients JavaDoc](https://kafka.apache.org/documentation/#producerapi)
- [AWS S3 Multipart Upload](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [MinIO Documentation](https://min.io/docs/minio/linux/index.html)
