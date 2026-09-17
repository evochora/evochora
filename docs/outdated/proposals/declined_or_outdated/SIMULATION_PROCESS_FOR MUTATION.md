

# Implementation Plan: Generalized Simulation Processes & Mutation System

This document outlines the steps to refactor the specific EnergyStrategy system into a generic ISimulationProcess plugin system. This enables arbitrary interventions (Mutation, Energy, Catastrophes) and instruction interception (e.g., simulating hardware failures).


## Phase 1: Protocol Buffer Contracts (Persistence Layer)

**Goal:** Generalize the data structures used for metadata and snapshots to support any named process, not just energy strategies.


### 1.1 Update Metadata Contracts

**File:** src/main/proto/org/evochora/datapipeline/api/contracts/metadata_contracts.proto



1. **Rename Message:** Change message EnergyStrategyConfig to message ProcessConfig.
    * Field 1: strategy_type -> process_class_name (The fully qualified Java class name).
    * Field 2: config_json -> config_json (Remains the same).
2. **Update Root Message:** In message SimulationMetadata:
    * Field 5: repeated EnergyStrategyConfig energy_strategies -> repeated ProcessConfig active_processes.


### 1.2 Update Tick Data Contracts

**File:** src/main/proto/org/evochora/datapipeline/api/contracts/tickdata_contracts.proto



1. **Rename Message:** Change message StrategyState to message ProcessState.
    * Field 1: strategy_type -> process_class_name.
    * Field 2: state_blob -> state_blob.
2. **Update Root Messages:**
    * In message TickData: Change repeated StrategyState strategy_states (field 7) to repeated ProcessState process_states.
    * In message TickDelta: Change repeated StrategyState strategy_states (field 8) to repeated ProcessState process_states.


## Phase 2: Runtime SPI (Java Interfaces)

**Goal:** Define the Java interfaces for the new plugin system.


### 2.1 Create ISimulationProcess

**File:** src/main/java/org/evochora/runtime/spi/ISimulationProcess.java (New File)

Replaces IEnergyDistributionCreator.

package org.evochora.runtime.spi; \
\
import org.evochora.runtime.Simulation; \
import org.evochora.runtime.spi.ISerializable; \
import java.util.Map; \
\
/** \
* Generic interface for simulation interventions (Mutation, Energy, etc.). \
* Must implement ISerializable for snapshotting. \
  */ \
  public interface ISimulationProcess extends ISerializable { \
  \
  /** \
    * Called immediately after instantiation via reflection. \
    * @param config Parameters from the configuration file (e.g., probability, amount). \
    * @param rng The random provider to be used by this process. \
      */ \
      void configure(Map&lt;String, Object> config, IRandomProvider rng); \
      \
      /** \
    * Executed once per tick by the Simulation loop. \
    * @param simulation Access to environment and organisms. \
      */ \
      void execute(Simulation simulation); \
      } \



### 2.2 Create IInstructionMutator

**File:** src/main/java/org/evochora/runtime/spi/IInstructionMutator.java (New File)

Allows plugins to intercept and modify instructions *before* execution.

package org.evochora.runtime.spi; \
\
import org.evochora.runtime.isa.Instruction; \
import org.evochora.runtime.isa.Instruction.Operand; \
import java.util.List; \
\
public interface IInstructionMutator { \
/** \
* Intercepts an instruction before execution but after operand resolution. \
* * @param instruction The instruction about to be executed. \
* @param resolvedOperands The list of resolved operands (Mutable!).  \
* Modifying values in this list changes the execution outcome. \
* @return The instruction to execute (usually the original, but can be wrapped/replaced),  \
* or null to cancel execution. \
*/ \
Instruction mutate(Instruction instruction, List&lt;Operand> resolvedOperands); \
} \



## Phase 3: Runtime Core Implementation

**Goal:** Integrate the new interfaces into the engine and enable instruction interception.


### 3.1 Update VirtualMachine

**File:** src/main/java/org/evochora/runtime/VirtualMachine.java



1. Add a registry for mutators: private final List&lt;IInstructionMutator> mutators = new ArrayList&lt;>();.
2. Add management method: public void addMutator(IInstructionMutator mutator) { ... }.
3. **Critical Change:** Modify the execute(Instruction instruction) method to implement the Safe-Interception-Flow:

