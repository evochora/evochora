# Refactoring Plan: Centralizing Machine Code Interpretation

This document outlines the plan to refactor the duplicated logic for reading and interpreting machine code found in the **Runtime** and the **DebugIndexer**. The goal is to create a single, shared component to improve maintainability and reduce errors.

---

## 1. The Problem: Duplicated Logic

Currently, the logic to translate raw machine code into a structured instruction exists in two places:

1. **In the Runtime**: The `RuntimeInstruction` class (previously `Instruction`, likely in a method like `resolveOperands`) reads code from the `Environment` to create executable operands for an `Organism`. This is the "hot path" executed at runtime.

2. **In the DebugIndexer**: This component reads machine code from a `RawTickState` to create a `DebugInstruction` for analysis and display in the debugger. It does not have access to the live `Environment` or `Organism`.

This duplication means that any change to the Instruction Set Architecture (ISA) must be implemented in both places, which is error-prone and inefficient.

---

## 2. The Solution: A Central, Two-Stage Architecture

We will introduce a central component, the `UnifiedDisassembler`. The process will be split into two distinct phases:

1. **Disassembly**: The `UnifiedDisassembler` reads machine code and translates it into a neutral, core data structure.

2. **Interpretation**: The respective "client" (Runtime or Debugger) takes this core data and transforms it into its specific, required format.

### Phase 1: Abstracting Memory Access (`IMemoryReader`)

First, we decouple the reading logic from the data source.

#### **Action:** Create a new interface.

```java
// In a shared package, e.g., org.evochora.runtime.api
public interface IMemoryReader {
    /**
     * Reads a Molecule at the given coordinates.
     */
    Molecule getMolecule(int[] coordinates);

    /**
     * Returns the shape/dimensions of the memory space.
     */
    int[] getShape();
}
```

#### **Action:** Create two implementations.

1. **For the Runtime:**

   ```java
   public class EnvironmentMemoryReader implements IMemoryReader {
       private final Environment environment;
   
       public EnvironmentMemoryReader(Environment environment) {
           this.environment = environment;
       }
   
       // ... Implement interface methods ...
   }
   ```

2. **For the DebugIndexer:**

   ```java
   public class RawTickStateMemoryReader implements IMemoryReader {
       // Internal map to speed up cell access
       private final Map<List<Integer>, Integer> cellData;
       private final int[] shape;
   
       public RawTickStateMemoryReader(RawTickState rawTickState) {
           // ... Constructor logic to convert List<RawCellState> into the map ...
       }
   
       // ... Implement interface methods ...
   }
   ```

### Phase 2: The Central `UnifiedDisassembler`

This is the core of the refactoring. It only knows the `IMemoryReader` interface and returns a neutral data structure.

#### **Action:** Create the neutral data classes.

```java
// The new, neutral representation of an instruction.
// Formerly RawInstructionData.
public class Instruction {
    private final InstructionSignature signature;
    private final List<InstructionArgument> arguments;

    // Constructor, Getters...
}

// Represents a single, raw argument. Formerly RawArgument.
public class InstructionArgument {
    private final ArgumentType type; // e.g., REGISTER, POINTER, LITERAL
    private final Object value;      // e.g., an Integer index, a coordinate array

    // Constructor, Getters...
}
```

#### **Action:** Implement the `UnifiedDisassembler`.

```java
public class UnifiedDisassembler {

    public Instruction disassemble(IMemoryReader memory, int[] instructionPointer) {
        // 1. Read the opcode from the instructionPointer's location using the memory reader.
        Molecule opcodeMolecule = memory.getMolecule(instructionPointer);

        // 2. Get the corresponding InstructionSignature from the ISA.
        InstructionSignature signature = Isa.getSignature(opcodeMolecule.getValue());

        // 3. Read the arguments according to the signature.
        List<InstructionArgument> rawArguments = new ArrayList<>();
        int[] currentPosition = instructionPointer.clone();

        for (ArgumentSignature argSig : signature.getArgumentSignatures()) {
            // Advance the read pointer to the next position
            // Read the argument using the memory reader
            // Add a new InstructionArgument to the list
        }

        // 4. Return the complete, neutral Instruction object.
        return new Instruction(signature, rawArguments);
    }
}
```

