# Replication Primitives — DIVIDE vs. Block Copy

**Status: IDEA — not decided. This one is an identity decision for the project; the option
spectrum is documented neutrally, one stated preference is marked as such.**

`docs/SCIENTIFIC_OVERVIEW.md` context: §2.5 presents *programmed* self-replication as the core
demonstration of the platform; §2.2 documents deliberate restraint regarding high-level primitives
(the omitted whole-footprint move instruction as a "conscious design choice"). A replication
primitive appears nowhere — this idea is new ground and touches project identity more than any
other in `ideas/`.

## Problem

Replication capability currently hangs on a ~1000-instruction algorithm (read lines to the stack,
write them back, manage the shell, mark ownership, FORK — `assembly/primordial/lib/reproduce.evo`).
This is the single highest cliff in the fitness landscape: a large fraction of mutations landing
*anywhere* in this algorithm destroys replication capability as a whole — and with it every future
of the lineage. The algorithm also occupies most of the genome and execution time, leaving little
room for evolvable *behavior* — which is where the graded-chemistry and continuous-regulation
ideas would create gradients.

Existing mitigations (NOP padding, redundant jumps/RETs, fuzzy labels, max-skip recovery) soften
but do not remove the structural all-or-nothing property.

## Option spectrum

### Pole A — DIVIDE: replication as physics

A primitive that duplicates the organism's marked region, with copy errors applied by the physics.

- Effect: the wall between "can replicate" and "cannot" nearly disappears; evolved computation
  concentrates entirely on behavior, where gradients exist.
- Price (fundamental): the evolution of the replication mechanism *itself* — one of the most
  interesting phenomena of Tierra-class systems and a distinguishing feature of Evochora — becomes
  unobservable by construction. Also collides head-on with the §2.2 design philosophy of not
  abstracting away evolutionary challenges.

### Pole B — Status quo

Replication stays a ~1000-cell program; fragility stays structural. The research object
"evolution of replication" stays fully intact.

### Middle ground — copy primitives below the replication level

A block- or line-copy instruction — the polymerase analogy: biology provides high-performance
copying machinery as a *primitive*, whose *orchestration* (when, where, how much, error handling)
remains evolvable. The replication algorithm shrinks from ~1000 to an estimated 50–100
instructions; the wall gets much lower but does not vanish, and replication remains an evolving
program.

Side effect worth noting: such a primitive would be the natural attachment point for copy-error
mutation — the [DUMB_VARIATION_PACKAGE](DUMB_VARIATION_PACKAGE.md) would couple mutation to the
copy primitive instead of to POKE, closing the polymerase analogy (mutation rate as a property of
the copying machinery).

## The underlying identity question

**What should Evochora study — the evolution *of* replication, or evolution *under*
replication?** Pole A optimizes for the latter, Pole B for the former; the middle ground tries to
keep both, at the risk of diluting both (the copy loop becomes more trivial *and* the wall
remains).

*Stated preference (not decided):* no full DIVIDE; the middle ground is worth examining.

## Open questions

1. Granularity of a copy primitive: single line, rectangular block, or "all owned molecules along
   a vector until shell"?
2. Cost model: energy/entropy per copied molecule (keeping the thermodynamic coupling of
   replication) or per invocation?
3. Ownership and marker semantics: does the primitive mark copies like the manual POKE loop does?
4. Interaction with the defensive-replicator property (abort on foreign molecules): built into the
   primitive or left to the orchestrating program?
5. If copy-error mutation attaches here (see side effect above): is the error rate a world
   constant, or a property of the primitive's invocation (potentially evolvable)?
6. Measurement: before/after fingerprints via
   [MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md) — how much of the lethal mass in
   the assay actually sits in the replication algorithm? (This quantifies the problem before any
   decision.)
