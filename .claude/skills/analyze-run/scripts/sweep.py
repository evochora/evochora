"""Clade membership and sweep statistics.

A sweep is measured as the share of living bodied organisms whose genome
descends from a clade-founding genome in the run's lineage tree. Clade roots
are run-specific inputs (typically the children of the run's primordial
genome); mapping a clade to a genotype must be validated on directly read
bodies before shares are interpreted as genotype frequencies.
"""
import math

import numpy as np
import pandas as pd

_B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

def short(genome_hash: int) -> str:
    """The 6-character base-62 label the analytics use for a genome hash."""
    n = int(genome_hash)
    if n < 0:
        n += 1 << 64
    out = ""
    for _ in range(6):
        out = _B62[n % 62] + out
        n //= 62
    return out

def build_tree(snapshots: dict) -> dict:
    """Merge the genome lineage of all snapshots into one int->int map.

    The organism snapshot endpoint calls the field ``genomeAncestors`` on current
    builds and ``genomeLineageTree`` on pre-#103 builds; both map a genome hash to
    its parent genome hash (the primordial genome maps to None).
    """
    tree = {}
    for snap in snapshots.values():
        mapping = snap.get("genomeAncestors") or snap.get("genomeLineageTree") or {}
        for child, parent in mapping.items():
            tree[int(child)] = None if parent is None else int(parent)
    return tree

def in_clade(genome_hash: int, root: int, tree: dict, _memo=None) -> bool:
    """True if ``root`` lies on the ancestral path of ``genome_hash``."""
    memo = _memo if _memo is not None else {}
    path = []
    g = genome_hash
    result = False
    while g is not None:
        if g == root:
            result = True
            break
        if (g, root) in memo:
            result = memo[(g, root)]
            break
        path.append(g)
        g = tree.get(g)
    for p in path:
        memo[(p, root)] = result
    return result

def clade_shares(snapshots: dict, tree: dict, roots: dict) -> pd.DataFrame:
    """Per snapshot tick: bodied count and each named clade's share.

    ``roots`` maps a column name to the clade-founding genome hash.
    """
    memo = {}
    rows = []
    for tick in sorted(snapshots):
        orgs = [o for o in snapshots[tick]["organisms"]
                if not o["isDead"] and int(o["genomeHash"]) != 0]
        n = len(orgs)
        row = {"tick": tick, "bodied": n}
        for name, root in roots.items():
            c = sum(in_clade(int(o["genomeHash"]), root, tree, memo) for o in orgs)
            row[name] = c
            row[f"share_{name}"] = c / n if n else math.nan
        rows.append(row)
    return pd.DataFrame(rows)

def logistic_fit(ticks, shares):
    """Least-squares line through logit(share) over ticks.

    Returns (slope per tick, intercept, fitted shares). Only the polymorphic
    phase (0.01 < share < 0.99) enters the fit: outside it the logit is
    undefined or dominated by relict individuals, and the near-fixation tail
    would flatten the slope without carrying information about selection.
    """
    t = np.asarray(ticks, dtype=float)
    s = np.asarray(shares, dtype=float)
    mask = (s > 0.01) & (s < 0.99)
    logit = np.log(s[mask] / (1 - s[mask]))
    slope, intercept = np.polyfit(t[mask], logit, 1)
    fitted = 1 / (1 + np.exp(-(slope * t + intercept)))
    return slope, intercept, fitted
