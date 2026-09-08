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
| `dv` | `int[]` | the newborn's direction vector when the plugin ran, from `child.getDv()`; the duplication, deletion and insertion plugins choose scan line and walk direction by it |
| `params` | `long[]` | numbers with plugin-defined meaning, documented by the plugin |

`cells` are listed in the order the plugin wrote them, which is its walk along `dv`.

The core carries no list of mutation kinds and no per-plugin logic. A new plugin records itself by
calling the same method and is complete: the analytics tables and charts group by `kind`, carry
`plugin_class` beside it as the provenance, and the visualizer marks the cells the event names.
A plugin is responsible for choosing `kind` names that no other plugin uses; the documentation
of `recordBirthMutation` says so.

### Where the record lives

`Organism` gains `birthMutations`, a list that stays `null` until the first record arrives, with
`recordBirthMutation(MutationRecord)`, `getBirthMutations()` and `clearBirthMutations()`. The field
is observation data on the organism, like `deathTick` and `parentGenomeHash`, for the same reason:
the event belongs to the individual and is read out with its state.

`Simulation` keeps the newborns that carry a record in a small list, filled after the birth
handlers of a tick have run (one null check per newborn), and
`Simulation.clearBirthMutationRecords()` clears exactly those, so the clear costs the number of
pending records, not the population, and the engine's memory figure counts what is actually
held. `SimulationEngine` calls it in `captureSampledTick` right after
`chunkEncoder.captureTick` has taken the states of the recording, so the engine, not the
serializer, changes the runtime state, and the serializer remains an observer. Dead organisms stay in the list until the capture
that records them, so a newborn that is born and dies between two recordings is serialized with
its events, as a dead state, before anything is cleared. A test in step 2 covers exactly this
case.

The unit the engine sends is a chunk, not a recording: `tickDataOutput.put` runs only when the
encoder has completed a chunk, which is `accumulatedDeltaInterval × snapshotInterval ×
chunkInterval` recordings. Clearing the records only after that send was considered and
rejected: the records would reappear in every recording of the chunk, and the pending list
would keep the organisms of the whole chunk alive, dead ones included. The capture loop logs a
failed send as `SEND_ERROR` and continues; a chunk that fails to send, or is lost afterwards in
the dead-letter queue, loses its events with every other field of every recording in it. That
is accepted, and it is the exposure every field already has.

Resume restores nothing here. Every checkpoint is a recording, at which the lists were emitted and
then cleared; `SimulationRestorer` rebuilds each organism field by field through `RestoreBuilder`
and does not read `birth_mutations`, so a restored organism carries no record, the same state the
uninterrupted run had after that capture. A test restores from a snapshot that carries events and
checks that the organism has no records.

The plugins do not touch the random source for this, so the trajectory of a run does not change.

Recording once rather than in every state was weighed against the alternative. Keeping the events
in every `OrganismState` would cost, by estimate, about 7 % of the organism bytes in every chunk
and in `organism_ticks` for the lifetime of every mutated organism, 2 to 7 % of the organism
serialization on every capture, and either roughly threefold write traffic on the static
organism table or the same first-appearance rule the once-only design needs anyway. What it would buy is a
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
  repeated sint32 dv = 7 [packed = true];        // the newborn's direction vector at birth
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
  repeated sint32 relative_coordinates = 3 [packed = true];  // dimensions × cells
  repeated int32 old_values = 4 [packed = true];
  repeated int32 new_values = 5 [packed = true];
  repeated int64 params = 6 [packed = true];
  repeated sint32 dv = 7 [packed = true];
}

