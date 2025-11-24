# Data Pipeline V3 - Test Services (Phase 1.4)

## Goal

Implement DummyProducerService and DummyConsumerService as concrete service implementations that demonstrate the complete data pipeline architecture. These services provide end-to-end testing capabilities and serve as reference implementations for future services.

## Success Criteria

Upon completion:
1. DummyProducerService compiles and extends AbstractService with IMonitorable
2. DummyConsumerService compiles and extends AbstractService with IMonitorable  
3. Both services implement proper monitoring with service-specific metrics
4. End-to-end integration test: Producer → InMemoryBlockingQueue → Consumer works
5. Services demonstrate proper use of AbstractService utilities and lifecycle
6. All unit tests pass and verify expected behavior

## Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)
- Phase 1.2: Core Resource Implementation (completed)
- Phase 1.3: Service Foundation (completed)

## Implementation Requirements

### DummyProducerService

**File:** `src/main/java/org/evochora/datapipeline/services/DummyProducerService.java`

**Class Declaration:**
```java
public class DummyProducerService extends AbstractService implements IMonitorable {
    // Implementation details below
}
```

**Required Functionality:**
- Extends AbstractService for lifecycle management
- Implements IMonitorable for service-specific metrics
- Sends Protobuf DummyMessage instances (from Phase 1.1) to configured output resource
- Configurable message sending interval and content

**Import Required:**
```java
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
```

**Configuration Options:**
- `intervalMs`: Integer (default: 1000) - Milliseconds between messages
- `messagePrefix`: String (default: "Message") - Prefix for message content
- `maxMessages`: Integer (default: -1) - Maximum messages to send (-1 for unlimited)
- `throughputWindowSeconds`: Integer (default: 5) - Time window for throughput calculation

**Resource Requirements:**
- Must have exactly one output resource at port "output" of type IOutputQueueResource<DummyMessage>
- DummyMessage refers to the Protobuf-generated class from Phase 1.1

**Service Logic:**
- Send Protobuf DummyMessage instances in loop with configured interval
- Generate messages using DummyMessage.newBuilder():
  ```java
  DummyMessage message = DummyMessage.newBuilder()
      .setId(messageCounter)
      .setContent(messagePrefix + "-" + messageCounter)
      .setTimestamp(System.currentTimeMillis())
      .build();
  ```
- Stop automatically if maxMessages reached
- Check pause state periodically using checkPause()

### DummyConsumerService

**File:** `src/main/java/org/evochora/datapipeline/services/DummyConsumerService.java`

**Class Declaration:**
```java
public class DummyConsumerService extends AbstractService implements IMonitorable {
    // Implementation details below
}
```

**Required Functionality:**
- Extends AbstractService for lifecycle management
- Implements IMonitorable for service-specific metrics
- Receives Protobuf DummyMessage instances (from Phase 1.1) from configured input resource
- Configurable processing delay and logging behavior

**Import Required:**
```java
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
```

**Configuration Options:**
- `processingDelayMs`: Integer (default: 0) - Artificial delay per message
- `logReceivedMessages`: Boolean (default: false) - Whether to log received messages at DEBUG level
- `maxMessages`: Integer (default: -1) - Maximum messages to process (-1 for unlimited)
- `throughputWindowSeconds`: Integer (default: 5) - Time window for throughput calculation

**Resource Requirements:**
- Must have exactly one input resource at port "input" of type IInputQueueResource<DummyMessage>
- DummyMessage refers to the Protobuf-generated class from Phase 1.1

**Service Logic:**
- Receive messages in loop using blocking receive()
- Apply configured processing delay if specified
- Stop automatically if maxMessages reached
- Check pause state periodically using checkPause()

### Monitoring Implementation

#### DummyProducerService Metrics
**IMonitorable.getMetrics():**
- `"messages_sent"`: Total messages sent by this service
- `"throughput_per_sec"`: Messages per second (moving average calculated on-demand using configurable time window)

#### DummyConsumerService Metrics
**IMonitorable.getMetrics():**
- `"messages_received"`: Total messages received by this service
- `"throughput_per_sec"`: Messages per second (moving average calculated on-demand using configurable time window)

#### Error Handling
- Both services must implement proper error tracking via getErrors()
- Record OperationalError for send/receive failures
- Handle InterruptedException gracefully in message loops

