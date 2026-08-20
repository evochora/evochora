# Conflict Loss: Failure Semantics and Per-Tick Priority

**Status: ACCOMPLISHED — implemented on branch `feature/deterministic-execution` (PR #105,
2026-08-20), together with [DETERMINISTIC_EXECUTION](DETERMINISTIC_EXECUTION.md).**

## Problem

When several organisms target the same cell with an environment-modifying instruction
(PEEK/POKE/PPK families — the only implementors of `IEnvironmentModifyingInstruction`) in the same
tick, conflict resolution sorts the contenders by organism ID and lets the lowest ID win
(`Simulation.java:695-707`). Every loser's instruction is marked not-executed, and
`executeSingleInstruction` returns before any accounting (`Simulation.java:526`).

A per-tick organism outcome therefore has **three** classes today: executed, failed (error penalty,
`instructionFailed` set, visible to `IFER`), and lost-conflict — which costs nothing, sets nothing,
and is serialized nowhere. Because the loser's IP does not move, it re-plans the identical
instruction on the identical target next tick. Two structural defects follow:

1. **Free, invisible starvation.** A loser pays no energy and no entropy, indefinitely. Observed at
   scale: in run `20260226-03114337`, 1 579 organisms were pinned for 140 million ticks — alive,
   zero instructions, zero failures, zero cost — behind lower-ID contenders on geyser-fed cells
   (verified end to end in
   [DEMO_RUNS_PRE_WIPE_EVALUATION §9.12](../../../experiments/DEMO_RUNS_PRE_WIPE_EVALUATION.md)).
2. **Permanent age bias.** Lowest-ID-wins means the same older organism wins the same contested cell
   every tick, forever. Starvation is not an edge case but the guaranteed outcome of persistent
   contention.

## Agreed semantics

### 1. Losing a conflict is an instruction failure — with retry

A conflict loser is treated exactly like an instruction that fails during execution, with one
deliberate difference:

- `instructionFailed` is set with its own reason (`"Lost write conflict"`,
  `VirtualMachine.LOST_WRITE_CONFLICT`), so the loss lands in the serialized `failure_reason` and
  in the existing `failure_count` analytics — no new fields, no new metrics. The failed-tick flag
  that `IFER`/`INER` read is set as for any failure; because the loser retries the same
  instruction, the guard that follows the write only runs once the write has succeeded, so the
  durable trace of a loss is the serialized failure, not a branch in the organism's control flow.
- The loser is charged like any failed instruction: base instruction cost plus
  `error-penalty-cost`, plus the base entropy. Energy and entropy death checks apply as usual.
- **The IP does not advance.** The organism retries the same instruction next tick.

Why retry instead of the ordinary advance-and-continue failure path:

- A conflict loss is unlike other failures: the instruction was never executed against the world
  and remains valid — it merely lost this tick's allocation. Retrying is the semantically correct
  continuation and typically succeeds as soon as contention clears.
- Advancing would open a silent copy-corruption channel: the primordial's write loops
  (`PPKR`/`POKE` in `CONTINUE_WRITELINE`) do not check for failure after each write — only the
  `SEEK` steps carry `INER` retry loops. With an advancing IP, every lost conflict in a dense
  colony would silently drop one molecule from the offspring, turning contention into an unintended
  mutation source.
- The retry-with-cost pattern already exists: the max-skip stall resets the IP and retries every
  tick at base + penalty cost (`Organism.java:769-779`, `recoverFromStall`). Conflict loss aligns
  with that precedent instead of creating a new special case.

Consequence, stated openly: a loser is in a forced retry — its control flow cannot leave the
instruction until the write succeeds or it dies. That is intended. Waiting for a contested cell is
allowed but costs base + penalty per tick and runs the entropy clock, so blocking strategies are
selected against instead of being free.

The all-or-nothing structure of resolution is unchanged: an instruction that loses at any of its
target coordinates fails as a whole; no partial writes.

### 2. Winner selection: computed per-tick priority

Lowest-ID-wins is replaced by a priority computed with the mechanism agreed in
[DETERMINISTIC_EXECUTION](DETERMINISTIC_EXECUTION.md) (computed randomness as a pure function of
seed, tick and consumer):

