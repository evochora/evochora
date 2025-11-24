# Data Pipeline V3 - Professional CLI (Phase 1.8)

## 1. Goal

This phase replaces the existing, transient command-line functionality with a professional, robust Command Line Interface (CLI) based on the **Picocli** framework. This new CLI will become the single entry point for the entire Evochora application, responsible for configuration loading, logging setup, and delegating tasks to the appropriate modules, such as starting the Node server or running the compiler.

This architectural change decouples the core application logic (`Node`, `Compiler`) from the user-facing entry point, establishing a clean separation of concerns and a foundation for future extensions.

## 2. Success Criteria

Upon completion:
1.  A new `org.evochora.cli` package exists, containing all CLI-related logic.
2.  The application's main entry point is the `CommandLineInterface` class, which uses Picocli to parse commands and options.
3.  The `Node` class is no longer responsible for loading the configuration or initializing the logging framework; it receives a prepared `Config` object. Its `main` method is removed.
4.  The CLI provides the specified commands (`node run`, `node stop`, `compile`) with their corresponding options.
5.  Calling the application without any command (`evochora`) displays the help message.
6.  `evochora node run` starts the Node server in the foreground, streaming its logs to the console.
7.  `evochora node run -d` starts the Node in the background (detached) and creates a `.evochora.pid` file.
8.  `evochora node stop` sends an HTTP request to the `/node/stop` endpoint to gracefully shut down the running Node.
9.  A new `NodeController` is added to the existing `HttpServerProcess` to facilitate graceful shutdowns.
10. Configuration values can be overridden from the command line via standard Java system properties (`-Dkey=value`) and an explicit `--config.file` option.

## 3. Package Structure

This specification is now fully aligned with the existing project structure from the `feature/datapipeline_v3/07_node` branch. The new `cli` package is introduced at the top level to act as the application's entry point, and the old CLI in `org.evochora.datapipeline.cli` will be removed. All other components are placed consistently with the existing architecture.

```
org.evochora
├── cli                           // <-- NEW: The application entry point
│   ├── CommandLineInterface.java // The new main class, using Picocli
│   ├── config                    // <-- MOVED from node: Configuration and logging setup
│   │   ├── ConfigLoader.java
│   │   └── LoggingConfigurator.java
│   └── commands
│       ├── node
│       │   ├── NodeCommand.java      // Parent command "node"
│       │   ├── NodeRunCommand.java   // Implements "run"
│       │   └── NodeStopCommand.java  // Implements "stop"
│       └── CompileCommand.java       // Adapts existing compile logic
│
├── datapipeline                  // <-- Existing datapipeline (remains untouched)
│   ├── api
│   │   ├── resources
│   │   └── services
│   ├── cli                       // <-- Will be removed (replaced by org.evochora.cli)
│   ├── resources
│   ├── services
│   └── ServiceManager.java
│
├── node                          // <-- Becomes a pure server library
│   ├── Node.java                 // Node class, NO LONGER has a main() method
│   ├── processes
│   │   ├── AbstractProcess.java
│   │   └── http
│   │       ├── HttpServerProcess.java
│   │       ├── AbstractController.java
│   │       └── api
│   │           ├── pipeline       // <-- Existing pipeline API
│   │           │   ├── dto
│   │           │   └── PipelineController.java
│   │           └── node           // <-- NEW: API for the node itself
│   │               └── NodeController.java
│   └── spi
│       ├── IController.java
│       ├── IProcess.java
│       └── ServiceRegistry.java
│
├── compiler                      // <-- Existing compiler (remains untouched)
└── runtime                       // <-- Existing runtime (remains untouched)
```


## 4. Prerequisites

- Phase 0: API Foundation (completed)
- Phase 1.1: Protobuf Setup (completed)  
- Phase 1.2: Core Resource Implementation (completed)
- Phase 1.3: Service Foundation (completed)
- Phase 1.4: Test Services (completed)
- Phase 1.5: Service Orchestration (completed)
- Phase 1.6: Command Line Interface (completed, functionality will be replaced with this implementation)
- Phase 1.7: Node Architecture (completed, functionality will be changed with this implementation)

## 5. Implementation Requirements

### 5.1. Refactoring of Core Components

