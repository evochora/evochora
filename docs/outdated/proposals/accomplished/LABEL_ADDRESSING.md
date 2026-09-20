# Label Addressing

**Status: ACCOMPLISHED 2026-09-19 — implemented on branch `label-addressing`; where the implementation left the plan is recorded under Outcome at the end.**

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
   `foreignReach` is not negative, the labels in cells of other owners and in unowned cells are
   examined. A foreign label is *reachable* when
   `foreignReachDeductionPerBit × hammingDistance + distance ≤ foreignReach`. Among the reachable
   labels the best occupied stage is taken, and on it the nearest label is the target,
   deterministically. A negative `foreignReach` means no foreign label is ever reachable.
3. **No match.** The lookup fails; the instruction fails as it does today.

**Ties.** Candidates at equal distance are ordered by position (flat index). An organism with an
even ID takes the first of them, one with an odd ID the last. The rule is the same for own
and foreign candidates and keeps the choice free of a spatial bias across the population.

**Foreign stages are bounded.** The foreign search examines stages up to the smallest of
`tolerance`, 3, and `foreignReach / foreignReachDeductionPerBit`.

**Distance.** `distance` is the Manhattan distance from the calling position to the label. It
wraps around the world edge only in a toroidal world; in a bounded world it does not, and neither
does the range search below. Today's distance wraps regardless of the topology.

What an instruction does with the resolved target is unchanged: jumps and calls go wherever the
lookup leads; `SKJ*`, `PSLI` and `LRLI` fail when the target cell is owned by another organism and
accept an unowned one. Unowned labels do not arise in the shipped configuration, where
`DecayOnDeath` clears a dying organism's cells before they are released; they exist in
configurations without that handler.

### Marked labels are not targets

A label enters the label index only while its marker is 0. A label written with a non-zero marker
is invisible to every lookup, including those of the organism writing it. It enters the index when
the marker falls to 0: owned by the child at `FORK`, unowned when its owner dies without a handler
clearing the cell. Overwriting or clearing a marked label leaves the index untouched. This rule
lives in `LabelIndex`, ahead of the matching strategy, and therefore holds for every strategy.

### Namespace flip

With probability `namespaceFlipRate` a newborn's labels and label references are all XORed with
the same mask, which has exactly one bit set, drawn uniformly from the uppermost `namespaceBits`
bits of the label value. Because the flip is coherent, nothing changes inside the organism.
Between two lineages the difference performs a random walk over the `namespaceBits` positions: it
grows with genealogical distance at first and then saturates, at a mean of half the positions.
With `namespaceBits = 8` and a tolerance of 2, about one in seven unrelated lineages stays within
tolerance of each other at any moment; each differing bit additionally deducts reach. The small
space is chosen on purpose: within the few hundred generations of a run, lineages separate and
meet again instead of separating once and for all. The remaining low bits are never touched by a
flip and identify a gene across lineages. `namespaceFlipRate = 0` draws no random number and
leaves addresses purely stable by descent.

*Expectation, to be observed in runs, not a verified property:* close relatives stay reachable
for each other; a host lineage escapes its parasites for a time, with all its labels at once,
because every label moves together; a parasite lineage follows by its own flips, since following
by point mutation of a single reference is improbable.

The flip is recorded on the newborn as a mutation record of kind `label-rewrite` with the mask as
its parameter, exactly as today's rewrite is, and it names the class of the strategy that chose
the mask as its source. Genome hashing and the namespace composition of the data pipeline work
for any mask and stay as they are.

### The strategy owns matching and address inheritance

Matching and the inheritance of addresses are two halves of one model and are configured together.
The strategy interface keeps its name, moves to `org.evochora.runtime.spi.ILabelMatchingStrategy`
and is reduced to what any strategy can answer:

| Method | Purpose |
|---|---|
| `initialize(properties)` | the shape and topology of the world, told once before the first label; default: nothing to prepare |
| `findTarget(searchValue, codeOwner, callerCoords, random)` | resolve a reference; `-1` when nothing matches |
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
mask is drawn from the root random provider, as every sequential part of a tick draws. That the
rewrite follows the mutation operators, which the namespace composition of recorded values relies
on, is thereby fixed in the core instead of following from the order of a configuration list.
`LabelRewritePlugin` is removed, and with it the `pipeline.core-plugins` mechanism, whose only
entry it was.

