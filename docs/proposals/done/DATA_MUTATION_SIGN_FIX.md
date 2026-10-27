# Sign Handling in Scale-Proportional DATA Mutation

**Status: ACCOMPLISHED — implemented on branch `feature/data-mutation-sign-fix`, 2026-08-24.**

## Problem

`GeneSubstitutionPlugin` mutates DATA molecules with a scale-proportional perturbation: the delta grows
with the magnitude of the value, so small values change relatively strongly and large values change
relatively weakly. The method documents this intent as *"producing smooth fitness landscape perturbations"*.

For negative DATA values the perturbation is not smooth — it is effectively a random jump.

### Cause

Reservoir sampling stores the raw molecule value without sign extension:

```java
// GeneSubstitutionPlugin.java, weighted reservoir sampling
state[2] = moleculeInt & Config.VALUE_MASK;
```

Reading a molecule elsewhere does sign-extend (`Molecule.fromInt`), so negative DATA values are real and
in active use. The emitter produces them for vector components, and the primordial organism uses them for
every movement to the west or north (`SEKI -1|0`, `SCNI %TMP 0|-1`, `.PLACE DATA:-1`).

`mutateData` then receives the unsigned 20-bit pattern instead of the value:

```java
private int mutateData(int dataValue) {
    int delta = Math.max(1, (int) Math.round(Math.pow(Math.abs(dataValue), dataExponent)));
    int offset = random.nextInt(2 * delta + 1) - delta;
    int newValue = dataValue + offset;
    return Math.max(0, Math.min(Config.VALUE_MASK, newValue));
}
```

| Value | Passed in | delta (exponent 0.7) | Result |
|---|---|---|---|
| `+1` (east/south) | 1 | 1 | 0, 1 or 2 — smooth |
| `-1` (west/north) | 1048575 | 16384 | ~50 % unchanged by the clamp, otherwise an arbitrary value between −2 and −16385 |

A direction vector `-1|0` therefore never mutates to a neighbouring value. It jumps to a five-digit
magnitude, which is no longer a unit vector, so every use of it raises `instructionFailed`
("not a unit vector") and costs the error penalty — potentially on every loop iteration.

The clamp to `[0, VALUE_MASK]` additionally creates two absorbing walls at the sign boundary: a positive
value can never mutate into a negative one and vice versa.

## Scope

Only `mutateData` is affected. The raw value from the sampler is dispatched to five mutators, and the
other four require the unsigned pattern:

| Molecule type | Mutator | Needs sign |
|---|---|---|
| CODE | `mutateCode` | no — opcode IDs are 0…40959, looked up in tables keyed by unsigned IDs |
| REGISTER | `mutateRegister` | no — IDs 0…1795; `RegisterBank.forId` returns `null` for negative input |
| LABEL / LABELREF | `mutateLabelHash` | no — 19-bit hashes, bit 19 is never set |
| DATA | `mutateData` | **yes** |

Sign extension must therefore happen inside `mutateData`, not at the sampling site.

Other occurrences of `& Config.VALUE_MASK` in `runtime/worldgen/` were checked and are unaffected:
`GeneDeletionPlugin`, `GeneInsertionPlugin` (label hash) and `LabelRewritePlugin` operate on hashes;
`GeneInsertionPlugin` masks on write, where masking is correct.

## Sign extension has one source

The operation itself must not be written a fourth time. The identical bit pattern exists three times
today:

| Location | Form |
|---|---|
| `Molecule.java:74-75` | inline inside `fromInt` |
| `Instruction.java:294-300` | `public static int extractSignedValue(int)` |
| `MoleculeDataUtils.java:29-36` | the same method again, in `datapipeline.utils` |

Adding a fourth copy would multiply the precondition for the very defect being fixed here: this defect is
a place where sign extension was not applied. The copies have already started to drift — the JavaDoc of
`MoleculeDataUtils.extractSignedValue` describes a *"16-bit value"*, while `Config.VALUE_BITS` has been 20
since the value width changed.

The operation is consolidated into a single implementation on `Molecule`, where it belongs semantically:
the signed value of a molecule is a property of the molecule, not of the instruction set. `Molecule.fromInt`
and both `extractSignedValue` methods delegate to it, and `mutateData` calls it. The method keeps the name
it already carries in the two existing copies.

Two constraints apply:

