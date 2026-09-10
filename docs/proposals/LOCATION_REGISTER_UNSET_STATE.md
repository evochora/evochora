# The Unset State of a Location Register

**Status: TO BE REVIEWED — the decisions are taken; the alternatives they were taken against are at
the end, together with what is deliberately left out.**

## Problem

A location register holds a coordinate vector. There is no state for "holds no position", so the
instruction set improvises one: `CRLR` writes the zero vector (`LocationInstruction.java:199-202`),
and a register carries the same zero vector from birth (`Organism.java:171`). The primordial reads
that as "not set" — it clears the continue coordinate to restart a reproduction
(`assembly/primordial/lib/reproduce.evo:418`) and guards its jumps with a comparison against `0|0`
(`:86-89` and `:136-139`).

`0|0` is an ordinary cell of the world, and on a toroidal world it is not even at an edge. Two
things follow from giving it a second meaning.

**A jump can reach a position the organism has no claim to.** Every other way a data pointer is
placed leads somewhere the organism has a claim to: `SEEK` steps one cell into empty or own ground,
`SKLR`, `SKLS` and `SYNC` return to a place the organism has been, and `SKJ*`, `PSLI` and `LRLI`
resolve a label and refuse a foreign target. `CRLR` followed by `SKLR` is the only path that
*invents* a position — the organism has never been at the world origin, and no label named it. A
mutation that drops the guard sends the data pointer there, and every organism that loses its guard
arrives at the same cell.

**A program cannot ask.** The only way to learn whether a location register holds a position is to
read its content out and compare it against `0|0`, which is exactly what makes the convention
necessary in the first place. No instruction asks the register itself.

A third, smaller defect sits in the specification rather than in the runtime. `ASSEMBLY_SPEC.md:76`
states that the location instructions "enforce ownership checks — the DP can only jump to previously
visited positions or label-known positions". `SKLR` and `SKLS` enforce nothing; that a stored
position was visited or label-known is a property of where the content came from, not a check the
instruction performs.

## What this changes for a running population

The change is not neutral for the organisms living under it, and both effects are intended.

`CRLR` today produces a usable, if arbitrary, position. Afterwards it produces a state on which six
instructions fail — `SKLR`, `SKLS`, `LRDR`, `LRDS`, `LSDR`, `LSDS` — each with the error penalty.
Every organism and every mutant whose path reaches one of them with a cleared register pays
something it did not pay before. That is a fitness change, not only a correctness change, and it is
the point: the alternative is the jump to the world origin.

The instruction space grows. Operations 18 and 19 of the conditional family are unregistered today,
so a mutation that lands there decodes to nothing; afterwards it decodes to `IFSL` or `INSL`. No
existing opcode moves and no already-written instruction changes its meaning, but what mutation can
reach is one pair wider.

## Solution

### A location register is either a position or nothing

`NO_LOCATION` is a shared, immutable `int[]` with no components. A position in an n-dimensional
world has exactly n components, so an array without components is not a position and cannot be
confused with one — including with `0|0`, which stays an ordinary position that can be stored,
handed out and jumped to like any other. The state is recognised by length rather than by identity,
because `LRLR` copies a register with `clone()` and a cloned empty array is a different instance.
The predicate is named once, as `Organism.isUnsetLocation(int[])`, so that `length == 0` appears in
no call site.

A location register carries `NO_LOCATION` from birth and after `CRLR`. `CRLR` remains the only
instruction that produces it. Today `CRLR` allocates a fresh `int[]` on every execution
(`LocationInstruction.java:201`); writing the shared constant removes that allocation.

Disjointness is enforced rather than assumed. `writeLocationOperand` and `pushLocation` reject a
vector whose length is neither zero nor the world's dimension count — one comparison on a path that
already performs four bounds checks. Without it, a wrong-length write would produce a value that is
neither a position nor the state, and nothing would notice.

| Instruction | Behaviour on `NO_LOCATION` |
|---|---|
| `LRLR`, `PUSL`, `POPL` | carry the state through — a copy copies what is there |
| `LRDR`, `LRDS`, `LSDR`, `LSDS` | fail: no instruction hands the state out as a vector |
| `SKLR`, `SKLS` | fail: there is no position to jump to |
| `IFSL`, `INSL` | ask for the state (below) |

Every one of these failures changes nothing else: no register is written, no stack entry is
consumed, the data pointer stays where it is.

Refusing the four instructions that hand a vector out is what keeps the state inside the organism.
`Organism.hasWorldDimensions` logs an error with a stack trace for any vector whose component count
does not match the world, so a `NO_LOCATION` that escaped into a data register would surface far
from its origin, as noise in the log of a world interaction.