The default strategy is `org.evochora.runtime.label.HammingLabelMatchingStrategy`; it replaces
`PreExpandedHammingStrategy`, which is removed. It is selected and configured in the existing
`label-matching { className, options }` block of the runtime configuration.

| Option | `reference.conf` | Meaning |
|---|---|---|
| `tolerance` | `2` | largest Hamming distance at which a label matches |
| `selectionSpread` | `50` | half-weight distance of the lottery among own duplicates; `0` = nearest |
| `foreignReach` | `250` | bound of `foreignReachDeductionPerBit × hammingDistance + distance` for foreign labels; negative = never |
| `foreignReachDeductionPerBit` | `50` | what each differing bit deducts from the reach of a foreign label |
| `namespaceFlipRate` | `0.05` | probability per newborn of a namespace flip |
| `namespaceBits` | `8` | number of uppermost label bits a namespace flip can hit |

`foreignPenalty` and `hammingWeight` no longer exist. The strategy validates its option block in
the constructor and rejects a key it does not know, naming `foreignReach` and
`foreignReachDeductionPerBit` as the replacements of the two removed keys; a configuration that
still names the old class or appends `${pipeline.core-plugins}` fails at start as well.
`reference.conf` carries the defaults with the full documentation; `config/evochora.conf` mirrors
the block with short comments. A strategy built without a configuration — tests and embedders —
keeps `selectionSpread = 0`, as today, so that it stays deterministic; its other values equal the
table.

### Index

`HammingLabelMatchingStrategy` holds every label for both kinds of lookup; the structures are
mutated only from the simulation thread outside the parallel wave and read concurrently inside it.
Labels in unowned cells have no owner to look them up as its own and are held for the foreign
search only.

- **Own labels:** `OwnLabelTable`, one open-addressing hash table of primitive longs over all
  owners, answers in one probe which label of an organism carries exactly the searched value — the
  case of almost every jump. Only for a value the organism holds not at all or several times its
  labels are walked: per owner, label values and flat indexes in parallel arrays ordered by flat
  index, one XOR and bit count per entry. The cost of that walk does not depend on `tolerance`; it
  grows with the number of labels the organism owns.
- **Foreign labels:** `TiledLabelIndex` holds all labels in one open-addressing hash table of
  primitive longs, plus the bit set of values in use. A value with at most 16 labels holds them
  under one key, and a search examines each; a value with more holds them by tile of 128 cells over
  the first two dimensions of the world, keyed by value and tile. Per stage the reach leaves a
  radius `foreignReach − foreignReachDeductionPerBit × stage`; among the labels of a tiled value the
  search visits the tiles in rings around the caller and ends with the ring no tile of which can
  hold a label nearer than the best found. The memory of the index follows the number of labels,
  not the size of the world and not the number of different values.
  Every candidate is compared by distance and then by flat index, a total order, so the result
  does not depend on the order of a bucket. The search ends on the first stage that yields a
  reachable label. `CoordinateDecoder` decodes flat indexes by multiplying with the reciprocal of
  the stride.

A child's labels are marked, and therefore outside the index, until `FORK`; the fork adds them
under the child: one probe of the table of own labels, one ordered insertion into the child's
array and one probe of the index of all labels per label.

### Label values use all 20 bits

A label value uses the full value field. `Config.LABEL_VALUE_MASK` is removed in favour of
`Config.VALUE_MASK`; the compiler's label hash, the two mutation plugins that flip or invent label
bits, the recorded namespace mask and the tick benchmark follow. The compiler's output changes on
purpose: the reference artifact of `CompilerOutputEquivalenceTest` is regenerated, after a diff
has shown that nothing but label and label reference values differs, and the pull request says
so. With 20 bits, `namespaceBits = 8` is the upper two of five hexadecimal digits and the
gene-identifying bits are the lower three.

### Label values are unsigned, and shown in hexadecimal

Every molecule type is registered in `MoleculeTypeRegistry` with the format its value is read and
written in, a `MoleculeValueFormat`: `DECIMAL` for a number in two's complement, `HEX` for a bit
pattern without a sign. `LABEL` and `LABELREF` declare `HEX`. Two new, type-neutral methods of
`Molecule` follow the declaration: `extractTypedValue(moleculeInt)` returns the value as the format
reads it, `formatValue(moleculeInt)` its text. The environment, organism and mutation endpoints
send the typed value, so a label value reaches the browser in the range `0…FFFFF`, as the key the
artifact's label maps use. `Molecule.extractSignedValue`, which the virtual machine calls for every
instruction, is not touched. No caller decides by the type: a new type is covered by its
registration.

