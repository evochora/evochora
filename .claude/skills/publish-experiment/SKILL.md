---
name: publish-experiment
description: Package a finished Evochora run as a citable experiment record on Zenodo — sampled data package, executed analysis notebook, timelapse, DOI, index entry. Use when asked to publish, package, or archive a run's finding.
---

# Publishing an experiment record

A record is a self-contained, citable package of one run and one finding. Anyone can read the
finding, re-run the analysis and browse the run in the visualizer — without the project's servers
and independent of later changes to the code or its persisted formats. Two records exist; both were
built this way (`docs/PUBLISHED_EXPERIMENTS.md`). Every step below ran at least twice; the pitfalls
at the end each cost a session.

## 0 · What must be fixed before any file is touched

- The run: `runId`, the config that produced it, the build that wrote it (`GIT_REVISION`).
- The finding in one sentence, and the limitations in a short list. The notebook is written
  around these; the package is cut for them.
- Where the run lives and where the package is built. Large runs sit on the external SSD or on the
  demo server; the package should be built next to the data, never through the user's machine.

## 1 · What a record contains

| File on Zenodo | Shown inline | Content |
|---|---|---|
| `analysis.ipynb` | yes | the **only document**: question, run facts, results with figures, interpretation, limitations, provenance, how to start the package |
| `timelapse.mp4` | yes | one frame per block over the whole run, rendered from the complete raw data before sampling |
| `experiment-<runId>.tar` | download | the package below |

The Zenodo description holds three sentences: what the run is, the requirement (Java 21), the
start command. No README — the notebook is the documentation.

`experiment-<runId>.tar` unpacks to one directory:

```
app/                    distribution that serves this run (bin/, lib/); bundle-only patches included
src/                    source archive of that revision, GIT_REVISION, *.patch, unpatched jar
config/archive.conf     start configuration: dataBaseDir = ${PWD}"/data", autoStart = false
config/evochora.conf    the base configuration it includes
analysis.ipynb          the same notebook as on the record, plus analysis/ (the helper module it imports)
data/KEEP_BLOCKS.json   the kept blocks
data/storage/<runId>/analytics/   complete
data/storage/<runId>/raw/         kept blocks only
data/database/                    kept blocks only: index database and environment chunk files
```

Start — the only supported way: from the unpacked directory,
`app/bin/evochora -c config/archive.conf node run`, Java 21, then `http://localhost:8081`.
`autoStart = false` matters: without it `node run` resumes and advances the run.
No Docker image is part of a package.

## 2 · Sampling rule

Raw batches, environment chunks and organism-tick rows are kept for exactly the same blocks; the
blocks form a **uniform grid — every K-th block over the whole run, no event windows**. K is the
smallest value that keeps the record under Zenodo's 50 GB (100 files per record). Analytics are
never sampled. Reference points: 807 blocks of 2.5 M ticks at ≈382 MB → K = 8, 101 blocks, ≈46 GB;
the 271 M-tick SSD run → K = 100, 531 blocks, 41 GB.

