# Run 20260402: A Selective Sweep Driven by a Single-Cell Regulatory Mutation

**Status: analysis complete for the observation; replication and follow-up items listed at the end.**

This document records the analysis of simulation run
`20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8` (271 million ticks), in which the
population oscillated between roughly 80 and 200 organisms for 168 million ticks and then rose
to a stable ~900. The cause is a selective sweep of one genotype that differs from its parent by a
single inserted instruction. Observations and interpretations are kept apart: every numbered
observation names its data source; interpretation sections say what the numbers are taken to mean.

## 1. Run coordinates

| Item | Value |
|---|---|
| Data location | external SSD, `/run/media/rainer/evochora/data-20260402/` (3.5 TB) |
| Storage layout | `storage/<runId>/raw` 1.3 TB (54 200 batch files), `storage/<runId>/analytics` 2.4 GB, `database/indexdb.mv.db` 942 GB, `database/blobs/env-chunks` 1.3 TB |
| Config | `config/local.conf` → `pipeline.tuning = profiles.sampled` (sampling interval 100), world 7680×4320 torus, seed 42, primordial `assembly/primordial/main.evo`, initial energy 100 000 |
| Energy sources | `SeedEnergyCreator` 0.25 % of cells × 10 000 at tick 0; `GeyserCreator` 0.0001 × cells ≈ 3 300 geysers, 10 000 every 100 ticks into a free, unowned (radius 3) neighbour; `SolarRadiationCreator` p = 0.01/tick × 10 000 |
| Organism limits | max-energy 150 000, max-entropy 10 000, error-penalty-cost 100, base cost 1 energy + 1 entropy per instruction, write −500 entropy per cell, own-cell read +1 |
| Wall clock | simulation 2026-04-02 11:56 → 2026-04-05 11:03 (≈71 h), three pauses of 11 h / 12.5 h / 9.4 h at ticks ≈135.2 M, ≈216.5 M, ≈269.7 M (resumed each time); net ≈38 h ≈ 2 000 ticks/s on 8 cores / 30 GB; indexers ran until 2026-04-06 |
| Code state | built from `main` at `7b45ae32` (2026-03-28, after PR #103/#104) plus unknown local modifications; the resolved configuration and all primordial sources are stored verbatim in the run metadata (`resolvedConfigJson`, `programs`) |
| Known code difference to today | `7c42df39` (2026-08-16) fixes a resume bug present during this run: after a resume, call frames without a register snapshot made every affected `RET` fail (frame popped, error penalty, IP runs past the `RET`). Only frames open at resume time were affected; the population shows no visible reaction at any of the three resume ticks (100 k-tick resolution: 116–126, 757–771, 879–906 alive). |
| Reproducibility | Not reproducible, independent of the resume bug: with `runtime.parallelism > 1` fuzzy label resolution (JMPI/CALL/SKJI…, executed in the parallel wave) draws from one shared, non-thread-safe RNG (`PreExpandedHammingStrategy`, `selectionSpread = 50`), so jump targets depend on thread scheduling; the label-matching RNG and the per-organism RNGs are re-derived from the seed on resume rather than restored; `parallelism = 0` resolves to `cores − 2`. Same seed + same config + same code therefore does **not** yield identical data today. See follow-up item 0. |

Serving the run locally: `bash build/install/evochora/bin/evochora -c config/local.conf node run`
(`pipeline.autoStart = false`, `runId` set) — HTTP on `localhost:8081`.
`GET /visualizer/api/organisms/{tick}?runId=…` answers in ≈100 ms; environment regions come as
protobuf (`GET /visualizer/api/environment/{tick}?region=x1,x2,y1,y2`), decodable with
`protoc --decode=org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse -I src/main/proto …/http_api_contracts.proto`.

## 2. Observations

### 2.1 Population trajectory (analytics `population`, `vital_stats`, `environment_composition`, LOD4)

| Ticks | alive | avg energy | avg entropy | births per organism per Mtick | energy cells in world |
|---|---|---|---|---|---|
| 10–160 M | 80–200 (mean ≈120) | 52–62 % | 8–16 % | 2.4–2.9 | 60 000–140 000 |
| 170 M | 178 | 48 % | 9.7 % | 2.4 | 67 000 |
| 200 M | 472 | 39 % | 6.3 % | 2.8 | 48 000 |
| 230–271 M | 820–960 (mean ≈900) | 37–39 % | 5–6 % | 3.1–3.4 | 2 500–33 000 |

Births per capita do not rise during the growth phase; the growth is a small surplus of births over
deaths over ≈60 M ticks. Spatially the old population occupied ≈45 of 144 world blocks
(480×480 cells); at 230 M all 144 are occupied.

`generation_depth.max_depth` drops to single digits at ≈70 M, ≈110 M and ≈175 M. These are
indexer-restart artifacts (`GenerationDepthPlugin` keeps depths only in memory), not biology.

### 2.2 The sweep (organism snapshots every 1 M ticks, 100 k ticks in 150–200 M; genome lineage tree from the API)

Genome `aKRwb3` (hash `-2263203560390388059`; 6-char base62 of the hash as in the notebooks).
First observed carrier: organism **55254**, born tick **166 040 570**, parent 55072 (genome
`f0ph4I`, hash `127144217089843200`), initial position (2527, 3672).

Share of all living organisms descending from `aKRwb3` (lineage tree walk):

| tick | 165 M | 170 M | 175 M | 180 M | 190 M | 200 M | 210 M | 225 M | 245 M+ |
|---|---|---|---|---|---|---|---|---|---|
| clade share | 0 % | 17 % | 35 % | 55 % | 67 % | 79 % | 91 % | 97.5 % | 100 % |

The analytics `dominant_share` (per genome hash) did not show the sweep because the clade
diversified quickly (most descendants carry their own hash; "other" dominates `genome_data`).

### 2.3 The mutation (environment regions, owner-filtered, body box x∈[−1,110], y∈[−1,85], LABEL/LABELREF values XOR-normalised against the anchor label exactly as `GenomeHasher` does)

Diff parent genome `f0ph4I` → `aKRwb3`, verified on three independent pairs of individuals
(55072→55254, 55141→55397, 55237→55478): **exactly one insertion, `NOT %DR0` at body cells
(29,4)–(30,4)**, i.e. inside the `NOP^4` padding of `MAIN_LOOP` between `GTI %SR 5000` and
`JMPI MAIN_REPRODUCE`. Row 4 of the body (positions are x offsets):

```
primordial / f0ph4I:  13:GTI %ER D100000  20:JMPI MAIN_REPRODUCE  26:GTI %SR D5000                33:JMPI MAIN_REPRODUCE  39:JMPI MAIN_HARVEST …
aKRwb3:               13:GTI %ER D100000  20:JMPI MAIN_REPRODUCE  26:GTI %SR D5000  29:NOT %DR0   33:JMPI MAIN_REPRODUCE  39:JMPI MAIN_HARVEST …
```

Persistence of the trait: 9 of the 10 most common genomes at 230 M and 270 M carry the insertion
(`QXTXR9` at 230 M is a reversion that lost it again); 40/40 randomly sampled living organisms at
270 M carry it, 0/40 at 150 M.

### 2.4 Phenotype: death causes and entropy (dense snapshots every 100 ticks; dead organisms appear once with their final state)

| window | alive (mean) | deaths | entropy deaths (SR ≥ 10 000) | energy deaths (ER ≤ 0) | age at death p50 / p90 |
|---|---|---|---|---|---|
| 145.0–146.0 M (pre-sweep) | 144 | 389 | 67 % | 33 % | 38 k / 1.02 M |
| 245.0–245.3 M (post-sweep) | 941 | 866 | 9 % | 91 % | 22 k / 0.64 M |

Entropy of living organisms by energy band, same environment window 170–200 M (1 M snapshots),
clade vs. all other genomes:

| band | group | n | median SR | p90 SR | SR > 5 000 |
|---|---|---|---|---|---|
| ER < 50 k | aKRwb3 clade | 1 052 | 195 | 1 617 | 0.7 % |
| ER < 50 k | others | 552 | 1 893 | 5 977 | 21.0 % |
| ER < 50 k | all, 100–160 M | 918 | 4 287 | 7 142 | 36.1 % |
| 50–100 k | clade / others | 4 754 / 2 471 | 278 / 397 | 1 450 / 2 097 | 0.0 % / 2.2 % |

Single-organism trace, organism 48627 (pre-sweep genotype, died at 145 000 930 with SR = 10 000):
over its last 8 500 ticks ER fell by exactly 1 per tick and SR rose by exactly 1 per tick; the IP
stayed in `ENERGY.HARVEST` (body rows 16 and 22, call depth 1) throughout, no energy was found, no
cell was written. Traces of `aKRwb3` individuals (e.g. 56851) show row writes whenever ER ≥ 50 k
and SR near 0 most of the time.

## 3. Interpretation

### 3.1 What the insertion does

Conditional instructions skip NOP cells and then the next real instruction
(`Organism.skipNextInstruction`). Before the insertion, `GTI %SR 5000` guarded
`JMPI MAIN_REPRODUCE`; afterwards it guards the `NOT`, and `JMPI MAIN_REPRODUCE` executes
unconditionally. The `NOT %DR0` itself has no data effect: `REPRODUCE.CONTINUE` overwrites its
`ER` reference parameter with `NRG ER` on entry to `CONTINUE_LOOP`.

Net behavioural change: the primordial reproduces only when ER > 100 000 (or SR > 5 000) and then
copies until ER < 50 000 (`REPRODUCTION_PAUSE_THRESHOLD`); the mutant calls `REPRODUCE.CONTINUE`
on every main-loop pass and therefore copies rows whenever ER ≥ 50 000, harvesting only through the
`MAIN_LOOP_ENERGY_CHECK` fallback. `MAIN_HARVEST` is unreachable from `MAIN_LOOP`.

### 3.2 Why that is a fitness advantage

Each instruction adds 1 entropy; death at 10 000. Writing a cell subtracts 500, so copying rows is
the only strong entropy sink. `ENERGY.HARVEST` is a blocking loop that returns only after an energy
pickup; the entropy valve `GTI %SR 5000` in `MAIN_LOOP` is therefore never reached while an organism
searches for energy. A primordial-type organism resets its entropy only by row writes after
harvesting from ≈50 k to >100 k (≥5 pickups), so its entropy drifts upward across long harvest
phases and it dies on the entropy clock whenever no energy is found within ≈10 000 ticks
(observation 2.4: 67 % entropy deaths; 36 % of low-energy individuals already above SR 5 000).
The mutant writes rows after almost every pickup (ER crosses 50 k), resetting SR to ≈0, and dies
only when energy actually runs out — a budget of ≈50 000 ticks instead of ≈10 000.

This is a change in mortality, not fecundity (per-capita births unchanged), and it is a
loss-of-regulation mutation: a conditional is disabled, no new function appears. It corresponds
to an earlier reproductive threshold under high extrinsic mortality (life-history shift).

### 3.3 Why the population grows sixfold

Entropy-clock mortality is largely independent of population density; it capped the population far
below the energy carrying capacity (tens of thousands of unharvested 10 k energy cells; 1 112 of
them within 1 200×1 200 cells of a starving mutant at 170 M). With that mortality removed, the
population grows until energy deaths balance births: the world fills, energy cells drop from
60–80 k to a few thousand, per-capita energy falls to ≈55 k.

## 4. Method notes (for reproduction)

- Snapshots: `GET /visualizer/api/organisms/{tick}` → organisms (id, parent, birth/death tick,
  energy, entropy, genome hash, position) and `genomeLineageTree` (genome hash → parent hash).
- Clade membership: walk the lineage tree from an organism's genome hash to the root; member if
  the target hash is on the path.
- Genome extraction: organism detail → `initialPosition`; environment region ±300 cells; keep cells
  with `owner_id` = organism, restrict to the body box, drop DATA cells (as the hash does), normalise
  LABEL/LABELREF by XOR with the anchor label (smallest (x,y) among LABELs). Diff two such maps.
  Caveat: DATA cells (including immediate operands such as the thresholds `DATA:100000`,
  `DATA:5000`) are invisible to the genome hash; row dumps including DATA were compared separately.
- Death causes: dense snapshots at 100-tick spacing; a dead organism appears exactly once with its
  final `energy`/`entropyRegister`; `energy ≤ 0` → energy death, `entropyRegister ≥ 10000` → entropy
  death (no other case occurred).
- Traces: organism detail per sampled tick gives `instructions.last` (opcode, IP, cost) and the
  call stack; IP minus `initialPosition` maps to body rows (energy.evo rows start at y = 8,
  reproduce.evo rows at y = 31).

## 5. Unresolved details

- Entropy resets of −1 000 … −3 000 without energy gain occur every few thousand ticks in both
  genotypes while searching; they coincide with ≈100–130 extra energy spent (consistent with a
  failed `PEEK` under conflict, error penalty 100, followed by the state stores of `HARVEST` and
  `REPRODUCE`). One case was a fuzzy jump into a neighbour's body (IP executing foreign code,
  foreign-label penalty 100). Neither was verified systematically.
