# Control Flow Directives

**Status: ACCOMPLISHED — implemented on branch `feature/control-flow` (2026-10-09), in the seven steps below; see *Outcome* at the end.**
Builds on [QUALIFIED_NAMES](QUALIFIED_NAMES.md) and [BLOCK_MECHANISM](BLOCK_MECHANISM.md).

A control block is a third kind of level next to the module and the procedure. It gives a stretch
of code a name and named places inside it, so that the jumps a programmer writes today with
hand-named labels can be written with labels the block provides, reached like every other name:
by the plain name inside the block, by the path from outside. The compiler generates no
instruction for a block: every jump, every conditional and every empty cell stays the
programmer's, so that the padding and the redundancy that make a program evolvable
(`docs/EVOASM_GUIDELINES.md`) stay under the programmer's control.

## Problem

All control flow in an Evochora program is written by hand: a conditional, a jump, a label the
programmer invents. In the two primordials 141 of 155 conditionals are followed directly by a
`JMPI` to such a label (`assembly/primordial/`). The labels are the weak point, not the jumps:

- Every decision needs names, and the names carry the structure the reader has to reconstruct:
  which blocks are the alternatives of one decision, where a branch ends, what the fall-through
  case is. `CONTINUE_SIDEVEC_0`, `CONTINUE_SIDEVEC_L` and `CONTINUE_SIDEVEC_DONE` in
  `reproduce.evo` are one three-way decision, and nothing but the names says so.
- A forgotten jump at the end of a block runs on into the next block; a jump to a wrong but
  existing label runs the wrong block; a block copied to another place needs every label renamed,
  and a missed rename jumps into the old copy. All of these compile.
- A library of behaviours, modules that a primordial is assembled from, multiplies these
  problems: many hands, many names, code that is read and copied more often than written.

What the primordials do not need is shorter code. Every statement is followed by padding
(`NOP^4`), every unconditional jump at the end of a row is doubled, and every one of these
choices depends on the place: how much room is left on the row, whether an insertion there
should be able to disable the guard, whether the jump is a row end. Code the compiler writes
cannot make these choices, and code the compiler writes with parameters makes them for whole
constructs at once, where the programmer makes them per cell.

## Options considered

| Option | Why not chosen |
|---|---|
| Do nothing | The labels stay the weak point; the library inherits it. |
| Structured statements that generate the jumps (`.IF cond` … `.ELSE` … `.ENDIF`, `.WHILE`, `.SELECT`/`.CASE value`), as MASM, HLA and the HLASM macros do | Generated jumps carry no padding and no redundancy. Parameters for them (`PAD 4 ROOM 12 JUMPS 2`) set one value per construct, where the hand-written form sets one per cell, and then-part and else-part commonly stand on rows with different room. Rejected after working the padded form of a real decision through. |
| Statements with fixed place names (`.IF`/`.THEN`/`.ELSEIF`/`.ELSE`/`.ENDIF` with `$THEN`, `$NEXT`, `$END`) and a condition part that is code | Works, but every kind of structure needs its own word pair and its own fixed names, loops and selections included; three mechanisms where one is enough. A completion rule that adds the jumps a condition part leaves out was tried and dropped: the compiler would add jumps sometimes and not at other times. |
| Block labels under a compiler-owned prefix (`$WALK.END`), with short names resolved through a lookup-only scope or rewritten by the parser | Needed a rule of its own in name resolution, a second kind of scope in the core or a parser that rewrites identifiers; and a scope that captured the definitions inside the block hid labels, procedures and constants written there. QUALIFIED_NAMES made all of it unnecessary: a block is a level, its labels are names on that level, and the one visibility rule applies. |
| A generic block with `.WHEN` for condition parts and `.CASE` for bodies | Both words would set a label and nothing else; two words for one thing. |
| A reserved label for the block's start (`$TOP`, `$BEGIN`) | The block's own name is that label; a loop is a block that jumps to its name. |
| A short form `.IF cond` with `&&`, `||` and parentheses, next to the block | A second syntax, with operators and precedence, that generates jumps and therefore leaves no room for mutation. |
| `.AND`/`.OR` as dividers of the condition part, with the per-term jump generated | Cannot be mixed without parentheses, the generated jumps carry no padding, and the programmer would write jumps in `.IF` but not in `.AND`. The chain of guards is the same code without the rule. |
| A `switch` with a jump table in the instruction set | A new instruction and a table of label references between instructions, which the duplication plugin does not copy. Out of scope; the dispatch table of guards covers value selection. |
| A check that every condition part and every part before a divider ends with an unconditional jump | Falling through has a meaning (the next place runs), it is what the abort chain relies on, and assemblers report only what cannot be assembled. Not checked. |
| Reaching a case of a block from outside the block without `EXPORT` | Would make the block the one level whose inner names are visible from outside, against the one rule of QUALIFIED_NAMES. Entering a block in the middle is what `EXPORT` on the case declares, as for a label in a procedure. |
| Reporting a jump from a procedure body to a label outside it | Issue #201, built on QUALIFIED_NAMES. |
| Allowing a name to be defined more than once, as deliberate redundancy | Done for labels, blocks and procedures together with issue #153 (comment there). Until then a block in a macro body takes its name as a parameter, like a label. |
| Stack conditionals that read without consuming | Deferred until a program needs it. |

