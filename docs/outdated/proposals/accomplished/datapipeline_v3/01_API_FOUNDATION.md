# Data Pipeline V3 - API Foundation Implementation

## Goal

Create the fundamental API interfaces that form the foundation for the entire data pipeline system. These interfaces define the contracts for resources, services, and dependency injection without implementing any concrete functionality.

## Success Criteria

Upon completion:
1. The project compiles successfully
2. All interfaces are documented with comprehensive Javadoc
3. A minimal test can be written using mock implementations
4. The API supports the Universal DI pattern described in the high-level concept

## Package Structure

All interfaces must be created in the `org.evochora.datapipeline.api` package and its sub-packages:

```
src/main/java/org/evochora/datapipeline/api/
├── resources/
│   ├── IResource.java
│   ├── IContextualResource.java  
│   ├── IWrappedResource.java
│   ├── ResourceContext.java
│   ├── IMonitorable.java
│   ├── OperationalError.java
│   └── wrappers/
│       └── queues/
│           ├── IInputQueueResource.java
│           └── IOutputQueueResource.java
└── services/
    ├── IService.java
    ├── ServiceStatus.java
    └── ResourceBinding.java
```

## Required Interfaces

### 1. Resource Interfaces

#### `IResource`
- **Purpose**: Base interface for all resources
- **Required Methods**:
  - `UsageState getUsageState(String usageType)`: Returns current resource state for specific usage context
  - `String getResourceName()`: Returns the unique name of this resource instance.
- **Required Inner Enum**: `UsageState` with values ACTIVE, WAITING, FAILED
- **State Semantics**:
  - **ACTIVE**: Resource is functioning normally for this usage type
  - **WAITING**: Resource is temporarily busy/blocked (e.g., queue full/empty)
  - **FAILED**: Resource has an error for this usage type

#### `ResourceContext`  
- **Purpose**: Context information passed to contextual resources during dependency injection
- **Created by**: ServiceManager during service instantiation by parsing resource URI
- **Usage**: Enables resources to create specialized wrappers based on how they're being used and configured
- **Required Fields**:
  - `serviceName`: String identifying the service requesting the resource
  - `portName`: String identifying the logical port within the service
  - `usageType`: String describing how the resource is being used (e.g., "queue-in", "storage-readonly")
  - `parameters`: Map of URI parameters for fine-tuning resource behavior (e.g., window=30, batch=100)
- **Implementation**: Should be a record for immutability

#### `IContextualResource`
- **Purpose**: Interface for resources that can return specialized wrappers based on usage context
- **Called by**: ServiceManager during dependency injection process
- **Usage**: Allows resources to inspect how they're being used and return appropriate wrapper objects
- **Required Methods**:
  - `IWrappedResource getWrappedResource(ResourceContext context)`: Returns the wrapped resource object to inject into the Resource  Binding
- **Requirements**: Must extend `IResource`

#### `IWrappedResource`
- **Purpose**: Marker interface for wrapped resources
- **Usage**: Identifies resources that are wrappers around other resources, created by IContextualResource
- **Requirements**: Must extend `IResource`

#### `IInputQueueResource<T>` (in wrappers.queues package)
- **Purpose**: A rich interface for queue-based resources that provide data, analogous to `java.util.concurrent.BlockingQueue`.
- **Type Parameter**: T represents the type of data this resource can provide.
- **Key Methods**:
  - `Optional<T> poll()`: Non-blocking retrieval.
  - `T take() throws InterruptedException`: Blocking retrieval.
  - `Optional<T> poll(long timeout, TimeUnit unit)`: Timeout-based retrieval.
  - `int drainTo(Collection<? super T> collection, int maxElements)`: Batch retrieval.

