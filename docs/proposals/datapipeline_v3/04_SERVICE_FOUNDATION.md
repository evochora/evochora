# Data Pipeline V3 - Service Foundation (Phase 1.3)

## Goal

Implement AbstractService as the foundational base class for all services in the data pipeline. This class provides common lifecycle management, thread handling, and resource utilities that eliminate code duplication across service implementations.

## Success Criteria

Upon completion:
1. AbstractService compiles and implements IService interface completely
2. Lifecycle management (start, stop, pause, resume) works correctly with proper state transitions
3. Thread management handles service execution and interruption gracefully
4. Utility methods provide convenient, type-safe access to injected resources
5. Unit tests verify all lifecycle scenarios and thread safety

## Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)
- Phase 1.2: Core Resource Implementation (completed)

## Implementation Requirements

### AbstractService Class

**File:** `src/main/java/org/evochora/datapipeline/services/AbstractService.java`

**Class Declaration:**
```java
public abstract class AbstractService implements IService {
    // Implementation details below
}
```

**Required Constructor:**
```java
protected AbstractService(com.typesafe.config.Config options, Map<String, List<IResource>> resources) {
    // Store configuration and resources for subclass access
    // Validate required configuration parameters
    // Initialize lifecycle management fields
}
```

**Required Implementation:**
- Must implement all `IService` interface methods completely
- Must provide resource access utilities for subclasses
- Must handle all lifecycle states correctly with proper thread management

### Core Functionality

#### Lifecycle Management
**State Transitions:**
- `start()`: STOPPED → RUNNING (creates and starts service thread)
- `stop()`: RUNNING/PAUSED → STOPPED (interrupts and joins service thread)
- `pause()`: RUNNING → PAUSED (sets pause flag, service should check periodically)
- `resume()`: PAUSED → RUNNING (clears pause flag, notifies waiting threads)
- `restart()`: Any state → STOPPED → RUNNING (stop + start sequence)

**Invalid State Transitions (Graceful Handling):**
- `start()` when RUNNING: Log warning, do nothing (idempotent)
- `stop()` when STOPPED: Log warning, do nothing (idempotent)
- `pause()` when STOPPED/ERROR: Log warning, ignore invalid operation
- `resume()` when RUNNING: Log warning, do nothing (idempotent)
- `resume()` when STOPPED/ERROR: Log warning, ignore invalid operation

**Thread Management:**
- Service runs in dedicated thread created in `start()`
- Thread name should be service class simple name
- Must handle InterruptedException gracefully
- `stop()` should interrupt thread and wait for completion (with timeout)

#### Resource Management
**Constructor:** `AbstractService(Config options, Map<String, List<IResource>> resources)`

**Resource Utilities:**
```java
protected <T> T getRequiredResource(String portName, Class<T> expectedType)
protected <T> List<T> getResources(String portName, Class<T> expectedType)
protected boolean hasResource(String portName)
```

**Method Behavior:**
- **getRequiredResource**: Returns exactly one resource, throws IllegalStateException if 0 or >1 resources
- **getResources**: Returns list of all resources for port (empty list if none configured)
- **hasResource**: Returns true if port has at least one configured resource

**Usage Examples:**
```java
// In service constructor:
IInputResource<DummyMessage> input = getRequiredResource("tickInput", IInputResource.class);
List<IOutputResource<String>> outputs = getResources("broadcasts", IOutputResource.class);

// Runtime type checking with clear error messages:
if (!IInputResource.class.isAssignableFrom(resource.getClass())) {
    throw new IllegalStateException("Resource at port 'tickInput' is not an IInputResource");
}
```

**Error Scenarios:**
- Port not configured → IllegalStateException with port name
- Wrong resource type → IllegalStateException with expected vs actual type
- Multiple resources for getRequiredResource → IllegalStateException with count


### AbstractService Interface

#### Public Methods (from IService)
```java
public void start()                    // Lifecycle: create and start service thread
public void stop()                     // Lifecycle: interrupt and join service thread  
public void pause()                    // Lifecycle: set pause flag
public void resume()                   // Lifecycle: clear pause flag and notify
public void restart()                  // Convenience: stop() + start()
public IService.State getCurrentState() // Return current lifecycle state
```

#### Protected Methods (for subclasses)
```java
protected <T> T getRequiredResource(String portName, Class<T> expectedType)
protected <T> List<T> getResources(String portName, Class<T> expectedType) 
protected boolean hasResource(String portName)
protected void checkPause() throws InterruptedException
protected final Config options         // Access to service configuration
protected final Map<String, List<IResource>> resources // Access to injected resources
```

#### Abstract Methods (must be implemented by subclasses)
```java
protected abstract void run() throws InterruptedException;
```

**Purpose:** Subclasses implement their specific service logic in this method
**Requirements:** 
- Must check for pause state periodically using `checkPause()`
- Must handle InterruptedException for graceful shutdown
- Should respect pause/resume mechanism

#### Pause/Resume Support
**Pause Check Pattern:**
```java
protected void checkPause() throws InterruptedException {
    synchronized (pauseLock) {
        while (isPaused() && getCurrentState() == State.PAUSED) {
            pauseLock.wait();
        }
    }
}
```

**Usage in service loop:**
```java
@Override
protected void run() throws InterruptedException {
    while (getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED) {
        checkPause(); // Blocks if paused
        
        // Service-specific work
        doWork();
    }
}
```

### Coding Standards

#### Documentation Requirements
- **All public classes and methods**: Comprehensive Javadoc in English
- **All protected methods**: Javadoc with @param and @return documentation
- **Abstract methods**: Clear contracts for subclasses with usage examples
- **Complex logic**: Inline comments explaining thread synchronization and state management
- **Synchronization**: Document thread-safety guarantees and locking strategies

