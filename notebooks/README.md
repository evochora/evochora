# Notebooks

Analysis notebooks for simulation data.

- `regime_diagnostic.ipynb` — ANOVA-based diagnostic for detecting adaptation across configuration changes
- `data_analysis_guide.ipynb` — guide to accessing simulation data from a notebook

## One-time setup

Notebooks are committed **without execution state** — no cell outputs, no execution counts. Outputs are
generated results, and embedded plots are base64 PNGs that add megabytes to the repository with every
commit. Your working copy keeps its outputs; only what Git records is stripped.

This is enforced by a clean filter that each clone has to register once, because Git does not allow a
repository to define filter commands for security reasons:

```bash
git config filter.nbstrip.clean "python3 tools/nbstrip.py"
```

Without this, `.gitattributes` names a filter that does not exist locally and notebooks are committed
unchanged — Git reports no error, so verify with `git diff` that a re-executed notebook shows no
output changes.

The filter script is `tools/nbstrip.py` (standard library only, no installation needed).

## Environment

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```