#### `IOutputQueueResource<T>` (in wrappers.queues package)
- **Purpose**: A rich interface for queue-based resources that accept data, analogous to `java.util.concurrent.BlockingQueue`.
- **Type Parameter**: T represents the type of data this resource can accept.
- **Key Methods**:
  - `boolean offer(T element)`: Non-blocking insertion.
  - `void put(T element) throws InterruptedException`: Blocking insertion.
  - `boolean offer(T element, long timeout, TimeUnit unit)`: Timeout-based insertion.
  - `void putAll(Collection<T> elements) throws InterruptedException`: Batch insertion.
  - `int offerAll(Collection<T> elements)`: Non-blocking batch insertion.

#### `IMonitorable`
- **Purpose**: Common interface for components that can provide monitoring metrics
- **Required Methods**:
  - `Map<String, Number> getMetrics()`: Returns metrics as key-value pairs (Prometheus-compatible)
  - `List<OperationalError> getErrors()`: Returns list of all errors since start
  - `void clearErrors()`: Clears the error list (admin function for CLI)
  - `boolean isHealthy()`: Returns true if component is operational, false if degraded/failed
- **Usage**: Can be implemented by resources (for resource-level monitoring) and bindings (for per-service monitoring)

### 2. Service Interfaces

#### `ResourceBinding`
- **Purpose**: Represents the connection between a service and a resource at a specific port
- **Required Fields**:
  - `context`: The complete ResourceContext used during dependency injection
  - `service`: Reference to the connected service
  - `resource`: Reference to the connected resource
- **State Access**: Binding state is obtained dynamically via `resource.getUsageState(context.usageType())`
- **Parameter Access**: URI parameters available via `context.parameters()`
- **Implementation**: Should be a record containing only structural information (no behavioral methods)

#### `ServiceStatus`
- **Purpose**: Complete status information for a service
- **Required Fields**:
  - `state`: Current service state (IService.State)
  - `bindings`: List of ResourceBinding objects showing resource connections
- **Implementation**: Should be a record for immutability

#### `OperationalError`
- **Purpose**: Represents operational errors that can occur in system components (resources, bindings, etc.)
- **Required Fields**:
  - `timestamp`: When the error occurred
  - `errorType`: Type/category of error (e.g., "CONNECTION_FAILED", "IO_ERROR", "TIMEOUT")
  - `message`: Human-readable error description
  - `details`: Optional additional error context (stack trace, etc.)
- **Implementation**: Should be a record for immutability

#### `IService`
- **Purpose**: Interface that all services must implement
- **Required Methods**:
  - `void start()`: Start the service
  - `void stop()`: Stop the service  
  - `void pause()`: Pause the service (must be resumable)
  - `void resume()`: Resume a paused service
  - `State getCurrentState()`: Get current service state (see inner enum)
- **Required Inner Enum**: `State` with values STOPPED, RUNNING, PAUSED, ERROR
- **State Semantics**:
  - **STOPPED**: Service is completely shut down, restart required for reactivation
  - **RUNNING**: Service is actively processing
  - **PAUSED**: Service is temporarily halted but can resume from current state
  - **ERROR**: Service encountered a fatal error and stopped
- **Optional Monitoring**: Services can optionally implement `IMonitorable` for detailed monitoring (metrics, errors, health)
- **Constructor Requirement**: All implementing services must have exactly one constructor with signature: `(String name, Config options, Map<String, List<IResource>> resources)`

## Implementation Guidelines

1. **Documentation**: All interfaces must have comprehensive Javadoc comments in English
2. **Type Safety**: Use generics where appropriate to ensure compile-time type checking
3. **Immutability**: Status and context objects should be immutable (records preferred)
4. **Thread Safety**: Consider thread-safety requirements in interface design
5. **Extension Points**: Interfaces should allow for future extension without breaking changes

## Non-Requirements

- Do NOT implement any concrete classes (only interfaces/enums/records)
- Do NOT add any business logic or functionality
- Do NOT worry about configuration loading or dependency injection logic
- Do NOT create any tests (this is pure API definition)

## Validation

The implementation is correct if:
1. All files compile without errors or warnings
2. The interfaces support the Universal DI pattern from the high-level concept
3. Mock implementations could theoretically be created for testing
4. The API is self-documenting through good interface design and Javadoc

This foundation will enable rapid development of the concrete implementations in subsequent phases.
