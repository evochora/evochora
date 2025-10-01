# Data Pipeline V3 - SimulationEngine (Phase 2.1)

## Goal

Implement SimulationEngine as the first production service that extracts simulation data from the Evochora runtime and outputs it as Protobuf messages for persistence and analysis. This service represents the critical interface between the simulation core and the data pipeline, designed for maximum throughput while maintaining complete checkpoint reproducibility.

## Success Criteria

Upon completion:
1. SimulationEngine compiles and extends AbstractService with IMonitorable
2. Extracts complete simulation state using runtime's public API
3. Generates Protobuf messages (SimulationMetadata, TickData) as defined in Phase 2.0
4. Supports configurable sampling intervals (N=1 for every tick, N>1 for sampling)
5. Implements auto-pause functionality at configured tick numbers
6. Captures complete checkpoint data (RNG state, strategy states, all organism/cell states) for future resume capability
7. All unit and integration tests pass

**Note:** This phase focuses on data capture only. Resume functionality (initializing simulation from checkpoint data) will be implemented in a future phase.

## Prerequisites and Related Specifications

This specification is part of Data Pipeline V3 implementation. Before implementing, understand the architectural context:

**Essential Reading:**
- `docs/proposals/datapipeline_v3/00_HIGHLEVEL_CONCEPT.md` - Overall Data Pipeline V3 architecture, explains the service-based pipeline concept, resource abstraction, and dual-mode deployment (in-process vs cloud)
- `docs/proposals/datapipeline_v3/02_PROTOBUF_SETUP.md` - Explains why Protobuf is used and how proto files generate Java classes
- `docs/proposals/datapipeline_v3/04_SERVICE_FOUNDATION.md` - Describes AbstractService base class, service lifecycle, and IMonitorable interface
- `docs/proposals/datapipeline_v3/05_TEST_SERVICES.md` - Explains DummyProducerService and DummyConsumerService patterns

**Completed Prerequisites:**
- Phase 0: API Foundation - IService, IResource, IMonitorable interfaces exist
- Phase 1.1-1.8: Core infrastructure - AbstractService, InMemoryBlockingQueue, ServiceManager implemented
- Phase 2.0: Protobuf data structures - metadata_contracts.proto and tickdata_contracts.proto defined with complete field documentation

**Key Concepts from Prerequisites:**

From 00_HIGHLEVEL_CONCEPT.md:
- Services are independent processing units that extend AbstractService
- Resources are abstract interfaces (IInputQueueResource, IOutputQueueResource) that hide implementation details
- The same service code works with InMemoryBlockingQueue (in-process) or future CloudMessageBusQueue (distributed) without modification
- SimulationEngine is the first "producer" service that generates data

From 04_SERVICE_FOUNDATION.md:
- AbstractService provides lifecycle management: constructor → start() → run() → stop()
- Services access resources via getRequiredResource() in constructor
- The run() method contains the main processing loop
- checkPause() handles pause/resume functionality
- IMonitorable.getMetrics() returns Map<String, Object> with service-specific metrics

From 02_PROTOBUF_SETUP.md:
- Protobuf messages are generated Java classes from .proto files
- Use builder pattern: TickData.newBuilder().setField(...).build()
- Services work with Java objects, not bytes - resources handle serialization
- Generated classes are in org.evochora.datapipeline.api.contracts package

## Architectural Context

### Differences from Archived DataPipeline V2

The archived implementation had several architectural issues that V3 resolves:

**V2 Approach (Archived):**
- Runtime had specialized export methods (`exportOrganismState()`, `exportCellState()`)
- Direct coupling between Runtime and DataPipeline
- Custom serialization format (not Protobuf)
- Single output channel for all data

**V3 Approach (Current):**
- Runtime remains completely decoupled (no export methods)
- SimulationEngine uses only public API (`organism.getId()`, `organism.getIp()`, etc.)
- Protobuf-based data structures with complete field documentation
- Separate outputs for metadata (sent once) and tick data (sent every N ticks)
- Complete state capture (RNG, strategy states, organisms, cells) for future checkpoint capability

### Data Flow

```
Runtime (Simulation Core)
    ↓ (public API access only)
SimulationEngine Service
    ↓ (via IOutputQueueResource)
tickDataOutput → PersistenceService
metadataOutput → PersistenceService
```

### Protobuf Contract Reference

This service implements the data structures defined in:
- `src/main/proto/org/evochora/datapipeline/api/contracts/metadata_contracts.proto`
- `src/main/proto/org/evochora/datapipeline/api/contracts/tickdata_contracts.proto`

Refer to these files for detailed documentation on:
- Where each field comes from (e.g., `Organism.getId()`)
- How to create each message (e.g., `Vector.newBuilder()`)
- What data types to use (e.g., `int32`, `int64`, `bytes`)