#### 5.1.1. `Node.java`
* The `public static void main(String[] args)` method **must be removed entirely** (lines 172-222).
* The following methods and logic **must be removed** from Node.java as they will be moved to CLI:
  * `reconfigureLogback()` method (lines 224-244) - **moves to `org.evochora.cli.CommandLineInterface`**
  * `showWelcomeMessage()` method (lines 249-261) - **moves to `org.evochora.cli.CommandLineInterface`**
  * All configuration loading logic from `main()` method - **moves to `org.evochora.cli.CommandLineInterface`**
  * All logging initialization logic from `main()` method - **moves to `org.evochora.cli.CommandLineInterface`**
* The constructor `public Node(final Config config)` remains unchanged, but it **must not** call `ConfigLoader` or initialize logging. It will receive a fully prepared `Config` object from the CLI.
* All other functionality (process management, lifecycle methods) remains unchanged.

### 5.2. New CLI Implementation (`org.evochora.cli`)

#### 5.2.1. `CommandLineInterface.java`
* This is the main class for the application, replacing the old CLI in `org.evochora.datapipeline.cli`.
* It must be annotated with `@CommandLine.Command` and register all subcommands (`NodeCommand`, `CompileCommand`).
* If no subcommand is provided, the help message must be displayed.
* Its `main` method is responsible for loading the configuration and initializing logging **using the exact same logic as currently implemented in `Node.main()`**.

**Critical Requirement**: The configuration loading and logging initialization logic must be moved from `Node.main()` to `CommandLineInterface.main()` with **identical functionality**. This includes:

1. **Configuration Loading**: Use `ConfigFactory.load()` which automatically loads `evochora.conf` if present, otherwise falls back to `reference.conf` from classpath
2. **Logging Format Configuration**: Set `evochora.logging.format` system property based on config, then reconfigure Logback
3. **Logback Reconfiguration**: Force Logback to reload configuration after setting system properties
4. **Logging Configuration Application**: Apply logging levels and settings from configuration
5. **Welcome Message Logic**: Display ASCII art welcome message if `node.show-welcome-message=true` in config (must be first output, DEBUG/TRACE logs excluded)
6. **Configuration File Logging**: Log which configuration file is being used (must come immediately after welcome message, DEBUG/TRACE logs excluded)

**The functionality must remain identical** to the current `Node.main()` implementation, but the code can be refactored as needed for the CLI architecture.

#### 5.2.2. Required Helper Methods
The `CommandLineInterface` class must provide the following functionality:

* **Logback Reconfiguration**: Method to force Logback to reload configuration after setting system properties
* **Welcome Message Display**: Method to display ASCII art welcome message

These methods must provide identical functionality to the current Node.java implementation but can be refactored as needed for the CLI architecture.

#### 5.2.3. Global Options
* Define a global option for specifying a custom configuration file:
    * `--config.file <file>`: Overrides the default `evochora.conf` file path.
* The CLI must support passing `-Dkey=value` arguments to the Java runtime for overriding configuration values.

#### 5.2.4. `NodeRunCommand.java`
* Implements the `evochora node run` command.
* Defines the `-d, --detach` option as a `boolean` flag.
* Receives the pre-loaded `Config` object, instantiates `Node`, and calls `node.start()`.
* If `-d` is specified, it creates a `.evochora.pid` file in the current working directory with the Process ID and detaches from the console.

#### 5.2.5. `NodeStopCommand.java`
* Implements the `evochora node stop` command.
* Reads the application config to get the host/port of the running node.
* Uses a standard Java HTTP client (e.g., `java.net.http.HttpClient`) to send a `POST` request to the `/node/stop` endpoint.
* Deletes the `.evochora.pid` file upon success.

### 5.3. Extending the existing HTTP Server

The existing `HttpServerProcess` must be extended to handle the new `stop` command.

#### 5.3.1. `NodeController.java`
* A new controller located at `org.evochora.node.processes.http.api.node.NodeController.java`.
* It exposes a single endpoint:
    * **`POST /stop`**: Returns `202 Accepted` and triggers a graceful shutdown of the Node by calling `System.exit(0)` after a brief delay (e.g., 200ms) to allow the HTTP response to be sent.

#### 5.3.2. `evochora.conf`
* The configuration file must be updated to register the new `NodeController`.

