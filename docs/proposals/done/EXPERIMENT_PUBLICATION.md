# Experiment Publication

**Status: ACCOMPLISHED — both records are published (demo run 20260226-03114337:
[10.5281/zenodo.22081452](https://doi.org/10.5281/zenodo.22081452), 2026-08-25; run 20260402:
[10.5281/zenodo.22155607](https://doi.org/10.5281/zenodo.22155607), 2026-08-29), the working
documents under `docs/experiments/` have migrated into the records, and the index
[docs/PUBLISHED_EXPERIMENTS.md](../../../PUBLISHED_EXPERIMENTS.md) is linked from the README and the
scientific overview.**

## Purpose

Simulation runs that produced a scientific finding are published as self-contained, citable
experiment packages. A package lets anyone read the finding, re-run the analysis, and browse the
run in the visualizer — without access to the project's servers and independent of later changes
to the code base or its persisted formats. The first package is the demo-server run
`20260226-03114337` (the `GDVR` selective sweep); the second is run `20260402` (the `NOT` sweep,
archived on the external SSD).

## Where

- **Zenodo** (`zenodo.org`) hosts the packages: one record per experiment, with a DOI. Zenodo
  renders Jupyter notebooks (verified in the sandbox up to 27 MB) and plays MP4 videos inline; it
  stores everything else for download. Limits: 100 files and 50 GB per record. A corrected or
  extended package is published as a **new version** of the same record; old versions and DOIs
  stay valid.
- **The repository** holds exactly one page, `docs/PUBLISHED_EXPERIMENTS.md`: title, one paragraph
  and DOI per experiment. `docs/SCIENTIFIC_OVERVIEW.md` and the root `README.md` link to it. Nothing
  else about published experiments lives in the repository (see *Migration*).
- **The demo server** is not involved: it shows the system, not the experiments. Showing an
  experiment on the node's start page is a separate topic and not part of this proposal.

## What a record contains

| File on Zenodo | Shown inline | Content |
|---|---|---|
| `analysis.ipynb` | yes | the **only document**: question, run setup, results with figures, interpretation, limitations, provenance, how to start the package (first section) |
| `timelapse.mp4` | yes | one frame per 2.5 M-tick block over the whole run, rendered with `video lineage --clade` (variant A orange, variant B blue, unresolvable gray) from the complete raw data before sampling (807 frames, 30 fps, ≈27 s), H.264 |
| `experiment-<runId>.tar` | download | the package (below) |

The record's Zenodo description holds three sentences: what the run is, the requirement (Java 21),
and the start command. There is no README file — the notebook is the documentation.

### The package

`experiment-<runId>.tar` unpacks to one directory:

```
app/                  distribution that serves this run (bin/, lib/); includes bundle-only patches
src/                  source archive of that revision, GIT_REVISION, *.patch, unpatched jar
config/archive.conf   start configuration (dataBaseDir = ${PWD}"/data", autoStart = false)
config/evochora.conf  the base configuration it includes
analysis.ipynb        the same notebook as on the record, plus analysis/ (helper module it imports)
data/KEEP_BLOCKS.json the kept blocks
data/storage/<runId>/analytics/   complete
data/storage/<runId>/raw/         kept blocks
data/database/                    kept blocks: index database and environment chunk files
```

**Start — the only supported way:** from the unpacked directory,
`app/bin/evochora -c config/archive.conf node run`, Java 21 required, then `http://localhost:8081`.
No Docker image is part of a package.

### Sampling rule

Raw batches, environment chunks and organism-tick rows are kept for exactly the same blocks; the
blocks form a **uniform grid: every K-th block over the whole run, no event windows**. K is chosen
so that the record stays under 50 GB. Analytics are never sampled. For run `20260226-03114337`
(807 blocks of 2.5 M ticks, ≈382 MB per block): **K = 8**, 101 blocks, ≈46 GB. For run `20260402`
the existing archive (K = 50, 59 GB including two screen recordings) is re-cut to K = 60 without
the event window and without the recordings.

### The notebook

- The story the notebook tells, standing alone (no reference to the sister run): two mutually
  exclusive one-edit variants of the same regulatory switch in `MAIN_LOOP` arose spontaneously, and
  the restrictive setting displaced the permissive one completely — a completed selective sweep,
  s ≈ 0.11 per generation, invisible in every aggregate curve. Three non-trivial points carry it:
  the "reproduce more" variant lost; the winner pays a recurring error tax and fixed anyway; a clean
  two-allele race (44/44 read bodies exactly A or exactly B) emerged without design.
- Sections, exactly these: **0** abstract and how to use the package; **1** the run (facts only;
  the system is not explained — a link to the GitHub repository covers it); **2** two variants of
  one switch (figure 1: row-4 diff primordial/A/B with both loci marked); **3** the sweep (figure 2:
  clade share over time on the package's 20 M grid with logistic fit — computed from the running
  node, plus the clade→genotype validation by directly read body strips); **4** invisible in the
  aggregates (figure 3: bodied population and birth rate over the full run with the sweep window and
  the 554 M artifact marked; per-clade rate/death-cause table as the honest negative result);
  **5** interpretation ("consistent with strong selection", mechanism explicitly an ISA derivation);
  **6** limitations (single run, mechanism of the advantage not established, no formal drift test,
  origin tick unknown, 50 000-tick grid, genome hash blind to hash-0 organisms and DATA operands,
  build predates the determinism fix); **7** reader's guide to the artifacts (554 M birth step is
  one damaged individual futile-forking; frozen cohorts are conflict-loss starvation, fixed in
  current `main`; `generation_depth` resets are indexer restarts) and provenance (build, revision,
  bundle-only patch, and the fact that the analytics and the video derive from the complete run
  while raw batches, environment chunks and organism ticks are sampled). Exactly three figures.
- Computed from the package: every figure except the organism-level forensics reads the Parquet
  analytics in `data/` directly (DuckDB/pandas). The forensics section (genome extraction, traces)
  queries the running node on `localhost:8081` and is marked as requiring the started package.
- Stored **executed**, with outputs — unlike the notebooks in the repository.
- Helper code lives in `analysis/` next to the notebook (the current ad-hoc scripts, turned into a
  module).

## Execution for the first record

All steps run on the demo server; nothing large passes through the user's machine.

1. Re-cut the existing bundle `/data/app/evochora-bundles/run-20260226-03114337-gdvr-sweep/` to
   K = 8: select blocks, extract the organism-tick rows and chunk entries for those blocks into a
   fresh H2 database (linked tables over the node's H2 TCP port; finish with plain `SHUTDOWN` —
   `SHUTDOWN COMPACT` corrupted the file once), copy the selected raw batches and chunk files, keep
   the analytics. Originals stay untouched.
2. Render `timelapse.mp4` from the **original** raw data, before the cut:
   `video lineage --scale 0.5 --sampling-interval 2500000 --fps 30 --format mp4 --overlay info`
   with `--clade` seeds for the two variant clades (member genomes observed in the organism
   snapshots; the packaged app carries the renderer extension as a bundle patch, merged into main).
3. Write and execute `analysis.ipynb` against the package (the forensics section with the package
   started in a temporary container with 16 GB heap while the demo container is stopped, then
   restored).
4. Build the tar, verify it unpacks and starts on a clean directory, upload notebook, video and tar
   with the Zenodo API, fill the description, publish, record the DOI in
   `docs/PUBLISHED_EXPERIMENTS.md`.

## Migration

Documents leave `docs/experiments/` when — and only when — their content lives in a published
record. After the **first** record (demo run), `docs/experiments/DEMO_RUNS_PRE_WIPE_EVALUATION.md`
is removed: its content lives in that record's notebook. `docs/experiments/RUN_20260402_SELECTIVE_SWEEP.md`
and `docs/experiments/run-20260402-scripts/` stay until the **second** record (the SSD run) is
published, because they are still the working material for it; they are removed with that record,
their code then lives in the packages' `analysis/` modules. When the last document has migrated,
the empty `docs/experiments/` directory is removed. The index `docs/PUBLISHED_EXPERIMENTS.md` is
created with the first record and linked from `docs/SCIENTIFIC_OVERVIEW.md` and the root `README.md`.

## Prerequisites

- A personal Zenodo account and an API token on `zenodo.org` (the user provides it to the
  implementing session; it is never written to a file).
- `ffmpeg` and Python with `duckdb`, `pandas`, `matplotlib`, `nbconvert` on the machine that
  executes the notebook.