### Critical Architectural Principle: Serialization Responsibility

**Services work with Protobuf message objects, not serialized bytes.** Services never call `toByteArray()` or `parseFrom()` - that's the resource's job.

**What SimulationEngine DOES:**
```java
// ✅ Create Protobuf message object
TickData tickData = TickData.newBuilder()
    .setSimulationRunId(runId)
    .setTickNumber(currentTick)
    // ... set all fields ...
    .build();

// ✅ Send Protobuf object to resource
tickDataOutput.send(tickData);  // Passes Java object, NOT bytes
```

**What SimulationEngine DOES NOT DO:**
```java
// ❌ NEVER call toByteArray() - that's the resource's job!
byte[] bytes = tickData.toByteArray();

// ❌ NEVER call parseFrom() - that's the resource's job!
TickData deserialized = TickData.parseFrom(bytes);
```

**Why This Separation Matters:**

Different resource implementations handle transport differently:
- Some resources store Java object references directly (no serialization needed)
- Other resources serialize to byte streams for network transport
- The service code is identical regardless of which resource implementation is used

Resources are generic (`IOutputQueueResource<T extends Message>`) and handle any serialization internally based on their specific transport requirements. Services stay simple and fast by working only with Java objects.

## Implementation Guidance

### Required Familiarization

Before implementing SimulationEngine, familiarize yourself with these key areas of the codebase:

**1. Runtime Package (`src/main/java/org/evochora/runtime/`)**
- `Simulation.java` - Core simulation orchestration, provides `tick()` method and organism access
- `model/Environment.java` - Environment representation, provides cell/molecule access
- `model/Organism.java` - Organism state, all getter methods map to proto fields
- `model/EnvironmentProperties.java` - Environment configuration
- `spi/IRandomProvider.java` - Random number provider interface with state serialization
- `spi/ISerializable.java` - Base interface for checkpoint-serializable components

**2. Compiler Package (`src/main/java/org/evochora/compiler/`)**
- `Compiler.java` - Compiles assembly sources to ProgramArtifact
- `api/ProgramArtifact.java` - Complete compilation output with all metadata

**3. DataPipeline Package (`src/main/java/org/evochora/datapipeline/`)**
- `api/services/AbstractService.java` - Base class for all services, provides lifecycle management
- `services/DummyProducerService.java` - Example service showing AbstractService usage patterns
- `services/DummyConsumerService.java` - Example service showing resource access patterns

**4. Protobuf Contracts (`src/main/proto/org/evochora/datapipeline/api/contracts/`)**
- `metadata_contracts.proto` - Metadata message structures with field documentation
- `tickdata_contracts.proto` - Tick data message structures with field documentation

### Archived Reference Implementation

An archived version exists at `src/archive/2025-09-24/main/java/org/evochora/server/engine/SimulationEngine.java`

**Key Differences from V3 Approach:**
- **Archived**: Used custom serialization, not Protobuf
- **V3**: Uses Protobuf messages with complete field mapping

- **Archived**: Implemented `Runnable` directly with manual thread management
- **V3**: Extends AbstractService for lifecycle management

- **Archived**: Used `ITickMessageQueue` custom interface
- **V3**: Uses generic `IOutputQueueResource<T>` with Protobuf types

- **Archived**: Had checkpoint/pause logic integrated
- **V3**: Service pause (via AbstractService) is separate from future checkpoint-resume

Use the archived implementation as a general reference for:
- How to construct and initialize a Simulation instance
- How to access organisms and environment state
- Pattern for iterating organisms and cells
- Auto-pause tick checking logic

Do NOT copy the archived approach for:
- Message serialization (use Protobuf builders instead)
- Thread management (use AbstractService lifecycle)
- Queue interfaces (use IOutputQueueResource)

## Implementation Requirements

### SimulationEngine Class

**File:** `src/main/java/org/evochora/datapipeline/services/SimulationEngine.java`

**Class Declaration:**
```java
public class SimulationEngine extends AbstractService implements IMonitorable {
    // Implementation details below
}
```

**Required Functionality:**
- Extends AbstractService for lifecycle management
- Implements IMonitorable for service-specific metrics
- Creates and executes an Evochora simulation using the Runtime API
- Extracts simulation state every N ticks (configurable sampling)
- Generates Protobuf messages and sends to configured output queues
- Supports pause/resume operations
- Auto-pauses at configured tick numbers

### Configuration Options

Based on evochora.conf.bak pattern:

