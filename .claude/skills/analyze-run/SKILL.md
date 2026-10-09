---
name: analyze-run
description: The methods and instruments for analyzing an Evochora simulation run — what each data source and tool answers and where it misleads, from population curves and lineages to life histories, bodies and the code an organism executes. Use when asked to examine a run for observations, sweeps, adaptations or the mechanism behind them.
---

# Analyzing an Evochora run

This skill is a toolbox, not a procedure. It describes what each data source and instrument
delivers, which question it answers, and how it misleads. Which of them a question needs, and in
which order, is a judgement about the question and the run at hand; none of them is a required
step, and working through all of them is not an analysis.

Nothing quantitative here describes what a run will show. Thresholds, limits, lifetimes, layouts
and costs belong to one run: its resolved configuration, the program it started from and the build
that wrote it. Read them from that run every time.

Two principles hold for every instrument: an observation is reported apart from its
interpretation, and a number carries its base and its sample size.

## The run, its build and its physics

**Where it lives.** `<dataBaseDir>/storage/<runId>/` holds `raw/` (the recorded batches,
`batch_*.pb.zst`, whose file names carry the tick range) and `analytics/` (the Parquet metrics);
`<dataBaseDir>/database/` holds the H2 index. The configuration that produced the run is usually a
`.conf` whose `pipeline.runId` matches.

**`raw/metadata.pb.zst`** is one `SimulationMetadata` message behind a varint length prefix. It
carries `resolved_config_json` (every value the run used), `build_revision` and, for a forked run,
its fork origin. The resolved configuration says what no default does: world shape and topology,
seed, sampling interval, energy plugins, every mutation rate, the organism limits (`max-energy`,
`max-entropy`, `error-penalty-cost`), the genome-hash exclusions, the label-matching strategy and
the thermodynamic rules.

**The build that wrote it.** A run is read and replayed with the build that wrote it.
`build_revision` names that commit — with `-dirty` when the working tree had uncommitted changes,
`unknown` outside a git checkout; then the exact state cannot be rebuilt, and whatever runs on
another build says so. Check the commit out into its own worktree and build it there
(`./gradlew installDist`); a node, a fork and a trace of the run all start from that installation.

**The program.** The primordial programs of that commit (`assembly/primordial/`) name what the
cells of a body mean: labels, registers, thresholds, the decisions between harvesting and
reproducing, what each phase of reproduction does. The `programId` of an organism names its
compiled artifact, and the artifact names its source files. Reading a mutation, a failure or a
death without the program means guessing.

**The physics.** The thermodynamic rules of the resolved configuration say which actions cost
energy, which add entropy and which remove it — per instruction, per molecule type read or
written, and by ownership. Together with the organism limits they decide what an organism can die
of and what it has to do to stay alive. Many questions about why organisms live or die are answered
by reading these rules before any data.

## Analytics (Parquet, no node needed)

Read the Parquet directly with DuckDB:

```sql
SELECT * FROM read_parquet('<analytics>/<metric>/<lod>/**/*.parquet', union_by_name=true)
ORDER BY tick
```

`union_by_name=true` keeps a run readable whose files were written by different builds: a column
missing from older files arrives as NULL instead of raising a schema mismatch. `lod0` is the finest
level, higher levels are coarser; a coarse level shows a whole run at once, a fine one a window.

**What a run records.** The metrics are not fixed: which exist and which columns they carry depends
on the build and the plugins the run was indexed with, so the run itself is the reference. Every
directory under `analytics/` is one metric; its `metadata.json` names it and describes what it
records and how its card draws it, and `DESCRIBE SELECT * FROM read_parquet(...)` lists its columns.
The source of each metric is a plugin under
`src/main/java/org/evochora/datapipeline/services/analytics/plugins/` of the run's build, whose
class comment says what a column means, what it counts and how its coarser levels aggregate. A
directory with a `metadata.json` but no Parquet files is a card the Analyzer computes in the browser
from other metrics. Through a running node, `/analyzer/api/manifest` lists the metrics of a run and
`/analyzer/api/data?runId=<runId>&metric=<metric>&lod=<lod>` returns one of them as JSON.

