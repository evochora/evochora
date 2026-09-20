---
name: evoasm
description: Write, change and debug EvoASM programs, the assembly the organisms run, with the trace recorder as the debugger. Use when asked to write or modify an .evo program, procedure or module, to find out what a program does in the simulation, or to show that assembly code works.
---

# Working on EvoASM programs

EvoASM is compiled to molecules laid out in the world, and an organism executes them one per
tick. Nothing about it is in any training data; the references are in the repository, and the
runtime source is the truth when the specification leaves a question open.

- `docs/ASSEMBLY_SPEC.md`: the language and every instruction.
- `src/main/java/org/evochora/runtime/isa/instructions/`: what an instruction really does.
- `assembly/primordial/`: the self-replicator, the worked example for every idiom below.
- `assembly/examples/duplicate-shell/`: a small procedure with a driver, written with this
  workflow; `tools/trace/README.md` shows the queries that verified it.

The maintainer's session rules apply throughout: a plan before code, one point per message, a
simulation run only within the bounds the maintainer has given, nothing committed on your own.
`docs/EVOASM_GUIDELINES.md` holds what this skill only points at: room for evolution, acting in
a shared world, checking a program.

## 0 · Before writing

Read the task twice and turn it into statements you can check in a trace: which cells hold what
at the end, what a result register says, which invariant holds at a label. Those statements are
the acceptance test; write them down before the first line of code.

Then read the relevant parts of the specification, and look at how the primordial does the same
kind of thing: pointer bookkeeping, marker handling, defensive writing, state persistence. Its
idioms are the house style, not because they are rules but because they are known to work under
the simulation's constraints.

## 1 · Facts about the language that are easy to get wrong

These are properties of the machine, verified in the runtime; every one of them has cost a
session an iteration.

- A conditional instruction skips the one instruction behind it when its condition is not met,
  and the skipped instruction costs no tick. Put a `JMPI` behind a conditional when more than one
  instruction depends on it.
- Equality (`IF*`, `IN*`) compares the molecule, type and value: `CODE:0` is unequal to
  `STRUCTURE:0`. Order (`GT*`, `LT*`, `GET*`, `LET*`, `PGT*`, ...) compares the numbers alone,
  whatever the types: `ENERGY:5` is greater than `DATA:3`. A test that must be sure of the type
  says so with `IFT*`/`INT*` before it orders. Every negated form is the exact opposite of its
  counterpart.
- The alias in `.IMPORT ... AS X` must not be an instruction mnemonic; `DUP` is the stack
  instruction, not a name. A register alias, `%DUP`, is fine.
- Inside an imported file `.ORG` is relative to the import position. Code runs along the
  direction vector until a jump, so long procedures are laid out in rows, each `.ORG` on its own
  line and each row ending in a jump, as the primordial does. The world's width is the hard bound.
- A label resolves by fuzzy matching among the organism's own labels first; a foreign label is
  reached only when no own label matches, and only within the configured reach. A label written
  with a non-zero marker is no target at all until the `FORK` resets the marker, so the labels
  of a copy under construction do not attract the parent's jumps. A label written with `MR` 0
  is a target at once: a second own label with the same value makes the choice between the two
  a lottery. Resolve positions into location registers before the world changes when the
  program has to be sure which one it means.
- Cells of different owners: `SEEK` enters only empty or own cells, `POKE` writes only into empty
  ones, `PEEK` clears whatever is there and pays for it; a foreign cell costs a lot to read. The
  cell tests `IFFR`, `IFMR`, `INPR` exist to decide before acting.
- A check and the write that follows it are in different ticks. A write that loses the conflict
  with another organism's write in its tick is retried by the machine on its own, the program
  never sees it. What `INER` after a `SEEK` or `POKE` reports is a failure of the instruction's
  own: an occupied target, a cell the organism may not enter.

## 2 · Writing

Common sense goes a long way; the points below are where common sense from other assemblies
misleads.

- A procedure restores on every exit path what it changed: data pointers saved on the location
  stack (`DPLS` / `SKLS`), the marker register (`GMR` / `SMR`), and it reports through a `REF`
  parameter. The abandon path of `assembly/examples/duplicate-shell/lib/duplicate.evo` shows the
  shape.
- Registers start as `CODE:0` and keep whatever was last put in them; give every register an
  alias per phase of the procedure, and initialise before use.
- The marker register decides what a `FORK` hands to a child: set it only while writing what the
  child should inherit, reset it before returning, orphan an abandoned copy with `CMR`.
