# Snapping Every Vector to a Unit Vector

**Status: PROPOSED — the rules are agreed with the maintainer; the implementation plan awaits
approval. Decisions taken are listed at the end.**

## Problem

Fourteen call sites of `Organism.isUnitVector`, plus the separate check in `V2B`, fail an
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

### A second, independent defect

`FORK`, `FRKI` and `FRKS` check the delta that places the child, but pass the child's direction
vector through unchecked (`StateInstruction.java:280`, `:464`, `:496`). `advanceIpBy` takes the
first component ≠ 0 and uses **its value** as the step width (`Organism.java:818`), so a child DV
of `2|0` would move the instruction pointer in steps of two, and a DV of `0|0` would move it along
`+axis 0`, because the loop finds no non-zero component and the defaults `dim = 0, sign = 1`
stand. The comment there states the unit-vector invariant; nothing enforces it.

## Decision

Every vector operand is mapped to a unit vector before it is used. No instruction fails for the
shape of its vector any more.

### 1. The nearest unit vector

The unit vector closest in angle to a vector **v** is the one that maximises

```
cos θ = (v · s·ê_i) / |v| = s · v_i / |v|
```

`|v|` is the same for all `2·dims` candidates and cancels out, so the nearest unit vector is the
one on the axis with the largest absolute component, carrying that component's sign. No square
root, no arc cosine, no multiplication: one pass over the components with a magnitude comparison,
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
costs a counter in the pass that is already running.

**For `k = 2` it is provably balanced.** The four sign combinations of two candidates give the
counts 0, 1, 1, 2 and therefore the picks 0, 1, 1, 0 — two on each axis. In two dimensions:

| tied vector | negatives in C | pick | result |
|---|---|---|---|
| `1\|1` | 0 | 0 | `1\|0` |
| `1\|-1` | 1 | 1 | `0\|-1` |
| `-1\|1` | 1 | 1 | `0\|1` |
| `-1\|-1` | 2 | 0 | `-1\|0` |

Every starting direction is turned away in exactly one of its two tie cases, and the targets are
distributed evenly, so no axis is preferred over the generations. The rule depends only on `C`,
not on the dimensionality, so the same holds for any candidate pair in higher dimensions.

`k = 2` is also the case that occurs in practice: a tie arises when a mutation raises a zero
component to ±1 while another component already is ±1. Larger `k` needs several mutations of the
same vector.

### 3. The zero vector

A zero vector has no direction, so no angle can be nearest. It maps to the organism's current DV.
That vector is a unit vector by invariant, is part of the serialised organism state and therefore
survives a resume, and yields something meaningful in every role: `TURN` keeps its direction,
`SEEK`, `SCAN` and `PEEK` address the neighbour ahead, `FORK` places the child ahead, `V2B` yields
the mask of the direction of travel. It introduces no direction of its own and therefore no drift.

### 4. Scope

The rule applies to all four roles a vector operand plays — neighbour access, direction of travel,
fork delta and bit mask — and to the child DV of the three fork instructions, which is unchecked
today.

The number of components stays a failure condition. A vector operand always occupies `dims` cells,
so no mutation can produce a wrong count; a wrong count is a defect, not a variant.

The compiler checks no more than it does today. A hand-written `SEKI 2|3` is snapped at runtime
like any other vector, and the author gets no diagnostic. This is deliberate: making the compiler
recognise which vector operands demand a unit vector would require the ISA signature to carry that
distinction, which it does not.

## Changes

