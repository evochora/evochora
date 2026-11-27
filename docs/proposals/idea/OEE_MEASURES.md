# Measures of Open-Ended Evolution on Evochora's Data

**Status: IDEA — not decided. A list of what the standard measures of the field need, what the
recorded data provide, and what is missing; open questions at the end.**

The question whether complexity keeps arising in a run is the one the field asks of every system,
and it asks it with a small set of measures: Bedau's evolutionary activity statistics with a neutral
shadow model (Bedau, Snyder & Packard 1998; Channon 2003), and the MODES metrics of change, novelty,
complexity and ecology (Dolson, Vostinar, Wiser & Ofria 2019). The activity statistics have been
computed for one published record ([First Results](../../SCIENTIFIC_OVERVIEW.md#4-first-results)),
the MODES metrics for none. This document records which of their inputs the recorded data hold and
which instruments would have to be built, so that the work can be planned when a run is to be
measured.

## What the measures need

| Input | Needed by | State in Evochora |
|---|---|---|
| Abundance of every component (genome) at every point in time | activity statistics, MODES change/novelty/ecology | `genome_population` holds every genome with living carriers at every recorded tick; resolution is the tuning profile |
| Descent of components | MODES persistence filter, phylogenetic metrics | `genome_lineage` holds every genome with its parent genome, unsampled; `births` every organism with its parent |
| Content of each component | novelty and complexity beyond identity | no hash-to-body table; bodies are read from a running node or the raw record; a genome whose carriers lived between two recordings has no recorded body unless the window is re-recorded with `node fork` |
| Death of every organism | shadow model, persistence filters, life tables | `deaths` holds every death with its tick and its cause, unsampled; joined with `births` by the organism it is the life table of the run |
| A complexity measure per component | MODES complexity | none; Avida's knockout measure needs an isolated assay, which does not exist |
| Diversity across persistent components | MODES ecology | `genome_diversity` has a Shannon index without persistence filter |
| Replicates under varied conditions | any statistical claim | runs are deterministic per seed; a series of seeds is scripted by hand |
| Export to the field's formats | reuse of existing toolkits (Empirical, phylogeny standards) | Parquet and DuckDB; no adapter, no Newick |

## What would have to be built

1. **A metrics notebook or module** that computes the activity statistics and the MODES metrics of
   change, novelty and ecology from `genome_population`, `genome_lineage` and `births`, with the
   persistence filter reimplemented (it exists only in C++ in Empirical). Post-processing, no
   change to the runtime.
2. **A complexity measure per newborn**: body size, number of distinct instructions, compressed size
   of the body. An analytics plugin at birth, small, because the body is read at birth anyway.
3. **An isolated assay of one organism**: a test world built from an organism's recorded body and
   registers, with a fixed energy supply, in which one cell can be changed and the organism's
   reproduction observed. This is the instrument Avida's knockout complexity rests on. The restore
   path already rebuilds organisms from snapshots; the assay would be a headless command on top of
   it. The mutational robustness assay among the ideas describes the same instrument from the side
   of the fitness landscape.
4. **A runner for series of seeds**, so that replicates are a configuration rather than a script.
5. **Export adapters** to the phylogeny standards of the field and to Newick.

## Open questions

- Which complexity measure is defensible for a body of typed molecules: structural (size, distinct
  instructions), informational (compressed size), or functional (knockouts in the assay)? The field
  has not settled this for any system.
- What the shadow model is for self-replicating organisms: a neutral model has to replicate the
  birth and death process without selection, and the births and the deaths table give the process;
  the choice of neutral rule is a decision.
- At which recording interval the abundance series is fine enough for the activity statistics, and
  whether windows have to be re-recorded for it.
