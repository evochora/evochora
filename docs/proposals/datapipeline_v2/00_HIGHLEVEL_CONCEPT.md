# Architectural Design: Data Pipeline V2


## 1. Overview

This document outlines the architecture for a new, decoupled, and scalable data pipeline. The core concept is to separate the high-performance simulation core from the flexible data processing and analysis components.

The system is designed as a set of independent, pluggable services communicating via abstracted message channels. A central ServiceManager orchestrates the pipeline based on a user-provided configuration, allowing for extreme flexibility in both development and deployment.


## 2. Core Principles



* **Decoupling**: Services are independent and have no knowledge of each other's implementations. They only interact through well-defined channel interfaces and message contracts.
* **Separation of Concerns**: Each service has a single, clear responsibility (e.g., running the simulation, indexing world data, replaying historical data).
* **Configurability**: The entire pipeline topology—which services run and how they are connected—is defined in a configuration file, not in code.
* **Scalability & Flexibility**: The architecture supports running services as threads in a single process, as separate processes on a single machine, or as distributed containers in a cloud environment, all from a single codebase.


## 3. Package Structure

To enforce decoupling and maintain a clear, acyclic dependency hierarchy, the project will be structured under a central org.evochora.datapipeline package.



* **org.evochora.datapipeline.api**: The foundational package containing all public contracts. It has no dependencies on implementation packages.
    * **.contracts**: Defines the data structures (POJOs) for messages transferred between services (e.g., RawTickData).
    * **.channels**: Defines the interfaces for message channels (IInputChannel, IOutputChannel, IMonitorableChannel).
    * **.services**: Defines the interfaces for services (IService) and their status reporting objects (ServiceStatus, ChannelBindingStatus).
* **org.evochora.datapipeline.core**: Contains the orchestration logic (ServiceManager, CommandLineInterface).
* **org.evochora.datapipeline.channels**: Contains concrete channel implementations (e.g., InMemoryChannel, KafkaChannel).
* **org.evochora.datapipeline.services**: Contains concrete service implementations, organized by role (e.g., services.engine.SimulationEngine, services.indexer.WorldIndexer).


## 4. Core Components & Data Flow


### 4.1. Service Roles



* **SimulationEngine**: The high-performance core. Runs the simulation and publishes the complete, raw RawTickData state after each tick.
* **DataIndexer**: A family of services that consume RawTickData and prepare it for analysis by writing to a specialized database.
* **ReplayService**: A service that reads a persisted "Tick-Log" and publishes historical RawTickData messages, allowing for batch processing and re-indexing.
* **WebAnalyzer**: The public-facing API that serves the web frontend by reading from the analysis database.


### 4.2. Channel Abstraction

Message channels are abstracted via interfaces to make the transport mechanism pluggable.



* **IInputChannel&lt;T>**, **IOutputChannel&lt;T>**: Core interfaces for reading and writing POJOs.
* **IMonitorableChannel**: An optional, separate interface a channel can implement to expose global monitoring metrics (e.g., total queue size).


## 5. Key Architectural Decisions


### 5.1. Service Orchestration & Configuration



* **ServiceManager**: A central class responsible for instantiating and wiring the pipeline based on the configuration.
* **Configuration Format**: **HOCON** via the com.typesafe:config library is used, following a strict precedence cascade: **Hardcoded Defaults → File → CLI Arguments → Environment Variables**.
* **Dynamic Loading & Wiring**: The HOCON file specifies the className for each component and its options. The ServiceManager uses reflection to load them.
* **Multiple Channels**: Services can be connected to multiple input and output channels. The configuration supports this via string lists (inputs, outputs).

**Example evochora.conf:**

```
pipeline {
  channels {
    raw-tick-stream {
      className = "org.evochora.datapipeline.channels.InMemoryChannel"
      options { capacity = 1000 }
    }
  }
  services {
    simulation {
      className = "org.evochora.datapipeline.services.DummyService"
      outputs = ["raw-tick-stream"]
      options { organismSources = ["assembly/primordial/main.s"] }
    }
  }
}
```


### 5.2. Monitoring and Status Reporting



* **Service State**: A service can be RUNNING, PAUSED, or STOPPED.
* **Channel Binding**: The connection between a service and a channel is a "binding". This binding is a wrapper responsible for connection-specific metrics.
* **Binding State**: A binding can be ACTIVE or WAITING.
* **Throughput**: For ACTIVE bindings, a throughput metric (messages/sec) will be reported, calculated as a moving average that excludes waiting times.
* **Status Object**: IService.getServiceStatus() returns a rich object containing the overall service state and a list of statuses for each channel binding.


### 5.3. Execution and Resumability



* **Execution Model**: Services run as **threads** within the ServiceManager process, allowing flexible deployment from a single codebase.
* **Resumability**: A PersistenceService archives the live RawTickData stream. The SimulationEngine can be started from an archived state. The ReplayService uses the same archive for batch processing.


### 5.4. Deployment Models

The architecture seamlessly supports different deployment models by changing the channel implementation in the configuration.



* **In-Process Model**: Uses InMemoryChannel for direct object reference passing between threads.
* **Distributed Model**: Uses a network-capable implementation (e.g., KafkaChannel) where each service runs in its own container and communicates over the network via an external message broker.


### 5.5. Logging Strategy



* **Format**: All logs will be written as **structured JSON** to facilitate automated parsing and analysis.
* **Target**: The sole destination for all logs is stdout (the console). This delegates log collection and storage to the execution environment (e.g., Docker, Kubernetes).
* **Technology**: The existing **Logback** framework will be configured with a JsonEncoder.
* **Levels**: Standard levels (ERROR, WARN, INFO, DEBUG, TRACE) will be used consistently across all components. INFO is the default for production.


## 6. Non-Functional Requirements & Outlook

The following principles are considered core to the design and should be implemented from the beginning.


### 6.1. Serialization & Data Contracts



* **Responsibility**: Services work exclusively with Java POJOs. The concrete IChannel implementation is responsible for serializing POJOs to a byte[] for transport and deserializing them back.
* **Standardization**: All producers and consumers on a given channel must use the same serialization format.
* **Format Recommendation**: For the high-performance raw-tick-stream, **Apache Avro** is the mandated serialization format. This choice provides an optimal balance of high performance, compact data size, and robust schema evolution capabilities, which is critical for the long-term maintainability of data archives. For less critical channels, other formats may be used.


### 6.2. Idempotent Consumers



* **Principle**: All data-consuming services (especially DataIndexers) must be designed to be idempotent. This is crucial for data integrity, especially when using message systems with "at-least-once" delivery guarantees.
* **Implementation**: A consumer must be able to process the same message multiple times without causing incorrect side effects. This is typically achieved by using a unique, deterministic identifier from the message (e.g., the tickNumber) to perform UPSERT operations or to check for duplicates before processing.


### 6.3. Observability

The architecture is designed to be highly observable.



* **Metrics**: The IMonitorableChannel interface and the channel binding mechanism provide the hooks to export detailed pipeline metrics (queue depth, throughput) to systems like **Prometheus/Grafana**.
* **Logging**: The structured logging strategy is fundamental to this goal.
* **Tracing**: For future debugging in a distributed setup, **OpenTelemetry** could be integrated to trace the flow of data through the entire pipeline.