| Area | File | Change |
|---|---|---|
| New | `src/main/java/org/evochora/runtime/model/UnitVector.java` | Utility class holding the rule: `nearest(int[] vector, int[] fallback)` returns the nearest unit vector, the tie rule and the fallback for the zero vector included. Returns the argument itself when it already is a unit vector, so the common case allocates nothing |
| Organism | `src/main/java/org/evochora/runtime/model/Organism.java` | `toUnitVector(int[])` checks the component count against the world, fails the instruction on a mismatch, and otherwise delegates to `UnitVector.nearest` with the organism's DV as the fallback; `isUnitVector` is removed once its last caller is gone |
| Role A | `src/main/java/org/evochora/runtime/isa/instructions/EnvironmentInteractionInstruction.java` | `getTargetCoordinates` snaps instead of returning an empty list; the four places that derive the target coordinate (`:103`, `:144`, `:195`, `:301`) are reduced to one, so planning and execution cannot use different vectors |
| Role A | `src/main/java/org/evochora/runtime/isa/instructions/StateInstruction.java` | `handleSeek` (`:423`) and the scan handler (`:523`) snap instead of returning |
| Role A | `src/main/java/org/evochora/runtime/isa/instructions/ConditionalInstruction.java` | the four checks (`:143`, `:170`, `:199`, `:227`) snap, so the condition is evaluated at the snapped neighbour |
| Role B | `src/main/java/org/evochora/runtime/isa/instructions/StateInstruction.java` | `handleTurn` (`:226`), `handleTrni` (`:538`) and `handleTrns` (`:548`) set the snapped vector unconditionally |
| Role B | `src/main/java/org/evochora/runtime/isa/instructions/StateInstruction.java` | the three `child.setDv(childDv)` calls (`:280`, `:464`, `:496`) set the snapped child DV, enforcing the invariant `advanceIpBy` already assumes |
| Role C | `src/main/java/org/evochora/runtime/isa/instructions/StateInstruction.java` | the fork deltas (`:270`, `:454`, `:487`) snap; the fork proceeds |
| Role D | `src/main/java/org/evochora/runtime/isa/instructions/VectorInstruction.java` | `V2B`/`V2BS` snap before forming the mask (`:236`, `:251`); the axis limit `nonZeroIndex >= VALUE_BITS/2` stays a failure |
| Docs | `docs/ASSEMBLY_SPEC.md` | every place that says an instruction requires a unit vector: the vector is snapped to the nearest unit vector, ties and the zero vector as specified. Affects §Vector Literals, the conditional families, the environment interaction section, `TURN`, `SEEK`, `FORK`/`FRK*`, `V2B` |

All new public members carry complete Javadoc describing what they are, not what changed.

## Tests

New:

- `UnitVectorTest`: a vector that already is a unit vector is returned unchanged and by identity;
  magnitude cases (`2|0` → `1|0`, `0|-3` → `0|-1`); every tie case for `k = 2` in two, three and
  four dimensions against the table above; the `k = 3` distribution as the documented 2:3:3; the
  zero vector yields the fallback; a component count differing from the fallback's is rejected by
  the caller, not here.
- A balance test: for every unit vector in two to four dimensions, apply every single-component
  mutation the substitution operator can produce, snap the result, and assert that the
  distribution of outcomes is the same for every starting direction up to the symmetry of the
  axes. This is the test that would catch a tie rule with drift.
- Per role, one instruction test that fails today and produces a result afterwards: `SCNI` with
  `1|1`, `SEKI` with `0|0`, `IFMI` with `1|-1` (asserting the branch, not merely the absence of a
  failure), `TRNI` with `2|0`, `FRKI` with a non-unit delta, `V2BI` with `1|1`.
- `FRKI` with a non-unit child DV: the child's DV is a unit vector and its instruction pointer
  advances by one cell.
- Two organisms whose snapped vectors address the same cell take part in conflict resolution as
  two claimants.

Existing tests to revisit: `VMConditionalInstructionTest` asserts the failure explicitly
(`:595`–`:605`, `IFMR` with `1|1` expecting `"not a unit vector"`); it becomes a test of the
snapped branch. The suite is searched once for any other test that passes a non-unit vector and
relies on the failure.

## Implementation plan

Six slices. Each ends with a green `./gradlew check` and one statement that can be checked; each
is its own commit whose message states the verification result. The specification changes travel
with the slice that changes the behaviour.

| # | Slice | Content | Verification |
|---|---|---|---|
| 1 | The rule exists, nothing uses it | `UnitVector` with its Javadoc, `UnitVectorTest`, the balance test | new tests green, existing suite unchanged |
| 2 | Direction of travel | `Organism.toUnitVector`; `TURN`, `TRNI`, `TRNS`; the three child DVs; ASSEMBLY_SPEC hunks for `TURN` and the fork DV | a child born with a non-unit DV advances by one cell; turning never fails |
| 3 | Neighbour access, environment | `EnvironmentInteractionInstruction` with the four coordinate derivations reduced to one; `SEEK`; `SCAN`; ASSEMBLY_SPEC hunks | planning and execution address the same cell; a non-unit vector claims a cell in conflict resolution |
| 4 | Neighbour access, conditions | the four `ConditionalInstruction` checks; ASSEMBLY_SPEC hunks for the conditional families | a mutated condition vector branches instead of failing |
| 5 | Fork and mask | the three fork deltas; `V2B`/`V2BS`; `isUnitVector` removed | forking with a mutated delta produces a child; every vector yields a mask below the axis limit |
| 6 | Documentation and measurement | remaining ASSEMBLY_SPEC hunks; JMH tick benchmark | benchmark on the benchmark host with determinism check, announced with its duration |

