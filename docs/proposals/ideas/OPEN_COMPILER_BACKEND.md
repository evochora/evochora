# Open Compiler Backend

**Status: IDEA — not decided.** Due when the first feature needs an IR item kind of its own.

## Where the backend stands

The IR has three item kinds, fixed in the model: `IrInstruction`, `IrLabelDef` and `IrDirective`
(`IrItem` is sealed, AGENTS.md rule "Fixed Kinds"). The backend phases — layout, linker, emitter —
switch over the three kinds exhaustively; a kind that is not handled does not compile. Features
extend the backend within those kinds: a directive gets a layout handler or a linking handler, an
instruction gets rewrite rules, linking rules and emission contributors, and `IrInstruction` is
open for subtypes that carry more than the main operands (`IrCallInstruction`).

The four compiler enhancement proposals (relative `.ORG`/`.DIR`, conditional compilation, control
flow directives, conditional branch ISA) were checked against this: none needs a fourth kind.
Everything they generate is an instruction, a label or a directive.

## The open form

The intent behind the compiler has always been that the phases are thin orchestrators and every
kind of behaviour is a feature. Applied to the backend, this means:

- `IrItem` is open. A feature may define an item kind of its own.
- Each backend phase keeps a registry of item handlers keyed by item class, the way the layout
  keeps directive handlers keyed by directive name today. A phase walks the items and dispatches
  each to the handler registered for its class; an item without a handler is an error the phase
  reports with the item's source position.
- Instruction and label become features themselves (`instruction`, `label`), and what the
  phases do with them today — assigning cells, resolving addresses, encoding cells — becomes
  their default handlers. The core phase keeps only the walk and the contexts.
- The rewrite phase, which already works on the whole item list through rules, is unchanged.

## What changes when it is taken up

- `IrItem` loses `sealed`; `OperandNode` and `IrOperand` may stay fixed unless the same feature
  needs an operand form of its own.
- The rule "Fixed Kinds" in AGENTS.md is replaced by a rule that names the item-handler
  registries as the extension point, and the architecture test that keeps the model's kinds
  records is extended to the new handler interfaces.
- The exhaustive switches in `LayoutEngine`, `Linker` and `Emitter` become registry lookups.

Until then, the sealed model is the better state: it says what the backend can do, and the
compiler refuses a kind it would silently mishandle.