- **It stays a `public static int` taking the packed integer, and never constructs a `Molecule`.**
  `Instruction.extractSignedValue` is called from `VirtualMachine:81` and `:269` on every instruction
  fetch. That is why it exists next to `Molecule.fromInt(...).toScalarValue()` at all: it avoids the record
  allocation on the hottest path in the system. A consolidation that removed that property would be a
  regression disguised as cleanup.
- **`datapipeline.utils` may depend on it.** `PackageDependencyRulesTest` declares `datapipeline -> runtime`
  as an allowed edge, and `MoleculeDataUtils` already imports `runtime.Config`.

The consolidation carries no behaviour change and is therefore done first, separately from the fix.

## Solution

`mutateData` sign-extends its input, perturbs in signed space, and masks the result back to the unsigned
pattern before returning. The contract of all five mutators stays uniform: raw 20-bit value in, raw 20-bit
value out.

There is no clamp. The mask is the range handling: a perturbation leaving the signed 20-bit range wraps
modulo 2^20, so `+524287` plus a positive offset continues at `-524288`.

### Why the range wraps

The runtime already treats the DATA value space as a ring, not as an interval. `ArithmeticInstruction`
computes in `long` and writes the result back through `Molecule`:

```java
case "ADD" -> scalarResult = (long) s1.toScalarValue() + s2.toScalarValue();
...
result = new Molecule(s1.type(), (int) scalarResult).toInt();
```

`Molecule.toInt()` masks with `VALUE_MASK` and `Molecule.fromInt` sign-extends on the next read, so an
organism incrementing `DATA:524287` by one holds `-524288` afterwards. This happens silently: division and
modulo by zero call `instructionFailed`, arithmetic overflow does not, and no instruction in the ISA
checks for it.

`+524287` and `-524288` are therefore neighbours in the machine's own semantics. Wrapping is a step of
size one there, not a jump across the value range. Three consequences follow:

- **One value semantics instead of two.** Clamping would have arithmetic treat the space as a ring while
  mutation treats it as an interval, leaving the system with two contradictory answers to the question
  what lies next to `524287`.
- **No absorbing states.** This fix removes two absorbing walls at the sign boundary; clamping would
  install two new ones at `±2^19`. Wrapping leaves none, so every value keeps a full mutation
  neighbourhood and the mutation model stays free of range artefacts.
- **Less code, not more.** The return value has to be masked anyway (see below), and for wrapping that
  mask *is* the range handling. Clamping would add a signed `Math.max`/`Math.min` pair on top of it.

The counter-argument is that closeness on the ring is not closeness in behaviour: a DV component of
`+524287` and one of `-524288` point in opposite directions. That is true, but it argues against clamping
just as much — clamping does not prevent the transition, it freezes the value at the edge. In practice
either choice only affects values within one delta (≈10086 at exponent 0.7) of the boundary, which are far
outside the range in which a DATA value still carries a meaning.

Masking on return is required independently of this. The caller compares against the unsigned sampled
value to detect a no-op:

```java
if (newValue == selectedValue) { /* no-op, skip write */ }
```

Returning a signed value would make `-1` compare unequal to `1048575`, so every null mutation would be
written and logged as a real one.

The debug log needs the same correction. A single statement, shared by all five types, prints the raw
pattern. That is correct for CODE, REGISTER, LABEL and LABELREF, whose values are identifiers rather than
numbers, so only DATA needs sign extension for display. The value is worth the small type distinction:
`Molecule.fromInt` sign-extends, so every other reader of that cell sees `-1` where the log shows
`1048575`. The log does not present a raw truth, it disagrees with the rest of the system — and it is the
one diagnostic that would expose this class of defect. The defective mutation `-1 -> -16385` currently
appears as `DATA:1048575->1032191`, indistinguishable from a healthy scale-proportional step on a large
value.

A display helper `displayValueForLog(int type, int value)` sign-extends for `TYPE_DATA` and passes every
other type through unchanged. It sits next to the existing `typeNameForLog`, which is already a
type-dependent display helper for the same statement, and both run inside the `LOG.isDebugEnabled()`
guard, outside the hot path.

The fix reverses an existing test expectation. `dataClampedToValidRange` asserts that a DATA value of `0`
stays within `[0, 1]` — that expectation is the defective clamp itself, written down as a contract: it
forbids `0` from mutating to `-1`. Correcting it is part of the fix, not an adjustment made to a test that
happens to be in the way.

## Exponent validation

`substitutionRate` is checked against `[0.0, 1.0]` in the constructor; `dataExponent` is taken from the
configuration unchecked. The delta computation does not tolerate that:

```java
int delta = Math.max(1, (int) Math.round(Math.pow(Math.abs(dataValue), dataExponent)));
int offset = random.nextInt(2 * delta + 1) - delta;
```

`Math.round(double)` returns a `long`; the `(int)` cast truncates rather than saturates, so an oversized
exponent yields an arbitrary value, negative ones included.

| exponent | \|value\| = 524288 | \|value\| = 1048575 |
|---|---|---|
| 0.7 | delta 10086 | delta 16384 |
| 1.0 | delta 524288 | delta 1048575 |
| 1.5 | delta 379625062 | delta 1073740288 |
| 1.6 | `nextInt(-1461345633)` — IllegalArgumentException | wraps to −6554 → silently delta 1 |
| 2.0 | silently delta 1 | silently delta 1 |
| 3.0 | silently delta 1 | delta 3145728 |

The exception is not the main problem. For one misconfigured exponent the behaviour depends on the
magnitude of the value the mutation happens to hit: at `1.6` a molecule holding 524288 aborts the
simulation while one holding 1048575 mutates quietly by ±1. At `2.0` nothing fails at all — the mutation
model has silently stopped being scale-proportional and became a constant ±1 step. A run then produces
data that means something other than what the configuration states.

This fix moves the boundary: the largest magnitude reaching `Math.pow` drops from 1048575 to 524288, so
the first breaking exponent shifts from ≈1.5 to ≈1.58. A limit that moves with an unrelated change is a
limit that should be written down.

The valid range is `[0.0, 1.0]`, and it follows from the semantics rather than from the arithmetic:

- `0.0` is meaningful — `Math.pow(v, 0)` is 1 for every `v` (Java defines `0^0` as 1), giving delta 1
  everywhere, a constant ±1 mutation model.
- `1.0` is the natural ceiling — delta equals `|value|`, so a value can reach 0 or double in one step.
  That is the strongest step that is still proportional. Above it the delta exceeds the value and the
  documented intent (small values change relatively strongly, large ones relatively weakly) no longer
  holds.

Both bounds sit far inside the arithmetically safe region, so no separate technical constant is needed.

The invariant belongs to the plugin, not to the configuration format, so it is enforced for both
constructors through one private static validation helper covering `substitutionRate` and `dataExponent`.
This also removes the current asymmetry, where the package-private test constructor bypasses the
`substitutionRate` check.

## Implementation

Test first — the test must fail against the current implementation.

**Step 0 — Consolidation, no behaviour change.** Single sign-extension implementation on `Molecule`;
`Molecule.fromInt`, `Instruction.extractSignedValue` and `MoleculeDataUtils.extractSignedValue` delegate
to it. The stale *"16-bit value"* JavaDoc disappears with the duplicate it describes. The existing test
suite must stay green unchanged — that is what makes this step reviewable on its own.

**Step 1 — Regression test.** A world containing exactly one mutable molecule (`DATA:-1`) makes reservoir
sampling deterministic: the single candidate is always selected. The test runs over seeds `0…49`, matching
the loop pattern every other seeded test in `GeneSubstitutionPluginTest` uses, and asserts two properties:

- for every seed the resulting value lies in `[-2, 0]`;
- at least one seed produces a value other than `-1`.

The second assertion is required because the first one alone also holds for a `mutateData` that does
nothing — the same safeguard `labelFlipsBits` implements through its `verified` counter.

A single seed would not be sufficient. Against the current code the clamp maps every non-negative offset
back to `1048575`, which equals the sampled value, so the no-op branch skips the write and the cell still
holds `DATA:-1` — a value inside `[-2, 0]`. Roughly half of all seeds therefore pass against the defective
implementation. The loop makes the failure certain, and it is precisely the second half of the defect (the
clamp at the sign boundary) that would otherwise hide the first.

**Step 2 — Fix.** Sign extension via the helper from Step 0, signed perturbation and re-masking in
`mutateData`; the `displayValueForLog` helper for the debug statement.

**Step 2 also settles the naming.** The defect exists because a parameter called `dataValue` holds a raw
bit pattern. After the fix the distinction becomes sharper rather than smaller: `mutateData` then holds
both representations at once — the raw pattern at entry and exit, the signed value in between. Java offers
no type distinction that is acceptable on this path, so naming is the only remaining means of keeping them
apart.

