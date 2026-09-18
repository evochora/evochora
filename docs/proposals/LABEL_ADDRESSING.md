# Label Addressing

**Status: AGREED — every decision below is made; implementation pending.**

## Problem

A jump names its target by a label value, and the runtime resolves it by fuzzy matching. Three
properties of today's resolution work against evolution.

**Construction sites are jump targets.** A label written with a non-zero marker belongs to a body
an organism is still building for a child. It is indexed like any other label and counts as
foreign, even for the organism writing it. A reference that has lost its own label can therefore
resolve into a half-built body — the parent's own child or a neighbour's — and the instruction
pointer runs through unfinished code. This is a cliff: the outcome is almost always fatal, and it
is reached by a single mutation.

**Foreign code use is never heritable.** Every birth XORs all labels and label references of the
newborn with a uniformly random mask, so that every individual lives in a label namespace of its
own. A reference that resolves into another organism's code matches one individual; the child's
references are moved by a random mask relative to that individual, and the host's children are
moved by another. Long runs show foreign code being executed, but the relation cannot be
inherited on either side, so parasitism cannot evolve.

**A lost label is replaced by an arbitrary one.** Label values are hashes of names and carry no
meaning, and foreign namespaces are random relative to one's own. With many labels in the world a
reference without an own match almost always finds some foreign label within tolerance — of any
function. A lost replication entry point is as likely to resolve to a foreign harvesting routine
as to a foreign replication routine.

Two further findings belong to the same area. The selection among duplicated labels collapses
when a *reference* mutates: two exact duplicates share the jumps by weighted lottery, but after a
one-bit flip of the reference both are inexact, the lottery is skipped, and the nearer duplicate
takes every jump — a mutation that tolerance is meant to keep neutral is not neutral. And label
values are limited to 19 of the 20 value bits only so that they read as non-negative decimal
numbers; the limit halves the label space for the sake of a display format.

## Solution

### Addresses are stable by descent

A label keeps its value across births; only mutation changes it. Homologous labels of related
organisms therefore carry the same address, up to their mutations. A reference without an own match
resolves to the homologous label of a nearby organism, and its children inherit exactly that
relation. Isolation between individuals no longer comes from the address but from the matching
rule below.

### Matching rule

Resolution proceeds in stages of Hamming distance, 0 up to `tolerance`. A stage is *occupied* when
at least one candidate lies on it; only the best occupied stage is considered, stages are never
mixed.

1. **Own labels first.** The labels owned by the executing organism are examined. If any lies
   within `tolerance`, the target is one of the labels on the best occupied stage:
   - one candidate: it is the target; no distance is computed and no random number is drawn;
   - several candidates (duplicates): with `selectionSpread` greater than zero one is drawn by
     lottery, weighted by inverse distance as today, from the organism's random source; with
     `selectionSpread` zero the nearest is the target.

   An own label within tolerance beats every foreign label, however near the foreign one is.
2. **Foreign labels only without an own match.** If no own label lies within tolerance and
   `foreignReach` is not negative, labels of other owners — unowned ones included — are examined.
   A foreign label is *reachable* when `hammingWeight × hammingDistance + distance ≤ foreignReach`.
   Among the reachable labels the best occupied stage is taken, and on it the nearest label is the
   target, deterministically. A negative `foreignReach` means no foreign label is ever reachable.
3. **No match.** The lookup fails; the instruction fails as it does today.

**Ties.** Candidates at equal distance are ordered by position (flat index). An organism with an
even ID takes the first of them, one with an odd ID the last. The rule is the same for own
and foreign candidates and keeps the choice free of a spatial bias across the population.

**Foreign stages are bounded.** The foreign search examines stages up to the smallest of
`tolerance`, 3, and `foreignReach / hammingWeight`.

`distance` is the toroidal Manhattan distance from the calling position to the label, as today.
Which instructions may land on a foreign label is unchanged: jumps and calls may, `SKJ*`, `PSLI`
and `LRLI` refuse a foreign target.

### Marked labels are not targets

A label enters the label index only while its marker is 0. A label written with a non-zero marker
is invisible to every lookup, including those of the organism writing it. It enters the index when
the marker falls to 0: owned by the child at `FORK`, unowned when its owner dies. This rule lives
in `LabelIndex`, ahead of the matching strategy, and therefore holds for every strategy.

### Clade flip

With probability `cladeFlipRate` a newborn's labels and label references are all XORed with the
same mask, which has exactly one bit set, drawn uniformly from the uppermost `cladeBits` bits of
the label value. Because the flip is coherent, nothing changes inside the organism; between
organisms the Hamming distance of homologous labels grows with genealogical distance, within the
`cladeBits` positions. Close relatives stay reachable for each other, distant clades drift apart,
and a host lineage escapes its parasites with all labels at once, while a parasite lineage follows
by its own flips. The remaining low bits are never touched by a flip and identify a gene across
clades. `cladeFlipRate = 0` draws no random number and leaves addresses purely stable by descent.

