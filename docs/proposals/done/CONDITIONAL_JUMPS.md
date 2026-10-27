# Conditional Jumps

**Status: ACCOMPLISHED — implemented on branch `feature/conditional-jumps` (2026-10-04), in the six steps below; see *Outcome* at the end.**

Conditional jumps are a second kind of conditional instruction next to the existing ones: the same
conditions, but instead of skipping the next instruction when the condition does not hold, the
instruction jumps to its label when the condition holds. With them come two things they need:
instruction weights for the mutation plugins, so that the doubled number of conditional opcodes is
not drawn disproportionately, and a cleanup of what an instruction family is.

## Problem

Every conditional today skips the next instruction when its condition does not hold. A
conditional jump is written as a pair:

```
IFI %DR0 DATA:10      ; executes the next instruction only when equal
JMPI TARGET           ; ...which jumps
```

This pair is the dominant idiom of the code organisms run. In the two primordials 69 of 75
(`shell-first`) and 72 of 80 (`classic`) conditionals are followed directly by a `JMPI`
(counted by script over `assembly/primordial/`). The instruction set has no single instruction for
it, so every such decision costs two instructions, two ticks and two cells of code, and it offers
mutation the pair's own channels: the conditional's opcode can flip to a data instruction of
another family (the jump then always happens), the `JMPI` can flip to `CALL` (operation flip),
`SKJI` or `PSLI` (family flip), `JMPR` or `JMPS` (variant flip), after which it practically never
jumps, and an insertion into padding between the two makes the
jump unconditional.

Estimated from the default substitution weights in `reference.conf` (CODE 1.0 with flip weights
0.7/0.2/0.1, REGISTER 0.2, DATA 2.0 × scalar slot 2.0, LABELREF 1.0), about 31 % of the
substitution weight that lands on an `IFI` + `JMPI` decision has an abrupt effect (the two opcodes
and the register); for a single conditional jump it is about 16 %, because the jump has no
family-flip partner for most operand lists and the `JMPI` cell is gone. The graded channel, the
immediate with the largest weight, is the same in both encodings. This is an estimate from the
weights, not a measurement.

## Options considered

| Option | Why not chosen |
|---|---|
| Do nothing | The pair stays the only way to express a conditional jump; an experiment comparing the two encodings is impossible. |
| Drop the idea | Same as doing nothing; no measurement shows the pair to be harmless. |
| Remove the padding between conditional and `JMPI` in the primordials | Removes only the insertion channel; the opcode flips of both instructions stay. |
| Conditional jumps **instead of** conditional skips | A skip that guards a single instruction (6 of 75 and 8 of 80 conditionals in the primordials) would need a label, a label reference and a label lookup. |
| Conditional jumps as new operations of the conditional family (the earlier version of this document) | Skip and jump could then be told apart in the configuration only opcode by opcode, and the analysis would count both as one class. |
| The compiler replaces a conditional skip before `CALL`/`RET` by a negated conditional jump | Puts jumps into genomes whose source uses skips; the encoding of a program must be the one its source names. |
| **Conditional jumps alongside the conditional skips, as their own family** | Chosen. |

## Solution

### Terms

*Conditional* is the umbrella term. The two kinds are the **conditional skip** (the existing
instructions, `IFI` …) and the **conditional jump** (`JFI` …), the terms of established
instruction sets (conditional skip: PIC, AVR; conditional jump: x86 `Jcc`). The project calls
`JMPI` a jump, so the new kind is a jump, not a branch.

### The instructions

- **Every conditional operation has a jump twin.** All 28 operations get one, 76 opcodes in all.
  A conditional operation added later gets its jump twin in the same change.
- **Names**, at most four characters like every opcode:
  - the leading `I` of `IF…`/`IN…` becomes `J`: `IFI` → `JFI`, `INR` → `JNR`, `IFSL` → `JFSL`;
  - `LT`, `GT`, `LET`, `GET` become `J` + `LT`/`GT`/`LE`/`GE`: `LTR` → `JLTR`, `GETI` → `JGEI`;
  - the leading `P` of the probabilistic comparisons becomes `Q`: `PGTI` → `QGTI`.

  No name collides with an existing opcode.