#### Naming Conventions
- **Classes**: PascalCase (AbstractService)
- **Methods**: camelCase (getRequiredResource, checkPause)
- **Fields**: camelCase with descriptive names
- **Constants**: UPPER_SNAKE_CASE for any constants
- **Threads**: Use class simple name for thread naming

#### Error Handling
- **Checked exceptions**: Handle InterruptedException properly (restore interrupt status)
- **Unchecked exceptions**: Use IllegalArgumentException for invalid parameters
- **Configuration errors**: Catch ConfigException and wrap in IllegalArgumentException with clear message
- **Resource access**: Validate resource types and throw clear exceptions for mismatches
- **Invalid state transitions**: Log WARNING and ignore gracefully (no exceptions)
- **Null safety**: Validate all inputs, no null returns from public methods

#### Thread Safety
- **State management**: Use AtomicReference for state tracking
- **Pause/resume**: Proper synchronization with wait/notify pattern
- **Resource access**: Thread-safe resource utility methods
- **No external synchronization**: Should work without external locking
- **Immutability**: Return defensive copies of mutable collections

#### Code Quality
- **Single responsibility**: AbstractService handles only lifecycle and resource management
- **Interface segregation**: Clean separation between public and protected methods
- **Configuration validation**: Validate all configuration parameters in constructor
- **Resource lifecycle**: Proper cleanup of threads and resources in stop() method

#### Logging Conventions
**Logger Setup:**
```java
protected final Logger log = LoggerFactory.getLogger(this.getClass());
```

**Logging Rules (CRITICAL - must be followed by all services):**
- **INFO Level**: Only for service lifecycle events and user-triggered actions
  - Service started/stopped/paused/resumed
  - Response to CLI commands
  - Special events (e.g., simulation auto-pause at configured tick)
- **WARN Level**: For recoverable problems that user should know about
  - Resource temporarily unavailable (but retrying)
  - Configuration warnings (using defaults)
  - Performance degradation warnings
- **ERROR Level**: For serious problems requiring attention
  - Unrecoverable errors causing service failure
  - Configuration errors preventing startup
  - Resource failures requiring manual intervention

**Logging Restrictions (CRITICAL):**
- **No per-message logging**: Do NOT log every message/tick processed
- **No high-frequency logs**: Do NOT log routine operations
- **No DEBUG in production**: DEBUG level only for development debugging
- **Sparse but informative**: Interactive CLI should not be flooded with logs

**Logging Examples:**
```java
// ✅ GOOD - Lifecycle events (include service name)
log.info("{} started with {} organisms", this.getClass().getSimpleName(), count);
log.info("{} auto-paused at tick {} due to pauseTicks configuration", this.getClass().getSimpleName(), tickNumber);

// ✅ GOOD - User actions (include service name)
log.info("{} paused by user command", this.getClass().getSimpleName());
log.info("{} stopped by user command", this.getClass().getSimpleName());

// ❌ BAD - High frequency
log.info("Processing tick {}", tickNumber); // Every tick!
log.info("Sent message to queue"); // Every message!
```

**Service Name Pattern:**
- All lifecycle logs must include `this.getClass().getSimpleName()` as first parameter
- Enables CLI to clearly identify which service produced each log message
- Essential for multi-service pipeline debugging and monitoring

### Configuration Support

**TypeSafe Config Integration:**
```java
protected <T> T getConfigValue(String path, T defaultValue, Class<T> type) {
    if (!options.hasPath(path)) {
        return defaultValue;
    }
    // Type-safe extraction with proper error handling
}
```

**Service Options Access:**
- Subclasses access configuration via protected `options` field
- Provide utility methods for common configuration patterns
- Validate required configuration in constructor

## Testing Requirements

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/services/AbstractServiceTest.java`

**Required test cases:**
1. **Lifecycle Transitions**: All valid state transitions work correctly
2. **Invalid Transitions**: Invalid state transitions log warnings but don't throw exceptions
3. **Restart Method**: restart() properly executes stop + start sequence
4. **Thread Management**: Service thread is created, named, and cleaned up properly
5. **Pause/Resume**: Pause mechanism blocks service execution, resume unblocks
6. **Resource Utilities**: getRequiredResource, getResources work with proper type checking
7. **Error Handling**: Invalid resource access throws clear exceptions
8. **Logging**: Appropriate log levels are used according to logging conventions
9. **Configuration**: TypeSafe Config integration works with defaults and validation

**Test Implementation:**
- Create concrete test subclass that implements abstract `run()` method
- Use mocked resources for testing resource utilities
- Test with real InMemoryBlockingQueue for integration verification
- All tests tagged `@Tag("unit")`

## Non-Requirements

- Do NOT implement specific business logic (that belongs in concrete services)
- Do NOT add complex monitoring features beyond basic state reporting
- Do NOT implement retry mechanisms or circuit breakers yet
- Do NOT optimize for extreme performance (focus on correctness and clarity)

## Validation

The implementation is correct if:
1. All lifecycle methods work correctly with proper state transitions
2. Service threads are properly managed (created, named, interrupted, cleaned up)
3. Pause/resume mechanism blocks and unblocks service execution correctly
4. Resource utilities provide type-safe access with clear error messages
5. All unit tests pass and demonstrate expected behavior
6. Integration with InMemoryBlockingQueue from Phase 1.2 works seamlessly

This foundation will enable rapid development of concrete services in subsequent phases by eliminating common lifecycle and resource management boilerplate.