The flip is recorded on the newborn as a mutation record of kind `label-rewrite` with the mask as
its parameter, exactly as today's rewrite is; genome hashing and the namespace composition of the
data pipeline work for any mask and stay as they are.

### The strategy owns matching and address inheritance

Matching and the inheritance of addresses are two halves of one model and are configured together.
The strategy interface moves to `org.evochora.runtime.spi.ILabelMatchingStrategy` and is reduced
to what any strategy can answer:

| Method | Purpose |
|---|---|
| `findTarget(searchValue, codeOwner, callerCoords, environment, random)` | resolve a reference; `-1` when nothing matches |
| `addLabel(labelValue, flatIndex, owner)` | a label with marker 0 appeared |
| `removeLabel(labelValue, flatIndex, owner)` | it disappeared |
| `changeOwner(labelValue, flatIndex, oldOwner, newOwner)` | its cell changed hands |
| `valuesMatch(searchValue, labelValue)` | whether a reference value can address a label value at all, regardless of owner and position |
| `birthMask(randomProvider)` | the XOR mask for a newborn's labels and references; default `0`, no rewrite |

The Hamming-specific getters, `updateMarker` and `getCandidates` leave the interface.
`GeneInsertionPlugin` asks `valuesMatch` instead of reading the tolerance. `LabelEntry` is removed.

The core applies the mask: after the birth handlers of a newborn have run, `Simulation` asks the
strategy for the mask and, if it is not zero, `org.evochora.runtime.label.LabelRewrite` XORs every
`LABEL` and `LABELREF` cell the newborn owns and records the `label-rewrite` mutation record. The
mask is drawn from the root random provider, as every sequential part of a tick draws.
`LabelRewritePlugin` is removed, and with it the `pipeline.core-plugins` mechanism, whose only
entry it was.

The default strategy is `org.evochora.runtime.label.HammingLabelMatchingStrategy`; it replaces
`PreExpandedHammingStrategy`, which is removed. It is selected and configured in the existing
`label-matching { className, options }` block of the runtime configuration. A configuration that
still names the old class or appends `${pipeline.core-plugins}` fails at start.

| Option | Default | Meaning |
|---|---|---|
| `tolerance` | `2` | largest Hamming distance at which a label matches |
| `hammingWeight` | `50` | reach a foreign label loses per differing bit |
| `selectionSpread` | `50` | half-weight distance of the lottery among own duplicates; `0` = nearest |
| `foreignReach` | `250` | bound of `hammingWeight × hammingDistance + distance` for foreign labels; negative = never |
| `cladeFlipRate` | `0.05` | probability per newborn of a clade flip |
| `cladeBits` | `8` | number of uppermost label bits a clade flip can hit |

`foreignPenalty` no longer exists. `reference.conf` carries the defaults with the full
documentation; `config/evochora.conf` mirrors the block with short comments.

### Index

`HammingLabelMatchingStrategy` keeps two structures, both mutated only from the simulation thread
outside the parallel wave and read concurrently inside it:

- **Own labels:** per owner, the label values and flat indexes of its labels in parallel arrays
  ordered by flat index. An own lookup walks the array once, XORs and counts bits per entry. Its
  cost does not depend on `tolerance`.
- **Foreign labels:** label value → flat indexes and owners in parallel arrays ordered by flat
  index, plus the bit set of occupied values. Per stage the reach leaves a radius
  `foreignReach − hammingWeight × stage`; because the first coordinate is the most significant
  part of the flat index, all labels within that radius lie in one contiguous range of the array
  (two on a torus), which is located by binary search and scanned. The search ends on the first
  stage that yields a reachable label.

### Label values use all 20 bits

A label value uses the full value field. `Config.LABEL_VALUE_MASK` is removed in favour of
`Config.VALUE_MASK`; the compiler's label hash, the two mutation plugins that flip or invent label
bits, the recorded clade mask and the tick benchmark follow. With 20 bits, `cladeBits = 8` is the
upper two of five hexadecimal digits and the gene-identifying bits are the lower three.

### Label values are unsigned, and shown in hexadecimal

`Molecule.extractTypedValue(moleculeInt)` returns the value as the molecule's type defines it:
unsigned for `LABEL` and `LABELREF`, sign-extended for every other type. The environment, organism
and mutation endpoints send this value, so a label value reaches the browser in the range
`0…FFFFF`, as the key the artifact's label maps use.

