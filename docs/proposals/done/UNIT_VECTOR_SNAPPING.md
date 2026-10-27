# Snapping Every Vector to a Unit Vector

**Status: ACCOMPLISHED — implemented on branch `unit-vector-snapping` (PR #169), 2026-09-10.
Every decision was taken with the maintainer and is listed at the end.**

## Problem

Thirteen call sites of `Organism.isUnitVector`, plus the separate check in `V2B`, fail an
instruction when its vector operand is not a unit vector. For a mutated program that is a cliff,
and the mutation operator cannot avoid it.

`GeneSubstitutionPlugin` picks a cell weighted by molecule type and perturbs a DATA value
proportionally to its scale (`GeneSubstitutionPlugin.java:419`):

```
delta  = max(1, round(|value|^exponent))     // exponent 0.7 by default
offset = random(-delta … +delta)
```

A vector component is emitted as DATA (`OperandEncoder.java:95`), so a component out of
`{-1, 0, 1}` always gets `delta = 1` and therefore an offset out of `{-1, 0, +1}`. An offset of 0
leaves the value unchanged and the plugin returns without recording a mutation. Exactly two
effective outcomes per component remain, and every one of them destroys the unit vector:

| Literal | component hit | outcome | sum of absolute components |
|---|---|---|---|
| `1\|0` | the `1` | `0\|0` or `2\|0` | 0 resp. 2 |
| `1\|0` | the `0` | `1\|1` or `1\|-1` | 2 |

Every effective substitution inside a unit vector literal therefore turns the instruction that
uses it into one that fails from then on. The operator cannot do better: it sees single cells and
has no way to recognise a vector component as such, let alone to rotate a vector as a unit. The
only side that can still act is the consuming one.

The order of magnitude is visible in the shipped primordial, whose source holds 43 vector
immediates in instructions — at least 86 component cells in a two-dimensional world, against 162
DATA cells counted in its body. This is an observation about one program, not a premise: any
program may place vectors differently, and the rules below must not depend on how this one does it.

### A rejected vector is not only failed — it still writes

The check for the `POKE`/`PEEK`/`PPK` family sits in `getTargetCoordinates()`
(`EnvironmentInteractionInstruction.java:297`), the method with which an instruction tells conflict
resolution which cell it claims. On a non-unit vector it marks the instruction failed and returns
`List.of()` **without setting `this.targetCoordinate`**. Three things follow:

1. `Simulation.resolveConflicts` skips an empty target list, so the instruction never enters as a
   contender and its `conflictStatus` stays at the field default `NOT_APPLICABLE`
   (`Instruction.java:874`).
2. `VirtualMachine.execute` runs `instruction.execute(context)` gated only on `lostConflict`, never
   on `organism.isInstructionFailed()` (`VirtualMachine.java:182–190`).
3. Neither `execute()` nor `handlePoke` checks the failure state. `handlePoke` finds
   `targetCoordinate == null`, derives it from the **unvalidated** vector (`:103–104`), and the
   guard `conflictStatus == NOT_APPLICABLE` (`:107`) lets the write through.

A `POKI 1|1` therefore writes into the diagonal cell today — a cell the instruction may not reach —
while bypassing arbitration, and is booked as a failed instruction at the same time. The same holds
for `handlePeek` (`:144–146`), which additionally empties the cell and clears its owner, and for
`handlePeekPoke` (`:194–196`). The adjacency rule of the physics is asserted, not enforced, and two
organisms can write the same cell in one tick.

### The child's direction vector is never checked

`FORK`, `FRKI` and `FRKS` check the delta that places the child, but pass the child's direction
vector through unchecked (`StateInstruction.java:280`, `:464`, `:496`). `advanceIpBy` takes the
first component ≠ 0 and uses **its value** as the step width (`Organism.java:818`), so a child DV
of `2|0` would move the instruction pointer in steps of two, and a DV of `0|0` would move it along
`+axis 0`, because the loop finds no non-zero component and the defaults `dim = 0, sign = 1`
stand. The comment there states the unit-vector invariant; nothing enforces it.

## Decision

Every vector operand of the four roles below is mapped to a unit vector before it is used. No
instruction fails for the shape of its vector any more.

### 1. The nearest unit vector

The unit vector closest in angle to a vector **v** is the one that maximises

```
cos θ = (v · s·ê_i) / |v| = s · v_i / |v|
```

`|v|` is the same for all `2·dims` candidates and cancels out, so the nearest unit vector is the
one on the axis with the largest absolute component, carrying that component's sign. No square
root, no arc cosine, no multiplication: a pass over the components with a magnitude comparison,
valid in any number of dimensions.

### 2. Ties

Let `C` be the axes with the maximal absolute value, in ascending order, and `k = |C| ≥ 2`. The
choice is

```
pick       = (number of axes in C whose component is negative) mod k
axis       = C[pick]
sign       = sign(v[C[pick]])
```

This is a pure function of the vector — no random number generator, no organism state — and it
costs a counter in the pass that only runs when snapping is needed.

**For `k = 2` it is provably balanced.** The four sign combinations of two candidates give the
counts 0, 1, 1, 2 and therefore the picks 0, 1, 1, 0 — two on each axis. In two dimensions:

| tied vector | negatives in C | pick | result |
|---|---|---|---|
| `1\|1` | 0 | 0 | `1\|0` |
| `1\|-1` | 1 | 1 | `0\|-1` |
| `-1\|1` | 1 | 1 | `0\|1` |
| `-1\|-1` | 2 | 0 | `-1\|0` |

The rule depends only on `C`, not on the dimensionality, so the same holds for any candidate pair
in higher dimensions. And `k = 2` is the case that arises from a single mutation: a unit vector has
one component ±1, and raising a second one to ±1 produces exactly two candidates. Larger `k` needs
several mutations of the same vector.

### 3. The zero vector, and the two roles a vector operand plays

A vector operand is either a **displacement** or a **direction**, and the zero vector means
something different in each.

- **Displacement** — `PEEK`, `POKE`, `PPK`, `SCAN`, `SEEK`, the `IF*` / `IN*` family, and the fork
  delta. The vector is an offset from the data pointer. An offset of none is a complete statement:
  the cell the pointer already stands on. It is therefore kept, and the instruction acts there. This
  reaches one cell *fewer* than a step, never one further, so the adjacency rule is not weakened —
  and a wall of STRUCTURE keeps a foreign data pointer out exactly as before, because that defence
  works through `SEEK` refusing to enter, not through what can be reached from where the pointer is.
- **Direction** — `TURN`, `TRNI`, `TRNS`, the child DV of a fork, and `V2B`. The vector says where
  to travel, and there is no travelling nowhere. The zero vector is answered by the current DV of
  **the organism whose instruction is executing**; for a child DV that is the parent, so a child
  born with a zeroed operand inherits the parent's heading. Snapping against the child would yield
  its constructor default `+axis 0` (`Organism.java:166–167`), a direction that comes from nowhere.
  The DV is a unit vector by invariant and part of the serialised organism state, so a resume takes
  the same branch.

Both roles map everything else to the nearest unit vector by the rule above.

### 4. Scope

The rule covers every vector operand of the instructions named in the two roles, the child DV of
the three fork instructions included.

The two roles are two methods on `Organism`, so that a call site says which it means rather than
leaving it to a comment: `toUnitVector` for a direction, `toDisplacement` for an offset.

**The snap lives in the role handlers, not in `resolveOperands`.** `SETV` and `PUSV` take `VECTOR`
operands that legitimately carry large, non-unit values, and `RTR*` explicitly does not require a
unit vector (`ASSEMBLY_SPEC.md:541`). Moving the snap into operand resolution would silently break
them; that is a load-bearing decision, not an implementation detail.

The compiler checks no more than it does today. A hand-written `SEKI 2|3` is snapped at runtime like
any other vector, and the author gets no diagnostic. Making the compiler recognise which vector
operands demand a unit vector would require the ISA signature to carry that distinction, which it
does not.

### 5. Two invariants that are enforced rather than assumed

- **Component count.** A vector operand always has as many components as the world has dimensions:
  `resolveOperands` allocates `new int[dims]` and fills it from the cells (`Instruction.java:330`),
  `VBLD`/`VBLS` are handed `dims`, and location registers are allocated at `dims`. Corrupted,
  overwritten or half-copied machine code changes the component *values*, never their number. A
  mismatch is therefore a bug in this code, and it is treated as one: `Organism.toUnitVector` logs
  it at ERROR with a stack trace, following the AGENTS.md rule for should-never-happen conditions
  (`log.error(msg, args, new IllegalStateException(…))`), and then marks the instruction failed so
  that a run does not die of an impossible state.
- **The restored DV.** After this change no runtime path can produce a non-unit DV, so a restored
  state that carries one cannot describe an organism this build could have produced.
  `Organism.RestoreBuilder.validateStateInvariants()` rejects it with `InvalidRestoreState`, the way
  it already rejects a state whose registers or stacks contradict the build. Existing runs are
  unaffected: a run is resumed with the build that created it.

## Changes

| Area | File | Change |
|---|---|---|
| New | `runtime/model/UnitVector.java` | Package-private utility holding the rule: `nearest(int[] vector)` returns the nearest unit vector, or `null` for the zero vector. Returns the argument itself when it already is a unit vector, and never writes into it |
| Organism | `runtime/model/Organism.java` | `toUnitVector(int[])` for a direction: `nearest`, and a **fresh copy** of the DV where `nearest` returned `null` — never the DV array itself, so parent and child cannot share one. `toDisplacement(int[])` for an offset: `nearest`, and the argument itself for the zero vector. Both check the component count per Decision 5 through one private helper. `isUnitVector` is removed once its last caller is gone |
| Organism | `runtime/model/Organism.java` | `RestoreBuilder.validateStateInvariants()` rejects a non-unit DV with `InvalidRestoreState` |
| Slice 0 | `runtime/isa/instructions/EnvironmentInteractionInstruction.java` | `handlePoke`, `handlePeek` and `handlePeekPoke` stop before deriving a coordinate when the instruction is already failed |
| Role A | `runtime/isa/instructions/EnvironmentInteractionInstruction.java` | one private `targetCoordinate(Environment)` holds snap and cache; `getTargetCoordinates()` wraps it in `List.of` and keeps both empty paths (no operands `:288`, no vector operand `:297`), which `Simulation.resolveConflicts` documents as "instruction runs, detects the error itself and fails gracefully". The vector is taken from the last operand, which every variant of this family carries it in, and type-checked there, instead of by the backwards scan for "the last `int[]`" (`:292–299`) |
| Role A | `runtime/isa/instructions/StateInstruction.java` | `handleSeek` (`:423`) and the scan handler (`:523`) snap instead of returning |
| Role A | `runtime/isa/instructions/ConditionalInstruction.java` | the four checks (`:143`, `:170`, `:199`, `:227`) snap, so the condition is evaluated at the snapped neighbour |
| Role B | `runtime/isa/instructions/StateInstruction.java` | `handleTurn` (`:226`), `handleTrni` (`:538`) and `handleTrns` (`:548`) set the snapped vector unconditionally; `handleTrns` keeps its check that the stack top is an `int[]` |
| Role B | `runtime/isa/instructions/StateInstruction.java` | the three `child.setDv(childDv)` calls (`:280`, `:464`, `:496`) set the child DV snapped against the parent |
| Role C | `runtime/isa/instructions/StateInstruction.java` | the fork deltas (`:270`, `:454`, `:487`) snap; the fork proceeds |
| Role D | `runtime/isa/instructions/VectorInstruction.java` | `V2B`/`V2BS` snap before forming the mask (`:236`, `:251`), keeping the guard that the operand is an `int[]`. The single failure message is split into two: the operand is not a vector, and the axis exceeds the mask width — the shape message can no longer occur |
| Own fix | `runtime/isa/instructions/StateInstruction.java` | `handleSeek:428` reads the owner as `getOwnerId(coord[0], coord[1])`, which allocates and is wrong beyond two dimensions; it becomes `getOwnerIdAt(coord)`. Own commit |
| Javadoc | `runtime/model/Organism.java` | `getDv()` (`:1600`), `advanceIpBy` (`:816`), `skipNopCells`, `RestoreBuilder.dv` (`:420`) state the invariant as an assumption and become statements of what is enforced |
| Javadoc | `runtime/isa/instructions/StateInstruction.java` | `handleFork` `@param` (`:263`), `handleForkExtended` (`:444–449`, whose duplicated line goes with it) |
| Javadoc | `runtime/isa/instructions/EnvironmentInteractionInstruction.java` | `getTargetCoordinates` `@return` (`:273`) says "or empty list if invalid"; the interceptor-ordering warning (`:249–274`) gains the sentence that the snap happens at the same point, which is why planning and execution agree |
| Docs | `docs/ASSEMBLY_SPEC.md` | line 68 (the DV definition, now an enforced invariant and how it is maintained); the conditional families; the environment interaction preamble (`:448`, whose adjacency claim becomes true by construction); `TURN`; `SEEK`; `V2B` including its failure sentence (`:530`); and new text for `FORK`/`FRK*` (`:479–480`), which today says nothing about either vector. §Vector Literals (`:275–283`) needs no edit — it describes syntax and component count only |

All new public members carry complete Javadoc describing what they are, not what changed.

## Tests

New:

- `UnitVectorTest`: a vector that already is a unit vector is returned unchanged **and by
  identity**; magnitude cases (`2|0` → `1|0`, `0|-3` → `0|-1`); every tie case for `k = 2` in two
  and three dimensions against the table above; the `k = 3` outcome distribution as documented; the
  zero vector yields `null`.
- **Drift freedom:** over all `2·dims` unit vectors in two and three dimensions, apply every
  single-component mutation the substitution operator can produce, snap the result, exclude the
  zero-vector case, and assert that the transition matrix is doubly stochastic — every direction is
  reached as often as it is left. This is the property the tie rule exists for, and the only thing
  that would catch a later "simplification" of it. The second dimension is there to catch
  hard-coding on two axes, not to test the rule, which depends only on the candidate set.
- **Zero vector:** parametrised over all unit DVs, the result equals the DV and is not the DV array
  itself.
- **No aliasing:** a register and a stack entry whose vector is snapped are unchanged afterwards.
  A conflict loser retries next tick with the same operands, because `commitStackReads` is
  deliberately skipped for losers (`Instruction.java:358`); an in-place snap would change what it
  retries with.
- Per role, one instruction test that fails today and produces a result afterwards: `SCNI` with
  `1|1`, `SEKI` with `0|0`, `IFMI` with `1|-1` (asserting the branch, not merely the absence of a
  failure), `TRNI` with `2|0`, `FRKI` with a non-unit delta, `V2BI` with `1|1`.
- `FRKI` with a zeroed child DV: the child's DV equals the **parent's** DV, and its instruction
  pointer advances by one cell.
- A restored state with a non-unit DV is rejected with `InvalidRestoreState`.
- Two organisms whose snapped vectors address the same cell take part in conflict resolution as two
  claimants.

**Defect test for slice 0**, red before the fix: an organism executing `POKI 1|1` leaves the
diagonal cell untouched; the same for `PEKI`, whose target must keep its molecule and its owner.

Existing tests to revisit: `VMConditionalInstructionTest` asserts the failure explicitly
(`:595`–`:605`, `IFMR` with `1|1` expecting `"not a unit vector"`); it becomes a test of the snapped
branch. The suite is searched once for any other test that passes a non-unit vector and relies on
the failure.

## Implementation plan

Seven slices. Each ends with a green `./gradlew check` and one statement that can be checked; each
is its own commit whose message states the verification result. Specification changes travel with
the slice that changes the behaviour, and **every slice that touches `ASSEMBLY_SPEC.md` stops before
its commit so the maintainer can read the diff**.

| # | Slice | Content | Verification |
|---|---|---|---|
| 0 | The write that should not happen | Defect test first, then the three handlers stop when the instruction is already failed | the red test turns green; no other test changes |
| 1 | The rule exists, nothing uses it | `UnitVector` with its Javadoc, `UnitVectorTest`, the drift-freedom test, the aliasing test | new tests green, existing suite unchanged; JMH micro-measurement of `nearest` while nothing calls it |
| 2 | Direction of travel | `Organism.toUnitVector`; `TURN`, `TRNI`, `TRNS`; the three child DVs; `RestoreBuilder`; the Javadoc inventory; ASSEMBLY_SPEC hunks for the DV definition and the fork | a child born with a zeroed DV inherits the parent's and advances by one cell; turning never fails; `DeterministicExecutionTest` and `ResumeForkNeutralityTest` green |
| 3 | Displacements, environment | `Organism.toDisplacement`; the single coordinate derivation; `SEEK`; `SCAN`; the `getOwnerIdAt` fix in its own commit; ASSEMBLY_SPEC preamble of the world interactions | planning and execution address the same cell; a vector naming no neighbour acts on the nearest one and leaves the cell two steps away untouched; without a displacement they act under the pointer |
| 4 | Displacements, conditions | the four `ConditionalInstruction` checks; the conditional section split into value comparisons and cell tests | a mutated condition vector branches instead of failing, shown by the same condition skipping and not skipping depending on the cell the mapping selects |
| 5 | Fork and mask | the three fork deltas as displacements; `V2B`/`V2BS` with the split messages; `isUnitVector` removed; ASSEMBLY_SPEC for the fork delta and `V2B` | forking with a mutated delta produces a child, with none it is placed under the pointer; a scalar operand to `V2B` says what is wrong |
| 6 | Documentation and measurement | the remaining javadoc inventory; the proposal brought to what was built; JMH tick benchmark | benchmark announced with its duration; `./gradlew check` green |

Slice 2 precedes the others because the zero-vector fallback reads the DV, and the DV invariant has
to hold before anything relies on it. The micro-measurement sits in slice 1, where the class exists
and nothing calls it, so the hot-path cost is known before five slices are committed on top of it.

## Consequences

- **Instruction failure counts change.** A whole class of failures disappears. Runs before and after
  the change are not comparable in their failure statistics.
- **A write that bypassed arbitration disappears.** Today a rejected vector still writes, outside
  conflict resolution; afterwards the instruction claims a neighbour and is arbitrated. Two
  organisms can now contend for a cell that no one contended for before.
- **Conditions branch instead of failing.** Today a rejected condition vector ends the instruction
  without calling `skipNextInstruction`, so the next instruction runs as if the condition held, and
  the failure cost is charged. Afterwards the condition is evaluated at the snapped neighbour and
  can take the branch.
- **A mutated fork delta no longer sterilises.** It moves the child instead.
- **Determinism.** The rule is a pure function of the operand and, for the zero vector, of the DV,
  which is part of the serialised organism state. `getTargetCoordinates` runs in `resolveConflicts`,
  sequentially after the parallel wave, so the DV it reads is stable and order-independent, and no
  random provider is touched.
- **Performance: the common case is unchanged, snapping costs more, and how much more is open.**
  Measured with a throwaway JMH benchmark on the development machine, which is not known to be
  quiet. A vector that already is a unit vector costs 2.66 ns against 2.57 ns for the check it
  replaces — the same within the noise. `isUnitVector` reads the dimension count through
  `Environment.getShape()`, which hands out a defensive copy (`Environment.java:524`), but that
  allocation does not show up in the measurement; the escape analysis of the JIT removes it. Taking
  the count from `this.ip.length` instead is hygiene, not a saving. The fast path stays exactly what
  `isUnitVector` does: sum the magnitudes, return the argument when the sum is 1; maximum, candidate
  count and negative candidates are computed in a second pass that runs only when snapping is
  needed. Snapping costs 4.31 ns with one candidate and 5.67 ns with two, so 1.6 to 3 ns more than
  the common case. **That path is not rare by construction.** This change keeps a mutated vector in
  circulation instead of failing the instruction that uses it, so a mutated `SEKI 1|1` in a main loop
  is snapped at every execution, and its share grows with the mutation load and the length of a run.
- **The tick benchmark shows no regression.** JMH on the benchmark host, `REALISTIC` at three
  population sizes, decision profile, base `caff2999` against the branch: +1.15 %, −1.68 % and
  −2.30 %, every one of them within the combined error, so by the rule of `docs/BENCHMARKING.md` the
  change is neutral. Two limits of that measurement are worth stating. `REALISTIC` carries three
  vector operands among roughly twenty instructions, so an effect of well under a percent would sit
  below the noise; and the candidate's error at 2000 organisms was ±5.8 % against the base's ±1.9 %,
  which is wider than the difference it is supposed to resolve. A gross regression is ruled out; a
  small one is not measurable this way.
- **A run with many mutated vectors does more work per tick than before.** Not because of the
  mapping, but because instructions now execute where they previously aborted: a rejected vector
  used to end its instruction before the environment was touched. This is the intended effect of the
  change and not a regression, but it is capacity that grows with the mutation rate, and no benchmark
  of intact programs can show it.
- **The DV invariant becomes enforced** on every path including resume, which closes the unchecked
  child-DV path that exists today.
- **`GeneInsertionPlugin`'s unit-vector mode loses its reason.** `generateUnitVector` (`:603`) and
  the config mode `VECTOR = "unit"` (`reference.conf:2141`) exist so that random insertion does not
  emit instructions that always fail. No code change is required, and none is made here.
- **`FAILURE_CAUSE_ANALYTICS` names a signature that disappears.** The idea document lists "not a
  unit vector" as a live failure cause (`docs/proposals/ideas/FAILURE_CAUSE_ANALYTICS.md:11, 21`).

## Documented limits

- **Snapping is not gradual.** The result is always one of the `2·dims` unit vectors; a mutated
  vector jumps by 90° or stays where it was. It removes the cliff, it does not produce small angular
  variation. Variation proportional to the mutation would need an operator that sees a vector as a
  unit, which needs a molecule type for vector components — considered and rejected.
- **A zeroed direction operand is correlated with the DV, not independent of it.** Where the vector
  names a direction — `TURN`, the child DV, `V2B` — every operand a mutation zeroes is answered with
  the same direction within one organism at one moment. In a program that holds its DV constant over
  long stretches, the fallback behaves there like a fixed direction. The rule introduces no
  preference for any axis over a population, but it does not spread a zeroed direction over the
  directions either. This path is as frequent as the tie path: `1|0 → 0|0` is one of the two
  effective outcomes of a mutation hitting the non-zero component. For a displacement operand the
  question does not arise, because a zero displacement keeps its own meaning.
- **Ties with `k ≥ 3` are not perfectly balanced.** In three dimensions the eight sign combinations
  of three candidates yield the outcomes `(1,0,0)`:1, `(-1,0,0)`:1, `(0,1,0)`:2, `(0,-1,0)`:1,
  `(0,0,1)`:1, `(0,0,-1)`:2 — uneven across signs, not only across axes. Three components of equal
  magnitude need several mutations of one vector. The imbalance is documented rather than hidden
  behind a scattering function that cannot be checked by hand.
- **`V2B`/`V2BS` still fail above the axis limit.** From the eleventh dimension on there is no bit
  position for the axis (`nonZeroIndex >= Config.VALUE_BITS / 2`). That is a limit of the mask width,
  not a property of the vector.
- **A wrong component count still fails**, as a logged bug rather than as a program error. No known
  runtime path produces one.
- **A mistyped vector literal is no longer diagnosed.** `SEKI 2|3` is snapped silently. The compiler
  checks no more than today, by decision.
- **`B2V`/`B2VS` keep their cliff.** A mask with no bit or several bits still fails
  (`VectorInstruction.java:220–224`), so the `SPNS → pick a bit → B2VS → TRNS` idiom stays as
  brittle at its start as it was. The operand is a scalar, not a vector, and a rule for masks needs
  its own decisions about the empty mask, several bits and bits beyond the axis limit.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Leave it as it is | Every effective mutation of a vector component is lethal to the instruction that uses it; the operator has no way to avoid it |
| Tie goes to the lowest axis | Simplest, but a vector on a higher axis turns towards axis 0 and never back, so all directions drift towards ±axis 0 over the generations |
| Tie decided by a hash of the components with an avalanche finaliser | Statistically even, but the balance can no longer be verified by hand and depends on a magic constant; the counting rule is provably balanced where it matters |
| Tie fails the instruction | Drift-free, but leaves three of four mutation outcomes lethal, which is most of the cliff |
| Zero vector maps to a fixed vector (+axis 0) | Introduces a direction that comes from nowhere and drifts every organism towards one axis |
| Zero displacement also answered by the DV | Treats an offset of none as missing information when it is a complete statement, and ties every zeroed world-interaction operand to the direction of travel |
| Zero vector of a child DV snapped against the child | The child carries its constructor default at that moment, which is the fixed-vector alternative under another name |
| Compute the true angle with square root and arc cosine | Mathematically identical result at far higher cost; the length of the vector is the same for every candidate and cancels out |
| Snap in `resolveOperands` instead of the role handlers | Would silently change `SETV`, `PUSV` and `RTR*`, which take non-unit vectors legitimately |
| Compiler rejects non-unit vector literals | Would need the ISA signature to distinguish which VECTOR operands demand a unit vector; decided against — the compiler checks no more than today |
| Wrong component count throws instead of being logged | The global catch-all in `VirtualMachine` (`:232`) turns any exception into an ordinary instruction failure without a log line, so throwing would be strictly worse than logging at ERROR with a stack trace |
| Bring `B2V`/`B2VS` into scope | The same cliff at the other end of the same round trip, but on a scalar operand, and a mask rule needs its own decisions; kept out deliberately, see Documented limits |
| A VECTOR molecule type for vector components | Would let the operator mutate a vector as a unit and produce real rotations; discussed separately and rejected |

## Decisions taken

- Every vector operand of the four roles is snapped to the nearest unit vector; no instruction fails
  for the shape of its vector any more.
- Nearest means the axis with the largest absolute component, carrying that component's sign.
- A tie is decided by the number of negative components among the tied axes, modulo their count.
- A vector operand is a displacement or a direction. A zero displacement is kept and addresses the
  cell under the data pointer; a zero direction is answered by the DV of the organism whose
  instruction is executing, for a child DV therefore by the parent's.
- The rule covers all four roles and the child DV of the fork instructions; the snap lives in the
  role handlers, never in `resolveOperands`.
- `B2V`/`B2VS` stay out of scope.
- A wrong component count is logged at ERROR with a stack trace and then fails the instruction.
- A restored non-unit DV is rejected with `InvalidRestoreState`.
- `UnitVector.nearest` returns `null` for the zero vector, never writes into its argument, and never
  returns an array owned by organism state.
- The compiler checks no more than it does today.
- `isUnitVector` is removed when its last caller is gone.
- The defect that lets a rejected vector still write is fixed first, in slice 0, with a red test.
- Every slice that touches `ASSEMBLY_SPEC.md` stops before its commit for the maintainer to read the
  diff.
