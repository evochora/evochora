# Proposal: Implement the v2 Datapipeline Persistence Service

This document outlines the step-by-step plan to implement the foundational persistence layer for the v2 datapipeline. The goal is to replace the old, monolithic indexer with a new, robust service that captures raw simulation data and stores it durably on the filesystem.

## 1. Core Architecture

The system will follow this data flow:

1.  The `SimulationEngine` produces data objects (`SimulationContext`, `RawTickData`).
2.  These objects are sent through in-memory channels.
3.  A new `PersistenceService` listens to these channels.
4.  The `PersistenceService` serializes the data objects into a binary format using **Protocol Buffers (Protobuf)**.
5.  An `IRawStorageProvider` abstraction is used to write the serialized byte data to a storage backend.
6.  The initial implementation of the storage backend will be the local **filesystem**.

## 2. Implementation Steps

### Step 1: Refactor Existing Storage Packages

To ensure a clean package structure, we first refactor the existing storage components.

*   **Goal:** Create a new package `org.evochora.datapipeline.storage.indexer`.
*   **Action:** Move all existing classes and interfaces related to the H2 indexer database into this new package. This includes:
    *   `IEnvironmentStateWriter.java`
    *   `model/EnvironmentState.java`
    *   `model/Position.java`
    *   `impl/h2/H2SimulationRepository.java`

### Step 2: Set up Protocol Buffers

The goal of this step is to define the data contracts using Protobuf and configure the build process to generate the necessary Java code.

#### 2.1. Define the data contract (`.proto` file)

*   **Goal:** Create a new file at `src/main/proto/org/evochora/datapipeline/api/contracts/pipeline_contracts.proto` that defines the entire data contract for the pipeline.
*   **File Configuration:**
    *   Use `proto3` syntax.
    *   The package must be `org.evochora.datapipeline.api.contracts`.
    *   Set the Java options `java_multiple_files = false` and `java_outer_classname = "PipelineContracts"`.
*   **Required Enums:**
    *   `WorldTopology`: with values `BOUNDED` (0) and `TORUS` (1).
    *   `StackValueType`: with values `LITERAL` (0) and `POSITION` (1).
*   **Required Messages:**
    *   Define a message for every data object that was previously a POJO in the `...api.contracts` package. Use `snake_case` for field names. The fields must exactly match the structure of the old POJOs, including list (`repeated`) and map (`map<key, value>`) types.
    *   **`Coordinates` message:** Must contain a `repeated int32 values = 1;`.
    *   **`RawOrganismState` message:** Must use the `Coordinates` message for all position-related fields. Its `data_stack` field must be a `repeated StackValue`.
    *   **`StackValue` message:** Must use a `oneof` block to represent either a `literal_value` (int32) or a `position_value` (Coordinates).
    *   **`ProgramArtifact` message:**
        *   Maps with non-standard keys (like `int[]` or complex objects) must be modeled appropriately. For example, a `map<int[], ...>` can be modeled as `map<int32, ...>` where the key is a linear address, or by using helper messages. Ensure that `machine_code_layout`, `initial_world_objects`, and `token_map` are correctly modeled, potentially using `string` keys as a substitute for complex key types if necessary, and document this choice.
        *   A `map<string, List<string>>` must be modeled using a helper message like `message StringList { repeated string values = 1; }`.
        *   Nested maps for `token_lookup` must be modeled using a chain of helper messages (`TokenLookupFile`, `TokenLookupLine`, `TokenInfoList`).

#### 2.2. Update the Build Process

*   **Goal:** Configure Gradle to automatically compile the `.proto` file into Java classes.
*   **Actions:**
    *   Modify `build.gradle.kts` to add the official Google Protobuf plugin (ID `com.google.protobuf`, e.g., version `0.9.4`).
    *   Add the necessary implementation dependencies for `com.google.protobuf:protobuf-java` and `com.google.protobuf:protobuf-java-util` (e.g., version `3.25.1`).
    *   Configure the `protobuf` block to use the protoc compiler artifact (e.g., `com.google.protobuf:protoc:3.25.1`) and to generate `java` sources.

#### 2.3. Clean up old POJOs

