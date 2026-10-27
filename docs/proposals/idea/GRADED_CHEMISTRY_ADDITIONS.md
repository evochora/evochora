# Graded Chemistry — Additions to the Reaction Network Idea

**Status: IDEA — not decided. No design decisions made yet; open questions at the end.**

`docs/SCIENTIFIC_OVERVIEW.md` §4.6 already describes the core idea of a reaction-based chemistry:
multiple substrate types, organism-catalyzed reactions (A + B → C + Energy + Entropy), reaction
chains with higher yield for more complex pathways, the contrast to Avida's externally defined
rewards, and niche construction through byproducts. This document does **not** restate that idea.
It records five additions that came out of a fitness-landscape analysis and are not yet covered
by §4.6.

## Motivation (corrected)

The fitness-landscape motivation needs a precise statement. An earlier analysis claimed the
selection map is a cliff ("replicate or die"); that is overstated — entropy only forces *writing*
(POKE reduces SR), not *replicating*, so harvesting plus non-reproductive writing is a
thermodynamically stable state. (Note: §2.5's sentence "organisms that stop replicating …
accumulate entropy and eventually die" is inaccurate for the same reason.)

What remains true and motivates a graded chemistry: **there is little an organism can get
gradually better at.** Energy acquisition (PEEK on ENERGY molecules) is trivially maxed out —
walk there, collect. The only quality axis under selection is replication itself. A chemistry with
graded yields adds slopes to the landscape: partial metabolic competence becomes selectable.

## Addition 1 — Continuous yield

§4.6's reaction chains are discrete: which reactions an organism can perform determines its access
to energy. The addition is a *continuous* dimension: the yield of a reaction depends gradually on
how it is performed — with molecule values acting as quantities/concentrations (stoichiometry),
so that being *slightly* better at a pathway yields *slightly* more energy.

The mechanical entry point already exists: `UniversalThermodynamicPolicy` supports value-dependent
costs and yields with permille scaling, so yields can already be made a continuous function of
molecule values without new infrastructure.

This is the piece that connects the chemistry to fitness-landscape smoothing: discrete chains
create new plateaus (you can do the reaction or you cannot); continuous yield creates slopes
between them.

## Addition 2 — The compositionality criterion

§4.6 states that the fitness landscape "emerges intrinsically from the physics" — but this holds
only under a condition it does not name. If the designer specifies a *finite list* of reactions
with hand-chosen quality criteria ("right order", "good timing", "purity"), the reward structure
is designed after all, merely hidden inside reaction rules — Avida with extra steps. The
difference to an explicit fitness function would be gradual, not categorical.

The criterion that makes the difference is **compositionality**: reaction products must themselves
be able to act as substrates of further reactions — an open combinatorics of transformation
pathways, not an enumerated set. Then the "tasks" (which metabolic pathways pay off) emerge from
the interaction of chemistry, resource distribution, and competition, not from the designer's
head. Whether a concrete chemistry design meets this criterion is the central quality gate for
any implementation proposal.

## Addition 3 — Generative reaction schema instead of a reaction table

Follows from Addition 2: reactions should be defined by a *general rule* over molecule types and
values (a schema that assigns any pair/set of substrates a product and a yield), not by a lookup
table of designed reactions. A schema keeps the reaction space open and unenumerated; a table is
a task list. How minimal such a schema can be (2–3 rules over type + value?) is an open design
question.

## Addition 4 — Spontaneous vs. catalyzed reactions

§4.6 only considers organism-catalyzed reactions. A second regime is possible: spontaneous
reactions between adjacent molecules in the environment (slow decay, equilibria, gradients that
build up without organisms). This would give the environment its own chemical dynamics — real
niche dynamics rather than a static substrate distribution — but is a grid-wide cost factor and
touches the simulation hotpath. Catalyzed-only is the cheap variant; spontaneous chemistry is the
expensive extension with its own scientific value.

## Addition 5 — The stagnation risk

Open dynamics do not imply complexity growth (the Flow-Lenia lesson: open-ended dynamics were
demonstrated, complexity increase was not). The concrete risk here: evolution finds the single
cheapest reaction and stops — the chemistry then degenerates into a slightly more expensive
ENERGY molecule. Design levers against this (depletion of easy substrates, yield that decreases
with global usage, spatial separation of substrates) exist but each smuggles designer assumptions
back in; this tension deserves explicit treatment in any proposal.

## Open questions

1. What is the minimal schema that satisfies compositionality (Addition 3)?
2. Where exactly does continuity come from — value-as-quantity stoichiometry via the existing
   permille mechanics, or a new mechanism?
3. Catalyzed-only first, or spontaneous chemistry from the start (Addition 4)?
4. How is stagnation (Addition 5) detected and measured? (Connects to
   [MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md) — a population-level analogue
   would be needed.)
5. Should §2.5 and §4.6 of `SCIENTIFIC_OVERVIEW.md` be corrected/extended with the entropy
   clarification and these additions once decided?
