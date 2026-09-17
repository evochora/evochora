# Proposal: Persistence Service Implementation

**Prerequisite:** This plan assumes that the universal resource injection framework described in `09_RESOURCE_FLEX.md` has been fully implemented first.

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

### Step 3: Configuration and Dependency Injection Strategy

*   **Goal:** To define a flexible and reusable way for services to access storage backends without hard-coding dependencies. This pattern will apply to all storage types.

#### 3.1. Central `storage` block in `evochora.conf`

*   All storage provider instances (for both raw data and indexer databases) will be defined in a single, top-level `storage` block within the `pipeline` configuration.
*   Each definition will have a unique name (e.g., `raw-filesystem-storage`, `indexer-h2-database`), and specify its `className` and `options`.

#### 3.2. Role of the `ServiceManager`

*   The `ServiceManager` will be responsible for pre-instantiating all providers defined in the central `storage` block upon startup.
*   When creating a service (like `PersistenceService`), the `ServiceManager` will read a key from the service's `options` (e.g., `storageProvider = "raw-filesystem-storage"`).
*   It will then look up the pre-instantiated provider by that name and inject it into the service's constructor.

#### 3.3. Type Safety and Fail-Fast Behavior

*   The service's constructor will declare the specific interface it expects (e.g., `IRawStorageProvider`).
*   The `ServiceManager` will perform a type check before injection. If the configured provider does not implement the required interface, the `ServiceManager` **must** fail on startup with a clear, informative error message. This prevents runtime errors due to misconfiguration.

### Step 4: Create the Raw Storage Abstraction

*   **Goal:** Define a clear interface for writing raw simulation data, separating the logic from the storage medium.
*   **Actions:**
    *   Create a new package `org.evochora.datapipeline.storage.raw`.
    *   Inside this package, define a public Java interface named `IRawStorageProvider` that extends `AutoCloseable`.
    *   The interface must define the following methods:
        *   `void initialize(String simulationRunId) throws IOException;`
        *   `void writeContext(PipelineContracts.SimulationContext context) throws IOException;`
        *   `void writeTicks(List<PipelineContracts.RawTickData> batch) throws IOException;`
        *   `void close() throws IOException;`

#### 4.1. Implement the Filesystem Storage Provider

*   **Goal:** Create the first implementation of the storage provider that writes to the local filesystem.
*   **Action:** Create a class `FileSystemRawStorageProvider` in the `...storage.raw` package that implements the `IRawStorageProvider` interface.
*   **Implementation Requirements:** The implementation must fulfill all requirements for directory creation (including for the DLQ), file naming, and length-delimited binary writing as laid out in the architectural plans.

### Step 5: Error Handling and Reliability Strategy

*   **Goal:** Define a robust error handling strategy that can distinguish between transient/data-related errors and persistent system errors.
*   **Core Principle:** The pipeline should continue processing if a single batch fails (potential data corruption), but must stop if a systemic issue (like disk full) is detected.

#### 5.1. Dead-Letter Queue (DLQ) for Failed Batches

*   **Concept:** A "Dead-Letter Queue" will be implemented as a separate directory on the filesystem. When a batch of ticks cannot be written to the primary storage location, the service will attempt to write it to the DLQ for later manual inspection.
*   **`IRawStorageProvider` Interface Update:** The `IRawStorageProvider` interface must be extended with a new method:
    *   `void writeTicksToDLQ(List<PipelineContracts.RawTickData> batch) throws IOException;`
*   **`FileSystemRawStorageProvider` Implementation Update:**
    *   The `initialize()` method must also create a DLQ directory, e.g., `runs/{simulationRunId}/raw_data_dlq`.
    *   The `writeTicksToDLQ()` method should write a failed batch to this directory, using the same file naming and serialization format as the primary `writeTicks()` method.

#### 5.2. `PersistenceService` Error Handling Logic

*   **Goal:** Implement the logic to handle write failures gracefully.
*   **Implementation Requirements:**
    *   When the `PersistenceService` attempts to flush a batch using `storageProvider.writeTicks()` and catches an `IOException`, it must **not** immediately stop.
    *   Instead, it must log a `WARN`-level message indicating the primary write failure.
    *   It must then immediately attempt to call `storageProvider.writeTicksToDLQ()` with the same failed batch.
    *   **If the DLQ write succeeds:** The service should log a `WARN` message confirming the batch was moved to the DLQ and then **continue processing** the next batch from the channel as normal.
    *   **If the DLQ write also fails:** This strongly indicates a systemic, non-recoverable error (e.g., disk full, permissions error). The service must log a concise `ERROR`-level message and **stop processing** (e.g., break its main loop and terminate).

