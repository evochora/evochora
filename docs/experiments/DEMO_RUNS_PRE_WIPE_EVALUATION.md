# Evaluation of the demo-server runs before the format migration wipe

**Status: evaluation complete for everything the public HTTP API could deliver. The organism/environment
endpoints failed part-way through (see §0.3); the items that depend on them are marked
"not obtainable" and listed in §6.**

| Item | Value |
|---|---|
| Evaluation date | 2026-08-19 / 2026-08-20 |
| Server | `https://evochora.org`, public HTTP GET only, no shell access, nothing written to the server |
| Build serving the data | static assets `Last-Modified: 2026-03-04`; run metadata `metadata.version = 0.4.0`; the stored primordial sources still use the pre-module-system syntax (`.INCLUDE` / `.REQUIRE`, `LABEL: EXPORT`) that today's repository writes as `.IMPORT` / `.SOURCE` / `EXPORT LABEL:` — i.e. a build from before PR #102 |
| Runs listed by `/analyzer/api/runs` | 3 (see §1) |
| Analysis workspace | scratchpad only; nothing added to the repository except this document |

Observations and interpretation are kept apart. Every numbered observation names the endpoint and the
metric it comes from; interpretation sections say what the numbers are taken to mean and where the
evidence stops.

---

## 0. Method and constraints

### 0.1 Endpoints used

| Endpoint | Purpose | State during the evaluation |
|---|---|---|
| `GET /analyzer/api/runs` | run inventory | working |
| `GET /analyzer/api/manifest?runId=…` | metric inventory | working (identical static list for all runs) |
| `GET /analyzer/api/tick-range?metric=…&runId=…[&lod=…]` | tick range + file count per metric | working |
| `GET /analyzer/api/parquet?metric=…&runId=…&lod=lodN[&tickFrom=&tickTo=]` | analytics as Parquet | working throughout |
| `GET /visualizer/api/simulation/metadata?runId=…` | `resolvedConfigJson`, `programs`, opcode/molecule maps | working |
| `GET /visualizer/api/organisms/ticks?runId=…` | tick range of organism snapshots | worked, then HTTP 500 (see §0.3) |
| `GET /visualizer/api/organisms/{tick}[?runId=]` | organism snapshot + `genomeLineageTree` | worked for 5 requests, then HTTP 500 |
| `GET /visualizer/api/organisms/{tick}/{id}` | organism detail (`initialPosition`, `lineage`) | worked intermittently, then HTTP 500 |
| `GET /visualizer/api/environment/{tick}?region=…` | environment region (protobuf) | HTTP 429, then HTTP 500 |

The LOD levels are **subsampling, not aggregation**: the `lod4` row at tick *T* is byte-identical to the
`lod0` row at tick *T* (verified on `genome` at ticks 1 730 000 000 and 1 736 000 000). `lod0` is the raw
50 000-tick sampling grid, `lod4` is every 1 000 000 ticks. This makes it legitimate to join `lod4` rows
of different metrics with each other.

### 0.2 Two derived quantities used throughout

Both are computed from analytics only, and both come from the plugin sources in this repository
(`GenomeAnalyticsPlugin`, `InstructionUsagePlugin`, `PopulationMetricsPlugin`):

* **bodied count** `= sum of the values in genome.genome_data`. `GenomeAnalyticsPlugin.extractRows`
  skips every living organism whose `genomeHash == 0`, and the `genome_data` map (top 10 + `other`)
  covers exactly the organisms it did count. `population.alive_count` counts *all* living organisms.
  Therefore `alive_count − bodied` = number of living organisms with genome hash 0.
  `GenomeHasher.computeGenomeHash` returns 0 when the organism owned no cells at the moment its hash
  was taken, which is the post-execute phase of its birth tick.
* **frozen count** `= alive_count − Σ(instruction_usage family columns)`. `InstructionUsagePlugin`
  counts one entry per living organism that has an `instructionOpcodeId` in the tick data, and
  `Organism.resetTickState()` (called from `VirtualMachine.plan`) clears that field at the start of
  every tick. An organism without the field executed nothing in that tick.

Sanity check: of run 1's 1 735 lod4 ticks below 1 735 000 000 the instruction total equals
`alive_count` exactly at 1 663 (e.g. 506/506 at 100 M, 829/829 at 900 M, 836/836 at 1 700 M) and
differs by 1 – 2 at the other 72 — i.e. the frozen count is a hard 0 (± single individuals) over the
first 86 % of the run.

### 0.3 Server load and the endpoint outage — read this before repeating the work

The organism snapshot endpoint answered the first request for run 1 in ~30 s with a 6.6 MB body. Four
concurrent requests produced `429 Too Many Requests` ("Server is under heavy load") on the environment
endpoint within minutes, and shortly afterwards **all DB-backed endpoints
(`/visualizer/api/organisms/*`, `/visualizer/api/environment/*`) began returning
`500 {"message":"Database error occurred"}` within 0.2 s** and did not recover during the following
hour of polling at 60-second intervals. The file-backed analyzer endpoints kept working normally the
whole time.

Consequences for this evaluation: only **one** organism snapshot (run 1, tick 1 735 500 000) and one
organism detail record could be retrieved. Clade shares from the lineage tree, genome extraction from
environment regions, and the row-4 comparison against the SSD-run mutation are therefore **not
obtainable** and are listed as open items in §6. Everything else below rests on analytics Parquet and
run metadata, which are complete.

Timeline: last successful organism snapshot 2026-08-19 22:14 UTC; first `429` on the environment
endpoint 22:28 UTC; from 22:33 UTC onward every `/visualizer/api/organisms/*` and
`/visualizer/api/environment/*` request returned `500`, still so at 23:05 UTC when this document was
finished. `/analyzer/api/*` answered `200` throughout, including at 23:05 UTC.

If this work is repeated: use **one** request at a time against `/visualizer/api/*`, and expect
~30 s and several MB per organism snapshot.

---

## 1. Run inventory

| # | runId | started (UTC) | world | sampling | tick range (organism index / analytics) | analytics files per metric |
|---|---|---|---|---|---|---|
| 1 | `20260226-03114337-11f7e3fc-d67c-4012-930d-cea20143b3b5` | 2026-02-26 03:11:43 | 4096 × 2304 TORUS | 50 000 | 0 … 2 017 450 000 | 807 |
| 2 | `20260227-14120409-e3932a5a-723a-49f7-b45e-23b22021ceed` | 2026-02-27 14:12:04 | 2048 × 1152 TORUS | 50 000 | 0 … 264 950 000 | 106 |
| 3 | `20260226-03063158-c8458c4a-7942-4592-bfc4-4fe00eff1458` | 2026-02-26 03:06:32 | 2048 × 1152 TORUS | 1 | 0 … 480 999 / 481 099 | 1 925 (lod0) |
| 4 | `20260226-21502638-…` (11 MB, raw only) | — | — | — | — | — |

Run 4 is **not served at all**: it does not appear in `/analyzer/api/runs`, and
`/visualizer/api/simulation/metadata?runId=20260226-21502638` answers 404. There is no public HTTP
surface for it, so nothing about its content could be established.

All three served runs use seed 42, one primordial organism at (175, 135) with 100 000 initial energy,
and the same primordial sources (`assembly/primordial/main.evo` + `lib/{energy,state,reproduce}.evo`,
programId `687a936d`). Diffing the stored sources against the current repository shows only the module
syntax migration; `state.evo` is byte-identical, and the semantics of `main.evo` (MAIN_LOOP at
`.ORG 0|4`, `GTI %ER 100000` / `GTI %SR 5000` guarding `JMPI MAIN_REPRODUCE`) are unchanged. The
mechanism found in the SSD run is therefore expressible in these runs.

---

## 2. Run 1 — `20260226-03114337-…` (2.017 billion ticks)

### 2.1 (a) Basic facts and configuration deviations

| Item | Value |
|---|---|
| Tick range | 0 … 2 017 450 000 (organism index and all analytics agree) |
| Sampling interval | 50 000 → 40 349 sampled ticks; `snapshotInterval` 10, `accumulatedDeltaInterval` 5, `chunkInterval` 1 |
| World | 4096 × 2304 = 9 437 184 cells, TORUS |
| Organisms created (cumulative `vital_stats.total_born`) | **14 638 097** at tick 2 017 000 000 |
| `totalOrganismCount` at tick 1 735 500 000 (organism snapshot) | 11 259 906 |
| Unique genomes ever (`genome.total_genomes`) | 150 521 at the end |

Deviations from the SSD run configuration listed in `RUN_20260402_SELECTIVE_SWEEP.md` §1
(everything not listed here is identical, including `max-energy` 150 000, `max-entropy` 10 000,
`error-penalty-cost` 100, base cost 1 energy + 1 entropy, write −500 entropy, own-cell read +1,
`PreExpandedHammingStrategy` with tolerance 2 / `selectionSpread` 50 / `foreignPenalty` 100,
`SeedEnergyCreator` 0.25 % × 10 000, `GeyserCreator` interval 100 / amount 10 000 / safetyRadius 3,
`SolarRadiationCreator` amount 10 000, `DecayOnDeath` → `CODE:0`):

| Parameter | SSD run | Demo run 1 |
|---|---|---|
| World | 7680 × 4320 (33.2 M cells) | 4096 × 2304 (9.4 M cells) |
| Sampling interval | 100 | 50 000 |
| `GeyserCreator.percentage` | 0.0001 (≈3 300 geysers) | 0.0002 (≈1 887 geysers) |
| `SolarRadiationCreator.probability` | 0.01 / tick | 0.02 / tick |
| `SeedEnergyCreator.amountVariance` | not documented | 0.2 |
| `GeneDuplicationPlugin.duplicationRate` | 0.2 (per the task brief; not recorded in the SSD document) | **0.1**, `minNopSize` 8 |
| `runtime.parallelism` | 0 → cores − 2 | 3, with `parallelism-scaling` 64→2, 128→4, 256→8, 512→0 |