Cutting the index database: select the organism-tick rows and chunk entries of the kept blocks into
a fresh H2 database (linked tables over the node's H2 TCP port), copy the selected raw batches and
chunk files, keep the analytics. Originals stay untouched. Finish H2 with plain `SHUTDOWN`.

**Missing analytics are regenerated, not reconstructed.** Analytics derive deterministically from
raw: run a node whose `startupSequence` lists only the analytics indexer(s), with the run's `runId`
and a fresh `consumerGroup` on the topic, and every batch is redelivered. This needs a build from the
run's era — a current `main` cannot read a database written before a proto renumbering
("Run ID not found"). Keep such an era-matched app next to the archive.

## 3 · The notebook

- Stored **executed**, with outputs — unlike the notebooks in the repository. Zero errors on a
  clean execution against the package is the acceptance test.
- Sections, in this order: **0** abstract and how to use the package; **1** the run (facts only;
  the system is not explained, a link to the repository covers it); **2** the variants or the
  event, with the genome-level evidence; **3** the dynamics over time (clade share, logistic fit,
  validation against directly read bodies); **4** what the aggregates show and do not show,
  including the honest negative results; **5** interpretation ("consistent with strong selection",
  never stronger; a mechanism is an ISA derivation, marked as such); **6** limitations; **7** the
  reader's guide to the artifacts (known indexer and conflict artifacts named as such) and
  provenance (build, revision, patches, which parts are sampled and which are complete).
- Exactly the figures the argument needs — three has been enough both times.
- Every figure except organism-level forensics reads the Parquet analytics in `data/` directly
  (DuckDB, pandas). The forensics section queries the running package on `localhost:8081` and says
  so.
- Helper code lives in `analysis/` next to the notebook as a module, not as ad-hoc scripts.
- Framing rules decided by the user: exploratory framing, no hypothesis written after the result;
  disclosure of what was not tested at the end, not scattered; provenance that explains nothing
  to the reader (reindexing, gaps that changed no number) stays out of the record.
- Markdown numbers are aligned to the computed outputs after the final execution, never typed from
  memory.

## 4 · The timelapse

Rendered from the **original** raw data before the cut — raw batches are deltas, so a frame at tick
T replays everything up to T, and the renderer materializes whole batches in heap (16 GB heap was
needed; 12 GB failed). `--sampling-interval` unset renders every batch.

```
bin/evochora video lineage --run-id <runId> --scale 0.5 --sampling-interval <block ticks> \
    --fps 30 --format mp4 --overlay info --clade <genomeHash>:<hue> --clade <genomeHash>:<hue>
```

Clade seeds are member genomes taken from the organism snapshots; without `--clade`, lineage colors
are random-walk hues from a common ancestor and do not show the clades. Re-encode the result with
`ffmpeg -crf 26` (single pass): ≈60–120 MB for ≈30 s, plays inline on Zenodo.

## 5 · Zenodo

- One record per experiment, license **CC0**, creator **Evochora Project**, in the community
  `evochora`. A corrected package is a **new version** of the same record; old DOIs stay valid.
- The community is closed for submissions. To add a record: open `record_submission_policy`
  temporarily, `POST /records/<id>/communities`, accept the request, close the policy again.
- The API token is provided by the user to the session that uploads; it is never written to a file.
- Upload from the machine with the fast line. When the package was built locally, `rsync` it to
  the demo server first and upload from there. Run the tar upload detached from the terminal with a
  log; record the tar's md5 before upload and compare it with what Zenodo reports.
- Upload notebook and video first (they are small and give the record its preview), then the tar,
  then description and metadata, then publish. Verify the record renders the notebook before
  announcing the DOI.

## 6 · After publishing

- DOI and a two-paragraph entry in `docs/PUBLISHED_EXPERIMENTS.md`: the finding, the run, the
  record. Entries are timeless (valid at one record and at ten).
- Working documents under `docs/experiments/` migrate into the record and are removed from the
  repository; their scripts become the package's `analysis/` module.
- Clean the build host: package directories, extracted trees and temporary databases go; originals
  stay. Nothing on the demo server may be deleted while a demo container serves it.

## Pitfalls that have cost sessions

- **Never delete a derived copy without diffing its tree against the claimed original.** A bundle
  once held the only analytics copy of a run; "everything is in the original" was asserted, not
  checked, and the analytics had to be regenerated overnight.
- `SHUTDOWN COMPACT` corrupted an H2 file once. Use plain `SHUTDOWN`.
- A running node re-creates `data/queue`, `data/topic` and `indexdb.trace.db` inside the package;
  tar from a clean tree, and re-clean if the package was started for review after tarring.
- Killing a process by a command-line pattern matched the session's own shell. Match only
  processes whose `argv[0]` ends in `/bin/java`.
- Environment chunks need ≥8 GB heap to serve; on the demo server the package is only viewable
  with the demo container stopped, then restored.
- A run recorded with a build whose pause/resume was not invariant reproduces by seed only up to
  its first resume. State that boundary in the limitations rather than claiming full replay.
