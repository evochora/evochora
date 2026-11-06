# User Guide

This guide takes you through an experiment with Evochora, from starting a simulation to analyzing and sharing what it produced. Each step says what it is for and where you find the details. Commands are shown for Linux and macOS; on Windows, run `bin\evochora.bat` instead of `bin/evochora`.

- [Key Concepts](#key-concepts)
- [1. Install and Start](#1-install-and-start)
- [2. Choose or Write an Ancestor](#2-choose-or-write-an-ancestor)
- [3. Configure an Experiment](#3-configure-an-experiment)
- [4. Start and Manage Runs](#4-start-and-manage-runs)
- [5. Observe a Run](#5-observe-a-run)
- [6. Record a Stretch in Full](#6-record-a-stretch-in-full)
- [7. Analyze the Data](#7-analyze-the-data)
- [8. Reproduce a Run](#8-reproduce-a-run)
- [9. Cite and Share](#9-cite-and-share)
- [10. Work with AI](#10-work-with-ai)
- [11. Extend Evochora with Plugins](#11-extend-evochora-with-plugins)

## Key Concepts

- **Environment**: the n-dimensional grid the simulation takes place in. Each cell holds one molecule.
- **Molecule**: the unit a cell holds, such as code, data, energy or structure.
- **Organism**: a self-replicating program laid out in the environment as molecules it owns.
- **Energy and entropy**: an organism's two budgets. Its actions cost energy and add entropy, and it dies when its energy runs out or its entropy exceeds a limit.
- **Ancestor**: an organism written by hand that a run starts with. A run can start with one or several.
- **EvoASM**: the assembly language organisms are written in.
- **Tick**: one step of the simulation, in which every organism executes one instruction.
- **Run**: one simulation from its first tick on, together with the data recorded about it. Each run has an ID.
- **Project folder**: the folder with your configuration, ancestors and runs; by default the installation folder.
- **Sampling**: a run records every n-th tick rather than every tick; a stretch of interest can be recorded again in full.
- **Node**: the Evochora process. It executes a run, records it and serves the web frontend in your browser.
- **Visualizer**: the part of the web frontend that shows the environment and its organisms at each recorded tick.
- **Analyzer**: the part of the web frontend that shows the metrics of a whole run as charts.

## 1. Install and Start

Download the archive for your system from the [releases page](https://github.com/evochora/evochora/releases), unpack it and start a node as described in the [Quick Start](../README.md#quick-start). The node starts a run with the default ancestor in the default environment. Open the visualizer and you can watch the ancestor copy itself.

You normally work in your project folder. By default, this is the installation folder: your configuration, your ancestors and the data of your runs are kept in it, next to Evochora itself and its sources. That way the whole folder can be published as an experiment, and the runs come with the exact software that produced them.

A Docker image is published as well, but the archives are the recommended way to run Evochora. [Choose or Write an Ancestor](#2-choose-or-write-an-ancestor) shows how to choose another ancestor or write your own.

## 2. Choose or Write an Ancestor

Evochora comes with several ancestors in [`assembly/ancestors/`](../assembly/ancestors/). The header of each one's `main.evo` says what it does, and the [ancestors README](../assembly/ancestors/README.md) shows how to start one or several of them from the configuration. Some ancestors take flags and values that change their behavior without editing the program. The default ancestor, for example, can be told to take over cells of other organisms that stand in the way of its child instead of giving up; [`config/evochora.conf`](../config/evochora.conf) lists these settings.

To write your own ancestor, copy an existing one and change it. Ancestors are written in EvoASM. The [Assembly Specification](ASSEMBLY_SPEC.md) describes the language, and the [EvoASM Guidelines](EVOASM_GUIDELINES.md) explain how to write programs that hold up in a simulation. Behaviors that several ancestors share, such as harvesting energy or reproducing, live in the [library](../assembly/lib/README.md) and can be imported. The small programs in [`assembly/examples/`](../assembly/examples/) show the language at work. Before you start a full run, you can test a program on its own with [`assembly/example.conf`](../assembly/example.conf), as the guidelines describe. For editing, the [VS Code extension](../extensions/vscode/README.md) adds syntax highlighting.

## 3. Configure an Experiment

An experiment is set up in a configuration file of its own. Create it next to [`config/evochora.conf`](../config/evochora.conf), begin it with `include required("evochora.conf")` and set only what this experiment changes. Your file then shows at a glance what makes the experiment different. Settings that all your experiments share belong in `evochora.conf` itself, which lists the most common settings. All settings with their full explanations are in [`reference.conf`](../src/main/resources/reference.conf), which also supplies the default for everything neither file sets.

The settings cover the size of the environment and whether its edges wrap around, the seed for random numbers, the ancestors and how the simulation works: where energy comes from, what actions cost in energy and entropy, how organisms mutate when they reproduce and what happens to an organism that dies. These parts of the simulation are plugins, and the ones that come with Evochora have options you set in the configuration. If none of them fits your experiment, you can write your own; [Extend Evochora with Plugins](#11-extend-evochora-with-plugins) describes how.

A run's sampling is set by its tuning profile, which the configuration selects with `pipeline.tuning`. The default, `sparse`, records every ten-thousandth tick and suits most runs. `sampled` records every hundredth tick and simulates hardly slower, but its recording takes far more disk space. `detailed` records every tick, and the simulation then waits for the disk; it serves to record a stretch of a run again tick by tick, as [Record a Stretch in Full](#6-record-a-stretch-in-full) shows. Profiles are ordinary blocks of the configuration, so you can also define your own; [`reference.conf`](../src/main/resources/reference.conf) lists the existing ones under `profiles`.

## 4. Start and Manage Runs

Starting a run, continuing it after a stop, looking at what it recorded and deleting it are all commands of `bin/evochora`. Give each of them your experiment's file with `-c`, so that it uses that experiment's settings and finds its runs.

### Start your experiment

The first start, in [Install and Start](#1-install-and-start), used the default experiment. To run your own, give the node the file you created in [Configure an Experiment](#3-configure-an-experiment):

```bash
bin/evochora -c config/my-experiment.conf node run
```

The node compiles your ancestors and places them in the environment. From then on it simulates, records what happens and serves the web frontend in your browser. The [CLI Usage Guide](CLI_USAGE.md) describes this and every other command with all its options.

### What a run needs

The simulation keeps most of the processor busy, so the computer and the visualizer respond more slowly than usual while a run is going.

How much memory a run requires depends on the size of the environment, the number of organisms and the sampling. The node estimates it at startup and warns if not enough memory is available; `EVOCHORA_OPTS` raises the limit, as the [CLI Usage Guide](CLI_USAGE.md#environment-variables) shows.

The space a run takes on disk depends on how large and how full the environment is and on the sampling. The tuning profile sets the sampling; [Configure an Experiment](#3-configure-an-experiment) explains the profiles. The recorded data go into `data/` in your [project folder](#1-install-and-start).

### Stop and continue

You can stop a run at any time with Ctrl+C and continue it later:

```bash
bin/evochora -c config/my-experiment.conf node resume
```

The run continues from the last full state it recorded. Without further options, `node resume` takes the newest run in your [project folder](#1-install-and-start). To continue another one, give its ID with `--run <id>`; the visualizer shows the ID of every run, as [Observe a Run](#5-observe-a-run) describes.

### Look at recorded runs

To study the runs you have recorded without simulating any further, start the node with:

```bash
bin/evochora -c config/my-experiment.conf node show
```

It serves the recorded runs to the visualizer and the analyzer. Because nothing is simulated, the processor stays free and both respond quickly. [Observe a Run](#5-observe-a-run) describes what they show.

### Clean up

Runs you no longer need take up disk space. `bin/evochora -c config/my-experiment.conf cleanup` deletes them: it first shows what it would delete and acts only with `--force`. The [CLI Usage Guide](CLI_USAGE.md#clean-up-simulation-runs) explains how to select runs by their IDs.

## 11. Extend Evochora with Plugins

Most experiments need no code. The plugins that come with Evochora have many settings, and the configuration combines them. If you need something that none of them provides and would rather not write it yourself, describe it in the [Discussions](https://github.com/evochora/evochora/discussions); we can help or add it. The rest of this section is for writing a plugin yourself.

A plugin is a Java class that implements one of Evochora's plugin interfaces:

| Interface | What it decides |
|---|---|
| `ITickPlugin` | processes that run every tick, such as energy sources |
| `IBirthHandler` | what happens to a newborn, such as mutation |
| `IDeathHandler` | what happens to the cells of an organism that dies |
| `IThermodynamicPolicy` | what each instruction costs in energy and entropy |
| `ILabelMatchingStrategy` | how a jump finds its target |
| `IInstructionInterceptor` | changes to an instruction before it executes |
| `AbstractAnalyticsPlugin` | a metric the analyzer shows and exports |

The plugins that come with Evochora are the best starting point. Those for energy sources, mutation and death are in [`runtime/worldgen`](../src/main/java/org/evochora/runtime/worldgen/), the thermodynamic policies in [`runtime/thermodynamics/impl`](../src/main/java/org/evochora/runtime/thermodynamics/impl/), label matching in [`runtime/label`](../src/main/java/org/evochora/runtime/label/) and the analytics plugins in [`datapipeline/services/analytics/plugins`](../src/main/java/org/evochora/datapipeline/services/analytics/plugins/). Copy the one closest to your idea, including its constructor, because Evochora creates a plugin through the constructor its kind expects.

Compile your class against the libraries in the archive's `lib/` directory, for example with `javac -cp "lib/*"`, package it as a jar and put the jar into the archive's `plugins/` directory. Then name your class in your configuration file in place of the plugin it replaces or next to the ones it joins, and start the node as usual.

If your plugin needs a library that Evochora also uses, Evochora's version is the one that loads.