### Phase 3: Integration into Runtime and DebugIndexer

Now, both components will use the new, central logic.

#### **Action:** Refactor the DebugIndexer.

The debugger performs a simple transformation for display purposes.

```java
// Inside the DebugIndexer
public DebugInstruction createDebugInstruction(RawTickState state, int[] ip) {
    // 1. Create the appropriate reader
    IMemoryReader memoryReader = new RawTickStateMemoryReader(state);

    // 2. Use the central disassembler
    UnifiedDisassembler disassembler = new UnifiedDisassembler();
    Instruction neutralInstruction = disassembler.disassemble(memoryReader, ip);

    // 3. Transform the neutral data into the display format
    String instructionName = neutralInstruction.getSignature().getName();
    List<DisassembledArgument> displayArgs = neutralInstruction.getArguments().stream()
        .map(this::formatArgumentForDisplay) // A method to format the argument as a string
        .collect(Collectors.toList());

    return new DebugInstruction(instructionName, displayArgs);
}
```

#### **Action:** Refactor the Runtime.

The runtime transforms the neutral data into executable objects. The logic from the old `resolveOperands` method will be replaced by this new process.

```java
// In a factory or as a static method for RuntimeInstruction
public static RuntimeInstruction create(Environment environment, Organism organism, int[] ip) {
    // 1. Create the appropriate reader
    IMemoryReader memoryReader = new EnvironmentMemoryReader(environment);

    // 2. Use the central disassembler
    UnifiedDisassembler disassembler = new UnifiedDisassembler();
    Instruction neutralInstruction = disassembler.disassemble(memoryReader, ip);

    // 3. Transform the neutral data into executable operands (The "Interpretation" phase)
    List<Operand> executableOperands = new ArrayList<>();
    for (InstructionArgument arg : neutralInstruction.getArguments()) {
        executableOperands.add(createOperandFromArgument(arg, organism, environment));
    }

    return new RuntimeInstruction(neutralInstruction.getSignature(), executableOperands);
}

private static Operand createOperandFromArgument(InstructionArgument arg, Organism org, Environment env) {
    // THIS is where the runtime-specific logic now lives:
    // - if arg.getType() == REGISTER, create a RegisterOperand that accesses the organism.
    // - if arg.getType() == POINTER, create a PointerOperand that accesses the environment.
    // ...etc.
}
```

---

## 4. Advantages of the New Architecture

* **Single Source of Truth**: The complex logic for reading the ISA (which opcode has how many arguments of which type) exists only in `UnifiedDisassembler`.

* **Clear Responsibilities**: The disassembler *reads*, the runtime *interprets for execution*, and the debugger *interprets for display*.

* **Decoupling**: The runtime and the debugger are now completely decoupled from each other.

* **Improved Maintainability**: Changes to the ISA only require modifications to the `UnifiedDisassembler` and potentially the neutral `Instruction` class.

---

## 5. Step-by-Step Action Plan

1.  [ ] **Phase 1**: Create the `IMemoryReader` interface and its two implementations: `EnvironmentMemoryReader` and `RawTickStateMemoryReader`.

2.  [ ] **Phase 2**: Create the `Instruction` and `InstructionArgument` data classes.

3.  [ ] **Phase 2**: Implement the `UnifiedDisassembler` class with the central reading logic.

4.  [ ] **Rename**: Rename the existing `DisassembledInstruction` class to `DebugInstruction`.

5.  [ ] **Rename**: Rename the existing `runtime.isa.Instruction` class to `RuntimeInstruction` throughout the codebase.

6.  [ ] **Phase 3**: Refactor the **DebugIndexer** to use the `UnifiedDisassembler`. Remove the old, duplicated logic.

7.  [ ] **Phase 3**: Refactor the **Runtime** (`RuntimeInstruction`) to use the `UnifiedDisassembler`. Remove the old operand resolution logic.

8.  [ ] **Cleanup**: Adapt existing tests and create new ones for the centralized logic to ensure correctness.