Wherever a person reads a label value it is five uppercase hexadecimal digits with leading zeros
and no prefix: `L:3A7F1`, `LR:3A7F1`, `[#3A7F1]` in the source view. One formatter serves the
visualizer (`ValueFormatter.formatLabelValue`), used by the organism panel, the environment
tooltips, the source annotations and the cell text at the highest zoom level, where the value is
drawn on two lines, two digits above three. Backend texts follow: `Molecule.toString`, the failure
messages of jumps and location instructions, the storage inspection command and the trace
consumer.

## Cost

The lookup is on the hot path of every jump and call. The own path replaces a hash lookup into a
world-wide map, a distance computation and a random draw per jump by one pass over the organism's
own labels, and draws a random number only among duplicates. The foreign path runs only for
references without an own match; its cost grows with `foreignReach` and with the number of
homologous labels in range, and it is no longer a rare path once parasites are common. Neither
statement is measured. The implementation therefore stops after the matching commit for a
measurement on the real code (see below); a result that shows the own path slower than today ends
the work until the numbers have been looked at.

Every run changes: the same seed gives a different trajectory.

## Implementation

One pull request, five commits and a measurement checkpoint. Core logic is written by the main
agent; mechanical adaptation of tests, constants and comments is delegated to a cheaper model and
verified by diff and test run. Each commit ends with `./gradlew check`.

**Commit 1 — Marked labels are not targets.** `LabelIndex` indexes a label only with marker 0 and
takes one call for a cell that is released (position, molecule, new owner) in place of the separate
owner and marker updates; `Environment.transferOwnership` and `clearOwnershipFor` use it. The marker
leaves `LabelEntry` and the strategy interface. Tests: a marked label is no target before `FORK`,
not even for its writer; it is the child's own label after `FORK`; it is unowned after the parent's
death; a resume from a snapshot taken while a child is under construction resolves every lookup as
the uninterrupted run does.

**Commit 2 — Strategy interface, Hamming strategy, clade flip.** The interface moves to
`runtime.spi` in its reduced form; `HammingLabelMatchingStrategy` with both index structures, the
staged rule, `foreignReach`, the tie rule and `birthMask`; `LabelRewrite` and its call in
`Simulation`; removal of `PreExpandedHammingStrategy`, `LabelEntry`, `LabelRewritePlugin` and
`pipeline.core-plugins`; `Environment` passes the previous owner when a label is overwritten;
`GeneInsertionPlugin` uses `valuesMatch`; `reference.conf`, `config/evochora.conf`,
`tools/trace/trace.conf` and `assembly/example.conf`; `SimulationBenchmark` places organisms with
shared label values. Tests: stages and the lottery on the best stage only; a reference flip leaves
the distribution among duplicates unchanged; the reach threshold per bit and the negative reach;
the tie rule; the range search across the torus seam; `birthMask` draws nothing at rate 0 and hits
only the uppermost `cladeBits`; the core rewrite step; a minimal second strategy in the test
sources, loaded by class name through the strategy factory.

**Measurement checkpoint — before anything else is built.** JMH tick benchmark, `origin/main`
against commit 2, decision profile with `selectionSpread=50`. Real-run comparison over 10 M ticks:
`origin/main` against commit 2 with `foreignReach = -1`, `cladeFlipRate = 0`, and against commit 2
with the defaults; two rounds in swapped order, compared by wall seconds per executed instruction
because behaviour differs on purpose.

**Commit 3 — 20-bit label values.** `RuntimeInstructionSetAdapter.labelValue`, `Config`,
`GeneSubstitutionPlugin` (bit choice and result mask), `GeneInsertionPlugin` (invented references),
`LabelNamespaceMask` (recorded mask), `SimulationBenchmark`, the comments in both configuration
files. Tests: the assertions that state 19 bits, and regression cases with the top bit set for mask
composition, mutation translation, procedure name resolution and both mutation plugins.

**Commit 4 — Unsigned label values and hexadecimal display.** `Molecule.extractTypedValue` and its
use in `EnvironmentController`, `OrganismStateConverter` and `LineageMutationTranslator`; the
backend texts; `ValueFormatter.formatLabelValue` and its callers in the visualizer.

**Commit 5 — Documentation.** `docs/SCIENTIFIC_OVERVIEW.md` and `README.md` (fuzzy addressing,
label namespace rewriting), `docs/ASSEMBLY_SPEC.md` (marked labels, the matching rule),
`docs/BENCHMARKING.md` (`selectionSpread`), the `evoasm` skill; wording of the flagship documents
and the assembly specification is proposed and approved hunk by hunk. This document moves to
`docs/outdated/proposals/accomplished/`.

**Before the pull request.** Architecture review of the branch, merge of `origin/main`,
`./gradlew check`, and a closing measurement with the production configuration.
