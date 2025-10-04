# Architectural Design: Data Pipeline V3

## 1. Overview

This document outlines the architecture for a completely redesigned data pipeline that prioritizes maximum simulation performance through clean separation of concerns. The core principle is that the simulation should run as fast as possible and only concern itself with simulation logic, while all data processing, persistence, and analysis happens in separate, configurable services.

Building on lessons learned from V2, this design emphasizes universal resource abstractions and flexible deployment patterns that seamlessly scale from single-process development to distributed cloud deployments.

## 2. Core Principles

* **Simulation Performance First**: The runtime simulation is completely decoupled from data processing and runs at maximum speed
* **Universal Resource Abstraction**: All external dependencies (queues, storage, databases) are abstracted through a unified resource system
* **Deployment Flexibility**: Identical codebase runs as threads in-process or as separate cloud instances via configuration
* **Configuration-Driven Architecture**: Services and their connections are defined declaratively, not in code
* **Clean Dependency Flow**: Runtime → SimulationEngine → Resources → Services → Resources → Services

## 3. Architecture Overview

### 3.1. Data Flow

```
Runtime (Simulation Core)
    ↓ (public API access)
SimulationEngine
    ↓ (via Resource)
PersistenceService (Protobuf → Storage)
    ↓ (via Resource)  
IndexerServices (Queue/Storage → Database)
    ↓ (via Resource)
HttpApiServer (Database → Web Client)
```

### 3.2. Resource Abstraction

Resources are the universal abstraction for all external dependencies:

* **Queues**: InMemoryBlockingQueue ↔ Amazon SQS ↔ Kafka
* **Storage**: Local Filesystem ↔ Amazon S3 ↔ Google Cloud Storage  
* **Databases**: SQLite ↔ PostgreSQL ↔ Cassandra

Resources are intelligent and handle their own serialization/deserialization based on their type.

## 4. Key Components

### 4.1. Runtime Integration

The **Runtime** package remains completely unchanged and decoupled. It provides a clean public API:

* **Organism**: `getId()`, `getDrs()`, `getCallStack()`, `getIp()`, etc.
* **Environment**: `getMolecule()`, `getOwnerId()`, `getShape()`, etc.

### 4.2. SimulationEngine

The **SimulationEngine** service extracts data from the Runtime using its public API and pushes it to configured resources. It does NOT handle serialization - that's the resource's responsibility.

### 4.3. PersistenceService  

The **PersistenceService** receives raw simulation data and persists it using Protocol Buffers for efficient storage and future replay capability.

### 4.4. Universal Dependency Injection

Following the Universal DI pattern from V2:

```hocon
pipeline {
  resources {
    tick-data-queue { 
      className = "org.evochora.datapipeline.resources.InMemoryBlockingQueue"
      options { capacity = 10000 }
    }
    raw-data-storage {
      className = "org.evochora.datapipeline.resources.LocalFileStorage" 
      options { basePath = "./data/raw" }
    }
  }
  
  services {
    simulation-engine {
      className = "org.evochora.datapipeline.services.SimulationEngine"
      resources {
        tickOutput: "queue-out:tick-data-queue?window=5"
      }
    }
    persistence-service-1 {
      className = "org.evochora.datapipeline.services.PersistenceService"  
      resources {
        tickInput: "queue-in:tick-data-queue"
        storageOutput: "raw-data-storage"
      }
    }
    persistence-service-2 {
      className = "org.evochora.datapipeline.services.PersistenceService"  
      resources {
        tickInput: "queue-in:tick-data-queue"
        storageOutput: "raw-data-storage"
      }
    }
    # Multiple PersistenceService instances compete for ticks from the same queue
  }
}
```

## 5. Deployment Patterns

### 5.1. Development (In-Process)

All services run as threads in a single JVM:
* InMemoryBlockingQueue for data transfer
* SQLite for databases  
* Local filesystem for storage

### 5.2. Production (Distributed)

Services run on separate instances/containers:
* Amazon SQS for data transfer
* PostgreSQL/other high-performance DB
* Amazon S3 for storage

**The same codebase works for both patterns** - only configuration changes.

## 6. Data Contracts

