"""Evolutionary activity after Bedau, Snyder & Packard (1998) with a neutral shadow, and MODES
change, novelty and ecology after Dolson et al. (2019), for one Evochora run.

Inputs, all read only:
  - the organisms table of the run's index database (exported once to CSV with the H2 shell)
  - analytics `births` (lod0): the variation class of every birth, which founds components
  - analytics `population` (lod0): the independent count of the living, for the plausibility check

A component is a genome since the last birth that a mutation plugin changed: a birth whose
variation class is a mutation kind founds a new component, every other bodied birth inherits its
parent's. The copy channel (class `no_event`) founds none; the bracket run lets it found one too,
which overcounts with every genome hash change outside the code.

Needs duckdb, numba, numpy, pandas and matplotlib (in a venv) and Java for the export.

Usable as a module (an experiment notebook imports it) or from the command line:
  python -I evolutionary_activity.py --data-dir <dataBaseDir> --run-id <runId or prefix>
      --h2-jar <h2.jar> --out <dir> [--sweeps 130-200,230-330] [--label <name>]
"""
import argparse
import json
import subprocess
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

import duckdb
import numba as nb
import numpy as np
import pandas as pd

GRID = 10_000             # activity raster in ticks; the analytics lod0 interval
WINDOW = 10_000_000       # length of an evaluation window in ticks
BAND_PERCENTILES = (99, 95)   # a' from the shadow; the first is the reading, the second the probe
BAND_WIDTH = (0.8, 1.2)   # the band [a0, a1] around a'
MODES_FILTERS = (10, 50)  # persistence filter lengths in generation times
SEED = 20261102            # first shadow seed; replicate r uses SEED + r
SHADOW_REPLICATES = 10
MUTATION_CLASSES = ("duplication", "deletion", "instruction_insertion", "label_insertion",
                    "substitution", "multiple", "other")
NEVER = np.iinfo(np.int64).max // 4


# ---------------------------------------------------------------------------------------- input

def run_dir(data_dir: Path, run_id: str) -> Path:
    """The storage directory of the run whose id starts with ``run_id``."""
    hits = [p for p in (data_dir / "storage").iterdir() if p.name.startswith(run_id)]
    if len(hits) != 1:
        raise SystemExit(f"run id {run_id!r} matches {len(hits)} runs under {data_dir}/storage")
    return hits[0]


def export_organisms(data_dir: Path, full_run_id: str, out_csv: Path, h2_jar: Path,
                     java: str = "java") -> Path:
    """Writes the organisms table to ``out_csv`` with the H2 shell, the database opened read only;
    an existing file is reused. No node may hold the database."""
    if out_csv.exists():
        return out_csv
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    schema = "SIM_" + full_run_id.upper().replace("-", "_")
    url = f"jdbc:h2:file:{data_dir}/database/indexdb;ACCESS_MODE_DATA=r"
    sql = (f"CALL CSVWRITE('{out_csv}', 'SELECT ORGANISM_ID, PARENT_ID, BIRTH_TICK, DEATH_TICK, "
           f"GENOME_HASH FROM \"{schema}\".\"ORGANISMS\"');")
    subprocess.run([java, "-Xmx2g", "-cp", str(h2_jar), "org.h2.tools.Shell", "-url", url,
                    "-user", "sa", "-password", "", "-sql", sql], check=True, capture_output=True)
    return out_csv


@dataclass
class Run:
    """The organisms of a run as arrays indexed by organism id, and the run's grid."""
    parent: np.ndarray
    birth: np.ndarray
    death: np.ndarray          # NEVER for organisms alive at the end
    bodied: np.ndarray
    ids: np.ndarray            # existing ids, ascending (= birth order)
    variation: np.ndarray      # class name index per id, -1 for founders
    classes: list
    population: pd.Series      # bodied_count by tick
    t_end: int
    checks: dict = field(default_factory=dict)

    @property
    def n(self):
        return self.parent.shape[0]