message StoredMutationEvents {
  int32 dimensions = 1;
  repeated StoredMutationEvent events = 2;
}
```

### Coordinates

Consumers convert a flat index to a coordinate with the world shape from the run metadata, and to
an offset relative to the organism's `initial_position` along the shortest toroidal path.

That offset rule exists twice today, with opposite tie-breaks for a difference of exactly half
an even world size: `GenomeHasher.computeGenomeHash` canonicalizes `-size/2` to `+size/2`,
`EnvironmentProperties.getRelativeVector` (used by the body endpoint) maps `+size/2` to
`-size/2`. One rule, the `GenomeHasher`'s, becomes a static helper on `EnvironmentProperties`;
`GenomeHasher` and `getRelativeVector` both call it. The arithmetic of the hasher is unchanged,
so no genome hash changes, which the resume and determinism tests confirm; the body endpoint
differs only for a body spanning exactly half the world; the assertion in
`EnvironmentPropertiesTest` that pins the old tie-break (`x < 50` at width 100) becomes
`x <= 50`.

Relative coordinates are what the lineage display needs: a mutation an ancestor received at a
cell sits at the same offset from every descendant's origin, so the events of a whole lineage can
be laid over the displayed body by adding its `initial_position`.

### What the plugins record

| Plugin | `kind` | `cells` | `oldValues` / `newValues` | `params` |
|---|---|---|---|---|
| `GeneDuplicationPlugin` | `duplication` | the target cells that received a non-empty molecule | 0 / the copied molecule | flat index of the first source cell |
| `GeneDeletionPlugin` | `deletion` | the label cell and every cleared cell | the removed molecule / 0 | occurrence count of the label hash in the genome |
| `GeneInsertionPlugin`, instruction entry | `insertion` | the cells of the placed chain | 0 / the placed molecule | none |
| `GeneInsertionPlugin`, label entry | `label-insertion` | the one label cell | 0 / the placed label | the label hash the new one was derived from, before the bit flips |
| `GeneSubstitutionPlugin` | `substitution` | the one cell | old molecule / new molecule | none |
| `LabelRewritePlugin` | `label-rewrite` | none | none | the XOR mask |

`params` keep three things that are lost otherwise. The duplication's source position: from the
copied values alone the source can only be searched for, and ambiguously. The deletion's label
count: the weight that made the deletion choose this label, no longer readable from the child
because the label is gone. The label insertion's source hash: the new label is a sampled
existing label with bits flipped, and which one is as unrecoverable by search as the
duplication's source.

`LabelRewritePlugin` records its mask as an event of its own: `kind = "label-rewrite"`, no
cells, `params = [mask]`. It is not a mutation — it changes every label and reference by the
same mask and the genome hash normalizes that away — but the mask is what a consumer needs to
compare a label value across generations: every newborn's labels are masked once, and every
descendant's again, so the `newValues` a mutation plugin recorded for a LABEL or LABELREF cell
never equal the value in any later body. An event without cells changes nothing; that is the
generic rule by which the analytics leave it out of the per-molecule table and out of the
variation sources, without naming the plugin.

A plugin that decides to do nothing — no NOP run large enough, a substitution whose new value
equals the old — records nothing. A newborn whose genome hash differs from the parent's and that
carries no event with cells therefore changed by something other than a mutation plugin.

### Organism indexer

The `organisms` table is today defined twice: `SingleBlobOrgStrategy` and
`RowPerOrganismStrategy` each hold an identical copy of its DDL and of the static MERGE
statement, while their base `AbstractH2OrgStorageStrategy` owns only the parameter binding.
Before the column is added, the DDL and the statement move into the base, next to
`createTickStatsTable`, so that the table has one definition; the two strategy tests stay
unchanged and green across that move. The base then gains the column
`birth_mutations BYTEA NULL` in `organisms`, holding a `StoredMutationEvents` message, and a
second prepared statement

```
MERGE INTO organisms (organism_id, birth_mutations) KEY (organism_id) VALUES (?, ?)
```

batched only for states that carry events.

Four things about the two statements:

- **Order.** The main statement's batch executes first, the event statement's batch after it,
  in the same commit window. A MERGE inserts the row when it does not exist yet; the event
  statement run first would insert a row with only `organism_id` and `birth_mutations` set, and
  `organisms` has `NOT NULL` columns (`birth_tick`, `program_id`, `initial_position`) on which
  that insert fails. The strategy test of step 5 covers the order.
- **Later windows.** The main statement runs again for every living organism in every commit
  window, while the event stands in the data once. An H2 `MERGE ... KEY` writes the named
  columns only and leaves the others as they are, and the main statement does not name the
  column, so the value written once is never cleared.
- **Competing consumers.** Both statements for one organism come from the same `OrganismState`
  of the same recording, and a recording lies in exactly one batch, which exactly one consumer of
  the group processes. The pair therefore always runs in one indexer, one session, one commit
  window. The other indexer sees the organism only in later recordings, without events, and runs
  only the main statement for it; whether that happens before or after the pair, the table ends
  the same, because the main statement writes the same static values and leaves the column
  alone. Two indexers merging the same row at once is what the static columns already do today;
  H2 serializes it on the row lock, and the MERGE is idempotent.
- **The single-statement alternative**, one `MERGE ... USING ... WHEN MATCHED THEN UPDATE SET
  birth_mutations = COALESCE(?, organisms.birth_mutations)`, was weighed and not taken: it
  rebuilds the main statement for every organism in every window for a value that a fraction of
  the rows carries once.

The conversion does not happen in the strategy. The strategy is a resource-layer serializer that
knows only its options, a connection and the tick data; it stores bytes. `OrganismIndexer`
derives the `EnvironmentProperties` from the run metadata in `prepareTables`, the way
`EnvironmentIndexer.prepareTables` already does. That derivation exists three times today, in
`EnvironmentIndexer`, `H2DatabaseReader` (with its own JSON parsing) and `EnvironmentController`;
it becomes one factory `MetadataConfigHelper.environmentProperties(metadata)` in
`datapipeline.utils`, next to the shape and topology accessors that already live there, and the
three copies and the organism indexer call it. The indexer converts the flat indices of each event to
relative coordinates with the organism's `initial_position` from the same state, builds the
`StoredMutationEvents` message and hands the strategy its bytes for the column.

Batches reach the organism indexers in arbitrary order across competing consumers. Nothing here
depends on the order: the statement touches only the organism's own row, and the lineage is read
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
| `position` | VARCHAR | relative coordinate as a JSON list, `[13,4]`; DuckDB reads it as a typed list with `position::INTEGER[]` |
| `old_value` | INTEGER | molecule int before |
| `new_value` | INTEGER | molecule int after |

One row per molecule mirrors the chunk without leaving anything out. The event view is a grouping
over `organism_id, event_index`: smallest position, number of rows, class and kind. Mutation class
against fate, fixation and clade is a join against `genome_lineage` and `genome_population` on
the genome hashes.

The plugin is registered in `reference.conf` under `analytics-indexer-1`. `config/evochora.conf`
overrides only the simulation engine and the logging and carries no analytics plugin list, so
there is nothing to mirror there; a HOCON array in that file would replace the whole list rather
than add to it. The plugin carries no manifest entry of its own; the two charts below read it.

### Two charts in the Analyzer

**The cause on the clade band.** The Clade Shares chart shows a sweep as a rising band but not
what started it. Every genome has its founding mutation in the events of its first carrier, so
the chart names it in the band's legend and tooltip: kind, number of cells and the smallest
position — or "no plugin event", which is the copy channel. The plugin stays in the table for the
analyst; on the band it would repeat the kind for every built-in plugin. The browser's DuckDB build does not
survive a hash aggregation over an unsorted column beyond a few thousand rows (see
`GenomePopulationPlugin`), so the chart must not group `mutation_events` by genome hash. A
second, small table `mutation_summary` therefore holds one row per event —
`tick, birth_tick, organism_id, genome_hash, parent_genome_hash, event_index, plugin_class, kind,
cell_count, position` (the smallest position), `dv` and `params` (all three as JSON lists in
text, read in DuckDB with a cast to `INTEGER[]` or `BIGINT[]`) — and the chart filters that table by the band's genome hash, a scan of a few
thousand rows. An analytics plugin has exactly one schema and one metric id
(`IAnalyticsPlugin.getSchema`, `AbstractAnalyticsPlugin.metricId`), so the table is written by a
plugin of its own, `MutationSummaryPlugin`, which reads the same events; the position conversion
it shares with `MutationEventsPlugin` through one helper.

The Clade Shares chart then has two companions, `genome_lineage` and `mutation_summary`.
`ManifestEntry` carries one `companionMetricId` and one `companionQuery` today; they become a
list of companions, each with metric id and query. That definition is mirrored in three places
and all three change together: `ManifestEntry` itself, the manifest JSON the `AnalyticsIndexer`
writes to storage per run at plugin initialization (`writePluginMetadata`) and the
`AnalyticsController` reads back, and `AnalyzerController.js`, where `loadCompanionData` loads
the one companion and the clade chart consumes it. Manifests written by an earlier build are not
read by this one: data is always read with the build that produced it.

**Variation sources per birth.** A second analytics plugin `VariationSourcesPlugin`, metric
`variation_sources`, reading every recording like the events plugin. A newborn is a state with a
parent whose `birth_tick` lies after the previous recording, `tick - interval`, the interval read
with `MetadataConfigHelper.getSamplingInterval` from the metadata the analytics context carries;
dead newborns are in the recording too. Recordings lie on a fixed grid that a pause or resume
does not shift, so the rule needs no state: the first recording of a run sees only founders,
which have no parent and are not births; the first recording after a resume sees exactly the
births since the checkpoint. One row per recording, wide, with one count per source:
`tick, unchanged, bodiless, no_event, duplication, deletion, insertion, label_insertion,
substitution, multiple, other`. A birth counts in exactly one column, so the counts of a row add
up to the births of its recording. The genome decides first — `bodiless` (genome hash 0), then
`unchanged` (genome hash equals the parent's) — and otherwise the kinds of the birth's events
that carry cells: one kind, that kind's column; two or more kinds, `multiple`, a birth two or
more plugins changed; a single kind none of this project's plugins report, `other`, so a plugin
brought from elsewhere gets a band instead of vanishing into one of the five. Where the hash
differs and no event carries cells, `no_event`. An event without cells, such as the label mask,
is not a source. Which kinds met at a birth under `multiple` stays derivable from
`mutation_summary`, which holds every event singly. A recording without births writes no row.

The manifest entry is a stacked bar chart over ticks with the ten counts as its bars, in absolute
births rather than shares: recording is sparse, so a bar stands on a handful of births, where a
share turns single births into a band jumping between halves and thirds — the height of the bar
carries the episode itself, and a burst of the copy channel is a block whatever the population
does around it. Under sparse recording a single recording holds a handful of births at most, so a
bar per recording would show nothing. The chart's query, run in
the browser over the loaded rows the way `InstructionUsagePlugin`'s is, cuts the ticks into fifty
buckets and sums the counts of the recordings in each, so a bar carries the births of a
window. The chart shows the copy channel and its episodes directly, and whether mutator lineages
gain ground over a run.

A level of detail is a sample in this pipeline — the rows of every `lodFactor^k`-th recording —
which is right for a state and wrong for a count: a coarser level would keep every tenth recording
and with it a tenth of the births. `ParquetSchema` therefore carries an aggregation per column,
`SAMPLE` — the default, today's behaviour — or `SUM`, which a column must be an integer type to
declare. For a plugin declaring one, the `AnalyticsIndexer` keeps per level of detail a running sum
over the rows since that level last wrote and writes it where the level's tick falls, together with
the sampled columns of that recording. What a window has accumulated when the batch ends is written
as well, with the tick of the last recording that fed it: batches reach competing indexers in
arbitrary order, so nothing may be carried across one, and a window straddling a batch boundary
yields two rows with partial sums instead of one. The rows of a window are therefore added rather
than one of them picked, which is what the bucketing query does anyway. Level 0 keeps writing the
plugin's row as it comes, and a plugin whose columns are all `SAMPLE` keeps exactly today's path. A
plugin with a `SUM` column that reports more than one row for one recording is refused at that
recording, since the sampled columns of a summed row are those of a single one. The ten counts of
`variation_sources` are declared `SUM`, so the metric has levels of detail like the other metrics,
each of them holding all the births of the run.

What is not a chart: mutation class against fate. That needs the newborn's future and stays a
notebook question over the join with `genome_lineage`.

### Controller

A new route `GET /visualizer/api/organisms/{tick}/{organismId}/mutations?runId=...` answers with
the events of the organism's whole lineage. The answer does not depend on `{tick}`; the segment
keeps the route apart from the two-segment detail route `{tick}/{organismId}` and is otherwise
unused. The answer carries: for the organism and each ancestor along `parent_id`,
every stored event with

- the origin: `organismId`, `generation` and `genomeHash` of the organism that received the
  mutation,
- `pluginClass`, `kind`, `dv`, `params`,
- the cells as absolute coordinates on the displayed body (`initial_position` of the displayed
  organism plus the relative offset, modulo the world shape), each with its molecule before and
  after in the form the environment API uses for cells — `moleculeType` and `moleculeValue`,
  split in the controller — so the grid compares fields and never a bit layout.

For LABEL and LABELREF cells the controller translates the recorded values into the displayed
organism's label namespace before answering. Label rewriting is core behaviour that happens to
be implemented through the plugin interface, so the controller may know its event: walking the
lineage from the origin of an event to the displayed organism, it XORs the masks of the
`label-rewrite` events on the way, and within one birth it applies that birth's mask to an event
only if the mask's `event_index` is the larger one, because such an event was recorded before
the mask. The composition is one integer operation per generation and per label cell on data
the one query already returned. The reason for this translation is documented at the controller
and at the plugin.

The reader extends the recursive query `readLineage` already runs over `organisms` — one
`WITH RECURSIVE` round trip for the whole ancestry — by `birth_mutations` and `generation`, and
by the selected organism itself, which the existing query leaves out because it seeds from
`parent_id`. The column is read from `organisms` like the genome hash. The route is called when an
organism is selected, not per tick.

### Display in the environment grid

Only for the selected organism, and only in the environment grid; the organism panel does not
change, and the minimap does not change.

- In the detailed renderer a mutated cell gets a thin border in the lineage colour of the genome
  the mutation arose in — `AppController._genomeHashToLineageColor` of the event's origin genome
  hash, which already exists.
- In the zoomed-out renderer, where a border cannot be drawn at one to four pixels per cell, the
  cell area is filled with that colour.

Two rules decide which cells are marked. They are applied in the frontend, where the molecule
of every visible cell is at hand, and they must be documented in the code beside the comparison.
Label values need no exception there, because the controller has already translated them into
the displayed organism's namespace.

1. A cell is marked only while it still holds what the mutation wrote: its molecule equals the
   event's `newValue`. A cell the organism itself has since overwritten with a different molecule
   carries no mark. A deletion's `newValue` is empty, so the cleared cell is marked while it stays
   empty, wherever the offset lands, inside or outside the displayed body: the mark shows where
   the deletion happened, and a restriction to the body would hide that. Marker bits are not
   compared; they belong to the organism, not to the molecule.
2. Where two events of the lineage touch the same cell, the younger one decides: the event of the
   more recent generation, and within one birth the event with the larger `event_index`.

### Memory estimates

Both holders of data declare their worst case. With the default density factor the engine's
`maxOrganisms` is `totalCells × 0.0003`, about 10,000 organisms for a 7680 × 4320 world.

- `SimulationEngine`: pending records, declared as `maxOrganisms × 500 bytes` — every living
  organism born since the last recording and carrying one median-sized duplication; about 5 MB
  at the default density. That is a realistic figure in the way the 24 KB per organism are; the
  absolute bound, every organism duplicating a whole scan line of the world's width, is named in
  the comment and not declared, because births per window are a fraction of the population and
  such a duplication needs an empty scan line of that length.
- `MutationEventsPlugin`: rows per recording, in the style of `GenomeLineagePlugin`.

### Out of scope

Per-cell marks in the environment (#135's storage form: the events are the ground truth such marks
could be derived from), execution coverage (#119), and the hash-0 births of body-less children,
which carry no genome and therefore no mutation.

## Cost

| | Amount | Basis |
|---|---|---|
| Tick hot path | none on the per-tick path; on the birth path the plugins fill three primitive lists inside the loops that write the cells, and the simulation adds one null check per newborn | birth handlers run per newborn, not per tick |
| Birth handler | one record per applied mutation; the cell list is filled inside the loop that writes the cells | the plugins already hold every value |
| Engine heap | records since the last recording, about 100 KB for 1,000 births per window at 0.3 events per birth; none in the detailed profile | estimate; the 240 MB the engine estimates for 10,000 organisms are unaffected |
| Chunk storage | about 50 bytes fixed plus 8 to 20 bytes per cell per event, depending on the marker, as in `molecule_data`; a median duplication of 17 cells about 190 bytes; run `20260902`: about 5,000 events, under 1 MB against 3.8 GB; under 0.1 % in every profile | counted in the analysis of that run |
| Organism table | one BLOB of about 200 bytes per mutated organism, written once | |
| Parquet | about 80,000 rows for run `20260902`; one to a few million rows for a run with millions of births | |
| Lineage request | one recursive query over `organisms`, as `readLineage` runs today, plus the column's bytes per ancestor | per selection, not per tick |

## Implementation

The work is cut into vertical slices, each one testable on its own and, from the first on,
runnable end to end, so that a fault in a contract or a seam shows up in the slice that made it.
Every slice ends in its own commits on the branch `mutation-events`, is checked with its tests
and `./gradlew check`, and is shown to the maintainer on a short run before the next one starts.

1. **One mutation from the engine to Parquet.** `runtime/model/MutationRecord.java`;
   `Organism`: field and three methods; `Simulation`: the list of newborns with pending records
   and `clearBirthMutationRecords()`; the complete `MutationEvent` message with field 39 (all
   fields, so that later slices renumber nothing); `OrganismStateSerializer` copies the record;
   `SimulationEngine`: clearing after the successful send, memory estimate;
   `GeneSubstitutionPlugin` records; the relative-offset helper on `EnvironmentProperties`, which
   the analytics plugin needs and which slice 3 makes the `GenomeHasher` and `getRelativeVector`
   delegate to; `MutationEventsPlugin` writes one row per molecule, with the entry in
   `reference.conf`. Tests: `MutationRecordTest`; the
   organism, simulation and serializer tests for record, read, clear, the newborn dead before its
   first recording, and the empty case; the substitution plugin test for cells, old and new
   values and the no-op; `MutationEventsPluginTest` after the pattern of
   `DeathLifetimesPluginTest`; `ResumeNeutralityTest` unchanged and green, plus the test that a
   restore from a snapshot with events carries no records. Runnable: a short run shows
   substitution rows in `mutation_events`.
2. **The remaining reporters.** `GeneDuplicationPlugin`, `GeneDeletionPlugin`,
   `GeneInsertionPlugin` (both entries), `LabelRewritePlugin` with its mask. Tests: one case per
   plugin test that checks cells, old and new values against what was written, `dv` and `params`,
   and that a no-op records nothing. Runnable: every kind appears in the table, and births whose
   hash changed without an event with cells are visible as the remainder.
3. **The organism table.** First, as its own commit, the `organisms` DDL and the static MERGE
   move from the two strategies into `AbstractH2OrgStorageStrategy`, both strategy tests
   unchanged and green. Then the relative-offset helper on `EnvironmentProperties` with
   `GenomeHasher` and `getRelativeVector` delegating to it (tie-break assertion adjusted; resume
   and determinism tests as the proof that no hash changed); the factory
   `MetadataConfigHelper.environmentProperties` replacing the three copies; `StoredMutationEvent`
   and `StoredMutationEvents`; the conversion in `OrganismIndexer`; the column and the second
   statement in the base. Tests: the helper (wrap-around, half world size), the indexer's
   conversion, both strategy tests for writing the bytes, reading the column back, the order of
   the two statements and the untouched later window. Runnable: the column is filled in H2 and
   readable.
4. **The controller route.** The new method on `IOrganismDataReader` — the dual-mode seam —
   implemented in `H2DatabaseReader` by extending the recursive lineage query; the label
   translation; the DTOs and the JSON. Tests: reader, translation across three generations with
   masks, controller. Runnable: `curl` returns the events of a lineage.
5. **The environment grid.** The border and fill in the two renderers, the two rules with their
   documentation, the fetch on selection; the browser heap of the lineage answer estimated and
   bounded; the render caches (`cellObjects`, `clearCache`) invalidated on a selection change; the
   genome-to-parent edges of the lineage answer fed into `_genomeParent` before the colours are
   computed, so an extinct ancestor genome keeps its lineage colour. The concrete look is agreed
   with the maintainer before this slice. Runnable: the marks of one lineage in the visualizer.
6. **The cause on the clade band.** `MutationSummaryPlugin` with the shared position helper and
   its test and its entry in `reference.conf`; the companion list in `ManifestEntry`, the
   written manifest and `AnalyzerController.js`; `mutation_summary` as the second companion of
   the Clade Shares manifest and its display in the chart. Runnable: a band names its founding
   mutation.
7. **Variation sources.** `VariationSourcesPlugin`, its test, its manifest entry, its entry in
   `reference.conf`, and the two options `yLabel` and `yFormat` in `StackedBarChart.js` that bars
   of counts need. Runnable: the stacked bar chart in the Analyzer.
8. **Documentation and sight check.** The `analyze-run` skill: the tables `mutation_events` and
   `mutation_summary` in the genome layer, the join, the `::INTEGER[]` cast for the list
   columns, and the rule that a changed hash without an event with cells is variation outside
   the plugins. A sight check on a run of some length: event count against births times rates,
   one duplication opened in DuckDB and compared with the body, the marks of one lineage in the
   visualizer. Every run is proposed separately with duration and data directory.
