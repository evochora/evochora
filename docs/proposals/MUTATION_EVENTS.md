# Mutation Events

**Status: TO BE REVIEWED**

Related issues: #135 (mutation marks for the visualizer), #119 (execution coverage). This proposal
records the ground truth both of them need and changes neither of them.

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

A mutation plugin records what it wrote as an event on the newborn. The event is persisted once,
with the newborn's first recorded state, and then dropped from memory — the path the death tick
already takes. Analytics reads it into a Parquet table; the visualizer can read it from the same
recording later.

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

### The contract

`tickdata_contracts.proto` gains

```
message MutationEvent {
  string plugin_class = 1;
  string kind = 2;
  int32 dimensions = 3;
  repeated sint32 cell_coordinates = 4 [packed = true];  // dimensions × cells, relative
  repeated int32 old_values = 5 [packed = true];
  repeated int32 new_values = 6 [packed = true];
  repeated int64 params = 7 [packed = true];
}
```

and `OrganismState` gains `repeated MutationEvent birth_mutations = 39;`, present only in the
first recording after the organism's birth.

Coordinates are relative to the organism's `initial_position`, along the shortest toroidal path,
with the same rule the `GenomeHasher` applies (a difference of exactly half the world size is
canonicalized to the positive side). This is the convention the body endpoint and the visualizer
already use, so a consumer computes the absolute cell as `initial_position + relative`, modulo the
world shape, and nothing else.

### Serialization

`OrganismStateSerializer` converts each record: flat index to coordinate, coordinate to relative
offset. The offset rule becomes a static helper on `EnvironmentProperties`, documented as the rule
the `GenomeHasher` applies; the `GenomeHasher` itself is not changed, so no genome hash changes.
The serializer receives the `EnvironmentProperties` in its constructor.

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

### Analytics

A new plugin `MutationEventsPlugin` under `services/analytics/plugins`, reading every recording
(fixed sampling interval 1, like `DeathLifetimesPlugin`, because an event appears in exactly one
recording) and writing level of detail 0 only (like `GenomeLineagePlugin`). One row per changed
molecule:

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
| `position` | VARCHAR | relative coordinate as text, components joined by `|` |
| `old_value` | INTEGER | molecule int before |
| `new_value` | INTEGER | molecule int after |

One row per molecule mirrors the chunk without leaving anything out. The event view is a grouping
over `organism_id, event_index`: smallest position, number of rows, class and kind. Mutation class
against fate, fixation and clade is a join against `genome_lineage` and `genome_population` on
the genome hashes.

No manifest entry, so no chart in the Analyzer.

The plugin is registered in `reference.conf` under `analytics-indexer-1` and mirrored in
`config/evochora.conf` beside the other metrics.

### Memory estimates

Both new holders of data declare their worst case:

- `SimulationEngine`: pending records, `maxOrganisms × 500 bytes` — every living organism born in
  the current window and carrying one duplication-sized event. Conservative in the same way as the
  24 KB per organism.
- `MutationEventsPlugin`: rows per recording, in the style of `GenomeLineagePlugin`.

### Out of scope

Visualizer display, database columns, per-cell marks in the environment (#135), execution
coverage (#119). The indexers pass the new field through inside the `organism_ticks` blob of the
first recording and are otherwise unchanged. A later display finds an organism's events in that
one recording: the first recorded tick at or after `birth_tick`.

## Cost

| | Amount | Basis |
|---|---|---|
| Tick hot path | none | only birth handlers are touched, and they run per newborn |
| Birth handler | one record per applied mutation; the cell list is filled inside the loop that writes the cells | the plugins already hold every value |
| Heap | records since the last recording | about 50 KB for 1,000 births per window at 0.3 mutations per birth; estimate |
| Chunk storage per event | about 60 bytes fixed plus about 10 bytes per cell; a median duplication of 17 cells about 230 bytes, a substitution about 70 bytes | estimate; the class name repeats and compresses away in the chunk |
| Run `20260902` | about 5,000 events, under 1 MB against 3.8 GB of chunks; about 80,000 Parquet rows | counted in the analysis of that run |
| A run with millions of births | one to a few hundred MB of chunk data at 0.3 events per birth, about one percent | estimate |

## Implementation

Every step in its own commit on the branch `mutation-events`.

1. **Runtime model.** `runtime/model/MutationRecord.java`; `Organism`: field and three methods;
   `Simulation.clearBirthMutationRecords()`; `EnvironmentProperties`: relative-offset helper.
   Tests: `MutationRecordTest`, additions to the organism and environment-properties tests
   (record, read, clear; wrap-around; half world size).
2. **Contract and serialization.** `MutationEvent` and field 39; `OrganismStateSerializer` with
   `EnvironmentProperties` and the conversion; `SimulationEngine`: serializer construction,
   clearing after the prune, memory estimate. Tests: a serializer test for relative coordinates,
   wrap-around and the empty case; `ResumeNeutralityTest` unchanged and green.
3. **Plugins.** The four recording sites beside the existing debug logs. Tests: one case per plugin
   test that checks cells, old and new values against what was written, and that a no-op records
   nothing.
4. **Analytics.** `MutationEventsPlugin`, its test after the pattern of `DeathLifetimesPluginTest`,
   the entries in `reference.conf` and `config/evochora.conf`.
5. **Documentation.** The `analyze-run` skill: the table `mutation_events` in the genome layer, the
   join, and the rule that a changed hash without an event is variation outside the plugins.
6. **Gate.** `./gradlew check`.
7. **Sight check** on a short run: event count against births times rates, one duplication opened
   in DuckDB and compared with the body. The run is proposed separately with duration and data
   directory.