Per-cell energy input is therefore ≈4× the SSD run's (twice the geyser density, twice the solar
probability, on a world with 3.5× fewer cells), which matters for §2.5.

Other mutation plugins (identical in runs 1–3, not documented for the SSD run):
`GeneDeletionPlugin` rate 0.025 / countExponent 2, `GeneInsertionPlugin` rate 0.05
(weight 3 generic instruction, weight 1 label bitflip×2), `GeneSubstitutionPlugin` rate 0.025,
`LabelRewritePlugin`.

### 2.2 (b) Population trajectory

`population` and `genome` at lod4 (1 M ticks), 200 M-tick buckets. "bodied" is the derived quantity
from §0.2; before 1 735.6 M it is within 5 % of `alive_count`, afterwards it is the only meaningful
population number (see §2.3).

| ticks | bodied (mean) | min … max | avg energy | avg entropy | births / Mtick | energy cells in world |
|---|---|---|---|---|---|---|
| 0 – 200 M | 474 | 1 … 574 | 56.3 % | 8.0 % | 700 – 1 200 | 23 600 |
| 200 – 400 M | 571 | 444 … 633 | 55.1 % | 7.1 % | 500 – 800 | 29 600 |
| 400 – 600 M | 609 | 526 … 675 | 55.7 % | 6.7 % | 500 → 3 100 | 31 500 |
| 600 – 800 M | 691 | 559 … 777 | 59.1 % | 5.5 % | 2 000 – 10 000 | 68 400 |
| 800 – 1000 M | 776 | 674 … 819 | 61.1 % | 5.4 % | 9 000 – 18 800 | 229 500 |
| 1000 – 1600 M | 782 | 675 … 829 | 62.7 % | 4.7 % | 3 000 – 15 000 | ≈320 000 |
| 1600 – 1735 M | 790 | 592 … 836 | 63.5 % | 4.4 % | ≈13 000 | ≈290 000 |
| 1877 – 2017 M | 760 | 623 … 815 | 65.5 % | 3.4 % | 9 000 – 14 000 | ≈270 000 |

Phases:

1. **Colonisation, 0 – 25 M.** 1 → ≈400 organisms; `alive_count` crosses 100 at 4 M, 400 at 25 M.
2. **Slow growth, 25 M – 550 M.** Bodied population 400 → 630, per-capita birth rate falls from
   2.1 to 0.7 per organism per Mtick, median age reaches 10–20 M ticks. Avg energy ≈55 %, avg
   entropy 6–8 %.
3. **Fecundity step, tick 554 100 000.** `vital_stats` at lod0: births per 50 000 ticks go
   33 → 139 → 162 → 156 → … between ticks 554 050 000 and 554 150 000 and stay at ≈160 thereafter;
   the population is unchanged across the step (608 → 616). A second, equal step at ≈568 M doubles the
   rate to ≈6 250 births/Mtick, and it drops back to ≈3 100 at ≈583 M. Further steps follow; by
   940 – 980 M the run sustains 15 000 – 18 800 births/Mtick with 780 – 810 bodied organisms alive,
   i.e. **19 – 23 births per organism per Mtick against 0.7 – 2.1 before 550 M**.
4. **Plateau, 800 M – 2017 M.** Bodied population oscillates 650 – 830 with no trend for
   1.2 billion ticks. Avg energy drifts 61 % → 66 %, avg entropy 5.4 % → 3.1 %. World energy cells
   rise from 23 600 (tick 0) to ≈300 000 (3.2 % of all cells) between 400 M and 1000 M and then
   plateau: the population does not consume the energy the world produces.
5. **Crashes.** Eleven events in which the bodied population fell by more than 12 % within 1 Mtick:
   ticks 219 M (513→451), **388 M (600→444, −26 %)**, 1435 M, **1569 M (824→660, −20 %)**, 1581 M,
   1625 M, 1718 M, 1719 M (814→703→611 in two steps), 1865 M, 1933 M, 1972 M. Every one recovered
   within 3 – 10 Mticks. The minimum bodied population after tick 50 M is **444** (tick 388 M), so the
   run was never close to extinction.

### 2.3 (b/e) The dominant feature of this run is not biological: two cohorts of non-executing organisms

This is the single most important observation about run 1, and it explains the entire apparent
"sixfold population explosion" that `population.alive_count` shows at 1 736 M.

**Observation 1 — the explosion is entirely genome-hash-0 organisms.**
`population.alive_count` rises 701 → 2 270 between ticks 1 735 000 000 and 1 736 000 000 and stays at
≈2 400 until 1 876 M. Over the same interval the bodied count (§0.2) is flat:

| tick | alive_count | bodied | hash-0 |
|---|---|---|---|
| 1 735 400 000 | 734 | 709 | 25 |
| 1 735 500 000 | 723 | 699 | 24 |
| 1 735 550 000 | 750 | 690 | 60 |
| 1 735 600 000 | 921 | 695 | 226 |
| 1 735 700 000 | 1 252 | 691 | 561 |
| 1 735 800 000 | 1 588 | 692 | 896 |
| 1 735 900 000 | 1 927 | 694 | 1 233 |
| 1 736 000 000 | 2 270 | 703 | 1 567 |
| 1 736 050 000 | 2 363 | 705 | 1 658 |
| 1 736 200 000 | 2 361 | 702 | 1 659 |
| 1 800 000 000 | 2 444 | 782 | 1 662 |
| 1 875 000 000 | 2 403 | 753 | 1 650 |

The hash-0 count grows by 166 – 172 per 50 000 ticks for eleven consecutive samples — one new
individual every ≈300 ticks — and then stops dead at ≈1 660 and stays within ±15 of that value for
**140 million ticks**. `vital_stats` confirms the extra births: 15 300 births/Mtick before, 19 682 in
the Mtick 1 735 → 1 736 M.

**Observation 2 — 1 595 of them never execute an instruction.**
Over the run's 2 018 lod4 ticks the frozen count is non-zero at 288 of them. Before tick
1 735 000 000 there are 72 such ticks and the value is **never more than 2** (single individuals that
lost a conflict in that tick); from 1 736 000 000 onward it is in the thousands:

| tick | alive | bodied | frozen |
|---|---|---|---|
| 1 700 000 000 | 836 | 824 | 0 |
| 1 740 000 000 | 2 385 | 728 | 1 579 |
| 1 800 000 000 | 2 444 | 782 | 1 593 |
| 1 860 000 000 | 2 755 | 808 | 1 624 |
| 1 876 500 000 | 2 406 | 754 | 1 597 |
| **1 876 550 000** | **2 380** | **745** | **112** |
| 1 876 600 000 | 1 183 | 708 | 198 |
| 1 900 000 000 | 1 292 | 785 | 0 |

`age_distribution` corroborates this independently: from 1 736 M onward the median age increases by
exactly 1 tick per tick (p50 = 327 993 at 1 736 M, 1 302 017 at 1 737 M, 64 185 897 at 1 800 M →
birth tick 1 735 814 103, 139 125 994 at 1 875 M → birth tick 1 735 874 006). The median member of the
population is a member of the 1 735.6 M cohort and never dies.

**Observation 3 — the cohort ends in a single 50 000-tick window.**
At tick 1 876 550 000 the frozen count collapses 1 597 → 112, i.e. ≈1 485 previously non-executing
organisms executed an instruction in that sample; the same sample shows `avg_entropy` 12.2 %
(≈1 220 raw, against 1.5 % in every neighbouring sample), `avg_energy` 29.5 % (against 36.7 %) and
`failure_count` 159 (against ≈31). In the next sample, 1 876 600 000, `alive_count` has fallen
2 380 → 1 183 while bodied is unchanged (745 → 708). A smaller version of the same event occurred at
tick 1 863 550 000 → 1 863 600 000 (hash-0 2 034 → 1 653, bodied 813 → 809).

**Observation 4 — it happens again, instantaneously, near the end of the run.**
`population` and `instruction_usage` at lod0, ticks 1 980 350 000 → 1 980 400 000: frozen count
0 → 183, with `alive_count` unchanged (1 168 → 1 170) and no change in energy or entropy. 181 – 190
organisms stay frozen for the remaining 37 Mticks to the end of the run (184 of 1 256 alive at tick
2 015 000 000).

**Interpretation.** Observation 4 rules out biology: 183 organisms that were executing in one sample
are non-executing in the next, with no birth, no death and no change in any other metric. Whatever
produces the frozen class removes organisms from execution while leaving them in
`Simulation.getOrganisms()` with `isDead == false` — that is exactly the state that
`SimulationEngine.extractOrganismStates()` serialises into `TickData` and that
`PopulationMetricsPlugin` counts. The two candidate mechanisms that the available data cannot separate
are (i) organisms that leave the execution path but not the organism list (the parallel wave dispatch
in `Simulation.tickParallel` plans only organisms it visits, and `parallelism` was 3 with
`parallelism-scaling` in this run), and (ii) organisms whose death was never applied to the live
object. Observation 3 is compatible with either: at tick 1 876 550 000 the cohort re-enters execution,
accumulates entropy at 1 per instruction from ≈0, and 1 223 of them cross `max-entropy` 10 000 inside
the following 50 000-tick window — the classic entropy-clock death, arriving en masse because the
whole cohort restarted its clock simultaneously.

The practical consequence: **`population.alive_count` for run 1 is not a population curve after tick
1 735 550 000.** Anything published from this run must use the bodied count, and the bodied count shows
no regime change at 1 736 M whatsoever (703 before, 782 at 1 800 M, 785 at 1 900 M, 813 at the end).