### 6.1. Protocol Buffers Schema

Raw simulation data will be defined in `.proto` files, replacing the old `RawTickState`/`RawOrganismState` classes:

```protobuf
message TickData {
  int64 tick_number = 1;
  string simulation_run_id = 2;
  repeated OrganismData organisms = 3;
  repeated CellData cells = 4;
}

message OrganismData {
  int32 id = 1;
  // ... other fields
}
```

### 6.2. Serialization Strategy

* **In-Memory Resources**: Pass object references directly (no serialization)
* **Persistent Resources**: Automatically serialize/deserialize using Protobuf
* **Services remain unaware** of serialization details

## 7. Migration from V2

Key differences from the archived V2 implementation:

1. **Universal Resources** instead of separate channels/storage abstractions
2. **Protobuf-based persistence** instead of custom serialization
3. **Complete Runtime decoupling** (no export methods in Runtime)
4. **Simplified service constructors** using the Universal DI pattern

## 8. Package Structure

To enforce clean dependencies and maintainability:

* **org.evochora.datapipeline**: Main package with core orchestration
  * `ServiceManager.java`: Central orchestration and DI container
  * `CommandLineInterface.java`: CLI entry point with interactive and headless modes
* **org.evochora.datapipeline.api**: Foundation with no implementation dependencies
  * **.contracts**: Data structures (POJOs) transferred between services
  * **.resources**: Resource interfaces (`IResource`, `IContextualResource`, `ResourceContext`)
  * **.services**: Service interfaces (`IService`) and status objects
* **org.evochora.datapipeline.resources**: Concrete resource implementations (InMemoryQueue, S3Storage, etc.)
* **org.evochora.datapipeline.services**: Concrete service implementations (SimulationEngine, PersistenceService, etc.)

## 9. Service Roles & Responsibilities

### 9.1. Core Services

* **SimulationEngine**: Extracts data from Runtime using public API, pushes to configured resources
* **PersistenceService**: Receives raw simulation data, serializes via Protobuf, stores persistently. Multiple instances can run in parallel using the "Competing Consumers" pattern to increase throughput
* **IndexerServices**: Family of specialized indexer services, each focusing on specific data aspects. Examples might include services for simulation metadata, environment data, organism states, or source code annotations. The exact division will be determined during implementation based on performance characteristics and data access patterns. Each indexer can be scaled independently using the competing consumers pattern and can read directly from live queues or archived storage resources
* **HttpApiServer**: Provides REST API for web clients, reads from analysis database

### 9.2. Universal Resource Dependency Injection

Services communicate exclusively through resources, never directly. The ServiceManager implements a sophisticated DI system:

- **Central Resource Definition**: All resources defined in top-level `resources` configuration block
- **Resource URI Pattern**: Services reference resources via URI-like strings with optional parameters (e.g., `"queue-in:tick-data?window=30&batch=100"`)
- **Contextual Resource Wrapping**: Resources can implement `IContextualResource` to return specialized wrappers based on usage context and URI parameters
- **Automatic Monitoring**: Wrappers transparently collect metrics without service awareness
- **Type Safety**: Services receive strongly-typed interfaces, not generic objects

**Example Flow:**
```
ServiceManager → InMemoryQueue.getInjectedObject("queue-in") → ConsumerWrapper → Service
```

The ServiceManager:
- Instantiates all resources from central configuration
- Applies Universal DI with contextual wrapping
- Manages service lifecycles (start, pause, stop)
- Provides status monitoring and metrics collection

*Detailed DI specification will be provided in a separate document.*

## 10. Monitoring & Status Reporting

### 10.1. Service States

Each service reports its state through `IService.getCurrentState()`:
- **RUNNING**: Service is actively processing
- **PAUSED**: Service is temporarily stopped but can resume
- **STOPPED**: Service has shut down
- **ERROR**: Service encountered a fatal error

### 10.2. Resource Binding Status

Each service-resource connection ("binding") provides metrics:
- **ACTIVE**: Resource is being actively used
- **WAITING**: Service is waiting for resource availability
- **FAILED**: Resource connection failed
- **Throughput**: Messages/second (calculated as moving average)