```
priority(org) = mix( tickSeed ^ mix(organismId) )     // OrganismRandom.tickStreamSeed()
tickSeed      = mix( seed ^ mix(tick) )               // Simulation, once per tick
```

The contender with the **smallest** priority wins. On bit-equality (astronomically unlikely), the
lower organism ID wins as a total-order backstop.

Properties: deterministic and resource-invariant (a pure function of seed, tick and IDs — no state,
no ordering, no scheduling enters, satisfying contract R1/R2 of DETERMINISTIC_EXECUTION); no
persistent bias (priorities reshuffle every tick, so under sustained contention every contender wins
with equal long-run frequency and permanent starvation of one party is impossible); hot-path cost of
one `mix64` per contender, no allocation.

### 3. No analytics changes

The failure reason travels through the existing `failure_reason`/`failure_count` path. A per-cause
failure breakdown, if ever wanted, is the existing
[FAILURE_CAUSE_ANALYTICS](../../../proposals/ideas/FAILURE_CAUSE_ANALYTICS.md) idea, where this reason becomes one
category. This proposal adds no metric, no column, and no backlog entry.

## Coordination

Both changes alter simulation trajectories. They must land in the same change set as the
DETERMINISTIC_EXECUTION implementation — one behaviour epoch, not two — so that runs before and
after are separated by a single, identifiable code state. Neither change touches a persisted
format: the agreed determinism design keeps the checkpoint format unchanged, and this proposal
only reuses the already-serialized `failure_reason`. The priority formula reuses the determinism
design's `mix64` primitive and its seed; it introduces no additional state and nothing new to
checkpoint.

## Implementation (as landed)

- Loser path: losers stay marked for the virtual machine (`executedInTick = true`, status
  `LOST_PRIORITY`). `VirtualMachine.execute` books a loser like any failed instruction — policy
  thermodynamics (the policies already return base cost only for `LOST_*` statuses), failure
  reason, error penalty, death checks — but skips `commitStackReads` and `instruction.execute`
  and sets `skipIpAdvance`, so the retry next tick sees identical operands.
- Winner selection: `Simulation.resolveConflicts` scans each contested cell for the smallest
  `tickStreamSeed()` with the ID backstop. Every environment-modifying instruction targets at
  most one cell today; an instruction reporting several target cells is rejected with an
  `IllegalStateException`, because an all-or-nothing rule across cells (and the question whether
  a cell whose winner lost elsewhere should be re-awarded) needs a definition together with the
  first such instruction, not speculative code without a test.
- A conflict loser records no instruction-execution data (it was not executed), so the
  instruction-usage analytics count only executed instructions; its trace is the failure reason.
- `ConflictResolutionStatus.LOST_LOWER_ID_WON` was renamed `LOST_PRIORITY`; the enum remains an
  in-memory diagnostic.

## Tests

`ConflictLossSemanticsTest` (unit, hand-built `POKI` contests) covers the six cases below; the
former `SimulationTest.testConflictResolutionSameTargetLowerIdWins` was rewritten to the new
semantics. Test 3 places the `IFER` guard behind the write and moves the loser onto it, because
the retry would otherwise re-execute the write itself (see §1).

1. **Loser accounting:** two organisms, same target cell, same tick — the loser has
   `instructionFailed` with reason `"Lost write conflict"`, paid base + `error-penalty-cost` energy
   and base entropy, its IP is unchanged; the winner's write took effect.
2. **Retry:** the loser succeeds on the next tick once the contention is gone; total cost equals one
   failed plus one successful execution.
3. **`IFER` visibility:** an `IFER` guarding the tick after a lost conflict takes the failure
   branch.
4. **Priority rule:** with fixed seed and IDs, the winner matches the formula; the winner changes
   across ticks; the result is independent of contender registration order and of
   `runtime.parallelism`.
5. **No starvation:** an organism that loses every tick dies within the bound set by its energy and
   `max-entropy` — it can no longer persist unchanged.
6. **Non-modifying instructions unaffected:** NOP and register-only instructions never enter
   conflict resolution (regression guard).
