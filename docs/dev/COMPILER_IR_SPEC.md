# Compiler Intermediate Representation (IR) Specification

## 1. Introduction

This document specifies the Intermediate Representation (IR) used by the Evochora compiler. The IR serves as a bridge between the frontend (parsing, semantic analysis) and the backend (code generation, layout) of the compiler. It is a structured representation of the program that the backend lays out, links and emits; before that, the IR rewriter lets the features rewrite it (section 6).

The IR is designed as a collection of objects, where each object represents a fundamental element of the source program, such as an instruction, a directive, or a piece of data.

---

## 2. Core IR Structure: `IrProgram`

The root of the IR is the `IrProgram` record.

-   **`programName`**: The name of the program.
-   **`items`**: The `IrItem` objects of the whole program in emission order, which the backend preserves.
-   **`debugInfo`**: What a debugger and a source view need besides the items: the text of the program's files, the classification of its tokens, the values of the computations the frontend computed, and the defines and values of the compilation's options. No backend phase reads or changes it; the emitter copies it into the artifact.

---

## 3. Basic Building Block: `IrItem`

`IrItem` is the sealed interface of everything that can appear in an `IrProgram`; it has exactly three kinds: `IrInstruction`, `IrDirective` and `IrLabelDef`. Every line or statement in the source code that has a semantic meaning is converted into items of these kinds. Each item stores `SourceInfo` to map it back to its original location in the source code for error reporting.

---

## 4. IR Item Types

### 4.1. Instructions: `IrInstruction`

This represents a processor instruction.

-   **`opcode`**: A `String` holding the instruction's name (e.g., "SETI", "ADDR").
-   **`operands`**: A list of `IrOperand` objects that are the arguments to the instruction.
-   **`synthetic`**: `true` for an instruction the compiler generated rather than one written in the source; it is carried into the debug information so that generated code can be told apart from written code.

`IrInstruction` is the one kind a feature may extend. The `proc` feature does so with `IrCallInstruction`, the instruction of a `CALL`, which carries the parameters of the call besides its `operands`: `refOperands`, `valOperands`, `lrefOperands` and `lvalOperands`, each a list of `IrOperand`.

### 4.2. Directives: `IrDirective`

This represents a compiler directive, which provides instructions to the compiler itself rather than generating machine code directly. Directives are namespaced to avoid collisions.

-   **`namespace`**: A `String` identifying the origin of the directive (e.g., "core").
-   **`name`**: A `String` for the directive's name (e.g., "org", "place").
-   **`args`**: A map from `String` argument names to `IrValue` objects.

#### Core Directives

-   `core:org`: Sets the layout origin. Its `args` map holds `components`, a `ListVal` with one value per component, `relative`, a `ListVal` of `Bool` with one entry per component: `true` where the component counts from the write cursor rather than from the base position of the enclosing module, and `backward`, a `ListVal` of `Bool` with one entry per component: `true` where the marker was `@-` and the component, as written, counts backwards. Each component is a `Literal`, a `Str` (a qualified name) or an `Expression`, which the layout computes at the directive; each is a number, except that a directive of one component may give the whole origin as a vector, counted from the base position (or, marked, from the cursor), or as a position, which becomes the cursor as it is.
-   `core:dir`: Sets the layout direction. Either `direction`, a `Literal`, `Str` or `Expression` whose value is the vector, or a rotation of the direction in effect: `forward` (`Bool`) and the two axes `axisA` and `axisB` (`Literal` numbers) spanning the plane it turns in.
-   `core:place`: Places a molecule at one or more locations. Its `args` map contains the molecule as `value`, a typed value given as a `Literal`, a `Str` or an `Expression`, and the locations as `placements`: a `ListVal` of placements, each a `ListVal` of components, each component a `MapVal` whose `kind` (`Str`) names its form and whose other entries are its operands, each a `Literal`, a `Str` or an `Expression`: `single` with `value`, `range` with `start` and `end`, `stepped` with `start`, `step` and `end`, `wildcard` with nothing else. Every operand is a number, except that a placement of one `single` component may give the whole place as a vector, counted from the base position of the enclosing module, or as a position, a place of the program.
-   `core:push_ctx` / `core:pop_ctx`: Internal directives injected by the preprocessor around an included file's content. These instruct the layout engine to push and pop the current layout context (base position and direction vector), enabling relative addressing and context restoration.
-   `core:proc_enter` / `core:proc_exit`: Mark the boundaries of a procedure. Both carry the same `args`: `name` (`Str`, the qualified name), `exported` (`Bool`), the parameter names as `refParams`, `valParams`, `lrefParams` and `lvalParams` (each a `ListVal` of `Str`, empty when the procedure has none of that kind), and `lrefArity` and `lvalArity` (`Literal` numbers).

#### Feature Directives

A feature emits directives in its own namespace:

-   `constdir:const_value`: A constant. `name` (`Str`) is its qualified name, `value` an `Expression` when the constant's value is a computation, otherwise a `Str` holding the operand as written.
-   `importdir:value`: A value a module requires. `name` (`Str`) is its qualified name, `value` as for `const_value`.
-   `importdir:bound_value`: Emitted at an import line for a `USING VALUE` binding that is a computation. `name` (`Str`) is the qualified name of the requirement it binds, `value` the `Expression`.
-   `reg:reg_alias`: A register alias. `name` (`Str`) is the alias's qualified name, `register` (`Str`) the register it stands for.

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

## 6. Rewriting

After the IR is generated, the IR rewriter hands the whole item list to every rewrite rule the features registered, in registration order; the rewriter itself knows no rule. The `proc` feature registers two. One inserts the instructions that pass the parameters around every `CALL` that has parameters, and turns a condition that guards a `CALL` into a jump around it, so that the condition skips the whole call. The other inserts, after every `core:proc_enter`, the instructions that take the parameters into the procedure's registers, before every `RET` of the procedure the instructions that hand the `REF` and `LREF` parameters back, and turns a condition that guards a `RET` into a jump around both. The instructions they insert are marked `synthetic`. Layout, linking and emission work on the rewritten IR.

## 7. Example IR Representation

Consider the following assembly code snippet:

```assembly
EXPORT .PROC MY_FUNC REF A
  RET
.ENDPROC

start:
  CALL MY_FUNC REF %DR0
```

Generated from it, before rewriting, the IR is an `IrProgram` containing a sequence of `IrItem`s like this:

1.  An `IrLabelDef` with `name` = `"MY_FUNC"`.
2.  An `IrDirective` (`core:proc_enter`) with `name`="MY_FUNC", `exported`=true, `refParams`=`["A"]`, empty `valParams`, `lrefParams` and `lvalParams`, and `lrefArity`=0, `lvalArity`=0.
3.  An `IrInstruction` with `opcode`="RET".
4.  An `IrDirective` (`core:proc_exit`) with the same arguments as `core:proc_enter`.
5.  An `IrLabelDef` with `name` = `"start"`.
6.  An `IrCallInstruction`, the `IrInstruction` of the `proc` feature, with `opcode`="CALL", `operands`=`[IrLabelRef{labelName="MY_FUNC"}]` and `refOperands`=`[IrReg{name="%DR0"}]`; its `valOperands`, `lrefOperands` and `lvalOperands` are empty.
