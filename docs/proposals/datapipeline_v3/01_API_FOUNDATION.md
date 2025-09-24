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
│   ├── ResourceContext.java
│   ├── IInputResource.java
│   ├── IOutputResource.java
│   ├── IMonitorable.java
│   └── OperationalError.java
└── services/
    ├── IService.java
    ├── ServiceStatus.java
    └── ResourceBinding.java
```

## Required Interfaces

### 1. Resource Interfaces

#### `IResource`
- **Purpose**: Marker interface for all resources
- **Requirements**: Should be empty or contain minimal common functionality

#### `ResourceContext`  
- **Purpose**: Context information passed to contextual resources during dependency injection
- **Created by**: ServiceManager during service instantiation
- **Usage**: Enables resources to create specialized wrappers based on how they're being used
- **Required Fields**:
  - `serviceName`: String identifying the service requesting the resource
  - `portName`: String identifying the logical port within the service
  - `usageType`: Optional string describing how the resource is being used (e.g., "queue-in", "storage-readonly")
- **Implementation**: Should be a record for immutability

#### `IContextualResource`
- **Purpose**: Interface for resources that can return specialized wrappers based on usage context
- **Called by**: ServiceManager during dependency injection process
- **Usage**: Allows resources to inspect how they're being used and return appropriate wrapper objects
- **Required Methods**:
  - `IResource getInjectedObject(ResourceContext context)`: Returns the actual resource object to inject into the service
- **Requirements**: Must extend `IResource`

#### `IInputResource<T>`
- **Purpose**: Interface for resources that can provide data input capabilities
- **Type Parameter**: T represents the type of data this resource can provide
- **Required Methods**: 
  - Methods for consuming/reading data (exact method signatures left to implementer)

#### `IOutputResource<T>`
- **Purpose**: Interface for resources that can accept data output
- **Type Parameter**: T represents the type of data this resource can accept  
- **Required Methods**:
  - Methods for producing/writing data (exact method signatures left to implementer)

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
- **Purpose**: Information about a service's connection to a resource, optionally with monitoring capabilities
- **Required Fields**:
  - `portName`: The logical port name
  - `resourceType`: Type/category of the resource
  - `state`: Current binding state (see inner enum)
  - `throughput`: Optional throughput metric (messages/second or similar)
- **Required Inner Enum**: `State` with values ACTIVE, WAITING, FAILED
- **State Semantics**:
  - **ACTIVE**: Resource is functioning normally
  - **WAITING**: Resource is temporarily busy/overloaded, service is waiting
  - **FAILED**: Resource has an error, binding is not functional
- **Optional**: Can implement `IMonitorable` for detailed per-binding metrics and error tracking
- **Implementation**: Should be a record with inner enum

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
  - `ServiceStatus getServiceStatus()`: Get current status with resource bindings
- **Required Inner Enum**: `State` with values STOPPED, RUNNING, PAUSED, ERROR
- **State Semantics**:
  - **STOPPED**: Service is completely shut down, restart required for reactivation
  - **RUNNING**: Service is actively processing
  - **PAUSED**: Service is temporarily halted but can resume from current state
  - **ERROR**: Service encountered a fatal error and stopped
- **Optional Monitoring**: Services can optionally implement `IMonitorable` for detailed monitoring (metrics, errors, health)
- **Constructor Requirement**: All implementing services must have exactly one constructor with signature: `(Config options, Map<String, List<IResource>> resources)`

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
