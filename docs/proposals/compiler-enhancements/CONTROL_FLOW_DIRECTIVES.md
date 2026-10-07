# Control Flow Directives

**Status: TO BE REVIEWED** — specification complete, implementation on branch `feature/control-flow`.
Builds on [QUALIFIED_NAMES](../../outdated/proposals/accomplished/QUALIFIED_NAMES.md).

A control block is a third kind of level next to the module and the procedure. It gives a stretch
of code a name and named places inside it, so that the jumps a programmer writes today with
hand-named labels can be written with labels the block provides, reached like every other name:
by the plain name inside the block, by the path from outside. The compiler generates no
instruction for a block: every jump, every conditional and every empty cell stays the
programmer's, so that the padding and the redundancy that make a program evolvable
(`docs/EVOASM_GUIDELINES.md`) stay under the programmer's control. With the block comes one rule
of the instruction set: a conditional whose test cannot be evaluated does not hold.

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
  `JMPI TURN`, `JMPI END`, `JFI %DR0 DATA:10 TURN`; a plain name means the innermost level that
  has it. From a block nested in `WALK`, the outer block's places are `WALK.TURN` and
  `WALK.END`. From outside the block, `WALK` itself is visible, a jump to it enters the block
  at its start; `WALK.TURN` and `WALK.END` are visible only when the case carries `EXPORT`, as a
  label in a procedure is. From another module, `LIB.WALK.TURN` when the block and the case are
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
- `.CONTROL`, `.CASE` and `.ENDCONTROL` are block words of the parser, as `.PROC` and
  `.ENDPROC` are: blocks nest and never overlap, an end closes the block opened last, and an end
  or a `.CASE` that belongs to another open block, or to none, is reported with both places, as
  the preprocessor reports its blocks. A `.PROC` stands only at the module level; inside a
  control block it is reported, as `.IMPORT` is.

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

### The instruction set: a failed test does not hold

A conditional's test has three outcomes, not two: it holds, it does not hold, or it cannot be
evaluated, because an operand names no register, a stack lacks a value, an argument cell lies
beyond the edge, or a cell test gets no vector. Other languages know only two outcomes; where a
third value meets a two-way decision, two models exist. IEEE floating point: a comparison with
NaN is false and its negation is true. SQL: a comparison with NULL is unknown and passes no
filter, negated or not. Measured on the two ways a guard is written, only the IEEE model gives
one answer:

| Guard | failed test under IEEE | under SQL | today (does nothing) |
|---|---|---|---|
| entry: `IFx; JMPI THEN`, body behind THEN | skip skips: body does not run | body does not run | jump runs: body runs |
| abort: `INx; JMPI END`, body follows | negation holds, jump runs: body does not run | skip skips the jump: body runs | body does not run |
| entry: `JFx THEN` | no jump: body does not run | does not run | does not run |
| abort: `JNx END` | jumps: body does not run | no jump: body runs | body runs |

The rule is therefore: **a conditional whose test cannot be evaluated does not hold; its negation
holds.** `IFI` skips, `INI` runs the next instruction, `JFI` stays, `JNI` jumps. The failure is
booked as it is today, with its penalty and its reason; the conditional decides in addition. Of a
conditional and its negation exactly one acts, on failure too, which is the invariant the
marshalling relies on when it rewrites `IFx; CALL P` into `INx; JMPI over; …`: the written form
and the compiled form then agree when `x` cannot be read, where today the written form would call
and the compiled form does not.

What the machine has to do for it, settled against the code:

1. **A conditional declares that it decides after a failure.** Every instruction declares its
   control-flow behaviour when it registers (`declareNeverFallsThrough`, `declareSkipsNext`,
   `declareLabelIsJumpTarget`); `AbstractConditionInstruction.regPair`, through which every
   conditional and its negation register, adds a fourth, `declareDecidesOnFailure`, for both
   opcodes of the pair; the two conditional classes stay as they are. `VirtualMachine.execute` runs an instruction
   that failed while it was planned only when it declared this; every other instruction stays
   unexecuted, as today. The machine learns a declaration, not an instruction kind.
2. **The conditional decides "does not hold".** `AbstractConditionInstruction.execute` continues
   where it returns today on a failed operand resolution, a wrong operand count or a failed test:
   with "does not hold", which `act` receives through the opcode's negation flag as it does now.
   The test itself is not evaluated on failure.