A selective sweep is invisible in every aggregate curve; it shows only in the lineage.

## Life table

One row per organism: id, parent id, birth tick, death tick, genome hash, parent genome hash,
generation. It is complete — including the children that are born and die between two
recordings — and it is where fates, lethality and non-plugin variation are read.

**Source 1: the raw batches** (`raw/**/batch_*.pb.zst`). Every living organism appears in every
recording; a dead one appears exactly once more, in the first recording after its death, with
`is_dead` and `death_tick` set, then it is pruned. The union over all recordings is therefore the
whole population ever born. Reading them:

- Generate Python stubs from `src/main/proto/**/tickdata_contracts.proto` with `protoc`
  (`protobuf` and `zstandard` in a temporary venv, never the system Python).
- A batch file is zstd-compressed; its payload is one or more length-delimited `TickDataChunk`
  messages (varint size prefix, `writeDelimitedTo`).
- **The first recording of every batch is in the chunk's `snapshot` field, the remaining ones in
  `deltas`.** Reading only `deltas` silently loses the births and deaths of every first recording;
  the totals then disagree with `vital_stats` and `death_lifetimes`, which is how to notice.
- Per organism take the first record for birth data and the `is_dead` record for `death_tick`,
  `energy`, `entropy_register` and the last failure at death.

**Source 2: the H2 index** (`<dataBaseDir>/database/indexdb.mv.db`, schema `SIM_<runId with _>`,
table `ORGANISMS`). Which columns it has depends on the build — `DEATH_TICK` among them in newer
ones; `INFORMATION_SCHEMA.COLUMNS` says it. Read-only via the H2 shell shipped in the install:

```
java -cp build/install/evochora/lib/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:<dataBaseDir>/database/indexdb;ACCESS_MODE_DATA=r;IFEXISTS=TRUE" -user sa \
  -sql "CALL CSVWRITE('<out>.csv', 'SELECT <columns> FROM <schema>.ORGANISMS')"
```

Binary columns such as the initial position are protobuf-encoded; the organism endpoints of a node
return them decoded. The file is locked while a node serves the directory — shell and node never
at the same time.

**Source 3: the `births` metric** — every birth with parent, genome and variation class, no death.

**Control group.** Split the children into *clones* (genome hash equal to the parent's and no event
that changed a cell) and *mutants*, and report every rate for mutants next to the same rate for
clones. The clones' rates are the baseline the environment sets; a mutant rate read without it is
misread as a mutation effect. Which cells the hash covers decides who counts as a clone — see
"What the hash does not see".

**Measuring reproduction.** Count, do not share: the mean number of children and the mean number
of children that themselves reproduced; the second decides whether a lineage grows. The
distribution is skewed, so report a median or a quantile beside the mean. Censor the end of the
run: births later than about two generation times before the last tick have not had time to
reproduce.

Reproduction decomposes into components that can move separately, and a difference between two
groups lies in one of them: the share of newborns that ever reproduce, the number of children of
those that do, the age at the first child, the interval between children, the life after the last
child. A group can have fewer fertile members and still more children per birth.

**One denominator.** State the base of every share and keep it the same across the report. A number
from a sample carries its sample size and an interval wide enough to judge whether the effect is
real.

**What ended a life.** The organism limits define the deaths that recur, and each leaves a
signature in the death record (raw batches) or in the organism's state at its last recording before
death (node):

| lifetime | origin | check at death |
|---|---|---|
| ≈ child initial energy ÷ `error-penalty-cost` | the organism fails every instruction from birth | `energy` ≤ 0 |
| ≈ `max-entropy` ÷ base entropy per instruction | the organism ran but nothing it did removed entropy | `entropy_register` ≥ `max-entropy` |
| ≈ the child's initial energy ÷ the per-tick base cost | the organism ran but never harvested | `energy` ≤ 0 |

Every parameter is looked up, never assumed: the child's initial energy is a constant of the
program that forks the child (it changes with the program, including by mutation); the costs and
limits come from the resolved configuration. A lifetime that recurs across many organisms points
at the rule behind it; the thermodynamic rules then say what the organism would have had to do to
escape it. Children with genome hash 0 are futile forks (no cells handed over).