### Coding Standards

#### Documentation Requirements
- **All public classes and methods**: Comprehensive Javadoc in English
- **All protected methods**: Javadoc with @param and @return documentation
- **Configuration options**: Document purpose and default values in class Javadoc
- **Service logic**: Explain message generation/processing patterns
- **Complex logic**: Inline comments explaining throughput calculation and message handling

#### Naming Conventions
- **Classes**: DummyProducerService, DummyConsumerService  
- **Methods**: camelCase (sendMessage, processMessage)
- **Fields**: camelCase with descriptive names
- **Constants**: UPPER_SNAKE_CASE for any constants

#### Configuration Handling
- **Use TypeSafe Config**: All configuration access via `com.typesafe.config.Config` methods
- **Default values**: Use Config.getInt(key, defaultValue) pattern for robust defaults
- **Validation**: Validate all configuration values in constructor
- **Path checking**: Use hasPath() before accessing optional configuration
- **Example pattern**:
  ```java
  this.intervalMs = options.getInt("intervalMs", 1000);
  this.messagePrefix = options.getString("messagePrefix", "Message");
  this.maxMessages = options.getInt("maxMessages", -1);
  ```

#### Error Handling
- **Checked exceptions**: Handle InterruptedException properly (restore interrupt status)
- **Unchecked exceptions**: Use IllegalArgumentException for invalid parameters
- **Configuration errors**: Catch ConfigException and wrap in IllegalArgumentException with clear message
- **Resource failures**: Record as OperationalError, continue if possible
- **Null safety**: Validate all inputs, no null returns from public methods

#### Logging Conventions
- **Follow AbstractService pattern**: Lifecycle logging handled by AbstractService
- **INFO Level**: Special business events (max messages reached, auto-pause triggered)
- **WARN Level**: Resource failures (with retry), configuration warnings
- **ERROR Level**: Unrecoverable errors causing service failure
- **DEBUG Level**: Individual message processing (only if configured)
- **No Lifecycle Logging**: Do NOT log start/stop/pause/resume (AbstractService handles this)

## Testing Requirements

### Unit Tests

**Files:**
- `src/test/java/org/evochora/datapipeline/services/DummyProducerServiceTest.java`
- `src/test/java/org/evochora/datapipeline/services/DummyConsumerServiceTest.java`

**Individual Service Tests:**
1. **Configuration**: Services handle all configuration options correctly
2. **Resource Access**: Services correctly access their required resources
3. **Monitoring**: Service-specific metrics are collected and reported correctly
4. **Error Handling**: Services handle resource failures gracefully
5. **Lifecycle Integration**: Services work correctly with AbstractService lifecycle
6. **Message Limits**: Services stop automatically when maxMessages reached

### Integration Test

**File:** `src/test/java/org/evochora/datapipeline/services/EndToEndServiceTest.java`

**End-to-End Scenario:**
1. Create InMemoryBlockingQueue with small capacity (10 messages)
2. Create DummyProducerService configured to send 20 messages
3. Create DummyConsumerService configured to receive all messages
4. Connect Producer → Queue → Consumer using proper resource configuration
5. Start both services and verify complete message flow
6. Verify producer metrics (messages_sent) match consumer metrics (messages_received)
7. Test pause/resume functionality affects both services correctly

**Test Requirements:**
- Use real InMemoryBlockingQueue (not mocked)
- Use real DummyMessage instances (not mocked)
- Verify proper resource wrapper creation and monitoring
- Test with various queue capacities and message counts
- All tests tagged `@Tag("integration")`

## Non-Requirements

- Do NOT implement complex business logic (keep services simple)
- Do NOT add advanced monitoring features beyond basic metrics
- Do NOT implement persistence or durability features

## Validation

The implementation is correct if:
1. Both services compile without errors and extend AbstractService correctly
2. Both services implement IMonitorable with meaningful metrics
3. Services demonstrate proper use of AbstractService utilities (getRequiredResource, checkPause)
4. Services follow logging conventions with service names in all lifecycle messages
5. Integration test demonstrates complete Producer → Queue → Consumer flow
6. All unit and integration tests pass
7. Services can be paused, resumed, and stopped correctly during message processing

This phase completes the foundational service architecture and enables testing of the complete data pipeline pattern before implementing production services.