Wherever a person reads a label value it is five uppercase hexadecimal digits with leading zeros and
no prefix: `L:3A7F1`, `LR:3A7F1`, `[#3A7F1]` in the source view. In the visualizer the palette of
molecule types, the one place that knows type names, names for every type the format its value is
written in (`valueFormat`: `decimal` or `hex`) and gives `hex` to `LABEL` and `LABELREF`.
`ValueFormatter.format`, which serves registers, stacks, parameters and instruction arguments, reads
that format; `ValueFormatter.formatHexValue` produces the digits and is used by `format`, the
environment tooltips, the source annotations and the cell text at the highest zoom level, where the
value is drawn on two lines, two digits above three. Backend texts are written through
`Molecule.formatValue`: `Molecule.toString`, the failure messages of jumps and location
instructions, the storage inspection command and the trace consumer. The hexadecimal form is for
reading only; `Molecule.parse`, the syntax of configuration, stays decimal.

## Cost

The lookup is on the hot path of every jump and call.

**Own path.** A hash lookup into a world-wide map of lists, a distance computation and a random
draw per jump are replaced by one probe of a table of primitives, with a walk over the organism's
own labels only for a value it holds not at all or several times, and a random draw only among
duplicates.

**Foreign path.** It runs only for references without an own match, but the design makes exactly
that case common once parasites exist. Under stable addresses one label value is shared by every
organism carrying the gene; the tiles keep the search to the labels nearby, so its cost follows
the distance to the target and not the size of the population. A reference that matches nothing
pays every stage on every execution, each stage visiting the tiles within its radius.

**Births and deaths.** Today every birth visits all cells of the newborn and rewrites every label,
each rewrite an index removal and insertion; with a flip rate of 0.05 that pass runs for one birth
in twenty. What remains per label is a probe, an insertion into a short array and a change to one
bucket.

**Memory.** The two tables cost more than sorted lists would — about as much per label as the
index this design replaces — and nothing that grows with the size of the world.

How these costs are measured is described in `docs/BENCHMARKING.md` (Label lookup); the measured
tables are kept with the pull request.

Every run changes: the same seed gives a different trajectory.

## Implementation

One pull request, five commits and a measurement checkpoint. Core logic is written by the main
agent; mechanical adaptation of tests, constants and comments is delegated to a cheaper model and
verified by diff and test run. Each commit ends with `./gradlew check` and leaves a runnable
state; the order is chosen so that no intermediate state translates or displays a label value
wrongly.

**Commit 1 — Marked labels are not targets.** `LabelIndex` indexes a label only with marker 0,
consults the old molecule's marker before it removes, and takes one call for a cell that is
released (position, molecule, new owner) in place of the separate owner and marker updates;
`Environment.transferOwnership` and `clearOwnershipFor` use it. The marker leaves `LabelEntry` and
the strategy interface. Tests: a marked label is no target before `FORK`, not even for its writer;
it is the child's own label after `FORK`; it is unowned after the parent's death when no handler
clears it; overwriting and clearing a marked label leave the index consistent; a resume from a
snapshot taken while a child is under construction resolves every lookup as the uninterrupted run
does.

**Commit 2 — Unsigned label values and hexadecimal display.** `MoleculeValueFormat`, its declaration
per type in `MoleculeTypeRegistry`, `Molecule.extractTypedValue` and `formatValue` and their use in
`EnvironmentController`, `OrganismStateConverter` and `LineageMutationTranslator`; the backend
texts; the palette's `valueFormat`, `ValueFormatter.format` and `formatHexValue` and their callers
in the visualizer. While label values still have 19 bits this changes what is shown, not what is
sent.

**Commit 3 — 20-bit label values.** `RuntimeInstructionSetAdapter.labelValue`, `Config`,
`GeneSubstitutionPlugin` (bit choice and result mask), `GeneInsertionPlugin` (invented references),
`LabelRewritePlugin` (mask range), `LabelNamespaceMask` (recorded mask), the comment in
`LineageMutationTranslator` that rests on the narrower mask, `SimulationBenchmark`, the comments in
both configuration files, the regenerated reference artifact. Tests: the assertions that state
19 bits, and regression cases with the top bit set for mask composition, mutation translation,
procedure name resolution, the endpoints of commit 2 and both mutation plugins.