## Solution

### Terms

A **control block** is the stretch between `.CONTROL <Block>` and `.ENDCONTROL`; it is a level
in the sense of QUALIFIED_NAMES, like a module and a procedure. A **case** is a place inside it,
set by `.CASE <Case>`. The **head** is the code from `.CONTROL` to the first `.CASE`; a **part**
is the code from one `.CASE` to the next.

### The directives

```
.CONTROL <Block>                   # the label <Block> stands here, on the enclosing level
  <code>
.CASE <Case>                       # the label <Case> stands here, on the block's level
  <code>
.ENDCONTROL                        # the label END stands behind it, on the block's level
```

- `.CONTROL <Block>` opens a block and defines the name `<Block>` on the level around it, as a
  label at the block's start and as the level that holds the block's names. The name is
  mandatory, one segment, and defined once per level like every name.
- `.CASE <Case>` defines the label `<Case>` on the block's level, at its place. A block may have
  any number of cases, none included.
- `.ENDCONTROL` closes the block; the label `END` stands behind it, on the block's level. `END`
  is defined when the block opens, so a case or a label named `END` in the same block is reported
  as a second definition of the name.
- The visibility is the one rule of QUALIFIED_NAMES. Inside the block the plain names work:
  `JMPI BLOCKED`, `JMPI END`, `JFI %DR0 DATA:10 BLOCKED`; a plain name means the innermost level that
  has it. From a block nested in `WALK`, the outer block's places are `WALK.BLOCKED` and
  `WALK.END`. From outside the block, `WALK` itself is visible, a jump to it enters the block
  at its start; `WALK.BLOCKED` and `WALK.END` are visible only when the case carries `EXPORT`, as a
  label in a procedure is. From another module, `LIB.WALK.BLOCKED` when the block and the case are
  exported, like `LIB.CLAMP.DONE`.
- Everything between the directives is code. What does not jump runs on into the next place:
  from the head into the first case, from one case into the next, from the last case out of the
  block. The compiler adds no instruction and checks no jump.
- A label, a constant, a register alias or a procedure defined inside the block belongs to the
  block's level, as it belongs to a procedure when defined there; `.IMPORT` and `.REQUIRE` stand
  only at the module level and are reported inside a block as inside a procedure.
- `.ORG` and `.DIR` are statements like any other. A part that is to begin on a new row has its
  `.ORG` before the `.CASE` that begins it, so that the label stands on the new row; the same
  holds for `.CONTROL` and `.ENDCONTROL`.
- `EXPORT` before `.CONTROL` exports the block's name one level out, `EXPORT` before `.CASE`
  the case, `EXPORT` before `.ENDCONTROL` the end; the rule is the one of every name.
- The block has no rule of its own on names: its name, its cases and `END` are labels, defined
  once per level as every label is, and what #153 changes for labels changes for them. A block
  in a macro body or a `.REPEAT` body is therefore defined once per expansion, like a label in
  such a body; a macro that opens a block takes the block's name as a parameter. A macro may
  open a block that another macro closes.
- `.CONTROL` … `.ENDCONTROL` is a block of the parser's discipline (BLOCK_MECHANISM), with
  `.CASE` as its divider, as `.PROC` … `.ENDPROC` is a block: blocks nest and never overlap, an
  end closes the block opened last, a block whose structure is broken is reported and skipped
  as a whole, and a macro may open a block that another macro closes. A `.PROC` stands only at
  the module level; inside a control block it is reported, as inside a procedure.

