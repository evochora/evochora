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

-   `core:org`: Sets the layout origin. Its `args` map holds `components`, a `ListVal` with one value per component, and `relative`, a `ListVal` of `Bool` with one entry per component: `true` where the component counts from the write cursor rather than from the base position of the enclosing module. Each component is a `Literal`, a `Str` (a qualified name) or an `Expression`, which the layout computes at the directive; each is a number, except that a directive of one component may give the whole origin as a vector, counted from the base position (or, marked, from the cursor), or as a position, which becomes the cursor as it is.
-   `core:dir`: Sets the layout direction. Either `direction`, a `Literal`, `Str` or `Expression` whose value is the vector, or a rotation of the direction in effect: `forward` (`Bool`) and the two axes `axisA` and `axisB` (`Literal` numbers) spanning the plane it turns in.
-   `core:place`: Places a molecule at one or more locations. Its `args` map contains the typed literal as `value` and the locations as `placements`: a `ListVal` of placements, each a `ListVal` of components, each component a `MapVal` whose `kind` (`Str`) names its form and whose other entries are its operands, each a `Literal`, a `Str` or an `Expression`: `single` with `value`, `range` with `start` and `end`, `stepped` with `start`, `step` and `end`, `wildcard` with nothing else. Every operand is a number, except that a placement of one `single` component may give the whole place as a vector, counted from the base position of the enclosing module, or as a position, a place of the program.
-   `core:push_ctx` / `core:pop_ctx`: Internal directives injected by the preprocessor around an included file's content. These instruct the layout engine to push and pop the current layout context (base position and direction vector), enabling relative addressing and context restoration.
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

-   **`IrLiteral`**: Represents a literal operand, carried as one `Value` of `model/expression`: a `Number` (a `long`), a `Typed` value (a type name such as "DATA" and a `long`), or a `Vector` (a list of `long` components). The layout checks that a vector argument of an instruction has one component per dimension of the world, and the emitter checks a number against the range of a cell where it encodes it.

-   **`IrLabelRef`**: Represents a reference to a previously defined label. This is used as an operand for control flow instructions.
    -   **`labelName`**: The `String` name of the label being referenced.

-   **`IrValue` Subtypes**:
    -   `Literal(Value)`: a number, a typed value or a vector, as `IrLiteral` carries one
    -   `Expression(Expression)`: a computation only the layout can compute, every name in it qualified, every node of it carrying where it begins and ends in the source; `const_value` carries one for a constant whose value is such a computation, and `core:org`, `core:dir` and `core:place` for an argument that needs the cursor or the origin of the line. The layout computes it where the directive stands: a `const_value` (`constdir`) and an `importdir` `value` record the result in the layout's named values under the directive's `name`, and an `importdir` `bound_value`, emitted at an import line for a `USING VALUE` binding that is a computation, records it under the qualified name of the requirement it binds; a `value` whose expression is only the requirement's own name records nothing, because the import line recorded the value already. The emitter hands the named values to the emission contributors, which write `constantValues` from them
    -   `Str(String)`
    -   `Bool(boolean)`
    -   `ListVal(List<IrValue>)`
    -   `MapVal(Map<String, IrValue>)`

---

## 6. Example IR Representation

Consider the following assembly code snippet:

```assembly
EXPORT .PROC MY_FUNC WITH A
  RET
.ENDPROC

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