3. **The operand list always has one entry per operand, at its place.** `Instruction.resolveOperands`
   returns an empty list today when the stack lacks a value or an argument cell lies beyond the
   edge. Instead it books the failure where it arises (the stack case is booked here, no longer
   found by the instruction through the list's length) and fills every slot it could not read
   with `Operand.MISSING`: one slot for one unreadable value, whatever its place, a stack slot
   without a value as well as an argument cell beyond the edge. The slots that could be read are
   resolved as always; the check per cell runs only when today's one check, on the last cell,
   fails, so the common path stays as it is. `MISSING` is one object, `isMissing()` compares by
   identity, and no cell can produce it. The list is mutable, because `InterceptionContext.setOperand`
   writes into it. A conditional jump that has to jump reads the slot its signature names as the
   label and does not jump when that slot is `MISSING`; it books nothing, because a `MISSING` slot
   exists only where a failure was booked and the organism keeps the first reason of a tick.
   Nothing assumes where the label stands among the operands.

   Who can see a list with a `MISSING` slot is bounded by two properties: `MISSING` arises only
   together with a booked failure, and a booked failure is executed only by a conditional (point
   1). The readers are therefore the conditional itself and the interceptors, which see per slot
   what could be read and learn the failure from the organism as today. Every other instruction
   body stands behind the gate in `VirtualMachine.execute`, the conflict resolution passes failed
   instructions over (point 4), the thermodynamics read no operands, and the trace and the
   execution record read the raw argument cells. Both properties are tests of step 5.
4. **A failed instruction claims no cell in conflict resolution.** `Simulation.resolveConflicts`
   asks every environment-modifying instruction of the second wave for its target cell, failed
   ones included; a failed instruction with operands that read like a vector can make another
   organism lose the conflict although it never writes. That is a defect of its own: the method
   passes over an instruction whose organism has booked a failure, after it has marked the
   instruction as processed in the tick and before it asks for the target cell, so that the
   instruction still goes through the execution phase for its penalty. Test first.
5. **A skip whose test failed still skips.** `Organism.skipNextInstruction` detects today that
   `skipNopCells` failed (pointer left the world, skip budget exhausted) by reading the organism's
   failure flag, which is also set when the conditional's own test failed before. `skipNopCells`
   returns whether it stopped on a cell, and `skipNextInstruction` asks that return value; its own
   failure ends the skip as today, a failure booked before does not.

One cause has no visible effect: when an argument cell lies beyond the edge of a bounded world,
what the decision would run next lies beyond it too. The skip finds no cell, the jump has no
label, the plain advance leaves the world; all four forms end in the stall recovery in the same
tick, with the one penalty, where the instruction ends today. Step 5 tests the recovery and the
penalty for this cause, not a skip or a jump.

Not changed: the penalty, the `failed` and `fail_reason` columns of the trace, the length checks
inside the instructions (they stay as guards and become unreachable for an organism), the stack
consumption of a failed stack instruction (the values that were peeked are popped, as today),
and the `cond_met` column of the trace, which stays empty for a failed step: under the rule a
failed test does not hold, so `failed` tells the decision. Interceptors see failed instructions
as today; what a plugin does with one is the plugin's.

### Architecture

One feature package, `features/control`, in the slicing every feature follows, and a mirror of
`features/proc` wherever the block does what a procedure does. The core changes in one place,
the parser, and only generically: it gets the block discipline the preprocessor already has
(`BlockKind`, `BlockReader`), so that `.PROC` and `.CONTROL` nest and never overlap and a wrong
closer is reported with both places. The core learns block words, not features.

| Component | Phase | Does |
|---|---|---|
| block kind | 3, parser | `ControlFeature` registers the block kind with the opener `.CONTROL`, the closer `.ENDCONTROL` and the divider `.CASE`. The closer and the divider are no statements and have no handler: the parser's block reading stops at them, and the parser reports one that belongs to another open block or to none. |
| `ControlDirectiveHandler` | 3, parser | Parses `.CONTROL <Block>` and reads the block's statements through the parser's block reading; at `.CASE <Case>` it starts a case, at `.ENDCONTROL` it ends the block and takes the end's position and `EXPORT`. Accepts `EXPORT` on all three words, which the parser handles before the handler sees them. Rejects a missing name. Keeps no state of its own. |
| `ControlNode`, `ControlCase`, `ControlEnd` | AST | Records. `ControlNode(name, exported, statements, cases, endExported, sourceInfo, endSourceInfo)` implements `IJumpTarget`: the block is its own head, its name is the label before its first statement, and it needs no node for the head. `ControlCase(name, exported, statements, sourceInfo)` implements `IJumpTarget`. `ControlEnd(sourceInfo)` implements `IJumpTarget`, carries the position of `.ENDCONTROL` and is the node of the `END` symbol; it never stands in the tree. The children of a block are its statements followed by its cases; `reconstructWithChildren` tells them apart by type. |
| `ControlSymbolCollector` | 4, pass 1 | As `ProcedureSymbolCollector`: defines `<Block>` on the current level (`Symbol.Type.LABEL`, node: the block, exported as written), reporting a second definition like a duplicate label; enters the block's scope with `enterScope(name)` and `registerNodeScope`; defines inside it `END` (node: `ControlEnd` with the end's position, exported as `.ENDCONTROL` said). Leaves the scope after the children. |
| `ControlCaseSymbolCollector` | 4, pass 1 | As `LabelSymbolCollector`: defines `<Case>` in the current scope when the walk reaches the case (`Symbol.Type.LABEL`, node: the case), so that a clash with a name written earlier in the block is reported at the later of the two, as for two labels. |
| `ControlAnalysisHandler` | 4, pass 2 | As `ProcedureAnalysisHandler`: enters the block's prebuilt scope and leaves it after the children. The instruction analysis validates the operands; `IJumpTarget` makes a block name, a case and the end valid label arguments. |
| Phases 5 and 6 | — | Nothing to register: the token map classifies a reference through its symbol, the post-processor replaces it by its path, as for every label. |
| `ControlNodeConverter`, `ControlCaseConverter` | 7, IR | As `ProcedureNodeConverter`: the block converter emits `IrLabelDef(qualifyName(<Block>))` with the block's position, calls `enterScope(<Block>)`, converts the statements, then the cases, then emits `IrLabelDef(qualifyName("END"))` with the end's position and calls `leaveScope()`. The case converter emits `IrLabelDef(qualifyName(<Case>))` with the case's position and converts the case's statements. |
| `ControlFeature` | — | Registers the block kind, the parser handler, the two symbol collectors, the analysis handler and the two converters; takes its place in `StandardFeatures` before `InstructionFeature`. |

