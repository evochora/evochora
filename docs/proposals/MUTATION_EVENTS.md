# Mutation Events

**Status: TO BE REVIEWED**

Related issues: #135 (mutation marks for the visualizer), #119 (execution coverage). This proposal
records the ground truth #135 asks for and carries it to the visualizer; it does not touch #119.

## Problem

The mutation plugins know exactly what they do to a newborn — which cells they write, what stood
there before, what stands there now — and forget it the moment they return. What reaches the
persisted data is the result: a genome hash that differs from the parent's. Everything about the
mutation itself has to be reconstructed afterwards by diffing a child's body against its parent's,
which needs both bodies to be recorded, produces heuristics instead of facts, and cannot tell a
plugin mutation from any other change of the body.

Two findings from the analysis of run `20260902-15025884` show what that costs. First, mutation
classes could only be compared by fate after a body diff over 12,000 genomes, with an estimated
error of the classification itself in every table. Second, half of all births whose genome differed
from the parent's came from no plugin at all — systematically defective copies, cells lost to
another organism's writes — and this channel was found by accident, as the remainder the plugins
could not explain. With the mutations themselves recorded, that remainder is a subtraction.

The visualizer has the same gap from the other side (#135): it cannot show which molecules of an
organism a mutation wrote, because nothing persisted says so.

## Solution

A mutation plugin records what it wrote as an event on the newborn. The engine persists the event
once, with the newborn's first recorded state, and drops it from memory — the path the death tick
already takes. The indexers turn it into a column of the organism table and a Parquet table; the
visualizer marks the cells in the environment grid, along the whole lineage of the selected
organism.

The engine computes nothing for the consumers: the record and the persisted event carry the cells
as the plugins hold them, absolute flat indices. Every conversion happens in an indexer.

### The record

A runtime value type `org.evochora.runtime.model.MutationRecord`, immutable, with a builder over
fastutil primitive lists so that the plugins' write loops do not box:

| Field | Type | Meaning |
|---|---|---|
| `pluginClass` | `String` | fully qualified class name of the reporting plugin, taken from `getClass().getName()`; the same convention as `PluginState.plugin_class` |
| `kind` | `String` | a short name chosen by the plugin; the core does not interpret it |
| `cells` | `int[]` | absolute flat indices of the cells the plugin wrote or cleared |
| `oldValues` | `int[]` | the molecule int at each cell before the write, parallel to `cells` |
| `newValues` | `int[]` | the molecule int at each cell after the write, parallel to `cells` |
| `params` | `long[]` | numbers with plugin-defined meaning, documented by the plugin |

The core carries no list of mutation kinds and no per-plugin logic. A new plugin records itself by
calling the same method and is complete: analytics groups by class name, the visualizer marks the
cells it names.

### Where the record lives

`Organism` gains `birthMutations`, a list that stays `null` until the first record arrives, with
`recordBirthMutation(MutationRecord)`, `getBirthMutations()` and `clearBirthMutations()`. The field
is observation data on the organism, like `deathTick` and `parentGenomeHash`, for the same reason:
the event belongs to the individual and is read out with its state.

`Simulation.clearBirthMutationRecords()` clears the lists of all organisms. `SimulationEngine`
calls it in `captureSampledTick` right after `pruneDeadOrganisms()`, so the engine, not the
serializer, changes the runtime state, and the serializer remains an observer. Dead newborns are
retained until they have been recorded, so a record is never lost, not even for a child that dies
248 ticks after birth.

Resume needs nothing. Every checkpoint is a recording, and at a recording the lists are emitted and
cleared in the same capture, so a resumed run and an uninterrupted run carry identical state.

The plugins do not touch the random source for this, so the trajectory of a run does not change.

Recording once rather than in every state was weighed against the alternative. Keeping the events
in every `OrganismState` would cost about 7 % of the organism bytes in every chunk and in
`organism_ticks` for the lifetime of every mutated organism, 2 to 7 % of the organism
serialization on every capture, and either a threefold write traffic on the static organism table
or the same first-appearance rule the once-only design needs anyway. What it would buy is a
redundancy no consumer needs, because no indexer skips a recording.

### The contract

`tickdata_contracts.proto` gains

```
message MutationEvent {
  string plugin_class = 1;
  string kind = 2;
  repeated int32 cells = 3 [packed = true];       // absolute flat indices
  repeated int32 old_values = 4 [packed = true];
  repeated int32 new_values = 5 [packed = true];
  repeated int64 params = 6 [packed = true];
}
```

and `OrganismState` gains `repeated MutationEvent birth_mutations = 39;`, present only in the
first recording after the organism's birth. `OrganismStateSerializer` copies the record into it.

For the organism table the same file gains the stored form, with the coordinates an indexer has
computed:

```
message StoredMutationEvent {
  string plugin_class = 1;
  string kind = 2;
  int32 dimensions = 3;
  repeated sint32 relative_coordinates = 4 [packed = true];  // dimensions × cells
  repeated int32 old_values = 5 [packed = true];
  repeated int32 new_values = 6 [packed = true];
  repeated int64 params = 7 [packed = true];
}

message StoredMutationEvents {
  repeated StoredMutationEvent events = 1;
}
```

### Coordinates

Consumers convert a flat index to a coordinate with the world shape from the run metadata, and to
an offset relative to the organism's `initial_position` along the shortest toroidal path, with the
rule the `GenomeHasher` applies (a difference of exactly half the world size is canonicalized to
the positive side). The rule becomes a static helper on `EnvironmentProperties`, documented as the
`GenomeHasher`'s rule; the `GenomeHasher` itself is not changed, so no genome hash changes.

Relative coordinates are what the lineage display needs: a mutation an ancestor received at a
cell sits at the same offset from every descendant's origin, so the events of a whole lineage can
be laid over the displayed body by adding its `initial_position`.

### What the plugins record

| Plugin | `kind` | `cells` | `oldValues` / `newValues` | `params` |
|---|---|---|---|---|
| `GeneDuplicationPlugin` | `duplication` | the target cells that received a non-empty molecule | 0 / the copied molecule | flat index of the first source cell |
| `GeneDeletionPlugin` | `deletion` | the label cell and every cleared cell | the removed molecule / 0 | occurrence count of the label hash in the genome |
| `GeneInsertionPlugin` | `insertion` or `label-insertion` | the cells of the placed chain | 0 / the placed molecule | none |
| `GeneSubstitutionPlugin` | `substitution` | the one cell | old molecule / new molecule | none |

`LabelRewritePlugin` records nothing: it changes every label and reference by the same mask, the
genome hash normalizes that away, and it is not a mutation. A plugin that decides to do nothing —
no NOP run large enough, a substitution whose new value equals the old — records nothing either.
A newborn whose genome hash differs from the parent's and that carries no event therefore changed
by something other than a mutation plugin.

`LabelRewritePlugin` runs after the mutation plugins, so the `newValues` of a LABEL or LABELREF
cell hold the value before the newborn's mask was applied, and every descendant applies a mask of
its own. See the label rule under the display below.

### Organism indexer

`AbstractH2OrgStorageStrategy` — the base of `SingleBlobOrgStrategy` and
`RowPerOrganismStrategy`, so both strategies get it — gains a column `birth_mutations BYTEA NULL`
in `organisms`, holding a `StoredMutationEvents` message, and a second prepared statement

```
MERGE INTO organisms (organism_id, birth_mutations) KEY (organism_id) VALUES (?, ?)
```

batched only for states that carry events and executed after the main organism batch of the same
commit window, so the row exists. An H2 `MERGE ... KEY` writes the named columns only, so the main
statement, which does not name the column, never clears it in later windows. The indexer converts
the flat indices to relative coordinates here, with the organism's `initial_position` from the
same state and the world shape from the run metadata.

Batches reach the organism indexers in arbitrary order across competing consumers. Nothing here
depends on the order: the statement touches only the organism's own row, and the lineage is walked
at request time, as `readLineage` does today.

### Analytics

A new plugin `MutationEventsPlugin` under `services/analytics/plugins`, reading every recording
(fixed sampling interval 1, like `DeathLifetimesPlugin`, because an event appears in exactly one
recording) and writing level of detail 0 only (like `GenomeLineagePlugin`). The presence of the
field is the signal; no deduplication is needed. One row per changed molecule:

| Column | Type | Content |
|---|---|---|
| `tick` | BIGINT | the recording |
| `birth_tick` | BIGINT | the newborn's birth |
| `organism_id` | INTEGER | the newborn |
| `parent_id` | INTEGER | its parent |
| `genome_hash` | BIGINT | the newborn's genome |
| `parent_genome_hash` | BIGINT | the parent's genome at the birth |
| `event_index` | INTEGER | ordinal of the event within this birth, in plugin order |
| `plugin_class` | VARCHAR | the reporting plugin |
| `kind` | VARCHAR | the plugin's kind |
| `position` | VARCHAR | relative coordinate as text, components joined by `\|` |
| `old_value` | INTEGER | molecule int before |
| `new_value` | INTEGER | molecule int after |

One row per molecule mirrors the chunk without leaving anything out. The event view is a grouping
over `organism_id, event_index`: smallest position, number of rows, class and kind. Mutation class
against fate, fixation and clade is a join against `genome_lineage` and `genome_population` on
the genome hashes.

The plugin is registered in `reference.conf` under `analytics-indexer-1` and mirrored in
`config/evochora.conf` beside the other metrics. It carries no manifest entry of its own; the two
charts below read it.

### Two charts in the Analyzer

**The cause on the clade band.** The Clade Shares chart shows a sweep as a rising band but not
what started it. Every genome has its founding mutation in the events of its first carrier, so
the chart names it in the band's legend and tooltip: plugin, kind, number of cells and the first
position — or "no plugin event", which is the copy channel. The browser's DuckDB build does not
survive a hash aggregation over an unsorted column beyond a few thousand rows (see
`GenomePopulationPlugin`), so the chart must not group `mutation_events` by genome hash. The
plugin therefore writes a second, small table `mutation_summary` with one row per event —
`tick, birth_tick, organism_id, genome_hash, parent_genome_hash, event_index, plugin_class, kind,
cell_count, position` (the smallest position) — and the chart filters that table by the band's
genome hash, a scan of a few thousand rows. `GenomePopulationPlugin.getManifestEntry` gains
`mutation_summary` as a second companion metric with that query; the manifest and the chart
accept a list of companions instead of one.

**Variation sources per birth.** A second analytics plugin `VariationSourcesPlugin`, metric
`variation_sources`, reading every recording like the events plugin. A newborn is a state whose
`birth_tick` lies after the previous recording, the interval taken from the analytics context;
dead newborns are in the recording too. One row per recording and source with the number of
births: source is `unchanged` (genome hash equals the parent's), `bodiless` (genome hash 0),
`no-event` (hash differs, no event), or the kinds of the birth's events joined by `+` in plugin
order, so that a birth counts exactly once and the shares of a recording sum to one. The manifest
entry is a stacked area chart of the shares over ticks, like Clade Shares. It shows the copy
channel and its episodes directly, and whether mutator lineages gain ground over a run.

What is not a chart: mutation class against fate. That needs the newborn's future and stays a
notebook question over the join with `genome_lineage`.

### Controller

A new route `GET /visualizer/api/organisms/{tick}/{organismId}/mutations?runId=...` answers with
the events of the organism's whole lineage: for the organism and each ancestor along `parent_id`,
every stored event with

- the origin: `organismId`, `generation` and `genomeHash` of the organism that received the
  mutation,
- `pluginClass`, `kind`, `params`,
- the cells as absolute coordinates on the displayed body (`initial_position` of the displayed
  organism plus the relative offset, modulo the world shape), with `oldValue` and `newValue`.

The reader walks the parent chain the way `readLineage` does, one primary-key lookup per
ancestor; the column is read from `organisms` like the genome hash. The route is called when an
organism is selected, not per tick.

### Display in the environment grid

Only for the selected organism, and only in the environment grid; the organism panel does not
change, and the minimap does not change.

- In the detailed renderer a mutated cell gets a thin border in the lineage colour of the genome
  the mutation arose in — `AppController._genomeHashToLineageColor` of the event's origin genome
  hash, which already exists.
- In the zoomed-out renderer, where a border cannot be drawn at one to four pixels per cell, the
  cell area is filled with that colour.

Three rules decide which cells are marked. They are applied in the frontend, where the molecule
of every visible cell is at hand, and they must be documented in the code beside the comparison:

1. A cell is marked only while it still holds what the mutation wrote: its molecule equals the
   event's `newValue`. A cell the organism itself has since overwritten with a different molecule
   carries no mark. A deletion's `newValue` is empty, so the cleared cell is marked while it stays
   empty. Marker bits are not compared; they belong to the organism, not to the molecule.
2. Where two events of the lineage touch the same cell, the younger one decides: the event of the
   more recent generation.
3. **Label rule.** For LABEL and LABELREF cells only the molecule type is compared, never the
   value. `LabelRewritePlugin` masks every label of a newborn after the mutation plugins have run,
   and every descendant is masked again, so the stored `newValue` of a label cell never equals the
   value in any body. Comparing values would hide every label mutation as "overwritten". The price
   is that a label the organism later replaced by another label keeps its mark; organisms do not
   rewrite labels at run time, so the price is small. This exception is documented in the
   comparison code and in the controller.

### Memory estimates

Both holders of data declare their worst case. With the default density factor the engine's
`maxOrganisms` is `totalCells × 0.0003`, about 10,000 organisms for a 7680 × 4320 world.

- `SimulationEngine`: pending records, `maxOrganisms × 500 bytes` — every living organism born
  since the last recording and carrying one duplication-sized event; about 5 MB at the default
  density, conservative in the same way as the 24 KB per organism.
- `MutationEventsPlugin`: rows per recording, in the style of `GenomeLineagePlugin`.

### Out of scope

Per-cell marks in the environment (#135's storage form: the events are the ground truth such marks
could be derived from), execution coverage (#119), and the hash-0 births of body-less children,
which carry no genome and therefore no mutation.

## Cost

| | Amount | Basis |
|---|---|---|
| Tick hot path | none | only birth handlers are touched, and they run per newborn |
| Birth handler | one record per applied mutation; the cell list is filled inside the loop that writes the cells | the plugins already hold every value |
| Engine heap | records since the last recording, about 100 KB for 1,000 births per window at 0.3 events per birth; none in the detailed profile | estimate; the 240 MB the engine estimates for 10,000 organisms are unaffected |
| Chunk storage | about 50 bytes fixed plus about 8 bytes per cell per event; a median duplication of 17 cells about 190 bytes; run `20260902`: about 5,000 events, under 1 MB against 3.8 GB; under 0.1 % in every profile | counted in the analysis of that run |
| Organism table | one BLOB of about 200 bytes per mutated organism, written once | |
| Parquet | about 80,000 rows for run `20260902`; one to a few million rows for a run with millions of births | |
| Lineage request | one primary-key lookup per ancestor; a few milliseconds at generation depth 200, under 200 ms at depth 3,000 | estimate from H2 lookup times; per selection, not per tick |

## Implementation

Every step in its own commit on the branch `mutation-events`.

1. **Runtime model.** `runtime/model/MutationRecord.java`; `Organism`: field and three methods;
   `Simulation.clearBirthMutationRecords()`; `EnvironmentProperties`: relative-offset helper.
   Tests: `MutationRecordTest`, additions to the organism and environment-properties tests
   (record, read, clear; wrap-around; half world size).
2. **Contract and serialization.** `MutationEvent`, `StoredMutationEvent`, `StoredMutationEvents`
   and field 39; `OrganismStateSerializer` copies the record; `SimulationEngine`: clearing after
   the prune, memory estimate. Tests: a serializer test for the copy and the empty case;
   `ResumeNeutralityTest` unchanged and green.
3. **Plugins.** The four recording sites beside the existing debug logs. Tests: one case per plugin
   test that checks cells, old and new values against what was written, and that a no-op records
   nothing.
4. **Analytics.** `MutationEventsPlugin` with both tables, its test after the pattern of
   `DeathLifetimesPluginTest`, the entries in `reference.conf` and `config/evochora.conf`.
   4a. The second companion on the Clade Shares manifest and its display in the chart.
   4b. `VariationSourcesPlugin`, its test, its manifest entry and its config entries.
5. **Organism indexer.** The column, the second statement and the conversion in
   `AbstractH2OrgStorageStrategy`. Tests: both strategy tests write a state with events and read
   the column back; a later window without events leaves it untouched.
6. **Controller.** The route, the reader method walking the lineage, the DTOs and the JSON.
   Tests: reader and controller.
7. **Frontend.** The border and fill in the two renderers, the three rules with their
   documentation, the fetch on selection. The concrete look is agreed with the maintainer before
   this step.
8. **Documentation.** The `analyze-run` skill: the table `mutation_events` in the genome layer, the
   join, and the rule that a changed hash without an event is variation outside the plugins.
9. **Gate.** `./gradlew check`.
10. **Sight check** on a short run: event count against births times rates, one duplication opened
    in DuckDB and compared with the body, the marks of one lineage in the visualizer. The run is
    proposed separately with duration and data directory.
