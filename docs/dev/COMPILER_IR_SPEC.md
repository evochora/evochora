# Compiler IR Specification

The IR (intermediate representation) is the form of a program between the frontend and the backend of the EvoASM compiler. The IR generator produces it from the syntax tree; the rewriting, the layout, the linker and the emitter work on it and on nothing else. This document specifies what the IR contains. Where it sits among the compiler's phases is described in [Architecture](ARCHITECTURE.md#2-the-compiler). The types live in `compiler/model/ir`.

- [The Program](#the-program)
- [Items](#items)
- [Operands](#operands)
- [Directive Values](#directive-values)
- [Layout Terms](#layout-terms)
- [Directives](#directives)
- [Rewriting](#rewriting)
- [Example](#example)

## The Program

An `IrProgram` has three parts:

- `programName`: the name of the program.
- `items`: the items of the whole program in emission order. The backend keeps this order.
- `debugInfo`: what a debugger and a source view need besides the items. No phase of the backend reads or changes it, and the emitter copies it into the artifact. It holds:
  - the text of the program's files,
  - the classification of its tokens,
  - the values of the computations the frontend computed,
  - the defines and values of the compilation's options.

## Items

`IrItem` is a sealed interface with exactly three kinds. Every item carries a `SourceInfo`, the place in the source it comes from.

**`IrInstruction`** is an instruction of the organisms' instruction set.

- `opcode`: the name of the instruction, such as `SETI` or `ADDR`.
- `operands`: its operands, a list of `IrOperand`.
- `synthetic`: `true` for an instruction the compiler generated rather than one written in the source. The emitter writes it into the artifact with the instruction, so that generated code can be told apart from written code.

`IrInstruction` is the one kind a feature may extend. The `proc` feature does so with `IrCallInstruction`, the instruction of a `CALL`. Besides its `operands`, it carries the parameters of the call in four lists of `IrOperand`: `refOperands`, `valOperands`, `lrefOperands` and `lvalOperands`.

**`IrLabelDef`** defines a label. It occupies one cell and is a target of jumps.

- `name`: the name of the label, qualified for a label written in the source.

**`IrDirective`** tells the backend something about the program. It takes no place in the program's code. A feature registers the handlers that act on it in the backend phases.

- `namespace`: the namespace of the directive, such as `core`.
- `name`: its name within the namespace, such as `org`.
- `args`: its arguments, a map from names to [directive values](#directive-values).

## Operands

`IrOperand` is a sealed interface with exactly three forms:

- `IrReg`: a register, with its `name`, such as `%DR0`.
- `IrLiteral`: a literal, carried as one `Value` of `compiler/model/expression`. A `Value` is a number, a typed value, a vector, a molecule type or a position.
- `IrLabelRef`: a reference to a label, with the label's qualified name as `labelName`. The label may be defined before or after the reference.

## Directive Values

`IrValue` is a sealed interface with exactly six forms:

- `Literal`: a `Value`, as an `IrLiteral` carries one.
- `Expression`: a computation that only the layout can compute. Every name in it is qualified.
- `Str`: a string, such as a qualified name.
- `Bool`: a boolean.
- `ListVal`: a list of `IrValue`.
- `MapVal`: a map from names to `IrValue`.

## Layout Terms

The directives of the layout use these terms, which [Layout Control](../EVOASM_SPEC.md#layout-control) and [Positions](../EVOASM_SPEC.md#positions) in the Assembly Specification explain for the language:

- **Cursor**: the cell the next code is placed in, the one after the cell placed last.
- **Direction**: the vector along which the layout places the code.
- **Base position**: the origin of the file being laid out. In the main file it is the origin of the program; in a file that `.IMPORT` or `.SOURCE` includes, it is the cursor where the include stands.
- **Marker**: `@+` or `@-` before a component of `.ORG`. A marked component counts from the cursor instead of from the base position, and `@-` counts backwards.
- **Position**: a place of the program, counted from the origin of the program.

## Directives

The namespace does not name the feature that emits a directive: the features `org`, `dir`, `place`, `ctx` and `proc` emit theirs in the namespace `core`. Each directive is acted on by the feature that emits it.

**`core:org`** moves the cursor. Its arguments are three lists with one entry per component:

- `components` (`ListVal`): the components, each a `Literal`, a `Str` holding a qualified name or an `Expression`.
- `relative` (`ListVal` of `Bool`): `true` where the component is marked, and so counts from the cursor.
- `backward` (`ListVal` of `Bool`): `true` where the marker is `@-`.

Each component is a number. A directive with a single component may instead give the whole new cursor:

- A vector counts from the base position, or from the cursor where it is marked, and backwards after `@-`.
- A position becomes the cursor unchanged.

**`core:dir`** sets the direction of the layout. It carries one of two forms:

- `direction`: the direction vector, as a `Literal`, a `Str` holding a qualified name or an `Expression`.
- A rotation of the direction in effect: `forward` (`Bool`) and the two axes `axisA` and `axisB` (`Literal` numbers) that span the plane it turns in.

**`core:place`** places a molecule at one or more locations.

- `value`: the molecule, as a `Literal`, a `Str` holding a qualified name or an `Expression`.
- `placements` (`ListVal`): one entry per placement. A placement is a `ListVal` with one component per axis.

Each component is a `MapVal`. Its entry `kind` (`Str`) names the form of the component, and its other entries are the operands of that form:

| `kind` | Operands |
|---|---|
| `single` | `value` |
| `range` | `start`, `end` |
| `stepped` | `start`, `step`, `end` |
| `wildcard` | none |

Each operand is a number, given as a `Literal`, a `Str` holding a qualified name or an `Expression`. A placement with a single `single` component may instead give the whole location:

- A vector counts from the base position.
- A position is taken unchanged.

**`core:push_ctx`** and **`core:pop_ctx`** enclose the content of a file that `.SOURCE` or `.IMPORT` includes. `push_ctx` saves the base position and the direction of the layout and makes the cursor the new base position; `pop_ctx` restores both. Neither has arguments.

**`core:proc_enter`** and **`core:proc_exit`** mark the start and the end of a procedure. Both carry the same arguments:

- `name` (`Str`): the qualified name of the procedure.
- `exported` (`Bool`): whether the procedure is exported.
- `refParams`, `valParams`, `lrefParams` and `lvalParams` (each a `ListVal` of `Str`): the names of the parameters of each kind, empty when the procedure has none of that kind.
- `lrefArity` and `lvalArity` (`Literal` numbers): the number of `LREF` and of `LVAL` parameters.

The four directives that follow produce no code themselves. They are used in two ways:

- Where the value of a constant or of a required value is an `Expression`, the layout computes it at the directive and records it under the qualified name. A later computation that names it gets that value.
- The artifact carries the values and the register aliases, so that a debugger and the visualizer can show them where they are used.

**`constdir:const_value`** defines a constant.

- `name` (`Str`): its qualified name.
- `value`: an `Expression` when the value is a computation, otherwise a `Str` holding what the frontend resolved it to: a literal as text, or the qualified name of a label.

**`importdir:value`** is a value that a module requires. Its arguments are those of `const_value`. Where its `Expression` is only the requirement's own qualified name, the layout records nothing at it: the import line bound the value, and its `importdir:bound_value` recorded it already.

**`importdir:bound_value`** stands at an import line for each `USING VALUE` binding that is a computation.

- `name` (`Str`): the qualified name of the requirement it binds.
- `value` (`Expression`): the computation.

**`reg:reg_alias`** defines a register alias.

- `name` (`Str`): the qualified name of the alias.
- `register` (`Str`): the register it stands for.

## Rewriting

Between the IR generator and the layout, the IR rewriter applies every rewrite rule the features registered, in the order they registered them, each to the items the one before returned. The rewriter knows no rule of its own. The `proc` feature registers two:

- The first inserts, after every `core:proc_enter`, the instructions that take the parameters into the procedure's registers. Before every `RET` of the procedure, it inserts the instructions that hand the `REF` and `LREF` parameters back. A conditional instruction directly before a `RET` is replaced by its negation and a jump, which skips the handing back and the `RET`.
- The second inserts, around every `CALL` that has parameters, the instructions that pass them. A conditional instruction directly before a `CALL` is replaced the same way, and the jump skips the whole call.

Every instruction a rule inserts is `synthetic`. The target of each of these jumps is an `IrLabelDef` the rule inserts after what the jump skips, with a generated name such as `_safe_ret_0` or `_safe_call_0`.

## Example

This program:

```
EXPORT .PROC MY_FUNC REF A
  RET
.ENDPROC

.ORG 0|@+2
START:
  CALL MY_FUNC REF %DR0
```

becomes these items:

1. `IrLabelDef` with `name` `MY_FUNC`.
2. `IrDirective` `core:proc_enter` with `name` `MY_FUNC`, `exported` `true`, `refParams` `[A]`, empty `valParams`, `lrefParams` and `lvalParams`, and `lrefArity` and `lvalArity` `0`.
3. `IrInstruction` with `opcode` `RET`.
4. `IrDirective` `core:proc_exit` with the same arguments as `core:proc_enter`.
5. `IrDirective` `core:org` with `components` `[0, 2]` (`Literal` numbers), `relative` `[false, true]` and `backward` `[false, false]`: the next code starts in column 0, counted from the base position, two rows below the cursor.
6. `IrLabelDef` with `name` `START`.
7. `IrCallInstruction` with `opcode` `CALL`, `operands` `[IrLabelRef MY_FUNC]` and `refOperands` `[IrReg %DR0]`; its other three lists are empty.

The rewriting inserts synthetic instructions for the parameter `A`, which passes through the data stack:

1. `IrLabelDef` `MY_FUNC`.
2. `core:proc_enter`.
3. `POP %FDR0`, synthetic: the procedure takes the parameter into its first register.
4. `PUSH %FDR0`, synthetic: it hands the parameter back before it returns.
5. `RET`.
6. `core:proc_exit`.
7. `core:org`.
8. `IrLabelDef` `START`.
9. `PUSH %DR0`, synthetic: the caller passes the parameter.
10. `CALL MY_FUNC`, the same `IrCallInstruction`.
11. `POP %DR0`, synthetic: the caller takes the parameter back.