### How the usual control structures are written

The block provides places; the structures of other languages are patterns of code between
them. The guidelines document the patterns with padding and redundancy; the specification shows
one example. Here they are written dense.

**How to write if / then / elseif / else.** The head tests the first condition. Every further
condition stands in a case of its own, directly before the case it guards. Every part that is
not the last ends by leaving the block. A new alternative is a new pair of cases, and the jump
of the condition before it points to the new condition.

```
.CONTROL ENERGY                    # if / then / elseif / else
  LTI %ER DATA:1000                # if starving
  JMPI STARVING
  JMPI TEST_RICH                   # else go on testing
.CASE STARVING                     # then
  CALL ENERGY.HARVEST
  JMPI END                         # endif
.CASE TEST_RICH                    # elseif rich
  GTI %ER DATA:50000
  JMPI RICH
  JMPI NORMAL                      # else
.CASE RICH                         # then
  CALL REPRODUCE.CONTINUE REF %ER
  JMPI END                         # endif
.CASE NORMAL                       # else
  CALL MOVE.STEP
.ENDCONTROL                        # endif
```

A condition of several terms is a chain in the same place. For *or*, every term that holds
jumps into the case; for *and*, every term that fails the whole jumps past it, and the body
follows:

```
  IFPI 1|0                         # if passable ahead
  JMPI STEP                        #    or
  IFPI 0|1                         #    passable to the right
  JMPI STEP
  JMPI BLOCKED                     # else

  IFFR %DIRVEC                     # if not foreign
  JMPI END                         #    and
  IFBI 0|0                         #    not inside the own body
  JMPI END
  CALL BUILD.CELL                  # then
```

The compiler negates nothing; the programmer writes the conditional that acts.

**How to write switch / case.** The head is a dispatch table, one test and one jump per value,
the default last; every case ends with `JMPI END`, which is `break`. Falling through to the
next case is what a case without that jump does.

```
.CONTROL BEHAVIOUR                 # switch on %MODE
  IFI %MODE DATA:0
  JMPI HARVEST                     # case 0
  IFI %MODE DATA:1
  JMPI REPRODUCE                   # case 1
  JMPI EXPLORE                     # default
.CASE HARVEST
  CALL ENERGY.HARVEST
  JMPI END                         # break
.CASE REPRODUCE
  CALL REPRODUCE.CONTINUE REF %ER
  JMPI END                         # break
.CASE EXPLORE
  CALL EXPLORE.RUN
.ENDCONTROL
```

**How to write a while loop.** The test at the top leaves the block; the jump to the block's
name at the bottom repeats it. `JMPI END` anywhere in the body is `break`, the jump to the
block's name is `continue`. For *until*, the body comes first and the test guards the jump back.

```
.CONTROL WALK                      # while the cell ahead is passable
  INPI 1|0                         # not passable?
  JMPI END                         # then the loop ends
  CALL MOVE.STEP                   # the body
  JMPI WALK                        # repeat
.ENDCONTROL

.CONTROL RETRY                     # until the step succeeded
  CALL MOVE.STEP                   # the body
  IFER                             # failed?
  JMPI RETRY                       # then once more
.ENDCONTROL
```

**How to write a for loop.** The counter is set before the block, tested at the top, and
stepped before the jump back.

```
SETI %I DATA:0                     # for I = 0
.CONTROL BUILD
  GETI %I DATA:10                  # while I < 10, written as its negation
  JMPI END
  CALL BUILD.CELL                  # the body
  INCR %I                          # I = I + 1
  JMPI BUILD                       # repeat
.ENDCONTROL
```

**How to leave an outer loop.** From a block nested in `WALK`, `JMPI END` leaves the inner
block and `JMPI WALK.END` the loop, as `break` and `break label` do in Java.

### The rule the patterns rely on

