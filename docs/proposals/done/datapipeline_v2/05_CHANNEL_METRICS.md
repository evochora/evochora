# Implementation Plan: Dynamic Pipeline Monitoring


## 1. Objective

Refactor the pipeline's monitoring capabilities to replace hardcoded, static values for channel throughput and status with dynamically calculated, real-time metrics. The goal is to provide an accurate, live view of the pipeline's health and performance through the existing CLI status command.


## 2. Key Architectural Principles



* **Decoupling:** The measurement logic must be decoupled from the service and channel implementations. Services should not be aware that they are being monitored.
* **Performance:** The monitoring mechanism must have a negligible performance impact on the core data processing path.
* **Accuracy:** Metrics must be consistent, comparable over time, and accurately reflect the state of the system (e.g., excluding paused intervals from throughput calculations).
* **Extensibility:** The system must gracefully handle channels that do not provide monitoring capabilities.


## 3. Core Component: Channel Binding Wrappers

Two new classes must be created to represent and instrument the connection between a service and a channel.

### 3.1 ChannelMetrics Record

* **Class:** ChannelMetrics
* **Package:** org.evochora.datapipeline.api.services
* **Functionality:**
    * Immutable record containing: `double messagesPerSecond`, `long timestamp`, `int errorCount`
    * Used to store calculated throughput metrics with metadata
    * Timestamp allows for stale data detection
    * Error count tracks failed operations for debugging



* **Classes to Create:**
    * AbstractChannelBinding&lt;T> (abstract base class)
    * InputChannelBinding&lt;T> extends AbstractChannelBinding&lt;T>
    * OutputChannelBinding&lt;T> extends AbstractChannelBinding&lt;T>
* **Package:** org.evochora.datapipeline.core
* **Functionality:**
    * **AbstractChannelBinding:** Contains common functionality: serviceName, channelName, direction, AtomicLong counter, underlying channel reference, and shared methods (getServiceName, getChannelName, getDirection, getAndResetCount, getUnderlyingChannel).
    * **Concrete Classes:** InputChannelBinding and OutputChannelBinding act as wrappers (Decorator Pattern) around the actual channel instances (IInputChannel, IOutputChannel).
    * They must implement the IInputChannel&lt;T> and IOutputChannel&lt;T> interfaces respectively, delegating all calls to the wrapped (delegate) channel.
    * Upon a successful read() or write() call, the corresponding wrapper must call the abstract incrementCount() method to atomically increment the internal counter.
    * This design avoids code duplication and ensures consistent behavior across both input and output bindings.


## 4. Central Logic: ServiceManager Modifications

The ServiceManager will be responsible for orchestrating the entire monitoring process.



* **Wiring Logic (buildPipeline method):**
    * When wiring services to channels, the ServiceManager must **not** pass the raw channel object to the service's addInputChannel/addOutputChannel methods.
    * Instead, it must instantiate the appropriate InputChannelBinding or OutputChannelBinding wrapper, passing the actual channel to the wrapper's constructor.
    * The **wrapper instance** is then passed to the service.
    * The ServiceManager must maintain a central list of all created ChannelBinding instances.
* **Periodic Throughput Calculation:**
    * The ServiceManager must implement a lightweight, periodic background task using a java.util.concurrent.ScheduledExecutorService with a single thread.
    * **Configuration:** The update interval must be configurable via `pipeline.metrics.updateIntervalSeconds` (default: 3 seconds). Metrics can be disabled via `pipeline.metrics.enableMetrics` (default: true).
    * This task will iterate through the central list of ChannelBinding instances.
    * In each iteration, it will call getAndResetCount() on each binding and calculate the messagesPerSecond by dividing the count by the task's period in seconds.
    * **Error Handling:** If getAndResetCount() throws an exception, log it as WARN level and continue with the next binding. Do not let metric collection failures affect the pipeline.
    * The calculated throughput value must be stored in a ConcurrentHashMap<String, ChannelMetrics> where:
        * **Key:** String in format "serviceName:channelName:direction" (e.g., "simulation-engine:raw-tick-stream:OUTPUT")
        * **Value:** ChannelMetrics record containing throughput, timestamp, and error count
    * This task ensures that metrics are consistent and comparable over fixed time windows and that paused service time is naturally excluded.


## 5. Dynamic State Logic: AbstractService Modifications

The AbstractService will determine the BindingState based on real-time channel metrics.



* **getServiceStatus method:**
    * This method is responsible for creating the list of ChannelBindingStatus records.
    * When constructing a ChannelBindingStatus record, it must dynamically determine the BindingState.
    * It must get the underlying channel object from its ChannelBinding instance.
    * It must perform an instanceof check to see if the channel implements IMonitorableChannel.
    * **If it is monitorable:**
        * It will call getBacklogSize() and getCapacity().
        * For an **INPUT** direction, the state is WAITING if getBacklogSize() == 0, otherwise ACTIVE.
        * For an **OUTPUT** direction, the state is WAITING if getCapacity() >= 0 and getBacklogSize() >= getCapacity(), otherwise ACTIVE. (Note: use >= 0 for capacity to handle the -1 unlimited case).
    * **If it is NOT monitorable:**
        * It must gracefully handle this case by providing a sensible default. The recommended default state is ACTIVE.


## 6. Status Reporting: ServiceManager Display Logic

The getStatus() method in ServiceManager, which formats the CLI output, needs to be updated.



* It will retrieve the latest, periodically calculated messagesPerSecond value for each binding.
* It will retrieve the dynamically determined BindingState from the ServiceStatus object provided by the AbstractService.
* It will use these real values to populate the "STATE" and "ACTIVITY" columns in the status table, replacing the previous hardcoded placeholders.