**What the hash does not see.** Which molecules are left out of the genome hash is configured under
`organism.genome-exclude`; read it before interpreting clone and mutant counts. Molecules left out
make a body defect invisible in every genome statistic, so a suspicion of that kind is settled in
the bodies. When a child lacks a cell, follow that cell backwards through the recordings: it
separates a copy that never wrote it from one that wrote it and had it taken away.

**The acute-lethal class is invisible in body data**: its members die before the first recording
after their birth. Their mutations are not invisible — the event tables hold every birth. Count
the class from the life table, or as the deficit between births expected from the plugin rates and
the genomes actually observed.

**Mutant does not mean mutated by a plugin.** A child's genome hash differs from its parent's
whenever the copy differs from the parent's *birth* genome — also when the parent lost or gained
cells during its life, when the parent's copy routine is defective, or when a neighbour wrote into
the child. The event tables name these births directly (see "A mutant birth without an event"); a
parent's body at its first and last recording separates inherited damage (changed body) from a
defective copier (unchanged body).

**Generation time** = median (child birth − parent birth) over the children of the life table.

## Genome lineage and sweeps

The lineage comes from the `genome_lineage` metric: one row per genome edge, with the genome it
arose from and the tick of its first carrier's birth.

```sql
SELECT genome_hash,
       min(first_birth_tick) AS first_seen_tick,
       list(DISTINCT parent_genome_hash) AS parents
FROM read_parquet('<analytics>/genome_lineage/lod0/**/*.parquet')
GROUP BY genome_hash
```

The three states of `parent_genome_hash`:

- `NULL` — a founding organism, a root of the tree.
- `0` — the parent carried no genome at all, which a broken replication can produce. Also a root,
  but the genome did not descend from another genome.
- otherwise — the parent genome. A genome can have several parents when the same change arose more
  than once; the table keeps every edge.

**Walking the tree.** One parent per genome: the edge with the smallest `first_birth_tick`, which is
what the Analyzer does. A genome that arises again later — a change and its reversal — can still
close a cycle; a walk that also requires each parent to have first appeared before its child cannot
loop.

**What made a genome.** Three metrics record what the mutation plugins wrote at birth. They are
facts, not a reconstruction:

- `mutation_events` — one row per changed molecule: `birth_tick`, `organism_id`, `parent_id`,
  `genome_hash`, `parent_genome_hash`, `event_index` (order of the events of one birth),
  `plugin_class`, `kind`, `position` (relative to the child's origin, along the shortest way around
  the world, as JSON text — cast it: `position::INTEGER[]`), `old_value` and `new_value` (packed
  molecule ints).
