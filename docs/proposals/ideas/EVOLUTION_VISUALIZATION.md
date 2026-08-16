# Evolution & Lineage Visualization Proposals

This document collects proposals for visualizing evolutionary dynamics in Evochora.
Each proposal is classified by where it belongs in the application:

- **Visualizer / Organism** — Shows data for one or two selected organisms at the current tick.
  Belongs in the organism detail panel or a dedicated sub-view within the visualizer.
- **Visualizer / Population** — Shows data across the population at the current tick.
  Belongs as an overlay or dedicated panel within the visualizer.
- **Analyzer** — Shows metrics aggregated across all ticks and all organisms.
  Belongs in the analyzer frontend.
- **Dedicated View** — Too complex for a panel; warrants its own full-screen view
  (potentially a new route alongside Visualizer and Analyzer).

Each proposal also notes whether it can be built with **existing data** or requires
**new backend tracking** (e.g. explicit mutation event records).

---

## Table of Contents

| # | Proposal | Classification | Data Available? |
|---|----------|----------------|-----------------|
| 1 | [Phylogenetic Tree](#1-phylogenetic-tree-interactive) | Dedicated View | Yes |
| 2 | [Muller Plot](#2-muller-plot-genome-frequency-over-time) | Analyzer | Partially |
| 3 | [Mutation Timeline](#3-mutation-timeline-per-organism) | Visualizer / Organism | Needs mutation events |
| 4 | [Genome Diff View](#4-genome-diff-view) | Visualizer / Organism | Needs genome snapshots |
| 5 | [Mutation Accumulation Heatmap](#5-mutation-accumulation-heatmap) | Visualizer / Population | Partially |
| 6 | [Diversity Dashboard](#6-diversity-dashboard) | Analyzer | Yes |
| 7 | [Fitness Landscape Explorer](#7-fitness-landscape-explorer) | Dedicated View | Partially |
| 8 | [Lineage Sunburst](#8-lineage-sunburst) | Visualizer / Population | Yes |
| 9 | [Mutation Spectrum over Time](#9-mutation-spectrum-over-time) | Analyzer | Needs mutation events |
| 10 | [Extinction & Survival Curves](#10-extinction--survival-curves) | Analyzer | Yes |

---

## 1. Phylogenetic Tree (Interactive)

**Classification:** Dedicated View

**What it shows:**
A branching tree diagram of ancestor-descendant relationships. The root is a
primordial organism (or the set of all primordials); each branch point represents
a reproduction event. This is the standard visualization tool in evolutionary
biology for understanding descent with modification.

**What a researcher learns:**
- Branching patterns: Which lineages radiated (many descendants) vs. went extinct?
- Timing: When did key diversification events happen?
- Bottlenecks: Did the population pass through a narrow genetic bottleneck?
- Asymmetry: Is the tree balanced (many co-existing lineages) or comb-like
  (one dominant lineage with sporadic offshoots)?

**Visual design ideas:**
- Vertical or horizontal tree layout, branch length proportional to time (ticks)
  or genetic distance (number of mutations).
- Extinct branches rendered faded/dashed; living branches rendered solid/bright.
- Nodes colored by genome hash (using existing lineage coloring).
- Collapsible subtrees for managing large populations.
- Click a node to jump to that organism in the Visualizer at its birth tick.
- Zoom and pan for navigating deep trees.

**Why a Dedicated View:**
A phylogenetic tree for an entire simulation run can contain thousands of nodes.
It needs full-screen space, its own zoom/pan controls, and a separate time axis.
It is not tied to a single tick — it shows the entire evolutionary history.

**Data requirements:** Existing data is sufficient. The `organisms` table already
stores `organism_id`, `parent_id`, `birth_tick`, `death_tick`, and `genome_hash`.
A recursive CTE or a bulk query can reconstruct the full tree.

---

## 2. Muller Plot (Genome Frequency over Time)

**Classification:** Analyzer

**What it shows:**
A stacked area chart where the X-axis is time (ticks) and the Y-axis is population
share. Each colored layer represents a unique genome (identified by genome hash).
When a mutation creates a new genome, a new layer emerges *from within* its parent
layer. When a genome goes extinct, its layer shrinks to zero.

This is the canonical visualization in experimental evolution and digital evolution
research (named after Hermann Joseph Muller). It is the single most informative
chart for understanding population-level evolutionary dynamics at a glance.

**What a researcher learns:**
- **Selective sweeps:** A new genome rapidly expands to dominate the population
  (one layer rapidly grows while others shrink).
- **Genetic drift:** Genome frequencies fluctuate randomly without clear selective
  advantage (layers wobble in width).
- **Clonal interference:** Multiple beneficial mutations compete simultaneously
  (several layers grow in parallel before one wins).
- **Diversification:** Many genomes coexist stably (many thin layers persist).
- **Extinction events:** Many layers disappear simultaneously.

**Visual design ideas:**
- Smooth stacked areas with the nesting property: child genomes are visually
  nested within their parent genome's area.
- Colors assigned via the existing lineage hue algorithm.
- Hover over a layer to see genome hash, current frequency, parent genome.
- Click a layer to filter the Visualizer to organisms with that genome.
- Time-range selection for zooming into interesting periods.

**Why Analyzer:**
The Muller Plot aggregates data across all ticks. It is fundamentally a
time-series visualization of the entire simulation run, not a snapshot of a
single tick.

**Data requirements:** Partially available. We need genome frequencies per tick,
which requires counting organisms per genome_hash at each tick. The
`genomeLineageTree` provides the parent-child genome relationships. What is
missing is an efficient pre-aggregated table of genome counts per tick — computing
this from raw organism data on the fly may be expensive for large simulations.

---

## 3. Mutation Timeline per Organism

**Classification:** Visualizer / Organism

**What it shows:**
A horizontal timeline tracing the complete ancestry of a selected organism, from
its primordial ancestor on the left to the organism itself on the right. Each
node on the timeline represents one ancestor. Transitions between nodes are
color-coded by whether a mutation occurred and what type:

- Gray line = no mutation (genome hash unchanged)
- Green = gene insertion
- Red = gene deletion
- Yellow = gene substitution
- Blue = gene duplication
- Purple = label rewrite

A cumulative mutation counter runs below the timeline, showing how many total
mutations have been accumulated at each point in the lineage.

**What a researcher learns:**
- Total mutational load of an organism (how many mutations since primordial).
- Mutation tempo: Were mutations evenly distributed or clustered in bursts?
  Clustering suggests **punctuated equilibrium** — periods of stasis interrupted
  by rapid change.
- Which mutation types contributed to this organism's evolution?
- At which generation did key changes happen?

**Visual design ideas:**
- Compact horizontal strip that fits in the organism detail panel.
- Nodes as small circles, connections as colored lines.
- Hover a node to see: organism ID, birth tick, genome hash, mutation type.
- Click a node to navigate the Visualizer to that ancestor at its birth tick.
- Scrollable for deep lineages, with a minimap showing the full extent.

**Why Visualizer / Organism:**
This is inherently about one selected organism's personal evolutionary history.
It enhances the existing lineage chain display.

**Data requirements:** Needs explicit mutation event tracking. Currently we can
detect *that* a mutation occurred (genome_hash differs from parent), but not
*what type* of mutation. The five mutation plugins (GeneInsertionPlugin,
GeneSubstitutionPlugin, GeneDeletionPlugin, GeneDuplicationPlugin,
LabelRewritePlugin) would need to record the mutation type applied at each
birth event.

---

## 4. Genome Diff View

**Classification:** Visualizer / Organism

**What it shows:**
A side-by-side comparison of two genomes at the instruction level, similar to a
`git diff`. The left side shows the parent genome (or any selected reference
organism), the right side shows the child (or current organism). Differences are
highlighted:

- Green background = inserted instructions
- Red background = deleted instructions
- Yellow background = substituted instructions (with old and new values shown)
- Blue background = duplicated regions

Each instruction is rendered with its decoded EvoASM representation (opcode +
operands) so the researcher can understand the *semantic* meaning of the change.

**What a researcher learns:**
- The exact structural change caused by a mutation.
- Whether the mutation affected a coding region, a data region, or a control-flow
  structure (labels/jumps).
- How a successful adaptation was achieved at the instruction level.
- Whether two organisms with different genome hashes actually differ in
  functionally meaningful ways.

**Visual design ideas:**
- Two-column layout with synchronized scrolling.
- Instruction-level rendering: `MOV DR0, DR1` not just raw molecule bytes.
- Color-coded molecule types (existing palette: CODE blue, DATA gray, LABEL gray,
  REGISTER blue-gray, ENERGY gold, STRUCTURE green).
- Diff summary header: "+3 inserted, -1 deleted, 2 substituted".
- Could also support comparing two arbitrary organisms (not just parent-child).

**Why Visualizer / Organism:**
Comparing two specific organisms at the instruction level is inherently a
per-organism operation within the Visualizer. It could be triggered from the
existing organism panel when selecting two organisms or when viewing a
parent-child pair.

**Data requirements:** Needs genome snapshots. Currently the full genome is only
available at runtime (the organism's molecule region in the environment grid).
To diff across time, we would need either: (a) stored genome snapshots at birth,
or (b) the ability to reconstruct a genome from the initial program + applied
mutations. Option (a) is more straightforward but storage-intensive.

---

## 5. Mutation Accumulation Heatmap

**Classification:** Visualizer / Population

**What it shows:**
An overlay on the environment grid where each organism's cell is colored by how
many mutations have accumulated in its lineage since its primordial ancestor.
Cool colors (blue) = few mutations, warm colors (red) = many mutations.

**What a researcher learns:**
- Geographic patterns in mutational load: Do organisms in energy-rich regions
  accumulate more mutations (more reproduction = more opportunities for mutation)?
- Correlation between mutational distance and spatial position.
- Whether "old" lineages (many accumulated mutations) cluster spatially or are
  dispersed.
- Identification of evolutionary "hotspots" where rapid evolution occurs.

**Visual design ideas:**
- Semi-transparent color overlay on the existing environment grid.
- Toggle on/off alongside existing display modes (ID color, Genome color).
- Color scale legend in the corner.
- The heatmap updates as the user navigates between ticks.

**Why Visualizer / Population:**
It shows data across the whole population at the current tick, overlaid on the
spatial environment grid — the Visualizer's primary canvas.

**Data requirements:** Partially available. We can compute lineage depth
(number of ancestors) as a proxy for mutation count using the existing recursive
CTE. However, lineage depth != mutation count because not every reproduction
involves a mutation (genome hash may be unchanged). For true mutation count, we
either need explicit tracking or a per-organism count of genome-hash changes in
the ancestry.

---

## 6. Diversity Dashboard

**Classification:** Analyzer

**What it shows:**
A collection of population-level diversity metrics plotted over time:

- **Effective genome count** (exponential Shannon entropy over genome frequencies):
  How many "functionally different" genomes coexist? A value of 1 means total
  monoculture; higher values mean more diversity.
- **Innovation rate:** Number of new unique genome hashes appearing per tick (or
  per N ticks). Shows the pace of evolutionary novelty.
- **MRCA depth:** How many ticks ago did the Most Recent Common Ancestor of the
  *entire current population* live? A sudden drop means a selective sweep just
  eliminated most diversity. A steady increase means the population is diversifying.
- **Lineage half-life:** The median survival time of newly-created lineages.
  Short half-life = most mutations are deleterious or neutral-but-lost-to-drift.
  Long half-life = mutations tend to persist.

**What a researcher learns:**
- Overall health and diversity trajectory of the evolving population.
- Whether the simulation is in an exploratory phase (high diversity, high
  innovation) or an exploitative phase (low diversity, refinement of a dominant
  genome).
- Early warning of diversity collapse.
- Whether the mutation rate and selection pressure are well-calibrated.

**Visual design ideas:**
- Four time-series charts stacked vertically, sharing the same X-axis (ticks).
- Interactive crosshair that shows values at the same tick across all charts.
- Annotations for notable events (e.g., "diversity crash at tick 50000").
- Smoothing controls (raw vs. moving average).

**Why Analyzer:**
All four metrics are aggregated across the entire population and plotted over all
ticks. This is fundamentally an Analyzer concern.

**Data requirements:** Existing data is sufficient for all four metrics. Genome
frequencies per tick can be computed from the organisms table. MRCA depth requires
ancestry traversal but can be sampled rather than computed every tick.

---

## 7. Fitness Landscape Explorer

**Classification:** Dedicated View

**What it shows:**
A network graph where each node represents a unique genome and edges connect
genomes that are one mutation apart (parent-child relationship). Node size
encodes current population count (or peak population count). Node color encodes
a fitness proxy — either average lifespan, average energy at death, or peak
population reached.

This visualizes the **adaptive landscape** that the population is navigating
through evolutionary time.

**What a researcher learns:**
- Which genomes are fitness peaks (large, bright nodes)?
- Are there **neutral networks** — clusters of genomes with similar fitness
  connected by single mutations? (Important for evolutionary theory: neutral
  networks enable exploration without fitness cost.)
- How "rugged" is the landscape? Many disconnected peaks = hard to optimize.
  Smooth gradients = easy to hill-climb.
- Which mutational paths led from primordial genomes to current fitness peaks?

**Visual design ideas:**
- Force-directed graph layout with physics simulation.
- Node size: log(population count).
- Node color: fitness gradient (red = low, green = high).
- Edge thickness: number of times this mutation transition occurred.
- Time slider to show how the population moves across the landscape over ticks.
- Click a node to see genome details and jump to an organism with that genome.
- Filtering: show only genomes that existed at a specific tick or tick range.

**Why a Dedicated View:**
This is a complex interactive graph that needs full-screen space and its own
interaction model (force layout, zoom, pan, filtering). It spans the entire
simulation history.

**Data requirements:** Partially available. Genome-to-parent-genome relationships
exist in `genomeLineageTree`. Fitness proxies (lifespan, energy at death) would
need to be aggregated per genome hash from the organism state data. Population
counts per genome per tick would need to be computed.

---

## 8. Lineage Sunburst

**Classification:** Visualizer / Population

**What it shows:**
A radial tree diagram (sunburst chart) where the primordial ancestor(s) sit at
the center, and each concentric ring represents one generation outward. Each
sector's angular width is proportional to the number of living descendants at the
current tick. Color follows the existing genome-hash lineage coloring.

**What a researcher learns:**
- Which lineages are currently dominant (widest sectors)?
- How deep are the lineages (how many rings from center to edge)?
- Branching structure: Is one lineage dominating or are many co-existing?
- Quick identification of the "winning" evolutionary branches at this tick.

**Visual design ideas:**
- Interactive: hover to see organism/genome details, click to drill into subtree.
- Outer ring shows current living organisms; inner rings show ancestors.
- Faded sectors for extinct sub-lineages.
- Smooth zoom transition when drilling into a subtree.
- Responsive sizing to fit the available panel space.

**Why Visualizer / Population:**
It represents the state of the population at the currently selected tick. The
"number of living descendants" is inherently a per-tick measurement. It could
work as a panel alongside the environment grid.

**Data requirements:** Existing data is sufficient. We need the full lineage tree
(available via parent_id) and current alive/dead status at the selected tick.

---

## 9. Mutation Spectrum over Time

**Classification:** Analyzer

**What it shows:**
A stacked bar chart or stacked area chart where the X-axis is time (ticks) and
the Y-axis shows the relative proportion (or absolute count) of each mutation
type occurring in the population:

- Gene Insertion (green)
- Gene Deletion (red)
- Gene Substitution (yellow)
- Gene Duplication (blue)
- Label Rewrite (purple)

**What a researcher learns:**
- Does the mutation spectrum shift over time? For example, early evolution might
  be dominated by insertions (genome growth) while later evolution shifts to
  substitutions (refinement).
- Correlation between mutation types and evolutionary phases.
- Whether certain mutation types are more common in successful lineages.
- Calibration feedback: Are the configured mutation probabilities producing the
  expected distribution?

**Visual design ideas:**
- Stacked area chart for smooth trends, or grouped bars for discrete time windows.
- Toggleable between absolute counts and relative proportions.
- Overlay line for total mutation rate.
- Synchronized with other Analyzer charts (shared time axis).

**Why Analyzer:**
Aggregates mutation events across the entire population over all ticks.

**Data requirements:** Needs explicit mutation event tracking. Each birth event
with a mutation must record which plugin was responsible. This requires changes
to the five mutation plugins and the persistence layer.

---

## 10. Extinction & Survival Curves

**Classification:** Analyzer

**What it shows:**
Kaplan-Meier-style survival curves for lineages. The X-axis is lineage age
(ticks since the lineage's founding mutation), and the Y-axis is the fraction
of lineages still alive. A lineage "dies" when its last living member dies.

Optionally, multiple curves can be overlaid to compare:
- Lineages founded in different simulation phases (early vs. late).
- Lineages that started with different mutation types.
- Lineages from different genome families.

**What a researcher learns:**
- What is the typical lifespan of a new lineage? Most new mutations are quickly
  lost — the curve quantifies this.
- Is there a critical threshold? E.g., "lineages surviving past 500 ticks have
  a 70% chance of surviving to 5000."
- Are lineages becoming more or less durable over time? (Comparing curves from
  different simulation phases.)
- The balance between neutral drift (exponential decay) and selection (non-
  exponential shapes).

**Visual design ideas:**
- Classic Kaplan-Meier step function with confidence intervals.
- Log-scale Y-axis option for examining tail behavior.
- Filter controls: time range of lineage founding, minimum population size, etc.
- Hover to see exact survival probability at any age.
- Median survival time prominently displayed.

**Why Analyzer:**
This aggregates data across all lineages in the simulation and is not tied to a
specific tick.

**Data requirements:** Existing data is sufficient. Lineage birth = organism
birth_tick (for the founding organism of a new genome). Lineage death = max
death_tick of all organisms sharing that genome_hash (and all descendant hashes).
This requires traversing the genome lineage tree to identify lineage boundaries.

---

## Cross-Cutting Concern: Mutation Event Tracking

Several proposals (3, 4, 9, and partially 5) require **explicit mutation event
records** that do not currently exist. Today, we can only detect that a mutation
occurred (genome_hash differs from parent), but not *what kind* of mutation.

**What needs to be tracked per birth event:**
- Mutation type (which plugin: insertion, deletion, substitution, duplication,
  label rewrite) — or "none" if the child genome is identical to the parent.
- Mutation position within the genome (index of affected instruction/molecule).
- Optionally: the old and new values at the mutation site.

**Where to record it:**
- The five mutation plugins (`GeneInsertionPlugin`, `GeneSubstitutionPlugin`,
  `GeneDeletionPlugin`, `GeneDuplicationPlugin`, `LabelRewritePlugin`) already
  perform the mutation; they would additionally emit a mutation event record.
- This record would flow through the existing data pipeline and be persisted
  alongside the organism record.

**Impact:** This is the single most impactful backend change for enabling rich
evolution visualization. Without it, we can still build proposals 1, 2, 5
(partial), 6, 7, 8, and 10. With it, all proposals become possible.

---

## Implementation Priority Suggestion

**Phase 1 — No backend changes needed:**
1. Diversity Dashboard (#6) — high value, data already available
2. Lineage Sunburst (#8) — enhances Visualizer, data available
3. Extinction & Survival Curves (#10) — classic analysis, data available

**Phase 2 — Backend: mutation event tracking:**
4. Mutation Event Tracking (cross-cutting backend change)
5. Mutation Timeline (#3) — immediate payoff from event tracking
6. Mutation Spectrum (#9) — immediate payoff from event tracking

**Phase 3 — Complex dedicated views:**
7. Muller Plot (#2) — needs pre-aggregated frequency data
8. Phylogenetic Tree (#1) — needs scalable tree layout algorithm
9. Genome Diff View (#4) — needs genome snapshot storage
10. Fitness Landscape Explorer (#7) — most complex, needs multiple data sources
