# Heap Memory Sizing Guide

This guide helps you calculate the required heap memory for each Evochora service when running in Docker containers or distributed deployments.

## Understanding Memory Requirements

Evochora's memory usage is primarily driven by several factors:

1. **Organism count**: How many organisms are alive in your simulation
2. **Resource types**: Whether resources are in-process (memory-based) or external (cloud-based)
3. **Queue capacity**: How many ticks are buffered in memory (for in-process queues)
4. **Batch size**: How many ticks are batched together before writing to storage

The key insight: **Each tick contains the complete state of all organisms in the simulation**, so more organisms = larger tick data = more memory needed.

### Resource Types Impact

Memory requirements depend heavily on whether you use **in-process** or **distributed** resources:

| Resource Type | In-Process Example | Distributed Example |
|---------------|-------------------|---------------------|
| Queue | `InMemoryBlockingQueue` | AWS SQS / Kafka / RabbitMQ |
| Storage | `FileSystemStorageResource` | S3 / Azure Blob / GCS |
| Topic | `H2TopicResource` | Kafka / RabbitMQ |
| Database | `H2Database` | PostgreSQL / MySQL / MongoDB |

Each service section below shows formulas for **both deployment types**.

---

## Quick Start: Calculate Your TickData Size

### Step 1: Determine Your Environment Size

Your environment size is defined by the `shape` parameter in your config:

```
environment_cells = product of all shape dimensions
```

**Examples:**
- 2D world `[100, 100]` → 10,000 cells
- 2D world `[1000, 1000]` → 1,000,000 cells
- 3D world `[100, 100, 50]` → 500,000 cells

### Step 2: Determine Your Organism Count

This depends on your simulation scenario:
- Initial organism count (from config)
- Organisms that spawn/replicate during simulation
- Typical range: 1 to several hundred organisms

### Step 3: Determine Occupied Cells

Cells can be occupied by different sources:
- **Organism code:** Each organism occupies ~2,500 cells with its program (molecules)
- **Energy distribution:** Plugins like `GeyserCreator` or `SolarRadiationCreator` place energy molecules
- **Data storage:** Organisms can write data to cells

**Typical calculation:**
```
occupied_cells = (organism_count × 2500) + energy_molecules + data_molecules
```

For **rough estimates**, you can assume `occupied_cells ≈ organism_count × 2500` if energy/data is negligible.

### Step 4: Calculate TickData Size

Each tick contains two **independent** components:

**A) Cell Data (all molecules in the environment):**
- 16 bytes per occupied cell (flat_index, molecule_type, molecule_value, owner_id)
- Includes organism code, energy molecules, and data molecules

**B) Organism Data (internal state):**
- 2,632 bytes per organism (registers, stacks, instruction pointer, call stack, etc.)

**Formula:**
```
TickData_size_bytes = 100 + (occupied_cells × 16) + (organism_count × 2632)
TickData_size_MB = TickData_size_bytes / 1,048,576
```

**Examples (assuming occupied_cells ≈ organism_count × 2500):**
- 4 organisms, ~10,000 occupied cells → 0.16 MB per tick
- 100 organisms, ~250,000 occupied cells → 4.1 MB per tick
- 400 organisms, ~1,000,000 occupied cells → 16.3 MB per tick
- 1,600 organisms, ~4,000,000 occupied cells → 65.1 MB per tick

---

## Memory Formulas by Container

### Core Services

#### SimulationEngine
Generates tick data and sends it to a queue resource.

**In-Process Deployment (`InMemoryBlockingQueue`):**
```
Heap_MB = 60 + (capacity × TickData_size_MB)

Where:
  TickData_size_MB = (100 + (occupied_cells × 16) + (organism_count × 2632)) / 1,048,576
  capacity = raw-tick-data.options.capacity
  occupied_cells = Number of non-empty cells in environment
  organism_count = Number of organisms in your simulation
```

**Distributed Deployment (AWS SQS / Kafka / RabbitMQ):**
```
Heap_MB = 60 + (sendBatchSize × TickData_size_MB)

Where:
  TickData_size_MB = (100 + (occupied_cells × 16) + (organism_count × 2632)) / 1,048,576
  sendBatchSize = Internal batching for external queue send operations (future config parameter)
  occupied_cells = Number of non-empty cells in environment
  organism_count = Number of organisms in your simulation
```

