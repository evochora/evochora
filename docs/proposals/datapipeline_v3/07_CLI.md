# Data Pipeline V3 - Command Line Interface (Phase 1.6)

## Goal

Implement a professional CLI for the Data Pipeline V3 that provides both interactive and headless operation modes with proper configuration management and logging. The CLI serves as the main entry point for controlling the data pipeline and integrates with the existing ServiceManager.

## Success Criteria

Upon completion:
1. Interactive mode with JLine-powered shell provides professional user experience
2. Headless mode with structured JSON logging enables automation and AI assistance
3. Complete configuration precedence handling (Environment Variables > CLI Arguments > Config File > Defaults)
4. All pipeline lifecycle commands (start, stop, restart, status, pause, resume) work correctly for both all services and individual services
5. Assembly compilation command works in headless mode and outputs complete ProgramArtifact as JSON
6. Strict error handling prevents accidental simulation termination
7. ServiceManager integration works seamlessly with both modes
8. All unit and integration tests pass

## Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)
- Phase 1.2: Core Resource Implementation (completed)
- Phase 1.3: Service Foundation (completed)
- Phase 1.4: Test Services (completed)
- Phase 1.5: Service Orchestration (completed)

## Implementation Requirements

### CommandLineInterface Class

**File:** `src/main/java/org/evochora/datapipeline/cli/CommandLineInterface.java`

**Class Declaration:**
```java
public class CommandLineInterface implements Runnable {
    // Implementation details below
}
```

**Required Functionality:**
- Main entry point with `public static void main(String[] args)`
- Picocli integration for command parsing and help generation
- Support for both interactive and headless operation modes
- Integration with existing ServiceManager for pipeline control

### Interactive Mode

**Purpose:** Provide human operators with a professional shell experience using JLine.

**Required Features:**
- JLine console with command completion and syntax highlighting
- Persistent command history across sessions
- Rich user interaction for confirmations and input
- Cross-platform compatibility (Windows, Linux, macOS)
- Professional terminal experience comparable to modern CLI tools

**Command Support:**
- All pipeline lifecycle commands (start, stop, restart, status, pause, resume)
- Help and exit commands
- Service-specific control (start/stop/restart/pause/resume individual services)

### Headless Mode

**Purpose:** Enable automation and AI assistance with structured output.

**Required Features:**
- Non-blocking command execution for automation
- Structured JSON logging for automated parsing
- Pipeline lifecycle commands (start, stop, restart, status, pause, resume) for all services and individual services
- Assembly compilation command (integrate with existing compiler, output complete ProgramArtifact as JSON)
- Strict error handling with proper exit codes
- Integration with existing logging infrastructure

**Output Format:**
- Interactive mode: Standard logback format (timestamp, level, thread, logger, message)
- Headless mode: Structured JSON format for automation
- Configurable log levels and formats

### Configuration Management

**Purpose:** Handle configuration precedence and provide pre-resolved Config to ServiceManager.

**Required Functionality:**
- Load configuration with proper precedence hierarchy
- Environment variable mapping to configuration paths
- Command line argument parsing and validation
- TypeSafe Config resolution before ServiceManager instantiation
- No ConfigFactory.load() usage within ServiceManager (as per Phase 1.5)
- Optional `--config` or `-c` parameter for explicit configuration file path (both interactive and headless modes)
- Support for different environments (development, test, production)

**Precedence Order:**
1. Environment Variables (highest precedence)
2. Command Line Arguments
3. Configuration File (evochora.conf)
4. Hardcoded Defaults (lowest precedence)

**Configuration File Format (evochora.conf):**
```hocon
pipeline {
  startupSequence = ["consumer", "producer"]
  
  resources {
    test-queue {
      className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
      options {
        capacity = 1000
        throughputWindowSeconds = 5
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
        maxMessages = 100
      }
    }
    
    producer {
      className = "org.evochora.datapipeline.services.DummyProducerService"
      resources {
        output = "queue-out:test-queue?window=2"
      }
      options {
        intervalMs = 100
        maxMessages = 100
      }
    }
  }
}

logging {
  # Global settings (apply to both modes)
  level = "WARN"  # Conservative default: only warnings and errors
  
  # Mode-specific format settings
  format = "JSON"
  
  # Class-specific logging levels (apply to both modes)
  loggers {
    "org.evochora.datapipeline.ServiceManager" = "INFO"
  }
  
  # Mode-specific overrides (optional)
  interactive {
    # Override specific settings for interactive mode
    format = "PLAIN"
    loggers {
      "org.evochora.datapipeline.services.DummyProducerService" = "DEBUG"  # More verbose in interactive
      "org.evochora.datapipeline.services.DummyConsumerService" = "DEBUG"  # More verbose in interactive
    }
  }
  
  headless {
    # Override specific settings for headless mode
    # Keep conservative settings for automation
  }
}
```