def load(analytics: Path, organisms_csv: Path) -> Run:
    o = pd.read_csv(organisms_csv)
    o.columns = ["id", "parent", "birth", "death", "ghash"]
    o = o.sort_values("id")
    n = int(o.id.max()) + 1
    parent = np.full(n, -1, np.int64); parent[o.id] = o.parent.fillna(-1).astype(np.int64)
    birth = np.full(n, -1, np.int64); birth[o.id] = o.birth
    death = np.full(n, NEVER, np.int64); death[o.id] = o.death.fillna(NEVER).astype(np.int64)
    bodied = np.zeros(n, bool); bodied[o.id] = o.ghash.values != 0
    ids = o.id.values.astype(np.int64)

    meta = analytics / "births" / "metadata.json"
    if not meta.exists():
        raise SystemExit(f"{meta} missing: this run was indexed without the births plugin, "
                         "whose variation classes found the components")
    classes = json.loads(meta.read_text())["visualization"]["config"]["variationClasses"]
    b = duckdb.sql(f"SELECT organism_id, variation, birth_tick FROM read_parquet("
                   f"'{analytics}/births/lod0/**/*.parquet')").df()
    variation = np.full(n, -1, np.int64); variation[b.organism_id] = b.variation
    pop = duckdb.sql(f"SELECT tick, bodied_count FROM read_parquet("
                     f"'{analytics}/population/lod0/**/*.parquet') ORDER BY tick").df()

    has_parent = parent[ids] >= 0
    c = {
        "organisms": int(len(o)),
        "bodied": int(bodied.sum()),
        "ids_contiguous": bool(o.id.is_unique and len(o) == n - int(o.id.min())),
        "parent_before_child": bool((parent[ids] < ids).all()),
        "parent_dead_before_birth": int((death[parent[ids][has_parent]]
                                         < birth[ids][has_parent]).sum()),
        "births_rows": int(len(b)),
        "births_rows_equal_organisms_with_parent": bool(len(b) == int(has_parent.sum())),
        "births_birth_tick_mismatch": int((birth[b.organism_id] != b.birth_tick).sum()),
        "variation_classes": classes,
        "births_by_class": {classes[k]: int(v) for k, v in b.variation.value_counts().items()},
    }
    return Run(parent, birth, death, bodied, ids, variation, classes,
               pop.set_index("tick").bodied_count, int(pop.tick.max()), c)


# ----------------------------------------------------------------------------------- components

@nb.njit(cache=True)
def _assign(ids, parent, bodied, founds):
    comp = np.full(parent.shape[0], -1, np.int64)
    ncomp = 0
    for i in ids:
        if not bodied[i]:
            continue
        p = parent[i]
        if founds[i] or p < 0 or comp[p] < 0:
            comp[i] = ncomp
            ncomp += 1
        else:
            comp[i] = comp[p]
    return comp, ncomp


def components(run: Run, copy_channel_founds: bool):
    """Component per organism id (-1 for the bodiless) and the number of components. A birth of a
    mutation class founds one; with ``copy_channel_founds`` a birth of class `no_event` too."""
    founding = set(MUTATION_CLASSES) | ({"no_event"} if copy_channel_founds else set())
    idx = np.array([k for k, name in enumerate(run.classes) if name in founding])
    founds = np.isin(run.variation, idx)
    return _assign(run.ids, run.parent, run.bodied, founds)


# ----------------------------------------------------------------------------------------- grid

@dataclass
class Grid:
    k: int                    # raster points 0..k-1 at t = i * GRID
    lo: np.ndarray            # first raster point an organism is alive at (birth <= t)
    hi: np.ndarray            # first raster point it is no longer alive at (death <= t)
    wend: np.ndarray          # raster point of each complete window's end
    generation: np.ndarray    # mean age of the parent at a birth, per window
    cumw: np.ndarray          # cumulative raster weight GRID / generation time


