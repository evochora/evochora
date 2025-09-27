# Data Pipeline V3 - Node Architecture (Phase 1.7)

## 1. Goal

This phase transitions the Data Pipeline V3 from a transient, CLI-bound application into a robust, long-running server architecture. A standalone **Node process** will host the core pipeline logic (`ServiceManager`) and expose an HTTP API for control. This decouples the core functionality from external clients, enabling persistent operation, automation, and future scalability.

The primary deliverables for this phase are the `Node` process, a generic `HttpServerProcess`, and the initial `PipelineController`.

## 2. Success Criteria

Upon completion:
1.  A `Node` can be launched as a standalone Java process from a `main` method.
2.  The node loads its configuration from `evochora.conf`, respecting the established precedence order (Environment Variables > CLI Arguments > Config File > Defaults).
3.  All long-running processes defined in the `node.processes` configuration are dynamically loaded, initialized via a consistent constructor contract, and started.
4.  The `HttpServerProcess` correctly parses its `routes` configuration, dynamically loading and registering all specified controllers and static file handlers.
5.  The `PipelineController` exposes all specified REST endpoints, providing full control over the pipeline lifecycle via HTTP.
6.  The `Node` provides a simple, type-safe dependency injection mechanism (Service Registry) for core services like `ServiceManager`.
7.  The entire architecture is modular and testable, with a clean separation of concerns between the `Node`, background processes, and controllers.
8.  All specified coding standards, logging, error handling, and testing requirements are met.

## 3. Configuration Structure (`evochora.conf`)

The application configuration is extended with a `node` block. The `pipeline` configuration remains unchanged in the root to ensure decoupling.

```hocon
# Core domain logic: Remains completely decoupled in the root
pipeline {
  startupSequence = ["consumer", "producer"]
  # ... services and resources as before
}

# The node block configures the server process itself
node {
  # Defines all long-running processes the Node should start and manage
  processes {
    
    # Logical name for the HTTP server process
    httpServer {
      className = "org.evochora.node.processes.http.HttpServerProcess"
      options {
        network {
          host = "0.0.0.0"
          port = 8080
        }
        
        # All routes are defined within the HttpServerProcess's options
        routes {
          # The nesting of objects defines the URL paths.
          # Actions ($controller, $static) are defined by keys prefixed with '$'.
          pipeline {
            # ACTION: Serves static UI files at the base path "/pipeline"
            $static = "/web/pipeline-control"
            
            # Builds the sub-path "/pipeline/api"
            api {
              # ACTION: Serves a controller at "/pipeline/api"
              $controller {
                className = "org.evochora.node.http.api.pipeline.PipelineController"
                options {}
              }
            }
          }
        }
      }
    }
  }
}
```

## 4. Package Structure

```
org.evochora
├── datapipeline              // <-- The pure, independent CORE (remains untouched)
│   ├── ServiceManager.java
│   └── ...
│
└── node                      // <-- NEW: The INFRASTRUCTURE layer
    ├── Node.java                 // Main class, the server's entry point
    │
    ├── config
    │   └── ConfigLoader.java     // Responsible for loading the entire configuration
    │
    ├── spi                     // Service Provider Interfaces for dynamic components
    │   ├── IProcess.java
    │   ├── IController.java
    │   └── ServiceRegistry.java
    │
    ├── processes
    │   ├── AbstractProcess.java
    │   └── http
    │       └── HttpServerProcess.java // Implementation of a manageable HTTP server
    │
    └── http
        ├── AbstractController.java // Base class for all API controllers
        └── api
            └── pipeline
                ├── PipelineController.java // Implements the pipeline control API
                └── dto                     // Data Transfer Objects for the pipeline API
                    ├── PipelineStatusDto.java
                    ├── ServiceStatusDto.java
                    └── ResourceBindingDto.java
```

## 5. Implementation Requirements

### 5.1. Core Components