What the core already provides and the block only uses: the level (`enterScope` with a segment,
`registerNodeScope`), the path as identity (`qualifyName`), the descent of a path into a scope a
symbol opened, the visibility rule with `EXPORT` on every segment, the one-segment rule on names,
the reporting of `.IMPORT`/`.REQUIRE` inside a level.

What changes in the parser core and in the neighbouring features, all in step 1 unless said:

- **Block discipline in the parser.** A feature registers a block kind (openers, closer,
  dividers) with the statement registry, as it registers one with the preprocessor. The parser
  keeps the stack of open blocks and offers the handler of an opener a method that reads
  statements up to the block's own divider or closer. A closer of another open block ends the
  inner block there, reported in the preprocessor's words ("`.ENDPROC` closes the `.PROC` opened
  at …, but the `.CONTROL` opened at … is still open"), and stays for the outer handler; a
  closer or divider with no open block of its kind is reported where it stands; a block still
  open at the end of the input is reported at its opener. `ProcFeature` registers `.PROC` /
  `.ENDPROC`, `ProcDirectiveHandler` reads its body through the same method and loses its
  look-ahead for `.ENDPROC`. Today a forgotten `.ENDCONTROL` in a procedure would give three
  messages, the first of them "Unknown directive '.ENDPROC'"; with the discipline it gives one,
  at the right line.
- **A label takes no statement.** `LabelNode` loses its `statement` field, `LabelDirectiveHandler`
  returns the node alone, and the enclosing loop reads the rest of the line as the next
  statement. Today a label alone on its line takes the statement of the next line, a leftover of
  the first parser that nothing but the label's converter ever read; it swallows a closer, so
  that `L:` before `.ENDPROC` yields "Unknown directive '.ENDPROC'" today. The IR is unchanged,
  because label definition and statement are emitted in the same order.
