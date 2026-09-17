# Analytics Plugin State Across Indexer Restarts

**Status: CONVERTED TO ISSUE (2026-08-23) — not scheduled. The problem is verified and tracked in
[#112](https://github.com/evochora/evochora/issues/112); the solution sketched below was never agreed.
Analysis of runs happens in notebooks and scripts, the analytics frontend serves only as a quick
overview, and its weaknesses are to be decided together when the frontend is taken up.**

## Problem

Analytics plugins are stream processors: the `AnalyticsIndexer` feeds them one `TickData` after
another, and each plugin emits Parquet rows. Two plugins additionally keep **state that spans
ticks**, held only in JVM memory:

| Plugin | Cross-tick state | What it is for |
|---|---|---|
| `GenerationDepthPlugin` | `depthMap`: organism id → generation depth | depth(child) = depth(parent) + 1; the parent's depth must be remembered from earlier ticks |
| `GenomeAnalyticsPlugin` | `cumulativePopulation`, `trackedGenomeSet`, `labelCache` | cumulative per-genome counts used to rank the top-N genomes consistently over time |

The other five plugins (`Population`, `VitalStats`, `AgeDistribution`, `InstructionUsage`,
`EnvironmentComposition`) compute each row from the current tick alone and are not affected.

When the indexer process restarts, it resumes reading the batch stream where it left off — but the
plugins start with **empty state** (`initialize()` creates fresh maps). The output continues
seamlessly, with silently wrong values:

- `GenerationDepthPlugin` re-derives every living organism's depth as 0 or 1 (parent unknown →
  default 0) and the depth grows again from there. Verified in run `20260402-…`: `max_depth`
  collapses from three-digit values to single digits at ticks ≈70 M, ≈110 M and ≈175 M — exactly
  the indexer restarts — and climbs falsely afterwards
  (documented in the published record of that run, see
  [docs/PUBLISHED_EXPERIMENTS.md](../../../PUBLISHED_EXPERIMENTS.md)).
- `GenomeAnalyticsPlugin` restarts its cumulative ranking from zero; how visibly this distorts the
  top-N selection depends on the population at restart time and has not been quantified.

Indexer restarts are normal operation, not an exceptional event — the run above saw three of them
during ordinary indexing. A defect of the class "silently wrong data after a routine operational
event" violates the no-silent-failure rule and cannot be detected from the output alone.

### Remediation for existing runs

No code is needed to repair an affected run's data: deleting the analytics output of the affected
metric and re-indexing it from the first batch rebuilds the state correctly, because the defect
only occurs when processing *resumes mid-stream*. This is an operational workaround, not a fix —
it costs a full re-read of the raw stream and does not survive the next restart.

## Proposed solution (to be discussed)

The idea in one sentence: **when the indexer records how far it has processed, it also records what
the stateful plugins currently remember — and on restart it gives them that memory back.**

Concretely:

1. `IAnalyticsPlugin` gains `byte[] saveState()` and `loadState(byte[])`, default-implemented as
   empty (stateless plugins ignore the feature). This mirrors, deliberately and by the same
   contract, the mechanism the runtime already uses for its plugins — `ITickPlugin.saveState()` /
   `loadState()`, implemented for example by `GeyserCreator` to survive a simulation resume.
2. The `AnalyticsIndexer` persists each plugin's state bytes **atomically together with its
   progress marker** (the position in the batch stream up to which output exists). Atomic means:
   state and progress are written as one unit, so they can never disagree about which tick the
   state describes. On restart, the indexer calls `loadState` before feeding the first resumed
   tick.
3. The stored state is a persisted artifact and therefore falls under
   [PERSISTED_FORMAT_VERSIONING](../../../proposals/PERSISTED_FORMAT_VERSIONING.md): it carries the format version and
   is rejected fail-fast on mismatch (the safe reaction to a rejected state is the re-index of the
   remediation note above).

Why this shape is proposed rather than a per-plugin repair: it fixes the *class* (any future
stateful plugin included) with one mechanism the project already has a precedent for, instead of
fixing `GenerationDepthPlugin` alone.

## Open questions for the design discussion

- Where exactly the state lives (alongside the Parquet output of the metric, or with the indexer's
  progress bookkeeping) and how atomicity with the progress marker is guaranteed in the current
  storage layout.
- Whether `GenerationDepthPlugin` should instead reconstruct its map on startup from recorded
  organism metadata (parent chains) — exact and stateless, but a per-plugin solution that requires
  giving analytics plugins access to data they currently do not have, and that does not cover the
  genome ranking state.
- State size bounds: `depthMap` is O(alive organisms), the genome maps are O(genomes ever tracked);
  whether the checkpoint cadence needs a cap or compaction.
- Whether plugin state should be included in the plugins' existing `estimateWorstCaseMemory`
  accounting.

## Acceptance test (holds for any chosen mechanism)

Index a stream of N ticks twice: once in a single pass, once interrupted and restarted at several
positions mid-stream. The Parquet output of every metric must be byte-identical between the two
runs. This test is what defines the defect and must be written first; today it fails for
`generation_depth` and is expected to fail for the genome ranking.