**Commit 4 — Strategy interface, Hamming strategy, namespace flip.** The interface moves to
`runtime.spi` in its reduced form; `HammingLabelMatchingStrategy` with both index structures, the
staged rule, the reach, the tie rule, the topology-aware distance, option validation and
`birthMask`; `LabelRewrite` and its call in `Simulation`; removal of `PreExpandedHammingStrategy`,
`LabelEntry`, `LabelRewritePlugin` and `pipeline.core-plugins`; `Environment` passes the previous
owner when a label is overwritten; `GeneInsertionPlugin` uses `valuesMatch`; `reference.conf`,
`config/evochora.conf`, `tools/trace/trace.conf` and `assembly/example.conf`; `SimulationBenchmark`
places organisms with shared label values and gains the scenarios the checkpoint measures. Tests:
stages and the lottery on the best stage only; a reference flip leaves the distribution among
duplicates unchanged; the reach per differing bit and the negative reach; the tie rule; the range
search across the torus seam and in a bounded world; an unknown option key is rejected; `birthMask`
draws nothing at rate 0 and hits only the uppermost `namespaceBits`; the core rewrite step and the
source it records; a resume across a birth whose flip fires, at rate 1; a minimal second strategy in
the test sources, loaded by class name through the strategy factory.

**Measurement checkpoint — before anything else is built.** Each run is announced and waits for
its go.
- JMH tick benchmark, `origin/main` against commit 4, decision profile with `selectionSpread=50`,
  extended by the cases the design makes common: a high share of lookups without an own match, one
  label value carried by thousands of organisms, and a body with many labels.
- Real-run comparison over 10 M ticks: `origin/main` against commit 4 with `foreignReach = -1`,
  `namespaceFlipRate = 0`, and against commit 4 with the defaults; two rounds in swapped order,
  compared by wall seconds per executed instruction because behaviour differs on purpose.

**Commit 5 — Documentation.** `docs/SCIENTIFIC_OVERVIEW.md` and `README.md` (fuzzy addressing,
label namespace rewriting), `docs/ASSEMBLY_SPEC.md` (marked labels, the matching rule),
`docs/BENCHMARKING.md` (`selectionSpread`), the `evoasm` skill; wording of the flagship documents
and the assembly specification is proposed and approved hunk by hunk. This document moves to
`docs/outdated/proposals/accomplished/`.

**Before the pull request.** Architecture review of the branch, merge of `origin/main`,
`./gradlew check`, and a closing measurement with the production configuration. The pull request
states that the compiler's output changes on purpose.

## Outcome

The five commits were built as planned. The measurement checkpoint after commit 4 changed the
index, and with it the strategy interface:

- **The checkpoint compared against `origin/main` and found the planned index slower on two
  paths.** An own lookup walked all of an organism's labels where `origin/main` looked one value
  up, and every birth and death shifted sorted arrays as long as the population. The index
  described above replaces the two sorted-array structures the plan named. It was chosen by
  measuring alternatives against each other and against `origin/main` — for the own path a binary
  search in the owner's array, the table, and a table per organism reached through the organism;
  for the foreign path an outward walk over the sorted array, the same over an array in chunks,
  and tiles of 64 and of 128 cells — with the rule that the read paths decide and the write path
  and memory break a tie. A first tiled index kept an array of tiles per label value; its memory
  grew with the number of different values times the area of the world, and a search that had to
  probe many rare values walked the tiles for each. The table keyed by value and tile, with the
  labels of a rare value held together, replaced it after the same comparison.
- **`initialize(properties)` joined the interface** because tiles need the world's shape before
  the first label arrives, and **`environment` left `findTarget`** because no strategy read it any
  more.
- **`LabelMatchingBenchmark` was added** (`docs/BENCHMARKING.md`, Label lookup): the programs of the
  tick benchmark own too few labels to show a change to the index. Its `namespacePerOrganism`
  switch fills the value space, the case a high namespace flip rate over many bits produces. In the tick benchmark
  `orphanedPercent` spreads the organisms without labels evenly; `extraLabels` was dropped again.
- **`estimateMemoryBytes(labels)` joined the interface**: the run's memory estimate assumes that at
  most 5 percent of a world's cells are labels and asks the strategy what holding them costs.
- **`docs/ASSEMBLY_SPEC.md` and `README.md` stayed as they were**, against the plan for commit 5: the
  specification defines the language and says nothing that became wrong, and the README's
  sentence on fuzzy jumps still holds.
- The real-run comparison ran as planned, three variants in two rounds of swapped order, each
  variant with the same hash in both rounds.