A conditional's test has three outcomes: it holds, it does not hold, or it cannot be evaluated,
because an operand names no register, a stack lacks a value, an argument cell lies beyond the
edge, or a cell test gets no vector. The instruction set settles the third as the IEEE model
does for a comparison with NaN: **a condition that cannot be tested does not hold, and its
negation holds** (`docs/ASSEMBLY_SPEC.md`, "Conditional Instructions"; accomplished in #203).
`IFI` skips, `INI` runs on, `JFI` stays, `JNI` jumps; the failure stays booked with its penalty.

The patterns need it in one place: the abort chain. `INx; JMPI END` and `JNx END` leave the block
when the test cannot be evaluated, so a body guarded by a chain never runs on an unreadable
value, and the entry chain `IFx; JMPI THEN` does not enter. Under the rule the written form and
the marshalled form of `IFx; CALL P` agree as well.

### Architecture

One feature package, `features/control`, in the slicing every feature follows, and a mirror of
`features/proc` wherever the block does what a procedure does. No change in the core: the block
is a block kind of the parser (BLOCK_MECHANISM) and a level of the symbol table
(QUALIFIED_NAMES), and both are registered, not built.

| Component | Phase | Does |
|---|---|---|
| block kind | 3, parser | `ControlFeature` registers `parserBlock(new BlockKind(Set.of(".CONTROL"), ".ENDCONTROL", Set.of(".CASE")), new ControlDirectiveHandler())`. The parser reads the block's extent before the handler runs and calls it only for a whole block; a broken block, a stray `.CASE` or `.ENDCONTROL` and `EXPORT` before a word the handler refuses are reported by the parser. |
| `ControlDirectiveHandler` | 3, parser | Implements `IParserBlockHandler`, as the `SelectHandler` of `ParserBlockTest` does: consumes `.CONTROL`, takes the name (`consume(IDENTIFIER)`) and `isExported()`, expects the newline; parses the head with `statements(block.bodyStart(), block.partEnd(first word))`, where the first word is the first divider or the closer; for every divider takes `lineOf(divider)`, the case name from its first operand, `exported` from `block.prefixed()`, and the statements from `line.next()` to `partEnd(next word)`; takes `endExported` from `block.prefixed().contains(block.closer())` and `endSourceInfo` from `tokenAt(block.closer()).source()`. Reports a `.CASE` without a name. `supportsExport(word)` is `true` for all three words. Keeps no state of its own. |
| `ControlNode`, `ControlCase`, `ControlEnd` | AST | Records. `ControlNode(name, exported, statements, cases, endExported, sourceInfo, endSourceInfo)` implements `IJumpTarget`: the block is its own head, its name is the label before its first statement. `ControlCase(name, exported, statements, sourceInfo)` implements `IJumpTarget`. `ControlEnd(sourceInfo)` implements `IJumpTarget`, carries the position of `.ENDCONTROL` and is the node of the `END` symbol; it never stands in the tree. The children of a block are its statements followed by its cases; `reconstructWithChildren` tells them apart by type. |
| `ControlSymbolCollector` | 4, pass 1 | As `ProcedureSymbolCollector`: defines `<Block>` on the current level (`Symbol.Type.LABEL`, node: the block, exported as written), reporting a second definition like a duplicate label; enters the block's scope with `enterScope(name)` and `registerNodeScope`; defines inside it `END` (node: `ControlEnd` with the end's position, exported as `.ENDCONTROL` said). Leaves the scope after the children. |
| `ControlCaseSymbolCollector` | 4, pass 1 | As `LabelSymbolCollector`: defines `<Case>` in the current scope when the walk reaches the case (`Symbol.Type.LABEL`, node: the case), so that a clash with a name written earlier in the block is reported at the later of the two, as for two labels. |
| `ControlAnalysisHandler` | 4, pass 2 | As `ProcedureAnalysisHandler`: enters the block's prebuilt scope and leaves it after the children. The instruction analysis validates the operands; `IJumpTarget` makes a block name, a case and the end valid label arguments. |
| Phases 5 and 6 | — | Nothing to register: the token map classifies a reference through its symbol, the post-processor replaces it by its path, as for every label. |
| `ControlNodeConverter`, `ControlCaseConverter` | 7, IR | As `ProcedureNodeConverter`: the block converter emits `IrLabelDef(qualifyName(<Block>))` with the block's position, calls `enterScope(<Block>)`, converts the statements, then the cases, then emits `IrLabelDef(qualifyName("END"))` with the end's position and calls `leaveScope()`. The case converter emits `IrLabelDef(qualifyName(<Case>))` with the case's position and converts the case's statements. |
| `ControlFeature` | — | Registers the block kind with its handler, the two symbol collectors, the analysis handler and the two converters; takes its place in `StandardFeatures` before `InstructionFeature`. |