### 10.3. Command Line Interface

* **Interactive Mode (Default)**: 
  * Provides an interactive shell for human operators
  * Human-readable logging format
  * Commands: `start`, `stop`, `restart`, `status`, `pause`, `resume`
  * Single simulation run per system for safety
* **Headless Mode**: 
  * Non-blocking commands for automation and AI assistance
  * Structured JSON logging for automated parsing
  * Same command set available as CLI arguments
  * Strict error handling prevents accidental simulation termination

### 10.4. Logging Strategy

* **Adaptive Format**: 
  * **Interactive Mode**: Human-readable console format for development and debugging
  * **Headless Mode**: Structured JSON format for automated parsing and container collection
* **Output Target**: All logs to stdout, delegating collection to execution environment
* **Technology**: Logback framework with configurable encoders
* **Levels**: Standard levels (ERROR, WARN, INFO, DEBUG, TRACE), INFO default for production

### 10.5. Observability

* **Metrics Export**: IMonitorable interface enables Prometheus/Grafana integration
* **Status Reporting**: Rich service and resource status objects for operational visibility
* **Tracing**: Future OpenTelemetry integration for distributed debugging

## 11. Configuration Management

### 11.1. Precedence Hierarchy

Configuration follows strict precedence (highest to lowest):
1. **Environment Variables** (e.g., `EVOCHORA_PIPELINE_SERVICES_SIMULATION_ENABLED=false`)
2. **Command Line Arguments** (e.g., `--pipeline.services.simulation.enabled=false`)
3. **Configuration File** (`evochora.conf`)
4. **Hardcoded Defaults** (in code)

### 11.2. Dynamic Loading

The ServiceManager uses reflection to instantiate components based on `className` configuration, enabling plugin-like extensibility.

## 12. Non-Functional Requirements

### 12.1. Serialization Strategy

* **Service Responsibility**: Services work exclusively with typed POJOs (contracts)
* **Resource Responsibility**: Resources handle all serialization/deserialization
* **Format Selection**: Resources choose appropriate formats (Protobuf for persistence, JSON for debugging, binary for performance)
* **Schema Evolution**: Protobuf provides forward/backward compatibility for long-term data archives

### 12.2. Idempotent Design

All data-consuming services must be idempotent:
- **Unique Identifiers**: Use deterministic IDs (e.g., `simulationRunId + tickNumber`) 
- **Upsert Operations**: Database operations should be idempotent (INSERT OR REPLACE)
- **Duplicate Detection**: Services check for already-processed data before acting
- **At-Least-Once Delivery**: System remains correct even with message duplication

### 12.3. Error Handling & Resilience

* **Resource Failures**: Services gracefully handle resource unavailability
* **Retry Logic**: Exponential backoff for transient failures
* **Circuit Breakers**: Prevent cascade failures in distributed deployments
* **Dead Letter Queues**: Unprocessable messages are preserved for debugging

### 12.4. Performance Characteristics

* **Zero-Copy In-Memory**: InMemory resources pass object references (no serialization overhead)
* **Competing Consumers**: Multiple service instances can consume from the same resource queue for horizontal scaling
* **Load Balancing**: Resources automatically distribute work among competing consumers
* **Batching Support**: Resources can batch operations for efficiency
* **Backpressure**: Resources signal when they're overwhelmed to prevent memory exhaustion
* **Resource Pooling**: Connection pools for databases and cloud services

## 13. Implementation Plan

### Phase 0: API Foundation ✅ COMPLETED

**Status:** Implemented and functional
- ✅ Core interfaces: IResource, IService, IMonitorable, IContextualResource, IWrappedResource
- ✅ Status objects: ServiceStatus, ResourceBinding, OperationalError, ResourceContext
- ✅ State enums: IService.State, IResource.UsageState
- ✅ Complete Javadoc documentation in English
- ✅ Compilation verified

**Documentation:** See `01_API_FOUNDATION.md` for detailed interface specifications.

### Phase 1: Rapid Development to Working System

#### Phase 1.1: Protobuf Infrastructure
- Configure Gradle for Protocol Buffers compilation
- Create DummyMessage contract under `datapipeline.api.contracts`
- Unit test for serialization/deserialization