The project already has a vocabulary for this and it is used rather than extended: `moleculeInt` / `rawMol`
denote the full packed integer, `rawValue` the masked, unsigned value component (`Molecule.java:73`,
`MoleculeDataUtils.java:30`). Accordingly `mutateData(int rawValue)` with a local `value` holding the
sign-extended number, `selectedValue` becomes `selectedRawValue`, and the reservoir state comment reads
`[2]=raw value`. The other four mutators keep their parameter names: `opcodeValue`, `regValue` and `hash`
are accurate, as those types carry no sign interpretation.

The JavaDoc follows the code. Two statements become wrong with the fix — *"The result is clamped to
`[0, VALUE_MASK]`"* and the `@return` *"clamped to valid range"* — and the contract that is easy to break
here, raw 20-bit pattern in and raw 20-bit pattern out, is stated explicitly instead. The DATA entry in the
class JavaDoc gains the wrap behaviour, since that summary is what a reader sees first.

**Step 2a — Correct the outdated expectation.** `dataClampedToValidRange` expects `[0, 1]` for the input
value `0`. The correct range after the fix is `[-1, +1]`. The test comment states why the sign boundary is
no longer a barrier. The test name no longer describes what it verifies either — nothing is clamped after
the fix — so it is renamed accordingly.

**Step 2b — Exponent validation.** Private static validation helper for `substitutionRate` and
`dataExponent`, called from both constructors. Tests cover the accepted bounds (`0.0`, `1.0`) and
rejection just outside them.

The condition is written positively — `if (!(value >= min && value <= max)) throw` — rather than as the
negated form `value < min || value > max` used today. Both comparisons are `false` for `NaN`, so the
negated form lets `NaN` pass, and the consequences are silent: `random.nextDouble() >= NaN` is `false`, so
every newborn would be mutated regardless of the configured rate, and `Math.round(Math.pow(x, NaN))` is 0,
so delta collapses to 1 and the model quietly becomes a constant ±1 perturbation. Infinities are caught by
either form. `NaN` cannot be reached through HOCON, where it is not a numeric literal, but it can through
the package-private test constructor — the very gap this step closes.

**Step 3 — Invariant coverage.** The fix establishes one property: the perturbation depends on the
magnitude only, so `delta(+v) == delta(-v)`. That is exactly what was violated, and a list of example
intervals does not state it — whoever later works on the delta formula would see intervals instead of the
rule. One parameterised test replaces the list.

Magnitudes `0`, `1`, `100`, `10000`, `524287`, each with both signs where a sign exists, seeds `0…49`, with
`ringDistance(a, b) = min(|a - b|, 2^20 - |a - b|)`. Two assertions per magnitude:

- **upper bound** — for every seed, `ringDistance(result, original) <= delta`;
- **lower bound** — over the 50 seeds, the largest observed `ringDistance` reaches at least `delta / 2`.

The lower bound is what verifies scale proportionality. The upper bound alone is also satisfied by an
implementation that only ever moves by ±1 — precisely the state an out-of-range exponent silently produces
(see *Exponent validation*). Requiring the maximum to reach `delta` exactly instead would be flaky: at
`delta = 724` the chance of hitting it within 50 seeds is 7 %, whereas `delta / 2` is missed with
probability below 1e-15.

The expected deltas are written into the test as a table rather than recomputed with the production
formula; a test that mirrors the implementation confirms a wrong formula along with a right one. The test
constructor uses exponent `0.5`, not the `0.7` of the shipped configuration:

| magnitude | 0 | 1 | 100 | 10000 | 524287 |
|---|---|---|---|---|---|
| delta at exponent 0.5 | 1 | 1 | 10 | 100 | 724 |

**Step 3a — The range boundary wraps.** For `original = 524287` over seeds `0…49`, at least one result has
the opposite sign. Roughly half the seeds wrap, so the assertion is safe, and only wrapping satisfies it.

This needs its own positive assertion because the invariant of Step 3 cannot detect a reintroduced clamp:
the invariant is an upper bound on movement, and a clamp moves the value less, never more. At
`original = 524287` both variants stay within `delta = 724` — wrapping reaches `-524288…-523565`, clamping
stops at `524287`. Wrapping is the one deliberate design decision in this fix, so it is held by a test
rather than by the document alone.

## Reproducibility

Not a constraint on this fix. Reproducibility in this project is defined for a fixed combination of code,
configuration and seed; a code change lies outside what the guarantee covers. Runs made before the fix stay
reproducible with the build they were made with.