- `mutation_summary` — one row per event: `birth_tick`, `organism_id`, `genome_hash`,
  `parent_genome_hash`, `event_index`, `plugin_class`, `kind`, `cell_count`, the smallest `position`
  among its cells, `dv` and `params` as JSON text. The built-in kinds: `duplication` (`params`: the
  flat index of the first source cell, the chosen label; the two cells of a closing jump follow the
  copied ones), `deletion` (`params`: how often the deleted label's hash occurred in the genome),
  `instruction-insertion`, `label-insertion` (one
  instruction in front of a block: a label with the block's old value, the instruction and a `JMPI`
  to the block's new value, written into an empty region, and as the event's last cell the block's
  own label, renamed by one bit — so the event's smallest `position` can be the block's label
  rather than the inserted chain; `params`: the old value and the new one), `substitution`
  (`params`: the slot code of the selected cell — 0 neither, 1 a scalar immediate slot, 2 a vector
  slot — and the action code: 0 value perturbation, 1, 2, 3 an opcode flip of the operation, family,
  variant, 4 a register step, 5 a register swap with the adjacent register operand, 6 a LABEL bit
  flip, 7 a LABELREF bit flip; a swap names two cells, every other action one), and `label-rewrite`
  — the XOR mask the labels and label references of a child received at birth, recorded only when
  the mask changed at least one molecule, with `cell_count` 0, `position` `[]` and the mask in
  `params`; it changes no genome hash and is not a mutation. Within one birth it is recorded after
  the mutations, so a value a mutation recorded stands before that birth's mask.
- `births` — one row per birth: `tick`, `birth_tick`, `organism_id`, `parent_id`,
  `parent_birth_tick`, `generation`, `genome_hash`, `parent_genome_hash` and `variation` (the index
  of the class within the list the metric's card names, the same classes `variation_sources`
  counts). It has one level of detail: a selection from a table of individual births is not a
  coarser picture of them but a different set.
- `variation_sources` — births per recording by what changed the genome: `unchanged`, `bodiless`
  (hash 0), `no_event`, one column per built-in kind, `multiple`, `other`. Its count columns are
  *summed* into the coarser levels, and a tick can carry two rows where an indexer batch ended —
  add the rows of a tick, never pick one.

`tick` is the recording a birth was first seen in, `birth_tick` the birth itself.

- **A genome's founding mutation** is what its first carrier received: the `mutation_summary` rows
  with its `genome_hash` at the smallest `birth_tick` (ties: smallest `organism_id`). A genome that
  arose more than once keeps its later origins as further rows.
- **A mutant birth without an event** — `genome_hash` differs from `parent_genome_hash`, is not 0,
  and no `mutation_summary` row has `cell_count > 0` — is variation outside the plugins; its cause
  is in the parent's body or a neighbour. `variation_sources.no_event` summed over the run checks
  the count.

A mutation an ancestor received sits at the same offset from every descendant's origin, which is
why `position` is an offset: the events of a lineage compare by offset, never by cell.

**Clade shares.** The Analyzer's Clade Shares card stacks each branch's share of the population;
opening a band shows its child clades, and sweeps stack inside each other. Off the Analyzer the
same is a join: collect a genome's descendants from `genome_lineage` and sum their
`genome_population.count` per tick. Every living genome has a row there, so a clade's share is the
complete sum of its members.

**The trunk.** Organisms that are ancestors of nearly the whole final population form the trunk; a
mutant genome whose first carrier sits on it is a fixed mutation. The mutant share of trunk births
against the mutant share of all births is the relative fixation probability of a mutant birth. In
a small population every trunk genome has a large clade — clade size alone is genealogy, not
selection.

**A new hash is not a new function.** The genome that founds a clade by its hash can differ from
its parent only by cells that never execute or matter — a cell outside the region the copier
carries, a value nothing reads. Where a clade's carriers differ from others in some measure (age at
the first child, children per birth, lifetime, a body trait), compare that measure genome by genome
along the ancestors: the genome at which it changes is the one whose change to read.

**Naming a genome:** everything in this project names a genome by six base-62 digits
(`0-9a-zA-Z`) of its *unsigned* 64-bit hash — the chart legends, the analysis scripts. Compute it
the same way and a band in the Analyzer and a row in a notebook are recognisably the same genome.

## A node and its endpoints

A node serves the run's index to the Visualizer, the Analyzer and their HTTP APIs. Two nodes on one
data directory collide on the H2 file lock, and a node started by someone else is not stopped or
reused for another purpose. `/analyzer/api/runs` on a port says what a node there serves.

`node show` serves the indexed runs without simulating (see `docs/CLI_USAGE.md`); a configuration
that includes the run's configuration, points `pipeline.dataBaseDir` at the data directory and, if
needed, sets another port (`node.processes.http.options.network.port`) is enough. `node run` with a
resume-enabled configuration CONTINUES THE SIMULATION unless auto-start is off.

The endpoints an analysis uses:

- **Body of one organism** (`/visualizer/api/environment/{tick}/organism/{id}`) — every cell the
  organism owns, in coordinates relative to its initial position, so bodies of different organisms
  compare directly; `initialPosition`, `worldShape` and `isToroidal` travel in the response, and
  absolute coordinates are `initial + relative`, wrapped by the world size on a torus.
- **Organism detail** (`/visualizer/api/organisms/{tick}/{id}`) — static information (parent, birth
  and death tick, program, initial position, genome), the lineage of ancestors, and the runtime
  state at that tick: energy, entropy register, marker register, IP, direction, data pointers,
  registers, stacks, the last and the next instruction with their arguments and costs, and the last
  failure. States exist only at recorded ticks.
