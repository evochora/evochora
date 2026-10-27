# Bit Chemistry: Bound Energy, Grid-Only Reactions, and What Evolution Can Reach

**Status: IDEA — not decided. No design decisions made yet; open questions at the end.**

A concrete candidate for the reaction system of `docs/SCIENTIFIC_OVERVIEW.md` §4.6, written
against the criteria of [GRADED_CHEMISTRY_ADDITIONS](GRADED_CHEMISTRY_ADDITIONS.md): continuous
yield, compositionality, a generative schema instead of a reaction table, catalyzed-only first.
It adds two things those documents do not cover: an accounting rule that keeps an organism from
manufacturing its own energy, and an analysis of which improvements evolution can actually reach
under the shipped mutation operators. The second part limits what the first part can deliver and
is stated as such.

## Problem

Energy acquisition today is a single `PEEK` on an `ENERGY` molecule: walk there, collect. There
is nothing an organism can get gradually better at, so the only quality axis under selection is
replication itself. A chemistry in which energy is *bound* and released only by a reaction the
organism performs adds a second axis: how well an organism reacts is a matter of degree.

Any such chemistry must respect the rule that keeps the energy economy closed today. Energy is
credited when a molecule leaves the world (`PEEK`) and charged when one enters it (`POKE`).
Registers are unconstrained: an organism can `SETI` any value, and the VM does not track where a
register's content came from. A reaction that runs on register contents therefore lets an
organism write two matching values and react them, or `PEEK` one substrate, `SETR` a copy, and
react the copy with the original. Both are a few molecules of code and would collapse the
chemistry into free energy. The reaction has to happen where the energy accounting already
lives: in the world.

## Idea

### The schema: one rule over the value bits

A new molecule type `SUBSTRATE` carries 20 value bits. Every set bit is a bound energy quantum.
Two substrates `A` and `B` react as follows:

```
product = A XOR B
yield   = popcount(A AND B) × E
```

Bits both substrates share annihilate and release energy; bits only one of them carries survive
into the product. `E` is the conversion constant, the energy released per annihilated pair.

This single rule gives the four properties asked for in GRADED_CHEMISTRY_ADDITIONS:

- **Continuous yield.** A partner sharing three bits yields three quanta, one sharing twelve
  yields twelve. A slightly better match is worth slightly more.
- **Compositionality.** The product is a substrate and reacts with anything that shares bits
  with it. Roughly a million substances and open reaction paths, no table.
- **Waste and niches.** A product with few remaining bits is worthless to most and accumulates.
  An organism whose surroundings hold the complementary substrates can use it. The death handler
  (`DecayOnDeath`) can emit substrates instead of energy, which closes the nutrient cycle.
- **Conservation.** The world's energy budget is the number of set bits it holds. Nothing is
  created; a reaction converts bits into ER at the constant `E`.

The rule is designed, but it is one rule over an open space, not an enumerated task list. That
is the compositionality criterion of GRADED_CHEMISTRY_ADDITIONS, Addition 2.

### Where the reaction happens: two grid cells, never a register

A reaction instruction family `RCT*` takes two displacement vectors from the active DP, in the
same way `PEEK`/`POKE` take one. Both target cells must hold a `SUBSTRATE` the organism may
access (unowned or own, by the same ownership rules as `PEEK`). One cell receives the product,
the other becomes empty, and the yield is credited to ER through a thermodynamic policy. The
organism chooses *which two world molecules* react and contributes nothing of its own.

Three placements were examined:

| Placement | Mechanism | Cost | Verdict |
|---|---|---|---|
| Two grid cells | `RCTI <vecA> <vecB>`, the organism only selects the pair | one AND, one XOR, one bit count; nothing for organisms that do not react | proposed |
| Register operands | substrates `PEEK`ed into registers and reacted there | cheapest | rejected: unforgeable provenance of register contents is impossible, see Problem |
| Spontaneous, grid-wide | adjacent substrates react on their own | grid-wide per-tick scan | deferred, as in GRADED_CHEMISTRY_ADDITIONS Addition 4 |

### The accounting: what closes the economy

The four rules below are what make the register exploit impossible. Each is the counterpart of
a rule that exists for `ENERGY` today.

1. **Substrates in registers are inert.** `SCAN` on a substrate delivers its value as `DATA`,
   information for comparing partners. No instruction reacts register contents. A `SETI` with a
   `SUBSTRATE` literal, or a `SETR` copy of a scanned value, produces nothing that can be reacted.
2. **Writing a substrate costs its bits.** `POKE` of a `SUBSTRATE`-typed register is charged
   `popcount(value) × E`, the same way `POKE` of `ENERGY` is charged its value. A manufactured
   substrate costs more than any reaction with it can ever return, because
   `popcount(A AND B) ≤ popcount(A)`. Manufacturing never pays.
3. **Taking a substrate out credits nothing.** `PEEK` on a substrate consumes it without
   crediting ER; if it credited the bits, substrate would be `ENERGY` with an extra step. The
   bits are dissipated. Transport through a register therefore always loses energy.
4. **Transport happens in the world.** A shove instruction moves the molecule at `DP + vec` by
   one unit vector without it ever entering a register. This is how an organism brings partners
   together, and the accounting has no gap through which it could gain.

Under these rules, bits come into existence only when an organism pays for them out of ER and
become ER only when a reaction annihilates them in the world.

