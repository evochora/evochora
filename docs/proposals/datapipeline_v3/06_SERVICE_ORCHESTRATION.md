# Data Pipeline V3 - Service Orchestration (Phase 1.5)

## Goal

Implement ServiceManager as the central orchestration component that handles pre-resolved TypeSafe Config processing, Universal Resource Dependency Injection, and service lifecycle management. This component serves as the DI container and orchestrator for the entire data pipeline system.

## Success Criteria

Upon completion:
1. ServiceManager compiles and processes pre-resolved TypeSafe Config correctly
2. Universal Resource DI system works with contextual wrapping and URI parameter parsing
3. Service lifecycle management (start, stop, pause, resume) works for all configured services
4. Status monitoring and metrics collection provides operational visibility
5. Integration test demonstrates complete pipeline orchestration with DummyProducerService and DummyConsumerService
6. All unit tests pass and verify expected behavior

## Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)
- Phase 1.2: Core Resource Implementation (completed)
- Phase 1.3: Service Foundation (completed)
- Phase 1.4: Test Services (completed)

## Implementation Requirements

### ServiceManager Class

**File:** `src/main/java/org/evochora/datapipeline/ServiceManager.java`

**Class Declaration:**
```java
public class ServiceManager implements IMonitorable {
    // Implementation details below
}
```

**Required Constructor:**
```java
public ServiceManager(com.typesafe.config.Config rootConfig) {
    // Load pipeline configuration
    // Initialize resources and services
    // Set up monitoring and lifecycle management
}
```

**Required Implementation:**
- Must implement IMonitorable for system-wide monitoring
- Must handle complete service lifecycle management
- Must provide status reporting for all services and resources
- Must handle pre-resolved configuration from CLI or tests

### Core Functionality

#### Configuration Loading
**ServiceManager receives pre-resolved configuration:**
- **CLI Responsibility**: Command line interface handles configuration precedence and resolution
- **ServiceManager Input**: Receives fully resolved `com.typesafe.config.Config` object
- **Test Usage**: Test cases can pass configuration directly to ServiceManager constructor

**Configuration Structure (as received by ServiceManager):**
```java
// ServiceManager receives pre-resolved TypeSafe Config from CLI
// This shows the structure that ServiceManager processes
Config pipelineConfig = rootConfig.getConfig("pipeline");
// Contains: startupSequence, resources, services sections
```

#### Universal Resource Dependency Injection

**Resource URI Pattern:**
- **Format**: `usageType:resourceName?param1=value1&param2=value2`
- **Usage Types**: `queue-in`, `queue-out`, `storage-readonly`, `storage-writeonly`
- **Parameters**: Resource-specific parameters (e.g., `window=30`, `batch=100`)

**Port Name Concept:**
A **port name** is a logical identifier within a service that represents a specific connection point for resources. It's similar to a port on a network device - it defines where and how a service connects to external resources.

**Port Name Examples:**
- `tickOutput`: Service sends tick data to this port
- `tickInput`: Service receives tick data from this port  
- `storageOutput`: Service writes data to storage via this port
- `broadcastOutput`: Service sends broadcast messages via this port
- `configInput`: Service reads configuration from this port

**Configuration Mapping (as processed by ServiceManager):**
```java
// ServiceManager processes resource URI mappings from Config
// Example structure in the Config object:
// pipeline.services.simulation-engine.resources.tickOutput = "queue-out:tick-data-queue?window=5"
// pipeline.services.persistence-service.resources.tickInput = "queue-in:tick-data-queue"
```

**DI Process:**
1. **Parse Resource URI**: Extract usageType, resourceName, and parameters
2. **Create ResourceContext**: Build context with service name, port name, usage type, and parameters
3. **Get Base Resource**: Retrieve resource instance from resources map
4. **Apply Contextual Wrapping**: Call `getWrappedResource(context)` if resource implements `IContextualResource`
5. **Inject into Service**: Pass wrapped resource to service constructor

**Example Flow:**
```
URI: "queue-out:tick-data-queue?window=5"
Port Name: "tickOutput"
↓
ResourceContext(serviceName="simulation-engine", portName="tickOutput", usageType="queue-out", parameters={window="5"})
↓
InMemoryBlockingQueue.getWrappedResource(context)
↓
MonitoredQueueProducer (with window=5 parameter)
↓
Service Constructor receives wrapped resource at port "tickOutput"
```