- **Simulation metadata** (`/visualizer/api/simulation/metadata?runId={runId}`) — the
  `moleculeTypes` and `opcodes` maps that decode a cell.

Requests against a large index can be slow and are best sent at a modest rate; environment chunks
need a heap sized for the world.

## Bodies

`moleculeType` in a body response carries the `Config` type constant, the type bits at their
position in the packed molecule (ENERGY is `2 << 20` = 2097152); look it up in `moleculeTypes`,
never normalize it by hand. A CODE cell's `moleculeValue` is its opcode, named by `opcodes`.
Molecules with `marker` ≠ 0 are staged for handover to a child at the next reproduction and are not
part of the finished body.

**Diffing two bodies.** Drop the marked cells and exactly the molecules the run's `genome-exclude`
list names, nothing more and nothing less. XOR-normalize LABEL and LABELREF values with the value of
the LABEL at the smallest relative position (of the LABELREF there if the body has no LABEL), as the
hasher does — otherwise a child whose namespace flip fired differs from its parent in every label;
excluding labels instead would hide an inherited label mutation. DATA operands stay, they are
genome. Empty cells (`CODE:0`) are unowned and absent from a body, so inserted or duplicated code
appears as new cells and a deletion as missing ones. Between members of one clade only the shared
differences are inherited; the rest is ongoing individual change.

**Reading a body as code.** Where the lineage has not moved a region, the compiled artifact of the
organism's program maps a relative position to its source line (`relativeCoordToLinearAddress`,
`sourceMap`). Where it has, read the cells: opcodes by name, labels by value. A recorded LABEL or
LABELREF value stands in the namespace the organism had; XOR it with the masks of the
`label-rewrite` events on its ancestor chain and the compiled artifact names it
(`labelValueToName`). A region's structure — which label opens it, which conditional and which
jumps follow — usually identifies the source block even where the positions have moved.

**Conditionals and padding.** A conditional that fails skips the next REAL instruction, walking over
NOPs. An insertion in the padding behind a conditional therefore makes the following jump
unconditional, an inserted conditional inverts the decision, and a substitution that breaks the
comparison — or points it at a register nothing writes, or turns it into a non-conditional
instruction — removes the decision. The decisions between harvesting and reproducing, and the
energy checks inside reproduction, are where such changes act on an organism's life history.

**Several labels, one reference.** A jump resolves its label by the run's label-matching strategy,
and more than one label can match: duplication copies labels, a label insertion renames a block's
label by one bit and gives the old value to the inserted chain, and the strategy accepts near
matches. Which target a jump takes can then change from one execution to the next, and a change in
one copy of a block acts only on the jumps that land there. Before reading what a mutated block
does, list every label in the body that the references to it can reach.

**Statistics of execution from samples.** Every recorded tick carries each organism's IP; aggregated
relative to the origin across many organisms it gives a coverage map that separates hot code from
code that rarely runs. Code executed once per rare event is invisible to it — exact execution is the
trace's.

## What an organism executes: fork and trace

The trace recorder (`tools/trace/README.md` is the reference) replays a window of a recorded run
from its checkpoint, exactly as the run went, and writes every step, every state and every changed
cell of the organisms it is given. It answers what an organism actually did, where samples and
bodies only show where it was and what it owned.

- **Setup.** Fork mode needs the build of the run, the run id and a configuration in which the
  storage resource resolves to the run's data — a file that includes the build's configuration and
  sets `pipeline.dataBaseDir` to the data directory will do. The storage is only read.
- **What it costs** grows with the size of the world (the whole world is simulated), the length of
  the window and the number of organisms recorded. The organism filter and a sparse full-cell
  snapshot interval keep the output to what a question needs.
- **Placing the window.** A behaviour happens at moments — a decision, a copy, a birth, a death —
  and the life table and the organism states give their ticks. A window that does not contain the
  moment in question shows everything except it.
