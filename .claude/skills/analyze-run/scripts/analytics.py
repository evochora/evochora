"""Loaders for the run's analytics Parquet files.

All functions read the Parquet analytics under ``data/storage/<runId>/analytics``
directly with DuckDB; nothing here needs the started node. Ticks are returned in
raw units (1 tick = 1 simulation step); figures divide by 1e6 for display.
"""
import json
from pathlib import Path

import duckdb
import pandas as pd

def _glob(analytics_dir: Path, metric: str, lod: int) -> str:
    return str(analytics_dir / metric / f"lod{lod}" / "**" / "*.parquet")

def load_metric(analytics_dir: Path, metric: str, lod: int = 4) -> pd.DataFrame:
    """Return one metric at the given LOD, ordered by tick.

    ``union_by_name`` keeps runs readable whose Parquet files were written by
    different builds: columns missing from older files arrive as NULL instead of
    raising a schema mismatch.
    """
    return duckdb.sql(
        f"SELECT * FROM read_parquet('{_glob(analytics_dir, metric, lod)}', "
        f"union_by_name=true) ORDER BY tick"
    ).df()

def load_population(analytics_dir: Path, lod: int = 4) -> pd.DataFrame:
    """Population metrics plus the two derived series used throughout.

    ``bodied``  – living organisms whose genome hash is non-zero, read from the
                  ``population.bodied_count`` column. ``alive_count`` counts every
                  living organism, so ``alive_count - bodied`` is the hash-0 cohort.
                  Runs written before that column existed fall back to summing the
                  per-genome counts in ``genome.genome_data`` (the genome plugin
                  skips hash-0 organisms, so the sum is the same quantity).
    ``births``  – first difference of ``vital_stats.total_born`` per LOD step.
    """
    pop = load_metric(analytics_dir, "population", lod)
    genome = load_metric(analytics_dir, "genome", lod)
    vital = load_metric(analytics_dir, "vital_stats", lod)

    pop["bodied"] = pop["bodied_count"] if "bodied_count" in pop.columns else pd.NA
    if pop["bodied"].isna().any():
        derived = genome.set_index("tick")["genome_data"].map(
            lambda s: sum(json.loads(s).values())
        )
        pop["bodied"] = pop["bodied"].fillna(pop["tick"].map(derived))

    vital["births"] = vital["total_born"].diff()
    df = pop.merge(genome[["tick", "shannon_index"]], on="tick")
    return df.merge(vital[["tick", "total_born", "births"]], on="tick")
