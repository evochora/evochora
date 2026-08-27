# Run Analysis Tooling

**Status: TO BE REVIEWED**

## Purpose

Make the scientific analysis of simulation runs easy in two directions: **for agents**, who should
find sweeps and discriminate artifacts from the Parquet analytics alone wherever possible, and
**for humans**, who should see the same signals as charts in the Analyzer. Today both depend on
expensive HTTP forensics against a running node and on specialist knowledge that lives in the
`analyze-run` skill.

## Motivating findings (from three analyzed runs)

1. **Selective sweeps are invisible in every aggregate curve.** Three runs each contained a
   completed sweep; none is visible in population, birth rate, dominance or diversity metrics.
   Detection required organism snapshots, a merged genome lineage tree, and clade-share
   computation — ~60 serial HTTP requests plus custom code.
2. **At high mutation rates the diversity metrics measure the mutation rate, not selection.**
   In run `20260826-17221710` (~34 % of births create a new genome hash) a sweep with s ≈ 0.44 per
   generation ran to fixation while `dominant_share` stayed at 0.02–0.03 and `shannon_index` rose
   monotonically.
3. **A single damaged organism can distort `vital_stats` by a factor of 3.** Futile forkers
   (non-viable stub children, dying after an exact constant lifetime — 248 ticks in two runs)
   dominated the birth statistics in two of three runs. The reliable detector is the lifetime
   distribution of recently died organisms — currently not exported anywhere.
4. The genome lineage (`genomeAncestors`) is only available piggybacked on the organism snapshot
   endpoint, which costs up to ~30 s per tick on large runs.
5. The environment endpoint answers protobuf only, forcing a `protoc` dependency and fragile text
   parsing onto every body-forensics step.
6. `generation_depth` resets to near zero on every indexer restart (issue #112), making the metric
   unusable for any run with restarts.

## Changes

Ordered; each item names its acceptance criterion. Thresholds named below are fixed constants,
not configuration.

### 1. Lineage export

The indexer writes a `genome_lineage` analytics table (Parquet, like the other metrics):
`genome_hash`, `parent_genome_hash`, `first_seen_tick`. Append-only, one row per genome, complete
from tick 0. *Accepted when* the merged tree an agent builds from organism snapshots is
reproducible from the Parquet table alone for a current run.

### 2. Clade-share analytics plugin and Analyzer chart

A new analytics plugin `clade_shares` computes, per sampled tick, each clade's share of the bodied
population. Clade roots start as the children of the primordial genome; when one clade holds
≥ 99 % of the bodied population for 10 consecutive samples, the tracker re-roots to that clade's
children — so nested sweep cascades (three levels in run `20260826-17221710`) stay visible. The
export carries the top 8 clades per tick plus an `other` bucket; the Analyzer renders it as a
stacked area chart (the Muller-plot view). *Accepted when* the three documented sweeps are visible
as rising bands in the chart of their runs' data.

### 3. Bodied count in the population metrics

`population` gains a `bodied_count` column (living organisms with genome hash ≠ 0 — computed
directly, not derived from `genome_data` JSON), and the population chart shows it as a second
line. The gap to `alive_count` makes ghost cohorts visible at a glance. *Accepted when* the
1 735.6 M ghost cohort of demo run `20260226-03114337` is visible in the chart as a diverging pair
of lines.

### 4. Death-lifetime percentiles in vital_stats

`vital_stats` gains `death_lifetime_p10`, `death_lifetime_p50`, `death_lifetime_p90`: percentiles
of the lifetime of organisms that died in the window. A futile-forking episode collapses these
onto one constant value, which is the reliable detector; no viable-birth lookahead is attempted.
*Accepted when* the forker episodes of run `20260826-17221710` (p50 = 248 from 54 M to 68 M) are
visible in the exported data.

### 5. JSON format for the environment endpoint

`/visualizer/api/environment/{tick}` accepts `format=json` and returns the same cells as JSON
with explicit fields (no protobuf default-value omission, `molecule_type` as plain type id).
The protobuf answer stays the default. *Accepted when* body forensics works without `protoc`.

### 6. Fix the generation-depth restart reset

Issue #112, tracked there; listed here because every analysis currently has to treat the metric
as unusable.

### Deferred

A body endpoint (`/organisms/{tick}/{id}/body`: owned cells in organism-relative coordinates,
optionally DATA-filtered and label-normalized) would reduce body forensics to one call per
organism. Deferred until the second experiment record (the SSD run) is published, since the
existing forensics scripts cover current needs.

## Skill maintenance

The `analyze-run` skill (`.claude/skills/analyze-run/`) encodes today's workarounds. **Every item
above that lands must update the skill in the same PR**, replacing the workaround it obsoletes:

- Item 1 → sweep detection reads `genome_lineage` Parquet; organism snapshots are no longer
  needed for the tree (the node remains necessary only for body forensics).
- Item 2 → step one of sweep detection becomes "look at the clade_shares chart/table"; the manual
  clade computation moves to the fallback path for old runs.
- Item 3 → drop the `bodied` derivation from `genome_data`.
- Item 4 → the futile-forker check becomes "look at death_lifetime_p50"; the snapshot-based
  lifetime histogram moves to the fallback path.
- Item 5 → drop the `protoc` instructions and the tolerant text parser.

Old runs predating these exports keep working through the skill's fallback paths; the skill marks
them as such.

The skill's `scripts/` directory is temporary tooling on volatile interfaces and is admitted to
the repository only for the duration of this proposal: items 1–5 replace its responsibilities one
by one, and the final item to land **deletes the directory entirely** — the skill then consists of
the method text plus queries against the stable exports.
