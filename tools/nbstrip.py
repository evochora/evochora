#!/usr/bin/env python3
"""Strip execution state from Jupyter notebooks, or verify that none is present.

Cell outputs and execution counts are the result of running an analysis, not source, and they
inflate the repository by megabytes per commit (embedded plots are base64 PNGs). Removing them
also stops execution counters from showing up as spurious diffs.

Two modes:

  filter  (no arguments)   Read a notebook on stdin, write the stripped notebook to stdout.
                           Used as a git clean filter, so working copies keep their outputs
                           while committed versions do not. See .gitattributes for the binding.

  --check <path>...        Report notebooks that still carry execution state and exit non-zero.
                           Used in CI, where the checked-out files are exactly what the
                           repository stores. Directories are searched recursively.

The check exists because a git filter cannot be enforced: a filter named in .gitattributes but
not configured locally is silently ignored, and Git offers no way to make that an error.
"""

import json
import sys
from pathlib import Path


def strip(notebook):
    """Removes all execution state from a notebook, in place, and returns it."""
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


def find_execution_state(notebook):
    """Returns a description per code cell that still carries execution state."""
    findings = []
    for index, cell in enumerate(notebook.get("cells", [])):
        if cell.get("cell_type") != "code":
            continue
        reasons = []
        outputs = cell.get("outputs") or []
        if outputs:
            reasons.append(f"{len(outputs)} output(s)")
        if cell.get("execution_count") is not None:
            reasons.append(f"execution_count={cell['execution_count']}")
        if reasons:
            findings.append(f"cell {index}: {', '.join(reasons)}")
    return findings


def notebooks_in(paths):
    """Expands the given files and directories into a sorted list of notebook paths."""
    found = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            found.extend(path.rglob("*.ipynb"))
        elif path.suffix == ".ipynb":
            found.append(path)
    # Checkpoint copies are local scratch state and not part of the repository.
    return sorted(p for p in found if ".ipynb_checkpoints" not in p.parts)


def check(paths):
    """Reports notebooks carrying execution state. Returns a process exit code."""
    targets = notebooks_in(paths)
    if not targets:
        print(f"No notebooks found under: {', '.join(paths)}")
        return 0

    offenders = 0
    for path in targets:
        try:
            with path.open(encoding="utf-8") as handle:
                notebook = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            print(f"{path}: cannot be read as a notebook: {error}")
            offenders += 1
            continue

        findings = find_execution_state(notebook)
        if findings:
            offenders += 1
            print(f"{path}: execution state present")
            for finding in findings:
                print(f"    {finding}")

    if offenders:
        print()
        print(f"{offenders} of {len(targets)} notebook(s) carry execution state.")
        print("Notebooks are committed without outputs; see notebooks/README.md for the")
        print("one-time filter setup, then re-commit the affected notebooks.")
        return 1

    print(f"{len(targets)} notebook(s) checked, no execution state present.")
    return 0


def main():
    if len(sys.argv) > 1:
        if sys.argv[1] != "--check":
            print(f"usage: {Path(sys.argv[0]).name} [--check <path>...]", file=sys.stderr)
            return 2
        return check(sys.argv[2:] or ["notebooks"])

    json.dump(strip(json.load(sys.stdin)), sys.stdout,
              indent=1, ensure_ascii=False, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