- **`.PROC` stands only at the module level.** `ProcedureSymbolCollector` reports a procedure
  defined inside any level, as `RequireSymbolCollector` reports `.REQUIRE`. No program in the
  repository nests a procedure, and nesting would give nothing: procedure-local registers belong
  to the call frame, so an inner procedure never sees the outer's, and the inner body would lie
  in the outer's cells. Access to a caller's registers is what `REF` parameters give.
- **Core comments, in step 2.** The class comment of `SymbolTable` ("one scope per procedure",
  and the sentence that a name an enclosing scope holds is reported when the table freezes,
  which QUALIFIED_NAMES withdrew), the comment of `Scope` ("procedure-local or module-global"),
  the two mentions of `ProcedureSymbolCollector` at the node-scope map, and the class comment of
  `ScopeTracker` speak of levels a node opens. The withdrawn sentence is a leftover and goes in a
  commit of its own.

Runtime changes: the five points of the instruction-set section (`Instruction`,
`AbstractConditionInstruction`, `ConditionalJumpInstruction`, `VirtualMachine`,
`Simulation.resolveConflicts`, `Organism.skipNopCells`/`skipNextInstruction`), and what described
the empty list: the `isEmpty` branch of `EnvironmentInteractionInstruction.targetCoordinate`
becomes unreachable and goes with its comment, the comments of `OperandSource.STACK` and
`InterceptionContext.getOperands` describe the list with `MISSING`. The trace consumer is
unchanged.

Nothing changes in the layout, the linker, the emitter, the artifact or the visualizer: block
labels are labels with paths.

### Documentation

- `docs/ASSEMBLY_SPEC.md`: a subsection "Control blocks" in section 7, in the form and length
  of `.PROC`'s, with one example, referring to "Qualified names" for visibility; under `.PROC`
  that a procedure stands only at the module level; under "Conditional Instructions" the
  sentence on a failed test; under "Blocks" that `.CONTROL` is not one of the blocks that
  section describes: a macro may open it and another macro close it.
- `docs/EVOASM_GUIDELINES.md`: a section "Control blocks" with the patterns above, each with
  padding and redundancy, and the row rule (`.ORG` before the directive).
- `tools/trace/README.md`: under `cond_met`, that a failed test does not hold, so `failed`
  tells the decision.
- `.claude/skills/evoasm/SKILL.md`: the block next to the instructions.
- `docs/COMPILER_CORE_BOUNDARY.md`: the feature row (`control`: the directives, the level, the
  labels; parser, symbol collection, analysis, IR conversion).
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
- The failed-test rule changes what a running genome does where a conditional fails, which after
  mutations is common (empty stacks). Runs before and after this change are not comparable
  where that happens, and no run replays bit for bit across it.

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
  directives show their line and the annotation of their name, nothing more; the label cell
  itself is in the grid.

## Implementation