- **Reading the tables.** `tools/trace/views.sql` creates the views `steps`, `state` and `cells` and
  `world_at(t)`. `steps` carries per instruction its position (`rel` relative to the origin), the
  executed and the compiled operation, resolved arguments, the data pointer, `cost_e` and `cost_s`,
  failures with their reason and `cond_met`; `state` the registers, energy and entropy after every
  tick; `cells` every changed cell with its owner.

What the tables answer when combined:

- **Who wrote a cell, and with which instruction:** a `cells` change joined to the `steps` row of the
  same tick and owner.
- **What an organism was doing over a stretch of its life:** the module of each step, read from its
  position against the program, run-length encoded into a sequence — copying, pausing, harvesting,
  resuming, and how they alternate.
- **Where energy and entropy come from and go:** `cost_e` and `cost_s` summed by instruction or by
  module, against the thermodynamic rules.
- **What a mutation changed in behaviour:** the same kind of episode in a carrier and in a relative
  without it — a parent, a sibling, the ancestor before the change — compared step by step at the
  mutated position and in what follows from it.

A trace of a few organisms is a case, not a sample; a comparison between groups needs many
organisms, from a trace of the whole population or from the life table and the organism states.

## Questions and the instruments that answer them

**Is the regime search-limited, and do geysers matter?** Geysers are `STRUCTURE:-1` cells; their
positions come from the tick-0 snapshot, the initial positions of the fertile organisms from the
raw records or the organism endpoints. The share of bodies near a geyser and the first-child
latency with and without contact show which energy source the world lives from.

**Where does an organism spend its time, and why do copies fail?** From samples: the IP's row
relative to the origin names the module, consecutive samples in the copier are one copy episode,
and the write pointer between episodes separates a pause (it continues where it stood) from an
abort (back at the start). From a trace: the module sequence of the organism, exactly. Episodes per
child, pauses and aborts per child and the module shares are the numbers.

**Does a genome reproduce faster than others?** First heritability: the share of its carriers'
children that carry the genome — a genome no child inherits is birth damage, not a variant. Then
the components of reproduction separately — latency to the first child, interval between children,
lifetime, children per fertile organism, the share that is fertile — never children per lifetime
alone, which rewards dying after the first child. Compare against organisms born in the same
window (the baseline drifts) and against the carriers of the parent genome in that window, with an
interval.

**What do organisms die of?** Deaths by cause in `vital_stats` where the build records them; per
organism the death record of the raw batches or the state at the last recording before death:
energy, entropy, IP and the last failure. Which limit ended a life, and in which part of the program
the organism was, connect the death to the rules of the run and the code that was running.

**Is a step in the birth rate biology?** A sudden persistent step is often one damaged organism
forking non-viable children. `death_lifetimes` separates it: its lifetimes are exact (the death tick
the simulation recorded), and percentiles collapsing onto one value mean many organisms dying at
the same age, which a population dying of ordinary causes does not do.

**Who produces bodiless children?** Count them per parent: a few parents with very many are futile
forkers; their state — where the write pointer stands, how much energy they hold against the
copier's thresholds — shows why their copies hand nothing over. Failure reasons by class name the
mechanism.

**Where are the cliffs?** A cliff is a mutation whose *form* takes its carrier out whatever its
*content* was — a copy that ends nowhere, a jump into data — so that the variation it carried is
never tested. Content that harms is selection's business; form that kills is a wasted birth.

