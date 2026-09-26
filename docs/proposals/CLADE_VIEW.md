# Clade View in the Visualizer

**Status: OPEN PROBLEMS — not ready for implementation. The section "Open problems" lists every
problem known when this version was written; it is to be revised before anything is built. Three
further points are deliberately left open by the maintainer, see the last section.**

## Problem

The visualizer colours organisms by genome kinship: every genome gets a hue derived from its place
in the genome tree, which the organism endpoints deliver as `genomeAncestors` (genome → parent
genome, built by `readGenomeAncestors`). Two things are missing or wrong:

- **There is no view of the clades themselves** — no way to see how the population divides between
  lines of descent over the run, to enter one line and see what it split into, or to follow what
  became of the descendants of a given genome.
- **The genome tree misattributes descent.** It gives every genome exactly one parent genome, that of
  its first carrier. When the same genome arises a second time from a different parent — most
  plausibly by back-mutation, A → B → A — the later carriers descend from B but stand under A in the
  tree. A genome whose parent carried no genome molecules begins a new line altogether.

A first attempt (PR #189, branch `visualizer-clades`) answered the clade view from the organisms'
lifespans at about sixty sampled ticks and sent the genome tree narrowed to the genomes alive at those
ticks. The narrowing lost almost every genome the viewer actually meets: measured on a run of
1.02 million organisms, the share of genomes on screen that the tree did not know was 47 % at tick
1 million, 71 % at 150 million, 83 % at 250 million and 100 % at the end of the run. A click on such
a genome could not enter its clade. The attempt also computed at request time what belongs in a
structure prepared for queries, and it kept the genome-tree definition. This proposal replaces its
server part; its frontend work (chart, path strip, genome field, palette, mutation marks) is reused.

## Requirements

- For any organism, all its descendants can be shown and followed over the run: its clade, how it
  divided, and what became of it. This is where the clade view began.
- Every genome that appears in the visualizer can be selected and shows its clade.
- The chart takes every recorded tick into account; there are no sample points that a short-lived
  clade can fall between.
- A click in the clade view answers in well under 0.2 s, for runs of more than 2 billion ticks and
  more than 15 million organisms.
- A one-time wait when a run is opened for the first time is acceptable, about 40 s for a run of
  15 million organisms, as long as the clade view shows that it is loading while the rest of the
  visualizer works. Most of it is reading the organisms from the database.
- A run that has grown while no browser watched it is brought up to date in at most the time of a
  first opening.
- No dependency on analytics plugins: they need not be configured in a run, and when they are, they
  may run behind the organism and environment indexers.
- The courses are condensed onto a time grid and nothing else: every value is exact for its section,
  no organism is left out, and the complete data stays in `organisms`.

## Solution

### What a clade is

A clade **begins** where a new genome arises. An *origin* is every organism whose `genome_hash`
differs from its `parent_genome_hash`, both read from its own row. An organism without a parent and an
organism whose parent had genome 0 are special cases of the same rule. Because the rule reads only the
organism's own row, a parent that is indexed late changes where a subtree hangs, never whether an
origin exists.

**Who belongs** to a clade is decided by descent of the organisms alone, along `parent_id`, not by
the genome tree. The clade of genome X is every organism descended from an origin of X.

This is exact where the genome tree is not: under back-mutation, under parallel origin of the same
genome, and for organisms without genome molecules, which belong to the clade of the organism they
descend from.

**Genome 0** is left out entirely, in the visualizer and in the analyzer alike (the analyzer's
`GenomePopulationPlugin` and `GenomeLineagePlugin` already do so): an organism with `genome_hash = 0`
is never an origin, is counted in no share — neither in a band nor in the population a share is taken
of — and appears in no chart. In the environment and the organism list it keeps its present grey
(`0x808080`), distinct from `other`, the outside of the entered clade and the dead. Its descendants
stay reachable through `parent_id`. Genome 0 does not mean "no body": `STRUCTURE:100` and `STATE` are
excluded from the hash.

**Topmost origins.** When genome A arises inside its own clade again (A → B → A), the inner origin
lies in the clade of the outer one already. Wherever origins of one genome are summed — a band, the
`<×n>` row, a genome selected directly, the common ancestor in the path — only the topmost origins
count: those with no origin of the same genome above them in the range considered (the whole run
for a genome selected directly, the subtree of the level above for a band). Inner origins are
reached by clicking (A › B › A) or by organism id.

The genome hash in evochora is not a strict genome — it covers the molecules an organism owned at its
birth, so a changed shell, for instance, yields a new hash without a change in logic. It therefore
decides only where clades begin, never who belongs to them.

### Data source

Nothing new is indexed. Since PR #190 the table `organisms` holds everything the clade view needs:
`organism_id`, `parent_id`, `genome_hash`, `parent_genome_hash`, `birth_tick` and `death_tick`.
`AbstractH2OrgStorageStrategy` gains the index `idx_organisms_parent (parent_id)`, which the organism
query (below) needs. It is one more B-tree beside the primary key and the one secondary index the table
has (`idx_organisms_genome`), written once per organism, and like that one it is created in the
`createTables` of both strategies (`RowPerOrganismStrategy`, `SingleBlobOrgStrategy`). Its cost while indexing was not measured; with an equivalent index in
memory the organism query took 1–6 ms.

The model described below cannot be prepared by the indexers. A clade's course over time is a sum
over all its descendants; adding an organism's contribution to every clade above it is not idempotent
under redelivery, and walking up to those clades needs ancestors that a competing consumer may not
have indexed yet. What an indexer can write statelessly — facts of one organism or one tick — is
already in `organisms`.

**What the model takes from the run's metadata and tick range.** With competing consumers the lowest
tick indexed so far is not the first tick of the run, so neither the grid nor the fork boundary is
derived from indexed rows:

- the first recorded tick is `fork.first_tick` from the metadata for a forked run, and the start of the
  recording for a run that began fresh; the recording interval comes from the run's resolved
  configuration in the metadata;
- the tick T a step catches up to is the end of `getOrganismTickRange()`, and the highest id it reads is
  `readTotalOrganismsCreated(T)`; the run has grown when T has changed.

**Forked runs.** A fork starts with organism ids far above 1, and the parents of its first organisms
belong to the run it was forked from. The boundary B is `readTotalOrganismsCreated` of the first
recorded tick of the fork, and 0 for a run that began fresh. An id at or below B that is missing is missing for good; an
organism whose parent is missing and at or below B is a founder of this run. Only ids above B are
gaps that may still be indexed. For a run that starts at tick 0 nothing changes. Until the first
recorded tick is indexed there is no model, and the clade view shows that it is loading.

**Reads.** `IOrganismDataReader` gains three reads of one table each, by primary key or by the one
secondary index, in plain statements that any relational database supports:

- `readOrganisms(afterId, upToId, consumer)` — a range of rows, streamed: the first build reads the
  whole run, a catch-up step the new ids;
- `readOrganisms(ids, consumer)` — rows by id: gaps, and the death ticks of the organisms alive at the
  last step;
- `readChildIds(parentIds)` — for the organism query.

`readGenomeAncestors` is removed; the interface goes from six abstract methods to eight, beside the
default `labelNamespaceMaskOf`. No SQL lives in the model package.

### The clade model

The server keeps one model per run. Its maps, each with fixed MVStore data types
(`IntegerDataType`, `LongDataType`, `ByteArrayDataType` and a small pair type for the composite keys),
never the generic `ObjectDataType`:

| Map | Key → value | Used for |
|---|---|---|
| origins | origin → origin above, genome, provisional flag, weight | the origin tree, ranking, skipping |
| child origins | (origin above, origin) | finding the subclades of a clade by a range read |
| courses | origin → course of its subtree over the grid | the chart |
| genome index | (genome, origin) | the origins of a genome by a range read |
| short hashes | (short hash as a number, genome) | completion in the genome field, unknown genomes |
| organism → origin | organism → its nearest origin | catch-up, the living, colouring, the organism query |
| living | organism → birth tick | adding the living on every request |
| gaps, pending, provisional members | — | ids not yet indexed, organisms waiting for a late parent |
| meta | — | layout version, state caught up to, section width |

- The **weight** of an origin is the area of its band: the sum of its course over the whole run.
  Ranking and skipping use it; it is kept with the origin so that neither reads a course.
- The **courses** hold the dead organisms only. The contribution of the living grows with every tick;
  it is added fresh on every request from the living map.
- A **provisional root** stands for an organism whose parent is not indexed yet and that is not an
  origin itself. It holds the subtree until the parent arrives and is then dissolved into the parent's
  clade. It is never a band: until then its subtree counts in `other` of the top level and is coloured
  as `other`.

#### Storage

The model lives in an **H2 MVStore file per run** (MVStore is part of the H2 dependency already in
use). It is never loaded into the heap as a whole: a request reads only the entries it needs, a
catch-up step writes only the entries it changes, and the MVStore cache is bounded.

- The directory is an option of `OrganismController` in `reference.conf`, `model-directory`,
  default `${pipeline.dataBaseDir}/clades`, one file per run id. It follows `dataBaseDir`, so switching
  between data directories switches the models with the databases. In a distributed deployment the web
  server may run on another machine than the database and the indexers; the model is derived and can
  be rebuilt at any time, so a local directory of the web server is sufficient.
- `retentionTime` is 0: readers of an older version are protected by `registerVersionUsage` (below),
  and a retention time keeps the compaction from freeing anything while steps follow each other within
  seconds.
- **One commit per step, and no other.** The store is opened with `autoCommitDisabled()` and
  `autoCommitBufferSize(0)`: the first alone stops only the timed commits, while MVStore still commits
  whenever its unsaved memory passes the buffer (measured: 8 commits inside one step of 408,324
  organisms). A half step on disk would be repeated by the next one and counted twice. Without the
  buffer, the pages a step changes stay in the heap until its commit: 137 MB for that step, against
  42 MB with the buffer; a step is at most 500,000 organisms.
- After every catch-up step a bounded compaction runs, `compact(80, 16 MB)`: it rewrites at most
  16 MB and only when the file is less than 80 % full.
- **Closing**: the build and catch-up threads are stopped and awaited, a step in progress is rolled
  back (`rollback()`), then the store is closed without compaction (`close(0)`), since `close` itself
  commits. The step is repeated after the next start; a `.building` file is discarded.
- Measured size: 62 MB after the first build and 107–138 MB in operation for the run of 1.02 million
  organisms; 167 MB and 166–250 MB for the run of 14.6 million.
- **Deleting**: `evochora cleanup` deletes the model file of every run whose schema it deletes, finding
  the directory in the configuration as it finds the blob directories today. The server deletes
  nothing on its own.
- **A file that cannot be opened** is handled by the MVStore error code. Corrupt
  (`ERROR_FILE_CORRUPT`), of an unsupported format (`ERROR_UNSUPPORTED_FORMAT`) or of an older model
  layout (the layout version in the meta map): it is deleted and rebuilt, with a WARN in the log — the
  model is derived, rebuilding is the intended handling, not a migration. Locked by a second process
  (`ERROR_FILE_LOCKED`, two nodes on one data directory): it is left alone, and the request fails with
  500 and a message naming the lock, with an ERROR in the log.

#### Code structure

The model is a package of its own beside the controllers,
`org.evochora.node.processes.http.api.visualizer.model`, named so that later models of the visualizer
find their place in it. It is neither `datapipeline` nor a controller:

| Class (working name) | Task |
|---|---|
| `CladeModels` | one model per run; the first build once per run, also under concurrent requests; closing |
| `CladeModelStore` | the MVStore file of a run: maps, `.building` and the rename, versions for readers |
| `CladeModelBuilder` | the first build and every rebuild |
| `CladeModelCatchUp` | catch-up steps |
| `SectionGrid` | grid arithmetic and doubling |
| `CladeQuery` | bands, skipping, path, a genome selected directly, the organism query, the origins of a tick |

The clade view is part of the organisms: `OrganismController` owns the model and serves it. It stays
thin — it reads the request, calls the package, builds the DTO and sets the ETag. `IController` gains
`close()` with an empty default. `HttpServerProcess` keeps the controllers it creates (today it holds
each only in a local variable) and calls `close()` on each after `app.stop()`; `OrganismController`
closes the models, which stop their threads before they close their stores.

#### First build

The first request for a run without a model file starts the build on a thread of its own and is
answered at once with `202 Accepted`; so is every request for that run until the build is done. The
frontend asks again in its 5 s poll and shows that the clade view is loading.

- All organisms are read once and held as arrays for the build; the origin tree and the courses are
  computed in two passes over the arrays, the courses summed from the leaves upwards.
- Every map is written in key order; the genome index and the short hashes are gathered in memory and
  written once. Only the model in MVStore remains.
- The build writes into `<run>.mv.building` with intermediate commits and renames it to `<run>.mv`
  when it is done. A `.building` file left from an interrupted build is discarded and built again.
- If the build fails, requests are answered with 500 and the cause; the next request starts it anew.
- Builds of different runs may run at the same time. The temporary heap of the arrays is accepted.

#### Catch-up

The run grows while it is viewed. A request for a run whose database has grown answers at once from
the last complete state of the model and starts one catch-up step in the background; while a step of
that run is running, no further one is started or queued. A step always catches up to what the
database holds at its start, however many requests arrived meanwhile. The next poll gets the new
state. So the load of catching up depends on how fast a run grows, not on how many browsers watch it.

A step:

1. **Structure.** New organisms and gaps that have arrived are entered: new origins in the origin
   tree, the child origins, the genome index and the short hashes, every organism in the map to its
   nearest origin. When a late parent arrives, a subtree that stood as a root of its own is attached
   where it belongs; a provisional root is dissolved into the parent's clade. The new entries of a
   step are gathered per map and written in key order.
2. **Contributions.** Organisms that were alive at the last step have their death ticks read again;
   those that died, and new organisms that are already dead, contribute their lifespan. Every
   contribution — and every attached subtree — is gathered at its nearest origin.
3. **Passing up.** The gathered contributions are passed upwards once, children before parents
   (descending organism id), so that every origin is written once per step, however many contributions
   reach it. Course and weight change together.
4. **Commit**, together with the state caught up to, and the bounded compaction.

A large backlog is worked off in steps of at most 500,000 new organisms — 3 to 5 s each by the
measurements — so the chart grows towards the timeline step by step. A missing death tick corrects itself: the living are counted fresh on every request, and the
next step books the lifespan once the death is indexed.

**Rebuild instead of catch-up.** When the backlog exceeds 50 % of the organisms the model already
holds, the model is rebuilt in the background instead (as a first build, into `.building`), while the
old model keeps answering; the rename replaces it. Catching up costs more per organism than building,
because every new key lands on another page of an existing tree: 9.5 s per million organisms on the
run of 1.02 million, 6.1 s on the run of 14.6 million, against 3.7 s and 37.6 s for a whole rebuild
including the read. Both cost the same at a backlog of 64 % and 74 % of the model respectively. The
threshold lies below both, because the cost of a rebuild is set by the read and is predictable, while
catching up depends on the share of origins in a run. Bringing a run up to date thus never takes
longer than opening it for the first time.

#### Reading while a step writes

A request reads the last committed version of the model (`openVersion`, kept alive by
`registerVersionUsage`) and never waits for a step. Only the writers of a run are serialised, by the
rule of one step at a time.

### Time grid

The courses are condensed onto a grid; the complete data stays in `organisms`.

- Each value of a course is a **sum**: the number of organisms alive at each recorded tick of the
  section, added up over those ticks. The answer divides by the number of recorded ticks in the
  section to show a mean. A clade shorter than a section leaves a small value, never none.
- The grid **starts at the first recorded tick** of the run, as the timeline does
  (`TickGrid.firstTick`). A section is the recording interval times a power of two wide, so every
  boundary falls on a recorded tick; section k covers the ticks from start + k·w up to start + (k+1)·w.
- When the number of sections exceeds `max-sections` (default 512, an option of `OrganismController`),
  the width doubles and each pair of neighbours is added. The number of sections stays between half the
  maximum and the maximum.
- The chart's x axis follows the **timeline** (`updateMaxTick`), not the model: when the timeline
  grows, the chart grows with it. Between the tick the model has caught up to and the end of the
  timeline lies a strip without data. It is hatched as the right edge of the first attempt was, and
  where it is wide enough it carries a vertical text that explains it; where it is too narrow, the text
  is left out.

### Bands

- The **subclades** of a selected clade X are the origins of other genomes whose nearest origin above
  is one of X's.
- **One band per genome, on every level including the top**: when genome Y arises several times
  inside X, its topmost origins together form the band Y. At the top, founders with the same genome
  form one band, as in the analyzer, whose top level is the root genomes of the lineage; the single
  founder lines are reached through `<×n>`.
- The **eight heaviest** get a colour; the weight is the area of the band.
- **`other`** holds the remaining subclades and the organisms that carry X itself without lying in a
  subclade. It can be clicked: it stays in X and shows ranks 9 to 16 as bands, the rest again as
  `other`, and so on. The carriers of X remain in `other`; on the last page `other` is only that part
  and cannot be clicked. The bands of the previous pages recede into the dark tone of everything
  outside the entered clade; the answer for a page carries the origins of the bands of all earlier
  pages for that.
- **Skipping**: when a clade is entered, the server descends by itself as long as a single subclade
  carries at least `skip-threshold` (default 0.95, an option of `OrganismController`) of its parent's
  weight, and stops at the first level that really divides. The weight is the same as for ranking,
  so where a click lands does not depend on the tick shown. An explicit click on a skipped level shows
  exactly that level without skipping again.

### A genome selected directly

A genome can be selected without navigating to it — typed into the genome field or clicked in the
organism list. Its clade is then all its topmost origins together; typing and clicking show the same.
The frontend shows the path from the root down to the deepest origin under which all of them lie,
then the genome; the levels in between would differ per origin and are replaced by one row (below).
When the origins lie under different founders, the path is `all › Y`. The topmost origins are found by
walking each origin's chain upwards with a memo of the origins already visited, so shared chains are
walked once.

### Requests

All belong to `OrganismController`. The two new routes are registered before `/{tick}`, as `/ticks`
is today. While the model of a run is built for the first time, the two new routes answer
`202 Accepted` and `/organisms/{tick}` carries no origins.

**Clade answer** — `GET /visualizer/api/organisms/clades?runId=…` with:

- `path` — the steps clicked from the top, without the skipped ones, as genomes separated by `.`.
  Each step means "the origins of this genome anywhere below the step before". A genome is written as
  its full base-62 number, eleven characters of `0-9a-zA-Z`; the six characters shown everywhere are
  its end.
- `genome` — a genome selected directly: all its topmost origins.
- `organism` — the clade of one organism: it and all its descendants. The path runs to its nearest
  origin, then `#id`. A line of `<×n>` is addressed this way too. For an origin the answer comes from
  the model. For an organism that is not an origin, the organisms between it and the first origins
  below it are not in the model: they are found with `readChildIds`, read with `readOrganisms(ids)`
  and added onto the grid together with the courses of those first origins; the walk stops at ids
  above the one the model has caught up to, so the answer matches its ETag.
- `skip=false` — show a level exactly, without skipping.
- `page` — the page of `other`.

The answer carries the path with every skipped level, per band its genome, its topmost origins and
its course (means per section, the living added up to the tick caught up to), the origins of the bands
of earlier pages, `other`, and the grid (section width, first tick). A normal answer is a few
kilobytes. The request stays a GET for ETag and `304`; Jetty's header limit of 8 KB allows about 580
steps, and a `431` appears in the frontend as an error.

**Organisms of a tick** — `GET /visualizer/api/organisms/{tick}` carries, instead of
`genomeAncestors`, per organism its nearest origin, and for every origin on the chains above them the
origin above and its genome (`originAncestors`). They come from the model, 1–3 ms per tick on both
runs measured. An organism the model has not reached yet — the timeline runs up to five seconds ahead
of it — is placed from its own row: it is its own origin when its genome is new, otherwise it takes
the nearest origin of its parent; the rows of such organisms are read with `readOrganisms(ids)`. So
every organism of every tick is placed exactly. The ETag of the tick carries the state of the model.
The genome chain and the genome depth of the selected organism are its chain of origins.

**Short hashes** — `GET /visualizer/api/organisms/short-hashes?runId=…&first=c`: all short hashes of
the run that begin with the character c.

**Caching.** The clade answer and the short hashes each have a block of their own under `http-cache`
in the options of `OrganismController`: `enabled = true`, `maxAge = 0`, `useETag = true` — on by
default, unlike the other organism endpoints, because their ETag carries the state of the model and
changes exactly when the answer does. `must-revalidate` is set by `applyCacheHeaders` itself. The ETag
consists of run, state of the model and every parameter.

**Organism details** keep `lineage`: the organism chain in the organism panel is read from it.

**Removed**: `readGenomeAncestors`, the field `genomeAncestors` in `OrganismsResponseDto` and
`OrganismDetailsResponseDto` with its uses in `OrganismController` and the examples of its OpenAPI
documentation, and the colouring by genome kinship that was its only reader. The skill `analyze-run`
loses `scripts/sweep.py` and its mentions in `SKILL.md`: the script served runs older than the genome
lineage export, which the skill no longer reads.

### Frontend

- **Colouring** of organisms in the environment, on the minimap and in the organism list: from the
  origins in `/organisms/{tick}`, by walking each organism's chain of origins up to an origin of a band.
- **Shares in the path** count only organisms with `isDead = false`. The simulation records a dead
  organism once more, at the first recorded tick after its death; the model counts an organism at a
  tick only while `birth ≤ tick < death`. The path shows the share at exactly the tick shown, the chart
  the mean over a section — the numbers differ, the rule is the same.
- **Refresh**: when the existing poll of the run's end (`updateMaxTick`, every 5 s) reports that the
  run has grown, the clade answer is fetched again; an unchanged one is a `304`.
- **Panel state**: closed in a new browser. Every opening or closing by the user is remembered in the
  browser and holds in the next session. While the model of a run is built for the first time, the
  panel is shown open and stays open afterwards; this does not change what is remembered.
- **Loading**: a short message with a loading indicator and no duration. It says what is missing until
  the model is built: organisms are grey, and the chart and the genome search are not available yet.
  The rest of the visualizer works meanwhile.
- **Genome field**: `#123456` is an organism id, anything else a short hash. With the first character
  typed, the frontend fetches the short hashes beginning with it (kept per run and state of the model)
  and from the second character on filters them locally, so the completion appears without delay.
  The rest is shown dimmed only where exactly one genome begins with what has been typed; Tab takes it.
  A short hash the run does not have turns the border red. A short hash shared by several genomes —
  about three pairs on a run of 600,000 genomes, only for typed input, since every click carries the
  full hash — shows the row `<2 genomes>` in the path (below); the field does not turn red.
- **Path strip**, English like the whole frontend, no tooltips:
  - `all` stays at the top while the rest scrolls (`position: sticky`), with a thin rule beneath it
    only when rows are hidden; the strip is never lower than two rows.
  - Special rows in angle brackets, dimmed and in italics: `<more>` while paging through `other` —
    one row under X, replaced page by page; its share is what is still in `other` on this page, its
    count the subclades still left below it; a click goes back one page, a click on X to the first
    page. `<×n>` for a genome with n topmost origins — no share, no count; a click unfolds the n lines,
    each leading into its line, where Y is an ordinary band with a complete path. `<+k>` for k skipped
    levels — a click unfolds them. `<2 genomes>` for an ambiguous short hash — a click unfolds the
    genomes as rows, each leading into its clade.
  - The shares are recounted on every change of tick, on every route to it (timeline, keyboard, tick
    field, jump to the end), once the organisms of that tick have arrived — the strip and the
    environment always show the same tick.
- **Mutation marks** take the hue of the selected organism's clade; their brightness follows the
  **genome depth** of the ancestor they stem from, no longer the depth in generations: the number of
  origins between it and the selected organism, an origin counted by the same rule as everywhere
  (`genomeHash` ≠ 0 and ≠ `parentGenomeHash`, both carried by the lineage mutations). A change to
  genome 0 is not an origin, so A → 0 → A counts once. They are redrawn only when the selected organism or the clade changes,
  never on every tick — otherwise the zoomed-out view loads its environment twice per tick.

### Presentation carried over from the first attempt

The presentation of the first attempt was tried and accepted in the browser and is taken over. Its
source is the branch `visualizer-clades` — `js/ui/CladePanel.js`, the palette and colour assignment
in `js/CladeModel.js`, the mark colours in `js/EnvironmentGrid.js`, the `clade-*` rules in
`style.css`, the field in `index.html` — and the messages of its commits `fce8e5bf` and `edc7519c`.
Where it and this proposal differ, this proposal applies (list at the end of this section).

**Controls.** There is no colour-mode switch; clade colours are the only colouring. Beside the
timeline track sits a field in the style of the other fields there (`#clade-input`, placeholder
`all`). It shows the clade the colours stand for and takes one: a typed genome enters its clade,
clearing the field goes back to `all`. Clicking the genome of a row in the organism list enters its
clade. Beside the field sits the fold arrow (`#clade-fold`); folded, nothing of the panel remains above
the timeline. The state is remembered (`evochora-clade-panel-open`).

**Panel.** Above the timeline panel: left edge at the timeline track, right edge at the timeline
panel, top edge level with the top of the minimap panel. Its height is set explicitly, so that the
strip beside the chart cannot move it, and it becomes visible only once the minimap has been laid
out. Border as other panels (`1px solid #333`), no header. The chart is exactly as wide as the track
below it and shares its x-mapping; the path strip stands to its right, above the field.

**Colours.** Palette E, in this order: `#ff3b3b` `#ff9f00` `#ffe600` `#5cff3b` `#00ffc8`
`#00c2ff` `#3b5cff` `#b23bff` `#ff3bc8` `#ff3b7a`. A level of fewer than nine clades takes the
curated subset for its number, unchanged (indices into the palette):

| Clades | Indices |
|---|---|
| 1 | 3 |
| 2 | 3, 8 |
| 3 | 1, 4, 8 |
| 4 | 1, 3, 6, 8 |
| 5 | 1, 2, 4, 6, 8 |
| 6 | 0, 1, 3, 5, 7, 8 |
| 7 | 0, 1, 2, 4, 5, 7, 9 |
| 8 | 0, 1, 2, 3, 5, 6, 7, 9 |

Which clade gets which colour follows time, not size: the clades are ordered by the weighted mean of
where their population sat on the timeline, and the subset is handed out in a spread order (0, half,
1, half + 1, …), so that clades alive at the same time land far apart in the palette and a clade keeps
its colour from tick to tick. Special colours outside the palette: `other` `#8f9bb3`, everything
outside the entered clade `#38405a`, dead organisms `#555555` as before, genome 0 `#808080` as
before — the four stay distinguishable.

**Chart.** Stacked areas, x = time over the whole run, y = share of the population of each tick,
ground `#15151d`. What lies outside the entered clade stays empty — filling it read as 100 %. In the
chart the bands are drawn quieter than elsewhere: 30 % of the ground mixed into the colour and the
colour drawn 16 % towards its own grey; in the environment, the organism list and on the minimap the
colours stay as they are. Hovering a band lightens it (`rgba(255,255,255,0.26)`) and outlines it
(`rgba(255,255,255,0.75)`, 1.5 px); the pointer becomes a hand only where a click opens something. A
white 1 px line marks the tick shown.

**Mutation marks** take the hue of the selected organism's clade over the whole range of brightness,
from 0.78 lightness for the nearest to 0.22 for the farthest ancestor, with a saturation of at least
0.45.

**Not to be proposed again** (tried and rejected with the maintainer): dividing the colour circle
evenly by the number of clades, OKLCH at maximum chroma, muted palettes, hues from the parent's arc,
brightness steps as a second distinction, darkening or hiding the outside, the largest child keeping
the colour of the entered clade.

**Where this proposal differs from the first attempt:**

- `other` can be clicked; it is hatched (`rgba(0,0,0,0.30)`, 3 px lines, 7 px apart) only on the last
  page, where it cannot be opened.
- The hatched strip at the right edge now marks the part of the timeline the model has not caught up
  to, and explains itself with a vertical text where there is room.
- Levels that do not divide are skipped by weight; the path strip gains `<more>`, `<×n>`, `<+k>` and
  `<2 genomes>`, and keeps `all` at the top while scrolling.
- The top level shows one band per genome, as every other level does; the first attempt showed one
  band per founder.
- The panel is closed in a new browser and opens by itself while a run's model is built for the first
  time.
- The tree, the levels and the shares of `CladeModel.js` are replaced by the server's clade answer;
  only its palette and colour assignment remain. Colouring walks the chains of origins that
  `/organisms/{tick}` carries.
- The brightness of mutation marks follows the genome depth instead of generations, and marks are
  redrawn only when the selected organism or the clade changes.

## Open problems

Every problem known when this version was written. None of them is solved by this document.

### Parts written without the maintainer's review

- **Findings of the second architecture review, worked in by the agent alone.** They were verified
  against the code, but the maintainer has not gone through them: one commit per step
  (`autoCommitBufferSize(0)`, rollback before `close(0)`, see Storage); where the first recorded tick,
  the recording interval, the tick a step catches up to and the fork boundary come from (see Data
  source); `genomeAncestors` leaving both organism DTOs; how the organism query walks an organism that
  is not an origin; how organisms the model has not reached yet are placed, and that a provisional
  root counts in `other`; `HttpServerProcess` keeping its controllers; the corrected counts of reader
  methods and indexes; genome depth counted by the origin rule.
- **Decisions the agent took alone**: the requirement of about 40 s for the first opening; the route
  names; at most 500,000 organisms per catch-up step; `lineage` staying in the organism details.

### Costs that grow with the run without a bound

The design answers several requests by enumerating origins or organisms. On the two runs measured
(1 and 14.6 million organisms) every such request is fast. For runs that last much longer — organisms
living hundreds of millions of ticks, genomes throwing off mutants for as long — nothing bounds these
costs:

1. **Following one organism** (`organism=`) — the requirement this view began with. For an organism
   that is itself an origin the answer comes from the model. For one that is not, every descendant
   down to the next change of genome is read from the database, since the model keeps courses per
   origin only. A long, stable line can have millions of such descendants. **Unsolved.**
2. **A click on a clade** reads all direct child origins of the clade to group them into bands by
   genome. A long-lived genome that keeps producing mutants accumulates hundreds of thousands of child
   origins, and every click on it reads them all.
3. **A genome selected directly** reads all its origins and sends all its topmost origins: on the larger
   run 65,347 origins, 523 KB and up to 167 ms already. The number grows with the length of a run.
   (Accepted by the maintainer as a known limit for the runs measured, not for runs without bound.)
4. **`/organisms/{tick}`** carries, on every change of tick, the chains of origins of all organisms of
   the tick up to the root. Their size grows with the number of genome changes along the lines: 125 KB
   for 860 organisms on the smaller run; runs with thousands of changes per line would send
   megabytes per tick.
5. **The first build** holds all organisms in the heap, about 36 bytes each. Builds of several runs
   may run at the same time, and the node shares its heap with the pipeline: several gigabytes for runs
   of 100 million organisms.
6. **Completion in the genome field** fetches all short hashes beginning with the first character
   typed, about one in 62 of all genomes: 89 KB for 614,000 genomes, about 1.4 MB for 10 million.

### Gaps

7. **Swapping the file after a rebuild.** The rebuilt model is renamed into place; what happens to
   requests still reading the old file at that moment, and when the old store is closed, is not
   described.
8. **Cost of the index on `parent_id` while indexing** is not measured (see Data source).

## Measurements

Single measurements of the model as proposed, read only against the two runs, and indications, not
benchmarks. The larger run predates `death_tick` and `parent_genome_hash`: its death ticks were
synthesised (a lifespan of 200,000 to 2,200,000 ticks), its parent genomes taken from the parent's row.
Its figures for the living are therefore not representative. Server times include building the JSON;
the network and drawing in the browser are not included.

| | 1.02 million organisms, local | 14.6 million organisms, 2 billion ticks, demo server |
|---|---|---|
| origins / genomes | 636,395 / 613,994 | 474,203 / 150,525 |
| genomes with several topmost origins | 5,249 (0.9 %), at most 443 | 15,726 (10 %), at most 65,347 |
| first opening (read + build) | 1.2 s + 2.5 s = 3.7 s | 23.5 s + 14.2 s = 37.6 s |
| file after the build / in operation | 62 MB / 107–138 MB | 167 MB / 166–250 MB |
| catching up, per million organisms | 9.5 s | 6.1 s |
| catch-up step at the 5 s poll, median / max | 134 ms / 369 ms | 100 ms / 6.5 s |
| clicks, pages, a genome selected directly | ≤ 35 ms, ≤ 7 KB | ≤ 8 ms, ≤ 7 KB |
| origins of a tick | 3 ms, 125 KB (860 organisms) | 1 ms, 7 KB (20 organisms) |
| short hashes for one first character | 2 ms, 89 KB | 1–3 ms, 23 KB |

**Known limits**, accepted with the maintainer:

- A genome selected directly with very many topmost origins makes a large answer: 65,347 origins,
  523 KB, 97–167 ms on the larger run. Every normal answer is a few kilobytes.
- A single catch-up step on the larger run took 6.5 s once, cause not established (it was not a
  doubling of the grid). Nobody waits for a step; the new state appears one poll later.

## Testing aids

For manual testing in the browser, three cases get prepared data where the runs at hand do not show
them readily: `<×n>` (a genome with several topmost origins) and `<more>` (a clade with more than
eight subclades), both common in real runs, so their fakes are dropped if real data shows them; and
`<2 genomes>` (a short hash shared by two genomes), which is rare.

## Deliberately open — an exception decided by the maintainer

A proposal holds agreed solutions only. The three points below are an explicit exception: the
maintainer decided to settle them only once the implementation can be tried in the browser. They are
recorded here so that they are not lost, not because they were overlooked.

1. **Chain in the organism panel** — whether it shows the genome chain, the organism chain or both.
   The organism chain comes from the lineage of the selected organism, the genome chain from its chain
   of origins; the choice affects presentation only.
2. **Width of the organism id column** in the organism list — seven-digit ids do not fit; the column
   is to become wider at the expense of the genome hash column. This is independent of the clade view
   and concerns `main` as it is; it is listed here so that it is taken up once this proposal is
   implemented.
3. **Width of the path strip and the genome field** — somewhat wider than in the first attempt; how
   much is set in the browser.

The wording of the loading message and of the text in the hatched strip is also set in the browser.