def grid(run: Run) -> Grid:
    k = run.t_end // GRID + 1
    lo = -(-run.birth // GRID)
    hi = np.minimum(np.where(run.death >= NEVER, k, -(-run.death // GRID)), k)
    nw = run.t_end // WINDOW
    wend = np.minimum((np.arange(nw) + 1) * WINDOW // GRID, k - 1)
    B = run.ids[run.bodied[run.ids] & (run.parent[run.ids] >= 0)]
    age = run.birth[B] - run.birth[run.parent[B]]
    gen = pd.Series(age).groupby(run.birth[B] // WINDOW).mean().reindex(range(nw))
    g = gen.ffill().bfill().values
    weight = GRID / g[np.minimum(np.arange(k) * GRID // WINDOW, nw - 1)]
    run.checks["generation_time_mean"] = float(age.mean())
    return Grid(k, lo, hi, wend, gen.values, np.concatenate([[0.0], np.cumsum(weight)]))


def living(lo, hi, members, k):
    d = np.zeros(k + 1, np.int64)
    np.add.at(d, lo[members], 1)
    np.add.at(d, hi[members], -1)
    return np.cumsum(d)[:k]


# ------------------------------------------------------------------------------------- activity

@nb.njit(cache=True)
def _activity(order, comp, lo, hi, wend, cumw, band_lo, band_hi):
    """Walks the organisms sorted by (component, lo), merges each component's carrier intervals on
    the raster, and evaluates every window end: components present, their summed activity in
    raster points and in generations, and for each band the activity and number of present
    components whose activity lies in it. Also the activity of each component at the run's end."""
    nw = wend.shape[0]
    nb_ = band_lo.shape[0]
    present = np.zeros(nw, np.int64)
    acum = np.zeros(nw, np.float64)
    acum_g = np.zeros(nw, np.float64)
    band_sum = np.zeros((nb_, nw), np.float64)
    band_n = np.zeros((nb_, nw), np.int64)
    act_end = np.zeros(comp.max() + 1, np.int64)
    seg_s = np.empty(order.shape[0], np.int64)
    seg_e = np.empty(order.shape[0], np.int64)
    i = 0
    m = order.shape[0]
    while i < m:
        c = comp[order[i]]
        ns = 0
        cs = -1
        ce = -1
        while i < m and comp[order[i]] == c:
            j = order[i]
            if lo[j] < hi[j]:
                if ns > 0 and lo[j] <= ce:
                    if hi[j] > ce:
                        ce = hi[j]
                else:
                    if ns > 0:
                        seg_s[ns - 1] = cs
                        seg_e[ns - 1] = ce
                    cs = lo[j]
                    ce = hi[j]
                    ns += 1
            i += 1
        if ns > 0:
            seg_s[ns - 1] = cs
            seg_e[ns - 1] = ce
        tot = 0
        for s in range(ns):
            tot += seg_e[s] - seg_s[s]
        act_end[c] = tot
        for w in range(nw):
            t = wend[w]
            a = 0
            ag = 0.0
            pres = False
            for s in range(ns):
                if seg_s[s] > t:
                    break
                e = seg_e[s] if seg_e[s] <= t + 1 else t + 1
                a += e - seg_s[s]
                ag += cumw[e] - cumw[seg_s[s]]
                if seg_s[s] <= t < seg_e[s]:
                    pres = True
            if pres:
                present[w] += 1
                acum[w] += a
                acum_g[w] += ag
                for b in range(nb_):
                    if band_lo[b] <= a <= band_hi[b]:
                        band_sum[b, w] += a
                        band_n[b, w] += 1
    return present, acum, acum_g, band_sum, band_n, act_end


def activity(comp, lo, hi, members, g: Grid, bands=()):
    order = members[np.lexsort((lo[members], comp[members]))]
    blo = np.array([b[0] for b in bands], np.float64)
    bhi = np.array([b[1] for b in bands], np.float64)
    return _activity(order, comp, lo, hi, g.wend, g.cumw, blo, bhi)


@nb.njit(cache=True)
def _shadow(ev_tick, ev_kind, ev_id, comp, founds, n, seed):
    """Replays the run's births (kind 1) and deaths (kind 0, before the births of the same tick).
    A newborn takes the component of a random living shadow organism unless the real birth founds
    a component, which it then founds in the shadow too; a death removes a random living shadow
    organism. Returns the shadow component and shadow death per id, and the number of events
    that met an empty population."""
    np.random.seed(seed)
    scomp = np.full(n, -1, np.int64)
    sdeath = np.full(n, 4611686018427387903 // 2, np.int64)
    alive = np.empty(n, np.int64)
    na = 0
    empty = 0
    for e in range(ev_tick.shape[0]):
        i = ev_id[e]
        if ev_kind[e] == 1:
            if founds[i]:
                scomp[i] = comp[i]
            elif na == 0:
                scomp[i] = comp[i]
                empty += 1
            else:
                scomp[i] = scomp[alive[np.random.randint(na)]]
            alive[na] = i
            na += 1
        else:
            if na == 0:
                empty += 1
                continue
            k = np.random.randint(na)
            sdeath[alive[k]] = ev_tick[e]
            alive[k] = alive[na - 1]
            na -= 1
    return scomp, sdeath, empty


def shadow(run: Run, comp, g: Grid, seed: int):
    B = run.ids[run.bodied[run.ids]]
    p = np.maximum(run.parent[B], 0)
    founds = np.zeros(run.n, bool)
    founds[B] = (run.parent[B] < 0) | (comp[B] != comp[p])
    dead = B[run.death[B] < NEVER]
    tick = np.concatenate([run.birth[B], run.death[dead]])
    kind = np.concatenate([np.ones(len(B), np.int8), np.zeros(len(dead), np.int8)])
    who = np.concatenate([B, dead])
    o = np.lexsort((who, kind, tick))
    scomp, sdeath, empty = _shadow(tick[o], kind[o], who[o], comp, founds, run.n, seed)
    shi = np.minimum(np.where(sdeath >= NEVER // 2, g.k, -(-sdeath // GRID)), g.k)
    return scomp, shi, empty


# --------------------------------------------------------------------------------- Bedau tables

def bedau(run: Run, g: Grid, copy_channel_founds: bool, tag: str):
    """Window table of diversity, cumulative and mean activity (raster points and generations) and
    new activity (eq. 7 of Bedau, Snyder & Packard 1998) for the run and its shadow. The shadow is
    replayed with several seeds; its columns hold the mean over the replicates, with _min and _max
    beside them, and a' is taken from the components of all replicates together."""
    comp, ncomp = components(run, copy_channel_founds)
    B = run.ids[run.bodied[run.ids]]
    members = B[g.lo[B] < g.hi[B]]
    alive = living(g.lo, g.hi, members, g.k)
    replicates, ends, equal, empty = [], [], True, 0
    for r in range(SHADOW_REPLICATES):
        scomp, shi, e = shadow(run, comp, g, SEED + r)
        smembers = B[g.lo[B] < shi[B]]
        equal &= bool((living(g.lo, shi, smembers, g.k) == alive).all())
        empty += e
        replicates.append((scomp, shi, smembers))
        ends.append(activity(scomp, g.lo, shi, smembers, g)[-1])
    checks = {
        "components": int(ncomp),
        "shadow_replicates": SHADOW_REPLICATES,
        "carriers_equal_living_shadow": equal,
        "shadow_events_in_empty_population": int(empty),
    }
    seen = np.concatenate([e[e > 0] for e in ends])
    bands, aprime = [], {}
    for pct in BAND_PERCENTILES:
        ap = float(np.percentile(seen, pct))
        aprime[pct] = ap
        bands.append((BAND_WIDTH[0] * ap, BAND_WIDTH[1] * ap))
    checks["a_prime"] = {str(k): v for k, v in aprime.items()}
    checks["bands"] = {str(k): list(b) for k, b in zip(BAND_PERCENTILES, bands)}
    def measures(c, hi, mem):
        present, acum, acum_g, bsum, bn, _ = activity(c, g.lo, hi, mem, g, bands)
        d = np.maximum(present, 1)
        m = {"diversity": present, "cum_activity": acum, "mean_activity_ticks": acum / d,
             "mean_activity_generations": acum_g / d}
        for k, pct in enumerate(BAND_PERCENTILES):
            m[f"new_activity_p{pct}"] = bsum[k] / d
            m[f"band_components_p{pct}"] = bn[k]
        return m

    cols = {f"{tag}real_{k}": v for k, v in measures(comp, g.hi, members).items()}
    reps = [measures(*rep) for rep in replicates]
    for k in reps[0]:
        stack = np.vstack([m[k] for m in reps])
        cols[f"{tag}shadow_{k}"] = stack.mean(axis=0)
        cols[f"{tag}shadow_{k}_min"] = stack.min(axis=0)
        cols[f"{tag}shadow_{k}_max"] = stack.max(axis=0)
    return pd.DataFrame(cols), comp, members, alive, checks


# ---------------------------------------------------------------------------------------- MODES

@nb.njit(cache=True)
def _subtree_last_death(ids, parent, bodied, death):
    last = np.full(parent.shape[0], -1, np.int64)
    for k in range(ids.shape[0] - 1, -1, -1):
        i = ids[k]
        if not bodied[i]:
            continue
        if death[i] > last[i]:
            last[i] = death[i]
        p = parent[i]
        if p >= 0 and last[i] > last[p]:
            last[p] = last[i]
    return last


def modes(run: Run, g: Grid, comp, members):
    """Change, novelty and ecology of the persistent components per window end t. A component
    persists when it has carriers at t and a carrier born by t has a bodied descendant (or is
    itself) alive at t + F, F a number of generation times of the window."""
    last = _subtree_last_death(run.ids, run.parent, run.bodied, run.death)
    B = run.ids[run.bodied[run.ids]]
    o = B[np.lexsort((run.birth[B], comp[B]))]
    cb = pd.DataFrame({"c": comp[o], "b": run.birth[o], "last": last[o]})
    cb["reach"] = cb.groupby("c")["last"].cummax()
    ncomp = int(comp.max()) + 1
    cols = {}
    for mult in MODES_FILTERS:
        rows, prev, ever = [], None, set()
        for w, end in enumerate(g.wend):
            t = int(end) * GRID
            gen = g.generation[w]
            if np.isnan(gen) or t + mult * gen > run.t_end:
                rows.append((np.nan, np.nan, np.nan, np.nan))
                prev = None
                continue
            alive = members[(g.lo[members] <= end) & (g.hi[members] > end)]
            ab = np.bincount(comp[alive], minlength=ncomp)
            pres = np.flatnonzero(ab)
            reach = cb[cb.b <= t].groupby("c")["reach"].last().reindex(pres).values
            P = pres[reach > t + mult * gen]
            Ps = set(P.tolist())
            p = ab[P] / ab[P].sum() if len(P) else np.array([])
            rows.append((len(Ps - prev) if prev is not None else np.nan, len(Ps - ever),
                         float(-(p * np.log(p)).sum()) if len(P) else 0.0, len(P)))
            prev = Ps
            ever |= Ps
        r = np.array(rows, float)
        for k, name in enumerate(("change", "novelty", "ecology", "persistent")):
            cols[f"{name}_{mult}"] = r[:, k]
    return pd.DataFrame(cols)


# ------------------------------------------------------------------------------------- the run

def analyze(data_dir: Path, run_id: str, h2_jar: Path, work: Path, java: str = "java"):
    """Returns the window table and the checks of one run."""
    rd = run_dir(data_dir, run_id)
    analytics = rd / "analytics"
    csv = export_organisms(data_dir, rd.name, work / f"{rd.name}-organisms.csv", h2_jar, java)
    run = load(analytics, csv)
    g = grid(run)
    main, comp, members, alive, c_main = bedau(run, g, False, "")
    bracket, _, _, _, c_bracket = bedau(run, g, True, "bracket_")
    pop = run.population.reindex(np.arange(g.k) * GRID)
    matched = ~np.isnan(pop.values)
    checks = dict(run.checks)
    checks.update({
        "raster_points": int(g.k),
        "population_points_matched": int(matched.sum()),
        "living_differs_from_population_at": int((alive[matched] != pop.values[matched]).sum()),
        "main": c_main,
        "bracket": c_bracket,
    })
    table = pd.concat([main, bracket, modes(run, g, comp, members)], axis=1)
    table.insert(0, "window_end_tick", (np.arange(len(g.wend)) + 1) * WINDOW)
    table.insert(1, "generation_time", g.generation)
    for q in ("mean_activity_ticks", "mean_activity_generations",
              *(f"new_activity_p{p}" for p in BAND_PERCENTILES)):
        table[f"ratio_{q}"] = table[f"real_{q}"] / table[f"shadow_{q}"].replace(0, np.nan)
    return table, checks



# --------------------------------------------------------------------------------------- trends

TREND_COLUMNS = ("real_diversity", "real_mean_activity_ticks", "real_mean_activity_generations",
                 *(f"real_new_activity_p{p}" for p in BAND_PERCENTILES))


def theil_sen(x, y):
    """Median of the pairwise slopes."""
    ok = ~(np.isnan(x) | np.isnan(y))
    x, y = x[ok], y[ok]
    i, j = np.triu_indices(len(x), 1)
    return float(np.median((y[j] - y[i]) / (x[j] - x[i])))


def trends(table: pd.DataFrame) -> dict:
    """Theil-Sen slope of each quantity against the generations elapsed, per 100 generations and
    relative to the quantity's mean, over the whole run and from the end of its first third on.
    A finite run shows a trend, not boundedness in the sense of Bedau, Snyder & Packard's
    appendix; the class is a judgement made with these numbers, not computed from them."""
    gens = np.cumsum(WINDOW / table.generation_time.ffill().bfill().values)
    out = {"generations": float(gens[-1])}
    for prefix in ("", "bracket_"):
        for col in TREND_COLUMNS:
            y = table[prefix + col].values.astype(float)
            r = {}
            for name, start in (("whole_run", 0), ("from_first_third", len(y) // 3)):
                slope = 100 * theil_sen(gens[start:], y[start:])
                r[name] = {"per_100_generations": slope,
                           "relative_percent": 100 * slope / float(np.nanmean(y[start:]))}
            out[prefix + col] = r
    return out

# ------------------------------------------------------------------------------------- figures

INK, INK2, GRIDC, SURFACE = "#0b0b0b", "#52514e", "#e6e5e0", "#fcfcfb"
FULL_DPI = 130            # the files beside the notebook
NOTEBOOK_DPI = 90         # the images embedded in a record's notebook
NOTEBOOK_COLOURS = 32     # palette of an embedded diagram; lines and areas need no more
REAL, SHADOW, SWEEP = "#2a78d6", "#eb6834", "#c3c2b7"


def notebook_png(path: Path) -> bytes:
    """A diagram as a palette PNG for embedding in a notebook, whose preview on Zenodo has a size
    limit. Only for diagrams: a still of the world loses the colours of its markers."""
    import io
    from PIL import Image
    im = Image.open(path).convert("RGB").quantize(colors=NOTEBOOK_COLOURS,
                                                  method=Image.Quantize.MEDIANCUT)
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def figures(table: pd.DataFrame, out: Path, label: str, sweeps=(), dpi=FULL_DPI):
    """Writes the figures and returns their paths. With ``dpi=NOTEBOOK_DPI`` and
    :func:`notebook_png` a notebook embeds the mean activity and new activity figures."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 9, "axes.edgecolor": INK2, "axes.labelcolor": INK2,
                         "xtick.color": INK2, "ytick.color": INK2, "axes.spines.top": False,
                         "axes.spines.right": False, "axes.grid": True, "grid.color": GRIDC,
                         "grid.linewidth": 0.6, "figure.facecolor": SURFACE,
                         "axes.facecolor": SURFACE})
    x = table.window_end_tick / 1e6
    files = []

    def shade(ax):
        for a, b in sweeps:
            ax.axvspan(a / 1e6, b / 1e6, color=SWEEP, alpha=0.25, lw=0)

    def pair(ax, column, ylabel):
        shade(ax)
        ax.fill_between(x, table[f"{column.replace('real', 'shadow')}_min"],
                        table[f"{column.replace('real', 'shadow')}_max"], color=SHADOW, alpha=0.25,
                        lw=0, label=f"shadow range ({SHADOW_REPLICATES} seeds)")
        ax.plot(x, table[column], color=REAL, lw=2, label="real")
        ax.plot(x, table[column.replace("real", "shadow")], color=SHADOW, lw=2,
                label="shadow mean")
        ax.set_ylabel(ylabel)
        ax.legend(frameon=False, loc="upper left", bbox_to_anchor=(1.0, 1.0))

    def save(fig, name, title):
        fig.suptitle(textwrap.fill(f"{label}: {title}", 110), color=INK, fontsize=10)
        fig.tight_layout()
        path = out / f"{label}-{name}.png"
        fig.savefig(path, dpi=dpi)
        plt.close(fig)
        files.append(path)

    fig, axs = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    pair(axs[0], "real_mean_activity_ticks", "raster points")
    pair(axs[1], "real_mean_activity_generations", "generations")
    axs[1].set_xlabel("tick (M); grey: sweeps")
    save(fig, "mean-activity", "mean cumulative activity per present component, in raster points "
         "and in generation times of each window")

    p0, p1 = BAND_PERCENTILES
    fig, axs = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    pair(axs[0], f"real_new_activity_p{p0}", f"a' = p{p0} of shadow")
    pair(axs[1], f"real_new_activity_p{p1}", f"a' = p{p1} of shadow")
    axs[1].set_xlabel("tick (M); grey: sweeps")
    save(fig, "new-activity", f"new activity per component (Bedau eq. 7), band "
         f"[{BAND_WIDTH[0]} a', {BAND_WIDTH[1]} a'] in raster points")

    fig, axs = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    pair(axs[0], "real_diversity", "diversity D")
    pair(axs[1], "bracket_real_diversity", "D, copy channel founds")
    axs[1].set_xlabel("tick (M); grey: sweeps")
    save(fig, "diversity", "diversity: components with carriers; below, the bracket in which "
         "births of class no_event found components too")

    fig, axs = plt.subplots(3, 1, figsize=(10, 7.5), sharex=True)
    for ax, q in zip(axs, ("change", "novelty", "ecology")):
        shade(ax)
        for mult, color in zip(MODES_FILTERS, (REAL, SHADOW)):
            ax.plot(x, table[f"{q}_{mult}"], color=color, lw=2, label=f"filter {mult} generations")
        ax.set_ylabel(q)
    axs[0].legend(frameon=False, loc="upper left", bbox_to_anchor=(1.0, 1.0))
    axs[2].set_xlabel("tick (M); grey: sweeps")
    save(fig, "modes", "MODES change, novelty and ecology (Shannon entropy) of the persistent "
         "components")
    return files


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--h2-jar", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--label")
    ap.add_argument("--sweeps", default="", help="tick ranges in M, e.g. 130-200,230-330")
    ap.add_argument("--java", default="java")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    label = a.label or a.run_id
    table, checks = analyze(a.data_dir, a.run_id, a.h2_jar, a.out, a.java)
    table.to_csv(a.out / f"{label}-windows.csv", index=False)
    checks["trends"] = trends(table)
    (a.out / f"{label}-checks.json").write_text(json.dumps(checks, indent=2))
    sweeps = [tuple(float(v) * 1e6 for v in s.split("-")) for s in a.sweeps.split(",") if s]
    figures(table, a.out, label, sweeps)
    print(json.dumps(checks, indent=2))


if __name__ == "__main__":
    main()