### Logging Strategy

**Purpose:** Provide adaptive logging based on operation mode.

**Required Features:**
- Interactive mode: Standard logback format (timestamp, level, thread, logger, message)
- Headless mode: Structured JSON format for automation
- Configurable log levels via CLI and environment
- Integration with existing Logback infrastructure
- All logs to stdout for container collection

### Error Handling

**Purpose:** Ensure robust operation and prevent accidental simulation termination.

**Required Features:**
- Strict error handling with appropriate exit codes
- Graceful shutdown procedures
- Clear error messages and recovery suggestions
- Prevention of accidental simulation termination
- Proper cleanup on errors

### ServiceManager Integration

**Purpose:** Seamlessly integrate with existing ServiceManager for pipeline control.

**Required Features:**
- Pass pre-resolved TypeSafe Config to ServiceManager
- Support all ServiceManager lifecycle methods (startAll, stopAll, pauseAll, resumeAll, restartAll)
- Support individual service control (startService, stopService, pauseService, resumeService, restartService)
- Handle service-specific error reporting
- Integrate with existing monitoring and status reporting
- Support for startup sequence configuration

**Status Output Format:**
- **Service Line**: Name, State, Global Service Metrics (e.g., messages_processed, throughput)
- **Binding Line** (indented): Port Name, Usage Type, Resource Name, Binding State, Binding Metrics (Wrapper)
- **Hierarchical Display**: Reflects all monitoring levels (global and connection-specific)
- **JSON Format** (headless): Structured output for automation with complete service and binding details

## Testing Requirements

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/cli/CommandLineInterfaceTest.java`

**Required Test Cases:**
1. **Configuration Loading**: Test precedence hierarchy (ENV > CLI > FILE > DEFAULTS)
2. **Interactive Mode**: Test JLine console functionality and command execution
3. **Headless Mode**: Test command execution and exit codes
4. **Logging Configuration**: Test both interactive and headless logging formats
5. **Command Parsing**: Test all commands with various argument combinations
6. **Error Handling**: Test error scenarios and proper exit codes
7. **ServiceManager Integration**: Test CLI commands with real ServiceManager

**Test Annotations:**
- Use `@AllowLogs` and `@ExpectLog` annotations for logging verification
- Tag all tests as `@Tag("unit")` or `@Tag("integration")` as appropriate
- Test both interactive and headless modes

### Integration Test

**File:** `src/test/java/org/evochora/datapipeline/cli/CLIIntegrationTest.java`

**Required Test Scenarios:**
1. **Full Interactive Session**: Complete interactive session with all commands
2. **Headless Automation**: Automated execution of all commands
3. **Configuration Override**: Test configuration precedence in practice
4. **Error Recovery**: Test error handling and recovery scenarios
5. **ServiceManager Integration**: Test CLI with real ServiceManager and DummyServices

**Test Requirements:**
- Use real ServiceManager and DummyServices (not mocked)
- Test end-to-end pipeline control via CLI
- Verify proper logging output in both modes
- All tests tagged `@Tag("integration")`

## Usage Examples

### Interactive Mode
```bash
# Start interactive mode
./evochora

# Start interactive mode with custom config
./evochora --config /path/to/custom.conf

# Interactive session
evochora> start
evochora> status
evochora> pause
evochora> resume
evochora> restart
evochora> stop
evochora> start producer
evochora> pause consumer
evochora> resume consumer
evochora> restart producer
evochora> stop producer
evochora> exit
```

### Headless Mode
```bash
# Start pipeline
./evochora start # all services in startupSequence
./evochora pause # all service
./evochora resume # all services paused services
./evochora restart # all running services
./evochora stop # all services

# Check status with JSON output
./evochora status --json

# Control individual services
./evochora start producer
./evochora pause consumer
./evochora resume consumer
./evochora restart producer
./evochora stop consumer


# Compile assembly (outputs complete ProgramArtifact as JSON)
./evochora compile -f assembly/test.s -e 1000x1000:toroidal

# With custom configuration file
./evochora start --config /path/to/custom.conf

# With environment variables
EVOCHORA_LOGGING_LEVEL=DEBUG ./evochora start --verbose
```

## Implementation Notes

### JLine Integration
- Use JLine for advanced shell features (completion, highlighting, history)
- Ensure cross-platform compatibility
- Provide professional user experience

### Configuration Strategy
- Handle all configuration precedence in CLI layer
- Pass pre-resolved TypeSafe Config to ServiceManager
- Support environment variable mapping

### Logging Strategy
- Adaptive format based on operation mode
- Integration with existing Logback infrastructure
- Structured output for automation

### Error Handling
- Strict error handling with proper exit codes
- Graceful shutdown procedures
- Prevention of accidental simulation termination
