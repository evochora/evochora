---
name: publish-experiment
description: Package a finished Evochora run as a citable experiment record on Zenodo — sampled data package, executed analysis notebook, video, DOI, index entry. Use when asked to publish, package, or archive a run's finding.
---

# Publishing an experiment record

A record is a self-contained, citable package of one run and one finding. Anyone can read the
finding, re-run the analysis and browse the run in the visualizer — without the project's servers
and independent of later changes to the code or its persisted formats. Every record so far
(`docs/PUBLISHED_EXPERIMENTS.md`) was built this way. Every step below ran at least twice; the
pitfalls at the end each cost a session, and the rules of section 3 and section 7 each cost a
round of iteration before they were written down.

## 0 · What must be fixed before any file is touched

- The run: `runId`, the configuration that produced it, the build that wrote it (`GIT_REVISION`).
- The finding in one sentence, and the chain of evidence that carries it, as a numbered list of
  links. The notebook is written along this chain and nothing else; the package is cut for it.
- Where the run lives and where the package is built. Large runs sit on the external SSD or on the
  demo server; the package is built next to the data, never through the user's machine.

## 1 · What a record contains

| File on Zenodo | Shown inline | Content |
|---|---|---|
| `analysis.ipynb` | yes | the **only document**: question, run facts, results with figures, interpretation, limitations, provenance, how to start the package |
| `<clip>.mp4` | yes | a video of the run rendered from the complete raw data before sampling: the whole run, or the window of the event with some lead |
| `experiment-<runId>.tar` | download | the package below |

The Zenodo description holds three sentences: what the run is, the requirement (the Java version
the packaged build was compiled for, which `languageVersion` in the `build.gradle.kts` of `src/`
names), the start command.

`experiment-<runId>.tar` unpacks to one directory:

```
README.txt              what is in the directory, the requirements, the two commands, the version DOI, the licences
app/                    distribution that serves this run (bin/, lib/, assembly/ with the programs)
src/                    source archive of that revision and GIT_REVISION
config/evochora.conf    the configuration shipped with the build, unchanged
config/run.conf         includes evochora.conf and sets only what the run had different (see below)
config/fork.conf        where the mechanism needs the execution tick by tick: serves data-fork/ (see below)
analysis.ipynb          the same notebook as on the record, executed
analysis/               the helper module it imports, requirements.txt, and analysis/data/ with tables that are data
stills/, figures/       the frames the notebook shows, and its figures
data/KEEP_BLOCKS.json   the kept blocks
data/storage/<runId>/analytics/   complete
data/storage/<runId>/raw/         kept blocks only
data/database/                    kept blocks only: index database and environment chunk files
data-fork/              a fork of the run that recorded every tick of the window in which the mechanism runs
```

**The configuration of the run.** `run.conf` is written by comparing the options the run recorded
in its own metadata (`resolvedConfigJson`) with the defaults of the packaged build, resolved
without substitution (pyhocon, `resolve=False`); only the differences go into `run.conf` (world
shape, founders, source roots pointing into `app/assembly`), everything else comes from the
unchanged `evochora.conf`. A record that states another world size than the run had is a
corrected version waiting to happen.

**The two commands**, in README, `run.conf` and the notebook, and the only supported ways to use
the package:

```
app/bin/evochora -c config/run.conf node show         serves the archived run on http://localhost:8081
EVOCHORA_OPTS="-Dpipeline.dataBaseDir=$PWD/data-rerun" app/bin/evochora -c config/run.conf node run
                                                      runs the same simulation again into ./data-rerun
```

The second leaves `data/` untouched (system properties override the file); the run is
deterministic for its seed. No Docker image is part of a package.

**The distribution** is built fresh for the package (`gw installDist`) and its file list compared
with the build's: a copy taken from a directory the temp cleaner had thinned lacked the primordial
programs once. Start probe with both commands before the tar.

