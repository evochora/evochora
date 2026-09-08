---
name: analyze-run
description: Analyze an Evochora simulation run scientifically — population dynamics, sweep detection via clade shares, and body-level forensics. Use when asked to examine a run for interesting observations, sweeps, or adaptations.
---

# Analyzing an Evochora run

Work in layers, cheapest first. Report observations separately from interpretations, and never
claim selection without the checks in step 4.

The question a run is opened with is rarely the finding it holds. Before answering it, build the
life table of step 1b and read it against its control group — the children that carry their
parent's genome unchanged. Most of what earlier sessions found by accident (a lifetime the whole
population shares, a class of children that never reproduces, variation the mutation plugins did
not produce) shows in that table without being looked for.

A current run needs nothing but the exports and the queries in this text. `scripts/sweep.py` is
**temporary tooling for older runs only** — it reconstructs from organism snapshots what those runs
have no export for, it sits on the snapshot JSON shape, which has changed before, and it is not
part of the tested system. It disappears when those runs are no longer analyzed.

## 0 · Orientation (always first)

1. Find the run: `<dataBaseDir>/storage/<runId>/` (raw + analytics) and the config that produced it
   (usually a `.conf` whose `pipeline.runId` matches). Note: world shape, seed, sampling interval,
   energy plugins (geyser/solar/seed rates), and every mutation rate. Differences from
   `config/evochora.conf` defaults are usually the point of the run.
2. Extent: count `raw/**/batch_*` files; the filenames give the tick range.

## 1 · Analytics layer (Parquet, no node needed)

Read the Parquet directly with DuckDB — no node, no helper module. One metric at one level of
detail, coarsest first:

```sql
SELECT * FROM read_parquet('<analytics>/<metric>/lod4/**/*.parquet', union_by_name=true)
ORDER BY tick
```

`union_by_name=true` keeps a run readable whose files were written by different builds: a column
missing from older files arrives as NULL instead of raising a schema mismatch. LOD 4 is the
coarsest and the place to start; drop to a finer one once a window is worth a closer look.

The two series that matter throughout:

- **bodied** = `population.bodied_count` = living organisms with genome hash ≠ 0. The gap to
  `alive_count` is the hash-0 cohort and is visible directly in the population chart as two
  diverging lines; a large gap means futile-forker or frozen-loser artifacts, not biology.
- **births** = the first difference of `vital_stats.total_born`, e.g.
  `total_born - lag(total_born) OVER (ORDER BY tick)`.

**Futile forkers first.** A sudden persistent step in the birth rate is more often ONE damaged
organism forking non-viable children than anything biological. The `death_lifetimes` metric decides
it in one look: `death_lifetime_p10`, `p50` and `p90` collapsing onto a single constant value means
many organisms dying at exactly the same age, which nothing biological does. `death_count` says how
many deaths are behind the percentiles. The lifetimes are exact — the metric reads the death tick
the simulation recorded, not the tick at which the corpse was observed. Runs whose analytics predate
the metric need the fallback below.

Scan for: population phases and crashes; birth-rate steps (see above); `genome_diversity.shannon_index` and `dominant_share` trends;
`environment_composition` (see below); `age_distribution.p50` (turnover); `instruction_usage`
failure rates. `generation_depth` is read from each organism and is therefore correct across
indexer restarts; a drop to near zero in an older run is the restart artifact of #112, not biology.

**Environment composition counts every cell**, so all eleven columns are exact — including the small
ones. `energy_cells` rising means the population cannot consume the input, falling means the world
is being eaten empty. `structure_cells`, `label_cells` and `register_cells` are small fractions of
a large world and are usable in absolute numbers: a step in them marks a change in what the
organisms build, not sampling noise. Runs indexed before the counting was made exact carry
Monte-Carlo estimates instead (1000 cells sampled and scaled), where anything below roughly a
percent of the world was indistinguishable from zero — do not compare those numbers with exact
ones, and do not read small categories out of them at all.

**A selective sweep is invisible in every aggregate curve.** Do not stop here.

## 1b · Life table (every organism, no node needed)

One row per organism: id, parent id, birth tick, death tick, genome hash, parent genome hash,
generation. It is complete — including the children that are born and die between two
recordings — and it is where fates, lethality and non-plugin variation are read.

**Source 1: the raw batches** (`raw/**/batch_*.pb.zst`). Every living organism appears in every
recording; a dead one appears exactly once more, in the first recording after its death, with
`is_dead` and `death_tick` set, then it is pruned. The union over all recordings is therefore the
whole population ever born. Reading them:

- Generate Python stubs from `src/main/proto/**/tickdata_contracts.proto` with `protoc`
  (`protobuf` and `zstandard` in a temporary venv, never the system Python).
- A batch file is zstd with possibly several frames — decompress *across frames*. The payload is
  one or more length-delimited `TickDataChunk` messages (varint size prefix, `writeDelimitedTo`).
- **The first recording of every batch is in the chunk's `snapshot` field, the remaining ones in
  `deltas`.** Reading only `deltas` silently loses a tenth of all births and deaths; the totals
  then disagree with `vital_stats` and `death_lifetimes`, which is how to notice.
- Per organism take the first record for birth data and the `is_dead` record for `death_tick`,
  `energy` and `entropy_register` at death. 87 M ticks (874 batches) take about 40 s.

**Source 2: the H2 index** (`<dataBaseDir>/database/indexdb.mv.db`, schema `SIM_<runId with _>`,
table `ORGANISMS` with organism_id, parent_id, birth_tick, genome_hash, generation,
parent_genome_hash). Read-only via the H2 shell shipped in the install:

```
java -cp build/install/evochora/lib/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:<dataBaseDir>/database/indexdb;ACCESS_MODE_DATA=r;IFEXISTS=TRUE" -user sa \
  -sql "CALL CSVWRITE('<out>.csv', 'SELECT * FROM <schema>.ORGANISMS')"
```

It has no death tick, and the file is locked while a node serves the directory — shell and node
never at the same time.

**Control group first.** Split the children into *clones* (genome hash equal to the parent's) and
*mutants* (different), and report every rate for mutants next to the same rate for clones. In the
runs analysed so far, 59–64 % of the clones died at the same age without reproducing and only
25–32 % of clones ever had a child: the baseline is a lottery set by the environment, and a
mutant rate read without it is misread as a mutation effect. Under the STATE-aware hash a
substitution in a DATA operand — a threshold, a harvest period — makes the child a mutant, where
the old hash counted it as a clone; the rates quoted here were measured under the old hash and
describe those runs.

**Fate classes** per child: *fertile* (has children); *acute lethal* (lifetime below ~1 000
ticks); *entropy death* (lifetime below ~20 000); *sterile long-lived*; *alive at end*. Two
lifetimes recur and both follow from the run's resolved config (in `raw/metadata.pb.zst`, a
3-byte varint prefix then a `SimulationMetadata` message; `resolved_config_json` holds every
value):

| lifetime | origin | check at death |
|---|---|---|
| ≈ child initial energy ÷ `error-penalty-cost` (25 000 ÷ 100 → 248 ticks) | the child fails every instruction from birth | `energy` ≤ 0 |
| ≈ `max-entropy` ÷ base entropy (+ a few ticks; 10 000 → 10 010) | the child ran but never wrote a cell, so nothing dissipated entropy | `entropy_register` ≥ max-entropy, energy account full |

Children with genome hash 0 are futile forks (no cells handed over); they die in the first class.

**The acute-lethal class is invisible in body data**: its members die before the first recording
after their birth, so no body endpoint ever shows them. Their mutations are not invisible: the
event tables of step 2 hold every birth, because a dead child stays in the recording that first
sees it. Count the class from the life table, or as the deficit between births expected from the
plugin rates and the genomes actually observed.

**Mutant does not mean mutated by a plugin.** A child's genome hash differs from its parent's
whenever the copy differs from the parent's *birth* genome — also when the parent lost or gained
cells during its life (damage inherited through copying), when the parent's copy routine itself
is defective, or when a neighbour wrote into the child. A run with the mutation event tables
names these births directly: a mutant birth without an event that changed a cell (the rule in
step 2), counted per recording in `variation_sources.no_event`. Without the tables, find them as
parents with ≥ 5 children none of which carries the parent's genome. Either way, fetch the
parent's body (step 3) at its first and at its last recording and diff the two — a changed body
is inherited damage, an unchanged one a defective copier. In the run where this was first
measured, such parents produced 16 % of all births, their children reproduced half as often as
other mutants, and the only adaptive sweep of the run came from this channel, not from a plugin.

**Generation time** = median (child birth − parent birth) over all children of the life table.

## 2 · Genome layer (Parquet, no node needed)

The lineage comes from the `genome_lineage` metric: one row per genome, with the genome it arose
from and the tick of its first carrier's birth. No node, no organism snapshots.

```sql
SELECT genome_hash,
       min(first_birth_tick) AS first_seen_tick,
       list(DISTINCT parent_genome_hash) AS parents
FROM read_parquet('<analytics>/genome_lineage/lod0/**/*.parquet')
GROUP BY genome_hash
```

