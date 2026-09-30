# Separating an Organism's Failure from a Fault in the Runtime

**Status: AGREED — every decision below was made with the maintainer on 2026-09-29/30; implementation pending.**

Issues: #195 (the catch-all), #165 (the same catch-all seen from the logging side; closed as a duplicate
once this is implemented), #148 (bounded worlds; implemented here because its decisions turned out to be
few once the fault rule was settled).

## Problem

An instruction can fail for two reasons that must be told apart:

- **An organism's failure.** Wrong operand type, empty stack, a cell it may not touch. Mutated code fails
  all the time; that is the simulation working. The instruction is marked failed with a reason, the
  organism pays the error penalty, nothing is logged.
- **A fault in the runtime.** A defect in an instruction, a helper, a plugin. It must reach the developer
  with its stack trace, and the run must not go on producing data from a defective state.

Today the two are mixed at two levels:

- `VirtualMachine.execute` ends in `catch (Exception e)`. Whatever an instruction throws is booked as
  `"VM Runtime Error: <exception>"`, charged the penalty and forgotten. Nothing is logged.
- Eight instruction families catch `NoSuchElementException`, `ClassCastException` and
  `ArrayIndexOutOfBoundsException` inside their own `execute` and book them as an organism's failure.
  Their `try` blocks also enclose environment access, so a defect throwing one of those types inside
  the environment is charged to the organism as "Invalid operand types".

A read-only audit of all eleven families (2026-09-29, on `main` at 7810a9ce) found 24 paths an organism
can reach that throw today: 4 escape the tick and stop the engine (all in bounded worlds or worlds with
an odd number of dimensions), 10 end in the catch-all, 10 are caught by a family catch. Six of the eight
family catch blocks are dead code: nothing reachable throws the type they catch.

## The rule

**An organism's failure is never an exception.** Everything an organism can do wrong is detected by an
explicit check and reported through `organism.instructionFailed(reason)` followed by a return. No
instruction, helper or accessor throws for it, and no `catch` turns an exception into a failure.

**Every exception is therefore a fault in the runtime.** It ends the run and is logged once, with its
stack trace and the tick, organism and instruction it happened in.

The separation is structural — by channel, not by exception type — so it does not depend on throwing the
right type at the right place. The penalty keeps one meaning: the organism did something it may not do.
A fault carries no evolutionary cost because it is not the organism's doing; and since the run ends, the
question of what the organism pays does not arise.

## Decisions

### D1. The catch-all goes; a fault ends the run

`VirtualMachine.execute` loses its `catch (Exception e)` and the inner `catch` that killed the organism
with `"Fatal VM Error"`. The exception leaves the tick. `TickWorkerPool` already rethrows a
`RuntimeException` from a worker unchanged; `SimulationEngine.run` lets it pass; `AbstractService` puts
the service into `ERROR`. The node stays up and reports the service as unhealthy.

No data from the faulty state is persisted: only complete chunks are written, and the chunk in progress is
regenerated from the checkpoint on resume. The run is continued with the corrected build through
`node resume`. `BuildRevisionCheck` warns that another build continues the run — that warning is the
intended information. From the fault's tick on, the corrected build behaves as the run was meant to; if
the correction also changes behaviour before that tick, the operator decides whether to record the run
again from the start.

The fault is booked nowhere in the organism: no failed flag, no reason, no penalty. The organism's state
after a fault is not a state any complete tick produces, and it is discarded with the tick.

### D2. One exception type carries the context

`org.evochora.runtime.SimulationFault extends IllegalStateException`, modelled on `ParallelWaveViolation`.
Its message names the tick, the organism id, the instruction (name and full opcode id) and the instruction
pointer; the original exception is its cause.

It is raised at the places where one organism's work is done and its context is known — the call of
`vm.plan`, the execution of an instruction together with the skip that follows it, and the target
resolution of one instruction in conflict resolution — by a `try`/`catch (RuntimeException)` that wraps
and rethrows. A `try` block that never throws costs nothing; the catch-all it replaces had one already.
A `ParallelWaveViolation` is wrapped like any other exception; it stays visible as the cause and still
ends the run.

The tick number comes from `Simulation`, not from the engine's counter, which advances only after the
tick.

### D3. The fault is logged once, at the top

`AbstractService` logs an exception from `run()` at `ERROR` **with the stack trace**. Today it logs the
exception's class name at `ERROR` and the trace at `DEBUG`, which the logging rule in AGENTS.md
("At ERROR, not DEBUG") already forbids. Nothing below it logs the fault; the runtime has no logger for it.

### D4. Plugins, handlers and interceptors fail like instructions