#### 5.3. Logging Requirements

*   **Goal:** Ensure log messages are concise and actionable, without excessive noise.
*   **Requirement:** For both `WARN` (DLQ) and `ERROR` (service failure) scenarios, the log message must be a single, clear line. It must **not** include a full Java stack trace.
*   **Required Information in Log Message:**
    *   The `simulationRunId`.
    *   The tick range of the failed batch (e.g., "ticks 1000-1999").
    *   A short, human-readable description of the error (e.g., the message from the caught `IOException`, like "Permission denied" or "No space left on device").

### Step 6: Monitoring and Metrics

*   **Goal:** Provide essential operational metrics for the `PersistenceService` by integrating with the existing `ServiceManager` status display.
*   **Implementation Requirements:**
    *   **Error Counting:** The `PersistenceService` must maintain an internal, thread-safe counter (e.g., an `AtomicInteger`) that is incremented every time a batch is successfully written to the Dead-Letter Queue (DLQ).
    *   **Status Reporting:** The service must override the `getActivityInfo()` method from `AbstractService`. This method must return a formatted string that displays the current value of the DLQ counter, for example: `DLQ Batches: 3`.
    *   **Throughput (Ticks per Second):** No specific implementation is needed for this metric inside the service. This will be automatically measured and displayed by the `ServiceManager` based on its monitoring of the service's input channel.

### Step 7: Implement the Persistence Service

*   **Goal:** Create the service responsible for consuming data from the channels and writing it to storage.
*   **Action:** Create a new package `org.evochora.datapipeline.services.persistence` and inside it, the class `PersistenceService`.

> **Architectural Note:** The `PersistenceService` **must** wait for and process the `SimulationContext` message *before* it begins processing the `RawTickData` stream. This is a deliberate design choice to guarantee a consistent data layout for downstream consumers.

*   **Implementation Requirements:**
    *   The `PersistenceService` class must inherit from `AbstractService`.
    *   Its constructor must be the standard constructor: `public PersistenceService(Config options, Map<String, List<Object>> resources)`.
    *   In the constructor, it must retrieve its required dependencies (`IRawStorageProvider`, `IInputChannel`) from the `resources` map by their port names. It is responsible for validating the presence and correct type of these dependencies, throwing an exception on mismatch to ensure fail-fast behavior.
    *   The `run()` method must first perform a blocking read on its context input channel to receive the `SimulationContext`.
    *   After receiving the context, it must use the `storageProvider` to initialize the storage and persist the context.
    *   It must then enter a loop to read `RawTickData` from the `tickData` input channel.
    *   A batching mechanism must be implemented. A batch is flushed to the `storageProvider` when its size reaches the configured `batchSize` or a `batchTimeoutMs` has passed since the last flush. The service should poll the channel (e.g., with a 100ms timeout) to ensure the timeout can be checked even if no new ticks arrive.
    *   Before terminating, a final flush of any remaining data in the batch must be performed.

### Step 6: Refactor `SimulationEngine`

*   **Goal:** Adapt the `SimulationEngine` to produce data using the new Protobuf-generated classes and to use the new DI pattern.
*   **Action:** Modify `SimulationEngine.java` to replace all instantiations of the old contract POJOs (e.g., `new RawTickData()`) with the builder pattern from the Protobuf-generated classes (e.g., `PipelineContracts.RawTickData.newBuilder()...build()`). The constructor must also be updated to the new standard signature.

### Step 7: Update Configuration

*   **Goal:** Re-wire the pipeline in the main configuration file to use the new `PersistenceService` and the universal resource syntax.
*   **Action:** Modify `evochora.conf` according to the new structure:
    *   Define the `FileSystemRawStorageProvider` and all required channels in the central `pipeline.resources` block.
    *   In the `persistence-service` definition, use the `resources` block to map logical port names (e.g., `storageTarget`) to the centrally defined resources.
    *   Ensure the `startupSequence` starts the `persistence-service` before the `simulation-engine`.

## 3. Testing Strategy

All tests must follow these core principles:
*   **Isolation:** Tests must be self-contained and not depend on external configuration files (e.g., `evochora.conf`). Dependencies like storage providers or channels should be mocked or created programmatically for the test.
*   **No `Thread.sleep`:** Asynchronous operations must be tested using reliable mechanisms like polling with timeouts (e.g., using a library like Awaitility) to wait for specific conditions to be met.
*   **Automatic Cleanup:** Tests must not leave any artifacts (e.g., files or directories) on the filesystem after they complete. Framework features like JUnit 5's `@TempDir` should be used for this purpose.