**`analysis/requirements.txt`** pins the versions the notebook was executed with and names the
Python version that actually ran; no minimum version is claimed that was not tried.

**What the package cannot show does not go into the record.** A still is rendered at a kept tick
and its caption names the region in world coordinates, so that the visualizer shows the same
place; a number the analytics cannot reproduce is not written. The trace recorder is working
material and nothing of it goes into a record.

**The mechanism is shown by a fork, not by a trace.** Where the finding rests on what the program
does at particular ticks, the package carries a fork of the run over that window, recorded with
the detailed profile, so that the reader follows the organism instruction by instruction in the
visualizer: `node fork --run <runId> --from <t0> --to <t1> --profile detailed --storage fork-source`
with a `pipeline.resources.fork-source` resource pointing at the run and `pauseTicks = [<t1>]`, so
that the fork stops where the window ends; the services are stopped with
`POST /pipeline/api/stop` and the node with SIGTERM; `queue/`, `topic/` and the trace database are
removed before the tar. `config/fork.conf` serves it with
`node show`, the README names the command next to the run's. The fork's index holds no mutation
from before its window; the notebook says so where it reads the fork. The mechanism itself is
derived from the program and the bodies in the text, and the fork is where the reader checks it.

## 2 · Sampling rule

Raw batches, environment chunks and organism-tick rows are kept for exactly the same blocks; the
blocks form a **uniform grid — every K-th block over the whole run, no event windows**. K is the
smallest value that keeps the record under Zenodo's 50 GB (100 files per record); measure the
block sizes and leave margin (K = 7 gave 41.7 GB where K = 6 would have been 47 GB). Analytics are
never sampled. Reference points: 807 blocks of 2.5 M ticks at ≈382 MB → K = 8, 101 blocks, ≈46 GB;
the 271 M-tick SSD run → K = 100, 531 blocks, 41 GB; 3 552 blocks of 100 000 ticks → K = 7,
508 blocks, 41.6 GB.

Cutting the index database without a node: the build's H2 jar, the source opened read-only
(`ACCESS_MODE_DATA=r`), `SCRIPT NODATA` for the DDL, a `LINKED TABLE` per table, and per kept
block one `INSERT … WHERE TICK_NUMBER BETWEEN a AND b` over the primary key; tables `METADATA`
and `ORGANISMS` whole, `ORGANISM_TICKS`, `ORGANISM_TICK_STATS` and `ENVIRONMENT_CHUNKS` for the
kept blocks; the chunk files `blobs/env-chunks/<SCHEMA>/…/chunk_<firstTick>.pb.zst` copied for the
same blocks. Originals stay untouched. Finish H2 with plain `SHUTDOWN`. The H2 tools take only
absolute paths to the database; this holds for the notebook's export of the organisms table too.

**Missing analytics are regenerated, not reconstructed.** Analytics derive deterministically from
raw: run a node whose `startupSequence` lists only the analytics indexer(s), with the run's `runId`
and a fresh `consumerGroup` on the topic, and every batch is redelivered. This needs a build from the
run's era — a current `main` cannot read a database written before a proto renumbering
("Run ID not found"). Keep such an era-matched app next to the archive.

**Reproducibility test**, part of every record: `node run` from the package for a few million
ticks, the batches compared tick for tick with the original (protobuf chunks; `simulation_run_id`
and `capture_time_ms` excluded; file checksums are not enough). The result goes into the
Technical Validation. A run recorded with a build whose pause/resume was not invariant reproduces
by seed only up to its first resume; state that boundary in the limitations instead.

## 3 · The notebook

**Written from the chain of evidence, not accreted from review points.** First the chain in
numbered links, then one figure per link, then the text that connects them; everything the chain
does not need is absent. Every figure and every table has a sentence that says which link needs
it; what has no such sentence is removed, and a table that repeats a figure in numbers is removed.
A notebook that grew by adding a line for every reviewer's point reads as what it is and is
rebuilt from the chain, not patched. The one exception is the appendix on evolutionary activity
below: it is the same in every record, so that records can be compared, and is not part of the
chain.