Note that the hash-0 class as such is *not* an artefact: hash-0 organisms exist from ≈560 M onward at a
level of 3 – 42 individuals that tracks the birth rate (they are organisms that owned no cells at the
instant their hash was taken), they execute normally, and after the 1 876 M die-off ≈500 of them
survive and all execute. They are, however, **invisible to every genome metric**, which is a real
analytics gap independent of the frozen-organism question.

### 2.4 (c) Sweep detection

* `genome.dominant_share` never exceeds **0.061** after tick 0 (maximum over the whole run is at
  tick 200 M); at the end of the run it is 0.024. `shannon_index` rises monotonically 4.88 (100 M) →
  6.00 (1 800 M) → 5.93 (2 017 M); `active_genomes` rises 198 → 540; `total_genomes` reaches 150 521.
  No genome hash ever comes close to the >20 % share that would trigger a clade analysis.
* This is the same signature the SSD run showed (`RUN_20260402` §2.2: "the analytics `dominant_share`
  did not show the sweep because the clade diversified quickly"), so a hidden clade sweep is **not
  excluded** — it simply cannot be seen in the hash-level metrics.
* The lineage-tree walk that would decide the question requires
  `GET /visualizer/api/organisms/{tick}` at a series of ticks. The one snapshot that was retrieved
  (tick 1 735 500 000) does contain a `genomeLineageTree` with 139 941 entries and 1 628 organism
  records (723 alive), so the data exists and the method of `RUN_20260402` §4 would apply unchanged —
  but the endpoint failed before a series could be collected (§0.3). **Not obtainable in this
  evaluation.**

What *is* established, from analytics alone, is that run 1 contains at least one **discrete,
irreversible life-history shift**: the fecundity step at tick 554 100 000 (§2.2 phase 3). Its shape is
diagnostic — the per-tick birth rate jumps 5× within one 50 000-tick sample, the population does not
change, and the new rate is held to within ±5 % for tens of millions of ticks. A step of exactly this
size (+130 births per 50 000 ticks ≈ one birth per 385 ticks) is what a *single* individual reproducing
on a fixed cycle contributes; the later steps at 568 M (+3 000/Mtick) and the frozen-cohort creation
rate at 1 735.6 M (one per ≈300 ticks) are the same quantum. Mean lifespan falls correspondingly:
610 alive / 33 births per 50 k ticks ≈ 920 000 ticks before the step, 610 / 160 ≈ 190 000 ticks after.
Avg energy rises (55 % → 62 %) and avg entropy falls (7 % → 4.7 %) across the same window, which is the
same *direction* as the SSD sweep (entropy-clock mortality relieved), obtained here with an almost
constant population because the world is already energy-saturated.

### 2.5 (d) Genome extraction and the row-4 comparison

**Not obtainable.** The comparison asked for — row 4 of a dominant late organism against organism 1 at
tick 0, looking for an insertion between `GTI %SR 5000` and `JMPI MAIN_REPRODUCE` — needs
`GET /visualizer/api/organisms/{tick}/{id}` for `initialPosition` and
`GET /visualizer/api/environment/{tick}?region=…` for the owned cells. Both returned HTTP 500 for the
whole second half of the evaluation (§0.3); the environment endpoint had already returned HTTP 429
before that. No environment payload was retrieved, so the question of whether the current
`EnvironmentHttpResponse` proto still decodes this server's responses could not be answered either.

The prerequisites are in place for a repeat attempt: the primordial stored in the run metadata is
semantically identical to today's (§1), so the body box (x ∈ [−1, 110], y ∈ [−1, 85]), the row
offsets (energy.evo at y = 8, reproduce.evo at y = 31) and the label normalisation of
`RUN_20260402` §4 all apply unchanged.

### 2.6 (e) Other observations

* **Instruction mix.** `instruction_usage` is one entry per executing organism per sampled tick.
  Environment-interaction instructions fall from 4.9 % of executed instructions (100 M) to 2.3 %
  (1 700 M); the failure rate rises from 0.6 % (100 M) to 2.7 % (1 800 M) with a spike to 9.4 % at
  1 900 M and a maximum of 6.6 % inside the die-off sample at 1 876 550 000. A rising failure rate
  with a falling share of environment interaction is consistent with more organisms executing code
  they did not write (fuzzy jumps into foreign bodies carry the 100-energy foreign-label penalty), but
  the metric does not distinguish own from foreign code, so **this is a hint, not evidence of
  parasitism**.
* **Longevity.** `age_distribution.p100` grows by exactly 1 tick per tick from at least 1 625 M to
  1 875 M: one organism born at tick 591 066 205 was still alive at tick 1 800 M (age 1.21 billion
  ticks) and died between 1 800 M and 1 825 M. The single retrieved snapshot confirms it: organism
  **635693**, born 591 066 205, alive at 1 735 500 000 with 108 526 energy and entropy 38, genome hash
  `-7069889221256077201`. Whether this is a genuinely immortal genotype or another instance of the
  frozen class cannot be decided (it *does* have a non-zero hash and the frozen count was 0 at that
  tick, so on the available evidence it was executing).
* **No extinction risk** after tick 50 M (§2.2 phase 5).
* **Environment.** Energy cells accumulate 23 600 → ≈300 000 (0.25 % → 3.2 % of the world) and then
  plateau; code cells 264 000 → 418 000; structure cells 250 000 → 353 000. The world is not
  energy-limited at any point after 600 M.
* **`generation_depth`** rises smoothly to `max_depth` 136 / `avg_depth` 102 at the end, with none of
  the indexer-restart drops seen in the SSD run.

---

## 3. Run 2 — `20260227-14120409-…` (265 million ticks)

### 3.1 (a) Basic facts

| Item | Value |
|---|---|
| Tick range | 0 … 264 950 000 |
| Sampling interval | 50 000 (5 300 sampled ticks) |
| World | 2048 × 1152 = 2 359 296 cells, TORUS |
| Organisms created | **47 923** (`vital_stats.total_born` at tick 264 000 000) |
| Unique genomes ever | 18 579 |

Configuration deviations from run 1 (everything else identical, same seed, same primordial):
world halved in each dimension; `GeyserCreator.percentage` 0.0001 (vs 0.0002);
`SolarRadiationCreator.probability` 0.01 (vs 0.02); `SeedEnergyCreator.amountVariance` 0.1 (vs 0.2).
Relative to the SSD run this is the same set of deviations as run 1 except that geyser density and
solar probability match the SSD run exactly; only the world size (2.36 M vs 33.2 M cells) and the
sampling interval (50 000 vs 100) differ, plus `GeneDuplicationPlugin.duplicationRate` 0.1.

### 3.2 (b) Population trajectory

`population` at lod0, 10 M-tick buckets:

| ticks | alive (mean) | min … max | avg energy | avg entropy |
|---|---|---|---|---|
| 0 – 10 M | 48.6 | 1 … 77 | 73.4 % | 11.5 % |
| 10 – 170 M | 59.9 | 38 … 84 | 68.2 % | 10.3 % |
| 170 – 185 M | 66 (rising) | 48 … 89 | 61 % | 8.7 % |
| 185 – 230 M | 79.7 | 53 … 100 | 57.8 % | 8.2 % |
| 230 – 265 M | 72.4 | 53 … 91 | 58.8 % | 8.5 % |

The only structure in 265 M ticks is a **≈35 % rise between 170 M and 195 M** (60 → 82 mean alive)
with avg energy falling 68 % → 57 % and avg entropy 9.2 % → 8.3 %, followed by a slow decline. There
are no crashes (at the raw 50 000-tick resolution `alive_count` never drops by more than 30 % between
consecutive samples after tick 2 M), and the minimum after 5 M is 38.

### 3.3 (c/d/e) Sweeps, genome-0, frozen organisms

* **Frozen count 0 at all 265 lod4 ticks; hash-0 count 0 at 264 of them (maximum 1).** Neither
  anomaly of §2.3 occurs here.
* `genome.dominant_share` stays between 0.026 and 0.19 with no trend; `active_genomes` 22 → 70,
  `shannon_index` 2.9 → 4.2 (it rises with the population step at 185 M and falls again at 260 M);
  `total_genomes` 18 579. Every top-10 entry is a singleton or a handful; `other` always dominates.
  **No sweep at hash level, and the population is too small (≈65 individuals) for a clade analysis to
  be informative even if the lineage endpoint had worked.**
* The 170 – 195 M rise is a genuine but modest regime shift in a very small population; with 60
  individuals it is within the range that drift plus one lucky lineage can produce. Nothing in the
  analytics distinguishes it from noise beyond the fact that it persists.

Run 2 is essentially the SSD run's *pre-sweep* regime, on a 14× smaller world, that never swept: mean
alive ≈65, avg energy 57 – 73 %, avg entropy 8 – 12 % — compare `RUN_20260402` §2.1's 10 – 160 M window
(80 – 200 alive, 52 – 62 % energy, 8 – 16 % entropy).

---

## 4. Run 3 — `20260226-03063158-…` (step-by-step demo, 481 000 ticks)

* `organisms/ticks`: 0 … 480 999. Analytics `lod0` covers 0 … 481 099 in 1 925 files; `lod4` contains
  a single row (tick 0 … 249), so the LOD ladder was never built out for this run.
* `resolvedConfigJson`: identical to run 2 except `samplingInterval = 1` and
  `estimatedDeltaRatio = 0.001`. World 2048 × 1152, seed 42, same primordial.
* Content: **4 organisms in total** over the whole run (`vital_stats.total_born` = 4).
  `alive_count` = 1 until tick 190 000, 2 until 380 000, 3 at 390 000, 4 from 410 000 to the end;
  avg energy 66 % → 97 – 99 %, avg entropy oscillating 0 – 37 %.
* This is exactly what it was built for — a dense per-tick trace for the visualizer demo. It contains
  no population, no evolution and no events. Its only value is as a *demonstration dataset*: a
  sampling-interval-1 run that lets the visualizer step tick by tick.

---

## 5. Run 4 — `20260226-21502638-…` (11 MB, raw only)

Not exposed by any public endpoint (§1). No tick range, no metadata, no analytics. Nothing can be said
about its content from HTTP.

---

## 6. Open items that the endpoint outage blocked

These are the parts of the evaluation that could not be completed and that would need the DB-backed
endpoints back (or direct file access) **before** the wipe:

1. **Clade shares for run 1.** Organism snapshots on the 50 000-tick grid at, say, every 25 M ticks
   plus a dense series around 540 – 600 M, and the `genomeLineageTree` walk of `RUN_20260402` §4.
   This is the only way to find out whether the fecundity step at tick 554 100 000 is a clade sweep
   and, if so, how fast it fixed.
2. **Genome diff for run 1.** First carrier of the post-554 M genotype vs its parent, by the
   environment-region method; specifically the row-4 comparison against the SSD-run insertion
   (`NOT %DR0` between `GTI %SR 5000` and `JMPI MAIN_REPRODUCE`). The primordial layout is identical,
   so the same body box and normalisation apply.
3. **Whether the current proto still decodes this server's environment responses** — untested, because
   no environment payload was ever retrieved.
4. **Direct confirmation of the frozen class** — one organism snapshot at, e.g., tick 1 800 000 000
   would show the 1 595 individuals' IDs, birth ticks, parents, energies and IP positions and would
   settle §2.3's two candidate mechanisms in minutes. This is the single highest-value request that
   remains.
5. Run 4: any inspection at all.

---

## 7. Verdict: what would be lost by wiping

### Run 1 — `20260226-03114337-…`

**Worth archiving the analytics Parquet before the wipe; worth ~1 hour of organism-snapshot work first
if the endpoint can be restored.**

What is genuinely in it:

* A 2.017-billion-tick run — 7.4× longer than the SSD run — with a **discrete, irreversible
  life-history shift at tick 554 100 000** (per-capita birth rate ×5 within one 50 000-tick sample,
  then ×2 again at 568 M, reaching 19 – 23 births per organism per Mtick against 0.7 – 2.1 before), at
  constant population, with avg energy 55 % → 62 % and avg entropy 7 % → 4.7 %. This is the same
  *direction* as the SSD sweep and an independent occurrence of it, which makes it a **replication
  candidate for `RUN_20260402` follow-up item 1** at a fraction of the storage cost.
* A **1.2-billion-tick plateau** (800 M – 2017 M) at 650 – 830 organisms with 11 crash-and-recover
  events and no extinction — the longest stationary phase we have.
* A **documented defect signature** worth keeping: two cohorts of organisms (≈1 595 from tick
  1 735 550 000, 183 from tick 1 980 400 000) that are alive in `TickData`, execute nothing, never
  die, and in one case all die within a single 50 000-tick window when they resume executing. The
  1 980.4 M event is instantaneous and rules out a biological explanation. This is a real bug report
  about the pipeline or the parallel wave dispatch, and the analytics files are its only evidence.
* The demonstration that **`population.alive_count` can be silently wrong** and that
  **hash-0 organisms are invisible to every genome metric** — both feed `RUN_20260402` follow-up
  item 3 (analytics gaps).

Cost of keeping it: the complete `lod0` analytics for all nine metrics is ≈**110 bytes per sampled
tick × 40 349 ticks ≈ 4 – 5 MB** for the whole run (measured: 19.6 B/row for `population`, 24 for
`environment_composition`, 26.7 for `age_distribution`, 12.4 for `genome`, 12.8 for
`instruction_usage`, 9.7 for `generation_depth`, 6.9 for `vital_stats`). At `lod4` it is 271 kB. There
is no argument for not archiving this.

What would *also* be worth having, and needs the organism endpoint back: a handful of organism
snapshots (≈6.6 MB each) at 540 M, 560 M, 600 M, 900 M, 1 800 M. Five requests, ~35 MB, and they carry
the lineage tree that makes items 1, 2 and 4 of §6 answerable later without the server. **If the demo
server can be restarted before the wipe, these five requests are the highest-value action available.**

### Run 2 — `20260227-14120409-…`

**Worth archiving the analytics Parquet (~0.6 MB); no deeper analysis warranted.**

265 M ticks, 47 923 organisms, mean population 65, one modest 35 % rise at 170 – 195 M, no sweep, no
crashes, no anomalies. Its value is as a **negative control**: same seed, same primordial, same
mutation plugins, a 14× smaller world and half the energy input of run 1 — and none of run 1's
phenomena appear (no fecundity step, no hash-0 organisms, no frozen organisms). That is a useful
contrast for any write-up of run 1, and it costs ≈0.6 MB (5 300 sampled ticks × ~110 B). Everything
else about it is already stated in §3; nothing further would be learned by keeping the raw data.

### Run 3 — `20260226-03063158-…`

**Nothing of scientific value. Keep only if a sampling-interval-1 demo dataset is wanted for the
visualizer.**

4 organisms, 481 000 ticks, no evolution. Its analytics `lod4` is broken anyway (one row). If a
tick-by-tick demo run is needed after the migration it is cheaper to regenerate than to migrate.

### Run 4 — `20260226-21502638-…`

**Nothing can be judged; it is not served.** 11 MB of raw data with no analytics and no HTTP surface.
Unless someone can inspect it on disk, wiping it loses nothing that anyone can currently see. If it is
cheap to keep 11 MB, keep it; there is no basis for a stronger recommendation.

---

## 8. Reproduction notes

All numbers in this document come from these calls (base `https://evochora.org`, `R` = runId):

```
GET /analyzer/api/runs
GET /analyzer/api/manifest?runId=$R
GET /analyzer/api/tick-range?metric=$M&runId=$R[&lod=lod0]
GET /analyzer/api/parquet?metric=$M&runId=$R&lod=lod4                    # whole run, 1 M resolution
GET /analyzer/api/parquet?metric=$M&runId=$R&lod=lod0&tickFrom=A&tickTo=B  # 50 k resolution window
GET /visualizer/api/simulation/metadata?runId=$R
```

Metrics: `population`, `vital_stats`, `genome`, `genome_diversity`, `genome_population`,
`environment_composition`, `generation_depth`, `age_distribution`, `instruction_usage`
(`genome_diversity` and `genome_population` share the `genome` storage metric).

Derived quantities, as DuckDB over the downloaded Parquet:

```sql
-- organisms with genome hash 0 (invisible to all genome metrics)
CREATE OR REPLACE MACRO hashed(gd) AS
  (SELECT sum(CAST(json_extract_string(gd, '$."'||k||'"') AS BIGINT))
   FROM (SELECT unnest(json_keys(gd)) k));
SELECT p.tick, p.alive_count, hashed(g.genome_data) AS bodied,
       p.alive_count - hashed(g.genome_data) AS hash0
FROM population p JOIN genome g USING(tick);

-- organisms that executed no instruction in the sampled tick
SELECT p.tick, p.alive_count - (i.arithmetic+i.bitwise+i.conditional+i.controlflow+i.data
       +i.environmentinteraction+i.location+i.nop+i.stack+i.state+i.vector) AS frozen
FROM population p JOIN instruction_usage i USING(tick);
```

Use **one concurrent request** against `/visualizer/api/*`; four were enough to take the run database
offline for the remainder of this evaluation (§0.3).

---

## 9. Post-restart analysis of run `20260226-03114337`

*(Added 2026-08-20 after the demo server's database was restarted. §§1–8 above are unchanged; this
section is numbered 9 to avoid renumbering them. It resolves items 1–4 of §6 — see §9.10.)*

The DB-backed endpoints answered normally throughout this session. All requests were issued strictly
serially, one at a time: **8 organism snapshots** (`/visualizer/api/organisms/{tick}`, 4.4 – 7.0 MB,
25 – 30 s each), **13 organism details** (`/visualizer/api/organisms/{tick}/{id}`, 10 – 22 kB,
0.3 – 0.7 s each), **9 environment regions** (`/visualizer/api/environment/{tick}?region=…`,
4 – 67 kB, 0.6 – 5 s each) and one metadata call. Environment regions were kept to
146 × 116 cells (one body plus margin) instead of the ±300 pad of `RUN_20260402` §4; that is enough
for a full body and costs a fraction of the time. A final health check after all work returned
HTTP 200 on `/analyzer/api/runs`, `/visualizer/api/organisms/ticks` and
`/visualizer/api/environment/2000000000`.

**The current `http_api_contracts.proto` decodes this server's environment responses without
change** (§6 item 3). `git show 8919f646^:src/main/proto/.../http_api_contracts.proto` is
byte-identical to the file in the working tree, and `protoc --decode=…EnvironmentHttpResponse`
parsed every payload retrieved here. Note that the response is sparse: cells holding `CODE:0`
(= NOP = the environment's empty value) are omitted, so an intact 112 × 87 body returns
≈1 500 – 1 750 cells, not ≈9 700.

### 9.1 Observation: the tick-554 100 000 step is one organism forking non-viable children

The organism snapshot at a tick *T* contains every organism alive at *T* plus every organism that
died in the preceding 50 000-tick sampling window, each with its final energy and entropy. Counting
the dead records:

| tick | alive | dead in window | of those: died at age < 2 000 ticks | distinct parents of those |
|---|---|---|---|---|
| 553 950 000 | 602 | 29 | **0** | — |
| 554 100 000 | 616 | 131 | **107** | 426157 (103), 441727 (3), 447058 (1) |
| 554 500 000 | 604 | 156 | **130** | 426157 (128), 2 others |
| 556 000 000 | 630 | 170 | **137** | 426157 (136), 1 other |
| 560 000 000 | 631 | 165 | **148** | 426157 (136), 446875 (8), 470456 (4) |
| 570 000 000 | 648 | 308 | **288** | 453810 (153), 426157 (135) |

1. **The step is produced by a single individual.** Organism **426157** (born 477 328 267,
   parent 425856, `initialPosition` (3087, 135), genome hash `-5642661521693584337`) forks a child
   roughly every 300 – 360 ticks from tick **554 061 674** onward and does so continuously for at
   least the next 16 million ticks. Its output of 128 – 136 children per 50 000 ticks is exactly the
   step that `vital_stats` shows (§2.2 phase 3: 33 → 139 → 162 births per 50 000 ticks).
2. **The children are non-viable.** Of 105 children of 426157 in the 554 100 000 snapshot, 103 carry
   `genomeHash == 0` (they own no cells at all), and every completed one died at age **exactly 248
   ticks** with `energy = −48` and `entropyRegister = 248`. Its last normal offspring before the
   event, 470408 (born 553 873 081), had a real genome hash and lived 180 761 ticks.
3. **Each child receives the unmutated 25 000 energy and burns it on failures.** Child 470652 (born
   554 099 951) was alive in the 554 100 000 snapshot with energy 20 152 after 48 executed ticks; its
   detail record shows `instructionFailed = true`, `failureReason = "Max skips exceeded (100)"`,
   last opcode `NOP`, and `ip == initialPosition == (3174, 142)`. 48 × (1 base + 100
   `error-penalty-cost`) = 4 848, so its starting energy was 25 000. The same arithmetic closes on
   the death records: 248 × 101 = 25 048, i.e. 25 000 − 25 048 = −48. `REPRODUCTION_CHILD_INITAL_ENERGY`
   (`PUSI DATA:25000` at body cell (29, 71)) is unchanged in the parent's body.
4. **The children are forked inside the parent's own body.** Their initial positions — (3174, 142),
   (3176, 142), (3176, 144) — are body offsets (87, 7) / (89, 7) / (89, 9) of 426157, i.e. all-NOP
   padding rows inside the parent's shell. A child there has no reachable instruction, so every tick
   costs 1 (NOP) + 100 (skip-limit failure). The very first futile child, 470517 (born 554 061 674),
   started at body offset (−1, 85), the parent's south-west corner shell cell, and is the only one
   with a non-zero hash.
5. **The parent can afford it because it encloses a geyser.** The environment region around
   (3087, 135) contains a cluster of 10 000-energy molecules at body offsets (89 – 92, 5 – 21) at
   every sampled tick (4 cells at 553 950 000, 6 at 554 100 000). `GeyserCreator` injects 10 000
   every 100 ticks into a free unowned neighbour, i.e. ≈100 energy per tick, against the ≈69 energy
   per tick (25 000 per ≈360 ticks) the futile forks cost.
6. **The real population is untouched.** Deaths of organisms that were not futile children stay at
   **20 – 34 per 50 000 ticks** across the whole window (29 before the step, 29 / 28 / 34 / 25 / 20
   after), and the living population goes 602 → 648 over 16 Mticks — inside the run's ordinary drift.
   Median energy of the living stays 84 – 92 k and median entropy 354 – 430 throughout.

### 9.2 Observation: what changed in the body of 426157, resolved to one 50 000-tick window

Owned cells were extracted from environment regions at four consecutive sampled ticks and restricted
to the body box x ∈ [−1, 110], y ∈ [−1, 85] relative to `initialPosition`. The body is **bit-identical
at 553 950 000, 554 000 000 and 554 050 000** (1 510 owned cells each) and differs at 554 100 000
(1 505 cells). The complete difference — every change, nothing omitted:

| body cell | 553.95 / 554.00 / 554.05 M | 554.10 M | what it is |
|---|---|---|---|
| (1, 10) | `DATA:2` | `DATA:4` | `ENERGY.HARVEST_STATE` slot (KIDX) |
| (1, 33) | `DATA:4` | `DATA:0` | `REPRODUCE.CONTINUE_STATE` slot (`%DIRMASK`) |
| (2, 33) | `DATA:0` | `DATA:-1` | `REPRODUCE.CONTINUE_STATE` slot (`%SIDEVEC`) |
| (2, 37) | `SYNC` | — | duplicate (unlabelled) copy of `CONTINUE_INIT2` |
| (2, 50) | `SYNC` | — | **the live `CONTINUE_INIT2` block (`LAB:145050`)** |
| (2, 61) | `SEEK` | — | body code |
| (2, 74) | `REGISTER:0` | — | operand cell of the `FORK` at (0, 74) |
| (2, 85) | `STRUCTURE:100` | — | **hole in the body's own shell** |

The first three are the persistent state slots that every reproduction cycle rewrites; they are not
a genetic change. The other five are a contiguous erased segment of **body column x = 2, rows 37 – 85**
— one sweep that removed every non-empty molecule it passed, including the shell cell at (2, 85).
The first futile fork (554 061 674) falls inside this same 50 000-tick window.

`REPRODUCE.CONTINUE_INIT2` in the stored primordial reads

```
CONTINUE_INIT2:  DPLS ; SYNC ; SEKI -1|0 ; SEKI -1|0 ; SCNI %SHELL -1|0 ; …
```

with `SYNC` at x = 2 of its row. The removed `SYNC` is the instruction that re-anchors the data
pointer to the instruction pointer before the two westward steps that position the `SCNI` on the
body's own shell.

### 9.3 Observation: the same signature in two later, independent instances

`GET /visualizer/api/organisms/{tick}` at 570 M, 900 M and 1 740 M shows the same class of
individual recurring, always as a handful of separate organisms:

| tick | deaths in window | futile (age < 2 000, hash 0) | distinct futile parents |
|---|---|---|---|
| 570 000 000 | 308 | 288 | 2 |
| 900 000 000 | 780 | **715** | 7 (145 / 144 / 142 / 133 / 75 / 70 / 6 children) |
| 1 740 000 000 | 624 | **607** | 10 (157 / 142 / 136 / 77 / 75 / …) |

At 900 M, 709 of the 715 futile children have hash 0 and 538 died at age exactly 248; a second age
class of 79 children, all from parent 2283492, died at age 1 306 with energy −6 (25 000 spent over
1 306 instructions — the same 25 000 budget, a slower burn because that landing site contains some
executable code). The seven futile parents at 900 M carry **seven different genome hashes** with no
common recent ancestor in the lineage tree, and are 26 – 172 million ticks old.

The bodies of two of these parents were extracted and compared against four control organisms
(alive, non-futile, chosen by age rank) at the same ticks. The discriminating cell is the `SYNC` at
x = 2 of the *labelled* `CONTINUE_INIT2` row:

| organism | tick | role | labelled `CONTINUE_INIT2` at row | `SYNC` at x = 2 | occupied rows of column x = 2 |
|---|---|---|---|---|---|
| 426157 | 553 950 000 | before its event | 50 | **present** | −1, 1, 4, 8, 10, 12, 31, 33, 35, 37, 50, 61, 74, 85 |
| 426157 | 554 100 000 | futile forker | 50 | **absent** | −1, 1, 4, 8, 10, 12, 31, 33, 35 |
| 453810 | 570 000 000 | futile forker | 50 | **absent** | 10, 33 |
| 9997283 | 1 740 000 000 | futile forker | 50 | **absent** | −1, 33 |
| 457022 | 553 950 000 | control | 37 | present | −1, 4, 8, 10, 12, 31, 33, 35, 37, 85 |
| 469519 | 553 950 000 | control | 37 | present | −1, 0, 8, 10, 12, 31, 33, 35, 37, 61, 74, 85 |
| 10981089 | 1 740 000 000 | control | 50 | present | −1, 4, 8, 10, 12, 31, 33, 35, 37, 50, 85 |
| 11273214 | 1 740 000 000 | control | 36 | present | −1, 10, 12, 31, 33, 35, 36, 85 |

3 of 3 futile forkers lack it, 4 of 4 controls have it. The two later forkers have lost almost the
whole column x = 2, consistent with the same sweep repeating over tens of millions of ticks.
Organism 9997283 (born 1 637 711 401, `initialPosition` (559, 48)) sits in an energy-saturated
neighbourhood: 897 of the 10 000-energy cells in its 146 × 116 region lie inside its own body.

### 9.4 Observation: a population-wide substitution in `MAIN_LOOP` that is not the 554 M event

All **eight** bodies extracted here — the three futile forkers, four controls, and 426157 before its
event — carry `GDVR` at body cell (1, 4) where the stored primordial source (run metadata,
`programs[0].sources[…]/main.evo` line 51) has `NRG %ER`. Everything else in row 4 matches the
primordial layout cell for cell:

```
primordial:  1:NRG  2:%ER   7:NTR 8:%SR   13:GTI 14:%ER 15:D100000   20:JMPI 21:MAIN_REPRODUCE   26:GTI 27:%SR 28:D5000   33:JMPI …
all 8 seen:  1:GDVR 2:%ER*  7:NTR 8:%SR   13:GTI 14:%ER 15:D100000   20:JMPI 21:MAIN_REPRODUCE   26:GTI 27:%SR 28:D5000   33:JMPI …
```

(*the operand cell (2, 4) is present in 4 of the 8 and erased in the other 4; an erased cell decodes
as register id 0 = `%DR0` = `%ER` either way.)

`GDVR` writes the direction vector into the register (`StateInstruction.handleGdv`), so `%ER` holds
an `int[]`, not a molecule. The following `GTI %ER DATA:100000` then takes the
`Mismatched operand types for comparison` path in `ConditionalInstruction`, which calls
`instructionFailed` (100 energy) **and**, because `conditionMet` stays false, still skips the
following `JMPI MAIN_REPRODUCE`. Three organism state records confirm the register content directly:
`dataRegisters[0]` of 426157 is `VECTOR [1, 0]` at 553 950 000, 554 000 000 and 554 050 000.

This is **not** the 554 M event: it is present before it, in the controls, and 1.2 billion ticks
later. On the sample available it is fixed or near-fixed in the population well before tick 554 M.

### 9.5 Observation: the frozen cohort of §2.3 has one parent and is a futile-fork product

The 1 740 000 000 snapshot (§6 item 4, the highest-value request that was outstanding) contains
2 385 living organisms, **1 657 of them with `genomeHash == 0`**. Of those, **1 635 have the same
parent, organism 9994870**, and birth ticks between **1 735 539 477 and 1 736 027 139** with a median
gap of **298 ticks** — the cohort of §2.3 Observation 1, at the rate §2.3 measured (one per ≈300
ticks). Their state at 1 740 M, i.e. ≈4.2 million ticks after birth:

* energy **24 268 – 24 900** (median 24 474; 1 579 of 1 635 in the 24 000 bucket) — the 25 000 fork
  endowment, minus ≈526;
* `entropyRegister` **26** (1 291 organisms) or **27** (280) — they executed 26 – 27 instructions in
  total and then stopped;
* 56 of the 1 635 instead hold 147 000 – 149 900 energy;
* organism 9994870 itself is not alive at 1 740 M.

The other 22 hash-0 organisms alive at 1 740 M are ordinary: four are futile children aged 32 – 296
ticks, the rest are 2.5 – 287 million ticks old with 100 – 149 k energy. The 728 organisms with a
non-zero hash have median energy 101 586 and median entropy 268.

### 9.6 Observation: other things visible in the snapshots

* **Death causes of the real population.** Excluding futile children, entropy deaths
  (`entropyRegister ≥ 10 000`) versus energy deaths (`energy ≤ 0`) per 50 000-tick sample are
  13/16, 12/17, 16/12, 21/13, 8/17, 9/11 at 553.95 – 570 M, 10/15 at 1 740 M — but **65/6 of 71 at
  900 M**. That single sample is strongly entropy-dominated; one sample is not a trend, but it is
  the only place in this evaluation where the SSD run's 67 % entropy-death regime (`RUN_20260402`
  §2.4) appears.
* **Longevity.** The futile forkers are all extreme long-livers (426157: 77 Mticks old when it
  broke; 8999418 at 1 740 M: 217 Mticks; 1214956 at 900 M: 172 Mticks) with very low entropy
  (0 – 96). §2.6's organism 635693 (born 591 066 205) is of the same class.
* **Neighbourhood overlap.** Bodies overlap in space: the 146 × 116 region around 426157 contains
  cells owned by four other organisms (455098 with 485 cells, 470514 with 83, 470407, 469641).
* **Genome-hash lineage depth** at 554 M is 26 hash generations for 426157's genotype; its organism
  lineage is 132 deep. Its genome hash is shared with its parent, grandparent and
  great-grandparent — the body change of §9.2 is invisible to the hash only because the hash was
  taken at birth; the erased `SYNC` is a CODE cell and *would* change the hash of any organism born
  after it.

### 9.7 Interpretation

`REPRODUCE.CONTINUE` uses one molecule, `%SHELL`, as the terminator symbol for every loop in the
copy machinery: `CONTINUE_BORDERMOVE`, `CONTINUE_CORNERMOVE`, `CONTINUE_READLINE` and
`CONTINUE_WRITELINE` all end on `IFR %TMP %SHELL`. `%SHELL` is loaded once per call, in
`CONTINUE_INIT2`, by stepping two cells west of the instruction pointer and scanning — and that
positioning depends entirely on the `SYNC` that first sets DP := IP. With the `SYNC` gone the two
steps and the scan start from a stale data pointer, so `%SHELL` is loaded with an arbitrary
molecule. Every terminator test then matches in the wrong place: the navigation loops walk through
the organism's own shell instead of stopping at it (which is what erased the rest of column x = 2
and punched the hole at (2, 85)), the row copy terminates after a few cells instead of 112, and
control reaches `CONTINUE_FORK` after a few hundred instructions instead of the ≈9 000 cell writes a
real copy costs. `CONTINUE_PREP_NEXT_REPRODUCTION` clears the molecule marker (`CMRI DATA:1`), so
`FORK`'s `transferOwnership` hands the child nothing. The result is a fork every ≈300 – 360 ticks
that pays the full 25 000-energy endowment for a child with no body, which then fails on every
instruction and dies after 248 ticks.