- **Operands**: the operands of the skip twin, followed by a `LABEL` operand, the target last as in
  established instruction sets (RISC-V `BEQ rs1, rs2, offset`).
- **Polarity**: the jump is taken when the condition holds, as the skip executes the next
  instruction when the condition holds (x86 `JE`, RISC-V `BEQ`).
- **Condition does not hold**: execution continues with the next instruction; no label is looked up.
- **Condition holds**: the label is resolved with `resolveLabelTarget` from the instruction's
  position, as `JMPI` resolves it, and execution continues behind the label. Without a matching
  label the instruction fails as `JMPI` does: error penalty, and the instruction pointer advances
  normally.
- **Cost**: the thermodynamic default of every instruction; no override.

| Conditional skip | Conditional jump | Operation | Index | Condition operands (the jump adds LABEL) |
|---|---|---|---|---|
| `IFR` / `INR` | `JFR` / `JNR` | 0 / 1 | 0 / 1 | REGISTER, REGISTER |
| `IFI` / `INI` | `JFI` / `JNI` | 0 / 1 | 2 / 3 | REGISTER, IMMEDIATE |
| `IFS` / `INS` | `JFS` / `JNS` | 0 / 1 | 4 / 5 | STACK, STACK |
| `LTR` / `GETR` | `JLTR` / `JGER` | 2 / 5 | 6 / 7 | REGISTER, REGISTER |
| `LTI` / `GETI` | `JLTI` / `JGEI` | 2 / 5 | 8 / 9 | REGISTER, IMMEDIATE |
| `LTS` / `GETS` | `JLTS` / `JGES` | 2 / 5 | 10 / 11 | STACK, STACK |
| `GTR` / `LETR` | `JGTR` / `JLER` | 3 / 4 | 12 / 13 | REGISTER, REGISTER |
| `GTI` / `LETI` | `JGTI` / `JLEI` | 3 / 4 | 14 / 15 | REGISTER, IMMEDIATE |
| `GTS` / `LETS` | `JGTS` / `JLES` | 3 / 4 | 16 / 17 | STACK, STACK |
| `IFTR` / `INTR` | `JFTR` / `JNTR` | 6 / 7 | 18 / 19 | REGISTER, REGISTER |
| `IFTI` / `INTI` | `JFTI` / `JNTI` | 6 / 7 | 20 / 21 | REGISTER, IMMEDIATE |
| `IFTS` / `INTS` | `JFTS` / `JNTS` | 6 / 7 | 22 / 23 | STACK, STACK |
| `IFMR` / `INMR` | `JFMR` / `JNMR` | 8 / 9 | 24 / 25 | REGISTER |
| `IFMI` / `INMI` | `JFMI` / `JNMI` | 8 / 9 | 26 / 27 | VECTOR |
| `IFMS` / `INMS` | `JFMS` / `JNMS` | 8 / 9 | 28 / 29 | STACK |
| `IFPR` / `INPR` | `JFPR` / `JNPR` | 10 / 11 | 30 / 31 | REGISTER |
| `IFPI` / `INPI` | `JFPI` / `JNPI` | 10 / 11 | 32 / 33 | VECTOR |
| `IFPS` / `INPS` | `JFPS` / `JNPS` | 10 / 11 | 34 / 35 | STACK |
| `IFFR` / `INFR` | `JFFR` / `JNFR` | 12 / 13 | 36 / 37 | REGISTER |
| `IFFI` / `INFI` | `JFFI` / `JNFI` | 12 / 13 | 38 / 39 | VECTOR |
| `IFFS` / `INFS` | `JFFS` / `JNFS` | 12 / 13 | 40 / 41 | STACK |
| `IFVR` / `INVR` | `JFVR` / `JNVR` | 14 / 15 | 42 / 43 | REGISTER |
| `IFVI` / `INVI` | `JFVI` / `JNVI` | 14 / 15 | 44 / 45 | VECTOR |
| `IFVS` / `INVS` | `JFVS` / `JNVS` | 14 / 15 | 46 / 47 | STACK |
| `IFER` / `INER` | `JFER` / `JNER` | 16 / 17 | 48 / 49 | — |
| `IFSL` / `INSL` | `JFSL` / `JNSL` | 18 / 19 | 50 / 51 | LOCATION_REGISTER |
| `PGTR` / `PLER` | `QGTR` / `QLER` | 20 / 21 | 52 / 53 | REGISTER, REGISTER |
| `PGTI` / `PLEI` | `QGTI` / `QLEI` | 20 / 21 | 54 / 55 | REGISTER, IMMEDIATE |
| `PGTS` / `PLES` | `QGTS` / `QLES` | 20 / 21 | 56 / 57 | STACK, STACK |
| `PLTR` / `PGER` | `QLTR` / `QGER` | 22 / 23 | 58 / 59 | REGISTER, REGISTER |
| `PLTI` / `PGEI` | `QLTI` / `QGEI` | 22 / 23 | 60 / 61 | REGISTER, IMMEDIATE |
| `PLTS` / `PGES` | `QLTS` / `QGES` | 22 / 23 | 62 / 63 | STACK, STACK |
| `IFBR` / `INBR` | `JFBR` / `JNBR` | 24 / 25 | 64 / 65 | REGISTER |
| `IFBI` / `INBI` | `JFBI` / `JNBI` | 24 / 25 | 66 / 67 | VECTOR |
| `IFBS` / `INBS` | `JFBS` / `JNBS` | 24 / 25 | 68 / 69 | STACK |
| `IFXR` / `INXR` | `JFXR` / `JNXR` | 26 / 27 | 70 / 71 | REGISTER |
| `IFXI` / `INXI` | `JFXI` / `JNXI` | 26 / 27 | 72 / 73 | VECTOR |
| `IFXS` / `INXS` | `JFXS` / `JNXS` | 26 / 27 | 74 / 75 | STACK |