#### 5.1.1. `ServiceRegistry.java`
A simple service locator class that holds instances of core, application-wide services.
* **Functionality:** A type-safe wrapper around a `Map<Class<?>, Object>`.
* **Methods:** `register(Class<?> type, Object instance)`, `<T> T get(Class<T> type)`.

#### 5.1.2. `Node.java`
The main class and entry point for the application.
* **Responsibilities:**
    * Initialize the `ConfigLoader` and `ServiceRegistry`.
    * Instantiate core services (like `ServiceManager`) and register them in the `ServiceRegistry`.
    * Parse the `node.processes` config. For each entry:
        * Instantiate the process class specified by `className` using reflection, calling its constructor with `(ServiceRegistry, Config)`.
        * Start the process in a managed way (e.g., on a separate thread).
    * Implement a graceful shutdown hook to stop all managed processes.

### 5.2. Process and Controller Abstractions

#### 5.2.1. `IProcess.java`
An interface for all long-running, manageable processes.
```java
public interface IProcess {
    void start();
    void stop();
}
```

#### 5.2.2. `AbstractProcess.java`
An abstract base class providing a consistent constructor for dependency injection.
```java
public abstract class AbstractProcess implements IProcess {
    protected final ServiceRegistry registry;
    protected final Config options;

    public AbstractProcess(ServiceRegistry registry, Config options) {
        this.registry = registry;
        this.options = options;
    }
}
```

#### 5.2.3. `IController.java`
A marker interface for all API controllers.
```java
public interface IController {
    void registerRoutes(Javalin app, String basePath);
}
```

#### 5.2.4. `AbstractController.java`
An abstract base class providing a consistent constructor for dependency injection.
```java
public abstract class AbstractController implements IController {
    protected final ServiceRegistry registry;
    protected final Config options;

    public AbstractController(ServiceRegistry registry, Config options) {
        this.registry = registry;
        this.options = options;
    }
}
```

### 5.3. `HttpServerProcess` Implementation

**File:** `org.evochora.node.processes.http.HttpServerProcess.java`
* **Implementation:** Extends `AbstractProcess`.
* **Constructor:** Calls `super(registry, options)` and parses the `options.routes` block recursively to build an internal list of all route definitions.
* **`start` method:**
    * Initializes a Javalin instance with the configured network settings.
    * Iterates through its list of route definitions:
        * For `$static` routes, it calls `app.config.addStaticFiles(...)`.
        * For `$controller` routes, it dynamically instantiates the controller (`className` via reflection), calling the constructor `(ServiceRegistry, Config)`, and then registers it with Javalin via `controller.registerRoutes(app, basePath)`.
    * Starts the Javalin server.
* **`stop` method:** Stops the Javalin server.

### 5.4. `PipelineController` Implementation

**File:** `org.evochora.node.http.api.pipeline.PipelineController.java`
* **Implementation:** Extends `AbstractController`.
* **Constructor:** Calls `super(registry, options)` and retrieves the `ServiceManager` from the registry: `this.serviceManager = registry.get(ServiceManager.class);`.
* **`registerRoutes` method:**
    * Registers all API endpoints as specified below.
    * Handler methods must map internal domain objects (from `ServiceManager`) to the specified DTOs for JSON responses.

#### 5.4.1. `PipelineController` API Endpoints
All paths are relative to the controller's base path (e.g., `/pipeline/api`).

* **Global Control:**
    * `GET /status`: Returns `200 OK` with a `PipelineStatusDto` body.
    * `POST /start`: Returns `202 Accepted` with an empty body.
    * `POST /stop`: Returns `202 Accepted` with an empty body.
    * `POST /restart`: Returns `202 Accepted` with an empty body.
    * `POST /pause`: Returns `202 Accepted` with an empty body.
    * `POST /resume`: Returns `202 Accepted` with an empty body.
* **Individual Service Control:**
    * `GET /service/{serviceName}/status`: Returns `200 OK` with a `ServiceStatusDto` body.
    * `POST /service/{serviceName}/start`: Returns `202 Accepted` with an empty body.
    * ... (and for `stop`, `restart`, `pause`, `resume`).