- *Ranking the kinds against the clones.* Join the life table with `mutation_summary`. A birth is
  `bodiless` (hash 0), a `clone` (hash equal to the parent's and no event with `cell_count > 0`),
  `no_event` (hash differs, no such event), the one kind of its events, or `multiple`. Per class:
  births, the share with at least one child, the mean number of children and of children that
  themselves had children, censored at the end; where the life table carries death ticks, the share
  dying acutely and the last failure reason per class.

```sql
WITH ev AS (SELECT organism_id, list(DISTINCT kind) kinds, count(*) n
            FROM mutation_summary WHERE kind <> 'label-rewrite' AND cell_count > 0 GROUP BY 1),
     kids AS (SELECT parent_id organism_id, count(*) children FROM organisms GROUP BY 1)
SELECT CASE WHEN o.genome_hash = 0 THEN 'bodiless'
            WHEN ev.organism_id IS NULL AND o.genome_hash = o.parent_genome_hash THEN 'clone'
            WHEN ev.organism_id IS NULL THEN 'no_event'
            WHEN ev.n = 1 THEN ev.kinds[1] ELSE 'multiple' END AS class,
       count(*) births, avg((coalesce(k.children, 0) > 0)::INT) fertile_share
FROM organisms o LEFT JOIN ev USING (organism_id) LEFT JOIN kids k USING (organism_id)
WHERE o.parent_id IS NOT NULL AND o.birth_tick < :last_birth - 2 * :generation_time
GROUP BY 1 ORDER BY 2 DESC
```

- *Splitting a kind by what its record says.* `mutation_summary.params` and the cells in
  `mutation_events` say what the operator decided: which label, which value, which instruction,
  where the cells went, how a copy ends. A kind whose fertility ranges from nothing to the clones'
  level across the values of one such decision has its cliff in that decision. The XOR of two label
  values of one record needs no masks and identifies the pair; what stands around the written cells
  — whether execution runs on into them, what follows a copy — is in the parent's body, not in the
  record.
- *Reading a split only where the confounder is the same in every row.* A split by one property
  of a mutation can hide an effect that a second property of the same mutation causes in every row.
  Before a split is reported, name what else differs between its rows; a group carries its n.

The run points, the instruction set decides: a rule for an operator is derived from what the
machine does with the written form, for any program. This is observation — place, neighbours and
the lineage's past all take part; the controlled counterpart, one mutation at a time under
identical conditions, is the assay described in `docs/proposals/ideas/MUTATIONAL_ROBUSTNESS_ASSAY.md`.

## Interpretation discipline

- Clade membership stands for a genotype only after several directly read bodies confirm it.
- Early shares in tiny populations are founder and drift effects — selection is called only for a
  sustained logistic rise across many generations, and phrased as "consistent with selection".
- **Measuring that rise:** fit a straight line through `logit(share) = ln(share / (1 - share))`
  over ticks; the slope is the selection coefficient per tick, and multiplied by the generation
  time it is per generation. Fit only the polymorphic phase, shares strictly between 0.01 and 0.99:
  outside it the logit is undefined or dominated by relict individuals.
  **Fit it only for a variant named before looking at the tree** (a decision setting, a body
  difference read in the bodies). For genomes picked *because* they fixed, the slope is positive
  by construction: a neutral lineage that happens to fix also rises.
- Fixation speed is expressed in generations, not ticks; clades are compared by per-capita rates,
  not raw counts.
- Cross-run context (documented retunings, artifact classes, physics changes) lives in
  `docs/PUBLISHED_EXPERIMENTS.md` and the published records' notebooks.

## Pitfalls

- `node run` without auto-start off resumes and ADVANCES the run.
- One organism per parent when sampling bodies; siblings bias the sample.
- Batch chunks carry their first recording in `snapshot`, not in `deltas`.
- The H2 index file is locked by a running node; the H2 shell then fails or, worse, the node does.
- The genome hash is taken at birth, when the child owns no marker cells (FORK resets the marker on
  every cell it hands over); it includes DATA operands and ENERGY cells and excludes what the run's
  `genome-exclude` list names (see `GenomeRule` and `GenomeHasher`). A child born owning an energy
  cell is a "mutant" with identical code. A body read later may contain the copy in progress for the
  next child, marked ≠ 0.
- **Energy credited is not energy taken.** An organism at `max-energy` takes molecules without its
  energy rising, so its gains show neither in the energy register nor in a negative `cost_e`.
  Harvesting is counted by the instructions that consume, not by the credit.
- **Survivors are not a sample of the born.** Organisms alive at a tick, or still alive at a later
  age, are those that succeeded so far; a comparison of their measures says nothing about those that
  died before.
- **A trace of a handful of organisms is anecdote.** It shows a mechanism; a difference between
  groups is measured on many organisms.
- **A walk over the genome lineage can loop** when it only takes the earliest edge; see "Walking the
  tree".