Slice 2 precedes the others because the zero-vector fallback reads the DV, and the DV invariant
has to hold before anything relies on it.

## Consequences

- **Instruction failure counts change.** A whole class of failures disappears. Runs before and
  after the change are not comparable in their failure statistics.
- **Conflict dynamics change.** An instruction whose vector was rejected claimed no cell and
  dropped out of conflict resolution; it now claims one. Two organisms can contend for a cell that
  no one contended for before.
- **Conditions branch instead of failing.** Today a rejected condition vector ends the instruction
  without calling `skipNextInstruction`, so the next instruction runs as if the condition held,
  and the failure cost is charged. Afterwards the condition is evaluated at the snapped neighbour
  and can take the branch.
- **A mutated fork delta no longer sterilises.** It moves the child instead.
- **Determinism.** The rule is a pure function of the operand and, for the zero vector, of the DV,
  which is part of the serialised organism state. A resume takes the same branches.
- **Performance.** One pass over `dims` components, computing magnitude sum, largest magnitude,
  candidate count and negative candidates at once. A vector that already is a unit vector — the
  normal case — is returned unchanged, with no allocation and no modulo. Only the snapping case
  allocates. To be confirmed by the JMH tick benchmark before merge.
- **The DV invariant becomes enforced** rather than assumed, which closes the unchecked child-DV
  path that exists today.

## Documented limits

- **Snapping is not gradual.** The result is always one of the `2·dims` unit vectors; a mutated
  vector jumps by 90° or stays where it was. It removes the cliff, it does not produce small
  angular variation. Variation proportional to the mutation would need an operator that sees a
  vector as a unit, which needs a molecule type for vector components — considered and rejected.
- **Ties with `k ≥ 3` are not perfectly balanced.** The eight sign combinations of three
  candidates give the counts 0,1,1,1,2,2,2,3 and thus the picks 0,1,1,1,2,2,2,0, a ratio of 2:3:3.
  This needs three components of equal magnitude and therefore several mutations of one vector.
  The imbalance is documented rather than hidden behind a scattering function that cannot be
  checked by hand.
- **`V2B`/`V2BS` still fail above the axis limit.** From the eleventh dimension on there is no bit
  position for the axis (`nonZeroIndex >= Config.VALUE_BITS / 2`). That is a limit of the mask
  width, not a property of the vector.
- **A wrong component count still fails.** No mutation can produce one.
- **A mistyped vector literal is no longer diagnosed.** `SEKI 2|3` is snapped silently. The
  compiler checks no more than today, by decision.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Leave it as it is | Every effective mutation of a vector component is lethal to the instruction that uses it; the operator has no way to avoid it |
| Tie goes to the lowest axis | Simplest, but a vector on a higher axis turns towards axis 0 and never back, so all directions drift towards ±axis 0 over the generations |
| Tie decided by a hash of the components with an avalanche finaliser | Statistically even, but the balance can no longer be verified by hand and depends on a magic constant; the counting rule is provably balanced where it matters |
| Tie fails the instruction | Drift-free, but leaves three of four mutation outcomes lethal, which is most of the cliff |
| Zero vector maps to a fixed vector (+axis 0) | Introduces a direction that comes from nowhere and drifts every organism towards one axis |
| Compute the true angle with square root and arc cosine | Mathematically identical result at far higher cost; `|v|` cancels out |
| Compiler rejects non-unit vector literals | Would need the ISA signature to distinguish which VECTOR operands demand a unit vector; decided against — the compiler checks no more than today |
| A VECTOR molecule type for vector components | Would let the operator mutate a vector as a unit and produce real rotations; discussed separately and rejected |

## Decisions taken

- Every vector operand is snapped to the nearest unit vector; no instruction fails for the shape
  of its vector any more.
- Nearest means the axis with the largest absolute component, carrying that component's sign.
- A tie is decided by the number of negative components among the tied axes, modulo their count.
- The zero vector maps to the organism's current DV.
- The rule covers all four roles and the child DV of the fork instructions.
- The component count remains a failure condition.
- The compiler checks no more than it does today.
- `isUnitVector` is removed when its last caller is gone.
