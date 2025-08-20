# Compiler Intermediate Representation (IR) Specification

## 1. Introduction

This document specifies the Intermediate Representation (IR) used by the Evochora compiler. The IR serves as a bridge between the frontend (parsing, semantic analysis) and the backend (code generation, layout) of the compiler. It's a structured, language-agnostic representation of the source code that facilitates optimization and final code emission.

The IR is designed as a collection of objects, where each object represents a fundamental element of the source program, such as an instruction, a directive, or a piece of data.

---

## 2. Core IR Structure: `IrProgram`

The root of the IR is the `IrProgram` object. It acts as a container for a linear sequence of `IrItem` objects, representing the entire compiled source file.

-   **`IrProgram`**: A list of `IrItem` objects that constitutes the whole program.

---

## 3. Basic Building Block: `IrItem`

`IrItem` is the abstract base class for all elements that can appear in an `IrProgram`. Every line or statement in the source code that has a semantic meaning is converted into a specific type of `IrItem`. Each `IrItem` also stores `SourceInfo` to map it back to its original location in the source code for error reporting.

---

## 4. IR Item Types

### 4.1. Instructions: `IrInstruction`

This represents a processor instruction.

-   **`opcode`**: A `String` holding the instruction's name (e.g., "SETI", "ADDR").
-   **`operands`**: A list of `IrOperand` objects that are the arguments to the instruction.

### 4.2. Directives: `IrDirective`

This represents a compiler directive, which provides instructions to the compiler itself rather than generating machine code directly. Directives are namespaced to avoid collisions.

-   **`namespace`**: A `String` identifying the origin of the directive (e.g., "core").
-   **`name`**: A `String` for the directive's name (e.g., "org", "place").
-   **`args`**: A map from `String` argument names to `IrValue` objects.

#### Core Directives

-   `core:org`: Sets the layout origin.
-   `core:dir`: Sets the layout direction.
-   `core:place`: Places a molecule at a specific location.
-   `core:scope_enter` / `core:scope_exit`: Mark the boundaries of a named scope.
-   `core:proc_enter` / `core:proc_exit`: Mark the boundaries of a procedure, carrying metadata like arity and export status.
-   `core:call_with`: Precedes a `CALL` instruction to specify the actual register arguments, enabling the backend to inject parameter marshalling code.

### 4.3. Label Definitions: `IrLabelDef`

This represents the definition of a label within the code.

-   **`name`**: A `String` containing the name of the label. This is used as a target for jumps and other control flow instructions.

---

## 5. Operands and Values

Operands and values are the data components used by instructions and directives. They are represented by a hierarchy of classes.

### 5.1. `IrOperand`

This is the base interface for all instruction operands.

### 5.2. `IrValue`

This is a sealed interface for typed values used in directive arguments. It allows for structured, type-safe data to be passed to backend phases.

### 5.3. Concrete Operand/Value Types

-   **`IrReg`**: Represents a machine register.
    -   **`name`**: The `String` name of the register (e.g., "%DR0", "%PR1").

-   **`IrImm`**: Represents an immediate (literal) numeric value without an explicit type.
    -   **`value`**: A `long` holding the numeric value.

-   **`IrTypedImm`**: Represents a typed immediate value, where a type is associated with a number.
    -   **`typeName`**: A `String` for the type's name (e.g., "DATA").
    -   **`value`**: A `long` for the numeric value.

-   **`IrVec`**: Represents a vector of numeric values.
    -   **`components`**: An array of `int` values for the components of the vector.

-   **`IrLabelRef`**: Represents a reference to a previously defined label. This is used as an operand for control flow instructions.
    -   **`labelName`**: The `String` name of the label being referenced.

-   **`IrValue` Subtypes**:
    -   `Int64(long)`
    -   `Str(String)`
    -   `Bool(boolean)`
    -   `Vector(int[])`
    -   `ListVal(List<IrValue>)`
    -   `MapVal(Map<String, IrValue>)`

---

## 6. Example IR Representation

Consider the following assembly code snippet:

```assembly
.PROC MY_FUNC EXPORT WITH A
  RET
.ENDP

start:
  CALL MY_FUNC WITH %DR0
```

This would be represented in the IR as an `IrProgram` containing a sequence of `IrItem`s like this:

1.  An `IrLabelDef` with `name` = `"MY_FUNC"`.
2.  An `IrDirective` (`core:proc_enter`) with `name`="MY_FUNC", `arity`=1, `exported`=true.
3.  An `IrInstruction` with `opcode`="RET".
4.  An `IrDirective` (`core:proc_exit`) with `name`="MY_FUNC".
5.  An `IrLabelDef` with `name` = `"start"`.
6.  An `IrDirective` (`core:call_with`) with `actuals` = `[Str("%DR0")]`.
7.  An `IrInstruction` with `opcode`="CALL" and `operands`=`[IrLabelRef{labelName="MY_FUNC"}]`.
