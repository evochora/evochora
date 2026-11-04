# User Guide

This guide takes you through an experiment with Evochora, from starting a simulation to analyzing and sharing what it produced. Each step says what it is for and where you find the details.

- [Key Concepts](#key-concepts)
- [1. Install and Start](#1-install-and-start)
- [2. Choose or Write an Ancestor](#2-choose-or-write-an-ancestor)
- [3. Configure the Environment and Its Rules](#3-configure-the-environment-and-its-rules)
- [4. Write Your Own Plugins](#4-write-your-own-plugins)
- [5. Start and Manage Runs](#5-start-and-manage-runs)
- [6. Observe a Run](#6-observe-a-run)
- [7. Record a Stretch in Full](#7-record-a-stretch-in-full)
- [8. Analyze the Data](#8-analyze-the-data)
- [9. Reproduce a Run](#9-reproduce-a-run)
- [10. Cite and Share](#10-cite-and-share)
- [11. Work with AI](#11-work-with-ai)

## Key Concepts

- **Environment**: the n-dimensional grid the simulation takes place in. Each cell holds one molecule.
- **Molecule**: the unit a cell holds, such as code, data, energy or structure.
- **Organism**: a self-replicating program laid out in the environment as molecules it owns.
- **Energy and entropy**: an organism's two budgets. Its actions cost energy and add entropy, and it dies when its energy runs out or its entropy exceeds a limit.
- **Ancestor**: an organism written by hand that a run starts with. A run can start with one or several.
- **EvoASM**: the assembly language organisms are written in.
- **Tick**: one step of the simulation, in which every organism executes one instruction.
- **Run**: one simulation from its first tick on, together with the data recorded about it.
- **Sampling**: a run records every n-th tick rather than every tick; a stretch of interest can be recorded again in full.
- **Node**: the Evochora process. It executes a run, records it and serves the web frontend in your browser.
- **Visualizer**: the part of the web frontend that shows the environment and its organisms at each recorded tick.
- **Analyzer**: the part of the web frontend that shows the metrics of a whole run as charts.

## 1. Install and Start

Download the archive for your system from the [releases page](https://github.com/evochora/evochora/releases), unpack it and start a node as described in the [Quick Start](../README.md#quick-start). The node starts a run with the default ancestor in the default environment. Open the visualizer and you can watch the ancestor copy itself.

The next step shows how to choose another ancestor or write your own.

## 2. Choose or Write an Ancestor

Evochora comes with several ancestors in [`assembly/ancestors/`](../assembly/ancestors/). The header of each one's `main.evo` says what it does, and the [ancestors README](../assembly/ancestors/README.md) shows how to start one or several of them from the configuration. Some ancestors take flags and values that change their behavior without editing the program. The default ancestor, for example, can be told to take over cells of other organisms that stand in the way of its child instead of giving up; [`config/evochora.conf`](../config/evochora.conf) lists these settings.

To write your own ancestor, copy an existing one and change it. Ancestors are written in EvoASM. The [Assembly Specification](ASSEMBLY_SPEC.md) describes the language, and the [EvoASM Guidelines](EVOASM_GUIDELINES.md) explain how to write programs that hold up in a simulation. Behaviors that several ancestors share, such as harvesting energy or reproducing, live in the [library](../assembly/lib/README.md) and can be imported. The small programs in [`assembly/examples/`](../assembly/examples/) show the language at work. Before you start a full run, you can test a program on its own with [`assembly/example.conf`](../assembly/example.conf), as the guidelines describe. For editing, the [VS Code extension](../extensions/vscode/README.md) adds syntax highlighting.

## 3. Configure the Environment and Its Rules

An experiment is set up in a configuration file of its own. Create it next to [`config/evochora.conf`](../config/evochora.conf), begin it with `include "evochora.conf"` and set only what your experiment changes; start the node with `bin/evochora -c config/<your-file>.conf node run`. Your file then shows at a glance what makes this experiment different, and everything else follows the template. `evochora.conf` holds the settings you usually change, each with a short comment. All settings with their full explanations are in [`reference.conf`](../src/main/resources/reference.conf), which also supplies the default for everything neither file sets.

The settings cover the size of the environment and whether its edges wrap around, the seed for random numbers, the ancestors and the rules of the simulation: where energy comes from, what actions cost in energy and entropy, how organisms mutate when they reproduce and what happens to an organism that dies. The rules are plugins, which the configuration names by their Java class. If the existing plugins are not enough, you can [write your own](#4-write-your-own-plugins).