```hocon
simulation-engine {
  className = "org.evochora.datapipeline.services.SimulationEngine"
  outputs {
    tickData: "raw-tick-data"
    metadataOutput: "context-data"
  }
  options {
    # Sampling configuration
    samplingInterval = 1  # N=1 means capture every tick, N=10 means every 10th tick

    # Environment configuration
    environment {
      shape = [100, 100]  # World dimensions (2D example)
      topology = "TORUS"  # TORUS or BOUNDED
    }

    # Organism configuration
    organisms = [{
      program = "assembly/primordial/main.s"  # Path to assembly source
      initialEnergy = 30000
      placement {
        positions = [5, 5]  # Initial position in environment
      }
    }]

    # Energy strategy configuration
    energyStrategies = [{
      className = "org.evochora.runtime.worldgen.GeyserCreator"
      options {
        count = 5
        interval = 100
        amount = 50
        safetyRadius = 3
      }
    }, {
      className = "org.evochora.runtime.worldgen.SolarRadiationCreator"
      options {
        probability = 0.05
        amount = 25
        safetyRadius = 2
        executionsPerTick = 1
      }
    }]

    # Auto-pause configuration
    pauseTicks = [20000, 90000]  # Auto-pause at these tick numbers

    # Random seed (optional - if not specified, use random seed)
    seed = 42

    # Maximum organism energy
    maxOrganismEnergy = 50000
  }
}
```

**Configuration Parameter Details:**

- **samplingInterval**: Integer (default: 1)
  - N=1: Capture every tick (0.5-1μs serialization overhead per tick)
  - N>1: Capture every Nth tick (reduces data volume)

- **environment.shape**: Array of integers
  - Dimensions of the environment (e.g., [100, 100] for 2D, [50, 50, 50] for 3D)
  - Maps to EnvironmentProperties constructor

- **environment.topology**: String ("TORUS" or "BOUNDED")
  - TORUS: Edges wrap around
  - BOUNDED: Edges are boundaries
  - Maps to EnvironmentProperties.getToroidal() array

- **organisms**: Array of organism configurations
  - **program**: Path to assembly source file (relative to project root)
  - **initialEnergy**: Starting energy for organism
  - **placement.positions**: Initial coordinates in environment

- **energyStrategies**: Array of energy strategy configurations
  - **className**: Fully qualified class name implementing IEnergyDistributionCreator
  - **options**: Strategy-specific configuration (passed to strategy constructor)

- **pauseTicks**: Array of tick numbers (default: empty array)
  - Service automatically pauses when reaching these tick numbers
  - User can resume via CLI command
  - Enables checkpoint creation at specific simulation milestones

- **seed**: Long (optional)
  - If specified: Use this seed for reproducibility
  - If not specified: Generate random seed using System.currentTimeMillis()

- **maxOrganismEnergy**: Integer (default: 50000)
  - Maximum energy an organism can have

### Resource Requirements

**Required Outputs:**
- **tickData**: `IOutputQueueResource<TickData>` - For high-frequency tick data
- **metadataOutput**: `IOutputQueueResource<SimulationMetadata>` - For one-time metadata

**Pattern:**
```java
// In constructor
private final IOutputQueueResource<TickData> tickDataOutput;
private final IOutputQueueResource<SimulationMetadata> metadataOutput;

public SimulationEngine(String name, Config options, Map<String, List<IResource>> resources) {
    super(name, options, resources);

    this.tickDataOutput = getRequiredResource("tickData", IOutputQueueResource.class);
    this.metadataOutput = getRequiredResource("metadataOutput", IOutputQueueResource.class);

    // Parse configuration and initialize simulation components
    // ...
}
```

### Service Lifecycle

#### Initialization Phase (Constructor)

1. **Parse Configuration**
   - Extract environment settings (shape, topology)
   - Parse organism configurations (program paths, positions, energy)
   - Parse energy strategy configurations
   - Validate all configuration parameters

2. **Compile Programs**
   - Use `org.evochora.compiler.Compiler` to compile assembly sources
   - Generate ProgramArtifact for each program
   - Store ProgramArtifact objects for metadata message

3. **Initialize Runtime Components**
   - Create EnvironmentProperties from configuration
   - Create IRandomProvider (SeededRandomProvider with configured/random seed)
   - Create IEnergyDistributionCreator instances from energyStrategies config
   - Create Environment using Runtime API
   - Create initial organisms at configured positions

4. **Prepare Metadata Message**
   - Create SimulationMetadata Protobuf message
   - Include all ProgramArtifacts
   - Include environment configuration
   - Include energy strategy configurations
   - Include resolved TypeSafe Config as JSON string
   - Store run ID for use in tick messages

**Important:** All initialization happens in constructor. The simulation does NOT start running until `start()` is called.

#### Start Phase (start() method)

**Override start() to send metadata before beginning simulation loop:**

```java
@Override
public void start() {
    // Send metadata message once before starting simulation loop
    metadataOutput.put(buildMetadataMessage());

    // Call super to start the service thread (which calls run())
    super.start();
}
```