**Documentation:** See `02_PROTOBUF_SETUP.md` for detailed implementation guide.

#### Phase 1.2: Core Resource Implementation
- InMemoryBlockingQueue implementing IContextualResource, IMonitorable, IInputQueueResource, IOutputQueueResource
- Smart wrapper creation based on usage type (queue-in vs queue-out) using rich, reusable wrappers (`MonitoredQueueConsumer`, `MonitoredQueueProducer`)
- Service-specific vs global metrics collection

**Documentation:** See `03_RESOURCE_CORE.md` for detailed implementation guide.

#### Phase 1.3: Service Foundation
- AbstractService implementing IService with complete lifecycle management (start, stop, pause, resume)
- Thread management and state transitions
- Resource binding management

**Documentation:** See `04_SERVICE_FOUNDATION.md` for detailed implementation guide.

#### Phase 1.4: Test Services
- DummyProducerService implementing IService and IMonitorable
- DummyConsumerService implementing IService and IMonitorable  
- End-to-end integration test: Producer → Queue → Consumer

**Documentation:** See `05_TEST_SERVICES.md` for detailed implementation guide.

#### Phase 1.5: Service Orchestration
- ServiceManager with HOCON configuration loading
- Universal Resource DI with contextual wrapping
- Service lifecycle management and monitoring

**Documentation:** See `06_SERVICE_ORCHESTRATION.md` for detailed implementation guide.

#### Phase 1.6: Command Line Interface
- Interactive mode with human-readable logging
- Headless mode with JSON logging for automation
- Commands: start, stop, restart, status, pause, resume, compile
- Strict error handling preventing accidental simulation termination

**Documentation:** See `07_CLI.md` for detailed implementation guide.

#### Phase 1.7: Node Architecture
- Standalone Node process hosting the core pipeline logic
- HTTP API for control and automation
- Decoupled infrastructure layer with modular processes
- Generic HttpServerProcess with dynamic route configuration
- PipelineController providing REST endpoints for pipeline lifecycle
- Service Registry for dependency injection
- Persistent operation independent of CLI clients

**Documentation:** See `08_NODE.md` for detailed implementation guide.

#### Phase 1.8: Professional CLI
- Replace transient CLI with professional Picocli-based interface
- Move configuration loading and logging initialization from Node to CLI
- Support for detached mode with PID file management
- Node control commands (run, stop) with HTTP API integration
- Maintain exact functionality of current Node.main() behavior
- Clean separation between CLI entry point and server library

**Documentation:** See `09_NODECLI.md` for detailed implementation guide.

### Result: Fully Functional Pipeline

After these 8 steps, the system will be:
- ✅ **Compilable and testable**
- ✅ **Configurable via HOCON**
- ✅ **Executable with real services**
- ✅ **Monitorable with metrics**
- ✅ **Controllable via CLI**

### Phase 2: Production Services & Storage

#### Phase 2.0: Protobuf Data Contracts ✅ COMPLETED
**Status:** Implemented and functional
- ✅ `metadata_contracts.proto`: SimulationMetadata with complete run configuration
- ✅ `tickdata_contracts.proto`: TickData with complete simulation state capture
- ✅ Complete field documentation (where each field comes from, how to create)
- ✅ Checkpoint capability fields (RNG state, strategy states, all organism/cell data)
- ✅ Compilation verified with Gradle protobuf plugin

**Note:** These contracts define the data structures used by SimulationEngine for data extraction and by future PersistenceService/Indexers for storage and analysis.

#### Phase 2.1: SimulationEngine ✅ COMPLETED
**Status:** Implemented and functional
- ✅ Extracts complete simulation state using Runtime's public API only
- ✅ Generates Protobuf messages (SimulationMetadata, TickData)
- ✅ Supports configurable sampling intervals (N=1 for every tick, N>1 for sampling)
- ✅ Implements auto-pause functionality at configured tick numbers
- ✅ Captures complete checkpoint data for future resume capability
- ✅ Outputs to queue resources (tickData, metadataOutput)
- ✅ Performance optimized: Cell serialization with hybrid approach (~350ns per cell)
- ✅ IdempotencyTracker and DeadLetterQueue resources integrated