Read that way, the "fecundity step" of §2.2 phase 3 is **not a life-history shift and not a
selective event**. It is one long-lived organism, parked on a geyser, entering a broken reproduction
loop and converting geyser energy into dead children at a fixed rate. The step's characteristic
shape — a 5× jump within one sample, no change in population, then a rate held to ±5 % for tens of
millions of ticks — is the signature of exactly that, and §2.4's remark that "a step of this size is
what a *single* individual reproducing on a fixed cycle contributes" is confirmed literally.

The same reading extends to the rest of the run. At 900 M and 1 740 M, 91 % and 97 % of all deaths in
the sampled window are futile children of 7 and 10 individuals respectively; 715 futile deaths per
50 000 ticks is 14 300 per Mtick, which is essentially the whole 15 000 – 18 800 births/Mtick that
§2.2 attributes to phase 4. **The birth rate of run 1 after tick 554 M is therefore not a population
statistic**; the real turnover is the 20 – 71 non-futile deaths per 50 000 ticks, which shows no step
at 554 M at all. Together with §2.3's finding that `alive_count` is not a population curve after
1 735.5 M, this means two of the three headline series for this run — births and alive count — must
be recomputed from organism-level data before anything is published from them.

The condition is not heritable in the ordinary sense: the carriers' children all die, and the seven
carriers at 900 M share no recent genome ancestry. It arises in individual bodies, repeatedly and
independently, in organisms that have lived long enough (26 – 217 Mticks) for a single CODE cell to
be erased. What erases it was not determined (see §9.8).