- Stored **executed**, with outputs — unlike the notebooks in the repository. Zero errors on a
  clean execution against the package is the acceptance test; after every execution the numbers in
  the markdown are checked against the cell outputs, the ones in rendered HTML tables included.
- Structure, the roles of a Data Descriptor under the record's own numbering: **0** title, an
  abstract of at most 170 words, three lines on how to use the package; **1** background and
  methods: the platform in two sentences, a glossary of the terms the record uses, a table of the
  run's facts from its resolved configuration, how the data were recorded and what the notebook
  derives, with a row for the run's class after Bedau that points to the appendix; **2** the state
before the event; **3** the event with the genome-level evidence;
  **4** the dynamics over time (share per kept block, logistic fit, validation against directly
  read bodies, the map that shows clumping); **5** how the carriers reproduced against the others;
  **6** what changed in the world; **7** the line of descent and the alternatives examined;
  **8** interpretation; **9** limitations; **10** data records (a table: path, content, format,
  coverage, size), technical validation, usage notes, code availability, provenance; the
  disclosure sentence last; **A** the appendix on evolutionary activity (below).
- **The appendix on evolutionary activity**, in every record, computed from the package with
  `evolutionary_activity.py` of the `analyze-run` skill, copied into `analysis/`; that skill's
  section "Evolutionary activity and the class after Bedau" defines what is computed. It holds the
  class as a sentence with the Theil-Sen slopes it rests on, in generations and in ticks, and
  where the two give different classes it says so with the factors by which generation time and
  mean lifetime changed; the bracket in which the copy channel founds components, and whether the
  class depends on it; the parameters (a' percentiles, band width, number of shadow seeds); a
  short table of the window values; and two figures, mean activity in both units and new activity,
  each with the range of the shadow. It says that MODES complexity is absent and why, in the words
  of the `analyze-run` section. Where the appendix stands against the finding — no new activity
  above the shadow in the window of a sweep — the interpretation says so.
- Observation, derivation, interpretation and speculation are kept apart and named as such. Where
  nothing was intervened, the wording is "consistent with", in the title, the abstract, the
  headings and the interpretation alike. In a stationary population a lineage that rises has more
  children per birth by arithmetic, so a comparison of carriers against the rest never shows the
  cause of a sweep, only the pattern of the advantage; it is accompanied by a check of hindsight,
  the growth every lineage founded in the same period reached.
- Every number with its base population and n; intervals are bootstrap intervals over parents,
  because siblings share a place and a time; the slope of one trajectory of one run carries no
  sampling uncertainty; a generation time is given as mean and as median, because the two differ.
- A comparison within a parent, between its children of two kinds, is made at equal birth rank
  and with both weightings, every child equal and every parent equal, and the text gives both:
  the kinds are not born in the same order, a later child of either kind is born into a
  surrounding its elder siblings have harvested, and the two weightings differed by half the
  effect once. A comparison at equal rank is conditioned on what happened to the earlier
  children; the text says so.
- What a stratification expects and what is observed come from the same sample and the same
  estimator, or they are not compared: an expectation computed on the sample with the measured
  density, set against an observation over the whole window, reversed a finding once, and the
  reversal was an artefact.
- A quantity read from the nearest kept tick stands before or after the event it is compared
  with, never at it; a comparison that rests on it is repeated with each side alone, and the text
  names the bias of each.
- A finding without intervention is an observational finding: what a stratification implies is
  "expected from the strata", never "predicted"; "testable predictions" are the experiments the
  interpretation proposes at its end, and nothing else.
- A before-and-after figure is reported only where the notebook explains the difference; a number
  that is shown and not explained is a gap, not an observation.
- The platform's own terms, taken from the program files of the run's revision inside the package
  (procedure names, paths), never from `main`; no invented vocabulary.
- A short title inside the figure, the caption as a sentence below it; tables rendered as tables
  (`display`), not printed as text; no printed line longer than the page. The notebook stays under
  1 MiB, or Zenodo shows no preview (`PREVIEWER_MAX_FILE_SIZE_BYTES` of InvenioRDM's previewer,
  which Zenodo does not raise for notebooks): frames are embedded pixel for pixel at a grid the
  figure can hold, never resampled.
- Diagrams — lines, bars, areas — are embedded as palette PNGs of a few dozen colours at the size
  they are shown (`notebook_png` in `evolutionary_activity.py`); this takes a diagram to about a
  quarter of its size, and each one is compared with its full version side by side before it goes
  in. Stills and frames of the world never: a palette changes the colours of their markers. The
  full versions go into `figures/`.
- Every figure except organism-level forensics reads the Parquet analytics in `data/` directly
  (DuckDB, pandas). The sections that query the running package on `localhost:8081` say so and
  cache their requests under `analysis-cache/`.
- Helper code lives in `analysis/` next to the notebook as a module, including the figures; a
  notebook cell is a few readable lines. The notebook is generated by a builder script kept with
  the record's sources in the repository, so that a version is rebuilt from one file; the builder
  stops on a passage it does not find rather than build an older text.
- Framing rules decided by the user: exploratory framing, no hypothesis written after the result;
  disclosure of what was not tested at the end, not scattered; provenance that explains nothing
  to the reader stays out.
- A sample from the trace recorder is working material, never evidence in the record; it may
  confirm what the code and the population data show, and no more.

## 4 · The video

Rendered from the **original** raw data before the cut — raw batches are deltas, so a frame at tick
T replays everything up to T, and the renderer materializes whole batches in heap (12 GB heap for
a 7 680 × 4 320 world; 16 GB was needed for the largest run). `--sampling-interval` is the block
length for one frame per block.

```
bin/evochora video lineage --run-id <runId> --start-tick <t0> --end-tick <t1> --sampling-interval <block ticks> \
    --scale 0.25 --glow-size 0.2 --fps 25 --format mp4 --crf 26 --overlay info,logo --clade <genomeHash>:<hue>
```

The logo overlay is part of every clip. Clade roots are every genome hash of every member of each group, taken from the
organisms table and passed through an argument file, not a few seeds: with seeds only, members
whose hash differs from the seeds' fall out of their clade's colour. Without `--clade`, lineage
colours are random-walk hues from a common ancestor and do not show the clades. A preview with coarse
sampling goes to the user for approval, and its size per frame gives the final size (≈60–120 MB
plays inline on Zenodo). Stills at cell resolution come from the `exact` renderer at scale 1, cut
to a region of the world and enlarged by an integer factor; the caption states the region and the
pixels per cell as the command made them.

## 5 · Quality assurance, in this order and once

1. The author's own pass over the whole notebook with three questions per paragraph, number and
   figure: does the chain need it, is it explained, can the package reproduce it.
2. One reviewer as a referee of a data publication, with the executed HTML and the package only,
   who gives the verdict on the whole first (one story, same claim at the same strength
   everywhere, observation apart from interpretation) and the findings by weight after.
3. The mechanical check, a script kept with the record's sources (`mechanical_check.py <package>
   <html>`): zero errors in the executed notebook, its size under 1 MiB, the headings of the
   rendered HTML against the expected list, the forbidden words (invented terms, a word the
   project does not use), figures captioned in reading order and every figure referred to
   captioned, every path named exists in the package, the class sentence of the appendix against
   its slopes, the abstract at most 170 words; and by hand every number in the markdown against
   the outputs.

Findings are not worked in one by one. The affected section is rewritten from the chain, and every
change is checked against all rules written down before, not only against the rule that caused it.
The round ends when a pass brings no finding with substance.

## 6 · Zenodo

- One record per experiment, license **CC0**, creator **Evochora Project**, in the community
  `evochora`. A corrected package is a **new version** of the same record; old DOIs stay valid.
- Metadata are taken from the previous record and compared field by field: upload type dataset,
  version, license, access, language, the common keywords plus two on the record's topic, related
  identifiers (the repository as `isSupplementedBy` software, evochora.org as `references`, the
  earlier records as `references` with `resource_type` dataset), the community. Earlier records are
  cited by their **concept DOI**, which stays the same over all versions, in the notebook, the
  metadata and `docs/PUBLISHED_EXPERIMENTS.md`; the README inside the package names the
  **version DOI**, because it belongs to that version. The version DOI is reserved when the draft
  is created and is written into the README before the tar is built.
- The community is closed for submissions. To add a record: open `record_submission_policy`
  temporarily, `POST /records/<id>/communities`, accept the request, close the policy again.
- The API token is provided by the user to the session that uploads; it is never written to a file.
  On the demo host it is passed through stdin of the ssh session, not on a command line.
- Upload from the machine with the fast line. On a slow line the package goes to the demo server
  first with a resumable, self-retrying `rsync` (`--partial --append-verify`, the node's `queue/`,
  `topic/`, `indexdb.trace.db`, caches and the tar excluded), its tree is compared with the
  original by checksum (`rsync -anc`), and the tar is built there. A file that still changes costs
  a new tar and a new upload: notebook, README and DOI are final before the tar is built.
- Order: create the draft with the metadata, upload notebook and video first (they are small and
  give the record its preview), then the tar detached with a log; compare every checksum Zenodo
  reports with the file's; let the user check the preview; publish only on the user's go; add the
  record to the community after publishing.

## 7 · After publishing

- DOI and a two-paragraph entry in `docs/PUBLISHED_EXPERIMENTS.md` with the concept DOI: the
  finding, the run, the record. Entries are timeless (valid at one record and at ten).
- Working documents under `docs/experiments/` migrate into the record and are removed from the
  repository; their scripts become the package's `analysis/` module.
- Clean the build host and the demo host: package directories, extracted trees, temporary
  databases, draft and upload responses, helper scripts and logs go; originals stay. Nothing on
  the demo server may be deleted while a demo container serves it.

## Pitfalls that have cost sessions

- **Never delete a derived copy without diffing its tree against the claimed original.** A bundle
  once held the only analytics copy of a run; "everything is in the original" was asserted, not
  checked, and the analytics had to be regenerated overnight.
- `SHUTDOWN COMPACT` corrupted an H2 file once. Use plain `SHUTDOWN`.
- A running node re-creates `data/queue`, `data/topic` and `indexdb.trace.db` inside the package;
  tar from a clean tree, and re-clean if the package was started for review after tarring.
- Killing a process by a command-line pattern matched the session's own shell. Match only
  processes whose `argv[0]` ends in `/bin/java`, with a pattern that names the package.
- Environment chunks need ≥8 GB heap to serve (the distribution's `bin/evochora` sets it); on the
  demo server the package is only viewable with the demo container stopped, then restored.
- A notebook built by accretion from two rounds of reviews was a patchwork that had to be rebuilt
  from the chain; a comparison presented as "decisive" showed only the sweep in other numbers; an
  energy budget counted the energy handed to a child as consumed; an explanation for excess shells
  was written without reading the death handler. Each of these is a rule in section 3 now.
- The Zenodo preview renders no notebook above 1 MiB; a figure of two frames resampled onto a
  non-integer grid weighed 1.2 MB on its own.
- The scratchpad under `/tmp` is cleaned by age; a worktree, a build installation and a venv were
  lost that way. Nothing that must survive a pause lives only there.
- A within-parent comparison averaged over all children set the first child, mostly of one kind,
  against the later ones of the other, and read as "no difference"; an expectation from the
  birthplace sample was compared with an observation over the whole window and read as "half";
  a square chosen after the result was presented as the measure. Each of these was found by a
  reviewer, and each is a rule in section 3 now.