#### Service Lifecycle Management

**Lifecycle Methods:**
```java
public void startAll()                    // Start all configured services
public void stopAll()                     // Stop all configured services
public void pauseAll()                    // Pause all running services
public void resumeAll()                   // Resume all paused services
public void restartAll()                  // Restart all configured services
public void startService(String serviceName)    // Start specific service
public void stopService(String serviceName)     // Stop specific service
public void pauseService(String serviceName)    // Pause specific service
public void resumeService(String serviceName)   // Resume specific service
public void restartService(String serviceName)  // Restart specific service
```

**State Management:**
- Track service states in `Map<String, IService.State>`
- Handle startup sequence if configured
- Graceful shutdown with timeout handling

#### Status Monitoring

**IMonitorable Implementation:**
```java
public Map<String, Number> getMetrics() {
    // System-wide metrics:
    // - "services_total": Total number of configured services
    // - "services_running": Number of services in RUNNING state
    // - "services_paused": Number of services in PAUSED state
    // - "services_stopped": Number of services in STOPPED state
    // - "services_error": Number of services in ERROR state
    // - "resources_total": Total number of configured resources
    // - "resources_active": Number of resources in ACTIVE state
    // - "resources_waiting": Number of resources in WAITING state
    // - "resources_failed": Number of resources in FAILED state
}

public boolean isHealthy() {
    // Return false if any service is in ERROR state or any resource is FAILED
}
```

**Service-Specific Error Tracking:**
```java
public Map<String, List<OperationalError>> getServiceErrors() {
    // Returns map of service name -> list of errors for that service
    // Only includes services that have errors (empty lists are omitted)
}

public List<OperationalError> getServiceErrors(String serviceName) {
    // Returns errors for a specific service
    // Returns empty list if service has no errors or doesn't exist
}
```

**Service Status Reporting:**
```java
public Map<String, ServiceStatus> getAllServiceStatus() {
    // Return complete status for all services including resource bindings
}

public ServiceStatus getServiceStatus(String serviceName) {
    // Return status for specific service
}
```

### Coding Standards

#### Documentation Requirements
- **All public classes and methods**: Comprehensive Javadoc in English
- **Configuration examples**: Include complete TypeSafe Config examples in class Javadoc
- **DI process**: Document the Universal DI flow with examples
- **Error scenarios**: Document common configuration errors and their solutions
- **Complex logic**: Inline comments explaining resource resolution and lifecycle management

#### Naming Conventions
- **Classes**: ServiceManager
- **Methods**: camelCase (startAll, getServiceStatus)
- **Fields**: camelCase with descriptive names
- **Constants**: UPPER_SNAKE_CASE for any constants
- **Configuration keys**: kebab-case (tick-data-queue, simulation-engine)

#### Error Handling
- **Configuration errors**: Clear error messages with file path and line number
- **Resource resolution**: Detailed error messages for missing or invalid resources
- **Service instantiation**: Catch and wrap reflection exceptions with clear context
- **Lifecycle errors**: Log errors but continue with other services
- **Null safety**: Validate all inputs, no null returns from public methods

#### Thread Safety
- **Service lifecycle**: Thread-safe service state management
- **Resource access**: Thread-safe resource map access
- **Status reporting**: Thread-safe status collection
- **No external synchronization**: Should work without external locking

#### Logging Conventions
**Logger Setup:**
```java
private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);
```

**Logging Rules:**
- **INFO Level**: ServiceManager initialization and configuration events
- **WARN Level**: Configuration warnings, resource resolution issues
- **ERROR Level**: Service instantiation failures, critical system errors
- **DEBUG Level**: Detailed DI process, resource resolution steps

**NO LIFECYCLE LOGGING**
- **DO NOT log service lifecycle events** (start, stop, pause, resume, restart)
- **AbstractService already handles all service lifecycle logging**
- **ServiceManager only logs orchestration-level events**

