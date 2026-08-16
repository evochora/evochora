# Mutational Robustness Assay

**Status: IDEA — not decided. Open design questions at the end.**

A measurement instrument that quantifies the local structure of the fitness landscape around a genome.
It does not change the runtime; it is a new headless CLI command that uses compiler and simulation as
libraries.

## Problem

Every statement about the ruggedness of Evochora's fitness landscape is currently unmeasured
plausibility. Examples of claims that have been made and cannot be tested today:

- the sign defect in scale-proportional DATA mutation ([DATA_MUTATION_SIGN_FIX](../DATA_MUTATION_SIGN_FIX.md))
  "probably explains a substantial part of observed lethality";
- `error-penalty-cost = 100` against `base-energy = 1` "is a cliff" and lowering it would smooth the
  landscape;
- raising the label-matching `tolerance` from 2 to 3 "widens the neutral network" for jump targets.

Each of these is a concrete, decidable question — but there is no instrument that decides it. Without
one, tuning decisions on the ISA, the mutation operators, or the thermodynamics are made by intuition.

## Idea

The standard metric of the RNA folding literature (Schuster/Fontana neutral-network studies), applied
to Evochora: systematically generate **single-mutation variants** of a genome, run each variant in
isolation under identical seeded conditions for K ticks, and classify the outcome:

| Class | Definition |
|---|---|
| lethal | dies without ever reproducing |
| sterile | survives K ticks but never reproduces |
| impaired | reproduces, but slower than the unmutated baseline |
| neutral | reproduces at baseline rate |
| improved | reproduces faster than baseline |

The output is a distribution — the mutational fingerprint of the genome. Every change to the ISA,
the mutation operators, or the thermodynamic configuration then gets a before/after measurement:
a change that moves mass from *lethal* toward *neutral* smooths the landscape; a change that does not
is rhetoric. The RNA literature identifies exactly this distribution shape (high neutral fraction
with preserved access to variation) as the signature of evolvable systems.

## Why the building blocks already exist

- **Deterministic, seeded simulation**: `IRandomProvider` — identical seeds reproduce identical runs.
- **Isolated mutation operators**: the four birth plugins in `runtime/worldgen/`
  (`GeneSubstitutionPlugin`, `GeneInsertionPlugin`, `GeneDeletionPlugin`, `GeneDuplicationPlugin`)
  encapsulate exactly the mutation spectrum the simulation uses; the assay can reuse their logic to
  enumerate realistic variants.
- **Headless CLI pattern**: `CompileCommand` / `InspectCommand` show how to run compiler + runtime
  without the pipeline.
- **Replication detector**: `GenomeHasher` already computes a genome hash that ignores DATA molecules
  and is invariant under the private label namespace of `LabelRewritePlugin` — a child with the
  parent's hash is a faithful replication.
- **Problem size**: the primordial owns roughly 2,500–3,000 cells (comment in
  `GeneDuplicationPlugin`), so exhaustive single-mutant enumeration is on the order of a few thousand
  short simulation runs — parallelizable, since runs are independent.

## First use cases

1. Before/after distribution for the [DATA_MUTATION_SIGN_FIX](../DATA_MUTATION_SIGN_FIX.md) —
   measures the real-world effect of a verified defect fix.
2. Config sweeps with zero code changes: `error-penalty-cost` ∈ {100, 10, 1} and label-matching
   `tolerance` ∈ {2, 3}.
3. Long-term: the decision basis for any "dumb variation" mechanisms (copy-error mutation at POKE,
   cosmic-ray substitution), which are documented as open goals in `docs/SCIENTIFIC_OVERVIEW.md` and
   whose viability depends on the intrinsic robustness of the encoding.

## Open design questions

1. **What counts as "reproduced"?** Candidate definition: a child organism exists after K ticks
   *and* its genome hash equals the parent's. FRKS success alone is too weak — an organism that forks
   a corrupted child has not replicated. An open sub-question is whether *imperfect but viable*
   offspring should form their own class instead of counting as failure.
2. **Which mutation spectrum?** Two modes serve different questions:
   - *Operator spectrum*: exactly the variants the four birth plugins would produce — measures the
     landscape evolution actually experiences today.
   - *Naive spectrum*: e.g. every molecule value ±1, random bit flips, random type-preserving value
     replacement — measures the intrinsic robustness of the encoding itself, independent of the
     intelligence of the operators. This is the relevant metric for "dumb variation" decisions.
   Both modes seem worth having; the operator spectrum as default.
3. **Assay world calibration**: world size, energy placement, and K must be chosen so that the
   *unmutated* primordial replicates reliably — otherwise the assay measures the world, not the
   mutation. This calibration should be part of the assay (a baseline run that must pass before
   variants are evaluated).
4. **Exhaustive vs. sampled**: full enumeration of all single-mutation variants, or random sampling
   for fast iteration? Both, with sampling as an option, seems plausible.
5. **Multi-seed robustness**: a single seed per variant makes the classification sensitive to
   stochastic world events (energy spawn positions). N seeds per variant multiply cost; the right N
   is an empirical question the baseline calibration can answer.
6. **Output format**: distribution summary plus a per-position map (which genome regions are fragile,
   which are neutral) — the per-position map is what localizes fragility and makes results actionable.
