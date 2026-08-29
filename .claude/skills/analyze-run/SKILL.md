---
name: analyze-run
description: Analyze an Evochora simulation run scientifically — population dynamics, sweep detection via clade shares, and body-level forensics. Use when asked to examine a run for interesting observations, sweeps, or adaptations.
---

# Analyzing an Evochora run

Work in three layers, cheapest first. Report observations separately from interpretations, and
never claim selection without the checks in step 4. The helper modules in `scripts/` are proven on
three runs; use them instead of re-deriving the recipes — but treat them as **temporary tooling**:
they sit on volatile interfaces (endpoint JSON shapes, analytics column layouts) and may need
adaptation when those change (the lineage field rename already broke them once). The proposal
`docs/proposals/RUN_ANALYSIS_TOOLING.md` moves their responsibilities into tested system exports;
as its items land, the scripts shrink and finally disappear.

## 0 · Orientation (always first)

1. Find the run: `<dataBaseDir>/storage/<runId>/` (raw + analytics) and the config that produced it
   (usually a `.conf` whose `pipeline.runId` matches). Note: world shape, seed, sampling interval,
   energy plugins (geyser/solar/seed rates), and every mutation rate. Differences from
   `config/evochora.conf` defaults are usually the point of the run.
2. Extent: count `raw/**/batch_*` files; the filenames give the tick range.

## 1 · Analytics layer (Parquet, no node needed)

Use `scripts/analytics.py` (needs `duckdb`, `pandas` — a venv with them exists or is quickly made).
`load_population(analytics_dir, lod)` returns population plus the two derived series that matter:

- **bodied** = `population.bodied_count` = living organisms with genome hash ≠ 0. The gap to
  `alive_count` is the hash-0 cohort and is visible directly in the population chart as two
  diverging lines; a large gap means futile-forker or frozen-loser artifacts, not biology.
  Runs whose analytics predate the column have no `bodied_count`; `load_population` then falls
  back to summing the per-genome counts in their `genome.genome_data`, a column only those older
  runs carry.
- **births** = diff of `vital_stats.total_born`.

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

**Environment composition counts every cell**, so all ten columns are exact — including the small
ones. `energy_cells` rising means the population cannot consume the input, falling means the world
is being eaten empty. `structure_cells`, `label_cells` and `register_cells` are small fractions of
a large world and are usable in absolute numbers: a step in them marks a change in what the
organisms build, not sampling noise. Runs indexed before the counting was made exact carry
Monte-Carlo estimates instead (1000 cells sampled and scaled), where anything below roughly a
percent of the world was indistinguishable from zero — do not compare those numbers with exact
ones, and do not read small categories out of them at all.

**A selective sweep is invisible in every aggregate curve.** Do not stop here.

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
  `/visualizer/api/simulations/{runId}/metadata` is keyed by exactly these values — look the type
  up there, never normalize it by hand. A CODE cell's `moleculeValue` is its opcode; the same
  metadata response carries the `opcodes` map.
  Molecules with `marker` ≠ 0 are staged for handover to a child at the next reproduction and are
  not part of the finished body — drop them when reading a genome.
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
  shifting. Exclude DATA molecules (operand noise) and LABEL/LABELREF *values* (XOR-masked per
  organism); compare several clade members — only shared differences are the inherited founder
  mutation, the rest is ongoing per-individual mutation.

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

## Fallbacks for runs without the newer metrics

- No `death_lifetimes`: fetch organism snapshots for a few ticks in the suspect window and build the
  lifetime histogram by hand from `deathTick − birthTick` of the entries marked dead. Expensive and
  it only sees the deaths of the sampled ticks, which is exactly why the metric exists.
- No `population.bodied_count`: sum the per-genome counts in `genome.genome_data`, the column older
  runs carry instead of the `genome_population` table; `load_population` does this automatically
  when the column is missing.