The mutation operators must keep producing no `SUBSTRATE` molecules. Today the insertion plugin
takes its argument types from configuration and the substitution plugin perturbs values, so a
genome cell cannot turn into substrate. That has to stay a constraint.

The thermodynamic policy prices by value today (`energy-permille`); rule 2 needs pricing by bit
count, a small extension of `UniversalThermodynamicPolicy`.

### What changes

- Runtime: a type constant and registry entry for `SUBSTRATE`, the `RCT*` family, the shove
  instruction, a bit-count price rule, a policy for the reaction yield.
- Worldgen: a tick plugin placing substrates, with two knobs: how many bits a substrate carries,
  and whether bit families are spatially clustered. The first sets how much blind reacting
  returns, the second how much selecting partners returns.
- Tooling: a colour in the visualizer, a row in the environment composition metric. Persistence
  is untouched, molecules are ints.
- `ENERGY` stays. Whether a world offers sunlight, substrates, or both is configuration, which
  makes the chemistry an experimental variable.

Hot path: zero for organisms that never react. A reaction is three integer operations.

## Reachability: what a mutation can actually improve

This section limits everything above and is the reason the idea is not more than an idea.

Both published selective sweeps (`docs/PUBLISHED_EXPERIMENTS.md`) were single-molecule edits at
an existing decision site: one closed a switch, one inserted a single `NOT` into NOP padding.
Nothing requiring more than one instruction has fixed. Under the shipped rates (substitution 0.05
per newborn, insertion 0.15, duplication 0.2, deletion 0.02) and a genome of several hundred
cells, a specific single-cell edit appears roughly once per tens of thousands of births; the two
sweep variants took 5 M and 166 M ticks to appear. An improvement that needs two specific edits,
the first of which is neutral on its own, has to land the second in a carrier of the first, and
neutral carriers drift at low frequency. Rough estimate, to be checked with the robustness assay
once it exists: weeks to months for two steps, years for three.

Consequences for the design:

- **Partner selection cannot arise de novo.** A scan, a comparison and a conditional jump are
  four to five instructions in the right order. The chemistry can reward partial competence, but
  reward is not reach. Every desirable behaviour must be at most one edit from the primordial, or
  the primordial must carry it as a tunable parameter.
- **The primordial carries the structure, selection tunes the parameters.** Partner test, residue
  handling and transport are built into a metabolism module with thresholds that start permissive
  or off. `GTI %MATCH DATA:0` accepts any partner; a scale-proportional `DATA` mutation makes it
  selective. That is one step, and it is smooth.
- **Structural novelty comes only from duplication plus divergence.** A duplicated metabolism
  block whose operand mutates is a specialisation on another bit family: two steps, each neutral
  or better. The mechanism exists (`GeneDuplicationPlugin`, `selectionSpread`).
- **This remains parameter evolution**, with more parameters whose value matters. Today one
  threshold pays to tune; with the chemistry it would be several: which pairs, how selective, keep
  the residue or not, where to put it. Behaviour more than one step away stays out of reach with
  or without the chemistry. Turning multi-step paths into single-step ones is a property of the
  variation operators, not of the chemistry, and belongs to
  [DUMB_VARIATION_PACKAGE](DUMB_VARIATION_PACKAGE.md).

## Primordial and experiment

The energy module of the primordial is replaced by a metabolism module in its dumbest form:
scan for substrates (`SNTI` works unchanged), react the first two found, shove the residue aside.
Calibration must make this viable: the expected yield of random pairs has to exceed the cost of
the instructions. The module then carries, switched off by their thresholds, the partner test and
the residue decision described above.

The experiment: one world in three variants, `ENERGY` only as baseline, substrates uniformly
placed, substrates clustered by bit family. Measured: energy intake per organism over time, and
with the existing clade analysis, whether metabolism code diverges and whether the selective
thresholds move.

## Hurdles

1. **Reachability**, as analysed above: the chemistry delivers slopes for parameters, not new
   behaviour. Whether that is worth the runtime change is a judgement about the experimental
   program, not a technical question.
2. **Stagnation** (GRADED_CHEMISTRY_ADDITIONS, Addition 5): if blind reacting is good enough,
   evolution stops there. The lever is substrate density and bit count per substrate, both
   configuration.
3. **Calibration**: `E`, bits per substrate, placement rate and instruction costs must be set so
   that the dumb module survives and the selective one wins. No evidence yet.
4. **Two new instruction families** (`RCT*`, shove) and a new molecule type touch the compiler,
   the ISA tables, the disassembler and the specification.

## Open questions

1. Is the XOR/AND schema the minimal one, or is there a rule with the same four properties and a
   larger share of profitable single edits?
2. Does `PEEK` on a substrate fail, or consume it without credit (rule 3)? Failing is stricter;
   consuming keeps the reproduction loop's handling of cells in its way unchanged.
3. Shove as its own instruction, or as a variant of an existing world instruction?
4. Bit-count pricing as a new rule kind in `UniversalThermodynamicPolicy`, or a separate policy
   for `SUBSTRATE` writes?
5. Which thresholds does the first metabolism module carry, and at which starting values?
6. How is reachability measured before the runtime is changed? The robustness assay
   ([MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md)) applied to the planned
   metabolism module would count the single edits that improve it, which is the number this idea
   stands or falls on.