**Why override start():**
- Metadata must be sent exactly once before any tick data
- The run() method may be called multiple times (pause/resume)
- start() is called once when service transitions to RUNNING state
- Ensures metadata is always available before tick data arrives

#### Run Phase (run() method)

**Main Simulation Loop:**
```java
@Override
protected void run() throws InterruptedException {
    // Simulation loop
    while (getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED) {
        checkPause(); // Handle pause/resume

        // Check auto-pause configuration
        if (shouldAutoPause(currentTick)) {
            logger.info("{} auto-paused at tick {} due to pauseTicks configuration",
                        getClass().getSimpleName(), currentTick);
            pause(); // Trigger auto-pause
            continue;
        }

        // Execute one simulation tick
        simulation.tick();  // Note: simulation is the Simulation instance created in constructor
        currentTick++;

        // Capture data if this is a sampling tick
        if (currentTick % samplingInterval == 0) {
            TickData tickData = captureTickData(currentTick);
            tickDataOutput.send(tickData);
        }
    }
}
```

**Tick Data Capture Process:**
1. Create TickData.Builder
2. Set simulation_run_id, tick_number, capture_time_ms
3. Extract organism states from simulation.getOrganisms()
4. Extract non-empty cell states from simulation.getEnvironment()
5. Capture RNG state using randomProvider.saveState()
6. Capture energy strategy states using each strategy's saveState()
7. Build and return TickData message

### Data Extraction Patterns

#### Extracting OrganismState

```java
private OrganismState extractOrganismState(Organism organism) {
    OrganismState.Builder builder = OrganismState.newBuilder();

    // Basic fields
    builder.setOrganismId(organism.getId());
    if (organism.getParentId() != null) {
        builder.setParentId(organism.getParentId());
    }
    builder.setBirthTick(organism.getBirthTick());
    builder.setProgramId(organism.getProgramId());
    builder.setEnergy(organism.getEr());  // Note: method is getEr(), not getEnergy()

    // Instruction pointer and movement
    builder.setIp(convertVector(organism.getIp()));
    builder.setInitialPosition(convertVector(organism.getInitialPosition()));
    builder.setDv(convertVector(organism.getDv()));
    for (int[] dp : organism.getDataPointers()) {
        builder.addDataPointers(convertVector(dp));
    }
    builder.setActiveDpIndex(organism.getActiveDpIndex());

    // Registers
    for (RegisterValue rv : organism.getDataRegisters()) {
        builder.addDataRegisters(convertRegisterValue(rv));
    }
    for (RegisterValue rv : organism.getProcedureRegisters()) {
        builder.addProcedureRegisters(convertRegisterValue(rv));
    }
    for (RegisterValue rv : organism.getFormalParamRegisters()) {
        builder.addFormalParamRegisters(convertRegisterValue(rv));
    }
    for (int[] loc : organism.getLocationRegisters()) {
        builder.addLocationRegisters(convertVector(loc));
    }

    // Stacks
    for (RegisterValue rv : organism.getDataStack()) {
        builder.addDataStack(convertRegisterValue(rv));
    }
    for (int[] loc : organism.getLocationStack()) {
        builder.addLocationStack(convertVector(loc));
    }
    for (ProcFrame frame : organism.getCallStack()) {
        builder.addCallStack(convertProcFrame(frame));
    }

    // Status
    builder.setIsDead(organism.isDead());
    builder.setInstructionFailed(organism.isInstructionFailed());
    if (organism.getFailureReason() != null) {
        builder.setFailureReason(organism.getFailureReason());
    }
    for (ProcFrame frame : organism.getFailureCallStack()) {
        builder.addFailureCallStack(convertProcFrame(frame));
    }

    return builder.build();
}
```

#### Extracting CellState (Sparse)

Only extract non-empty cells:

```java
private List<CellState> extractCellStates(Environment environment) {
    List<CellState> cells = new ArrayList<>();

    // Iterate all cells in environment
    int[] shape = environment.getShape();
    for (int[] coord : iterateCoordinates(shape)) {
        Molecule molecule = environment.getMolecule(coord);

        // Only include non-empty cells
        if (molecule.getType() != 0 || molecule.getValue() != 0 || molecule.getOwnerId() != 0) {
            CellState.Builder builder = CellState.newBuilder();
            builder.setCoordinate(convertVector(coord));
            builder.setMoleculeType(molecule.getType());
            builder.setMoleculeValue(molecule.getValue());
            builder.setOwnerId(molecule.getOwnerId());
            cells.add(builder.build());
        }
    }

    return cells;
}
```

#### Extracting ProgramArtifact