**Logging Examples:**
```java
// ✅ GOOD - ServiceManager events
log.info("ServiceManager initialized with {} resources and {} services", resourceCount, serviceCount);

// ✅ GOOD - Configuration issues
log.warn("Service '{}' references unknown resource '{}' for port '{}'", serviceName, resourceName, portName);

// ❌ FORBIDDEN - Service lifecycle logging (AbstractService handles this)
log.info("Service '{}' started successfully", serviceName); // AbstractService already logs this!
log.info("Service '{}' stopped", serviceName); // AbstractService already logs this!
log.info("Service '{}' paused", serviceName); // AbstractService already logs this!

// ❌ BAD - High frequency
log.info("Processing resource URI: {}", uri); // Every resource!
```

### Configuration Support

#### TypeSafe Config Integration
```java
private Config loadPipelineConfig(Config rootConfig) {
    if (!rootConfig.hasPath("pipeline")) {
        throw new IllegalArgumentException("Configuration must contain 'pipeline' section");
    }
    return rootConfig.getConfig("pipeline");
}
```

#### Resource Instantiation
```java
private void instantiateResources(Config pipelineConfig) {
    if (!pipelineConfig.hasPath("resources")) {
        log.debug("No resources configured");
        return;
    }
    
    Config resourcesConfig = pipelineConfig.getConfig("resources");
    for (String resourceName : resourcesConfig.root().keySet()) {
        Config resourceDefinition = resourcesConfig.getConfig(resourceName);
        String className = resourceDefinition.getString("className");
        Config options = resourceDefinition.hasPath("options") 
            ? resourceDefinition.getConfig("options") 
            : ConfigFactory.empty(); // No options configured
        
        // Instantiate resource using reflection
        // Store in resources map
    }
}

```

#### Service Instantiation with DI
```java
private void instantiateServices(Config pipelineConfig) {
    if (!pipelineConfig.hasPath("services")) {
        log.debug("No services configured");
        return;
    }
    
    Config servicesConfig = pipelineConfig.getConfig("services");
    for (String serviceName : servicesConfig.root().keySet()) {
        Config serviceDefinition = servicesConfig.getConfig(serviceName);
        String className = serviceDefinition.getString("className");
        Config options = serviceDefinition.hasPath("options") 
            ? serviceDefinition.getConfig("options") 
            : ConfigFactory.empty(); // No options configured
        
        // Resolve resource dependencies
        Map<String, List<IResource>> serviceResources = resolveServiceResources(serviceDefinition, serviceName);
        
        // Instantiate service with DI
        // Store in services map
    }
}
```

#### Resource URI Parsing
```java
private ResourceContext parseResourceUri(String uri, String serviceName, String portName) {
    // Parse "usageType:resourceName?param1=value1&param2=value2"
    // Extract usageType, resourceName, and parameters
    // Return ResourceContext
}
```

## Testing Requirements

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/ServiceManagerTest.java`

**Required test cases:**
1. **Configuration Processing**: Process pre-resolved configuration correctly
2. **Resource Instantiation**: Resources are created correctly from configuration
3. **Service Instantiation**: Services are created with proper resource injection
4. **Universal DI**: Resource URI parsing and contextual wrapping works correctly
5. **Lifecycle Management**: All lifecycle methods work for individual services and all services
6. **Status Monitoring**: Service and resource status reporting works correctly
7. **Error Handling**: Configuration errors, missing resources, and service failures are handled gracefully
8. **Thread Safety**: Concurrent access to ServiceManager methods works correctly
9. **Logging Verification**: Expected logs are produced and unexpected logs are handled correctly

**Logging Test Annotations:**
```java
// Allow specific logs during test execution
@AllowLogs({ 
    @AllowLog(level = LogLevel.WARN, message = "Service 'test-service' is already running"),
    @AllowLog(level = LogLevel.ERROR, message = "Failed to instantiate resource: test-resource")
})

// Expect specific logs to occur
@ExpectLog(level = LogLevel.INFO, message = "ServiceManager initialized with 2 resources and 1 services")
@ExpectLog(level = LogLevel.INFO, message = "Instantiated resource 'test-queue' of type InMemoryBlockingQueue")
@ExpectLog(level = LogLevel.INFO, message = "Instantiated service 'producer' of type DummyProducerService")