The location stack carries the state as well. It has to: procedure parameters of a location register
are marshalled through it — the compiler emits `PUSL` before the `CALL` and `POPL` after it
(`CallerMarshallingRule.java:92-110`), the callee's prologue and epilogue do the same
(`ProcedureMarshallingRule.java:49-51`, `:107-113`) — so a location register that holds no position
must survive being passed to a procedure. The stack is the transport for register contents, and it
transports what the register holds. What must not happen is that anyone reads the state as a
coordinate, and that is enforced at the instructions that interpret an entry as a position: `SKLS`,
`LSDR` and `LSDS` refuse it.

### The persisted format needs no schema change

A `Vector` is `repeated int32 components` (`metadata_contracts.proto:413-416`), so a `Vector` with
no components expresses "no position" exactly as the runtime does, without a sentinel and without a
new type. The pipeline already carries it end to end: `OrganismStateSerializer.convertVectorReuse`
turns an `int[0]` into a zero-component `Vector`, and `SimulationRestorer.toIntArray:974` turns it
back into `int[0]`. `location_stack` stays `repeated Vector` in both messages, the location
registers stay `RegisterValue.vector`, and `OrganismStateConverter` keeps throwing on an empty
`oneof`, which still means corrupt data and nothing else.

Location content reaches the format through five carriers: the flat register array of
`OrganismState`, the same array in `OrganismRuntimeState`, the registers saved in a `ProcFrame`, the
per-procedure snapshot of the persistent store, and the register values captured for the instruction
annotation. None of them needs a code change. What they need is the field comments — three places
say "Location registers use the `RegisterValue.vector` variant"
(`tickdata_contracts.proto:143`, `:397`, `:447`) and must say what a vector with no components means
— and the display below, which applies to all five.

`ValueFormatter.js` is where a location value becomes text: a raw array is joined with `|` (`:32`),
a `VECTOR` object the same way (`:52`). A vector with no components therefore renders as the empty
string — no error, an empty cell indistinguishable from a display gap. That file gets an explicit
rendering for "no position"; `OrganismStateView.js` only passes values into it. This breaks in the
browser, not in the build, so `./gradlew check` does not cover it.

### `IFSL` and `INSL`

Without an instruction that asks, a program can only learn the state by provoking a failure and
observing it with `IFER`, at the price of the error penalty on every query. The conditional family
gains one pair:

* `IFSL %LOC_REG` — executes the next instruction if the register holds a position.
* `INSL %LOC_REG` — the negation.

They occupy operations 18 and 19 of the conditional family, which are free (0 to 17 are in use, of
64), so no existing opcode moves. The variant is `L`, the single location register operand, whose
resolution carries only the register id and no value (`Instruction.java:319-328`), so the
instruction reads the register itself. The naming follows the family — `IF` plus a condition letter
plus the operand letter, as in `IFMR`, `IFPR`, `IFFR`, `IFVR` — and its negation reads as the
sentence a program means: `INSL` is "if not set".

The family selects its operation by name inside the class, in part by prefix, so the new pair is
dispatched by exact operation and placed where it cannot capture or be captured by `IFS` and `INS`.
That is internal to `ConditionalInstruction`; it changes nothing outside it.

Two mirrored definitions carry the instruction names and gain the pair:
`extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json:37` and the conditional listings
in `ASSEMBLY_SPEC.md` (the value and register tests, and the negated list).
`docs/proposals/compiler-enhancements/CONDITIONAL_BRANCH_ISA.md` enumerates "all 18 conditional
operations" in two places and will need the pair when it is implemented; it is not yet built, so
this costs two lines in an unbuilt document.

### A failed instruction in the location family changes nothing

The rule is issue #159's and is applied here to the family this work touches: an instruction that
fails leaves registers, stacks and the world as they were and pays only its cost, its entropy and
the error penalty. Five places in `LocationInstruction` change something before they can fail:

* `SKLS` pops and then sets the data pointer — it must read the top, check it, and pop only when the
  jump happens.
* `LSDS` checks for room on the data stack and then pops, without ever inspecting the value; it must
  look before it pops, because refusing `NO_LOCATION` is a decision about the value.
* `POPL` pops and then writes the register.
* `SWPL` and `ROTL` pop two or three entries and then push them back.

`DUPL` peeks and is already correct.

For `SWPL` and `ROTL` the failure is reachable only from a stack that was restored above its limit —
which `SimulationRestorer.warnIfStackBeyondLimit` deliberately permits, because a state is restored
as it was stored. From a stack within the limit, popping two and pushing two cannot cross it. The
test for these two must therefore construct the over-limit stack; against a normal stack it would
pass without exercising the rule.