```java
private org.evochora.datapipeline.api.contracts.ProgramArtifact convertProgramArtifact(
        org.evochora.compiler.api.ProgramArtifact artifact) {

    org.evochora.datapipeline.api.contracts.ProgramArtifact.Builder builder =
        org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder();

    builder.setProgramId(artifact.programId());

    // Convert sources
    for (Map.Entry<String, List<String>> entry : artifact.sources().entrySet()) {
        SourceLines.Builder sourcesBuilder = SourceLines.newBuilder();
        sourcesBuilder.addAllLines(entry.getValue());
        builder.putSources(entry.getKey(), sourcesBuilder.build());
    }

    // Convert machine code layout (Map<int[], Integer> → repeated InstructionMapping)
    for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
        InstructionMapping.Builder mapping = InstructionMapping.newBuilder();
        mapping.setPosition(convertVector(entry.getKey()));
        mapping.setInstruction(entry.getValue());
        builder.addMachineCodeLayout(mapping.build());
    }

    // Convert initial world objects (Map<int[], PlacedMolecule> → repeated PlacedMoleculeMapping)
    for (Map.Entry<int[], PlacedMolecule> entry : artifact.initialWorldObjects().entrySet()) {
        PlacedMoleculeMapping.Builder mapping = PlacedMoleculeMapping.newBuilder();
        mapping.setPosition(convertVector(entry.getKey()));

        org.evochora.datapipeline.api.contracts.PlacedMolecule.Builder moleculeBuilder =
            org.evochora.datapipeline.api.contracts.PlacedMolecule.newBuilder();
        moleculeBuilder.setType(entry.getValue().type());
        moleculeBuilder.setValue(entry.getValue().value());
        mapping.setMolecule(moleculeBuilder.build());

        builder.addInitialWorldObjects(mapping.build());
    }

    // Convert source map (Map<Integer, SourceInfo> → repeated SourceMapEntry)
    for (Map.Entry<Integer, SourceInfo> entry : artifact.sourceMap().entrySet()) {
        SourceMapEntry.Builder sourceMapEntry = SourceMapEntry.newBuilder();
        sourceMapEntry.setLinearAddress(entry.getKey());

        org.evochora.datapipeline.api.contracts.SourceInfo.Builder sourceInfoBuilder =
            org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder();
        sourceInfoBuilder.setFileName(entry.getValue().fileName());
        sourceInfoBuilder.setLineNumber(entry.getValue().lineNumber());
        sourceInfoBuilder.setColumnNumber(entry.getValue().columnNumber());
        sourceMapEntry.setSourceInfo(sourceInfoBuilder.build());

        builder.addSourceMap(sourceMapEntry.build());
    }

    // Convert call site bindings (Map<Integer, int[]> → repeated CallSiteBinding)
    for (Map.Entry<Integer, int[]> entry : artifact.callSiteBindings().entrySet()) {
        CallSiteBinding.Builder bindingBuilder = CallSiteBinding.newBuilder();
        bindingBuilder.setLinearAddress(entry.getKey());
        bindingBuilder.setTargetCoord(convertVector(entry.getValue()));
        builder.addCallSiteBindings(bindingBuilder.build());
    }

    // Convert relative coord to linear address (direct map)
    builder.putAllRelativeCoordToLinearAddress(artifact.relativeCoordToLinearAddress());

    // Convert linear address to coord (Map<Integer, int[]> → repeated LinearAddressToCoord)
    for (Map.Entry<Integer, int[]> entry : artifact.linearAddressToCoord().entrySet()) {
        LinearAddressToCoord.Builder addrBuilder = LinearAddressToCoord.newBuilder();
        addrBuilder.setLinearAddress(entry.getKey());
        addrBuilder.setCoord(convertVector(entry.getValue()));
        builder.addLinearAddressToCoord(addrBuilder.build());
    }

    // Convert label mappings (Map<Integer, String> → repeated LabelMapping)
    for (Map.Entry<Integer, String> entry : artifact.labelAddressToName().entrySet()) {
        LabelMapping.Builder labelBuilder = LabelMapping.newBuilder();
        labelBuilder.setLinearAddress(entry.getKey());
        labelBuilder.setLabelName(entry.getValue());
        builder.addLabelAddressToName(labelBuilder.build());
    }

    // Convert register alias map (direct map)
    builder.putAllRegisterAliasMap(artifact.registerAliasMap());

    // Convert procedure parameter names (Map<String, List<String>> → map with ParameterNames)
    for (Map.Entry<String, List<String>> entry : artifact.procNameToParamNames().entrySet()) {
        ParameterNames.Builder paramsBuilder = ParameterNames.newBuilder();
        paramsBuilder.addAllNames(entry.getValue());
        builder.putProcNameToParamNames(entry.getKey(), paramsBuilder.build());
    }

    // Convert token map (Map<SourceInfo, TokenInfo> → repeated TokenMapEntry)
    for (Map.Entry<SourceInfo, TokenInfo> entry : artifact.tokenMap().entrySet()) {
        TokenMapEntry.Builder tokenMapEntry = TokenMapEntry.newBuilder();

        org.evochora.datapipeline.api.contracts.SourceInfo.Builder sourceInfoBuilder =
            org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder();
        sourceInfoBuilder.setFileName(entry.getKey().fileName());
        sourceInfoBuilder.setLineNumber(entry.getKey().lineNumber());
        sourceInfoBuilder.setColumnNumber(entry.getKey().columnNumber());
        tokenMapEntry.setSourceInfo(sourceInfoBuilder.build());

        org.evochora.datapipeline.api.contracts.TokenInfo.Builder tokenInfoBuilder =
            org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder();
        tokenInfoBuilder.setTokenText(entry.getValue().tokenText());
        tokenInfoBuilder.setTokenType(entry.getValue().tokenType());
        tokenInfoBuilder.setScope(entry.getValue().scope());
        tokenMapEntry.setTokenInfo(tokenInfoBuilder.build());

        builder.addTokenMap(tokenMapEntry.build());
    }

    // Convert token lookup (nested Map → repeated FileTokenLookup)
    for (Map.Entry<String, Map<Integer, Map<Integer, List<TokenInfo>>>> fileEntry : artifact.tokenLookup().entrySet()) {
        FileTokenLookup.Builder fileBuilder = FileTokenLookup.newBuilder();
        fileBuilder.setFileName(fileEntry.getKey());

        for (Map.Entry<Integer, Map<Integer, List<TokenInfo>>> lineEntry : fileEntry.getValue().entrySet()) {
            LineTokenLookup.Builder lineBuilder = LineTokenLookup.newBuilder();
            lineBuilder.setLineNumber(lineEntry.getKey());

            for (Map.Entry<Integer, List<TokenInfo>> columnEntry : lineEntry.getValue().entrySet()) {
                ColumnTokenLookup.Builder columnBuilder = ColumnTokenLookup.newBuilder();
                columnBuilder.setColumnNumber(columnEntry.getKey());

                for (TokenInfo token : columnEntry.getValue()) {
                    org.evochora.datapipeline.api.contracts.TokenInfo.Builder tokenInfoBuilder =
                        org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder();
                    tokenInfoBuilder.setTokenText(token.tokenText());
                    tokenInfoBuilder.setTokenType(token.tokenType());
                    tokenInfoBuilder.setScope(token.scope());
                    columnBuilder.addTokens(tokenInfoBuilder.build());
                }

                lineBuilder.addColumns(columnBuilder.build());
            }

            fileBuilder.addLines(lineBuilder.build());
        }

        builder.addTokenLookup(fileBuilder.build());
    }

    return builder.build();
}
```

