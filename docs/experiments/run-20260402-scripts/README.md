# Analysis scripts for RUN_20260402_SELECTIVE_SWEEP.md

These are the ad-hoc Python scripts used for the analysis described in
[`../RUN_20260402_SELECTIVE_SWEEP.md`](../RUN_20260402_SELECTIVE_SWEEP.md). They are kept next to
the experiment document as a record of the method, not as maintained tooling: no tests, minimal
error handling, and they were written for one run. The reusable ideas (lineage walk, genome
extraction and diff, death-cause windows, per-organism traces) are candidates for a notebook
chapter on organism-level forensics or an `inspect` CLI subcommand.

## Requirements

- Python 3 (standard library only)
- a running Evochora node serving the run (`bash build/install/evochora/bin/evochora -c config/local.conf node run`)
- `protoc` on the PATH (for `genome.py`, which decodes environment regions) and the repository's
  `src/main/proto` directory

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `EVOCHORA_BASE_URL` | `http://localhost:8081` | node HTTP endpoint |
| `EVOCHORA_RUN_ID` | the run analysed in the document | run to query |
| `EVOCHORA_ANALYSIS_DIR` | `./analysis-data` | where downloads and intermediate pickles go |
| `EVOCHORA_PROTO_DIR` | `../../../src/main/proto` relative to the scripts | proto contracts for `protoc --decode` |

## Scripts

| Script | Purpose |
|---|---|
| `fetch_snapshots.py START STOP STEP [...]` | download `GET /visualizer/api/organisms/{tick}` into `snap/<tick>.json` |
| `snapshots_load.py` | parse all downloaded snapshots into `snaps.pkl` (organism lists + merged genome lineage tree); also provides `short()` (6-char base62 genome label) |
| `top_genomes.py` | most common genome hashes per sampled tick |
| `clade_share.py` | ancestry chain of a genome and the population share of its clade over time (edit the 6-char label at the top) |
| `genome.py TICK ORGANISM_ID` | owned cells of an organism relative to its initial position (environment region via API + `protoc`) |
| `genome_diff.py TICK:ID TICK:ID ...` | body-restricted, DATA-free, label-normalised genome diffs along a list of organisms (same normalisation as `GenomeHasher`) |
| `row_dump.py TICK ROW ID [ID ...]` | dump one body row including DATA operands, with opcode names |
| `deaths_window.py START STOP OUT.pkl` | dense 100-tick snapshots of a window, keeping dead organisms (final energy/entropy → death cause) |
| `trajectory.py ID START STOP STEP` | energy/entropy of one organism over time |
| `ip_sample.py ID START STOP STEP` | histogram of executed body rows / opcodes for one organism (where does it spend its time) |
| `ip_sequence.py ID START STOP STEP` | per-sample last instruction, IP and DP relative to the body, call depth, cost |

Typical order: `fetch_snapshots.py` → `snapshots_load.py` → `top_genomes.py` / `clade_share.py` →
`genome_diff.py` on lineage members → `row_dump.py` for the affected row → `deaths_window.py`,
`trajectory.py`, `ip_sample.py`, `ip_sequence.py` for the phenotype.