*   **Goal:** Remove the old Java-based data contracts.
*   **Action:** Delete all original Java files from the package `org.evochora.datapipeline.api.contracts`. They are now obsolete.

### Step 3: Create the Raw Storage Abstraction

*   **Goal:** Define a clear interface for writing raw simulation data, separating the logic from the storage medium.
*   **Actions:**
    *   Create a new package `org.evochora.datapipeline.storage.raw`.
    *   Inside this package, define a public Java interface named `IRawStorageProvider` that extends `AutoCloseable`.
    *   The interface must define the following methods:
        *   `void initialize(String simulationRunId) throws IOException;`
        *   `void writeContext(PipelineContracts.SimulationContext context) throws IOException;`
        *   `void writeTicks(List<PipelineContracts.RawTickData> batch) throws IOException;`
        *   `void close() throws IOException;`

#### 3.1. Implement the Filesystem Storage Provider

*   **Goal:** Create the first implementation of the storage provider that writes to the local filesystem.
*   **Action:** Create a class `FileSystemRawStorageProvider` in the `...storage.raw` package that implements the `IRawStorageProvider` interface.
*   **Implementation Requirements:**
    *   **`initialize(simulationRunId)`:** Must create a directory structure for the raw data, e.g., `runs/{simulationRunId}/raw_data`.
    *   **`writeContext(context)`:** Must serialize the `SimulationContext` message and write it to a file named `context.bin` in the run directory.
    *   **`writeTicks(batch)`:** Must create a filename based on the tick range in the batch (e.g., `ticks_000000000-000000999.bin`). It must then serialize each message from the batch and write it to this single file.
    *   **Serialization Format:** Inside the binary files, each Protobuf message must be written in a "delimited" format, meaning it must be preceded by its length (as a 4-byte integer) to allow for easy streaming and parsing later.

### Step 4: Implement the Persistence Service

*   **Goal:** Create the service responsible for consuming data from the channels and writing it to storage.
*   **Action:** Create a new package `org.evochora.datapipeline.services.persistence` and inside it, the class `PersistenceService`.

> **Architectural Note:** The `PersistenceService` **must** wait for and process the `SimulationContext` message *before* it begins processing the `RawTickData` stream. This is a deliberate design choice to guarantee a consistent data layout for downstream consumers.

*   **Implementation Requirements:**
    *   The `PersistenceService` class must inherit from `AbstractService`.
    *   Its constructor must accept a `Config` object and an `IRawStorageProvider` instance.
    *   The `run()` method must first perform a blocking read on its `contextData` input channel to receive the `SimulationContext`.
    *   After receiving the context, it must use the `storageProvider` to initialize the storage and persist the context.
    *   It must then enter a loop to read `RawTickData` from the `tickData` input channel.
    *   A batching mechanism must be implemented. A batch is flushed to the `storageProvider` when its size reaches the configured `batchSize` or a `batchTimeoutMs` has passed since the last flush. The service should poll the channel (e.g., with a 100ms timeout) to ensure the timeout can be checked even if no new ticks arrive.
    *   Before terminating, a final flush of any remaining data in the batch must be performed.

### Step 5: Refactor `SimulationEngine`

*   **Goal:** Adapt the `SimulationEngine` to produce data using the new Protobuf-generated classes.
*   **Action:** Modify `SimulationEngine.java` to replace all instantiations of the old contract POJOs (e.g., `new RawTickData()`) with the builder pattern from the Protobuf-generated classes (e.g., `PipelineContracts.RawTickData.newBuilder()...build()`).

### Step 6: Update Configuration

*   **Goal:** Re-wire the pipeline in the main configuration file to use the new `PersistenceService`.
*   **Action:** Modify `evochora.conf`:
    *   Remove any old indexer services (`debug-indexer`, `environment-state-indexer`).
    *   Add a new service definition for the `persistence-service`.
    *   Configure its `className` to point to `...services.persistence.PersistenceService`.
    *   Wire its `inputs` to the `raw-tick-data` and `context-data` channels.
    *   Define its `options` like `batchSize` and `batchTimeoutMs`.
    *   Ensure the `startupSequence` starts the `persistence-service` before the `simulation-engine`.