#### Helper Methods

```java
private Vector convertVector(int[] components) {
    Vector.Builder builder = Vector.newBuilder();
    for (int component : components) {
        builder.addComponents(component);
    }
    return builder.build();
}

private org.evochora.datapipeline.api.contracts.RegisterValue convertRegisterValue(
        org.evochora.runtime.model.RegisterValue rv) {

    org.evochora.datapipeline.api.contracts.RegisterValue.Builder builder =
        org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder();

    if (rv.isScalar()) {
        builder.setScalar(rv.getScalar());
    } else {
        builder.setVector(convertVector(rv.getVector()));
    }

    return builder.build();
}

private org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrame(
        org.evochora.runtime.model.ProcFrame frame) {

    org.evochora.datapipeline.api.contracts.ProcFrame.Builder builder =
        org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder();

    builder.setProcName(frame.getProcName());
    builder.setAbsoluteReturnIp(convertVector(frame.getAbsoluteReturnIp()));

    for (RegisterValue rv : frame.getSavedPrs()) {
        builder.addSavedPrs(convertRegisterValue(rv));
    }
    for (RegisterValue rv : frame.getSavedFprs()) {
        builder.addSavedFprs(convertRegisterValue(rv));
    }
    builder.putAllFprBindings(frame.getFprBindings());

    return builder.build();
}
```

### Monitoring Implementation

#### SimulationEngine Metrics

**IMonitorable.getMetrics():**
- `"current_tick"`: Current simulation tick number
- `"organisms_alive"`: Number of living organisms
- `"organisms_total"`: Total organisms created (including dead)
- `"ticks_per_second"`: Simulation throughput (moving average over configurable window)
- `"messages_sent"`: Total TickData messages sent
- `"sampling_interval"`: Current sampling interval (N)

#### Error Handling

- Record OperationalError for queue send failures
- Handle runtime exceptions gracefully (log and continue if possible)
- Validate configuration parameters in constructor (fail fast)

### Coding Standards

#### Documentation Requirements

