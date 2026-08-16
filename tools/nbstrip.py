#!/usr/bin/env python3
"""Strip execution state from a Jupyter notebook.

Reads a notebook on stdin, writes the stripped notebook to stdout. Cell outputs and execution
counts are the result of running an analysis, not source, and they inflate the repository by
megabytes per commit (embedded plots are base64 PNGs). Removing them also stops execution
counters from showing up as spurious diffs.

Used as a git clean filter, so the working copy keeps its outputs while the committed version
does not. See .gitattributes for the filter binding.
"""

import json
import sys


def strip(notebook):
    for cell in notebook.get("cells", []):
        if cell.get("cell_type") != "code":
            continue
        cell["outputs"] = []
        cell["execution_count"] = None
        metadata = cell.get("metadata", {})
        # Collapsed/scrolled state describes how outputs were displayed and is meaningless
        # without them.
        for key in ("collapsed", "scrolled", "execution"):
            metadata.pop(key, None)
    # Records the kernel that last ran the notebook, not how to run it.
    notebook.get("metadata", {}).pop("widgets", None)
    return notebook


def main():
    notebook = json.load(sys.stdin)
    json.dump(strip(notebook), sys.stdout, indent=1, ensure_ascii=False, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