The four `catch (Exception e)` blocks in `Simulation` — tick plugins, birth handlers, instruction
interceptors, death handlers — go. A defect in a plugin is a fault like a defect in an instruction: it is
wrapped in a `SimulationFault` whose message names the plugin or handler class, the tick and, where there
is one, the organism, and it ends the run. Today those blocks log `WARN` with `e.getMessage()` and let a
half-applied mutation or a half-run handler stay in the run.

### D5. Every path an organism can reach gets an explicit check

Found by the audit and to be confirmed by the systematic test (see Verification):

- **State family.** `StateInstruction.execute` does not check `isInstructionFailed()` after
  `resolveOperands`, unlike every other family, so an invalid register id — failed during planning, with
  `readOperand` returning `null` — reaches a cast or an unboxing and throws `NullPointerException`
  (TURN, FORK, RAND, SEEK, SCAN). The check is added. The eight unchecked casts of the family
  (`(int[])` in TURN, FORK, SEEK, SEKS, SCAN, SCNS; `(Integer)` in FORK, RAND) become `instanceof` checks.
- **Family catches.** All twelve `catch` blocks in the eight families go. Six families' catches are dead
  already; the State and Vector catches are replaced by the checks above and by D7.
- **Failure texts.** The new checks name the instruction and what it needed, in the style of the other
  families (`"TURN requires a vector operand"`), not the generic `"Invalid operand types for state
  instruction."` they replace. The text is what a failure shows in the analyzer's failure table.

### D6. An unknown opcode pays like every failed instruction

`VirtualMachine.plan` hands an unknown opcode id to the `NopInstruction` it builds, and
`ThermodynamicPolicyManager.getPolicy` indexes an array of length "highest registered opcode + 1" with it,
without a bounds check — its comment claims the virtual machine lets nothing unknown through. An id
outside the array throws `ArrayIndexOutOfBoundsException` into the catch-all, where the instruction pays
the penalty but no base cost; an unregistered id inside the array pays base cost and penalty. Any `POKE`
of a CODE value and any mutation can produce either.

`getPolicy` gets the bounds check: an id outside the array resolves its policy by instruction class,
without caching. Every unknown opcode then pays base cost and penalty. The instruction keeps the unknown
id, which the recorded data needs (the visualizer shows `UNKNOWN [DATA:42]`). The comment is corrected.

### D7. `UnitVector.nearest` computes in `long`

The sum of absolute components and the largest absolute component are `int` today. `Math.abs(MIN_VALUE)`
is negative and the sum overflows; vector `ADD`/`SUB` produce such components (twelve doublings of a
register). Three consequences, one cause: in a world with an odd number of dimensions no candidate axis
is found and `nearest[-1]` throws; a sum overflowing to 0 passes as the zero vector, so `toDisplacement`
returns the vector unchanged and the data pointer reaches 2^31 cells away; a sum overflowing to 1 passes
as a unit vector, so `setDv` accepts a direction that is none. Both quantities become `long`; every vector
except the true zero vector then maps to a unit vector.

### D8. Bounded worlds (#148)

In a world with topology `BOUND`, the instruction pointer, every data pointer and the birth position lie
inside the world at every moment. A step that would leave the world is the organism's failure. `BOUND`
has never been used in a production run; this is what makes it usable.

- **Instruction pointer advance and skip.** When the advance after an instruction, or the skip over
  non-code cells, would cross the edge, the instruction is booked failed and the stall recovery moves the
  pointer on at once, as it does today when the skip budget is exhausted — the pointer never stands still
  on the edge repeating the same instruction. The recovery pops call frames until it finds a return
  address inside the world, and falls back to the birth position.
- **Arguments beyond the edge.** An instruction whose argument cells lie beyond the edge fails without
  executing (a cell beyond the edge would read as 0, which as a register argument is `%DR0`). The
  advance that follows crosses the edge and the recovery moves the pointer on.
- **Jumps.** A jump whose code cell — the label plus the direction of travel — lies beyond the edge fails
  like a jump that finds no label; the pointer advances past the jump.
- **`CALL` and `RET`.** A `CALL` on the last cells along the direction of travel succeeds and stores the
  return address it computes, which lies beyond the edge; the address is a coordinate like any other, only
  outside. The `RET` to it is the step that fails: it is booked failed and the stall recovery moves the
  pointer on. A return address beyond the edge is therefore legitimate frame content, on the call stack
  and in a checkpoint.
- **Data pointer.** `SEEK` and its variants fail when the target lies beyond the edge; the pointer stays.
  A cell beyond the edge is not passable, so the scan for passable neighbours does not report it.
  (Since #193, an instruction whose data pointer already lies outside fails through
  `dataPointerInsideWorld`; that path cannot fire once no pointer leaves, and it stays as a fault
  detector.)