Strictly in this order; every step leaves the build green, and every step has a test that
would fail if the step were wrong. The level and the resolution come first, because a problem
there changes the design; the runtime rule comes after the compiler, because it is independent
of it and its mechanics are still open.

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | Parser, block discipline, AST | test first for the two defects (a label alone before `.ENDPROC`; `.ENDPROC` inside an open block), then: parser core (`ParserStatementRegistry` block kinds, `Parser`/`IParsingContext` stack of open blocks and block reading); `features/label`: `LabelNode`, `LabelDirectiveHandler`, `LabelNodeConverter`; `features/proc`: `ProcFeature` (block kind), `ProcDirectiveHandler` (reads through the core), `ProcedureSymbolCollector` (module level only); `features/control/`: `ControlNode`, `ControlCase`, `ControlDirectiveHandler`, `ControlFeature` (block kind and parser registration), `StandardFeatures`; tests: new `ParserBlockTest` in `compiler/frontend/parser`, `ProcedureDirectiveTest`, `CallAnalysisHandlerTypeSafetyTest` (builds a `LabelNode`), new `ControlDirectiveTest` in `compiler/directives` | `gw test --tests '*ParserBlock*' --tests '*ProcedureDirective*' --tests '*Label*' --tests '*ControlDirective*' --tests '*StandardFeatures*' --tests '*CompilerArchitectureRules*' --tests '*CompilerOutputEquivalence*'` | `L:` alone on its line before `.ENDPROC` compiles, and before an instruction on the next line gives the IR it gave before; `.ENDPROC` inside an open `.CONTROL` is reported once, with both places, and the procedure closes; `.ENDCONTROL` with no open block and `.CASE` outside a block are reported where they stand; a block still open at the end of the input is reported at its `.CONTROL`; a `.PROC` inside a `.PROC` or inside a block is reported; a block with head, three cases and nesting parses into the node tree, the end with its own position; `EXPORT` flags land on block, case and end; a missing name is reported; the architecture test accepts the feature; the reference artifact is unchanged |
| 2 | The block as a level | `ControlSymbolCollector`, `ControlCaseSymbolCollector`, `ControlEnd`, `ControlAnalysisHandler`, `ControlFeature` (registrations); the core comments of `SymbolTable` and `ScopeTracker`, the withdrawn shadowing sentence in a commit of its own; new `ControlLevelTest` in `compiler/features/control` | `gw test --tests '*ControlLevel*' --tests '*SemanticAnalyzer*' --tests '*QualifiedNames*'` | `JMPI END` and `JMPI TURN` inside `WALK` resolve to `WALK.END` and `WALK.TURN`; `JMPI WALK` from before and after the block resolves; `JMPI WALK.TURN` from after the block is reported as not marked EXPORT and resolves with `EXPORT .CASE`; `END` in a nested block is the inner end, `WALK.END` the outer; `JFI %DR0 DATA:1 TURN` passes the operand check; a label `END:` in the block is reported at the label with the `.ENDCONTROL` line named, a label `TURN:` in the head before `.CASE TURN` is reported at the `.CASE`, a second block `WALK` on one level is reported; a label and a constant defined in the block are visible inside it and reached from outside as `WALK.L` only with `EXPORT`; `.IMPORT` inside a block is reported |
| 3 | IR and the equivalence to hand-written code | `ControlNodeConverter`, `ControlCaseConverter`, `ControlFeature` (registration); new `ControlBlockCompileTest` in `compiler/features/control` | `gw test --tests '*ControlBlockCompile*' --tests '*CompilerOutputEquivalence*'` | for each pattern of the solution, the block program and its hand-written twin with ordinary labels compile to the same cells at the same coordinates, label and label-reference cells compared by position and type, not value; the artifact's label maps carry the paths `WALK`, `WALK.TURN`, `WALK.END`; the reference artifact is unchanged |
| 4 | EXPORT across modules | `ControlBlockCompileTest` (two modules, `@TempDir`, `@Tag("integration")`, modelled on `ReExportedImportResolutionTest`) | `gw test --tests '*ControlBlockCompile*'` | `LIB.WALK` and `LIB.WALK.TURN` are reachable from an importer with `EXPORT .CONTROL` and `EXPORT .CASE`; without one of them the missing segment is reported; a block inside a procedure is reached as `LIB.PROC.WALK` with the procedure exported |
| 5 | The failed-test rule | test first for the defect: `SimulationTest` (a failed instruction with vector-like operands no longer makes another organism lose the conflict), then `Simulation.resolveConflicts`; `Instruction` (`declareDecidesOnFailure` and its query; `resolveOperands` with `Operand.MISSING` per slot, mutable list; the comment of `OperandSource.STACK`), `AbstractConditionInstruction` (`regPair` declares, `execute` decides), `ConditionalJumpInstruction` (a `MISSING` label slot: no jump), `VirtualMachine.execute` (the one query), `Organism.skipNopCells` (returns whether it stopped on a cell), `skipNextInstruction`, `EnvironmentInteractionInstruction.targetCoordinate` (the `isEmpty` branch goes), `InterceptionContext.getOperands` (comment); tests: `VMConditionalSkipInstructionTest`, `VMConditionalJumpInstructionTest` (empty stack and register naming no register, each positive and negated: skip/no skip, stay/jump, penalty booked, the first reason kept, two instructions advanced on a skip; a cell beyond the edge: recovery and penalty for all four forms), `ConditionalNegationTest` (exactly one of a pair acts, on failure too), `InstructionArgumentSlotTest` (one entry per operand, `MISSING` only with a booked failure, the list mutable), a `VirtualMachine` test that no instruction but a conditional executes with a booked failure, `InstructionInterceptorTest` (`MISSING` visible per slot; its two expectations of an empty list change), `OrganismFailureChannelTest` unchanged and green, `SimulationTest` (reason texts) | `gw test --tests '*VMConditional*' --tests '*ConditionalNegation*' --tests '*InstructionArgumentSlot*' --tests '*VirtualMachine*' --tests '*OrganismFailureChannel*' --tests '*SimulationTest*' --tests '*InstructionInterceptor*'` | every row of the table in the instruction-set section holds; the cause beyond the edge ends in the recovery for all four forms; the two properties of point 3 hold; no instruction throws for any operand an organism can supply; a failed instruction claims no cell |
| 6 | The reference program | `src/test/resources/org/evochora/compiler/reference/main.evo` and `lib/util.evo` (a section in its own `.ORG` region after the existing code, with guard, chain, selection, loop, nesting, `EXPORT .CONTROL` and `EXPORT .CASE`, a macro with the block name as parameter, `.ORG` before a `.CASE`, padding in the primordial style); regenerated `expected/main.json` | `gw test --tests '*CompilerOutputEquivalence*'`; a diff of the artifact that shows only added cells and the entries of the new labels | the test passes with the new artifact and the pull request names the regeneration |
| 7 | Documentation | the documents listed under Documentation; this document's status and the settled rule | review of the diff against the neighbouring sections: length, parts, vocabulary | every document uses the terms level, path, control block, case, and no more words than its neighbours |
| 8 | Gate | — | `gw check` | PMD and the full suite green |