What the core already provides and the block only uses: the block discipline (`parserBlock`,
`IParserBlockHandler`, `statements`, `lineOf`, `tokenAt`, the `EXPORT` flags of the block's
words), the level (`enterScope` with a segment, `registerNodeScope`), the path as identity
(`qualifyName`), the descent of a path into a scope a symbol opened, the visibility rule with
`EXPORT` on every segment, the one-segment rule on names, the reporting of `.IMPORT`/`.REQUIRE`
and `.PROC` inside a level.

Core comments, in step 2: the class comment of `SymbolTable` ("one scope per procedure", and the
sentence that a name an enclosing scope holds is reported when the table freezes, which
QUALIFIED_NAMES withdrew), the comment of `Scope` ("procedure-local or module-global"), the two
mentions of `ProcedureSymbolCollector` at the node-scope map, and the class comment of
`ScopeTracker` speak of levels a node opens. The withdrawn sentence is a leftover and goes in a
commit of its own.

Nothing changes in the layout, the linker, the emitter, the artifact, the visualizer or the
runtime: block labels are labels with paths, and the rule the patterns rely on is in the machine.

### Documentation

- `docs/ASSEMBLY_SPEC.md`: a subsection "Control blocks" in section 7, in the form and length
  of `.PROC`'s, with one example, referring to "Qualified names" for visibility; under "Blocks"
  that `.CONTROL` … `.ENDCONTROL` is a block as `.PROC` is, with `.CASE` as its divider, whose
  words a macro may open and another macro close.
- `docs/EVOASM_GUIDELINES.md`: a section "Control blocks" with the patterns above, each with
  padding and redundancy, and the row rule (`.ORG` before the directive).
- `.claude/skills/evoasm/SKILL.md`: the block next to the instructions.
- `docs/COMPILER_CORE_BOUNDARY.md`: the feature row (`control`: the directives, the block kind,
  the level, the labels; parser, symbol collection, analysis, IR conversion).
- `docs/proposals/README.md`: this document's row.

Every documentation change takes the form, depth and vocabulary of the sections around it,
describes what the programmer writes and what then holds, and uses no term the document has not
introduced at that place (AGENTS.md, "User documentation").

## Consequences for programs and experiments

- Existing programs compile unchanged; no program uses the three directives.
- The primordials stay as they are. A primordial written with blocks is a work of its own, as
  with the conditional jumps.
- The reference program of `CompilerOutputEquivalenceTest` is extended by a section that uses
  the blocks in every form the specification names; its artifact is regenerated once,
  deliberately, and the pull request says so after a diff has shown that only the new cells
  were added.

## Pitfalls

- **Falling through is silent.** A case that does not end with a jump runs the next case, as a
  hand-written block runs the next block. The specification says what falls through runs on;
  the guidelines show every pattern with its closing jump.
- **`END` means the innermost block.** From a case of `BEHAVIOUR` nested in `WALK`, `JMPI END`
  leaves `BEHAVIOUR`, not the loop; the loop is left by `JMPI WALK.END`. Java's `break` and
  `break label` are the same two forms.
- **Entering a block in the middle** needs `EXPORT` on the case, as entering a procedure at a
  label does; the dispatch table of a block stands in its head, so the patterns never need it.
- **Macros and `.REPEAT`.** A block in a body expanded twice is a second definition of its
  name, like a label; the name has to be a macro parameter until #153 allows duplicates.
- **Terms with stack operands in a chain** consume their values on the path that evaluates
  them; `DUP` before the term is the programmer's means. Documented, not checked.
- **`IFER` in a chain** refers to the term before it, because terms execute in order and a
  skipped jump costs no tick; `IFER` as the first term refers to the instruction before the block.
- **The source view** lists under a directive line only machine instructions, so the three
  directives show their line and nothing more, as a label line does; the label cell itself is in
  the grid, and a definition gets no annotation, a reference does.

## Implementation