### 3.1. `FileSystemRawStorageProvider` Tests

*   **Goal:** Verify that the `FileSystemRawStorageProvider` correctly interacts with the filesystem in isolation.
*   **Setup:** Each test method must use a temporary directory provided by the testing framework to ensure all created files and folders are automatically deleted.
*   **Test Cases:**
    *   **Initialization:** A test must verify that calling `initialize()` creates the expected directory structure (`runs/{simulationRunId}/raw_data`) within the temporary directory.
    *   **Context Writing:** A test must verify that `writeContext()` creates a `context.bin` file in the correct location and that this file is not empty.
    *   **Tick Writing:**
        *   Verify that `writeTicks()` with a non-empty batch creates a binary file with the correct naming convention (e.g., `ticks_000000000-000000999.bin`).
        *   Verify that the content of the created file is correct by reading the data back and deserializing it into the expected Protobuf messages, confirming that the length-delimited format was written correctly.
        *   Verify that calling `writeTicks()` with an empty or null list results in no file being created.

### 3.2. `PersistenceService` Unit Tests

*   **Goal:** Verify the internal logic of the `PersistenceService` (batching, timeouts, channel interaction) without any actual filesystem interaction.
*   **Setup:** The `IRawStorageProvider` dependency must be mocked (e.g., using Mockito). The `resources` map passed to the service's constructor must be created programmatically in the test, containing the mocked storage provider and real `InMemoryChannel` instances for the inputs. The service must be run in a dedicated thread for each test, which is then gracefully shut down at the end.
*   **Test Cases:**
    *   **Context Handling:** Verify that after sending a `SimulationContext` message, the service calls the `initialize()` and `writeContext()` methods on the mock storage provider exactly once and with the correct data.
    *   **Batching by Size:**
        *   Send exactly `batchSize` number of `RawTickData` messages to the input channel.
        *   Verify that the `writeTicks()` method on the mock storage provider is called exactly once.
        *   Verify that the list of ticks passed to `writeTicks()` has a size of `batchSize`.
    *   **Batching by Timeout:**
        *   Send fewer than `batchSize` messages (e.g., one message).
        *   Wait for a period longer than the configured `batchTimeoutMs` without sending more messages.
        *   Verify that `writeTicks()` is called on the mock provider due to the timeout.
    *   **Shutdown Flush:**
        *   Send a few messages (less than `batchSize`).
        *   Immediately stop the service.
        *   Verify that `writeTicks()` is called on the mock provider with the remaining messages as part of the shutdown sequence.
    *   **Error Handling (DLQ Success):** A test must verify that if the mock storage provider's `writeTicks()` method throws an `IOException`, the service correctly calls the `writeTicksToDLQ()` method and continues running.
    *   **Error Handling (DLQ Failure):** A test must verify that if both `writeTicks()` and `writeTicksToDLQ()` throw an `IOException`, the service stops its processing loop and terminates gracefully.
    *   **Metrics Reporting:** A test must verify that after a successful DLQ write, the `getActivityInfo()` method returns the correct, updated string (e.g., "DLQ Batches: 1").

### 3.3. Integration Test

*   **Goal:** Verify that the `PersistenceService` and `FileSystemRawStorageProvider` work correctly together.
*   **Setup:** The test must use a real `FileSystemRawStorageProvider` configured with a temporary directory (`@TempDir`) and a real `PersistenceService`.
*   **Test Case:**
    *   Simulate a mini-pipeline run by sending one `SimulationContext` and a stream of `RawTickData` messages (e.g., enough for 2-3 batches) to the service's input channels.
    *   After sending the data, shut down the service and wait for its thread to terminate.
    *   Verify that the expected files (`context.bin` and the correctly named `ticks_...bin` files) exist in the temporary directory.
    *   Verify the integrity of the data by reading the files, deserializing all Protobuf messages, and asserting that their content matches the data that was originally sent.
*   **Additional Test Case (Error Handling):** An integration test should be added where filesystem permissions are manipulated to cause a write failure, verifying that the DLQ file is created correctly.

### 10.1 `ServiceManager` Configuration Tests (New Requirement)
*   **Goal:** Verify the dependency injection and fail-fast logic for the universal resource system.
*   **Test Case:** A test must be created that attempts to start a `ServiceManager` with a configuration where the `persistence-service`'s `storageTarget` port references a resource of the wrong type (e.g., an `InMemoryChannel`). The test must assert that the `PersistenceService`'s constructor throws an exception (e.g., `ClassCastException` or `IllegalArgumentException`) and the pipeline fails to start.