## Decisions

1. One structure, the control block: `.CONTROL <Block>`, `.CASE <Case>`, `.ENDCONTROL`.
2. The compiler writes labels and nothing else; no instruction is generated for a block.
3. A control block is a level in the sense of QUALIFIED_NAMES: `<Block>` is a name of the
   level around it and the label of the block's start; `<Case>` and `END` are names of the
   block's level; everything defined inside the block belongs to the block's level.
4. The block name is mandatory; `END` is defined when the block opens.
5. Visibility and paths follow the one rule of QUALIFIED_NAMES: plain names inside, `WALK.TURN`
   from a level the block encloses, `EXPORT` on a case to enter it from outside, `LIB.WALK.TURN`
   across modules with every segment exported.
6. What does not jump runs on into the next place; the compiler checks no jump.
7. `EXPORT` before each of the three directives exports the name it defines.
8. No short form, no `&&`/`||`, no `.AND`/`.OR`, no `.IF`/`.WHILE`/`.SELECT` word pairs; the
   structures of other languages are patterns of code, documented in the guidelines.
9. A conditional whose test cannot be evaluated does not hold and its negation holds (the IEEE
   model); the failure stays booked; the five mechanics of the instruction-set section are the
   way, including the conflict-resolution defect and the return value of `skipNopCells`. For an
   argument cell beyond the edge all four forms end in the stall recovery, as today.
10. Stack conditionals stay destructive; non-destructive reading is deferred.
11. Names stay unique per level; duplicates for labels, blocks and procedures come together
    with #153.
12. A block in a macro or `.REPEAT` body takes its name as a parameter, like a label.
13. The primordials stay unchanged; the reference program is extended, its artifact regenerated
    once.
14. The source view gets no new concept; the directives show as label lines do.
15. `cond_met` stays empty for a failed step, as today; under the rule a failed test does not
    hold, so `failed` tells the decision, and the trace consumer is unchanged.
16. The jump out of a procedure body is #201; the jump into a block's case is what `EXPORT`
    on the case declares.
17. The marshalling's bridge labels stay as they are (QUALIFIED_NAMES, decision 9).
18. The block is its own head: `ControlNode` holds the statements before the first case and the
    cases; there is no node for the head, and `END` has the position of `.ENDCONTROL`.
19. The block has no rule of its own on names; its name, its cases and `END` are labels, and
    the uniqueness is the label's rule (#153 for duplicates).
20. The parser gets the block discipline of the preprocessor: block kinds registered by
    features, nesting without overlap, a wrong closer reported with both places, no marker
    nodes; `.PROC`/`.ENDPROC` and `.CONTROL`/`.CASE`/`.ENDCONTROL` are block kinds.
21. A label takes no statement; the rest of its line is the next statement.
22. `.PROC` stands only at the module level; nested procedures and closures are not taken up:
    `REF` parameters give the access, and there are no procedure values to close over.
23. `Operand.MISSING` marks one unreadable slot, whatever its place; its readers are the
    conditional and the interceptors, bounded by two tested properties; the declaration that a
    conditional decides on failure is made in `AbstractConditionInstruction.regPair`.