// Test methods should verify logging behavior
@Test
void testServiceInstantiationWithExpectedLogs() {
    // Test implementation
    // Logs will be automatically verified by annotations
}
```

### Integration Test

**File:** `src/test/java/org/evochora/datapipeline/ServiceManagerIntegrationTest.java`

**End-to-End Scenario:**
1. Create complete TypeSafe Config with InMemoryBlockingQueue, DummyProducerService, and DummyConsumerService
2. Instantiate ServiceManager with configuration
3. Start all services and verify complete message flow
4. Test pause/resume functionality affects all services
5. Test individual service lifecycle control
6. Verify status monitoring shows correct states and metrics
7. Test graceful shutdown
8. **Message Flow Test**: Verify protobuf `DummyMessage` objects flow correctly from producer through queue to consumer
9. **Message Integrity Test**: Ensure messages maintain object reference consistency during in-memory transport

**Test Requirements:**
- Use programmatic TypeSafe Config creation (not external config files)
- Use real InMemoryBlockingQueue and DummyServices (not mocked)
- Verify proper resource wrapper creation and monitoring
- Test with various configuration scenarios
- All tests tagged `@Tag("integration")`

**Data Flow Verification:**
- **DummyProducerService** generates protobuf-based `DummyMessage` objects
- **InMemoryBlockingQueue** transports messages from producer to consumer (in-memory, no serialization)
- **DummyConsumerService** receives and processes the `DummyMessage` objects
- Verify end-to-end message flow: Producer → Queue → Consumer
- Test message integrity and object reference consistency

**Integration Test Logging:**
```java
// Allow expected logs during integration test
@AllowLogs({
    @AllowLog(level = LogLevel.INFO, message = "DummyProducerService started"),
    @AllowLog(level = LogLevel.INFO, message = "DummyConsumerService started"),
    @AllowLog(level = LogLevel.INFO, message = "DummyProducerService stopped"),
    @AllowLog(level = LogLevel.INFO, message = "DummyConsumerService stopped")
})

// Expect specific system logs
@ExpectLog(level = LogLevel.INFO, message = "ServiceManager initialized with 1 resources and 2 services")
@ExpectLog(level = LogLevel.INFO, message = "Instantiated resource 'test-queue' of type InMemoryBlockingQueue")
@ExpectLog(level = LogLevel.INFO, message = "Instantiated service 'producer' of type DummyProducerService")
@ExpectLog(level = LogLevel.INFO, message = "Instantiated service 'consumer' of type DummyConsumerService")
```

### Configuration Test Examples

**Test Configuration Setup:**
```java
// Test creates Config object and passes it to ServiceManager
// ServiceManager receives pre-resolved Config - no ConfigFactory usage anywhere
Config testConfig = createTestConfig(); // Helper method creates the Config
ServiceManager serviceManager = new ServiceManager(testConfig);
```

**Helper Method for Tests:**
```java
private Config createTestConfig() {
    String configString = """
        pipeline {
          startupSequence = ["consumer", "producer"]
          resources {
            test-queue {
              className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
              options {
                capacity = 100
                throughputWindowSeconds = 3
              }
            }
          }
          services {
            consumer {
              className = "org.evochora.datapipeline.services.DummyConsumerService"
              resources {
                input = "queue-in:test-queue"
              }
              options {
                processingDelayMs = 50
                maxMessages = 50
              }
            }
            producer {
              className = "org.evochora.datapipeline.services.DummyProducerService"
              resources {
                output = "queue-out:test-queue?window=2"
              }
              options {
                intervalMs = 100
                maxMessages = 50
              }
            }
          }
        }
    """;
    return ConfigFactory.parseString(configString);
}
```

## Non-Requirements

- Do NOT implement CLI interface yet (that comes in Phase 1.6)
- Do NOT implement advanced monitoring features beyond basic status reporting
- Do NOT implement service discovery or dynamic service registration
- Do NOT implement configuration hot-reloading

## Validation

The implementation is correct if:
1. ServiceManager processes pre-resolved TypeSafe Config correctly
2. Universal Resource DI system correctly parses URIs and creates contextual wrappers
3. All service lifecycle methods work correctly for individual and bulk operations
4. Status monitoring provides accurate information about services and resources
5. Integration test demonstrates complete pipeline orchestration
6. All unit and integration tests pass
7. ServiceManager can orchestrate DummyProducerService and DummyConsumerService end-to-end
8. Configuration errors provide clear, actionable error messages

This orchestration layer completes the foundational architecture and enables the CLI interface implementation in Phase 1.6.