**Key Difference:**
- **In-Process:** Must buffer `capacity` ticks in heap (e.g., 5000 ticks)
- **Distributed:** Only buffer `sendBatchSize` ticks during send operation (e.g., 10-100 ticks)
- **Result:** Distributed uses **50-500x less memory** for the queue buffer!

**Recommendation:** 
- In-process: Use small `capacity` with TickData_size to keep memory manageable
- Distributed: Memory usage is directly TickData_size x `sendBatchSize`

---

#### PersistenceService (per instance)
Reads from queue, batches tick data, writes to storage, publishes to topic.

**Note:** You can run multiple instances for parallel processing (1-10+ instances). Each instance requires the memory calculated below.

**Both In-Process and Distributed Deployments:**
```
Heap_MB = 70 + (maxBatchSize × TickData_size_MB) + (maxKeys × 0.00003)

Where:
  TickData_size_MB = (100 + (occupied_cells × 16) + (organism_count × 2632)) / 1,048,576
  maxBatchSize = persistence-service-N.options.maxBatchSize
  maxKeys = persistence-idempotency-tracker.options.maxKeys
  occupied_cells = Number of non-empty cells in environment
  organism_count = Number of organisms in your simulation
```

**Same formula for both!** The batch must be assembled in heap before writing, regardless of storage type.

**What Changes with Distributed Resources:**
- ✅ **Queue:** External (SQS/Kafka) → no queue buffer in this service's heap
- ✅ **Storage:** External (S3/GCS) → no filesystem cache in heap
- ✅ **Topic:** External (Kafka/RabbitMQ) → no topic cache in heap
- ❌ **Batch:** Always in heap during processing (`maxBatchSize` impact unchanged)
- ❌ **Idempotency:** Always in heap (~152 MB with `maxKeys=5M`, must be fast)

**Recommendation:** With large organisms (e.g., 400+ organisms = 16 MB/tick), use  smaller `maxBatchSize` to keep memory manageable, regardless of resource type.

---

#### MetadataPersistenceService
Handles simulation metadata (configuration, initial state).

**Both In-Process and Distributed Deployments:**
```
Heap_MB = 50 MB  (constant - metadata is tiny, ~1-10 KB)
```

**Note:** Metadata size is negligible compared to tick data, so resource type doesn't significantly impact memory.

**Recommendation:** Fixed at 256 MB container limit for both deployment types.

---

### Indexers

#### MetadataIndexer / DummyIndexer
Reads metadata from storage and indexes it in the database.

**In-Process Deployment (`H2Database`):**
```
Heap_MB = 50 + (maxPoolSize × 3)

Where:
  maxPoolSize = pipeline.database.maxPoolSize
```

**Distributed Deployment (PostgreSQL / MySQL / MongoDB):**
```
Heap_MB = 50 + (maxPoolSize × 3)
```

**Same formula!** Database connection pools have similar overhead regardless of database type.

**Recommendation:** 256 MB is sufficient for both deployment types.

---

### Storage & Database Resources

#### H2 Database (index-database)
Stores indexed simulation metadata and batch locations.

**In-Process Only (H2 is embedded):**
```
Heap_MB = 50 + cache_size_MB + (maxPoolSize × 3)

Where:
  cache_size_MB = CACHE_SIZE parameter in jdbcUrl / 1024
  maxPoolSize = pipeline.database.maxPoolSize
```

**Example:** With `CACHE_SIZE=524288` (512 MB) and `maxPoolSize=10`:
```
Heap_MB = 50 + 512 + 30 = 592 MB
```

**Distributed Alternative (PostgreSQL / MySQL / MongoDB):**
- Database runs in separate process/container → **no heap usage in this resource**
- Services use connection pools → heap usage moved to service containers (see Indexers section)

**Recommendation:** 1 GB for in-process H2, not applicable for distributed databases.

---

#### H2 Topic (batch-topic, metadata-topic)
Persistent message queue for batch notifications between services.

