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
  back to summing `genome.genome_data`.
- **births** = diff of `vital_stats.total_born`.

**Futile forkers first.** A sudden persistent step in the birth rate is more often ONE damaged
organism forking non-viable children than anything biological. The `death_lifetimes` metric decides
it in one look: `death_lifetime_p10`, `p50` and `p90` collapsing onto a single constant value means
many organisms dying at exactly the same age, which nothing biological does. `death_count` says how
many deaths are behind the percentiles. The lifetimes are exact — the metric reads the death tick
the simulation recorded, not the tick at which the corpse was observed. Runs whose analytics predate
the metric need the fallback below.

Scan for: population phases and crashes; birth-rate steps (see above); `genome_diversity.shannon_index` and `dominant_share` trends;
`environment_composition.energy_cells` (rising = population cannot consume the input, falling =
world being eaten empty); `age_distribution.p50` (turnover); `instruction_usage` failure rates.
`generation_depth` drops to near zero are indexer-restart artifacts (#112), never biology.

**A selective sweep is invisible in every aggregate curve.** Do not stop here.

## 2 · Genome layer (read-only node)

**Before starting anything, check what is already running** — two nodes on the same data
directory collide on the H2 file lock:

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

Query strictly serially. Fetch organism snapshots (`/visualizer/api/organisms/{tick}`) on a grid
of 10–15 sampled ticks; cache them as JSON. The lineage field is `genomeAncestors` on current
builds and `genomeLineageTree` on pre-#103 builds — same child→parent mapping, merge across all
snapshots into one tree (`scripts/sweep.py: build_tree` handles both names).

**Sweep detection:** take the primordial genome (tree root), list its direct children, and compute
each child clade's share of the bodied population per snapshot (`sweep.in_clade`, memoized). A
clade rising monotonically toward 100 % is a sweep candidate. Repeat one level deeper inside a
winning clade — sweeps stack (a second mutation can fix within the first clade).

## 3 · Body forensics (the genotype, not the bookkeeping)

Clade membership is a proxy; the mutation is molecules in the world. Via the node:

- Organism detail (`/visualizer/api/organisms/{tick}/{id}`) → `staticInfo.initialPosition`.
- Environment strip (`/visualizer/api/environment/{tick}?region=x1,x2,y1,y2`) answers in protobuf:
  decode with `protoc --decode=org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse
  -I src/main/proto src/main/proto/org/evochora/datapipeline/api/contracts/http_api_contracts.proto`.
  Parse cell blocks tolerantly (fields with default values are omitted; `molecule_type` may arrive
  shifted as `id << 20` on some builds — normalize).
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
- **Founder mutations** of a clade: full-body diff against organism 1 at tick 0 (box
  x0−2…x0+112, y0−2…y0+87). Exclude DATA molecules (operand noise) and LABEL/LABELREF *values*
  (XOR-masked per organism); compare several clade members — only shared differences are the
  inherited founder mutation, the rest is ongoing per-individual mutation.

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
- No `population.bodied_count`: sum the per-genome counts in `genome.genome_data`; `load_population`
  does this automatically when the column is missing.
