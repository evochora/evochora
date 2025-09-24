# Data Pipeline V3 - Core Resource Implementation (Phase 1.2)

## Goal

Implement the foundational InMemoryBlockingQueue resource that serves as the core communication mechanism for the data pipeline. This resource must demonstrate all key concepts: contextual wrapping, service-specific monitoring, and universal resource interfaces.

## Success Criteria

Upon completion:
1. InMemoryBlockingQueue compiles and implements all required interfaces
2. Smart wrapper creation works based on usage type (queue-in vs queue-out)
3. Service-specific metrics are collected separately from global resource metrics
4. Unit tests verify contextual wrapping and monitoring functionality
5. Basic producer/consumer workflow functions end-to-end

## Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)

## Implementation Requirements

### Core Resource: InMemoryBlockingQueue

**File:** `src/main/java/org/evochora/datapipeline/resources/InMemoryBlockingQueue.java`

**Required Interfaces:**
- `IResource` (base interface with UsageState support)
- `IContextualResource` (smart wrapper creation)
- `IMonitorable` (global resource metrics)
- `IInputResource<T>` (via wrappers)
- `IOutputResource<T>` (via wrappers)

### Coding Standards

#### Documentation Requirements
- **All public classes and interfaces**: Comprehensive Javadoc in English
- **All public methods**: Javadoc with @param and @return documentation
- **Inner classes**: Javadoc explaining purpose and usage
- **Complex logic**: Inline comments explaining algorithm (especially throughput calculation)

#### Naming Conventions
- **Classes**: PascalCase (InMemoryBlockingQueue, QueueConsumerWrapper)
- **Methods**: camelCase (getUsageState, getMetrics)
- **Constants**: UPPER_SNAKE_CASE for any constants
- **Generics**: Single letter T for type parameters

#### Error Handling
- **Checked exceptions**: Handle InterruptedException properly (restore interrupt status)
- **Unchecked exceptions**: Use IllegalArgumentException for invalid parameters
- **Configuration errors**: Catch ConfigException and wrap in IllegalArgumentException with clear message
- **Error recording**: All exceptions should be recorded as OperationalError in getErrors()
- **Null safety**: Validate all inputs, no null returns from public methods

#### Configuration Handling
- **Use TypeSafe Config**: All configuration access via `com.typesafe.config.Config` methods
- **Default values**: Use Config.getInt(key, defaultValue) pattern for robust defaults
- **Validation**: Validate all configuration values in constructor
- **Path checking**: Use hasPath() before accessing optional configuration

### Wrapper Architecture

#### IContextualResource.getWrappedResource() Implementation
**Required behavior:**
- `usageType = "queue-in"`: Return new `QueueConsumerWrapper` instance
- `usageType = "queue-out"`: Return new `QueueProducerWrapper` instance  
- Other usageTypes: Throw IllegalArgumentException

#### QueueConsumerWrapper (Inner Class)
**Location:** Inner class of InMemoryBlockingQueue
**Purpose:** Service-specific wrapper for consuming from queue
**Interfaces:** Must implement `IInputResource<T>`, `IWrappedResource`, `IMonitorable`
**Responsibilities:**
- Delegate receive() calls to parent queue's take() method
- Track service-specific metrics: messages consumed, throughput
- Handle queue-empty scenarios in getUsageState()

#### QueueProducerWrapper (Inner Class)
**Location:** Inner class of InMemoryBlockingQueue  
**Purpose:** Service-specific wrapper for producing to queue
**Interfaces:** Must implement `IOutputResource<T>`, `IWrappedResource`, `IMonitorable`
**Responsibilities:**
- Delegate send() calls to parent queue's put() method
- Track service-specific metrics: messages sent, throughput
- Handle queue-full scenarios in getUsageState()

### Resource State Logic

**IResource.getUsageState(usageType) implementation:**
- `"queue-in"`: WAITING if queue is empty, ACTIVE if has data, FAILED if queue error
- `"queue-out"`: WAITING if queue is at capacity, ACTIVE if has space, FAILED if queue error
- Unknown usageType: Throw IllegalArgumentException with clear error message

### Monitoring Requirements

#### Global Resource Metrics (IMonitorable)
**InMemoryBlockingQueue.getMetrics():**
- `"capacity"`: Queue capacity (-1 for unlimited)
- `"current_size"`: Current number of elements in queue (O(1) operation)
- `"throughput_per_sec"`: Messages per second from all services combined (calculated on-demand using configured time window)

#### Service-Specific Metrics (Wrapper IMonitorable)
**ConsumerWrapper.getMetrics():**
- `"messages_consumed"`: Messages consumed by this specific service (atomic counter)
- `"throughput_per_sec"`: Messages per second calculated on-demand using configured time window