Strictly in this order; every step leaves the build green, and every step has a test that
would fail if the step were wrong. The level and the resolution come first, because a problem
there changes the design.

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | Parser handler and AST | `features/control/`: `ControlNode`, `ControlCase`, `ControlDirectiveHandler`, `ControlFeature` (block kind and handler only), `StandardFeatures`; new `ControlDirectiveTest` in `compiler/features/control`, modelled on the `SelectHandler` cases of `ParserBlockTest` | `gw test --tests '*ControlDirective*' --tests '*ParserBlock*' --tests '*StandardFeatures*' --tests '*CompilerArchitectureRules*' --tests '*CompilerOutputEquivalence*'` | a block with head, three cases and nesting parses into the node tree, the end with its own position; `EXPORT` flags land on block, case and end; a missing block name and a `.CASE` without a name are reported; `.ENDPROC` inside an open `.CONTROL` is reported once, with both places; `.CASE` outside a block and `.ENDCONTROL` without a block are reported where they stand; a block open at the end of the input is reported at its `.CONTROL`; a label alone on its line before `.CASE` or `.ENDCONTROL` leaves them in place; the architecture test accepts the feature; the reference artifact is unchanged |
| 2 | The block as a level | `ControlSymbolCollector`, `ControlCaseSymbolCollector`, `ControlEnd`, `ControlAnalysisHandler`, `ControlFeature` (registrations); the core comments of `SymbolTable` and `ScopeTracker`, the withdrawn shadowing sentence in a commit of its own; new `ControlLevelTest` in `compiler/features/control` | `gw test --tests '*ControlLevel*' --tests '*SemanticAnalyzer*' --tests '*QualifiedNames*'` | `JMPI END` and `JMPI BLOCKED` inside `WALK` resolve to `WALK.END` and `WALK.BLOCKED`; `JMPI WALK` from before and after the block resolves; `JMPI WALK.BLOCKED` from after the block is reported as not marked EXPORT and resolves with `EXPORT .CASE`; `END` in a nested block is the inner end, `WALK.END` the outer; `JFI %DR0 DATA:1 BLOCKED` passes the operand check; a label `END:` in the block is reported at the label with the `.ENDCONTROL` line named, a label `BLOCKED:` in the head before `.CASE BLOCKED` is reported at the `.CASE`, a second block `WALK` on one level is reported; a label and a constant defined in the block are visible inside it and reached from outside as `WALK.L` only with `EXPORT`; `.IMPORT` and `.PROC` inside a block are reported |
| 3 | IR and the equivalence to hand-written code | `ControlNodeConverter`, `ControlCaseConverter`, `ControlFeature` (registration); new `ControlBlockCompileTest` in `compiler/features/control` | `gw test --tests '*ControlBlockCompile*' --tests '*CompilerOutputEquivalence*'` | for each pattern of the solution, the block program and its hand-written twin with ordinary labels compile to the same cells at the same coordinates, label and label-reference cells compared by position and type, not value; the artifact's label maps carry the paths `WALK`, `WALK.BLOCKED`, `WALK.END`; the reference artifact is unchanged |
| 4 | EXPORT across modules | `ControlBlockCompileTest` (two modules, `@TempDir`, `@Tag("integration")`, modelled on `ReExportedImportResolutionTest`) | `gw test --tests '*ControlBlockCompile*'` | `LIB.WALK` and `LIB.WALK.BLOCKED` are reachable from an importer with `EXPORT .CONTROL` and `EXPORT .CASE`; without one of them the missing segment is reported; a block inside a procedure is reached as `LIB.PROC.WALK` with the procedure exported |
| 5 | The reference program | `src/test/resources/org/evochora/compiler/reference/main.evo` and `lib/util.evo` (a section in its own `.ORG` region after the existing code, with guard, chain, selection, loop, nesting, `EXPORT .CONTROL` and `EXPORT .CASE`, a macro with the block name as parameter, `.ORG` before a `.CASE`, padding in the primordial style); regenerated `expected/main.json` | `gw test --tests '*CompilerOutputEquivalence*'`; a diff of the artifact that shows only added cells and the entries of the new labels | the test passes with the new artifact and the pull request names the regeneration |
| 6 | Documentation | the documents listed under Documentation; this document's status | review of the diff against the neighbouring sections: length, parts, vocabulary | every document uses the terms level, path, control block, case, and no more words than its neighbours |
| 7 | Gate | — | `gw check` | PMD and the full suite green |

## Decisions