The code says this in its own words. Comments name the behaviour, never the issue or this document.

### A world has at least one dimension

`NO_LOCATION` rests on positions having at least one component. `GridLayout` validates the world
shape today — tile side, index bounds — but not that a shape has any dimensions at all; its
per-dimension loop simply does not run, and a dimensionless world is accepted as a one-cell world
whose only coordinate is `int[0]`. It gains the check, and its constructor's `@throws` list gains
the condition. `EnvironmentProperties` is deliberately not the place: the compiler builds a
dimensionless instance as a placeholder for an artifact without a world
(`LinearizedProgramArtifact.java:118`).

### The primordial

Two sites, both in `assembly/primordial/lib/reproduce.evo`, both the same guard:

* `:86-89` reads the continue coordinate out, pushes `0|0`, compares and resumes if they differ.
  It becomes `IFSL %CONTCORD` followed by the jump.
* `:136-139` is the same guard with the opposite polarity. It becomes `INSL %CONTCORD` followed by
  the jump.

`CRLR %CONTCORD` at `:418` keeps its meaning and now says what it does. Both blocks are anchored by
their own `.ORG`, so the shortening stays inside its row and no later block moves; the `NOP` padding
of the two rows is adjusted with them.

This cannot be separated from the runtime change. As soon as `CRLR` sets the state, the `LRDS` of
the old guard fails, its `PUSV 0|0` still pushes, and the comparison decides on whatever lies
beneath — the guard becomes noise in both directions. Nothing in the suite would report it:
`AssemblyProgramsCompileTest` compiles the primordial, it does not run it. Runtime change and
primordial change land together.

### The specification

`ASSEMBLY_SPEC.md` states what actually holds:

* `:76` — that a location register holds either a position or nothing, what `SKLR` and `SKLS` do
  with each, and that the ownership check lives in `SEEK` and in the label-resolving instructions,
  not in the register instructions. This hunk removes a documented guarantee the code never made.
* `:518` — `CRLR` sets the register to "no position" instead of clearing it to `[0, 0]`; the second
  mention at `:691` follows.
* the conditional listings — the new pair and its negation.
* the entries of the instructions that now refuse the state.

### Documentation the change falsifies

Existing JavaDoc states the invariant that is being replaced and is corrected with it:
`Organism.getLocationStack` (`:1795-1803`, "vectors of one component per world dimension"),
`Organism.writeLocationOperand` (`:1911-1920`), `Organism.getRegisters` (`:1650-1655`),
`resetStackSavedRegisters` (`:1990-1993`) and `resetPersistentRegisters` (`:2046-2050`), which both
promise a zero vector for location banks, `SimulationRestorer.convertRegisterValue` (`:783-797`),
whose reasoning rests on what the write side can produce, and `OrganismStateSerializer`'s
`defaultForRegisterSlot` (`:243-253`) and `convertRegisterValueReuse` (`:256-263`).

`DUPL` pushes the same array reference twice (`LocationInstruction.java:100`), and `getRegisters`
hands out the register's own array. That is safe because nothing writes into a stored vector, and
`NO_LOCATION` makes a shared stored array ordinary rather than exceptional; the policy is stated
where the stack is documented.

## Implementation

1. **The state, the guards, and the primordial** — one commit, because the two cannot be separated.
   `Organism`: the `NO_LOCATION` constant, the `isUnsetLocation` predicate, the four sites that
   initialise a location register (`:171`, `:250`, `:1997` and `:2054`, the last of which resets the
   persistent bank on `CALL`, `RET` and stall recovery), and the length check in
   `writeLocationOperand` and `pushLocation`. `LocationInstruction`: `CRLR` writes the state,
   `SKLR`, `SKLS`, `LRDR`, `LRDS`, `LSDR`, `LSDS` refuse it, `LRLR`, `PUSL`, `POPL` carry it.
   `OrganismStateSerializer.defaultForRegisterSlot` substitutes the state instead of a zero vector.
   `assembly/primordial/lib/reproduce.evo`: the two guards and their padding.
   Verified by new cases in `VMLocationInstructionTest`, each written red first, and by reading the
   changed primordial rows.

2. **`IFSL` and `INSL`.** `ConditionalInstruction`: the pair at operations 18 and 19, variant `L`,
   dispatched by exact operation. `evochora.tmLanguage.json`. Verified by
   `ConditionalInstructionCompilerTest` for the encoding and one runtime case per polarity;
   `ConditionalNegationTest` picks the pair up through `regPair` on its own.

3. **The #159 rule in the family.** `SKLS`, `LSDS`, `POPL`, `SWPL` and `ROTL` check before they
   change anything. Verified by a test per instruction that provokes the failure and asserts the
   stack is untouched — for `SWPL` and `ROTL` from a stack constructed above its limit.