- How much of the sweep's speed is due to the insertion alone versus later mutations within the
  clade was not separated; the insertion is the only trait shared by all dominant descendants.

## 6. Follow-up items (decisions pending)

0. **Determinism.** Make a run reproducible from seed + config + code: derive label-matching
   randomness per organism (or per call) instead of one shared stream; checkpoint the per-organism
   and label RNG state on resume; make the worker-thread count explicit configuration instead of
   `cores − 2`; align (or explicitly document) the sequential vs. parallel wave semantics. Must land
   before any replication run; changes run output, so coordinate with persisted-format versioning.
1. Reproduce the phenomenon on the demo system (500 GB) — see constraints in §1: exact
   reproduction of this run is not possible with current code because of the resume bug; a fresh
   run is a statistical replication.
2. Replication with further seeds under storage constraints (`sparse` profile, analytics only).
3. Analytics gaps: death reason in tick data, lineage-based clade share, DATA blindness of the
   genome hash. The `GenerationDepthPlugin` restart defect is now covered by
   [ANALYTICS_PLUGIN_STATE](../proposals/ANALYTICS_PLUGIN_STATE.md).
4. Regime-diagnostic notebook (Eyad) on window 170–200 M.
5. Primordial design decision (keep, fix, or adopt the evolved variant).
6. Publication / community communication.
7. Side findings of §5.
8. Continuing the run beyond 271 M.
9. Organism-level forensics as reusable tooling: a notebook chapter and/or an `inspect` CLI
   subcommand (lineage walk, genome extraction + normalised diff, death-cause windows, traces);
   the ad-hoc scripts used here are kept in `run-20260402-scripts/`.