- Energy is the budget: check it before something expensive starts, and prefer probabilistic
  gates (`PGTI`) over hard thresholds where a decision should be a dial.
- Code meant to evolve needs room: the mutation plugins write into empty cells between an
  organism's own cells, and a duplication needs a run of them. The primordial's `NOP` padding is
  that room, its redundant jumps and multiple returns keep a lineage alive when a mutation lands
  on one of them. A tool procedure or an experiment driver that no plugin will ever touch does
  not need any of it.
- Comments end the line and say what the instruction is for, aligned as in the primordial. A
  header comment on a procedure names its parameters, what it assumes about the world, and what
  it leaves behind on abandonment.

## 3 · Compile

```
build/install/evochora/bin/evochora compile --source-root <dir> --file <main>.evo --env <w>x<h>
```

The installation tree must be current (`./gradlew installDist`, after the benchmark check the
session rules require). A compile error names file and line; layout collisions and overlong rows
show here, semantics do not.

## 4 · Debug with the trace recorder

`tools/trace/README.md` is the reference. The recorder runs the program in a quiet world, every
tick recorded, nothing persisted, and writes three tables you query with DuckDB.

```
tools/trace/run-trace.sh <scratchpad>/trace-N <ticks> \
  'pipeline.services.simulation-engine.options.environment.shape = [128, 96]' \
  'pipeline.services.simulation-engine.options.compiler.source-roots = [{ path = "<dir>" }]' \
  'pipeline.services.simulation-engine.options.organisms = [{ program = "<main>.evo", initialEnergy = 100000, placement { positions = [0, 0] } }]'
```

Choose the smallest world that holds the program and its work (each side a multiple of 32),
and round the tick count generously: the engine drops a partial chunk of 50 ticks at the pause.
A self-replicator needs three times its own body on each side, or it has no room to place a copy
in every direction. It also needs the energy for one: a quiet world has none to harvest, a copy
of the primordial costs roughly 45 energy per body cell, and `runtime.organism.max-energy` caps
what the organism can hold, so a run that has to reach a `FORK` raises that cap and
`initialEnergy` with it.

Load `tools/trace/views.sql` first; then ask, in this order:

1. `steps`: any failures, any `differs`? Both should be zero for a program that does what it
   says, and the first row of either is where to look.
2. `steps` grouped by `src_label`: where the ticks went, and whether every part ran.
3. The acceptance statements from step 0 as queries: `world_end()` against what should be there,
   a `REF` result in `state`, an invariant at a label as a query that lists its violations.
4. When something is wrong, walk back: the cell that is wrong, the change row that last wrote
   it, the step at that tick with its `dp`, the register that held the wrong value and the last
   tick it changed (`changed` column, or `lag` over `state`), the step that set it. Where a
   conditional went shows in the step that followed it, and `changed` shows what a `SCAN` read
   in its own row.

Expect a first trace to be wrong, and expect the trace to say why within a few queries. What the
trace cannot tell you: why a fuzzy jump chose the label it chose, and which thermodynamic rule
priced a step; both need the configuration or the runtime source.

## 5 · Report

End with the numbers: the acceptance queries and their results, ticks and energy per unit of
work, failures and `differs`, the result register and the marker after every call. Then every
iteration: what the trace showed, what the cause was, what changed. When the maintainer asks to
see each iteration as it happens, stop after every finding and report before changing anything.

Close with a short section for the maintainer's decision: what about the recorder, this skill,
or the documentation would have helped in this session, concrete and from this session only.
"Nothing" is a valid answer; a proposal stays a proposal until the maintainer decides.

## Pitfalls that have cost sessions

- `.IMPORT "lib/x.evo" AS DUP` fails to parse: `DUP` is an instruction.
- `LETI %TMP ENERGY:0` does not check that `%TMP` holds energy: order ignores types, so a
  `STRUCTURE:5` passes as greater than zero. `INTI %TMP ENERGY:0` asks the type.
- Four calls that copy a frame beside each edge resolved the same corner label to a copy's
  corner once copies existed; the driver resolves the corners into location registers first.
- `cond_met` compares addresses, not conditions: it is 1 only when the next step began at the
  cell right behind the conditional. `NOP` padding sits there in every program written to evolve,
  so a padded conditional reports 0 even where its condition held. Read the decision off the next
  step row instead, whose `addr` and `src_label` say where execution went.
- Trace outputs belong in the session's scratchpad; the recorder's own README says what it
  writes and what it removes.
