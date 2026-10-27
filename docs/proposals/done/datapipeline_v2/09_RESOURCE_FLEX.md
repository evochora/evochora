# Proposal: Universal Resource Injection Framework

This document specifies the plan to refactor the `ServiceManager` and the entire service ecosystem to use a single, universal, and flexible dependency injection (DI) mechanism. This new architecture will replace the separate, specialized injection logics for `inputs`, `outputs`, and `storageProviders`.

## 1. Core Architecture

The new architecture is based on these principles:

1.  **Central Resource Definition:** All shared, reusable components of the pipeline (Channels, Storage Providers, etc.) will be defined in a single, top-level `resources` block in `evochora.conf`.
2.  **Universal Dependency Declaration:** Each service will declare all its external dependencies in a single `resources` map within its configuration. This provides a unified way to request any type of resource.
3.  **Standardized Service Constructor:** All services will adhere to a single, standardized constructor signature, making them predictable and easy for the `ServiceManager` to instantiate.
4.  **Simplified ServiceManager:** The `ServiceManager`'s role is simplified to that of a generic DI container. It reads the central definitions, reads each service's dependency list, and injects the requested resources.
5.  **Self-Reporting Metrics:** Components (services and resources) are responsible for collecting and exposing their own metrics. The DI framework will not use wrappers for metric collection.

## 2. Implementation Steps

### Step 1: Establish the Universal Configuration Syntax

*   **Goal:** Define a single, consistent HOCON syntax for defining and consuming all resources.
*   **Specification:**
    *   A new top-level block named `resources` will be added to the `pipeline` configuration. All previously separate definitions (like `channels` and `storage`) must be moved into this block.
    *   The existing `inputs`, `outputs`, and `storageProviders` blocks inside a service's configuration are now deprecated and must be removed.
    *   Each service must now define a single `resources` block. This block is a map where the key is a logical `portName` and the value is either a single resource reference string or a list of resource reference strings.
*   **Example Syntax:**

    ```hocon
    # In evochora.conf
    pipeline {
      # 1. Central Resource Definitions
      resources {
        # Channels
        persistence-channel { className = "...", options = { ... } }
        monitoring-channel { className = "...", options = { ... } }

        # Storage Providers
        raw-filesystem { className = "...", options = { ... } }
        indexer-db { className = "...", options = { ... } }
      }

      services {
        simulation-engine {
          className = "..."
          resources {
            # Fan-Out: one port references a list of resources
            tickDataOut: ["persistence-channel", "monitoring-channel"]
          }
          options { ... }
        }
        
        complex-indexer {
          className = "..."
          resources {
            # Multiple ports, each referencing one resource
            rawDataSource: "raw-filesystem"
            indexedDataTarget: "indexer-db"
          }
          options { ... }
        }
      }
    }
    ```

### Step 2: Define the Standardized Service Constructor

*   **Goal:** Create a single, predictable constructor signature for all `IService` implementations.
*   **Action:** All services that inherit from `AbstractService` must now implement a single public constructor with the following signature:
    *   `public MyService(Config options, Map<String, List<Object>> resources)`
*   **Details:**
    *   `options`: The `Config` object from the service's `options` block in the configuration.
    *   `resources`: A map where the key is the `portName` from the configuration, and the value is a `List` of the instantiated resource objects. The `ServiceManager` will ensure this is always a `List`, even if only one resource was configured.

### Step 3: Refactor the `ServiceManager`

*   **Goal:** Transform the `ServiceManager` into a simple, generic DI container that implements the new logic.
*   **Actions:**
    *   **Resource Instantiation:** The `ServiceManager` must first read the central `pipeline.resources` block and instantiate all defined resources, storing them by name in an internal map.
    *   **Remove Old Logic:** All specific logic for `inputs`, `outputs`, and `storageProviders` must be completely deleted.
    *   **Implement New Generic Injection Logic:** When instantiating a service, the `ServiceManager` must:
        1.  Read the service's `resources` map from the configuration.
        2.  For each `portName`, resolve the reference(s) to the actual resource object(s) from its internal map.
        3.  Create a `Map<String, List<Object>>` containing the fully resolved dependencies for that service.
        4.  Invoke the service's standardized constructor, passing the `options` and the newly created resources map.
        5.  **Type Safety:** The `ServiceManager` is not responsible for type checking. This responsibility is delegated to the service itself.

### Step 4: Refactor All Services

*   **Goal:** Adapt all existing services to conform to the new universal DI pattern.
*   **Actions for each service:**
    *   The constructor must be changed to the new standard signature: `(Config options, Map<String, List<Object>> resources)`.
    *   Inside the constructor, the service must access its required dependencies from the passed-in `resources` map.
    *   The service is responsible for its own validation and type checking. For example, a service expecting exactly one storage provider for a port must check that the list for that port is not null and contains exactly one element, and then perform a cast to the expected interface type. This ensures fail-fast behavior at startup.

### Step 5: Refactor Core Service Interfaces

*   **Goal:** Remove all now-obsolete injection methods from the core service interfaces.
*   **Actions:**
    *   **`IService.java` & `AbstractService.java`:** The methods `addInputChannel`, `addOutputChannel`, and `addStorageProvider` are now redundant and must be **removed**. The framework will no longer use "setter injection".
    *   **`ChannelBinding` Objects:** These objects are no longer needed for dependency injection. However, they are still crucial for **metrics collection**. The `ServiceManager` will now create these bindings *after* instantiating the services by inspecting the resolved dependencies. If a dependency is an `IInputChannel` or `IOutputChannel`, the `ServiceManager` will wrap it in the appropriate `Binding` for its internal metrics tracking. The services themselves will receive the raw, unwrapped channel objects.