- **All public classes and methods**: Comprehensive Javadoc in English
- **Configuration options**: Document in class Javadoc with defaults and examples
- **Data extraction methods**: Explain mapping from Runtime API to Protobuf
- **Complex logic**: Inline comments for simulation loop and state extraction
- **Proto references**: Link to proto file comments for field documentation

#### Naming Conventions

- **Classes**: SimulationEngine (PascalCase)
- **Methods**: captureTickData, extractOrganismState (camelCase)
- **Fields**: tickDataOutput, metadataOutput (camelCase)
- **Constants**: MAX_ENERGY, DEFAULT_SAMPLING_INTERVAL (UPPER_SNAKE_CASE)

#### Configuration Handling

- **Use TypeSafe Config**: All configuration via `com.typesafe.config.Config`
- **Default values**: Provide sensible defaults for optional parameters
- **Validation**: Validate all configuration in constructor (fail fast)
- **Error messages**: Clear error messages for invalid configuration

**Example Pattern:**
```java
// Required configuration (no default)
List<? extends Config> organismConfigs = options.getConfigList("organisms");
if (organismConfigs.isEmpty()) {
    throw new IllegalArgumentException("At least one organism must be configured");
}

// Optional configuration with default
int samplingInterval = options.hasPath("samplingInterval")
    ? options.getInt("samplingInterval")
    : 1;

if (samplingInterval < 1) {
    throw new IllegalArgumentException("samplingInterval must be >= 1, got: " + samplingInterval);
}
```

#### Error Handling

- **Configuration errors**: Throw IllegalArgumentException in constructor
- **Resource failures**: Record as OperationalError, attempt retry
- **Runtime exceptions**: Log ERROR, attempt to continue simulation
- **InterruptedException**: Handle gracefully, respect service stop signal
- **Null safety**: Validate all inputs, use Optional for nullable returns

#### Logging Conventions

**Follow AbstractService logging rules strictly:**

- **INFO Level**:
  - Service lifecycle (handled by AbstractService)
  - Auto-pause events: `log.info("{} auto-paused at tick {} due to pauseTicks configuration", getClass().getSimpleName(), tickNumber)`
  - Simulation milestones (e.g., "Simulation initialized with {} organisms")

- **WARN Level**:
  - Resource temporarily unavailable (retrying)
  - Configuration warnings (using defaults)
  - Performance degradation

- **ERROR Level**:
  - Unrecoverable simulation errors
  - Resource failures requiring manual intervention
  - Configuration errors preventing startup

- **DEBUG Level**:
  - Per-tick details (only for debugging)
  - Data extraction details
  - Detailed state information

**CRITICAL: No per-tick INFO logging**
```java
// ❌ BAD - High frequency logging
log.info("Processing tick {}", tickNumber);
log.info("Sent TickData message");

// ✅ GOOD - Sparse lifecycle logging
log.info("{} initialized with {} organisms", getClass().getSimpleName(), organismCount);
log.info("{} auto-paused at tick {} due to pauseTicks configuration",
         getClass().getSimpleName(), tickNumber);
```

#### Thread Safety

- **Service state**: Managed by AbstractService
- **Metrics**: Use AtomicLong for counters
- **Configuration**: Immutable after constructor
- **Resource access**: Thread-safe via AbstractService utilities

#### Code Quality

- **Single responsibility**: SimulationEngine handles only simulation execution and data extraction
- **Separation of concerns**: Runtime stays decoupled, service uses only public API
- **Configuration validation**: All validation in constructor
- **Resource management**: Proper cleanup in stop() if needed

### Checkpoint Data Capture Requirements

**Critical Requirement:** Every TickData message must contain complete state information that would enable future checkpoint/resume functionality.

**Implementation Requirements:**

1. **RNG State Serialization**
   - Use `IRandomProvider.saveState()` to capture complete RNG state
   - Serialize Well19937c internal state (624 ints + index)
   - Include in every TickData message for checkpoint capability

2. **Energy Strategy State Serialization**
   - Use `ISerializable.saveState()` for each energy strategy
   - Stateful strategies (GeyserCreator) serialize locations
   - Stateless strategies (SolarRadiationCreator) return empty bytes
   - Include all strategy states in every TickData message

3. **Complete State Capture**
   - Capture ALL living organisms (not just alive count)
   - Capture ALL non-empty cells (sparse representation)
   - Capture complete organism state (registers, stacks, IP, etc.)
   - No lossy compression or approximations

4. **Validation Tests**
   - Unit test: Verify all required fields present in TickData messages
   - Integration test: Serialize/deserialize TickData, verify bit-identical data
   - Completeness test: Verify RNG state bytes have correct length (624*4 + 4 = 2500 bytes)
   - Stress test: Large simulation with data capture at multiple sampling intervals