public void execute(Instruction instruction) { \
// 1. Resolve operands once (triggers side effects like Stack POP) \
List&lt;Instruction.Operand> operands = instruction.resolveOperands(simulation.getEnvironment()); \
\
// 2. Cache them back into the instruction to freeze state \
instruction.setPreResolvedOperands(operands); \
\
// 3. Apply Mutators (Plugins can now safely modify 'operands') \
if (!mutators.isEmpty()) { \
for (IInstructionMutator mutator : mutators) { \
instruction = mutator.mutate(instruction, operands); \
if (instruction == null) return; // Execution cancelled by plugin \
} \
} \
\
// 4. Execute (Instruction uses the pre-resolved/modified operands) \
instruction.execute(context, artifact); \
} \



### 3.2 Update Simulation Class

**File:** src/main/java/org/evochora/runtime/Simulation.java



1. Remove private final List&lt;StrategyWithConfig> energyStrategies; (or similar references).
2. Add private final List&lt;ISimulationProcess> processes = new ArrayList&lt;>();.
3. Add method public void addProcess(ISimulationProcess process) { this.processes.add(process); }.
4. Add method public List&lt;ISimulationProcess> getProcesses() { return processes; }.
5. In the tick() method, iterate over processes and call process.execute(this) instead of the old energy logic.


### 3.3 Delete Legacy Interfaces



* Delete org.evochora.runtime.worldgen.IEnergyStrategyCreator.
* Delete org.evochora.runtime.worldgen.EnergyStrategyFactory.


## Phase 4: Data Pipeline & Glue Code

**Goal:** Instantiate plugins via reflection and map data to the new Protobuf contracts.


### 4.1 Update SimulationEngine Initialization

**File:** src/main/java/org/evochora/datapipeline/services/SimulationEngine.java



1. Refactor Configuration Loading: \
   Replace initializeEnergyStrategies with initializeSimulationProcesses.
    * Input: simulation.processes (List of Config objects).
    * Logic:
        * Read className string from config.
        * Use Class.forName(className) and getDeclaredConstructor().newInstance().
        * Call process.configure(optionsMap, simulation.getRandomProvider()).
        * **Important:** Register the process with simulation.addProcess(process).
        * **Check:** If process instanceof IInstructionMutator, register it with simulation.getVirtualMachine().addMutator(...).
2. **Refactor Metadata Building (buildMetadataMessage):**
    * Iterate simulation.getProcesses().
    * Build ProcessConfig (Protobuf) using class name and original config JSON.
    * Add to SimulationMetadata.active_processes.
3. **Refactor State Extraction (extractStrategyStates -> extractProcessStates):**
    * Iterate simulation.getProcesses().
    * Call process.saveState() (from ISerializable).
    * Build ProcessState (Protobuf).
    * Return List&lt;ProcessState>.


### 4.2 Update DeltaCodec

**File:** src/main/java/org/evochora/datapipeline/utils/delta/DeltaCodec.java



1. Update captureTick signature to accept List&lt;ProcessState> instead of List&lt;StrategyState>.
2. Update internal logic to assign this list to the new process_states field in the TickData or TickDelta builders.


## Phase 5: Migration of Existing Strategies

**Goal:** Port existing energy logic to the new system.


### 5.1 Refactor Solar & Geyser

**Files:** src/main/java/org/evochora/runtime/worldgen/SolarRadiationCreator.java & GeyserCreator.java



1. Implement ISimulationProcess.
2. Move constructor logic to configure(Map&lt;String, Object> params, IRandomProvider rng).
3. Rename distributeEnergy to execute(Simulation simulation).
4. Inside execute, continue using simulation.getEnvironment() to place energy.


## Phase 6: Configuration Update

**File:** evochora.conf (Example)

Update the configuration structure to use the new generic list:

simulation { \
// Old \
// energyStrategies = [ ... ] \
\
// New \
processes = [ \
{ \
className: "org.evochora.runtime.worldgen.SolarRadiationCreator" \
options: { \
amount: 50 \
probability: 0.001 \
} \
}, \
{ \
className: "org.evochora.plugins.mutation.CosmicRayMutation" \
options: { \
rate: 0.0001 \
} \
} \
] \
} \