The frozen cohort of §2.3 is the same phenomenon with a different ending: 1 635 futile children of
one parent, forked between 1 735.54 M and 1 736.03 M, that executed 26 – 27 instructions and then
stopped rather than continuing to fail. They keep ≈24 500 of their 25 000 endowment for 140 million
ticks. This settles what they *are* (§6 item 4) and narrows §2.3's two candidate mechanisms: they
are not organisms whose death was never applied — they never died, they were born already
bodiless — but why execution stops after 26 instructions instead of continuing to burn 101 energy
per tick, as the 554 M children do, is still open.

Finally, `GDVR` at (1, 4) (§9.4) is a separate, older and population-wide change with the opposite
sign to the 554 M event: it permanently closes the *energy* route into `MAIN_REPRODUCE`, leaving
`GTI %SR DATA:5000` as the only trigger, and adds a 100-energy failure to every main-loop pass. If
that reading is right, the whole population of this run reproduces on an entropy trigger only. It is
consistent with the rising failure rate of §2.6 but was not verified against a trace.

### 9.8 What could not be established

* **What erased the `SYNC`.** The sampling grid is 50 000 ticks; the erasure and the first futile
  fork fall in the same window, so their order is not resolvable. The five erased cells lie in one
  column and were plausibly removed by a single walk — but a walk with a valid `%SHELL` would have
  stopped at the shell, so either the `%SHELL` load had already failed for another reason, or the
  sweep came from outside the body. A neighbouring organism's `PEEK` is an equally consistent
  candidate: four other organisms own cells within 60 cells of the body.