**In-Process Only (H2 is embedded):**
```
Heap_MB = 100 + cache_size_MB + (maxPoolSize × 3)

Where:
  cache_size_MB = CACHE_SIZE parameter in jdbcUrl / 1024
  maxPoolSize = pipeline.database.maxPoolSize
```

**Distributed Alternative (Kafka / RabbitMQ / AWS SNS):**
- Topic runs in separate process/infrastructure → **no heap usage in this resource**
- Services use client libraries → minimal heap overhead (~10-50 MB)

**Recommendation:** 1 GB for in-process H2 topics, not applicable for distributed topics.

---

#### FileSystemStorageResource (tick-storage)
Handles file I/O and compression for batch files.

**In-Process Only (local filesystem):**
```
Heap_MB = 100 MB  (constant - I/O buffers + compression)
```

**Distributed Alternative (S3 / Azure Blob / GCS):**
- Storage is external → **no heap usage in this resource**
- Services interact via SDK clients → heap overhead in service containers (~20-50 MB)

**Recommendation:** 256 MB for in-process filesystem, not applicable for cloud storage.

---

#### InMemoryBlockingQueue (raw-tick-data)
Buffers tick data between SimulationEngine and PersistenceService.

```
Heap_MB = 50 + (capacity × TickData_size_MB)

Where:
  TickData_size_MB = (100 + (occupied_cells × 16) + (organism_count × 2632)) / 1,048,576
  capacity = raw-tick-data.options.capacity
  occupied_cells = Number of non-empty cells in environment
  organism_count = Number of organisms in your simulation
```

**Note:** This queue is typically co-located with SimulationEngine, so its memory is included in the SimulationEngine calculation.

---

#### InMemoryIdempotencyTracker (persistence-idempotency-tracker)
Detects duplicate ticks using a ring buffer + HashSet.

**Both In-Process and Distributed Deployments:**
```
Heap_MB = 50 + (maxKeys × 0.00003)

Where:
  maxKeys = persistence-idempotency-tracker.options.maxKeys
```

**Always in-memory!** The idempotency tracker must be fast (O(1) lookups), so it's always in heap regardless of other resource types.

**Example:** With `maxKeys=5,000,000`:
```
Heap_MB = 50 + (5,000,000 × 32 / 1,048,576) = 50 + 152 = 202 MB
```

**Recommendation:** 
- 256 MB container limit is sufficient
- Provides ~30-60 second duplicate detection window at 100k-150k TPS
- For distributed deployments with multiple PersistenceService instances, consider a distributed idempotency tracker (Redis/DynamoDB) to share state across instances

---

## Practical Examples

### Example 1: Small Environment - In-Process Deployment (Single Machine)

**Deployment Type:** All resources in-process (development/testing on single machine)

**Config:**
```hocon
environment.shape = [100, 100]     # 10,000 cells
capacity = 5000                    # InMemoryBlockingQueue
maxBatchSize = 1000
maxKeys = 5000000

# Resources (all in-process):
raw-tick-data: InMemoryBlockingQueue
tick-storage: FileSystemStorageResource
batch-topic: H2TopicResource
index-database: H2Database
```

**Simulation Scenario:**
```
organism_count = 4
occupied_cells ≈ 10,000
TickData_size = 0.16 MB per tick
```

**Memory Requirements:**

| Service/Resource | Formula | Heap | Container |
|------------------|---------|------|-----------|
| **SimulationEngine** | 60 + (5000 × 0.16) | 860 MB | 1 GB |
| **PersistenceService (×3)** | 70 + (1000 × 0.16) + 152 | 382 MB each | 512 MB each |
| **MetadataPersistence** | 50 | 50 MB | 256 MB |
| **MetadataIndexer** | 50 + 30 | 80 MB | 256 MB |
| **H2 Database** | 50 + 512 + 30 | 592 MB | 1 GB |
| **H2 Topics (×2)** | 100 + 512 + 30 | 642 MB each | 1 GB each |
| **Storage** | 100 | 100 MB | 256 MB |

**Total: ~7 GB** (including all resources in their own containers)

