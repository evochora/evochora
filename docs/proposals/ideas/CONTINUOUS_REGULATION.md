# Continuous Regulation Layer (EMIT/SENS)

**Status: IDEA — not decided. No design decisions made yet; open questions at the end.**

A gene-regulatory-network-inspired layer that lets behavior depend *gradually* on internal state,
instead of exclusively on hard branches. Complementary to `docs/SCIENTIFIC_OVERVIEW.md` §4.4/§4.5:
those sections describe signaling molecules in the environment as a *communication* mechanism for
coordination; this idea is an organism-internal *regulation* substrate. The environment variant is
a possible later extension that would bridge to §4.4/§4.5.

## Problem

Behavioral decisions in evoASM are exclusively hard branches: a `GTI`-style comparison either jumps
or does not. Every decision is binary, and a mutation at a decision site flips behavior completely
instead of shifting it. The ingredients for graduality already exist but always terminate in
all-or-nothing evaluation:

- decision thresholds are DATA molecules whose mutation is scale-proportional
  (smooth — after [DATA_MUTATION_SIGN_FIX](../DATA_MUTATION_SIGN_FIX.md));
- fuzzy label matching computes a graded score
  (`hamming × hammingWeight + distance + foreignPenalty`), but evaluates it as an argmax.

There is no mechanism by which behavior can depend on internal state in a *dosed* way. In terms of
the fitness landscape: the genome→behavior map is discontinuous exactly at the sites that mutate
most (thresholds, jump targets, decision structure).

## Idea

Three elements that produce continuity without floats in the genome:

1. **Concentration state.** The organism gets a signal field `hash → concentration` — a new
   organism-internal state (analogous to ER/SR: integer, capped, serialized).
2. **EMIT / SENS instructions.** `EMIT <hash> <amount>` releases a signal. `SENS <hash>` reads the
   **Hamming-weighted sum** of all signals near the hash — the existing fuzzy-matching machinery
   (`PreExpandedHammingStrategy` pattern), evaluated as a weighted sum instead of an argmax. This
   is graded binding affinity (Banzhaf's artificial gene regulatory networks) built from existing
   infrastructure.
3. **Decay.** Concentrations decay exponentially per tick (one integer multiplication per signal),
   producing temporal dynamics: memory, ramps, oscillations — instead of static flags.

Under mutation, continuity acts twice: a bit flip in a signal hash shifts binding strengths
*gradually* (Hamming proximity instead of exact match), and amounts/thresholds are DATA values
under scale-proportional perturbation. The most-mutated part of behavior becomes continuous while
the replication core stays discrete.

## Hurdles

1. **Selection pressure** (the hardest question): evolution only uses the layer if it is cheaper
   or more robust than hard `GTI` chains. The cost model must make graded regulation pay off,
   otherwise this is a dead feature.
2. **Chicken-and-egg**: the primordial must use the layer meaningfully from the start (at least
   one decision via SENS instead of a hard comparison), otherwise selection has nothing to act on.
3. **Hotpath**: decay is O(number of signals) per organism per tick — uncritical with a cap, but
   must be demonstrated. Runtime state also touches serialization (`RestoreBuilder`), protobuf,
   and the visualizer's organism state view.
4. **Environment extension**: signals as environment molecules (diffusion, morphogens,
   inter-organism gradients) would connect to §4.4/§4.5 — at grid-wide cost. Organism-internal
   is the cheap first stage.

## Open questions

1. Signal identity: reuse the 19-bit label-hash space (shared Hamming machinery, label mutation
   operators apply directly) or a separate signal space?
2. SENS semantics: weighted sum only, or also weighted-max / threshold variants?
3. How does the layer couple back into control flow — SENS writes a register that existing
   conditionals consume, or dedicated graded-branch instructions?
4. Decay rate: global constant, per-signal (evolvable, e.g. value-encoded), or configurable policy?
5. What in the primordial becomes the first SENS-based decision (harvest-vs-reproduce timing is
   the obvious candidate), and how is the selection-pressure hurdle (1) validated experimentally?
6. Interaction with FORK: does the child inherit the parent's concentrations, start empty, or
   receive a configurable fraction?
