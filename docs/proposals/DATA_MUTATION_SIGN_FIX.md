# Sign Handling in Scale-Proportional DATA Mutation

**Status: TO BE REVIEWED — verified defect, decisions listed at the end.**

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
| `-1` (west/north) | 1048575 | 16394 | ~50 % unchanged by the clamp, otherwise an arbitrary value between −2 and −16395 |

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

## Solution

`mutateData` sign-extends its input, perturbs in signed space, clamps to the signed 20-bit range
`[-524288, 524287]`, and masks the result back to the unsigned pattern before returning. The contract of
all five mutators stays uniform: raw 20-bit value in, raw 20-bit value out.

Masking on return is required. The caller compares against the unsigned sampled value to detect a no-op:

```java
if (newValue == selectedValue) { /* no-op, skip write */ }
```

Returning a signed value would make `-1` compare unequal to `1048575`, so every null mutation would be
written and logged as a real one.

The debug log prints the DATA transition sign-extended, otherwise the diagnostic output stays misleading.

## Implementation

Test first — the test must fail against the current implementation.

**Step 1 — Regression test.** A world containing exactly one mutable molecule (`DATA:-1`) plus a seeded
random provider makes reservoir sampling deterministic. Assert that the mutated value is within
`[-2, 0]`. Against the current code the assertion fails, which documents the defect.

**Step 2 — Fix.** Sign extension, signed clamp and re-masking in `mutateData`; sign-extended debug output.

**Step 3 — Coverage.** Add cases for the sign boundary (`0`, `+1`, `-1`), for the clamp limits
(`-524288`, `524287`), and one large-magnitude value confirming the proportional delta still applies.

## Decisions required before implementation

1. **Clamp or wrap** at `[-524288, 524287]`.

   Arguments for clamping: wrapping maps a value near `+524287` to a value near `-524288` in a single
   mutation step — a jump across the entire value range. That reintroduces exactly the kind of
   discontinuity this fix removes, only relocated to the range boundary. Clamping keeps two absorbing
   walls, but after the fix they sit at `±2^19`, orders of magnitude outside every value the primordial
   uses; an absorbing wall only distorts values that actually reach it.

   Arguments for wrapping: no absorbing states at all — every value keeps a full-sized mutation
   neighbourhood, and the value space becomes homogeneous. This matters only if organisms are expected
   to evolve values near the range boundary.

2. **Reproducibility.** The fix changes mutation behaviour, so existing runs no longer reproduce under the
   same seed.

   Context for the decision: any faithful fix necessarily changes mutation outcomes — preserving
   seed-compatibility would mean preserving the defect. Old runs remain reproducible with old builds.
   The cost is therefore a documentation task (note the behaviour change), not a design constraint.