**Note:** Actual checkpoint/resume functionality (initializing a simulation from TickData) will be implemented in a future phase. This phase focuses on ensuring complete data capture.

## Testing Requirements

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/services/SimulationEngineTest.java`

**Required test cases:**
1. **Configuration Parsing**: All configuration options parsed correctly
2. **Resource Access**: Required resources accessed correctly
3. **Metadata Generation**: SimulationMetadata message created correctly
4. **TickData Generation**: TickData messages created correctly with all fields
5. **Sampling Interval**: Only every Nth tick captured when N>1
6. **Auto-Pause**: Service pauses at configured tick numbers
7. **State Extraction**: Organism and cell states extracted correctly
8. **Program Compilation**: Assembly programs compiled and included in metadata
9. **Checkpoint Data**: RNG and strategy states included in TickData
10. **Error Handling**: Invalid configuration throws clear exceptions

### Integration Tests

**File:** `src/test/java/org/evochora/datapipeline/services/SimulationEngineIntegrationTest.java`

**End-to-End Scenarios:**

1. **Complete Pipeline Test**
   - Create InMemoryBlockingQueue for tick data
   - Create InMemoryBlockingQueue for metadata
   - Create SimulationEngine with simple configuration
   - Start service and run for 100 ticks
   - Verify metadata message received
   - Verify 100 tick messages received
   - Verify all Protobuf messages parse correctly

2. **Checkpoint Data Completeness Test**
   - Run simulation 0→100 ticks
   - Capture TickData messages at various sampling points
   - Verify all required checkpoint fields are present:
     - RNG state bytes have correct length (2500 bytes for Well19937c)
     - All strategy states present (count matches energyStrategies config)
     - All organisms captured (count matches alive organism count)
     - All non-empty cells captured (verify sparse representation)
   - Verify Protobuf serialization/deserialization is bit-identical

3. **Auto-Pause Test**
   - Configure pauseTicks = [50, 100]
   - Start simulation
   - Verify service pauses at tick 50
   - Resume service via CLI/API
   - Verify service pauses at tick 100

4. **Sampling Test**
   - Configure samplingInterval = 10
   - Run for 100 ticks
   - Verify only 10 TickData messages sent (ticks 10, 20, ..., 100)

**Test Requirements:**
- Use real Runtime components (not mocked)
- Use real InMemoryBlockingQueue (not mocked)
- Use real Protobuf messages (not mocked)
- Verify Protobuf serialization/deserialization
- All tests tagged `@Tag("integration")`

### Performance Tests

**File:** `src/test/java/org/evochora/datapipeline/services/SimulationEnginePerformanceTest.java`

**Performance Benchmarks:**
1. **Serialization Overhead**: Measure tick time with/without data capture
2. **Throughput**: Measure ticks/second with N=1 sampling
3. **Memory**: Monitor memory usage during long runs
4. **Queue Pressure**: Test with slow consumers (backpressure handling)

**Performance Goals:**
- Data capture overhead should scale linearly with organism count
- Data capture overhead should scale linearly with environment size (sparse cell iteration)
- Memory growth should be linear with simulation complexity (no leaks)
- Per-tick overhead should not dominate simulation execution time
- Performance characteristics should be identical in both in-process and cloud deployment modes

## Non-Requirements

- Do NOT implement distributed simulation (single-process only)
- Do NOT implement simulation visualization (data extraction only)
- Do NOT implement custom compression (let Protobuf handle it)
- Do NOT implement replay capability (that's for future services)
- Do NOT optimize prematurely (correct implementation first)

## Validation

The implementation is correct if:
1. SimulationEngine compiles and extends AbstractService correctly
2. Extracts complete simulation state using only Runtime public API
3. Generates valid Protobuf messages matching proto file specifications
4. Sends metadata exactly once at start
5. Sends TickData every N ticks (respecting samplingInterval)
6. Auto-pauses at configured tick numbers
7. Includes complete checkpoint data (RNG state, strategy states, all organisms, all non-empty cells)
8. Checkpoint data completeness tests pass (all required fields present, correct byte lengths)
9. All unit, integration, and performance tests pass
10. Logging follows conventions (no per-tick INFO logs)
11. Service can be paused, resumed, and stopped correctly
12. Metrics provide meaningful operational visibility

## Success Indicators

After implementation:
- ✅ Complete simulation data available for analysis
- ✅ Complete state capture enables future checkpoint/resume functionality
- ✅ Data pipeline can process simulation output in real-time
- ✅ No coupling between Runtime and DataPipeline
- ✅ Configuration-driven simulation setup
- ✅ Ready for PersistenceService implementation (Phase 2.2)

This specification enables the first production service in Data Pipeline V3, bridging the simulation core with the data processing infrastructure while maintaining architectural purity and maximum performance.