---

### Example 2: Large Environment (1000×1000) - Distributed Deployment

**Deployment Type:** External resources (cloud-native, multi-container)

**Config:**
```hocon
environment.shape = [1000, 1000]   # 1,000,000 cells
sendBatchSize = 10                 # SQS/Kafka batching (future parameter)
maxBatchSize = 100                 # Still needs to fit in heap!
maxKeys = 5000000

# Resources (all external):
raw-tick-data: AWS SQS / Kafka
tick-storage: S3 / Azure Blob
batch-topic: Kafka / RabbitMQ
index-database: PostgreSQL / MySQL
```

**Simulation Scenario:**
```
organism_count = 400
occupied_cells ≈ 1,000,000
TickData_size = 16.3 MB per tick
```

**Memory Requirements:**

| Service | Formula | Heap | Container |
|---------|---------|------|-----------|
| **SimulationEngine** | 60 + (10 × 16.3) | 223 MB | 256 MB |
| **PersistenceService (×5)** | 70 + (100 × 16.3) + 152 | 1,852 MB each | 2 GB each |
| **MetadataPersistence** | 50 | 50 MB | 256 MB |
| **MetadataIndexer (×2)** | 50 + 30 | 80 MB each | 256 MB each |
| **PostgreSQL** | External | - | (separate RDS instance) |
| **Kafka** | External | - | (separate cluster) |
| **S3** | External | - | (AWS service) |
| **SQS** | External | - | (AWS service) |

**Total Service Memory: ~10.5 GB** (only service containers, infrastructure is external)

**Key Insights:**
- **SimulationEngine:** Minimal heap (no queue buffer!)
- **PersistenceService:** Still needs heap for batch processing (unavoidable)
- **Scalability:** Can run 5+ PersistenceService instances instead of 3 (no queue bottleneck)
- **Infrastructure:** PostgreSQL, Kafka, S3, SQS run separately (managed services)

**⚠️ Trade-off:** 
- Less memory in services ✅
- More infrastructure complexity (external services) ⚠️
- Better horizontal scalability ✅

---

## Scaling Recommendations

### Rule of Thumb: Adjust Capacity Based on TickData Size

| TickData Size | Recommended Capacity | Recommended BatchSize | Reasoning |
|---------------|----------------------|-----------------------|-----------|
| < 1 MB | 1000-5000 | 1000 | Small ticks, can buffer many |
| 1-10 MB | 100-500 | 100-500 | Medium ticks, moderate buffering |
| 10-50 MB | 10-50 | 50-100 | Large ticks, minimal buffering |
| > 50 MB | 5-10 | 10-50 | Very large ticks, extremely tight buffering |

**Goal:** Keep total queue memory under 1-2 GB for SimulationEngine.

---

### Memory vs. Throughput Trade-offs

**Higher Capacity/BatchSize:**
- ✅ Better throughput (fewer I/O operations)
- ✅ Better coalescing (more consistent batch sizes)
- ❌ More memory required
- ❌ Longer recovery time if service crashes

**Lower Capacity/BatchSize:**
- ✅ Less memory required
- ✅ Faster recovery (less data loss on crash)
- ❌ More I/O operations (more files written)
- ❌ Potentially worse batch coalescing

**Recommendation:** Start with low values for large environments, then increase if throughput is insufficient.

---

## Docker Compose Example

