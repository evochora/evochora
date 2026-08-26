# Organism Snapshot Cost: Ancestor Closures and Stored Tick Statistics

**Status: ACCOMPLISHED — implemented on branch `fix/113-record-connection-failures` (PR #118), 2026-08-26.**

The read paths were verified against a copy of the production database: the static fields of 4 604
organisms across seven ticks, the stored organism total at six ticks over six orders of magnitude,
and the ancestor relation seeded with all 150 525 genomes of the run, which agrees with the old
full-tree query entry for entry in both directions. The browser check ran on short runs produced by
the current build; the published run could not serve it, for the reason recorded under step 8.

## Problem

Every `GET /visualizer/api/organisms/{tick}` request scans the whole `organisms` table three times:
once into a map of all organisms ever created, once for a `MAX` over an unindexed column, and once
through a self-join that recomputes the complete genome lineage tree. On run
`20260226-03114337` — 14 644 315 organisms, 150 526 genomes — a single tick change costs tens of
seconds of CPU and carries a 6.6 MB tree in the response. All three reads scale with the total size
of the run, not with what the tick shows.

The heap peak of the first read is the practical trigger of [#113](https://github.com/evochora/evochora/issues/113):
concurrent requests exhausted the 16 GB heap on the demo node, and the resulting `OutOfMemoryError`
inside an H2 store operation killed the embedded database for the lifetime of the process.

## What one request does today

| Step | Query | Cost |
|---|---|---|
| `readOrganismsBlobForTick` | one row of `organism_ticks` by primary key, zstd-decompressed | proportional to the tick |
| `readAllStaticInfo` | `SELECT organism_id, parent_id, birth_tick, genome_hash FROM organisms` — no `WHERE` | full scan, all rows into a `HashMap` |
| `readTotalOrganismsCreated` | `SELECT MAX(organism_id) FROM organisms WHERE birth_tick <= ?` | full scan, constant memory |
| `readGenomeLineageTree` | self-join of `organisms` with itself, `ORDER BY organism_id` | full scan, join, sort, up to 150 k entries |

HTTP caching is disabled by default for this endpoint (`reference.conf`, `http-cache.organisms`), so
every tick change pays all of it again.

## Finding 1: the static-info load is redundant, not merely oversized

`SingleBlobOrgStrategy.readOrganismsAtTick` reads the tick's BLOB and then loads the entire
`organisms` table to look up three fields per organism: `parent_id`, `birth_tick`, `genome_hash`.

All three are already in the BLOB it just read — protobuf fields 2, 3 and 28 of `OrganismState` —
and the table is written from exactly those values (`AbstractH2OrgStorageStrategy.addOrganismMetadataBatch`).
All three are immutable per organism: parent and birth tick by nature, and the genome hash is
assigned once, at placement (`SimulationEngine`) or at birth via FORK (`Simulation`), and never
changed. Table and BLOB therefore always agree, and the existing fallback
(`staticInfo != null ? staticInfo.parentId : org.getParentId()`) chooses between two identical
sources.

This is specific to the BLOB strategy. `RowPerOrganismStrategy` solves the same task with a join
restricted to the tick (`WHERE s.tick_number = ?`) and reads no more than the tick's organisms.

Organisms that died since the last sampled tick are part of the same BLOB — `pruneDeadOrganisms()`
runs only inside `captureSampledTick`, after serialization — and they carry their static fields like
any other organism. The set in the BLOB is exactly the set the response needs.

## Finding 2: the whole tree is computed where a closure is needed

The tree has one consumer: lineage colouring in the visualizer. A genome's colour depends only on its
own ancestor chain — a root hue derived from the genome hash alone, each child hue shifted from its
parent's. Colours are stable per genome and independent of the rest of the tree. The same colouring
is applied to the ancestor list in the organism detail view.

What a view needs is therefore the **ancestor closure** of the genomes it displays, not the tree of
all genomes ever observed. Measured on the published run, that is 719 entries against 150 525 — a
factor of 209.

The closure cannot be derived from an organism response, and that is not an oversight: an organism
carries its own genome hash and its parent's *organism id*, but not the parent's genome hash. The
parent is usually long dead and pruned. The CLI `LineageRenderer` solves the same problem and can
only do so because it streams every tick from the beginning, accumulating an organism→genome map.
The visualizer jumps to an arbitrary sampled tick and never streams, so the relation has to be
served to it.

## Finding 3: the organism total is recomputed although the pipeline delivers it

`readTotalOrganismsCreated` reconstructs the count as `MAX(organism_id) WHERE birth_tick <= ?`.
The simulation already maintains the value and ships it with every tick — `TickData.total_organisms_created`
(field 8) and `TickDelta.total_organisms_created` (field 6), documented as *"a monotonic counter that
includes both initial and child organisms"*. Nothing persists it, so it is derived instead.

`OrganismIndexer.processChunk` currently drops it: the lightweight `TickData` it builds for delta
ticks copies only the tick number and the organisms.

Its single consumer is the organism panel header, which renders `(alive/total)`.

## Measurements

**Shape of the real data** (run `20260226-03114337`, measured on a reflink copy of the production
database; the live node was never queried):

| | value |
|---|---|
| organisms / genomes / ticks | 14 644 315 / 150 526 / 0 … 2 017 449 974 |
| genomes with a parent, roots | 150 525 / 304 — no cycles, no self-references, no dangling parents |
| genome depth to root | min 1, median 19, p90 46, p99 75, **max 89**, mean 24.4 |
| genomes alive simultaneously | median 397, p90 520, p99 541, **max 548**, mean 372 |
| ancestor closure for 400 visible genomes | **719 entries** |
| organism ancestry chain of the youngest organism | 223 |

Simultaneously living genomes come from the `active_genomes` column of `GenomeAnalyticsPlugin`,
read through the Parquet-backed analyzer API.

**The closure walk over the real data**, after `CREATE INDEX … ON organisms(genome_hash, organism_id)`
(build: 51–55 s on 14.6 M rows), 400 seed genomes, 719 closure entries, three runs each:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| embedded, genome-wise | 903 ms | **240 ms** | **222 ms** |
| embedded, level-wise (`IN` list + window function) | 510 ms | 365 ms | 319 ms |
| over H2 TCP (localhost), genome-wise | 358 ms | **289 ms** | **306 ms** |
| over H2 TCP (localhost), level-wise | 362 ms | 355 ms | 359 ms |

Both variants return identical results. Without the index, a single walk step costs ~69 ms, which
extrapolates to ~50 s for the full closure — the index is what makes the approach work at all.

Two caveats: the first run is never truly cold, because the index had just been built; and the file
did not grow measurably, since MVStore reused free space inside the 33 GB file. For the index size the
synthetic measurement stands: **+121 MB on a 276 MB `organisms` table** with the same row count.

**Rejected alternatives**, all measured:

| | why not |
|---|---|
| materialized `genome_lineage` table (+3 MB, sub-ms lookups) | see below |
| `INDEX(birth_tick)` for the organism total (+158 MB) | 1 416 ms instead of 5 880 ms — still seconds, for more storage than the genome index |
| level-wise walk | not faster in either configuration we can run today (table above) |

A materialized genome→parentGenome table was the sketch in #114. It was rejected on architectural
grounds: maintaining it at index time turns an order-independent function of table content into a
function of commit order. `AGENTS.md` requires every service except `SimulationEngine` to be capable
of running as competing consumers, `AbstractBatchIndexer` re-assigns unacknowledged batches after
`claimTimeout`, and its DLQ path skips a batch entirely. Under any of those, a genome can be recorded
as a root because the row of its parent organism has not been written yet — and because the first
writer wins, that error is **permanent**. A read-time derivation has the same exposure while a run is
still being indexed, but it is **transient**: once the missing rows arrive, the answer corrects
itself. That difference is the whole argument.

## Solution

### Ancestor closures instead of the full tree

The derivation stays at read time, over the `organisms` table, restricted to the genomes a view
displays. One step of the walk is today's query for a single genome:

```sql
SELECT p.genome_hash
FROM organisms c LEFT JOIN organisms p ON c.parent_id = p.organism_id
WHERE c.genome_hash = ? AND c.genome_hash <> 0
  AND (p.genome_hash IS NULL OR p.genome_hash <> c.genome_hash)
ORDER BY c.organism_id LIMIT 1
```

A returned parent hash of 0 is normalised to "root", and 0 is never followed. The walk collects every
genome reachable upwards from the requested ones, driven by a visited set — "already in the result"
is the termination test — so it terminates on any input.

It is implemented genome-wise, in `H2DatabaseReader`, next to the two `organisms` queries that
already live there. `IH2OrgStorageStrategy` gains no method: its own contract states that
`organisms` is not affected by the strategy, three of the four existing queries over that table are
already inline in the reader, and dispatching only the fourth would give a future strategy no real
freedom while enlarging the interface.

### The supporting index

```sql
CREATE INDEX IF NOT EXISTS idx_organisms_genome ON organisms(genome_hash, organism_id)
```

The DDL is offered as a protected helper on `AbstractH2OrgStorageStrategy` and **called by each
strategy** from its own `createTables`, next to its own `organisms` DDL — the same shape
`addOrganismMetadataBatch` already has: the base provides, the strategy decides whether to use it.

`OrganismIndexer.prepareTables` calls `createOrganismTables()` on every start. For a new run the
table is empty and the index is created instantly. Restarting an indexer on an already populated
table pays the one-off build — 51 s on 14.6 M rows — inside a blocking DDL, during which competing
consumers and visualizer readers of `organisms` wait. This is bounded and one-off, but operators
should know it exists.

The write path pays continuous maintenance for a second secondary index on `organisms`. This is not
measured. Estimated: organism MERGEs are deduplicated per commit window, so 14.6 M over the whole
run rather than per tick, against 40 000 BLOB writes totalling over a gigabyte — an overhead in the
region of 10–20 % of organism indexing, disappearing in the indexer's total throughput.

### The API contract

```java
// IOrganismDataReader
- Map<Long, Long> readGenomeLineageTree(long tickNumber);
+ Map<Long, Long> readGenomeAncestors(Collection<Long> genomeHashes);
```

This is what the controller sees; it never learns which database serves it. The signature takes a
collection rather than a tick because both callers already hold the data — the tick view has just
read the organisms, the detail view the ancestry chain. A tick-shaped signature would force the
implementation to read the tick BLOB a second time and would need a second method for the detail
view.

Because two implementations must produce identical answers, the contract states the relation itself:

1. **Genome hash 0 is not part of the relation at all.** Such organisms exist — a broken replication
   loop can produce children without marked molecules — but genome 0 is neither a key nor a parent.
   A carrier whose parent holds genome 0 is treated exactly like one with no parent.
2. A genome's parent genome is the genome of the parent organism of its **first carrier**: the lowest
   `organism_id` among carriers that either have no parent, or whose parent's genome differs from
   their own. Filtering precedes ordering — a carrier that inherited the genome unchanged is never
   the first carrier.
3. A first carrier with no parent, with parent genome 0, or whose parent row is not present makes the
   genome a **root**. The last case occurs while a run is still being indexed and resolves itself
   when the missing rows arrive.
4. The relation is structural: it asks only whether a child's genome differs from its parent's, never
   why. Mutation and defective replication logic are covered alike, without special handling.
5. The return value is the closure: every genome reachable upwards from the requested ones, mapped to
   its parent. A **present key with a `null` value means root**; an **absent key means the genome
   does not occur in this run**. Every genome that occurs, other than 0, gets an entry.
6. Implementations accept input collections of arbitrary size and batch internally if their transport
   makes round trips expensive. Measured for H2: batching is *not* worth it embedded or over
   localhost TCP; over real network distance it would be.

Three invariants make those rules verifiable, and belong in the same place because a second
implementer cannot derive them from the code they are writing:

- Organism ids are assigned in creation order (`Simulation.nextOrganismId` only increments, across
  resume as well), so the lowest id is the earliest carrier.
- A parent organism is always created before its child, so `parent_id < organism_id`.
- Therefore the relation is **tick-independent** — the global first carrier of a genome visible at
  tick T was born no later than T, so it is also the first carrier within any bound `birth_tick <= T`.
  This is why the old `birth_tick` bound of `readGenomeLineageTree` can be dropped without changing
  any answer. The existing test `readGenomeLineageTree_respectsBirthTickFilter` is replaced by one
  asserting that the closure does not depend on the tick requested.
- And therefore the relation is **acyclic**: if genome A's parent genome were B, then walking up from
  A's first carrier through parents that still carry B — ids strictly decreasing — reaches a
  qualifying carrier of B with a lower id than A's. So B's first carrier is older than A's, and the
  symmetric claim cannot hold at the same time. The visited set is a guard, not a repair.

Point 5 has a technical consequence worth stating in the same place: `null` values rule out
`Map.of`, `Map.copyOf` and `Collectors.toMap`. The type is unchanged from today's
`readGenomeLineageTree`, so DTO and frontend keep their shape.

### The organism total

`OrganismIndexer` carries the value into delta ticks:

```java
TickData deltaAsTick = TickData.newBuilder()
    .setTickNumber(delta.getTickNumber())
    .addAllOrganisms(delta.getOrganismsList())
    .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())   // new
    .build();
```

It is stored in a table the shared base owns:

```sql
CREATE TABLE organism_tick_stats (
  tick_number             BIGINT PRIMARY KEY,
  total_organisms_created BIGINT NOT NULL
)
```

`StreamingSession` gains a third prepared statement, driven by the base in `commitOrganismWrites`,
`resetStreamingState` and `purgeClosedConnections` like the two existing ones. A per-strategy layout
was considered and dropped: the session has to grow either way, a slot used by only one strategy is
more code in the base rather than less, and the API contract requires both strategies to answer the
same way — otherwise a configuration choice would change public behaviour. A scalar counter is also
not organism state; it does not trade storage against query performance in any way the strategy axis
is about.

An upsert is required rather than an insert: at-least-once delivery means a chunk can be reprocessed.
Both existing per-tick statements are MERGE for the same reason.

**The stats row is written for every sampled tick, including a tick with no living organisms.** An
extinction tick is the one tick where "how many were ever created" is the only surviving information.
The BLOB row stays conditional on a non-empty organism list, so `getAvailableTickRange` is unchanged
and the visualizer's navigable range does not move.

**A tick with no row raises `TickNotFoundException` instead of returning 0.** Reporting zero organisms
ever created for a tick that merely was not sampled is a silent wrong answer, indistinguishable from a
run that genuinely produced nothing. `IH2EnvStorageStrategy.readChunkContaining` already sets this
precedent on the environment side.

`TickNotFoundException` is a checked exception, so this **is** an API change:
`IOrganismDataReader.readTotalOrganismsCreated`, `IH2OrgStorageStrategy.readTotalOrganismsCreated`,
`H2DatabaseReader` and `OrganismController` all declare it. `VisualizerBaseController` already maps it
to 404. Both JavaDocs are rewritten: the one on `IH2OrgStorageStrategy` currently documents the
`MAX(organism_id)` derivation *as the contract*, and "or 0 if none exist" is removed from both.

The visualizer does not reach that path in normal operation. `SimulationEngine` initialises its tick
counter to −1 and increments before sampling, so the first sampled tick is **0**, and 0 is a multiple
of every interval; sampling is `tick % samplingInterval == 0` on absolute tick numbers, so a resumed
run stays on the same grid; the metadata endpoint delivers `samplingInterval`; and
`AppController.navigateToTick` rounds every target down to a multiple of it before clamping to
`maxTick`. The change becomes visible in two situations, and in both it replaces a silent defect with
a loud one: a run whose metadata carries no `samplingInterval`, and a hole in the organism data left
by a DLQ skip. The second matters scientifically — today such a hole renders as an empty population
over a populated environment, which looks like an extinction event rather than like missing data.

**Widths.** The column is `BIGINT`, matching the wire field, so nothing is narrowed on the write path.
The API keeps `int`, because the model is bounded at its root: `organism_id` is `INT`, so a run cannot
exceed 2.1 billion organisms without overflowing the ids themselves. The narrowing happens once, on
read, and it is not silent — H2 raises error 22003, *"Numeric value out of range"*, rather than
truncating (verified against 2.4.240). Both facts belong in the JavaDoc so the bound is a stated
decision rather than an accident.

### HTTP: one response per view

No new endpoint and no new route. The ancestor closure is bundled into the responses that need it:

- `GET /visualizer/api/organisms/{tick}` carries the closure for the genomes of that tick.
- `GET /visualizer/api/organisms/{tick}/{organismId}` carries the closure for the genomes in the
  organism's ancestry chain.

Each response is self-contained: no view depends on what another view happened to deliver.

Bundling costs 719 entries — roughly 32 kB against 6.6 MB today — and saves a request, a second
pooled connection per view, a second read and decompression of the same BLOB, and the entire
"what do I not know yet" logic in the client. The second connection matters: pool exhaustion is
what #113 was about.

Both closures are composed **in the controller**, which already holds the reader, the organisms and
the ancestry chain, and calls `readGenomeAncestors` with the genome hashes it has. Putting the closure
into `OrganismTickDetails` would instead oblige every database implementation to compute it a second
time inside `readOrganismDetails`.

The detail view needs its own closure and cannot borrow the tick's: an ancestor organism can carry a
genome that is not an ancestor *genome* of the displayed one, because a genome hash can arise a second
time from a different parent genome while the relation records only the first origin. Today the full
tree covers that case by accident of size. Its cost is small, because the genomes along one organism's
ancestry chain are nearly collinear — the walk is essentially one chain of at most 89 steps, not 223
independent ones. The seed set is every genome of the returned chain, not only the five or six the
frontend displays; that truncation is a display decision and does not belong in the server contract.

`OrganismsResponseDto` replaces `genomeLineageTree` with the closure field, and the detail DTO gains
the corresponding one.

The ETag `"runId_tick"` does not cover the closure, which is not stable while a run is still being
indexed. This is unchanged from today, where the same applies to the tree, and HTTP caching for this
endpoint is disabled by default.

### Frontend

`AppController` clears `_genomeParent` and the colour caches **on every tick change** and adds to
them within one tick. Keeping them across tick changes was considered and dropped: on a run that is
still being indexed a closure can transiently record a genome as a root, and today that error
disappears at the next tick change. Keeping the caches would make it last the whole session.
Recomputing 719 colours costs milliseconds; a session-long wrong picture is not worth that saving.

Adding within one tick is required because a view is served by two responses — the organisms of the
tick and, when one is selected, its ancestry chain, whose ancestors need not appear at that tick.
Their closures overlap but neither contains the other. Both come from the same relation, so a genome
present in both carries the same parent and adding cannot contradict what is already there.

`_computeLineageColor` gains a visited set across its recursion. It currently guards only against a
genome being its own parent, so a two-node cycle would overflow the stack. That defect predates this
change and cannot be triggered by the data, but the function is being touched.

Five defensive coercions for fields the new contract guarantees are removed, so a missing field shows
rather than being replaced by a plausible substitute: `Array.isArray(data.organisms) ? … : []`,
`data.totalOrganismCount || 0` and `data.genomeLineageTree || {}` in `OrganismApi`,
`this.state.totalOrganismCount || organisms.length` in `AppController`, and the `if (!tree) return;`
guard in `_applyGenomeLineageTree`.

### Runs indexed by older builds

Out of scope. No detection, no migration, no fallback — a database written before this change lacks
the stored per-tick total, and the organisms endpoint fails for it the way any schema change fails
today. Making format mismatches explicit is the subject of
[PERSISTED_FORMAT_VERSIONING](../../../proposals/PERSISTED_FORMAT_VERSIONING.md).

### Points checked and deliberately not changed

- `H2DatabaseReader` keeps two further inline `organisms` queries, `readOrganismStaticInfo` and
  `readLineage`. Moving all queries over that table into one place is a decision about who owns
  `organisms`, not part of this change.
- The `organisms` DDL is duplicated in both strategies. Consolidating it would decide, as a side
  effect, that no future strategy may store static organism metadata differently.
- `RowPerOrganismStrategy.createTables` ends with `conn.commit()`, `SingleBlobOrgStrategy.createTables`
  does not. DDL commits implicitly in H2, and both already create the identical `organisms` table
  across that difference in production.
- `createTables` is an unenforced convention: a strategy must remember to call the shared helpers and
  `markTablesCreated()`. Turning it into a template method is a structural change beyond this
  document.

## Implementation

**Step 1 — Remove the redundant static-info load.** `SingleBlobOrgStrategy.readOrganismsAtTick` takes
`parent_id`, `birth_tick` and `genome_hash` from the BLOB it already read; `readAllStaticInfo` is
deleted. Independent of every later step, and the step that removes the heap peak behind #113.

**Step 2 — The organism total.** The indexer carries it into delta ticks; the base creates and writes
`organism_tick_stats` through a third statement in `StreamingSession`, for every sampled tick;
`readTotalOrganismsCreated` reads it by primary key and raises `TickNotFoundException` where no row
exists; the signature change is carried through reader, strategy interface and controller; both
JavaDocs rewritten.

**Step 3 — The index.** DDL helper on the base, called from each strategy's `createTables`.

**Step 4 — The closure.** `readGenomeAncestors` in `H2DatabaseReader`, walking genome-wise with a
visited set; `readGenomeLineageTree` removed from reader and `IOrganismDataReader`; the contract and
its invariants recorded on the new method.

**Step 5 — HTTP.** Both closures composed in the controller and bundled into the tick and detail
responses; the tree removed from the response DTO.

**Step 6 — Frontend.** Replace instead of merge, visited set in `_computeLineageColor`, the four
coercions removed.

**Step 7 — Tests.** Small hand-built scenarios:

- first carrier with a differing parent genome → that parent genome is recorded
- first carrier with no parent → root: key present, value `null`
- first carrier whose parent carries genome 0 → root as well
- genome hash 0 as an input produces no entry and no walk
- a carrier that inherited its genome unchanged is not chosen as the first carrier
- a carrier whose parent row is absent → root, and the answer corrects itself once the row exists
- the closure does not depend on the tick requested
- closure of several genomes with shared ancestors contains each entry exactly once
- the walk terminates on a hand-built cyclic table
- the stored total survives a reprocessed chunk
- a sampled tick without organisms still stores a total
- a tick with no stats row raises `TickNotFoundException`
- the tick response no longer carries the full tree

The repository has no test infrastructure for the visualizer's JavaScript, so step 6 carries no
automated tests. It is verified by hand in step 8, as frontend changes are today.

**Step 8 — Verification in the browser.** On a run produced by the current build.

The published run cannot serve this purpose, and the reason is worth recording. Its organism blobs
were written in February 2026, before commit `8919f646` renumbered the fields of `OrganismState`;
a current build fails to parse them with *"Protocol message had invalid UTF-8"*, which is the exact
consequence [PERSISTED_FORMAT_VERSIONING](../../../proposals/PERSISTED_FORMAT_VERSIONING.md) records as evidence. The
demo node serves that run only because its image predates the renumbering. This is independent of
this change and blocks any browser check against existing data.

The read paths this change touches were therefore verified against the production data directly,
which needs no blob parsing:

- the static fields of 4 604 organisms across seven ticks spanning the whole run agree between blob
  and table without exception;
- the stored organism total agrees with the derived one at six ticks across six orders of magnitude;
- and the ancestor relation, seeded with **all 150 525 genomes of the run**, agrees with the old
  full-tree query entry for entry, in both directions — in 5.8 s against the old query's 10.7 s.