### The runtime

- **`AbstractConditionInstruction extends Instruction`** (`runtime.isa.instructions`) holds the
  evaluation of every condition, `holds(...)`. Its `execute` follows the pattern every instruction
  follows today: `resolveOperands` reads all operands the registration names, a failed resolution
  returns, and an operand count other than the expected one calls `instructionFailed` ("Invalid
  operand count"), which is also how an empty stack is caught. The expected count comes from the
  condition: each `Condition` value carries the number of values its test takes (`EQUAL` 2, `MINE`
  1, `PREVIOUS_FAILED` 0); a skip expects that number, a jump one more for its label. The method
  then evaluates the condition on the first operands and hands the result to an abstract method
  that the two concrete classes implement; the jump reads its label, the last operand, only when
  the condition holds. The protected helpers of `Instruction` the conditions need (`resolveOperands`,
  `isPassable`, `isTargetAccessible`, `dataPointerInsideWorld`) stay where they are.
- **`Condition`**, an enum in its own file beside it, has one value per test: `EQUAL`,
  `LESS_THAN`, `GREATER_THAN`, `TYPE_EQUAL`, `MINE`, `PASSABLE`, `FOREIGN`, `VACANT`,
  `PREVIOUS_FAILED`, `HOLDS_POSITION`, `GREATER_THAN_DRAW`, `LESS_THAN_DRAW`, `WITHIN_BODY`,
  `EXISTS`. Every opcode is registered with its condition and whether it is negated; `INI` is
  `EQUAL` negated, and so is `JNI`. The evaluation branches on the condition, not on the
  opcode name: the name comparisons `ConditionalInstruction.execute` runs on every execution today
  disappear.
- **`regPair` moves to `AbstractConditionInstruction`** and serves both classes: one call
  registers both opcodes of a pair with their condition, not negated and negated, and enters each
  as the other's negation in one table by name, as it does today. The compiler's question for a
  negation reads that table.
- **`ConditionalSkipInstruction`** (today's `ConditionalInstruction`, renamed) skips the next
  instruction when the condition does not hold.
- **`ConditionalJumpInstruction`** jumps when the condition holds. Each opcode carries the
  operation and the index of its skip twin.
- **`jumpTo`** moves from `ControlFlowInstruction` (private) to `Instruction` (protected), beside
  `resolveLabelTarget`, so that `JMPI` and every conditional jump land through the same code.

A new condition is one enum value and its branch in `holds`; skip and jump get it together.

### Families

A family is an instruction class. The number in the lowest five bits of an opcode ID identifies
the class within that ID.

- **`Family.java` stays the explicit central table** of these numbers, one named entry per class.
- **`registerOp` rejects** a number already taken by another class, and a class registering under
  a second number, naming both. Until now nothing enforced it, and `StackInstruction` registers
  under `DATA` beside `DataInstruction` (`Instruction.init()`).
- **`StackInstruction` gets `STACK = 10`**; its indices 8–11 and operations 3–6 become 0–3. The
  opcode IDs of `DUP`, `SWAP`, `DROP` and `ROT` change. The flip pools do not: the stack
  instructions take no operands, every instruction of `DataInstruction` does, so they never shared
  a pool.
- `CONDITIONAL` is renamed `CONDITIONAL_SKIP`; `CONDITIONAL_JUMP = 11` is new.
- Decision 26 of `PROBABILISTIC_CONDITIONALS` stands: the registry is the only source of an
  instruction's family, operation and operand list. The family is now the class.

### Control flow declared at registration

The code that writes into genomes asks today for the family to learn how execution moves:
`GenomeFlow` asks for `CONDITIONAL` (can skip the next instruction) and `CONTROL` (its label
operand is a jump target), the insertion asks for `CONDITIONAL` (execution may not go on behind
it), the trace tool for `CONDITIONAL`. A label addressed only by conditional jumps would count as
a place for data, and the trace tool could not tell a jump from any other instruction.

Each instruction declares its behaviour when it registers, as `declareNeverFallsThrough` does today:

| Declaration | Declared by | Asked by |
|---|---|---|
| `declareSkipsNext` | every conditional skip | `GenomeFlow.reachedByFallThrough`, `GenomeFlow.endsOpen`, insertion (`goesOnBehind`), trace tool |
| `declareJumpsConditionally` | every conditional jump | trace tool |
| `declareLabelIsJumpTarget` | `JMPI`, `CALL`, every conditional jump | `GenomeFlow.isControlFlowOperand` |

`goesOnBehind` becomes: no skip, not never-falls-through. A conditional jump goes on behind: a
label entry gives the inserted instruction's own label operand the value A' of the closing jump
(`GeneInsertionPlugin.appendInstruction`), so an inserted jump reaches the block on both paths. It
is neutral when inserted and becomes a real conditional exit once its label reference drifts onto
another label. No code outside
`Instruction.init()` and the tests reads a `Family` constant afterwards.

### The mutation plugins

The plugins' logic stays as it is. This change only keeps the 152 conditional opcodes from being
drawn disproportionately.

**Instruction weights.** Insertion and substitution draw opcodes in proportion to a weight. The
weight of an opcode is the weight of its class times its own weight; 0 excludes it.

```hocon
instructionWeights {
  default = 1                     # weight of every class not named under families
  families = [
    { class = "org.evochora.runtime.isa.instructions.ConditionalSkipInstruction", weight = 0.5, default = 1 }
    { class = "org.evochora.runtime.isa.instructions.ConditionalJumpInstruction", weight = 0.5, default = 1 }
  ]
}
```

- `default` at the top is the weight of every class not named; 1 lists what is excluded
  (blacklist), 0 lists what is included (whitelist), and stays closed against classes added later.
- `families` is a list: HOCON replaces a list as a whole, so a configuration that sets it gets
  exactly what it writes, with nothing merged in from `reference.conf`. A class listed twice is
  rejected.
- A listed class carries `class`, its full name, `weight`, its factor, and `default`, the weight of
  its opcodes not named; `opcodes { NAME = w }` names single opcodes.
- The code has no defaults: the top `default`, and `class`, `weight` and `default` of every listed
  class, are required. A missing key, an unknown class, an unknown opcode and an opcode under a class it
  does not belong to stop the start with a message naming them.
- Families are named by their full class name, as the thermodynamic overrides name them.

**Where the weights apply.**

- *Insertion*: every entry, instruction and label entry alike, has its own `instructionWeights`
  and draws its opcode by it. The `instructions` key of an entry is removed; a whitelist expresses
  what a list expressed. A label entry still leaves out every instruction behind which execution
  may not go on (conditional skips, `JMPI`, `JMPR`, `JMPS`, `RET`), whatever their weight;
  conditional jumps it may insert. An entry left without an opcode of weight above 0 stops the
  start, as an empty list does today, with a message that names the two ways out: remove the
  entry, or give it weights that leave an opcode.
- *Substitution*: one `instructionWeights` at plugin level. The weights steer which opcode a flip
  produces: the target is drawn in proportion to its weight among the flip's alternatives. Which
  cell is mutated does not change. A flip whose alternatives all weigh 0 changes nothing and
  records nothing, as a flip without alternatives does today.

**Where the shared block lives.** One block in `pipeline.services.simulation-engine.options`,
directly beside `plugins`, the deepest named place both plugins can reach (HOCON paths cannot
address list elements). Every insertion entry and the substitution refer to it:

```hocon
instructionWeights = ${pipeline.services.simulation-engine.options.instructionWeights}
```

An experiment configuration that replaces the `plugins` list carries these references itself.
The comment above the block says what it is and how it works.

**Defaults.** Both conditional classes weigh 0.5. Counted from the opcodes (185 others, 76 skips,
76 jumps), conditionals keep about 30 % of what a wildcard insertion inserts, about 15 % each. A
configuration comparable with runs before this change sets the skip class to 1 and the jump class
to 0.

**Flips that appear.** The substitution's family flip takes an opcode of another class with the
same operand list. Two groups of jumps find partners: `JFER`/`JNER` (label only) with `JMPI`,
`CALL`, `SKJI` and `PSLI`; `JFSL`/`JNSL` (location register, label) with `LRLI`. They stay
allowed. A jump can thus arise in a genome written with skips only, through a flip of a `JMPI`,
whenever the jump class weighs more than 0.

### The compiler

The compiler never changes the encoding the source names. It rewrites only where it must:

- a conditional skip directly before a `CALL` or `RET` is replaced, as today, by
  its negation and a `JMPI` around the whole marshalling sequence, because a skip acts on the next
  machine instruction and the call has become several;
- a conditional jump before a `CALL` or `RET` passes through unchanged: it acts on its own label,
  and falling through runs the whole sequence.

The compiler needs no change for the jumps: a label operand is translated generically.
`IInstructionSet.negatedConditional` is renamed `negatedConditionalSkip`; its signature stays, and
it answers for skips only. `.IF` (`CONTROL_FLOW_DIRECTIVES`) will use the negation of the jumps: `.IF` with a skip
becomes negated skip + `JMPI`, with a jump the negated jump.

### Analysis and tools

- `Instruction.getInstructionSetInfo` reports the class that registered an opcode as its family;
  it no longer climbs the class hierarchy to the class directly below `Instruction`, which would
  report `AbstractConditionInstruction` for both kinds.
- `InstructionUsagePlugin` names its columns after the classes; `conditional` becomes
  `conditionalskip`, `conditionaljump` is new.
- The trace tool's `cond_met` means "the condition held" for both kinds: for a skip 1 when the
  instruction behind it ran next, for a jump 1 when the next step began elsewhere. "Behind it" is
  the first instruction after the empty cells that follow, as the machine passes them, so padding
  no longer turns the column wrong. A step that failed leaves `cond_met` empty: the failure and its
  reason are in `failed` and `fail_reason`. A jump whose label stands directly behind it reports 0
  even when its condition held; both paths lead to the same place, and the README says so.

### Documentation

- `docs/ASSEMBLY_SPEC.md`: conditional skips renamed; a section on conditional jumps with syntax,
  polarity, the failure without a label, and the table of all pairs.
- `docs/EVOASM_GUIDELINES.md` and the `evoasm` skill: the conditional jump beside skip + `JMPI`.
- `tools/trace/README.md` and the `evoasm` skill: `cond_met` for both kinds; the warning about
  padding is removed.
- `docs/proposals/compiler-enhancements/CONTROL_FLOW_DIRECTIVES.WIP.md`: names of the jumps and the
  compiler rule above.
- `docs/proposals/README.md`: this document and its dependencies.
- `notebooks/data_analysis_guide.ipynb`: the `instruction_usage` columns.
- `.claude/skills/analyze-run/SKILL.md`: the skip semantics and the analysis of inserted
  conditionals, extended by the jumps.
- `reference.conf` and `config/evochora.conf`: the shared block, the references, the entries
  without `instructions`.

The primordials stay as they are; a primordial written with jumps is a work of its own.

## Consequences for experiments

- Default runs insert half as many conditional skips as before (about 15 % instead of about 30 %
  of wildcard insertions) and as many conditional jumps.
- The opcode IDs of `DUP`, `SWAP`, `DROP` and `ROT` change; programs are compiled by name and
  persisted runs are read with the build that wrote them.
- The weighted draws consume the random source differently; no run replays bit for bit against a
  build before this change, whatever the weights.

## Pitfalls

- **A primordial with jumps and the jump class at 0**: its jumps change only where a flip leads into
  another class — `JFER`/`JNER` to `JMPI`, `CALL`, `SKJI`, `PSLI`, `JFSL`/`JNSL` to `LRLI`; every
  other flip of a jump finds only targets of weight 0 and changes nothing.
- **An opcode written under the wrong class** would silently keep the default of its own class
  if it were accepted; the start rejects it.
- **Hot path**: `ConditionalSkipInstruction` changes from name dispatch to dispatch on the
  condition. Expected neutral or faster (an array lookup per opcode instead of several string
  comparisons per execution), not measured; the JMH tick benchmark decides.

## Implementation

Strictly in this order; every step leaves the build green.

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | Family is the class | `Family.java` (`STACK`; Javadoc: one entry per class, no claim of never-changing values, `DATA` without the stack instructions), `Instruction` (`init`, the check in `registerOp`), `StackInstruction` (indices, operations 0–3), `InstructionRegistryTest` | `./gradlew test --tests '*InstructionRegistry*'`, then `./gradlew test` | a second class under a taken number and a class under two numbers are rejected; four stack IDs changed, nothing else |
| 2 | Control flow declared at registration | `Instruction` (three declarations and their queries), `ConditionalInstruction`, `ControlFlowInstruction`, `GenomeFlow` (also its class Javadoc), `GeneInsertionPlugin`, `GeneDuplicationPlugin` (reads `GenomeFlow`), `tools/trace/consumer/TraceConsumer`, `GenomeFlowTest`, `GeneInsertionPluginTest`, `GeneDuplicationPluginTest` | `./gradlew test --tests '*GenomeFlow*' --tests '*GeneInsertion*'`, then `./gradlew test` | no `Family` constant read outside `init()` and tests; behaviour unchanged |
| 3 | Conditions in one place, skips renamed | `Family.java` (`CONDITIONAL` → `CONDITIONAL_SKIP`), `Instruction.buildInstructionSetInfo` (registering class as family), `AbstractConditionInstruction`, `Condition`, `ConditionalSkipInstruction` (from `ConditionalInstruction`), `IInstructionSet`, `RuntimeInstructionSetAdapter`, `CallerMarshallingRule`, `ProcedureMarshallingRule`, `Molecule` (Javadoc), the conditional and marshalling tests, `CallSiteBindingRuleTest` (stub of the interface), `RuntimeInstructionSetAdapterTest`, `ConditionalNegationTest` | `./gradlew test`; JMH tick benchmark before and after (`benchmark` skill) | no opcode name compared in an execution; tests unchanged except for names; benchmark shows no regression |
| 4 | Conditional jumps | `Family.java` (`CONDITIONAL_JUMP`), `Instruction` (`jumpTo`), `extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json` (the editor's list of opcode names, held to the registry by `SyntaxHighlightingRulesTest`), `ControlFlowInstruction`, `ConditionalJumpInstruction`, `Instruction.init()`, new `VMConditionalJumpInstructionTest`, `InstructionRegistryTest`, `GenomeFlowTest`, `GeneInsertionPluginTest`, `GeneSubstitutionPluginTest`, a compiler test | `./gradlew test` | per condition: holds → jumps, does not hold → falls through; label missing while holding → fails with penalty; label missing while not holding → no failure; 76 opcodes, names ≤ 4 characters, twin operation and index; a jump before `CALL` with parameters passes the compiler unchanged; a label entry may insert a jump, whose label operand is A'; a label addressed only by jumps is a jump target; `JMPI` can flip to `JFER` |
| 5 | Instruction weights | weight configuration reader in `runtime.worldgen` (`InstructionWeights`, with `WeightedOpcodes` for the weighted draw), `GeneInsertionPlugin` (per entry, `instructions` removed), `GeneSubstitutionPlugin` (weighted flip targets, `instructionWeights` among the known keys), `reference.conf`, `config/evochora.conf`, plugin tests, `ResumeNeutralityHarness`, `MutationHistoryIndependenceTest`, `ShippedConfigurationTest` (builds both plugins from the shipped configuration) | `./gradlew test --tests '*GeneInsertion*' --tests '*GeneSubstitution*'`, then `./gradlew check` | blacklist and whitelist select as specified; an entry without an opcode of weight above 0 stops the start with both ways out named; every missing or unknown key rejected with its name; class weight 0 with opcode weights inside is silent; defaults only in the configuration files |
| 6 | Documentation and tools | the documents listed above, `tools/trace/consumer/TraceConsumer` (`cond_met` for jumps, empty cells passed), `.claude/skills/evoasm` | a trace of a primordial: `cond_met` of a padded `PGTI` matches the step that followed; review of the diff | every document uses the new terms and names |

## Decisions

1. Conditional jumps are introduced alongside the conditional skips, not instead of them.
2. Every one of the 28 conditional operations gets a jump twin; a new operation gets its twin in
   the same change.
3. Names: `I` → `J` for `IF…`/`IN…`, `J` + `LT`/`GT`/`LE`/`GE` for `LT`/`GT`/`LET`/`GET`, `P` → `Q`
   for the probabilistic comparisons; at most four characters.
4. The jump is taken when the condition holds.
5. Without a matching label a holding jump fails as `JMPI` does; a condition that does not hold
   looks up no label.
6. Own class `ConditionalJumpInstruction`; conditions evaluated once, in
   `AbstractConditionInstruction`, by a `Condition` fixed per opcode at registration.
7. Terms: conditional skip and conditional jump; classes `ConditionalSkipInstruction` and
   `ConditionalJumpInstruction`, families `CONDITIONAL_SKIP` and `CONDITIONAL_JUMP`,
   `negatedConditionalSkip`.
8. A family is an instruction class; `Family.java` stays the explicit table; `registerOp` enforces
   one number per class; `StackInstruction` gets `STACK = 10`.
9. Control flow is declared at registration (skips next, jumps conditionally, label is a jump
   target); nothing asks the family for it.
10. The family flips between `JFER`/`JNER` and `JMPI`/`CALL`/`SKJI`/`PSLI` and between
    `JFSL`/`JNSL` and `LRLI` stay allowed.
11. The compiler never changes the encoding; skips before `CALL`/`RET` are rewritten as today,
    jumps are not.
12. `jumpTo` moves to `Instruction` as protected.
13. Instruction weights: class weight × opcode weight, nested, `default` on both levels, no
    defaults in code, families by full class name, camelCase.
14. Insertion: weights per entry, the `instructions` list is removed. Substitution: weights at
    plugin level, steering only which opcode a flip produces.
15. One shared block beside `plugins` in the simulation engine's options, referenced by every
    user; both conditional classes default to 0.5.
16. The mutation plugins' logic is otherwise unchanged.
17. Primordials unchanged.
18. The trace tool's `cond_met` means "the condition held" for both kinds and passes empty cells
    behind the instruction as the machine does.
19. `getInstructionSetInfo` reports the registering class as the family.
20. `families` in `instructionWeights` is a list, so that an experiment configuration replaces it
    as a whole.
21. Label entries may insert conditional jumps; they leave out conditional skips and the
    instructions that never fall through, as today.
22. An insertion entry left without an opcode of weight above 0 stops the start, naming the ways
    out.
23. Operand count checked as every instruction checks it today; the expected count is carried by
    the `Condition`, plus one for the label of a jump.
24. One negation table by name in `AbstractConditionInstruction`, filled by the shared `regPair`
    for both classes.
25. `cond_met` stays empty for a step that failed.

## Outcome

Implemented in the six steps above. What the built code does beyond the solution text:

- **The family constants arrive with their classes.** `CONDITIONAL_SKIP` came with
  `ConditionalSkipInstruction` in step 3 and `CONDITIONAL_JUMP` with `ConditionalJumpInstruction` in
  step 4, so that step 1 touched no file whose family checks step 2 replaced.
- **The editor knows the jumps.** The opcode list of the VS Code grammar
  (`extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json`), which
  `SyntaxHighlightingRulesTest` holds to the registry, carries the 76 names.
- **The weighted draw is one class.** `WeightedOpcodes` holds the opcodes of weight above zero with
  their running sums; the insertion and the substitution draw through it.
- **The shipped configuration is built in a test.** `ShippedConfigurationTest` builds both
  mutation plugins from `config/evochora.conf` over `reference.conf`, with the weights they refer to.
- **Two declarations, not three.** `declareJumpsConditionally` lost its last reader in the
  runtime when label entries were allowed to insert jumps; it is removed, and the trace tool tells
  a skip from a jump by the instruction's class.
- **`cond_met` stays empty where it cannot know.** A jump with a label between it and the next
  instruction reaches that instruction either way; its `cond_met` is left empty instead of 0.
- **No constructor serves only the tests.** The insertion and the substitution had a
  package-private constructor that only tests called; both are removed, with
  `InstructionWeights.uniform()`, and the tests build the plugins from configuration.
- **One text for a wrong operand count.** Step 3 kept the old failure texts word for word, so that a
  run could show the rebuilt evaluation to behave identically; afterwards every conditional fails
  with "Invalid operand count for <name>".
- **Measured.** Step 3 against step 2 on the benchmark host: JMH `SKIP` and `REALISTIC` at 100, 500
  and 2000 organisms from +0.5 % to +6.7 %, two of six outside the error; a real run of 5 million
  ticks in two rounds gave the same `TICKHASH` on both sides and 215 to 220 seconds each.
- **The trace shows a padded decision.** In a 2000-tick trace of the primordial, `cond_met` of the
  `PGTI` in `main.evo`, with `NOP^4` behind it, matched the step that followed.

Not done: a primordial written with conditional jumps. Considered for this change and left out
(decision 17 stands); conditional compilation can give one source both encodings, through macros
with a fixed number of parameters each, since a macro takes exactly as many arguments as it names.