**Documentation:** See `10_SIMULATIONENGINE.md` for detailed implementation specification.

#### Phase 2.2: Storage Resource Abstraction
**Status:** Specification complete, ready for implementation
- Storage capability interfaces (IStorageReadResource, IStorageWriteResource)
- Streaming interfaces (MessageWriter, MessageReader) with Protobuf-aware design
- FileSystemStorageResource with atomic writes (temp file + fsync + rename)
- MonitoredStorageWriter and MonitoredStorageReader wrappers with metrics
- DummyWriterService and DummyReaderService for testing
- Hierarchical key structure supporting simulation runs and batches
- Length-prefixed Protobuf format for efficient streaming read/write

**Documentation:** See `11_STORAGE_RESOURCE.md` for detailed implementation specification.

**Key Design Decisions:**
- Protobuf-aware storage (services work with MessageLite objects, storage handles serialization)
- Atomic writes ensure indexers never see partial files
- Hybrid metrics (buckets + 10% sampling) for 1.2% overhead, ~10% accuracy
- Batch filenames: `batch_[19-digit-start]_[19-digit-end].pb` for lexicographic = chronological sort
- Usage types: `storage-read` and `storage-write` (direct variants deferred)

#### Phase 2.3: PersistenceService
**Status:** Specification complete, ready for implementation
- Consumes TickData batches from queue (using drainTo with configurable size and timeout)
- Writes batches to storage using MessageWriter (streaming, atomic)
- Supports competing consumers (multiple instances for throughput)
- Generates batch filenames with zero-padded tick ranges
- Optional idempotency tracking for bug detection (tick-level deduplication)
- Retry logic with exponential backoff for transient failures
- Failed batches sent to DLQ using existing DeadLetterMessage (no new protobuf definitions)
- Graceful shutdown writes partial batches without data loss
- Tracks metrics: batches written, ticks written, bytes written, batches failed, duplicate ticks detected

**Documentation:** See `12_PERSISTENCE_SERVICE.md` for detailed implementation specification.

**Key Design Decisions:**
- Uses existing `DeadLetterMessage` from `system_contracts.proto` for DLQ (maintains clean architecture by avoiding infrastructure→domain dependencies)
- Failed batch data serialized to bytes and stored in `original_message` field, metadata in `metadata` map
- Validates batch consistency (first and last tick must have same simulationRunId)
- Idempotency tracking optional: for bug detection only (should always be zero duplicates)
- Default configuration: 1000 ticks per batch, 5s timeout, 3 retries with 1s initial backoff

**Dependencies:**
- Requires Phase 2.2 (Storage Resource) implementation complete
- Uses Phase 2.0 contracts (TickData, SimulationMetadata)
- Uses existing DeadLetterMessage from system_contracts.proto
- Integrates with Phase 2.1 output (SimulationEngine queue outputs)

#### Phase 2.4: Indexer Services
**Status:** Specification pending
- Family of specialized indexers reading batches from storage
- Each indexer focuses on specific data aspects (environment, organisms, metadata, etc.)
- Database-coordinated competing consumers (check "batch already indexed?" before processing)
- Uses MessageReader for streaming batch processing (O(1) memory)
- Can run concurrently with persistence or batch process after completion
- Exact indexer specializations to be determined based on analysis requirements

**Dependencies:**
- Requires Phase 2.2 (Storage Resource) implementation complete
- Uses Phase 2.0 contracts (TickData, SimulationMetadata)
- Requires database resources (initially SQLite, later PostgreSQL)

### Phase 3: Production Resources
- Cloud storage resources (S3, GCS)
- Database resources (PostgreSQL, Cassandra)  
- Queue resources (SQS, Kafka)
- Metrics export and observability

### Phase 4: Analysis & Web API
- Complete family of specialized IndexerServices (types determined based on experience)
- Competing consumers scaling for high-throughput indexers
- Batch processing capability (IndexerServices reading from archived storage)
- HttpApiServer with REST endpoints
- Performance optimization and production hardening

This design ensures maximum simulation performance while providing enterprise-grade reliability, observability, and operational flexibility.
