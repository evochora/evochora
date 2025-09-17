## Goal

To create all fundamental Java interfaces and data classes that will serve as the common contract for the entire data pipeline. These files form the architectural foundation and will be located in the org.evochora.datapipeline.api package and its sub-packages.

All interfaces and classes must be documented with meaningful Javadoc comments.


## 1. Package: org.evochora.datapipeline.api.contracts


### **SimulationContext.java**



* **Type:** public class
* **Description:** A message containing the static, immutable context for a simulation run. This message is typically sent once at the beginning of a stream.
* **Fields:** \
  private String simulationRunId; // A unique identifier for this specific simulation run. \
  private EnvironmentProperties environment; // Describes the world's structure. \
  private java.util.List&lt;ProgramArtifact> artifacts; // The list of all programs used in the simulation. \



### RawTickData.java



* **Type:** public class
* **Description:** A POJO representing the dynamic state of the simulation at a single point in time. This is the primary high-volume message that flows through the pipeline.
* **Fields:** \
  private String simulationRunId; // Foreign key linking to the SimulationContext. \
  private long tickNumber; \
  private java.util.List&lt;RawCellState> cells; \
  private java.util.List&lt;RawOrganismState> organisms; \



### EnvironmentProperties.java



* **Type:** public class
* **Description:** Defines the static properties of the simulation world.
* **Fields:** \
  private int[] worldShape; // {width, height, ...} defining the world's dimensions. \
  private WorldTopology topology; // e.g., TORUS or BOUNDED. \



### WorldTopology.java



* **Type:** public enum
* **Description:** Defines the topology of the world's boundaries.
* **Values:** TORUS, BOUNDED.


### ProgramArtifact.java



* **Type:** public class
* **Description:** A rich, serializable representation of a compiled program, including all necessary metadata for execution, analysis, and debugging.
* **Fields:** \
  private String programId; \
  private java.util.Map&lt;String, java.util.List&lt;String>> sources; // Map of source file names to their content. \
  private java.util.List&lt;InstructionMapping> machineCodeLayout; // The compiled code as a sparse list of positions and instructions. \
  private java.util.List&lt;PlacedMoleculeMapping> initialWorldObjects; // Molecules to be placed at simulation start. \
  private java.util.List&lt;SourceMapEntry> sourceMap; // Maps linear addresses to source code locations. \
  private java.util.List&lt;LabelMapping> labelMap; // Maps addresses to label names. \
  // Additional metadata for advanced debugging and analysis can be added here as needed. \



### Helper Classes for ProgramArtifact



* **InstructionMapping.java (Record/Class):**
    * int[] position;
    * int instruction;
* **PlacedMoleculeMapping.java (Record/Class):**
    * int[] position;
    * SerializablePlacedMolecule molecule;
* **SourceMapEntry.java (Record/Class):**
    * int linearAddress;
    * SerializableSourceInfo sourceInfo;
* **LabelMapping.java (Record/Class):**
    * int linearAddress;
    * String labelName;
* **SerializablePlacedMolecule.java (Record/Class):**
    * int type;
    * int value;
* **SerializableSourceInfo.java (Record/Class):**
    * String sourceName;
    * int line;
    * int column;


### RawCellState.java



* **Type:** public class
* **Description:** Represents the state of a single cell in the world.
* **Fields:** \
  private int[] position; // {x, y, ...} supporting n-dimensions \
  private int type; \
  private int value; \
  private int ownerId; \



### RawOrganismState.java



* **Type:** public class
* **Description:** Represents the complete internal and external state of a single organism.
* **Fields:** \
  private int organismId; \
  private Integer parentId; // Nullable for primordial organisms \
  private int ownerId; \
  private String programId; // ID linking to the ProgramArtifact \
  private int energy; \
  private long birthTick; \
  private boolean isDead; \
  \
  // VM State \
  private int[] position; // Instruction Pointer (IP) position {x, y, ...} \
  private java.util.List&lt;int[]> dp; // Data Pointer positions \
  private int activeDp; \
  private int[] dv; // Direction Vector {x, y, ...} \
  \
  // Registers \
  private int[] dataRegisters;      // DRs \
  private int[] procedureRegisters; // PRs \
  private int[] formalParamRegisters; // FPRs \
  private java.util.List&lt;int[]> locationRegisters;  // LRs \
  \
  // Stacks \
  private java.util.List&lt;StackValue> dataStack; \
  private java.util.List&lt;int[]> locationStack; \
  private java.util.List&lt;SerializableProcFrame> callStack; \
  \
  // Error Information \
  private OrganismErrorState errorState; // Null if no error has occurred \



### SerializableProcFrame.java



* **Type:** public class
* **Description:** A serializable representation of a procedure call frame.
* **Fields:** \
  private String procedureName; \
  private int[] returnAddress; // n-dimensional position vector \
  private int[] savedProcedureRegisters; \
  private int[] savedFormalParamRegisters; \



### OrganismErrorState.java



* **Type:** public class
* **Description:** Contains detailed information about a terminal error that occurred in an organism.
* **Fields:** \
  private String reason; \
  private java.util.List&lt;SerializableProcFrame> callStackAtFailure; \



### StackValue.java



* **Type:** public class
* **Description:** A wrapper object to represent values on the Data Stack, which can be either a 32-bit integer literal or an n-dimensional position vector.
* **Fields:** \
  private StackValueType type; \
  private int literalValue;      // Used if type is LITERAL \
  private int[] positionValue;   // Used if type is POSITION \



### StackValueType.java



* **Type:** public enum
* **Description:** Discriminator for the type of value held in a StackValue object.
* **Values:** LITERAL, POSITION.


## 2. Package: org.evochora.datapipeline.api.channels


### IInputChannel.java



* **Type:** public interface with a generic type parameter &lt;T>.
* **Description:** Defines the contract for a component from which messages can be read.
* **Methods:** \
  T read() throws InterruptedException; \



### IOutputChannel.java



* **Type:** public interface with a generic type parameter &lt;T>.
* **Description:** Defines the contract for a component to which messages can be written.
* **Methods:** \
  void write(T message) throws InterruptedException; \



### IMonitorableChannel.java



* **Type:** public interface
* **Description:** An optional interface that can be implemented by channels to provide global metrics.
* **Methods:** \
  long getQueueSize(); \
  long getCapacity(); \



## 3. Package: org.evochora.datapipeline.api.services


### IService.java



* **Type:** public interface
* **Description:** Defines the lifecycle and control interface for all services in the pipeline.
* **Methods:** \
  void start(); \
  void stop(); \
  void pause(); \
  void resume(); \
  ServiceStatus getServiceStatus(); \



### ServiceStatus.java



* **Type:** public record
* **Description:** An immutable data object representing the complete status of a service at a point in time.
* **Fields:** \
  State state; \
  java.util.List&lt;ChannelBindingStatus> channelBindings; \



### State.java



* **Type:** public enum
* **Description:** Represents the lifecycle state of a service.
* **Values:** RUNNING, PAUSED, STOPPED.


### ChannelBindingStatus.java



* **Type:** public record
* **Description:** An immutable data object describing the status of a single connection between a service and a channel.
* **Fields:** \
  String channelName; \
  Direction direction; \
  BindingState state; \
  double messagesPerSecond; \



### **Direction.java**



* **Type:** public enum
* **Values:** INPUT, OUTPUT.


### **BindingState.java**



* **Type:** public enum
* **Values:** ACTIVE, WAITING.