- **`FORK`.** A child whose position lies beyond the edge is not created; the instruction fails.
- **Restore.** A checkpoint whose instruction pointer, data pointers or birth position lie outside the
  world is rejected with `InvalidRestoreState`, like every other violated invariant. Return addresses are
  checked for their dimension count only.
- **A program can ask whether a cell exists.** A cell beyond the edge reads as empty and vacant
  and is not passable, a combination no cell inside the world shows, so the edge was already
  inferable from `IFP` and `IFV`. The conditional pair `IFX*`/`INX*` (register, immediate vector,
  stack variants, like the other cell conditions) asks the question directly: it holds when the
  cell at `DP` + vector exists, which in a toroidal environment every cell does. It is what lets a
  program that meets a blockage decide between clearing a molecule and turning at the edge, and it
  closes #168 together with the invariant above.
- **The environment's guards stay.** The in-range accessors keep throwing `IllegalArgumentException` for
  a coordinate outside the world, in production, not as assertions: once no pointer leaves the world,
  such a throw is a fault in the runtime, and D1 treats it as one. An assertion would run only in tests
  and hide the defect where it matters.

All checks are skipped in a `TORUS` world, whose coordinates always wrap.

### D9. The logging rule

Recorded in AGENTS.md, "Logging Guidelines / Stack Traces": a stack trace only when the cause is a bug or
unknown; every message with a known cause without one, but carrying the messages of the cause chain. The
single-line format is strict for messages without a stack trace and does not apply to the trace itself.
The 23 log calls that pass an exception today are reviewed against the rule in this work, in a commit of
their own.

## What changes for a run

- **Every topology.** A fault in the runtime or a plugin ends the run instead of continuing. Unknown
  opcodes outside the registered range pay base cost and penalty instead of the penalty alone, so the tick
  hash of a run with mutation differs from the previous build's. Vectors whose absolute sum overflowed
  behave differently (D7). Failure texts of the State family become specific (D5). Nothing else in a
  `TORUS` run changes; no test holds a fixed tick hash.
- **`BOUND`.** A `CALL`, `FORK`, `SEEK` or jump at the edge fails where today the run ends; an organism
  walking off the edge is recovered at once instead of after the skip budget.

## Verification

- **Systematic test.** One test runs every registered instruction against every operand state the audit
  lists — a register holding a scalar where a vector is expected and the reverse, an invalid register id,
  a data stack shorter than the instruction's stack operands, a location register or stack entry holding no
  position, an all-`MIN_VALUE` vector in a 1D and a 3D world, an unregistered opcode inside and outside
  the policy array, `MR` zero and non-zero — and asserts that no exception leaves `Instruction.execute`
  and no `SimulationFault` leaves the tick. It is written first, goes red when the family catches are
  removed, and green when the explicit checks are in. It stays as the guard for every later instruction.
- **Bounded-world tests.** One scenario per movement primitive at each edge in a `BOUND` world — advance,
  skip, jump, `CALL`/`RET`, `SEEK`, `FORK` — asserting the failure, the pointer's position afterwards and
  the recovery; a restore test per rejected pointer.
- **Fault tests.** `CaptureExecutionDetailsTest` drives a throwing instruction and expects the
  `SimulationFault` with its context instead of a booked failure; a plugin test expects it for a throwing
  plugin; an `AbstractService` test expects the `ERROR` log with the trace (`@ExpectLog`).
- **Benchmark.** The JMH tick benchmark (`docs/BENCHMARKING.md`) before and after, because D8 adds a
  comparison per instruction-pointer step and a `contains` check per jump, `CALL`, `RET`, `SEEK` and
  `FORK`; the condition is that execution without a fault is not measurably slower.
- **Acceptance.** One real run with the new build under the production profile; the log must hold no
  `ERROR`. Proposed with its duration and data directory before it is started.

## Implementation order

1. AGENTS.md rules and this document.
2. D1, D2, D3 with their tests — first, because the systematic test can only go red once the
   catch-all no longer swallows what the family catches let through.
3. Systematic test (red once the family catches are removed), then D5, D6, D7 (green), with a
   targeted test per path the audit found.
4. D4 with its tests.
5. D8 with its tests.
6. D9: review of the 23 log calls.
7. Benchmark, acceptance run; #165 closed as a duplicate; this document moved to
   `docs/outdated/proposals/accomplished/`.

## Related, not part of this work

- #159 — a failed instruction changes nothing but its cost. The audit found one more case for it: when the
  data stack holds fewer values than an instruction's stack operands, the values already peeked are still
  popped by `commitStackReads`, so `ADDS` with one value on the stack consumes it and fails.
