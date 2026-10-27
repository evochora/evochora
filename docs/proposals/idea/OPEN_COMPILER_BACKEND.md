# Open Compiler Backend

**Status: IDEA — not decided.** The backend part is due when the first feature needs an IR item
kind of its own; the last section records the form the whole compiler is heading for.

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

## The whole compiler in the open form

The backend is one end of a longer line. The form the compiler is heading for, phase by phase,
is this:

- **The phases hand each other finished data formats and nothing else**: the dependency graph,
  the token stream, the AST, the IR, the artifact. A phase takes the format before it, walks it,
  dispatches every item to the handler a feature registered for it, and returns the format after
  it. The core of a phase is the walk, the dispatch and the phase context; it interprets nothing
  a feature produced.
- **Every format has a slot in which a feature keeps data of its own**, keyed by a type the
  feature owns, as the phase contexts already have (`getOrCreate`), the IR has through the
  namespace of `IrDirective`, and the AST has through the feature's node types. The artifact and
  the token map do not have it yet (issue #153 and the kinds of `TokenInfo`); the symbol table's
  `Symbol.Type` and the module system are the core concepts that would have to open last.
- **A feature reads only its own slot.** A handler of a feature in one phase finds what the
  feature's handler in an earlier phase left, and never what another feature left; what two
  features share goes through the core data of the format — a token's text and position, a
  symbol's definition, an instruction's operands. Where the slot is keyed by a feature-owned
  class, the rule is enforced by the test that forbids a feature to reference another; where it
  is keyed by a string, by a test over the registrations.
- **The core carries no feature's name**: not in a field, not in an enum member, not in a
  method on a phase context, not in a string.

Where this stands today is recorded in `docs/COMPILER_CORE_BOUNDARY.md`; the candidates listed
there are the distance left. The order in which the distance closes follows the need: the
artifact and the token map first (#153), because every feature with debug data touches them, the
backend when a feature needs an item kind, the symbol kinds and the module system when a feature
needs to define names of its own.
