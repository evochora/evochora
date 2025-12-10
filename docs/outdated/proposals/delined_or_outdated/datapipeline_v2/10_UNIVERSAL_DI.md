# Proposal: Universal Dependency Injection Framework

This document specifies the final, universal, and flexible dependency injection (DI) framework for the datapipeline. It replaces all previous specialized injection mechanisms with a single, consistent pattern.

## 1. Core Architecture

The architecture is based on these principles:

1.  **Central Resource Definition:** All shared components (Channels, Storage Providers) are defined in a single, top-level `resources` block in `evochora.conf`.
2.  **Self-Describing Dependencies:** Each service declares its dependencies in a `resources` map. The dependency is described by a URI-like string that specifies the resource to be injected and, optionally, how it should be used.
3.  **Contextual Resources:** Complex resources (like channels) can implement an optional interface (`IContextualResource`) that allows them to "handle themselves." They can inspect the context in which they are being injected (e.g., as an input or an output) and return an appropriate object, such as a metric-collecting wrapper. Simple resources do not need to do this.
4.  **Standardized Service Constructor:** All services adhere to a single constructor signature, making them predictable and easy to manage.
5.  **Simplified ServiceManager:** The `ServiceManager` becomes a generic orchestrator. Its only job is to parse the configuration, ask resources to "handle themselves" if they can, and inject the final objects into the services.

## 2. Implementation Steps

### Step 1: Establish the Universal Configuration Syntax

*   **Goal:** Define a single, consistent HOCON syntax for defining and consuming all resources.
*   **Specification:**
    *   A single, top-level `resources` block must be added to the `pipeline` configuration. All previous definitions (like `channels` and `storage`) must be consolidated into this block.
    *   Inside each service definition, the old `inputs`, `outputs`, and `storageProviders` blocks are deprecated. They must be replaced by a single `resources` block.
    *   This block is a map where the key is the logical `portName` for the service, and the value is a **Resource URI**.
    *   The **Resource URI** is a string with the format `[usageType:]resourceName`. The `usageType:` prefix is **optional**.
        *   `resourceName`: The name of a resource defined in the central `resources` block.
        *   `usageType`: An optional string that tells a resource *how* it's being used (e.g., `channel-in`, `storage-readonly`).
*   **Example Syntax:**

    ```hocon
    pipeline {
      resources {
        tick-data-channel { ... }
        raw-data-storage { ... }
      }

      services {
        simulation-engine {
          resources {
            # Resource with a usage type
            tickOutput: "channel-out:tick-data-channel"
          }
        }
        persistence-service {
          resources {
            tickInput: "channel-in:tick-data-channel"
            # Resource without a usage type
            storageTarget: "raw-data-storage"
          }
        }
      }
    }
    ```

### Step 2: Define Core DI Abstractions

*   **Goal:** Create the new, optional interface that enables resources to be self-aware.
*   **Actions:**
    *   **`ResourceContext.java`:** Create a new record or simple data class. It must contain:
        *   `String serviceName`
        *   `String portName`
        *   `String usageType` (can be a default value like "default" or null if not specified in the config)
        *   `ServiceManager serviceManager` (to allow access to global components like the metrics registry)
    *   **`IContextualResource.java`:** Define a new public interface with a single method:
        *   `Object getInjectedObject(ResourceContext context);`

### Step 3: Refactor the `ServiceManager`

*   **Goal:** Transform the `ServiceManager` into a simple, generic DI container.
*   **Actions:**
    *   **Resource Instantiation:** The `ServiceManager` must first instantiate all components from the central `pipeline.resources` block.
    *   **Remove Old Logic:** All specific logic for `inputs`, `outputs`, and `storageProviders` must be deleted.
    *   **Implement New Generic Injection Logic:** When preparing dependencies for a service, the `ServiceManager` must:
        1.  Parse the resource URI string into its `usageType` (optional) and `resourceName` parts.
        2.  Retrieve the original resource object by its `resourceName`.
        3.  Check if the retrieved resource object implements the `IContextualResource` interface.
        4.  **If it does:** Create a `ResourceContext` and call `getInjectedObject(context)` to get the final object that should be injected.
        5.  **If it does not:** The original resource object itself is the final object to be injected.
        6.  Pass the map of final objects to the service's constructor.

### Step 4: Update All Services and Resources

*   **Goal:** Adapt all components to the new, unified system.
*   **Actions:**
    *   **Standardize all Service Constructors:** All services must be updated to use the single constructor `(Config options, Map<String, List<Object>> resources)`. They are responsible for retrieving and validating their own dependencies from the `resources` map.
    *   **Refactor `InMemoryChannel`:** This class must be updated to implement the `IContextualResource` interface. Its `getInjectedObject` method will contain the logic to inspect the `usageType` from the context (`channel-in` or `channel-out`) and return the appropriate `InputChannelBinding` or `OutputChannelBinding` wrapper. This ensures metrics collection continues to work.
    *   **Remove Old Interfaces:** The now-obsolete injection methods (`addInputChannel`, etc.) must be removed from `IService` and `AbstractService`.

### Step 5: Testing Strategy

*   **Goal:** Verify the new DI framework is robust and works as intended.
*   **Actions:**
    *   Create unit tests for the `ServiceManager`'s new URI parsing and dependency resolution logic.
    *   Create a unit test for the `InMemoryChannel`'s implementation of `getInjectedObject` to ensure it returns the correct wrapper based on the provided `usageType`.
    *   Create an integration test with a full `evochora.conf` example to verify that a pipeline with multiple services and different dependency types (with and without `usageType`) is wired together correctly.