1. One structure, the control block: `.CONTROL <Block>`, `.CASE <Case>`, `.ENDCONTROL`. The name
   stays `.CONTROL`: it names the purpose, control flow, without naming one structure, and the
   block has no condition of its own; `.CONDITIONAL` would name what a loop does not have,
   `.BLOCK` is the general word the specification uses for every kind of block.
2. The compiler writes labels and nothing else; no instruction is generated for a block.
3. A control block is a level in the sense of QUALIFIED_NAMES: `<Block>` is a name of the
   level around it and the label of the block's start; `<Case>` and `END` are names of the
   block's level; everything defined inside the block belongs to the block's level.
4. The block name is mandatory; `END` is defined when the block opens.
5. Visibility and paths follow the one rule of QUALIFIED_NAMES: plain names inside, `WALK.BLOCKED`
   from a level the block encloses, `EXPORT` on a case to enter it from outside, `LIB.WALK.BLOCKED`
   across modules with every segment exported.
6. What does not jump runs on into the next place; the compiler checks no jump.
7. `EXPORT` before each of the three directives exports the name it defines.
8. No short form, no `&&`/`||`, no `.AND`/`.OR`, no `.IF`/`.WHILE`/`.SELECT` word pairs; the
   structures of other languages are patterns of code, documented in the guidelines.
9. The patterns rely on the rule that a condition which cannot be tested does not hold, settled
   in the instruction set (#203); this document changes nothing in the runtime.
10. Stack conditionals stay destructive; non-destructive reading is deferred.
11. The block has no rule of its own on names; its name, its cases and `END` are labels, and
    the uniqueness is the label's rule; duplicates for labels, blocks and procedures come
    together with #153.
12. A block in a macro or `.REPEAT` body takes its name as a parameter, like a label.
13. The primordials stay unchanged; the reference program is extended, its artifact regenerated
    once.
14. The source view gets no new concept; the directives show as label lines do.
15. The jump out of a procedure body is #201; the jump into a block's case is what `EXPORT`
    on the case declares.
16. The marshalling's bridge labels stay as they are (QUALIFIED_NAMES, decision 9).
17. The block is its own head: `ControlNode` holds the statements before the first case and the
    cases; there is no node for the head, and `END` has the position of `.ENDCONTROL`.
18. `.CONTROL` … `.ENDCONTROL` is a block kind of the parser's discipline, with `.CASE` as its
    divider; the discipline, the recovery and the `EXPORT` flags of the block's words are
    BLOCK_MECHANISM's, and the handler is written as its `SelectHandler` test case is.
19. The cases are defined in text order, by a collector of their own, so that a clash is
    reported at the later name, as for two labels.

## Outcome

Implemented in the seven steps above, by one agent per step with the plan row as its contract.
What the built code does beyond the solution text:

- **Messages of the parser.** A `.CONTROL` without a name, a word after the name, a `.CASE`
  without a name or with a word after it is reported once ("Expected block name after
  .CONTROL.", "Expected newline after .CONTROL declaration.", "Expected case name after .CASE.",
  "Expected newline after .CASE <name>.") and the block is left behind, as the block mechanism
  does for every handler that gives up at its header.
- **Messages of the level.** A second block of one name on one level is reported as "Cannot
  define block '<name>': the name is already used at <position>.", a second case as "Cannot
  define case …", in the form of the label's message; a label `END:` in a block is reported by
  the label itself, with the `.ENDCONTROL` line as the place already taken.
- **The example case is `BLOCKED`**, not `TURN`: `TURN` is an instruction, and the lexer never
  yields it as a name.
- **EXPORT across modules needed no code**: step 4 is a test class of its own,
  `ControlBlockModuleTest`, tagged integration, because the one visibility rule carries the
  block's names across an import unchanged.
- **The reference program shows each path once**, not every pattern: a block with a case and a
  jump to `END`, a block nested in that case that leaves the outer one, and the entry into a
  block another module exports, at its start and at its exported case. The patterns live in
  `ControlBlockCompileTest`, where every one of them compiles to the cells of its hand-written
  twin. The library's block stands in the last module laid out before `main`, so that no existing
  cell moves; the program-relative addresses of `main`'s cells are renumbered by the cells laid
  out before them.
- **The core changed in its comments only**: the symbol table and the scope tracker speak of
  the levels a node opens, a procedure or a control block, where they named procedures alone.