* **Whether `GDVR` is truly fixed.** Eight bodies were examined, not a random sample of the
  population; "fixed or near-fixed" is an inference from 8/8 at two ticks 1.2 billion apart.
* **The exact instruction path of a futile cycle.** No dense (per-tick) trace exists; the run's
  sampling interval is 50 000 and the endpoint offers no finer grid. The reconstruction in §9.7 is
  read off the assembly plus the register/DP states of four sampled ticks, not observed step by step.
* **Why the 1 735.6 M cohort freezes after 26 instructions.** Needs either the raw tick data or a
  reproduction of the pipeline behaviour; it cannot be decided from the HTTP surface.
* **Run 4** (§6 item 5) remains unexposed; nothing was attempted.

### 9.9 Status of the §6 open items

1. **Clade shares for run 1** — answered in the negative sense: there is no clade sweep behind the
   554 M step. The step is one organism (§9.1), the later steps are further individuals with
   unrelated genotypes (§9.3), and none of it propagates because the offspring die.
2. **Genome diff for run 1** — done (§9.2, §9.3, §9.4). The full normalised body diff across the
   event is eight cells, five of them one erased column segment; the `NRG %ER → GDVR` substitution
   in `MAIN_LOOP` is real but predates the event and is population-wide.
3. **Does the current proto still decode this server's environment responses** — yes, unchanged
   (§9 preamble).
4. **Direct confirmation of the frozen class** — done (§9.5): 1 635 of 1 657 hash-0 organisms alive
   at 1 740 M are children of the single organism 9994870, forked at one per ≈298 ticks between
   1 735.54 M and 1 736.03 M, holding ≈24 500 energy and entropy 26 – 27.
5. **Run 4** — still nothing.

### 9.10 Relation to the SSD run (observation, not the frame of this analysis)