```hocon
# evochora.conf (example snippet)
node {
  processes {
    httpServer {
      # ... existing options
      options {
        # ... existing network and routes
        routes {
          pipeline { ... } // Existing pipeline API
          
          node {
            "$controller" { # NEW entry for the node control API
              className = "org.evochora.node.processes.http.api.node.NodeController"
              options {}
            }
          }
        }
      }
    }
  }
}

## 6. Coding Standards

All standards regarding JavaDoc, immutability, code style, and logging from previous specifications remain in effect.

### 6.1. Documentation Requirements
- **All public classes and methods**: Comprehensive Javadoc in English
- **All public methods**: Javadoc with @param and @return documentation
- **Complex logic**: Inline comments explaining algorithm decisions
- **Configuration examples**: Include complete, working configuration examples

### 6.2. Error Handling
- **Graceful degradation**: Handle missing configuration files and invalid options gracefully
- **Clear error messages**: Provide actionable error messages with suggestions for resolution
- **Exit codes**: Use standard exit codes (0 for success, non-zero for errors)
- **Logging**: Log errors at appropriate levels (ERROR for fatal, WARN for recoverable issues)

### 6.3. Thread Safety
- **Immutable objects**: Use immutable data structures where possible
- **Thread-safe operations**: Ensure all CLI operations are thread-safe
- **Resource cleanup**: Properly clean up resources on shutdown

## 7. Testing Requirements

### 7.1. Unit Tests (`@Tag("unit")`)

**Required Test Classes:**
- `NodeRunCommandTest.java` - Test the `node run` command logic
- `NodeStopCommandTest.java` - Test the `node stop` command logic  
- `NodeControllerTest.java` - Test the HTTP controller endpoints
- `CommandLineInterfaceTest.java` - Test CLI parsing and help generation

**Test Coverage:**
- Command parsing with various argument combinations
- Configuration loading and validation
- Error handling scenarios
- Help message generation
- PID file creation and cleanup

### 7.2. Integration Tests (`@Tag("integration")`)

**Required Test Class:**
- `CliIntegrationTest.java` - End-to-end CLI testing

**Test Scenarios:**
- Full `node run -d` and `node stop` lifecycle
- PID file handling and cleanup
- HTTP server responsiveness verification
- Configuration file loading and precedence
- Error recovery and cleanup

**Test Requirements:**
- Use real HTTP server and CLI processes
- Test actual network communication
- Verify proper resource cleanup
- All tests must complete in under 1 minute

## 8. Usage Examples

### 8.1. Basic Usage

```bash
# Start the application (shows help)
./evochora

# Start node in foreground
./evochora node run

# Start node in background (detached)
./evochora node run -d

# Stop running node
./evochora node stop

# Compile assembly code
./evochora compile -f assembly/test.evo -e 1000x1000:toroidal
```

### 8.2. Configuration Override

```bash
# Use custom configuration file
./evochora node run --config /path/to/custom.conf

# Override configuration via system properties
./evochora node run -Dnode.processes.httpServer.options.network.port=9090
```

### 8.3. Error Scenarios

```bash
# Missing configuration file (should use defaults)
./evochora node run --config /nonexistent.conf

# Invalid command (should show help)
./evochora invalid-command

# Stop when no node is running (should handle gracefully)
./evochora node stop
```

## 9. Migration Notes

### 9.1. Breaking Changes
- The `Node.main()` method is removed - applications must use the CLI
- Configuration loading is now handled by CLI, not Node
- Logging initialization is now handled by CLI, not Node

### 9.2. Backward Compatibility
- All existing configuration files remain compatible
- All existing HTTP API endpoints remain unchanged
- All existing service configurations remain unchanged

### 9.3. Migration Steps
1. Update any scripts that call `Node.main()` directly to use `evochora node run`
2. Update any configuration overrides to use CLI arguments instead of direct Node configuration
3. Test all existing functionality to ensure compatibility

## 10. Future Considerations

### 10.1. Extensibility
- The CLI architecture supports adding new commands easily
- New node controllers can be added without changing CLI code
- Configuration precedence system supports future configuration sources

### 10.2. Performance
- CLI startup time should be minimal
- Configuration loading is optimized for fast startup
- Background node processes run independently of CLI

### 10.3. Monitoring
- PID file enables process monitoring
- HTTP API provides runtime status
- Logging provides operational visibility