# Dumb Variation Package — Encoding Robustness + Copy-Error Mutation

**Status: IDEA — not decided. No design decisions made yet; open questions at the end.**

`docs/SCIENTIFIC_OVERVIEW.md` §4.3 already names the target mechanisms as open goals:
environment-driven mutation ("cosmic radiation"), copy-error mutation tied to POKE, and somatic
mutation — and calls them "architecturally straightforward". Architecturally that is true (the
plugin seams exist). This document records what §4.3 does not: the *evolutionary* prerequisite
analysis — these mechanisms only make sense as the last part of a three-part package.

## Problem

Today, variation arises exclusively at birth through four operators that produce only valid
constructs by design: `GeneSubstitutionPlugin` picks opcodes from lookup tables of registered
instructions, `GeneInsertionPlugin` builds only syntactically correct instruction chains,
`GeneDeletionPlugin` removes only label-to-label blocks. The neutrality of the fitness landscape
is therefore **extrinsic** — it lives in the intelligence of the operators, not in the encoding.

The encoding itself is brittle (verified):

- 235 of 1,048,576 CODE values are valid instructions (≈ 0.022 %), with no synonymy;
- 48 of ~1M operand values hit a valid register ID (`RegisterBank`: 8 banks in a 2048-entry table);
- operands are not type-checked at runtime — any molecule at an operand position is consumed as a
  number; an invalid value raises `instructionFailed` plus the error penalty.

Biology is the other way around: mutation is a dumb copy error, and robustness lives
**intrinsically** in the code (64 codons → 20 amino acids; synonymous mutations are neutral).

The scientifically interesting part of §4.3's mechanisms — variation decoupled from the birth
event, Eigen error-catastrophe dynamics as an *emergent* phenomenon rather than a design problem —
presupposes dumb variation. Introduced on today's encoding, dumb variation would produce almost
exclusively lethals: one would measure the brittleness of the encoding, not evolutionary dynamics.

## The package (three parts, only meaningful together)

1. **Degenerate opcode encoding** (codon principle): every instruction gets synonymous encodings —
   e.g. low-order bits are ignored, or unknown opcodes map to the nearest registered instruction
   of the same family (the family/operation/variant structure of `OpcodeId` makes "nearest"
   well-defined). Point mutations in opcodes then become frequently neutral or semantically close,
   instead of "NOP + error penalty".
2. **Operand totality** (Push principle, "no invalid programs"): interpret register IDs modulo
   bank size instead of failing — every value denotes a valid register. Analogously, examine for
   each operand class whether "invalid" can be replaced by "nearest valid interpretation".
3. **Only then: dumb variation sources** from §4.3 — copy-error mutation at POKE (variation coupled
   to replication activity, not to the birth event) and/or cosmic-ray substitution (fully
   decoupled).

### Supporting property, previously undocumented: automatic reading-frame resynchronization

Operands carry their own molecule types (DATA/REGISTER/LABELREF), and non-CODE molecules are
treated as free-to-skip NOPs at instruction boundaries (`VirtualMachine.plan()` STRICT_TYPING gate
+ `Organism.skipNopCells`). A derailed IP therefore lands on the next real instruction by itself —
frameshift-like damage partially self-heals at instruction boundaries. This is a structural
advantage over classical assembler A-life substrates and supports the viability of the package.

## Hurdles

1. **The decision basis does not exist yet.** Whether and how much parts 1+2 smooth the landscape
   is exactly what the [MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md) measures in
   its *naive spectrum* mode. The assay is a hard prerequisite; without it the package is blind
   flight.
2. **Semantic risks.** Modulo registers may favor degenerate programs (everything aliases into a
   few registers); nearest-valid-opcode changes the penalty model (today junk execution is
   expensive, which disciplines the population).
3. **Error catastrophe is a real risk.** Copy-error at POKE couples mutation rate to replication
   activity — precisely the dynamic that historically forced the entropy mechanism (§2.5).
   Rate calibration is critical.
4. **Determinism/performance.** Cosmic radiation touches the per-tick hotpath (additional random
   draws); POKE copy-error touches the instruction hotpath.

## Open questions

1. Which synonymy scheme for part 1: ignored low-order bits, nearest-in-family mapping, or an
   explicit redundant opcode table? (They differ in how much of the value space becomes neutral
   vs. semantically-close.)
2. Does operand totality apply only to register IDs, or also to label hashes (always "valid"
   already via fuzzy matching) and vector components?
3. What happens to the error-penalty model when far fewer operations can fail?
4. Copy-error at POKE vs. cosmic radiation first — or both, as separately switchable plugins?
5. Which mutation-rate regime avoids error catastrophe, and can the threshold itself be made an
   experimental observable (Eigen threshold as measurement target rather than design constraint)?
6. Should the reading-frame resynchronization property be documented in `SCIENTIFIC_OVERVIEW.md`
   independently of this package?