**ProducerWrapper.getMetrics():**
- `"messages_sent"`: Messages sent by this specific service (atomic counter)
- `"throughput_per_sec"`: Messages per second calculated on-demand using configured time window

### Performance Optimization

**On-Demand Calculation:**
- Throughput is calculated only when `getMetrics()` is called (not on background timer)
- Uses sliding window of message timestamps within configurable time window
- Default time window: 5 seconds (configurable via resource options)

**Low-Overhead Tracking:**
- Only timestamp recording per message (single long assignment)
- Atomic counters for message counts (minimal overhead)
- No background threads for metric calculation

### Configuration Support

**Constructor:** `InMemoryBlockingQueue(com.typesafe.config.Config options)`

**Required configuration options:**
- `capacity`: Integer (default: 1000, must be > 0 for memory safety)
- `throughputWindowSeconds`: Integer (default: 5, time window for moving average calculation)

**TypeSafe Config Usage:**
```java
public InMemoryBlockingQueue(Config options) {
    this.capacity = options.getInt("capacity", 1000);
    this.windowSeconds = options.getInt("throughputWindowSeconds", 5);
    
    if (capacity <= 0) {
        throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
    }
    
    this.queue = new ArrayBlockingQueue<>(capacity);
}
```

**URI Parameter Support:**
The InMemoryBlockingQueue supports the following URI parameters:
- `window`: Integer - Override throughput window seconds for this specific binding (overrides resource-level throughputWindowSeconds)

Example usage:
```hocon
simulation-engine {
  resources {
    tickOutput: "queue-out:test-queue?window=3"  # 3-second window for this binding
  }
}
```

**Example HOCON:**
```hocon
pipeline {
  resources {
    test-queue {
      className = "org.evochora.datapipeline.resources.InMemoryBlockingQueue"
      options {
        capacity = 10000
        throughputWindowSeconds = 5  # Default window
      }
    }
  }
  
  services {
    simulation-engine {
      resources {
        # Override throughput window for this specific binding:
        tickOutput: "queue-out:test-queue?window=3"
      }
    }
  }
}
```

## Technical Requirements

### Implementation Details

#### Internal Queue Implementation
- **Always bounded**: Use `java.util.concurrent.ArrayBlockingQueue<T>` for memory safety and optimal performance
- **Capacity validation**: Constructor must reject capacity <= 0 with IllegalArgumentException
- **Field type**: `ArrayBlockingQueue<T>` (no need for interface since only one implementation)

#### Thread Safety
- All operations must be thread-safe for concurrent access from multiple services
- Use BlockingQueue's built-in thread-safety (take(), put(), size())
- All wrapper operations must be atomic and thread-safe

#### Error Handling
- Queue operations should handle InterruptedException gracefully
- Implement proper error reporting via `IMonitorable.getErrors()`
- Failed operations should be recorded as OperationalError instances

#### Performance Requirements
- Minimize object allocation in wrapper methods (reuse timestamp collections)
- Use atomic operations for counters (AtomicLong for message counts)
- Efficient sliding window calculation for throughput

#### Code Quality
- **Thread safety**: All methods must be thread-safe without external synchronization
- **Immutability**: Return defensive copies of mutable collections (e.g., in getErrors())
- **Interface segregation**: Wrapper classes should be package-private inner classes
- **Single responsibility**: Each wrapper handles only one usage type
- **Configuration validation**: Validate all configuration parameters in constructor

## Testing Requirements

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/resources/InMemoryBlockingQueueTest.java`

**Required test cases:**
1. **Basic Queue Operations**: send/receive functionality
2. **Contextual Wrapping**: Different wrappers for queue-in vs queue-out
3. **Service-Specific Metrics**: Separate metric tracking per wrapper
4. **Global Resource Metrics**: Resource-level aggregated metrics
5. **State Calculation**: Correct ACTIVE/WAITING/FAILED states based on queue state
6. **Error Handling**: Proper error recording and reporting
7. **Thread Safety**: Concurrent access verification

**Test tag:** All tests should be tagged `@Tag("unit")`

## Non-Requirements

- Do NOT implement complex backpressure mechanisms yet
- Do NOT add persistence/durability (pure in-memory)
- Do NOT implement advanced monitoring features (keep metrics simple)

## Validation

The implementation is correct if:
1. All interfaces are correctly implemented with proper method signatures
2. Contextual wrapping creates appropriate wrapper types based on usage
3. Service-specific metrics are isolated per service instance
4. Queue state logic correctly reflects ACTIVE/WAITING/FAILED conditions based on queue state
5. All unit tests pass and achieve expected behavior
6. Generated code integrates seamlessly with existing API Foundation

This core resource will serve as the foundation for all subsequent service implementations and demonstrate the complete resource architecture pattern.
