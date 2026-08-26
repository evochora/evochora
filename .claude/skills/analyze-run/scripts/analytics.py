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
    """Return one metric at the given LOD, ordered by tick."""
    return duckdb.sql(
        f"SELECT * FROM read_parquet('{_glob(analytics_dir, metric, lod)}') ORDER BY tick"
    ).df()

def load_population(analytics_dir: Path, lod: int = 4) -> pd.DataFrame:
    """Population metrics plus the two derived series used throughout.

    ``bodied``  – living organisms whose genome hash is non-zero: the sum of the
                  per-genome counts in ``genome.genome_data`` (the plugin skips
                  hash-0 organisms). ``alive_count`` counts every living organism,
                  so ``alive_count - bodied`` is the hash-0 cohort.
    ``births``  – first difference of ``vital_stats.total_born`` per LOD step.
    """
    pop = load_metric(analytics_dir, "population", lod)
    genome = load_metric(analytics_dir, "genome", lod)
    genome["bodied"] = genome["genome_data"].map(
        lambda s: sum(json.loads(s).values())
    )
    vital = load_metric(analytics_dir, "vital_stats", lod)
    vital["births"] = vital["total_born"].diff()
    df = pop.merge(genome[["tick", "bodied", "shannon_index"]], on="tick")
    return df.merge(vital[["tick", "total_born", "births"]], on="tick")
