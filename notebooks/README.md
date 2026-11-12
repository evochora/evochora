# Notebooks

Jupyter notebooks that analyze the data of a run.

- [`data_analysis_guide.ipynb`](data_analysis_guide.ipynb): the usual analyses of a run, from population dynamics to phylogenetic trees, and a reference of the data a notebook can fetch.

A notebook reads its data from a node over HTTP. Its first code cell sets `BASE_URL`, the address of the node, and optionally `RUN_ID`, the run to analyze. Without `RUN_ID`, the notebook takes the longest run the node holds.

## Run a Notebook in Colab

[Open the notebook in Colab](https://colab.research.google.com/github/evochora/evochora/blob/main/notebooks/data_analysis_guide.ipynb?flush_cache=true) and run its cells. Colab runs in the cloud and cannot reach a node on your computer, so use it with a node that is reachable from the internet. `BASE_URL` is preset to the public demo at `https://evochora.org`.

Colab comes with its own versions of the packages the notebook uses, and the notebook uses them. Only when `requests` or `pyarrow` is missing does its setup cell install the packages at the versions of `requirements.txt`. The cell that uses DuckDB installs it at its pinned version if it is missing.

## Run a Notebook Locally

To analyze the runs of your own node, run the notebook on your computer. In this directory:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt notebook
jupyter notebook data_analysis_guide.ipynb
```

`requirements.txt` names exact versions of the packages the notebooks use, so that an analysis runs with the same versions wherever it is repeated. `notebook` is Jupyter itself. Any other Jupyter frontend works as well, such as JupyterLab or VS Code.

Then set `BASE_URL` to your node. The first code cell holds the line for a local node, commented out.
