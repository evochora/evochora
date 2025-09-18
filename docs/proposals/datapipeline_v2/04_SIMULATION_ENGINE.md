## Goal
To implement the SimulationEngine service. This service is the primary data producer in the pipeline, responsible for running the core simulation and publishing its state. This task involves wrapping the existing simulation logic into the IService pattern, making it a fully compliant and configurable component of the new data pipeline architecture.

## 1. File Location and Structure
* **File:** SimulationEngine.java
* **Package:** org.evochora.datapipeline.services

## 2. Class Definition
* **Name:** SimulationEngine
* **Base Class:** It must extend the existing org.evochora.datapipeline.services.BaseService class.

## 3. Prerequisite: Backward-Compatible Refactoring
* The existing energy strategy creator classes (e.g., GeyserCreator, SolarRadiationCreator in org.evochora.runtime.worldgen) must be refactored.
* They need a **new constructor** that accepts a com.typesafe.config.Config object to parse their parameters from an options block.
* The original constructors must be kept to ensure backward compatibility with other parts of the system that may still use them.

## 4. Constructor and Initialization
* The class must have a public constructor that accepts a com.typesafe.config.Config object (its options).
* It must override addOutputChannel to store references to **all** its output channels in a list.

## 5. Configuration (options)
The service must be configured via its options block, mirroring the structure of the existing config.jsonc.
**Example options block:**
```hocon 
options {
  seed = 12345
  environment {
    shape = [260, 160]
    topology = "TORUS" // Corresponds to toroidal = true
  }
  organisms = [
    {
      program = "assembly/primordial/main.s"
      initialEnergy = 30000
      placement {
        # For this initial implementation, only the "fixed" strategy is required.
        strategy = "fixed"
        # A list of n-dimensional coordinates for each organism instance.
        positions = [[105, 65], [120, 70]]
      }
    }
  ]
  energyStrategies = [
    {
      className = "org.evochora.runtime.worldgen.GeyserCreator"
      options {
        count = 5
        interval = 5
        amount = 999
        safetyRadius = 2
      }
    },
    {
      className = "org.evochora.runtime.worldgen.SolarRadiationCreator"
      options {
        probability = 0.05
        amount = 999
      }
    }
  ]
  pauseTicks = [50000, 100000]  # Optional: Automatically pause simulation at these tick numbers
}
```
## 6. Core Logic (implemented in the run() method)
1. **Initialization:**
    * Create a new simulation instance based on the complete configuration.
    * This includes compiling all source files specified in the `organisms` list and generating the `ProgramArtifact` objects.
    * For each organism configuration, use the specified `placement` strategy (e.g., "fixed") to create and place the initial organisms with their correct `initialEnergy` at the given `positions`.
    * Dynamically load and instantiate the specified `energyStrategies` using their `className` and `Config`-based constructors.
2. **Publish Initial Context:**
    * **Crucially**, before starting the simulation loop, the service must create and send a single `SimulationContext` message to **all** configured output channels.
    * This message must be fully populated with the `simulationRunId`, the `EnvironmentProperties`, and the list of all `ProgramArtifacts` generated during initialization. This message provides the static context for the entire following stream of tick data.
3. **Main Simulation Loop:**
    * Enter a loop that respects the STOPPED and PAUSED states.
    * Execute a single simulation tick.
    * After the tick, create a RawTickData message.
    * Write the RawTickData message to **all** configured output channels.
    * Check if the current tickNumber is in the pauseTicks list and call pause() if it is.
    * **Note:** When paused due to pauseTicks, the service remains paused until manually resumed via the CLI.
    * Repeat.

## 7. Interface Implementation
* **getServiceStatus()**: Must be overridden to provide detailed status, including activity details and the state of all channel bindings.

## 8. Logging Requirements
* **INFO**: Log startup and shutdown events.
* **DEBUG**: Log progress and context publication.
* **WARN**: Log recoverable errors that allow simulation to continue (e.g., individual organism compilation failures, missing energy strategies).
* **ERROR**: Log unrecoverable simulation errors that prevent the simulation from running (e.g., no organisms could be compiled, invalid environment configuration).

## 9. Error Handling Strategy
The service must implement resilient error handling:

* **Recoverable Errors (WARN level):**
  * Individual organism compilation failures → Skip the organism, continue with others
  * Missing energy strategy classes → Skip the strategy, continue with others  
  * Invalid configuration values → Use defaults, continue simulation
  * Missing assembly files → Skip the organism, continue with others

* **Critical Errors (ERROR level):**
  * No organisms could be compiled successfully → Stop service, simulation cannot start
  * Invalid environment configuration → Stop service, simulation cannot start
  * Critical system failures → Stop service

The simulation should only stop if it cannot run at all. Individual component failures should be logged as warnings and the simulation should continue with the remaining valid components.

## 10. Testing Requirements
A JUnit test class, SimulationEngineTest.java, must be created.

* **Test 1: shouldCorrectlyInitializeFromConfig**: Verify that the service correctly parses its configuration, including the dynamic loading of energy strategies.
* **Test 2: shouldCreateAndPlaceOrganismsCorrectly**:
    * **Goal:** Verify that organisms are created according to the organisms configuration block.
    * **Steps:**
        1. Provide a test configuration with a "fixed" placement strategy and specific positions.
        2. Instantiate the SimulationEngine.
        3. Access the internal simulation state (before starting the loop).
        4. Assert that the correct number of organisms has been created.
        5. Assert that they are located at the exact positions specified in the configuration.
* **Test 3: shouldPublishContextMessageFirstToAllOutputs**: Verify that the SimulationContext is the first message sent to every configured output channel.
* **Test 4: shouldPublishTickDataMessagesInLoop**: Verify correct RawTickData publication after the context message.