4. **The format and the display.** The three proto field comments; `ValueFormatter.js` renders "no
   position" visibly. Verified by the resume and indexer tests for the round trip, and by looking at
   an organism with a cleared location register in the visualizer, since the browser path is outside
   `./gradlew check`.

5. **The world guard.** `GridLayout` rejects a shape without dimensions, with a test and the
   `@throws` entry.

6. **The documentation.** The `ASSEMBLY_SPEC.md` passages, proposed hunk by hunk, and the JavaDoc
   listed above.

Tests whose meaning changes and which are adjusted with the step that changes them:
`VMLocationInstructionTest`, `LocationInstructionCompilerTest`,
`RuntimeIntegrationTest:291` and `:346` (a `CRLR` on an `LREF` parameter written back to the caller,
asserted as `[0,0]` today), and `StatefulProgram.java:61`, which feeds the resume-neutrality tests.

Verified overall by `./gradlew check`.

## Alternatives, and what they cost

**Do nothing, and correct only the specification.** The false sentence at `:76` goes, the sentinel
stays. Costs nothing and leaves the defect: a mutation that drops a guard still sends the data
pointer to the world origin, and every organism that loses its guard still arrives at the same cell.
Rejected because the convention has no backing in the physics and the collision is a property of the
world, not of one program.

**Drop the idea.** Same as above without the specification fix, and the specification then keeps a
guarantee the runtime never made. Rejected for that reason alone.

**`null` as the state.** Costs nothing to write and is the obvious first thought. It collides with
two things: `ArrayDeque` rejects `null`, so the location stack would need a different container, and
both write gates reject `null` deliberately, with the documented reasoning that a null register
surfaces far from its origin. Rejected.

**A dedicated value type for location content.** Expresses the two states in the type system, so no
convention can be broken by a wrong-length write. Costs one small object per location value on a
path that runs on every procedure call with location parameters — `PUSL` and `POPL` marshal each
location argument four times — where the chosen design allocates nothing and `CRLR` even allocates
less than today. It would also change the signatures of the location stack and the write gates and
the roughly thirty test sites that use them. Rejected on the allocation, with the length check in
the write gates buying back most of what the type would have guaranteed.

**A dedicated message type, or `RegisterValue`, for the location stack in the format.** Both were
considered and both are unnecessary: a `Vector` with no components already expresses the state.
`RegisterValue` would additionally widen a field that cannot express a scalar today, force a
bank-aware acceptance rule into the restorer to keep the corruption check it has, change the DTO the
visualizer reads, and — because `Vector.components` and `RegisterValue.scalar` share field number 1
with different wire types — make data from an older build decode silently into the new state instead
of failing. Rejected.

**What the chosen design costs.** "No components means no position" is a rule about an `int[]`, not
a promise of the type system: a future call site that builds a location vector of the wrong length
would produce something that is neither. The length check in the write gates catches that at the two
places where a location value enters the organism, and the `GridLayout` guard keeps the two kinds
disjoint by making a dimensionless world impossible; between them the rule holds, but it holds by
construction and not by the compiler. The second cost is the fitness change named above: six
instructions become fallible on a cleared register.

## Decided, and deliberately not part of this work

**The ownership checks stay as they are.** `SEEK` remains strict — a step goes into an empty or an
own cell, which is what makes a wall of `STRUCTURE` an obstacle. `SKJ*`, `PSLI` and `LRLI` keep their
check, so a fuzzy label match cannot place a data pointer inside a foreign body. `SKLR`, `SKLS` and
`SYNC` keep no check: they lead to a place the organism has been, and `SYNC` in particular must not
check, because an organism executing foreign code would otherwise be unable to run an instruction
that the code's owner runs without trouble — and executing foreign code is a deliberate property of
this world. With the invented position gone, every placement leads to a step into empty or own
ground, to a place the organism has been, or to a label position that passed the check.

**Location registers and the location stack keep absolute world coordinates.** They receive a
coordinate only from a position the organism visited or from a label it resolved, so an organism
cannot survey the world through them.

**The primordial is checked by reading, not by a run.** The two rewritten guards are read against
the seven `%CONTCORD` sites of the file. A run would establish more — that no path reaches
`SKLR %CONTCORD` at `:144`, `:147` or `:322` after `CRLR` without passing a guard — and is
deliberately not part of this work.

**Issue bookkeeping happens after implementation.** Issue #166 then records the decisions above and
what was built; issue #159 records that the location family follows its rule and that the rest of it
— transactional stacks, gains after success, the handler audit — stays parked.
