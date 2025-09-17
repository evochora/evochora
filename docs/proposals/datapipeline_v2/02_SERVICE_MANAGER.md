## Goal

To create the core orchestration components of the data pipeline. This task combines three parts:



1. A concrete, thread-safe InMemoryChannel for in-process communication.
2. The central ServiceManager that reads the configuration, instantiates and wires together all services and channels, and manages their lifecycle.
3. The necessary test components and unit tests to verify that the orchestration core functions correctly.

The result of this task will be a functional, runnable, and **fully tested** pipeline core that can load and manage services.


## Part 1: InMemoryChannel.java


### 1.1. File Location and Structure



* **File:** InMemoryChannel.java
* **Package:** org.evochora.datapipeline.channels


### 1.2. Class Definition



* **Name:** InMemoryChannel
* **Generics:** The class must be generic, accepting a type parameter &lt;T>.
* **Interfaces:** It must implement all three channel interfaces from the API package:
    * IInputChannel&lt;T>
    * IOutputChannel&lt;T>
    * IMonitorableChannel


### 1.3. Internal Implementation



* The core of this class must be a java.util.concurrent.BlockingQueue&lt;T>.
* java.util.concurrent.ArrayBlockingQueue&lt;T> is the recommended choice due to its bounded nature.


### 1.4. Constructor



* The class must have a single public constructor that accepts a com.typesafe.config.Config object, representing the options block from the configuration. \
 
```java
/**
 * Constructs an InMemoryChannel based on the provided configuration.
 * @param options A Config object containing the channel's settings, e.g., 'capacity'.
 */
public InMemoryChannel(com.typesafe.config.Config options)
```
* Inside the constructor, parse the capacity from the options object. Provide a sensible default (e.g., 1000) if the key is not present.
* The internal ArrayBlockingQueue must be initialized with this capacity.


### 1.5. Method Implementation



* Implement read(), write(T message), getQueueSize(), and getCapacity() by delegating the calls to the internal BlockingQueue instance (take(), put(), size(), etc.).


## Part 2: ServiceManager.java


### 2.1. File Location and Structure



* **File:** ServiceManager.java
* **Package:** org.evochora.datapipeline.core


### 2.2. Class Definition



* **Name:** ServiceManager
* **Description:** The central orchestrator for the data pipeline. It is responsible for building the entire pipeline from a configuration object and managing the lifecycle of all services.


### 2.3. Constructor



* The class must have a single public constructor that accepts the root com.typesafe.config.Config object for the entire pipeline. \

```java
/**
 * Constructs a ServiceManager for a pipeline defined by the given configuration.
 * @param pipelineConfig The root configuration object for the pipeline.
 */
public ServiceManager(com.typesafe.config.Config pipelineConfig)
```  


### 2.4. Core Logic (to be executed within the constructor or a dedicated build() method)

The ServiceManager must perform the following steps upon initialization:



1. **Instantiate Channels:**
    * Read the pipeline.channels configuration block.
    * Iterate through each channel definition (e.g., "raw-tick-stream").
    * For each definition, get the className and the options config block.
    * Using Java Reflection, create an instance of the channel class, passing its options block to the constructor.
    * Store the created channel instances in a Map&lt;String, Object>, mapping the channel name to its instance.
2. **Instantiate Services:**
    * Read the pipeline.services configuration block.
    * Iterate through each service definition (e.g., "simulation").
    * For each definition, get the className and the options config block.
    * Using Java Reflection, create an instance of the service class, passing its options block to the constructor.
    * Store the created service instances in a Map&lt;String, IService>.
3. **Wire Services and Channels:**
    * After all services are instantiated, iterate through the service configurations again.
    * For each service, read its inputs (which is a list of strings) and outputs (list of strings) configuration.
    * For each input channel name, retrieve the corresponding channel instance from the channel map. The service instance needs to be provided with this channel. **Note:** A recommended approach is to define methods like addInputChannel(String name, IInputChannel&lt;?> channel) on a base class that services can extend.
    * Do the same for all output channels.


### 2.5. Public Methods (Lifecycle Management)

The ServiceManager must provide the following public methods to be controlled by the CommandLineInterface:



* **void startAll()**:
    * Iterates through all managed IService instances.
    * For each service, it creates a new Thread and calls the service's start() method within that thread.
    * It should keep a reference to all running threads.
* **void stopAll()**:
    * Calls stop() on all managed services and ensures their threads are properly shut down.
* **void pauseService(String serviceName)**:
    * Finds the service by name and calls its pause() method.
* **void resumeService(String serviceName)**:
    * Finds the service by name and calls its resume() method.
* **List&lt;ServiceStatus> getPipelineStatus()**:
    * Iterates through all managed services and calls getServiceStatus() on each.
    * Returns a list of all status objects, providing a complete snapshot of the pipeline's health. This will be used by the CLI to render the status table.


## Part 3: Example Configuration File for Testing



* **File:** pipeline.hocon (can be placed in a test resources directory)
* **Content:** This file should define a minimal pipeline with one channel and two dummy services (a producer and a consumer) to test the wiring and lifecycle management.

```hocon
pipeline {
  channels {
    test-stream {
      className = "org.evochora.datapipeline.channels.InMemoryChannel"
      options {
        capacity = 10
      }
    }
  }
  services {
    test-producer {
      className = "org.evochora.datapipeline.services.testing.DummyProducerService"
      output = ["test-stream"]
      options {
        messageCount = 100
      }
    }
    test-consumer {
      className = "org.evochora.datapipeline.services.testing.DummyConsumerService"
      input = ["test-stream"]
      options {}
    }
  }
}
```

## Part 4: Testing Requirements
### 4.1. Required Test Components

The following dummy service implementations must be created within the test source set (e.g., src/test/java). They are required to write the unit tests for the ServiceManager.

* **DummyProducerService.java**
    * **Package:** org.evochora.datapipeline.services.testing
    * **Description:** A simple service that implements IService and writes a configured number of messages to its output channel.
    * **Logic:**
        * Its constructor accepts a Config object.
        * It must have a method to receive its output channel (e.g., addOutputChannel).
        * The start() method reads messageCount from its options and writes that many integers (e.g., 0, 1, 2...) to the output channel. After sending all messages, the thread can terminate.
* **DummyConsumerService.java**
    * **Package:** org.evochora.datapipeline.services.testing
    * **Description:** A simple service that implements IService and reads messages from its input channel, counting them.
    * **Logic:**
        * Its constructor accepts a Config object.
        * It must have a method to receive its input channel (e.g., addInputChannel).
        * The start() method enters a loop, calling read() on the input channel until the thread is interrupted (by stop()).
        * It must expose a method like getReceivedMessageCount() for test assertions.


### 4.2. Required Unit Tests

A JUnit test class, ServiceManagerTest.java, must be created to verify the functionality of the core components.



* **Test 1: pipelineShouldBeConstructedCorrectly**
    * **Goal:** Verify that the ServiceManager correctly parses the pipeline.hocon file.
    * **Steps:**
        1. Load the test HOCON configuration.
        2. Instantiate the ServiceManager.
        3. Assert that one channel and two services have been created.
        4. Assert that the DummyProducerService is wired to the InMemoryChannel as an output, and the DummyConsumerService is wired as an input.
* **Test 2: pipelineLifecycleShouldExecuteCorrectly**
    * **Goal:** Verify the end-to-end data flow through a simple pipeline.
    * **Steps:**
        1. Create the pipeline via the ServiceManager.
        2. Call startAll().
        3. Wait a reasonable amount of time for the producer to send all its messages.
        4. Call stopAll().
        5. Assert that the DummyConsumerService has received exactly the messageCount specified in the configuration.
* **Test 3: pauseAndResumeShouldControlFlow**
    * **Goal:** Verify that the pause/resume functionality works as expected.
    * **Steps:**
        1. Start the pipeline.
        2. Immediately call pauseService("test-producer").
        3. Wait briefly and assert that the consumer has received *zero* messages.
        4. Verify via getPipelineStatus() that the producer's state is PAUSED.
        5. Call resumeService("test-producer").
        6. Wait for the pipeline to complete and assert that the consumer has now received all messages.