Reading the three states of `parent_genome_hash` correctly matters:

- `NULL` - a founding organism. These are the roots of the tree.
- `0` - the parent carried no genome at all, which a broken replication can produce. Also a root,
  but a different one: the genome did not descend from another genome.
- otherwise - the parent genome. A genome can have several parents when the same mutation arose
  more than once; the table keeps every edge and leaves the choice to the analysis.

**What made a genome.** Three metrics record what the mutation plugins wrote at birth. They are
facts, not a reconstruction, and need no node:

- `mutation_events` — one row per changed molecule: `birth_tick`, `organism_id`, `parent_id`,
  `genome_hash`, `parent_genome_hash`, `event_index` (order of the events of one birth),
  `plugin_class` (the plugin's class name), `kind` (what the plugin calls the operation),
  `position` (relative to the child's origin, along the shortest way around the world, as JSON
  text — cast it: `position::INTEGER[]`), `old_value` and `new_value` (packed molecule ints).
- `mutation_summary` — one row per event with the same keys, `cell_count`, the `position` of the
  first cell, `dv` and `params` as JSON text. The built-in kinds: `duplication` (`params`: flat
  index of the copied source), `deletion` (`params`: how many copies of the deleted label the body
  had), `insertion`, `label-insertion` (`params`: hash of the source label), `substitution`, and
  `label-rewrite` — the XOR mask every child's labels receive, with `cell_count` 0 and the mask in
  `params`; it changes no genome hash and is not a mutation.
- `variation_sources` — births per recording by what changed the genome: `unchanged`, `bodiless`
  (hash 0), `no_event`, one column per built-in kind, `multiple` (several kinds in one birth),
  `other` (a kind outside the built-in set). Its count columns are *summed* into the coarser LOD
  levels, and a tick can carry two rows where an indexer batch ended — add the rows of a tick,
  never pick one.

`tick` is the recording a birth was first seen in, `birth_tick` the birth itself. Two rules:

- **A genome's founding mutation** is what its first carrier received: the `mutation_summary`
  rows with its `genome_hash` at the smallest `birth_tick` (ties: smallest `organism_id`). That
  is what the *Clade Shares* chart prints in a band's label; a genome that arose more than once
  keeps its later origins as further rows.
- **A mutant birth without an event that changed a cell is variation outside the plugins**: a
  child whose `genome_hash` differs from `parent_genome_hash`, is not 0, and has no
  `mutation_summary` row with `cell_count > 0`. Its cause is in the parent's body or in a
  neighbour, never in a plugin — the channel step 1b describes. Check the claim before repeating
  it: `variation_sources.no_event` summed over the run against the same count from the life table.

The two event tables are written once per birth, with the child's first recording, and nothing is
skipped. A mutation an ancestor received sits at the same offset from every descendant's origin,
which is why `position` is an offset: the events of a lineage compare by offset, never by cell.

**Sweep detection:** the Analyzer's *Clade Shares* chart does this by itself — it reads
`genome_population` next to `genome_lineage` and stacks each branch's share of the population.
Click a band to open it into its child clades; a band rising monotonically toward 100 % is a sweep
candidate, and opening it shows whether a second mutation is fixing inside the first. Sweeps stack,
so keep opening the winner.

Off the Analyzer the same thing is a join: take a genome, collect its descendants from
`genome_lineage`, and sum their `genome_population.count` per tick. Every living genome has a row
there — no ranking — so a clade's share is the complete sum of its members. A genome can carry more
than one parent edge; take the one with the smallest `first_birth_tick`, which is what the chart
does.

**Fixed mutations from the trunk.** Take the organisms alive at the end, walk each one's parent
chain to the founder, and count for every organism how many of the final organisms descend from
it. Organisms that are ancestors of ≥ 90 % of the final population form the *trunk*; a mutant
genome whose first carrier sits on the trunk is a fixed mutation. The mutant share of trunk births
against the mutant share of all births is the relative fixation probability of a mutant birth. In
a population of a few hundred every trunk genome has a clade of thousands — clade size alone is
genealogy, not selection.

**Naming a genome:** everything in this project names a genome by six base-62 digits
(`0-9a-zA-Z`) of its *unsigned* 64-bit hash — the chart legends, the analysis scripts. Compute it
the same way and a band in the Analyzer and a row in a notebook are recognisably the same genome.

**Fallback for runs without the metric.** Older runs need the node: fetch organism snapshots
(`/visualizer/api/organisms/{tick}`) on a grid of 10–15 sampled ticks, cache them as JSON, and
merge the `genomeAncestors` field (`genomeLineageTree` on pre-#103 builds) into one tree
(`scripts/sweep.py: build_tree` handles both names). Query strictly serially; a snapshot can cost
~30 s on a multi-million-organism index.

**Before starting a node, check what is already running** — two nodes on the same data directory
collide on the H2 file lock:

1. `curl localhost:8081/analyzer/api/runs` — if a node already serves the target run, use it and
   do not start a second one (and do not stop it afterwards; it is not yours).
2. If a node is running but serves a *different* run (or the port is taken by something else):
   do not kill it and do not blindly start a second node — ask the user how to proceed.
3. Only if nothing is running, start a read-only node yourself, say so, and stop it when the
   analysis is done. `node run` with a resume-enabled config WILL CONTINUE THE SIMULATION unless
   auto-start is off:

```
# serve.conf:  include "<the run's conf>"  +  pipeline.autoStart = false
EVOCHORA_OPTS="-Xmx12g" build/install/evochora/bin/evochora -c serve.conf node run
```

## 3 · Body forensics (the genotype, not the bookkeeping)

Clade membership is a proxy; the mutation is molecules in the world. Via the node:

- **Body of one organism** (`/visualizer/api/environment/{tick}/organism/{id}`) answers JSON with
  every cell the organism owns — no bounding box to guess, no neighbours to sort out afterwards.
  Coordinates are relative to the organism's initial position, so bodies of different organisms
  compare directly; `initialPosition` and `worldShape` travel in the response, and absolute
  coordinates are `(initial + relative + size) % size`.
  `moleculeType` carries the `Config` type constant, i.e. the type bits at their position in the
  packed molecule (ENERGY is `2 << 20` = 2097152). The `moleculeTypes` map of
  `/visualizer/api/simulation/metadata?runId={runId}` is keyed by exactly these values — look
  the type up there, never normalize it by hand. A CODE cell's `moleculeValue` is its opcode; the
  same metadata response carries the `opcodes` map.
  Molecules with `marker` ≠ 0 are staged for handover to a child at the next reproduction and are
  not part of the finished body — drop them when reading a genome, together with the STATE cells,
  which the organism wrote for itself and which are outside the genome.
- Organism detail (`/visualizer/api/organisms/{tick}/{id}`) → `staticInfo.initialPosition` and the
  runtime state; the body endpoint above already carries the anchor, so this is only needed for the
  state itself.
- **The reproduction switch lives in `MAIN_LOOP` row 4** (y0+4, x0−2…x0+45 covers it): primordial
  layout is `NRG %DR0 … GTI %DR0 D100000 [NOP padding 16–19] JMPI MAIN_REPRODUCE … GTI %SR D5000 …`.
  Conditional skip semantics: a failed test skips the next REAL instruction, walking over NOPs. An
  insertion in the padding therefore makes the `JMPI` unconditional (energy route always open); a
  substitution that breaks the `GTI` comparison closes the route permanently. This one switch has
  been retuned independently in three runs — check it in every analysis.
- **Execution heatmap (statistical):** every sampled tick carries each organism's IP. Aggregating
  IP positions relative to the body anchor across many organisms of a clade yields a coverage
  heatmap good enough to separate hot code (main loop, harvest) from code that never runs at
  relevant frequency. **Blind spot:** code executed once per rare event (once per reproduction
  cycle, say) has hit probability ~1e-6 per sample and is systematically invisible — sampled data
  cannot decide "is this block ever executed". For that, exact in-runtime coverage counting is
  needed (feature request: see the execution-coverage issue on GitHub).
- **Founder mutations** of a clade: full-body diff against organism 1 at tick 0. Both bodies come
  from the body endpoint in the same relative coordinates, so the diff is a set operation without
  shifting. Exclude STATE molecules (written by the organism for itself); XOR-normalize
  LABEL/LABELREF values with the anchor label as the hasher does (see Pitfalls) — excluding them
  would hide an inherited label mutation; DATA operands stay in the diff, they are genome. Compare
  several clade members — only shared differences are the inherited founder mutation, the rest is
  ongoing per-individual mutation.

**Which rule applies depends on the build that wrote the run.** A run written with the STATE type
hashes the DATA operands and excludes STATE, and its body diffs drop STATE cells. A run from before
the STATE type has no STATE cells at all: its state slots are DATA, its genome hash excluded every
DATA cell, and its body diffs must drop DATA instead. Decide it before diffing — the `moleculeTypes`
map of the run metadata lists STATE, and `environment_composition` carries a `state_cells` column.

### Older runs: the environment strip and protoc

Runs recorded before the chunk format gained its delta directory cannot be read by a current
build at all — they need a build of their own epoch, which has neither the body endpoint nor a
JSON format. For those runs body forensics goes the old way:

- Organism detail → `staticInfo.initialPosition`, then an environment strip around it
  (`/visualizer/api/environment/{tick}?region=x1,x2,y1,y2`; the primordial body fits
  x0−2…x0+112, y0−2…y0+87). The strip contains the neighbours' cells too — filter by `ownerId`.
- That endpoint answers protobuf only. Decode with
  `protoc --decode=org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse
  -I src/main/proto src/main/proto/org/evochora/datapipeline/api/contracts/http_api_contracts.proto`
  and parse cell blocks tolerantly: protobuf omits fields holding their default value, so a cell
  with `owner_id` 0 carries no `owner_id` line at all.

## 4 · Interpretation discipline

- Validate clade→genotype on several directly read bodies before using clade shares as genotype.
- Early shares in tiny populations are founder/drift effects — call selection only for a sustained
  logistic rise across many generations, and phrase it as "consistent with selection".
- **Measuring that rise:** fit a straight line through `logit(share) = ln(share / (1 - share))`
  over ticks; the slope is the selection coefficient per tick, and multiplying by the generation
  time gives it per generation. Fit **only the polymorphic phase**, shares strictly between 0.01
  and 0.99: below and above, the logit is undefined or dominated by relict individuals, and the
  near-fixation tail flattens the slope without carrying information about selection.
  **Fit it only for a variant named before looking at the tree** (a switch setting, a body
  difference verified in step 3). For genomes picked *because* they fixed — every trunk mutation
  of step 2 — the slope is positive by construction: a neutral lineage that happens to fix also
  rises. In one run all 19 trunk mutations, neutral hitchhikers in NOP padding included, fitted
  +0.04 to +0.2 per generation.
- Generation time = median parent-birth→child-birth distance over sampled newborns; use it to
  express fixation speed in generations, not ticks.
- Per-capita rates (births per organism per Mtick), never raw counts, when comparing clades.
- Cross-run context (documented switch retunings, artifact classes, physics changes) lives in
  `docs/PUBLISHED_EXPERIMENTS.md` and the published records' notebooks.

## Pitfalls that have burned sessions before

- `node run` without `autoStart=false` resumes and ADVANCES the run.
- Organism snapshots can cost ~30 s each on multi-million-organism indexes; requests must be
  serial; environment chunks need ≥8 GB heap.
- Old runs (pre-#103 proto renumbering) are unreadable by current builds — serve them with the
  build that wrote them.
- One organism per parent when sampling bodies (siblings bias the sample).
- Batch chunks carry their first recording in `snapshot`, not in `deltas` (step 1b).
- The H2 index file is locked by a running node; the H2 shell then fails or, worse, the node
  does. Finish shell exports before starting a node.
- The genome hash is taken at birth, when the child owns no marker cells (FORK resets the marker
  on every cell it hands over); it includes the DATA operands and the ENERGY cells and excludes
  STATE (see `GenomeHasher`). A child born owning an energy cell is a "mutant" with identical
  code. A body read later may contain the copy in progress for the next child, marked ≠ 0. When
  diffing bodies, drop those cells and the STATE cells, and XOR-normalize LABEL and LABELREF
  values with the value of the LABEL at the smallest relative position, as the hasher does —
  otherwise every child differs from its parent in every label.
- Empty cells (`CODE:0`) are unowned and absent from a body; inserted or duplicated code therefore
  appears as *new* cells, a deletion as *missing* cells.

## Fallbacks for runs without the newer metrics

- No `death_lifetimes`: fetch organism snapshots for a few ticks in the suspect window and build the
  lifetime histogram by hand from `deathTick − birthTick` of the entries marked dead. Expensive and
  it only sees the deaths of the sampled ticks, which is exactly why the metric exists.
- No `mutation_events`, `mutation_summary`, `variation_sources`: what a mutation did has to be
  reconstructed by diffing the child's body against the parent's (step 3), and births outside the
  plugins are found by the ≥ 5-children heuristic of step 1b.
- No `population.bodied_count`: sum the per-genome counts in `genome.genome_data`, the JSON column
  older runs carry instead of the `genome_population` table. It holds the top genomes plus an
  `other` bucket, and the plugin that wrote it skipped hash-0 organisms, so the sum is the same
  quantity.