Stated only for the record, and after the fact. `RUN_20260402`'s finding was a heritable single-cell
insertion that spread through the population and changed mortality; per-capita births were unchanged
and the population grew sixfold. What run 1 shows at 554 M is the opposite in every structural
respect: a somatic (in-life) deletion in one individual, not heritable, no clade, no population
change, and an apparent birth-rate change that consists entirely of offspring that never live. The
two runs do share a substrate observation — that a single molecule in `REPRODUCE`/`MAIN_LOOP` can
retune the whole reproduction cycle, and that the genome hash does not see everything that matters
(there it was DATA operands, here a CODE cell erased after birth). Whether run 1 also contains a
genuine heritable sweep is untouched by this analysis; the `GDVR` substitution of §9.4 is the only
candidate seen, and testing it would need a body sample across the 100 – 550 M range.

### 9.11 Observation: the `GDVR` substitution of §9.4 is a completed selective sweep, 5 M – 900 M

*(Added 2026-08-20, the follow-up §9.10 asks for. Nothing above this subsection was changed.)*

**Cost.** 6 organism snapshots (`/visualizer/api/organisms/{tick}`, ticks 5 M, 20 M, 100 M, 250 M,
400 M, 500 M; 57 kB – 4.1 MB, 26 – 81 s each), 44 organism details (5 – 25 kB, 0.2 – 1.4 s each) and
**45 environment regions** (1.0 – 2.3 kB, mean 1.4 kB, 0.3 – 3.6 s each). All requests strictly
serial, ≥ 1.2 s apart. No 429, no 500, no timeout. Health check after the last request: HTTP 200 on
`/analyzer/api/runs`, `/visualizer/api/organisms/ticks` and
`/visualizer/api/environment/2000000000`. The snapshots of §§9.1 – 9.6 (553.95 M, 900 M, 1 740 M)
were reused from the earlier session without re-fetching.

Environment regions were reduced from the 146 × 116 box of §9 to a **48 × 5 strip**
(`x₀−2 … x₀+45`, `y₀+3 … y₀+7` relative to `initialPosition`), which covers the whole of `MAIN_LOOP`
row 4 plus `MAIN_HARVEST` / `MAIN_LOOP_ENERGY_CHECK` row 6. That is ≈2 % of the payload and ≈10 % of
the latency of a full-body region, and it is what made 44 bodies affordable. `initialPosition` is
**not** in the snapshot; it comes from `staticInfo.initialPosition` of the organism detail record, so
each body costs two requests.

The opcode ids were taken from the server's own metadata map
(`/visualizer/api/simulation/metadata` → `opcodes`) and not assumed: `28944 = NRG`, `29584 = GDVR`,
`16609 = GTI`, `20500 = JMPI`, `29008 = NTR`, and `0 = NOP`, which the environment endpoint omits
from its responses.

#### 9.11.1 Reference: the primordial state at tick 0

One strip at tick 0 around organism 1's `initialPosition` (175, 135):

```
y=4:  -1:STRUCTURE:100  0:LAB  1:NRG  2:%DR0   7:NTR  8:%DR1   13:GTI 14:%DR0 15:D100000
      [16..19 empty = NOP]  20:JMPI 21:LABREF   26:GTI 27:%DR1 28:D5000   33:JMPI 34:LABREF
      39:JMPI 40:LABREF
```

This is `main.evo` line 51 ff. cell for cell, and it fixes the ancestral state of both loci that turn
out to matter: `NRG` at (1, 4) and **NOP padding at (16 … 19, 4)**.

#### 9.11.2 The population carries exactly two mutually exclusive `MAIN_LOOP` variants

Of the 44 bodies read, every single one is one of two types, and the two differ at *two* cells of
row 4, not one:

| | (1, 4) | (2, 4) | (16 … 19, 4) | bodies |
|---|---|---|---|---|
| primordial (tick 0) | `NRG` | `%DR0` | NOP NOP NOP NOP | 1 |
| **variant A** | `GDVR` | `%DR0` (erased in 4 of 30) | NOP NOP NOP NOP | **30** |
| **variant B** | `NRG` | `%DR0` (erased in 1 of 14) | `VGTR %DR6 %DR7 %DR6` | **14** |

The association is perfect: 30/30 `GDVR` bodies have NOPs at 16 – 19, 14/14 `NRG` bodies carry the
`VGTR`. No body carried both changes, and no body carried neither. Everything else in row 4 — and
all of row 6 — matches the primordial layout in all 44.

Both variants are one edit from the primordial, and by the ISA they are **opposite** edits:

* `skipNextInstruction` (`Organism.java`) walks past NOPs and skips the next *real* CODE
  instruction; `ConditionalInstruction` calls it exactly when `conditionMet` is false. In the
  primordial the instruction guarded by `GTI %ER DATA:100000` is therefore the `JMPI MAIN_REPRODUCE`
  at (20, 4).
* **Variant A** puts a vector in `%ER` (`GDVR` = get direction vector), so
  `GTI %ER DATA:100000` takes the `Mismatched operand types for comparison` path: 100 energy
  penalty, `conditionMet` stays false, the `JMPI` is skipped **every time**. The energy route into
  `MAIN_REPRODUCE` is permanently closed and `GTI %SR DATA:5000` is the only remaining trigger.
  This is §9.4's reading, now confirmed on 30 bodies instead of 8.
* **Variant B** leaves the comparison intact but moves the guarded instruction: the next real
  instruction after the `GTI` is now the `VGTR` at (16, 4), not the `JMPI` at (20, 4). Whether the
  energy test passes or fails, execution arrives at the `JMPI MAIN_REPRODUCE` — on the true branch by
  falling through the `VGTR`, on the false branch by skipping it. The energy route is permanently
  **open**.

So the two variants are a regulatory switch stuck in its two opposite positions, competing in one
population.

#### 9.11.3 Carrier fraction in directly read bodies

Organisms were drawn **uniformly at random** from the bodied living population of each snapshot
(`isDead == false`, `genomeHash ≠ 0`), with at most one organism per parent to avoid siblings. The
100 M row also contains a second octet drawn by an older, deliberately diversity-stratified rule
(oldest organism per spatial bin, distinct genome hashes) — it is reported separately because that
rule oversamples old birth cohorts and is not a fair frequency estimator.

| tick | sampled bodies *n* | variant A (`GDVR`) | fraction |
|---|---|---|---|
| 0 (organism 1) | 1 | 0 | — (primordial) |
| 20 M | 4 | 2 | 50 % |
| 100 M (random) | 8 | 4 | 50 % |
| 100 M (stratified, old cohort) | 8 | 4 | 50 % |
| 250 M | 8 | 5 | 62.5 % |
| 400 M | 8 | 7 | 87.5 % |
| 500 M | 8 | 8 | 100 % |
| 554 M / 1 740 M (§9.4, not random) | 8 | 8 | 100 % |

#### 9.11.4 The carriers are one genome clade, so the fraction can be computed for the whole population

All 30 variant-A bodies share the genome hash `-5127331321190430415` on their ancestral path in
`genomeLineageTree`, and none of the 14 variant-B bodies does — **44/44 agreement**. That node is
four genome generations from the primordial genome `-836674516429840615`
(`-836674516429840615 → -709769230402479443 → 303937259130183321 → 3496254855536648327 →`
**`-5127331321190430415`**). The variant-B bodies sit on a different early branch of the same tree
(all 14 descend from `-3579820754707553964`, a direct child of the primordial genome).

Clade membership is therefore a valid proxy for the genotype, and it can be evaluated for *every*
living organism in a snapshot at no request cost (the method of `RUN_20260402` §4). Share of the
bodied living population:

| tick | bodied alive | variant-A clade | share | variant-B clade | share | neither |
|---|---|---|---|---|---|---|
| 5 M | 159 | 0 | **0.0 %** | 110 | 69.2 % | 49 |
| 20 M | 307 | 116 | **37.8 %** | 180 | 58.6 % | 11 |
| 100 M | 506 | 226 | **44.7 %** | 280 | 55.3 % | 0 |
| 250 M | 558 | 384 | **68.8 %** | 174 | 31.2 % | 0 |
| 400 M | 540 | 482 | **89.3 %** | 58 | 10.7 % | 0 |
| 500 M | 595 | 566 | **95.1 %** | 29 | 4.9 % | 0 |
| 553.95 M | 602 | 592 | **98.3 %** | 10 | 1.7 % | 0 |
| 900 M | 802 | 802 | **100.0 %** | 0 | 0.0 % | 0 |
| 1 740 M | 728 | 727 | **99.9 %** | 1 | 0.1 % | 0 |

The clade share and the directly read bodies agree everywhere (50 % vs 44.7 % at 100 M, 62.5 % vs
68.8 % at 250 M, 87.5 % vs 89.3 % at 400 M, 100 % vs 95.1 % at 500 M).

Two further facts from the same tables:

* The clade **did not exist at 5 M**: neither `-5127331321190430415` nor its parent
  `3496254855536648327` occurs anywhere in the 5 M lineage tree (256 nodes), while its grandparent
  `303937259130183321` does. The substitution therefore arose between tick **5 M and 20 M**.
* From 100 M onward the run contains **only these two clades** — every bodied organism belongs to
  one of them.

A logistic fit of `logit(share)` against tick over 20 M – 554 M gives a slope of
**0.00828 per million ticks** (fitted values 0.308 / 0.463 / 0.749 / 0.912 / 0.959 / 0.974 against
observed 0.378 / 0.447 / 0.688 / 0.893 / 0.951 / 0.983), i.e. 50 % crossing at ≈118 M. Generation
time measured as parent-birth → child-birth over 317 newborns in five sampled windows is a median of
**13.5 M ticks** (mean 12.7 M), which puts the selection coefficient at **s ≈ 0.11 per generation**.
The one point the fit misses is the start: 0 % at 5 M to 37.8 % at 20 M is roughly one generation and
far too fast for s = 0.11, so the initial rise is founder-lineage expansion in a population of
159 – 307, not selection.

#### 9.11.5 Reproduction-route consistency (no additional requests)

If variant A closes the energy route, the whole population from ≈900 M onward can only enter
`MAIN_REPRODUCE` through `GTI %SR DATA:5000`. What the snapshots show:

* **Reproduction continues undiminished after fixation.** Non-futile newborns per 50 000-tick window
  are 39 / 45 / 173 / 32 / 28 at 100 / 250 / 400 / 500 / 554 M, and the bodied population is 802 at
  900 M and 728 at 1 740 M with the clade at 100 %. A population that is 100 % variant A is still
  reproducing, so the entropy trigger demonstrably works.
* **Per-capita birth rates of the two clades are indistinguishable** (futile forkers excluded):
  1.33 vs 1.71 per organism per Mtick at 100 M, 1.56 vs 1.72 at 250 M, 1.37 vs 1.03 at 400 M,
  1.10 vs 0.69 at 500 M. As in `RUN_20260402` §2.1, the sweep is not a fecundity effect visible in a
  single window.
* **Death causes do not separate the clades either.** Futile children excluded, entropy deaths
  (`entropyRegister ≥ 10 000`) vs energy deaths (`energy ≤ 0`) are 19/0 vs 25/1 at 100 M, 18/4 vs
  8/1 at 250 M, 22/16 vs 3/0 at 400 M. Entropy death dominates in *both* clades at every tick where
  both are numerous.
* **The standing entropy distribution is the same in both clades** and is far below the trigger:
  median 344 – 448, p90 ≈ 1 700 – 1 900, and only 1 – 2 % of organisms above 4 000, in the variant-A
  and variant-B subsets alike. Parents of newborns in a window carry median entropy 44 – 608 at the
  sampled tick, never ≈5 000.
* The reason the last two points are *expected* rather than contradictory is in the run's own
  configuration: `write-rules.*.entropy = -500` for the PEEK/POKE/PPK families. One successful cell
  write dumps 500 entropy, so an organism that crosses 5 000 and manages ten writes is back at 0
  within ten instructions, and `ENERGY.HARVEST` writes too. Entropy near the trigger is therefore a
  state the population passes through in a handful of ticks and a 50 000-tick sampling grid cannot
  catch it.

**What this establishes and what it does not.** The route argument is structural, not statistical:
`GDVR` writing an `int[]` into `%ER` makes `GTI %ER DATA:100000` type-mismatched by
`ConditionalInstruction`, and a mismatch is a guaranteed skip — so in a 100 % variant-A population
`GTI %SR DATA:5000` is the only surviving entry into `MAIN_REPRODUCE`, and the fact that the
population still reproduces proves that entry is used. What the snapshots do **not** show is a
reproduction event caught in the act: no organism was observed at `%SR ≳ 5 000` immediately before a
fork, because the sampling grid is 50 000 ticks and the entropy excursion lasts a few instructions.
The entropy-dominated mortality (and §9.6's 65/71 at 900 M) is consistent with the reading but does
not discriminate between the clades and is therefore not evidence for it.

#### 9.11.6 Interpretation

**This is a genuine heritable selective sweep, and it is complete.** The variant-A genotype is
absent at 5 M, present at 37.8 % at 20 M, crosses 50 % at ≈118 M, and is fixed by 900 M; it is
carried by a single genome clade whose membership predicts the row-4 genotype in 44 of 44 directly
read bodies; the substitution is a CODE cell, so it is visible to the genome hash and is inherited at
birth. §9.4's "fixed or near-fixed well before tick 554 M" was half right: **fixed by 900 M, but
still polymorphic at 44.7 % at 100 M** — the eight bodies of §9.4 were all read at 554 M or later,
i.e. after the sweep had essentially finished, which is why they looked uniform. The sweep runs
almost exactly through the 100 – 550 M window this check was aimed at.

The selected trait is a **regulatory shutdown**. Variant A gives up the energy trigger entirely and
pays 100 energy of `error-penalty-cost` on every single pass through `MAIN_LOOP` for the privilege
(the failed type comparison). It beat a competitor that had the *opposite* defect — variant B's
`VGTR` insertion moves the guarded instruction and leaves the energy trigger permanently on, so
variant B attempts `REPRODUCE.CONTINUE` on every main-loop pass. Between reproducing whenever energy
allows and reproducing only when entropy forces it, with a per-pass tax attached, selection took the
restrictive option at s ≈ 0.11 per generation. That is a plausible reading of a system in which
mortality is entropy-dominated and a reproduction attempt is expensive, but the mechanism of the
advantage is inferred from the ISA, not measured: no per-tick trace exists in this run.

This is also the answer to the question §9.10 left open. Run 1 **does** contain a heritable sweep,
and structurally it is the same kind of object as `RUN_20260402`'s: a single-cell change in
`MAIN_LOOP` that retunes the reproduction trigger, spreading through the population over tens of
generations with no visible per-capita fecundity change. The differences are that here the change is
a substitution rather than an insertion, it is visible to the genome hash rather than hidden in DATA
operands, it *restricts* reproduction rather than enabling it, and it produced no population-size
response at all — `alive_count` runs 506 → 802 across the entire sweep, inside the run's ordinary
drift. It is also completely unrelated to the 554 M step, which §§9.1 – 9.3 already showed to be
somatic and non-heritable: the sweep was over long before that step, and organism 426157 was itself a
variant-A carrier.

#### 9.11.7 What this subsection could not establish

* **The exact origin tick.** The clade is absent at 5 M and at 37.8 % at 20 M; no snapshot in between
  was taken. Two genome generations elapse in that window, so the founding organism is not
  identified.
* **The cause of the advantage.** Per-capita births and death causes do not separate the clades in
  any single 50 000-tick window, which is what an s ≈ 0.11 per-generation difference should look
  like — it is too small to see in windows of ≈0.004 generations. Establishing *why* variant A wins
  needs either many more windows or a dense trace, neither of which this run's 50 000-tick grid can
  supply.
* **Whether the two loci are truly alternative.** All 44 bodies are one or the other, but no
  double mutant and no unmutated survivor was seen after 100 M, so nothing is known about how a
  primordial-genotype organism would fare against either.
* **Sub-clade structure.** Clade membership was tested against one root hash; whether variant A
  itself contains further row-4 or elsewhere changes that rose within the clade was not examined.

### 9.12 Runtime explanation of the frozen cohort (code audit, verified against HEAD and image revision `346f4c27`)

The freeze of §2.3/§9.5 is **conflict-resolution starvation**. The runtime has exactly one path on
which a living organism's tick costs nothing and executes nothing: losing a write conflict. Only the
environment-modifying instructions (PEEK/POKE/PPK families) go through conflict resolution; when
several organisms target the same cell in one tick, the contenders are sorted by organism ID and the
lowest ID wins (`Simulation.java:695-707`); every loser's instruction is marked not-executed and
`executeSingleInstruction` returns before any energy/entropy/failure accounting
(`Simulation.java:526`). The loser's IP does not advance, so next tick it plans the identical
instruction on the identical target — permanent starvation for as long as any lower-ID organism
keeps targeting that cell.

This accounts for every recorded observation:
- 9994870's ~1 635 children were all forked at the same coordinates from the parent's stale DP and
  run the same code in lockstep; after ~26 instructions each reaches the same PEEK/POKE target and
  all but the lowest-ID contenders starve — frozen with entropy 26–27, energy intact.
- The 56 children holding 147–149.9 k energy are the round's **winners**: repeated PEEK on a
  geyser-fed ENERGY cell costs no entropy and pays the full molecule value
  (`reference.conf` read-rules: ENERGY −1000 ‰ / entropy 0 for own, foreign and unowned alike),
  pinning them near max-energy 150 000. 56 + 1 579 = 1 635.
- The collective wake-up at ≈1 876.55 M is the blocker disappearing: all losers resume at once,
  their entropy clocks run on from 26, and 1 223 cross max-entropy within one sampling window.
- The instantaneous second freeze at ≈1 980.4 M is a new lower-ID organism starting to target the
  cell — no birth or death required.
- The contrast with 426157's children (§9.1): those sat in all-NOP padding, and NOP never enters
  conflict resolution; they executed every tick, paid 1 + 100 (max-skip failure + error penalty)
  and died at 25 000 ⁄ 101 → age 248. 9994870's children landed on reachable PEEK/POKE code.
- §9.6's "extreme long-livers with entropy 0–96" fit the same physics: parked on a geyser-fed
  cell, harvesting entropy-free.

Analytics read `alive_count` and instruction counts from the same TickData, and the instruction
plugin counts only organisms whose instruction actually executed — so
`alive_count − instructions_per_tick` is a direct measurement of the starved set, not an artifact.

Decisive confirmation on the live data (done 2026-08-20, 2 serial requests plus the cached
1 740 M snapshot): (a) the 1 579 frozen children sit at exactly **two** IPs — (1547, 2083) with
1 290 and (1558, 2083) with 289 — and a sampled one (11265227, energy 24 474, entropy 26) has
**`PPKI` as its next instruction and no last instruction and no failure**: it plans an
environment-modifying instruction every tick and never gets to execute it. Its data pointers
target (1216, 263). (b) The region around that target holds **geyser-fed `ENERGY:10000` molecules**
(at (1215, 261) and (1216, 264)) and `DATA:8` cells **owned by lower-ID members of the same cohort**
(11260776, 11260895) — the conflict winners, which did execute their `PPKI` and thereby acquired
cells. (c) The 56 high-energy children are scattered over 56 distinct IPs (they kept executing and
harvesting), the 1 579 losers are pinned. The only prediction not separately checked is the identity
of the change that released the cohort at ≈1 876.55 M.

Design-relevant observations (for later discussion, not decided here): the lowest-ID rule is a
systematic age bias (older organisms always win contested cells); a conflict loser pays nothing,
which makes starvation free; `Instruction.ConflictResolutionStatus` is never serialised, so
starvation is invisible in all recorded data.