#### 5.4.2. Data Transfer Objects (DTOs)
The DTOs are the public contract of the API. They must be accurate JSON representations of the existing internal data structures. The controller is responsible for the mapping.

* **`ResourceBindingDto.java`**: A serializable representation of a `ResourceBinding`.
    * **Source:** Created from a `ResourceBinding` object.
    * **Fields:** `portName` (from `context`), `resourceName` (from `context`), `usageType` (from `context`), and `metrics` (from the resource wrapper).
* **`ServiceStatusDto.java`**: A JSON representation of `ServiceStatus`.
    * **Source:** Created from a `ServiceStatus` object and the service's name.
    * **Fields:** `name` (String), `state` (Enum), `metrics` (Map), `errors` (List), `resourceBindings` (List of `ResourceBindingDto`).
* **`PipelineStatusDto.java`**: The overall status of the node.
    * **Source:** Aggregated by the controller.
    * **Fields:** `nodeId` (String, from hostname), `status` (String: "RUNNING", "STOPPED", "DEGRADED"), `services` (List of `ServiceStatusDto`).

## 6. Coding Standards
* **JavaDoc:** All public classes, methods, and non-obvious protected/private methods must have clear and concise JavaDoc explaining their purpose, parameters, and return values.
* **Immutability:** DTOs must be implemented as immutable Java `records`. Internal domain objects should be immutable where possible.
* **Final Fields:** Class fields must be declared `final` whenever possible to promote immutability.
* **Code Style:** Adhere to the existing code style of the project.

## 7. Logging Strategy
* **Framework:** All logging must use the SLF4J API.
* **Target:** All log output must be directed to `stdout` to support standard container log collection.
* **Node Lifecycle:** The `Node` must log the lifecycle of its managed processes at `INFO` level (e.g., "Starting process 'httpServer'..."). It must **not** log the lifecycle of the data pipeline services to avoid redundancy.
* **HTTP Requests:** The `HttpServerProcess` must log incoming requests (method and path) at `DEBUG` level.
* **Configuration:** Log levels should be configurable via `logback.xml` and environment variables.

## 8. Error Handling
* **Node Startup:** The `Node` must terminate with a non-zero exit code if any part of the startup process fails (e.g., configuration error, port in use, process initialization failure). Errors must be logged clearly.
* **API Error Responses:** Controllers must handle exceptions gracefully and return a consistent JSON error body: `{"timestamp": "...", "status": 404, "error": "Not Found", "message": "Service 'xyz' not found."}`.
    * **`404 Not Found`**: Returned when a specific resource (e.g., `/service/nonexistent`) cannot be found.
    * **`409 Conflict`**: Returned for invalid state transitions (e.g., starting an already running service).
    * **`400 Bad Request`**: Returned for malformed client requests (e.g., invalid JSON body for future endpoints).
    * **`500 Internal Server Error`**: Returned for any unhandled server-side exception. The full stack trace must only be logged on the server.

## 9. Testing Requirements

### 9.1. Unit Tests (`@Tag("unit")`)
* **`ConfigLoader`**: Test precedence hierarchy (ENV > CLI > FILE > DEFAULTS) and environment variable mapping.
* **`Node`**: Test dynamic loading and initialization of processes. Use mock `IProcess` implementations.
* **`HttpServerProcess`**: Test route parsing and controller registration logic. Use mock `IController` implementations.
* **`PipelineController`**: Test each handler method individually. Mock the `ServiceManager` to verify that the correct methods are called and test the mapping logic from domain objects to DTOs.

### 9.2. Integration Tests (`@Tag("integration")`)
* **Full Node Test:** Write an integration test that starts a complete `Node` with a real `HttpServerProcess` and `PipelineController`, using a dedicated test `evochora.conf`.
* **End-to-End API Test:** Use an HTTP client library (e.g., REST Assured) to send requests to the running test node. Verify the HTTP status codes and JSON responses for all `PipelineController` endpoints, including error cases. This test must use a real, running `ServiceManager` configured with `DummyConsumerService` and `DummyProducerService`.