```yaml
version: '3.8'

services:
  simulation-engine:
    image: evochora:latest
    command: ["simulation-engine"]
    environment:
      JAVA_OPTS: "-Xmx1g"
    deploy:
      resources:
        limits:
          memory: 1.5g  # Heap + JVM overhead

  persistence-service-1:
    image: evochora:latest
    command: ["persistence-service-1"]
    environment:
      JAVA_OPTS: "-Xmx6g"
    deploy:
      resources:
        limits:
          memory: 7g

  persistence-service-2:
    image: evochora:latest
    command: ["persistence-service-2"]
    environment:
      JAVA_OPTS: "-Xmx6g"
    deploy:
      resources:
        limits:
          memory: 7g

  persistence-service-3:
    image: evochora:latest
    command: ["persistence-service-3"]
    environment:
      JAVA_OPTS: "-Xmx6g"
    deploy:
      resources:
        limits:
          memory: 7g

  h2-database:
    image: evochora:latest
    command: ["h2-database"]
    environment:
      JAVA_OPTS: "-Xmx1g"
    deploy:
      resources:
        limits:
          memory: 1.5g

  batch-topic:
    image: evochora:latest
    command: ["batch-topic"]
    environment:
      JAVA_OPTS: "-Xmx1g"
    deploy:
      resources:
        limits:
          memory: 1.5g

  metadata-topic:
    image: evochora:latest
    command: ["metadata-topic"]
    environment:
      JAVA_OPTS: "-Xmx1g"
    deploy:
      resources:
        limits:
          memory: 1.5g

  metadata-indexer:
    image: evochora:latest
    command: ["metadata-indexer"]
    environment:
      JAVA_OPTS: "-Xmx256m"
    deploy:
      resources:
        limits:
          memory: 512m

  metadata-persistence:
    image: evochora:latest
    command: ["metadata-persistence"]
    environment:
      JAVA_OPTS: "-Xmx256m"
    deploy:
      resources:
        limits:
          memory: 512m
```

**Note:** This example assumes a 1000×1000 environment with the optimized settings from Example 3.

---

## Quick Reference Table

| Environment Cells | Organisms | TickData Size | Capacity | BatchSize | SimEngine Heap | Persistence Heap |
|-------------------|-----------|---------------|----------|-----------|----------------|------------------|
| 10,000 | 4 | 0.16 MB | 5000 | 1000 | 860 MB | 382 MB |
| 100,000 | 40 | 1.6 MB | 500 | 500 | 860 MB | 952 MB |
| 250,000 | 100 | 4.1 MB | 100 | 100 | 470 MB | 632 MB |
| 1,000,000 | 400 | 16.3 MB | 10 | 100 | 223 MB | 1,852 MB |
| 4,000,000 | 1,600 | 65.1 MB | 5 | 10 | 386 MB | 873 MB |

---

## Troubleshooting

### OutOfMemoryError in SimulationEngine
**Symptom:** JVM crashes with "java.lang.OutOfMemoryError: Java heap space"

**Solutions:**
1. Reduce `raw-tick-data.capacity` in config
2. Increase container memory limit
3. Use a smaller environment

---

### OutOfMemoryError in PersistenceService
**Symptom:** JVM crashes during batch processing

**Solutions:**
1. Reduce `maxBatchSize` in config
2. Reduce `maxKeys` in idempotency tracker (trades duplicate detection window for memory)
3. Increase container memory limit

---

### Slow Throughput / High Latency
**Symptom:** Ticks are processed slowly, queue fills up

**Solutions:**
1. Increase `maxBatchSize` (if memory allows) for better I/O efficiency
2. Scale out: Add more PersistenceService instances
3. Enable compression (already enabled by default in evochora.conf)
4. Use faster storage (SSD, NVMe)

---

## Additional Notes

### Ring Buffer Optimization (v3.0+)
The `InMemoryIdempotencyTracker` uses a Ring Buffer + HashSet design that provides:
- **50% memory savings** compared to previous LinkedHashSet implementation
- **Guaranteed memory bounds** (no growth beyond `maxKeys`)
- **O(1) operations** for all methods
- **FIFO eviction** when capacity is reached

This optimization is already active in your deployment—no configuration changes needed.

---

### H2 Cache Limiting
The H2 database cache is limited to 512 MB by default via the `CACHE_SIZE=524288` parameter in the JDBC URL. This prevents unbounded cache growth while maintaining good query performance.

If you experience high database load or slow queries, you can increase this value, but be aware it will increase heap usage proportionally.

---

## Summary

1. **Calculate your TickData size** based on environment cells
2. **Choose capacity and batchSize** based on available memory
3. **Use the formulas** to calculate heap requirements for each container
4. **Add 20-50% overhead** for JVM, GC, and safety margin
5. **Monitor actual usage** and adjust as needed

**Remember:** Memory requirements scale with environment size. Plan accordingly for production workloads!

