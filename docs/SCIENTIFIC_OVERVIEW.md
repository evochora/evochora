# Evochora: A Simulation Platform for Digital Evolution Research

## Abstract

Evochora is an open-source platform for digital evolution research in which a program is a body. The environment of its organisms is a space made of typed matter, and their logic and behavior are made of the same: they find their energy there, build and place their offspring there, reshape their surroundings, and a neighbor can dismantle them molecule by molecule. Survival is thermodynamic: every instruction costs energy and produces entropy, harvesting replenishes the one, and only what the organism writes into the environment carries the other away; it dies when either fails. No fitness function rewards it from outside. The platform is built to study what only a body of matter has: material, shape, territory, metabolism, and what of these a parent passes on, and behind them the open question of what an environment must provide for complexity to keep arising.

Researchers can shape the environment the organisms live in: its dimensionality, its landscape, how and when energy enters it, its thermodynamics and its structure-aware mutation plugins, which work on whole blocks of code. They choose the ancestors a run begins from, one or several that compete, and write them in EvoASM, Evochora's spatial assembly language, from scratch or from reusable behavior modules.

Every run is deterministic, runs at millions of instructions per second on a workstation, has reached billions of ticks, and can be resumed or re-recorded in any detail; a web visualizer follows a selected organism instruction by instruction and marks where its lineage mutated; run data are exported as Parquet files for standard analysis tools.

Of the runs made so far, two are published as citable experiment records, each tracing a selective sweep to the single instruction that caused it; researchers are invited to run their own experiments or to join them.

This document describes the platform's physics, the tools for observing and analyzing a run, the first results, the research directions it is built for, its architecture, and its limitations.

**Live demo:** [https://evochora.org/](https://evochora.org/)

## Table of Contents

1. [Introduction](#1-introduction)
    - [1.1 Evochora among related systems](#11-evochora-among-related-systems)
2. [The Digital Physics of Evochora](#2-the-digital-physics-of-evochora)
    - [2.1 The Environment and Its Molecules](#21-the-environment-and-its-molecules)
    - [2.2 The Virtual Machine](#22-the-virtual-machine)
    - [2.3 Ownership and Thermodynamics](#23-ownership-and-thermodynamics)
    - [2.4 Plugin Interfaces](#24-plugin-interfaces)
    - [2.5 The Ancestor](#25-the-ancestor)
3. [Running Experiments](#3-running-experiments)
    - [3.1 Setting Up a Run](#31-setting-up-a-run)
    - [3.2 Observing a Run](#32-observing-a-run)
    - [3.3 Analyzing a Run](#33-analyzing-a-run)
    - [3.4 Resuming and Re-recording a Run](#34-resuming-and-re-recording-a-run)
4. [First Results](#4-first-results)
5. [Research Directions](#5-research-directions)
    - [5.1 Replicator Ecology](#51-replicator-ecology)
    - [5.2 Host–Parasite Coevolution](#52-hostparasite-coevolution)
    - [5.3 Environmental Heterogeneity and Dynamic Worlds](#53-environmental-heterogeneity-and-dynamic-worlds)
    - [5.4 Evolvability and Mutation Regimes](#54-evolvability-and-mutation-regimes)
    - [5.5 The Bioenergetics of Complexity: A Digital Eukaryogenesis](#55-the-bioenergetics-of-complexity-a-digital-eukaryogenesis)
    - [5.6 From Internal Coordination to Multicellularity](#56-from-internal-coordination-to-multicellularity)
    - [5.7 Digital Chemistry and Reaction Networks](#57-digital-chemistry-and-reaction-networks)
6. [Architecture, Reproducibility and Scale](#6-architecture-reproducibility-and-scale)
    - [6.1 Architecture](#61-architecture)
    - [6.2 Reproducibility](#62-reproducibility)
    - [6.3 Performance and Scale](#63-performance-and-scale)
7. [Limitations](#7-limitations)
8. [Conclusion](#8-conclusion)
9. [Getting Involved](#9-getting-involved)
10. [Acknowledgements](#10-acknowledgements)
11. [References](#11-references)

## 1. Introduction

Biology shows that evolution can produce complexity, but also that this takes conditions which were a long time coming: life on Earth remained microbial for most of its history before complex organisms appeared, late and in bursts. Digital evolution has reproduced the early part of this history. In Tierra, the system the field was built on, self-replicating programs gave rise to parasites and to defenses against them within a few million instructions, without any fitness measure imposed from outside [(Ray, 1991)](#ref-ray-1991). In Avida, the field's main experimental system since, complex functions evolve when the environment rewards them, with results that reached the biological literature [(Ofria & Wilke, 2004)](#ref-ofria-2004).

The later part has not been reproduced yet: after an initial burst of innovation, the rate at which new adaptations appear levels off in the systems Bedau and colleagues measured [(Bedau et al., 2000)](#ref-bedau-2000). What is missing has been debated for three decades under the name of open-ended evolution. Several of the conditions proposed concern the organism's body: that it should consist of the same material as its environment, that energy should be something it has to collect rather than processor time it is given, and that it should have to maintain itself against entropy [(Taylor, 2014](#ref-taylor-2014), [2015)](#ref-taylor-2015). Both Tierra and Avida were built around the program. In Tierra an organism is a sequence of instructions in a shared memory that contains nothing else, in Avida a program running on a virtual CPU of its own, and a body in this sense was not part of either design. Artificial chemistries [(Dittrich et al., 2001)](#ref-dittrich-2001) approached the body from the other side. In Squirm3 self-replicating molecules arise from a random soup and matter is conserved, but nothing executes [(Hutton, 2002)](#ref-hutton-2002); in Stringmol, built for questions of this kind, the molecules are programs that act on one another, but a molecule has no extent in space [(Hickinbotham et al., 2016)](#ref-hickinbotham-2016).

Evochora's organisms are programs, they consist of the same material as their environment, and they have extent in space. The material itself does not react; it is what programs read, write and consume. The body is defined by ownership. The environment is a grid, and each of its cells holds one molecule of a few kinds: code, which organisms execute; data, which they read and write; structure, which cannot be passed but only removed at a cost; and energy, which they collect and can store. An organism is the set of cells it owns, and in the environment there is nothing else of it. This body it has to assemble itself. An offspring is not a copy that the system places somewhere, but a structure the parent writes into free cells with its own pointers, at whatever place it can reach. That individuals make such decisions themselves is one of the conditions Soros and Stanley propose for open-endedness [(Soros & Stanley, 2014)](#ref-soros-2014). The child inherits those of these cells the parent has marked for it, together with the energy the parent hands over. Every molecule written costs energy, and what one organism has built, a neighbor can take apart again. The three conditions named above are thus met in the physics: the body is made of the environment's material, energy is a molecule the organism has to find and collect, and since instructions produce entropy that only writing into the environment sheds, an organism has to keep changing the environment to stay alive; the idea behind this rule is Schrödinger's [(Schrödinger, 1944)](#ref-schrodinger-1944). Form, size, place and inheritance are decided by the program rather than by the system, and so they are exposed to selection.

These conditions were not designed in one piece. They entered one after another, each when a run showed that something was missing: entropy, for instance, after the first populations had copied over one another into an error catastrophe [(Eigen, 1971)](#ref-eigen-1971). [Energy sources, thermodynamic policies, mutation operators and death handlers](#24-plugin-interfaces) are plugins, components that can be exchanged or added without touching the rest. The question of what an environment has to provide can thereby be asked one condition at a time.

What exists today is a platform on which populations sustain themselves over billions of ticks, and two published runs in which the change of a single instruction swept to fixation and could be traced to its mechanism. The rise of complexity has not been shown in Evochora yet; the platform is built to look for it one condition at a time.

The platform consists of a runtime that runs the simulation, the EvoASM language with its compiler for writing organisms, a data pipeline that records and indexes every run, and a visualizer and an analyzer in the browser. The chapters that follow describe the physics of the environment, the tools for observing and analyzing a run, the first results, the research directions, the architecture and the limitations. A running simulation can be watched at [evochora.org](https://evochora.org/). How the project came about is told in the [Origin Story](ORIGIN_STORY.md).

### 1.1 Evochora among related systems

The table reads along the lines of the introduction: what an organism is and what its environment holds, what resources it lives on, how offspring arise and what they inherit, where selection and variation come from, and how organisms reach one another. Lenia and Flow-Lenia are included for the case the introduction mentions only in passing: creatures that are bodies in a medium but not programs.

| System | The organism | Resources | Offspring | Selection | Variation | Interaction |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Tierra** [(Ray, 1991)](#ref-ray-1991) | Executes programs in a shared memory | CPU time, memory space | Parent copies into allocated memory | Reaper; faster copying wins | Bit flips, copy errors | Others' code readable and executable, write-protected |
| **Avida** [(Ofria & Wilke, 2004)](#ref-ofria-2004) | Executes a program per cell | CPU cycles by merit; resources as numbers | Parent copies, system places the child | Replacement; rewarded tasks | Copy errors, indels | Little; messaging, predation, host-parasite coevolution in extensions |
| **Squirm3** [(Hutton, 2002)](#ref-hutton-2002) | Bonds atoms into chains | Atoms, conserved | Template replication from free atoms | Competition for free atoms | Interactions between replicators | Shared atoms |
| **Stringmol** [(Hickinbotham et al., 2016)](#ref-hickinbotham-2016) | Reacts strings in a container or on a grid [(Hickinbotham et al., 2021)](#ref-hickinbotham-2021) | Energy to execute opcodes in the original model; strings decay | One string copies another when bound | Decay, binding | Copy errors | Binding |
| **Lenia** [(Chan, 2019)](#ref-chan-2019) | Forms patterns in a continuous field | None | Not built in | None; the user selects | Parameters set by the experimenter | Collision, fusion |
| **Flow-Lenia** [(Plantec et al., 2023)](#ref-plantec-2023) | Flows conserved mass into patterns that carry their rule parameters | Mass, conserved | Not built in; patterns divide and merge | Species displace one another over mass | Parameters mix on contact; mutations added by the experimenter | Flows of mass between patterns |
| **Evochora** | Executes a spatial body as a program | Energy molecules from sources; space | Parent assembles and places; marked cells inherited | Death by energy or entropy; space | Structure-aware mutation plugins; imperfect copies | Through the environment: consume, replace, execute others' code |

## 2. The Digital Physics of Evochora

In Evochora, the environment is a grid of cells, each holding one molecule. An organism is a virtual machine with an instruction pointer that executes those molecules as code and data pointers that read and write them. Its actions cost energy, which it has to gather, and produce entropy, which it has to shed. The body of an organism is the cells it owns. [Energy sources, thermodynamic policies, mutation operators and death handlers](#24-plugin-interfaces) are configured for each run and define the physics of the environment. This chapter describes these parts in turn, and the ancestor every run begins from.

### 2.1 The Environment and Its Molecules

The environment is an n-dimensional grid. A run configures how many dimensions it has, how large it is in each, and whether its edges wrap around as a torus or bound it. Every cell of the grid holds one molecule. A molecule has a type and a value: the type says what the molecule is to an organism, the value what it carries. There are eight types:

- **CODE**: an instruction of the virtual machine. The value 0 is the NOP instruction and stands for an empty cell.
- **DATA**: a number, used as an argument of instructions.
- **STATE**: a DATA value an organism has written for its own use. It is working memory, not code, so that a value an organism keeps changing can be told from the code it carries. In arithmetic and comparisons it behaves like DATA; Section 2.3 says how a write becomes STATE.
- **ENERGY**: a quantity of energy an organism can take up.
- **STRUCTURE**: an obstacle. Pointers do not pass it, and removing it costs energy.
- **LABEL**: a mark in the code that jumps aim at. Section 2.2 says how a jump finds it.
- **LABELREF**: the argument of a jump, naming the label it aims at.
- **REGISTER**: the argument of an instruction, naming a register of the virtual machine.

All eight are read, written and consumed alike; the last three differ from the first five only in what they mean to a program.

A molecule has one more property, the marker. It belongs to ownership and is introduced with it in Section 2.3.

### 2.2 The Virtual Machine

Each organism runs on a virtual machine of its own. The machine executes the code that lies in the grid and acts on the grid through pointers; it sees nothing of the environment beyond the cells around its pointers. See the [Assembly Specification](ASSEMBLY_SPEC.md) for all details of the machine, its instruction set and the language.

### Fig. 1: An organism's virtual machine and its environment
```
   +-------------------------------------------------------------+
   |               Environment: n-dimensional grid               |
   |       [CODE] [DATA] [STATE] [ENERGY] [STRUCTURE] [...]      |
   +--------------^----------------------------^-----------------+
                  |                            |
            execute code             read, write, consume
                  |                            |
   +--------------|------- Virtual machine ----|-----------------+
   |     +--------v--------+        +----------v----------+      |
   |     | Instruction     |        | Data pointers       |      |
   |     | pointer         | ------>| DP0 ... DPn         |      |
   |     +-----------------+        +---------------------+      |
   |         registers, stacks, energy (ER), entropy (SR)        |
   +-------------------------------------------------------------+
```

The **instruction pointer** executes an instruction with its arguments and goes on to the next; empty cells it skips. The next instruction lies in the direction of the **direction vector**, which the program can set, so a program is a line of cells through the grid. The **data pointers** are where the organism touches the environment: every molecule it reads, writes or consumes lies at a data pointer or one step from it. A data pointer moves one cell at a time; it cannot jump freely, only to positions the organism has stored before.

The machine has registers for numbers, vectors and positions, in several banks so that procedures can keep values of their own, and three stacks, for data, for calls and for positions; see [Registers](ASSEMBLY_SPEC.md#registers) and [Stacks](ASSEMBLY_SPEC.md#stacks) in the specification.

Jumps do not target addresses. A jump names a **label**, and the machine searches the grid for a LABEL molecule with that value. The search tolerates small differences, so a label whose value has mutated is still found; and it can reach into the code of other organisms, so one organism can execute another's code. How tolerant the search is and how far it reaches is set for each run; see [Fuzzy label matching](ASSEMBLY_SPEC.md#fuzzy-label-matching) in the specification.

### 2.3 Ownership and Thermodynamics

An organism owns cells of the grid, and the cells it owns are its body. Empty cells belong to nobody. A cell becomes the organism's when it writes a molecule into it. Its data pointers can only move through empty cells and through its own cells; cells of other organisms they cannot enter. What an organism can do with a neighboring cell is inspect its molecule or take the molecule up, which empties the cell and ends the neighbor's ownership of it. When an organism dies, all its cells become free; what happens to the molecules in them decides a death handler, a plugin described in [Section 2.4](#24-plugin-interfaces). Details are in the specification under [Ownership](ASSEMBLY_SPEC.md#ownership).

Ownership also decides what a child inherits. When an organism reproduces, the child receives cells from the parent's body and energy from the parent's account. The parent controls which cells: it can mark the molecules it writes, and the marked cells are the ones that pass to the child. It can mark a wall of STRUCTURE around its body, ENERGY it has put aside in its cells, or a copy of its code, and it leaves unmarked what it wants to keep. A DATA value written without a mark becomes STATE, the organism's private working memory, and is never inherited. Details are in the specification under [Molecule Marker](ASSEMBLY_SPEC.md#molecule-marker). Which of the inherited cells count as the organism's genome is a definition for the analysis, not a rule of the physics ([Analyzing a Run](#33-analyzing-a-run)).

Everything an organism does costs it something, in two accounts, the energy register (ER) and the entropy register (SR): instructions cost energy and produce entropy. An organism gathers energy from the environment and sheds entropy by writing molecules into it. It dies when its energy is used up or its entropy exceeds the limit. So an organism that only takes up and destroys dies of its entropy, while one that builds can live on. The idea comes from Schrödinger, who described living systems as keeping themselves up by passing entropy on to their surroundings [(Schrödinger, 1944)](#ref-schrodinger-1944). What an instruction costs is set by thermodynamic policies, which are configured for a run; for reading and writing, a policy can depend on the type and value of the molecule and on whether the cell is the organism's own, foreign or unowned. Details are in the specification under [Thermodynamic Configuration](ASSEMBLY_SPEC.md#thermodynamic-configuration).

In Evochora's default configuration, taking up an ENERGY molecule credits its value to the energy account. Writing a molecule lowers the entropy account, with two exceptions: STATE, because it is the organism's own memory and not something built into the environment, and ENERGY, because the organism can take it back unchanged. Taking a molecule up from a foreign cell costs several times more than from an own or unowned cell, so that taking a neighbor's body apart is expensive. A run can set each of these differently.

### 2.4 Plugin Interfaces

The physics is modular, so that its parts can themselves be the subject of an experiment. For each of the following aspects there is a plugin interface, with standard plugins that come with Evochora; a different behavior can be implemented in Java.

**Resource distribution** — How does energy enter the environment? Built-in models include continuous probabilistic spawning (analogous to solar radiation), periodic eruptions at fixed locations (geysers), one-time seeding, and vaults of energy behind walls of STRUCTURE that are refilled. A run can replace or combine them.

**Thermodynamic costs** — What does an action cost? Thermodynamic policies set the energy and entropy of each instruction, and for reading and writing they can depend on the molecule and on who owns the cell ([Section 2.3](#23-ownership-and-thermodynamics)). A run can change them or use new ones.

**Mutation** — What happens to an organism's genome during reproduction? Four built-in mutation operators insert, substitute, delete and duplicate code. They are structure-aware: they read the body as the machine would, so what they insert is executable and what they duplicate or delete is a whole block of code. A run can configure, combine or replace them.

**Death and recycling** — What happens when an organism dies? The standard plugin replaces a dead organism's molecules with one configured molecule. By default that is the empty cell, so the body disappears; configured as ENERGY, the dead become food for others.

**Label matching** — How does a jump find its label? The standard strategy matches by Hamming distance, own labels first ([Section 2.2](#22-the-virtual-machine)), and passes label values unchanged from parent to child, so that relatives share their addresses; an optional namespace flip at birth lets lineages drift apart in them.

**Instruction interception** — An interface for changing instructions before they execute, for example to let a region of the environment corrupt them like radiation. No standard plugin uses it yet.

### 2.5 The Ancestor

An ancestor is a self-replicating program written by hand, and a run starts from one or several of them. Three come with Evochora, in [`assembly/ancestors`](../assembly/ancestors). The default one is put together from the [library of behaviors](../assembly/lib/behavior), reusable modules an organism can be built from, and does three things in a loop. It harvests: a data pointer walks through the surroundings and takes up the energy it finds. It hoards: it keeps a store of energy inside its body, filled in good times and drawn on when energy runs low. And it reproduces when it has energy to spare or entropy to shed: it builds the child's shell of STRUCTURE first, then the body row by row, marks both for the child, and gives up when the place for the shell turns out to be taken by someone else. A flag makes it aggressive instead: it then takes up what lies in the way, as long as its energy and entropy allow. The other two ancestors are earlier designs kept for comparison; the header of each program says what it does.

## 3. Running Experiments

An experiment in Evochora is a configuration file and one or more ancestors. This chapter describes how a run is set up and started, how it is observed, how its data are analyzed, and how a run is continued or recorded again.

### 3.1 Setting Up a Run

A run is described by a configuration file. The file that comes with Evochora, [`config/evochora.conf`](../config/evochora.conf), is the template: copy it, change what the experiment needs, and start Evochora with it, as the [Quick Start](../README.md#quick-start) of the README and the [CLI usage](CLI_USAGE.md#start-simulation-node) describe. The file sets the environment, its dimensions, size and topology; the ancestors, each with its program, its starting position and its initial energy; the plugins ([Plugin Interfaces](#24-plugin-interfaces)) with their options; and the seed. A program can take settings from the configuration as well, as [flags](CLI_USAGE.md#flags---define) and [values](CLI_USAGE.md#values---value). Every setting is documented where it is defined, in the [reference configuration](../src/main/resources/reference.conf).

A run does not record every tick. A recording profile sets the sampling interval, the number of ticks between two recorded states, and nothing downstream sees finer than that: the standard profiles record every tick, every hundredth or every ten-thousandth. The interval is a choice between detail and cost. An environment of millions of cells recorded every tick fills storage fast, and writing it slows the simulation down until the disk, not the processor, sets the pace. Long runs in large environments are therefore usually recorded sparsely, and a stretch that turns out to matter can be recorded again in detail afterwards ([Resuming and Re-recording a Run](#34-resuming-and-re-recording-a-run)).

### 3.2 Observing a Run

The visualizer shows a run in the browser, recorded tick by recorded tick, while the run is going on or afterwards. It draws the environment as a two-dimensional grid; a run in more dimensions is recorded and analyzed like any other, but it cannot be drawn. For a selected organism it shows the registers, the stacks, energy and entropy, the instruction being executed and the next one, and the line of the ancestor's source this code came from, where the code has not mutated. The mutations in the organism's lineage are marked in the grid, and its ancestry is drawn as a strip from the founder to its parent. A public instance at [evochora.org](https://evochora.org/) shows a recorded run.

![The visualizer: the environment with organisms, and the state of a selected organism](https://github.com/user-attachments/assets/3bf0aa8e-f2bd-4b98-a03f-dd33d449ed93)

For long runs, the `video` command renders the recorded data into a film ([CLI usage](CLI_USAGE.md#render-simulation-videos)).

### 3.3 Analyzing a Run

The analyzer, also in the browser, shows the metrics of a run as charts over its whole length: how the population develops, how organisms are born and die and of what, how genomes change and spread, which instructions are executed and which fail, and what the environment consists of. Its data are the metrics that analytics plugins compute from the recorded states and export as Parquet files, the standard columnar format of data science; a run can add plugins of its own, and their metrics appear beside the others.

![The analyzer: charts of a run's metrics](https://github.com/user-attachments/assets/eb83082c-0a04-4572-aaa3-2e601a1322c3)

Analysis is not limited to these tools. The same Parquet files can be loaded into Python or R, into a Jupyter notebook or into DuckDB, where researchers can apply their own methods: phylogenetic reconstruction, diversity indices, survival analysis, genome comparison, or whatever their field uses. A [Jupyter notebook](https://colab.research.google.com/github/evochora/evochora/blob/main/notebooks/data_analysis_guide.ipynb) demonstrates this with phylogenetic trees, Muller plots and cross-metric analysis using pandas, networkx and DuckDB.

Phylogenies, Muller plots and genome comparison all rest on one question: what counts as a genome. Organisms in Evochora have no genome in the biological sense: there is no separation of genotype and phenotype, the body is the program, and what a child inherits is whatever its parent marked for it. The platform does not define a genome; it records what an organism is born with. For analysis, a genome is therefore an operational definition: the cells a newborn owns, normalized for its label namespace (see label matching under [Plugin Interfaces](#24-plugin-interfaces)), minus the molecules a run declares to be the organism's own work rather than its inheritance. By default these are its STATE cells and the STRUCTURE shell the ancestor builds around every child: both differ between copies of one genome without a change in the code, and counting them would make such copies look like mutants. The definition is a parameter of the run and is recorded with it. Within it, a change in any cell, damage included, counts as a change of genome. A separation of genotype and phenotype, a region of data that steers the behavior, read by logic the mutation operators do not touch, is something an organism could implement itself; whether it evolves or is designed is an experimental question, not a rule of the environment.

Evochora does not try to be an analysis suite. The possible analyses range from phylogenetic trees and Muller plots to fitness landscapes and complexity measures such as assembly theory [(Sharma et al., 2023)](#ref-sharma-2023). Rather than implementing each of them, the platform provides the data in a form that lets researchers bring their own methods.

### 3.4 Resuming and Re-recording a Run

A run that was stopped can be continued with `node resume` from a recorded state near its end. The ticks between that state and the stop are computed again, identically ([Reproducibility](#62-reproducibility)), so a sparse recording loses nothing of the run. A stretch of a run can be recorded again with `node fork`: Evochora replays the run from a recorded state before the chosen tick and records the stretch as a new run, with a sampling interval of its own, every tick if needed. This is how an event found in the sparse record of a long run can be examined in detail ([CLI usage](CLI_USAGE.md#fork-a-run-node-fork)).

## 4. First Results

<!-- To be written: the findings published as experiment records, what they show and how they were found; room for what runs revealed about the physics itself. -->

## 5. Research Directions

The stability mechanisms described in Section 2 — thermodynamic coupling, fuzzy addressing, and defensive replication — have produced viable populations sustaining themselves over billions of ticks. Whether a simulation produces genuine evolutionary dynamics or merely random variation has to be established case by case; for Evochora, a first documented instance exists — a completed selective sweep of a heritable variant, published as the platform's first experiment record [(Evochora Project, 2026)](#ref-evochora-2026). Frameworks for classifying long-term evolutionary behavior [(Bedau et al., 2000)](#ref-bedau-2000) provide a broader lens for this question; the research directions below provide others. Some are already experimentally accessible with the current implementation; others require additional development. Each represents a testable hypothesis enabled by the platform's architecture.

### 5.1 Replicator Ecology

A replicator ecology arises when several kinds of self-replicating programs live in one environment on the same supply of resources. Can two strategies that live on the same resource coexist, or does one exclude the other? What does space change? When does conflict pay? Theory has answers that experiments with living organisms can test only indirectly. Where individuals stay in place and meet only their neighbors, peaceful strategies can survive in patches that a mixed population would lose [(Nowak & May, 1992)](#ref-nowak-may-1992). Whether aggression pays depends on the cost of a fight set against the value of what is fought over, and a population often settles on a mixture of strategies [(Maynard Smith & Price, 1973)](#ref-maynard-smith-price-1973); in nature, the cost of a fight cannot be set.

Digital systems have produced such ecologies. In Tierra, an ecology of parasites, immune hosts and hyperparasites arose within a few million instructions [(Ray, 1991)](#ref-ray-1991) ([Host–Parasite Coevolution](#52-hostparasite-coevolution)). In Avida, predators that consume other organisms were added as an extension [(Wagner et al., 2013)](#ref-wagner-2013). In Evochora, strategies compete for energy and for space in one currency. Harvesting, hoarding, a child's body and its shell all cost energy, and so does aggression: taking up a cell that belongs to a neighbor costs what the run's thermodynamic policy sets for it. Defense is construction, paid before any attacker comes. Organisms meet only as neighbors, and a body blocks the pointers of others, so a place is a resource too.

Evochora can turn these questions into experiments through its ancestors and its configuration. A run can be seeded with several strategies, and the cost of a fight is a setting of the run. Since the default ancestor is assembled from a library of behaviors ([The Ancestor](#25-the-ancestor)), a strategy can be a variant of it: a behavior exchanged, a flag set, a threshold changed ([Setting Up a Run](#31-setting-up-a-run)), as in its aggressive variant. Conceivable experiments start with two strategies on the same energy supply, to see whether they coexist or one excludes the other, and whether the density and the arrangement of the energy sources decide it. The cost of aggression can be varied between runs, to find the price at which aggressors lose, and the shell the ancestor builds around each child can be tested in populations with and without aggressors, to see whether the defense pays. Long runs can show whether evolution alters the strategies or makes them converge. Every organism's record names the ancestor program it descends from, so the fate of each strategy can be read from the data, and the visualizer colors organisms by lineage. An experiment of this kind can be set up today from a configuration and its ancestors. The analytics count the population by genome, so comparing strategies is a query on the recorded data; a metric by ancestor program, a small addition, would make it a chart of the analyzer.

Coexistence through niches lies one step further. Two strategies that live on different things need a second kind of energy or a molecule only some can use, which is the chemistry of [Matter and Chemistry](#57-matter-and-chemistry).

### 5.2 Host–Parasite Coevolution

Parasites appear wherever a replicator can use another's machinery. In Tierra they arose in the first runs: programs that had lost their copy routine and used a neighbor's, hosts immune to them, and hyperparasites that turned the parasites' processing to their own reproduction [(Ray, 1991)](#ref-ray-1991). In Stringmol they arise almost at once. A mixed population dies of them, while a spatial one forms waves in which hosts and parasites pursue one another and both persist; under that pursuit the replicators evolve ways to tell their own copies from parasites, the parasites evolve around them, and when parasites become rare the hosts shed their defenses again [(Hickinbotham et al., 2021)](#ref-hickinbotham-2021). In Avida, where every organism runs on a processor of its own, parasites had to be introduced; hosts that coevolved with them developed more complex functions and more evolvable genomes than hosts without them [(Zaman et al., 2014)](#ref-zaman-2014). Parasitism is among the few mechanisms for which a drive toward complexity has been shown in digital systems.

In Evochora, how a jump finds its label is a plugin of the run. The standard label matching ([Plugin Interfaces](#24-plugin-interfaces)) takes a neighbor's label only when none of the organism's own matches. The instruction pointer executes what stands in a cell, whoever owns it; the data pointers never enter another's cells ([Ownership and Thermodynamics](#23-ownership-and-thermodynamics)), so a neighbor's body can be read only from outside, and written into only by exchanging a cell at its edge. Organisms that jump to a neighbor's label and execute its code have been observed in many runs. Under the standard strategy this happens when an organism's own label is missing or changed, by a flawed copy or by a mutation, and what it executes then is a relative's routine in place of the one it lost.

Whether that becomes a way of life is the question. A lineage that lives on a neighbor's routine needs a relative with matching labels within reach, generation after generation; whether it can keep one, and what follows if it does, waves, defenses and their loss as in Stringmol, is open. The conditions are settings of a run: the reach and tolerance of the label search, where the ancestor places its children, the price of a foreign read, and the namespace flip, a plugin option that changes every label of a newborn by one bit, so that the addresses of lineages drift apart with the generations and out of a parasite's tolerance. Which organism executed whose code stands in the record at every recorded tick, as the position of its instruction pointer against the owner of the cell; counted over a whole run, it would be a metric, computed by a query on the record or by a new analytics plugin.

Two things lie beyond what arises by itself. A parasite ancestor could be written by hand with the system as it is: a program that writes cells into a neighbor's body, so that the neighbor's instruction pointer executes logic that serves the parasite, paid with the neighbor's energy. The second would require an instruction added to the system: a jump whose tolerance and reach the program sets itself would place the search under selection, and whether selection widens it toward exploitation or narrows it is a question only such an instruction can put.

### 5.3 Environmental Heterogeneity and Dynamic Worlds

In biological evolution, environmental heterogeneity is a primary driver of diversification. Geographic barriers create isolated populations that diverge independently [(Mayr, 1963)](#ref-mayr-1963), resource gradients produce niche specialization, and catastrophic events reset ecological landscapes, opening opportunities for adaptive radiation [(Schluter, 2000)](#ref-schluter-2000).

Evochora's current environment is largely homogeneous — energy is distributed uniformly, and the spatial landscape does not change over time. Introducing heterogeneity along both spatial and temporal axes opens a rich experimental space. Spatial heterogeneity can be created using STRUCTURE molecules as impassable barriers (rivers, mountain ranges), defining resource-rich and resource-poor regions, or establishing isolated "islands" connected by narrow corridors. Temporal dynamics can range from gradual environmental shifts — slow migration of resource zones — to sudden catastrophes that destroy local populations and reset regions to empty space.

The scientific question is direct: does environmental structure alone drive diversification in digital organisms, as it does in biological evolution? Can geographic isolation produce divergent lineages from a single ancestor? Do populations recover differently after catastrophic events depending on their mutation regime or replication strategy? These experiments require no changes to the core runtime — only configuration of the existing plugin architecture for resource distribution and STRUCTURE placement.

### 5.4 Evolvability and Mutation Regimes

Mutation is the ultimate source of evolutionary innovation, yet its implementation in most artificial life platforms remains surprisingly simplistic — typically a single, globally applied mechanism such as random bit-flips at a fixed rate [(Ray, 1991)](#ref-ray-1991). This ignores both the concept of evolvability — the capacity of a system for adaptive evolution, which itself evolves [(Wagner, 2005)](#ref-wagner-2005) — and the role of non-adaptive forces such as drift and mutation pressure in shaping genome architecture [(Lynch, 2007)](#ref-lynch-2007). By hard-coding a specific mutation model, simulations inadvertently fix the topology of the fitness landscape, potentially blocking routes to higher complexity.

Evochora treats mutation as a first-class experimental variable. Four independent mutation operators act at the moment of reproduction, each with distinct configurable parameters:

- **Gene insertion** places syntactically valid instruction chains into empty genome regions, with configurable instruction filters, register bank restrictions, and label mutation rates.
- **Gene substitution** mutates individual molecules in place, with a separate weight and strategy per molecule type — opcodes can be mutated at the operation, family, or variant level, while numeric values undergo scale-proportional perturbation. Every registered molecule type carries a weight, so which parts of a body are exposed to substitution is itself an experimental parameter; an organism's own state (STATE) is excluded by default.
- **Gene deletion** removes code blocks between labels, with configurable bias toward duplicate regions (mimicking tandem repeat instability in biological genomes).
- **Gene duplication** copies labeled code blocks into empty regions, providing raw material for subfunctionalization.

A separate, non-mutational mechanism — the **namespace flip** — XORs all labels and label references of a newborn with a mask of one bit, at a configurable rate. Inside the organism nothing changes, because internal Hamming distances are preserved; between lineages the difference in addresses grows with genealogical distance. With a rate of zero, all descendants of a founder share one address space.

Complementing these operators, the fuzzy label matching system adds a further dimension: tolerance (how many bit errors a jump can absorb), the reach within which a reference without an own match resolves to a foreign label — from never to anywhere in the world, shortened by every differing bit — and the stochastic spread when multiple matching own labels exist. Together, these parameters define a high-dimensional space of mutation regimes.

The experimental program is straightforward: by systematically varying these parameters — individually and in combination — researchers can ask how the structure of variation shapes evolutionary outcomes. Does higher insertion rate accelerate innovation or destabilize populations? Does biased deletion of duplicate regions prevent or promote genome expansion? Does increasing fuzzy jump tolerance create a wider neutral network, allowing populations to cross fitness valleys that trap organisms with stricter matching? These are empirical questions that the platform is built to answer.

Additional mutation mechanisms not yet implemented but architecturally straightforward could further expand this space: environment-driven mutation ("cosmic radiation") that decouples variation from reproduction, copy-error mutation tied to the POKE instruction rather than birth, or somatic mutation affecting an organism's runtime state (registers, stacks) without altering its heritable genome.

### 5.5 The Bioenergetics of Complexity: A Digital Eukaryogenesis

A central puzzle in evolutionary biology is why life on Earth remained microscopic and relatively simple for billions of years before the sudden explosion of complex eukaryotic life. The leading hypothesis, the **"Energetics of Genome Complexity"** [(Lane & Martin, 2010)](#ref-lane-martin-2010), posits that prokaryotes are fundamentally constrained by their bioenergetics: a single cell cannot expand its genome size (and thus complexity) significantly because its energy generation is limited by its surface area. The transition to complexity (Eukaryogenesis) was only possible through endosymbiosis—the internalization of energy-producing bacteria (mitochondria)—which broke this barrier by internalizing the surface area for energy production.

Evochora is uniquely architected to test if a similar **"Processing Power Constraint"** exists in digital life. A traditional single-threaded digital organism faces a trade-off analogous to the prokaryotic constraint: a single Instruction Pointer (IP) must be shared between "metabolic" maintenance (foraging for energy) and "complex" behaviors (navigation, construction). As the genome grows more complex, the cost of maintaining it (execution cycles) outstrips the organism's ability to gather energy sequentially.

The basic mechanism already exists: Evochora's `FORK` instruction can spawn a secondary, fully independent VM context — complete with its own registers, stacks, and data pointers — within the same organism body. However, without coordination, two execution contexts on shared code are merely co-located competitors, not cooperating subsystems. The real scientific challenge is the evolution of coordination: how do two threads within the same body synchronize their actions? One approach, already possible with the current instruction set, is indirect signaling via molecules placed in the shared environment. A more structured approach — direct messaging channels between co-located execution contexts — would require extending the runtime. Whether coordination emerges through existing mechanisms or requires new instructions is itself an open question. The hypothesis is that this internal parallelization, once coordinated, is not just a performance optimization but a necessary prerequisite for the evolution of higher-order complexity.

### 5.6 From Internal Coordination to Multicellularity

The coordination challenge described in the previous section has implications beyond individual organisms. If internal parallelism requires signaling between co-located execution contexts, the same mechanisms could be externalized for communication between neighboring organisms. This leads to a hypothesis about the **Major Transition to Multicellularity** [(Maynard Smith & Szathmáry, 1995)](#ref-maynard-smith-1995): the signaling machinery evolved for *internal* coordination acts as a **pre-adaptation (exaptation)** for *external* cooperation.

The predicted trajectory follows three stages:

1. **Prokaryotic**: Simple, single-threaded organisms limited by the processing power constraint (one IP).
2. **Eukaryotic**: Internal parallelism via `FORK` breaks the energy constraint, requiring coordination — initially through molecules deposited in the shared environment, potentially through dedicated signaling channels.
3. **Multicellular**: The same coordination mechanisms are externalized. Genetically related neighbors — siblings from the same parent, where kin selection [(Hamilton, 1964)](#ref-hamilton-1964) favors cooperation — begin to coordinate, forming higher-level units of individuality through a **Fraternal Transition** [(Moreno & Ofria, 2022)](#ref-moreno-2022).

With the current instruction set, inter-organism signaling is already possible in principle: organisms can deposit and read molecules in the environment, functioning as a primitive broadcast mechanism analogous to biological quorum sensing [(Bonabeau et al., 1999)](#ref-bonabeau-1999). Whether this is sufficient for the emergence of cooperation, or whether dedicated signaling instructions are needed to enable richer coordination — targeted messaging, persistent bonds between neighbors, or direct resource transfer — is an open experimental and design question.

Acknowledging that the spontaneous evolution of such complex architectures could take prohibitively long (mirroring the "Boring Billion" stasis in Earth's history), researchers can seed experiments with engineered multi-threaded primordials that already possess internal coordination, testing directly whether pre-existing signaling channels accelerate the transition to multicellularity.

### 5.7 Digital Chemistry and Reaction Networks

Evochora's current thermodynamic model — with its two-axis energy/entropy economy — already provides meaningful selection pressure. But the resource landscape remains simple: organisms harvest a single type of energy molecule. Biological ecosystems, by contrast, are driven by complex chemical reaction networks where the waste products of one metabolic pathway become resources for another, giving rise to trophic webs and circular economies.

A natural extension of Evochora's physics is the introduction of a **reaction system**. Instead of a single ENERGY molecule, the environment could contain multiple substrate types — either as new molecule types or encoded in the value bits of existing types. Energy acquisition would no longer be a simple PEEK operation but a **reaction** catalyzed by the organism: combining specific substrates (A + B → C + Energy + Entropy) to unlock energy. This creates the possibility of **reaction chains**, where complex metabolic pathways must evolve to access high-yield energy sources, while simpler reactions yield less [(Dittrich et al., 2001)](#ref-dittrich-2001).

This approach offers an intriguing parallel to Avida's task-based fitness rewards [(Ofria & Wilke, 2004)](#ref-ofria-2004) — but with a crucial difference. In Avida, the reward structure (which logic functions earn resources) is externally defined by the researcher. In a reaction-based chemistry, the "fitness landscape" emerges intrinsically from the physics of the world itself. Organisms that evolve more sophisticated metabolic pathways gain access to richer energy sources, much as biological evolution produced photosynthesis and aerobic respiration — not because an external reward function demanded it, but because the chemistry of the environment made it possible.

Such a system naturally leads to **niche construction** [(Odling-Smee et al., 2003)](#ref-odling-smee-2003). Reactions produce byproducts that accumulate in the environment, altering it over time. A "waste" product might become the substrate for a new mutant that evolves the machinery to consume it — closing the loop and creating a new ecological niche. This echoes the dynamics observed in **Chromaria** [(Soros & Stanley, 2014)](#ref-soros-2014), where the physical remains of organisms reshape the evolutionary landscape for subsequent generations. The death handler plugin already demonstrates this principle in miniature: it can be configured to convert dead organisms into energy molecules or other substrates, creating a basic nutrient cycle. A full reaction system would extend this into a rich chemical ecology where food webs and metabolic specialization can emerge.

## 6. Architecture, Reproducibility and Scale

<!-- To be restructured into two parts with subsections: first what matters for experiments (determinism, resume, sampling and fork, world size, technical limits, benchmarks), then the architecture. -->

The grand scientific ambitions of Evochora are predicated on the ability to run large-scale experiments that are both computationally feasible and scientifically rigorous. Unlike many prototype simulations, Evochora is built on a mature software engineering foundation designed for long-term stability and distributed execution. This section outlines the computational framework, addressing its performance architecture, data handling capabilities, and the provisions for reproducible analysis.
For a detailed technical breakdown of the engineering choices behind this performance (e.g., why standard GPU kernels were avoided), see the **[Architecture & Design Decisions](dev/ARCHITECTURE_DECISIONS.md)** document.

### Engineering Maturity Status

| Component | Status | Feature Highlights |
| :--- | :--- | :--- |
| **Virtual Machine** | ✔ Functional | Full register set, 3 stacks, dual-pointer architecture, procedural calls. |
| **Determinism** | ✔ Verified | Identical simulation state and trajectory from seed and configuration, independent of thread count and of pause/resume; conflict resolution by computed per-tick priority. |
| **Compiler** | ✔ Functional | Multi-phase immutable pipeline with high-level assembly, procedures, macros and source-map generation. |
| **Data Pipeline** | ✔ Functional | Decoupled architecture with resume capability and delta compression (Queue -> Persistence -> Indexer -> DB). |
| **Visualizer** | ✔ Live | Web-based, tick-by-tick inspection, memory layout visualization and assembly debugger. |
| **Primordial Organism** | ✔ Viable | Self-replicating ancestor with metabolic loop and spatial replication logic. |

The following diagram illustrates the high-level data flow architecture:
```
┌────────────────────────────┐
│      SimulationEngine      │
└─────────────┬──────────────┘
              │ (TickData)
              ▼
┌────────────────────────────┐
│        Tick Queue          │
└─────────────┬──────────────┘
              │ (Batches)
              ▼
┌────────────────────────────┐
│    Persistence Service     │ (Competing Consumers)
└─┬─────────────────────┬────┘
  │ (Data)       (BatchInfo Event)
  │                     │
  ▼                     ▼
┌───────────┐    ┌───────────┐
│  Storage  │    │  Topics   │
└─────┬─────┘    └──────┬────┘
      │ (Reads)    (Triggers)
      │                 │
      └────────┬────────┘
               │
               ▼
┌────────────────────────────┐
│      Indexer Services      │ (Competing Consumer Groups)
└─────────────┬──────────────┘
              │ (Indexed Data)
              ▼
┌────────────────────────────┐
│          Database          │
└─────┬───────────────┬──────┘
      │               │ (Queries)
      ▼               ▼
┌────────────┐  ┌────────────┐
│ Visualizer │  │  Analyzer  │ (Web based)
└────────────┘  └────────────┘
```

### 6.1 Architecture

The core simulation is optimized for throughput. The raw, in-memory simulation engine achieves hundreds of thousands of instructions per second on standard consumer hardware. However, in a typical local setup where the entire data pipeline (persistence, indexing) shares the same machine resources, sustainable throughput is constrained by I/O and varies significantly with environment size, population density, and plugin configuration. This bottleneck is an artifact of the single-node configuration; the decoupled architecture is designed to overcome this by distributing services across a cloud cluster.

The primary computational load (CPU) scales linearly with the number of active organisms (O(N)). The data pipeline load (I/O) is dominated by periodic full environment snapshots whose size scales with the environment, while incremental updates between snapshots use delta compression and scale only with the number of changes per tick.

The simulation tick has snapshot semantics — every organism acts against the world as it was at the start of the tick — and is executed in two waves:

1.  **Plan and local execution**: All organisms determine their next instruction and, if it only changes their own state (arithmetic, jumps, reads), execute it immediately. This wave runs on a configurable number of worker threads over disjoint subsets of the population.
2.  **Resolve and write**: Environment-modifying instructions pass a conflict resolver that mediates competing claims on the same Molecule, then execute sequentially in organism order. The winner of a contested cell is chosen by a priority computed per tick from seed and organism identity, so no organism is favored by its age or ID and every contender is expected to win a contested cell equally often over time; a loser pays the cost of a failed instruction and retries.

The thread count is an execution resource, not part of the simulated physics: a run produces the same trajectory whether it uses one thread or many, and whether it was paused and resumed. For massive-scale experiments, a long-term vision involves partitioning the world into spatial regions managed by separate, distributed compute nodes. While this introduces synchronization challenges at the boundaries, the principle of locality inherent to organism behavior is expected to minimize inter-node communication, following the principles of **Indefinite Scalability** demonstrated by the **Moveable Feast Machine** [(Ackley, 2013)](#ref-ackley-2013), making this a viable path for future scaling.

### 6.2 Reproducibility

Large-scale simulations generate substantial data volumes, varying widely with environment size, snapshot frequency, and delta compression settings. The Evochora data pipeline is a decoupled, asynchronous system built on a foundation of abstract **Resources** to handle this throughput. The flow is designed for scalability: the `SimulationEngine` writes `TickData` messages to a queue. Multiple `PersistenceService` instances can act as competing consumers on this queue, writing data in batches to a durable storage resource. Downstream, various `IndexerService` types consume this stored data—again as competing consumer groups to build specialized indexes that are written to a database. This data powers the web-based visualizer. A key architectural principle is that all services are written against abstract resource interfaces (e.g., for queues and storage), allowing the underlying implementation to be seamlessly swapped from a high-performance in-process setup to a cloud-native, distributed one (e.g., message buses and object storage) without changing any service code. The indexing process is computationally intensive but parallelizable, and configurable sampling intervals allow researchers to trade temporal resolution for storage efficiency when running long simulations.

Scientific rigor is ensured through **full determinism**. Randomness consumed by the sequential parts of a tick comes from one seeded, checkpointed generator; randomness consumed while organisms execute concurrently is computed from seed, tick, organism identity and the position of the draw within the tick rather than drawn from shared state, so it depends neither on thread scheduling nor on anything that would have to be persisted. Together with the computed conflict priority this guarantees that an experiment reproduces the identical simulation state and trajectory from seed and configuration, on any number of cores and across pauses (persisted records additionally carry wall-clock capture times, which are metadata, not state).

Resume from any persisted state and delta compression for reducing data volume without sacrificing replayability are both fully implemented, enabling the use of cost-effective spot instances for very long-running experiments.

### 6.3 Performance and Scale

<!-- To be written: the cost model per organism, the benchmark figures with their conditions, world size as a question of memory, population as the measure. -->

## 7. Limitations

<!-- To be written: the current scientific limitations of the platform, each checked against the code. -->

## 8. Conclusion

Evochora is an open-source digital evolution platform designed to make the physics of a simulated world a first-class experimental variable. Its architecture — spatially distributed code in a configurable n-dimensional grid, a two-axis thermodynamic economy, fuzzy label matching for mutation-resilient genomes, and a modular plugin system for mutation, resource distribution, and death mechanics — provides researchers with a flexible testbed for a range of evolutionary experiments.

The platform has demonstrated its viability through long-running simulations sustaining stable populations over billions of ticks. Four configurable mutation operators, label namespace rewriting, a defensive primordial organism, and a fully deterministic, resumable data pipeline provide the foundation for reproducible research.

The research directions outlined in this document — from replicator ecology and environmental heterogeneity to digital eukaryogenesis, multicellularity, and reaction-based chemistry — represent a broad program of testable hypotheses. Some are immediately accessible with the current implementation; others require extensions that the architecture is designed to support. Researchers, students, and engineers are invited to use this platform, challenge its assumptions, and contribute to its development. The source code, documentation, and community channels are available at [https://github.com/evochora/evochora](https://github.com/evochora/evochora).

## 9. Getting Involved

Evochora is open source under the MIT License. Contributions of all kinds are welcome — from running experiments and reporting results to extending the plugin ecosystem or improving the data pipeline (see the [Contribution Guide](https://github.com/evochora/evochora/blob/main/CONTRIBUTING.md) for details). Anyone interested in using the platform, whether for research, exploration, or development, is encouraged to reach out — questions are answered promptly.

- **Source code and documentation:** [GitHub Repository](https://github.com/evochora/evochora)
- **Published experiment records:** [Published Experiments](PUBLISHED_EXPERIMENTS.md)
- **Live demo and simulation video:** [https://evochora.org/](https://evochora.org/)
- **Questions and discussion:** [GitHub Discussions](https://github.com/evochora/evochora/discussions)
- **Community chat:** [Discord Server](https://discord.gg/t9yEJc4MKX)

## 10. Acknowledgements

_Full disclosure: AI tools were used to assist in the writing and editing of this document. The scientific content — the design decisions, hypotheses, and experimental directions — is the author's original work._

## 11. References

- <a id="ref-ackley-2013"></a>Ackley, D. H. (2013). Indefinite scalability for living computation. In *Artificial Life 13* (pp. 603-610). MIT Press.
- <a id="ref-bedau-2000"></a>Bedau, M. A., Snyder, E., & Packard, N. H. (2000). A classification of long-term evolutionary dynamics. In *Artificial Life VII* (pp. 228-237). MIT Press.
- <a id="ref-bonabeau-1999"></a>Bonabeau, E., Dorigo, M., & Theraulaz, G. (1999). *Swarm Intelligence: From Natural to Artificial Systems*. Oxford University Press.
- <a id="ref-chan-2019"></a>Chan, B. W.-C. (2019). Lenia: Biology of Artificial Life. *Complex Systems*, 28(3), 251-286.
- <a id="ref-dittrich-2001"></a>Dittrich, P., Ziegler, J., & Banzhaf, W. (2001). Artificial chemistries—a review. *Artificial Life*, 7(3), 225-275.
- <a id="ref-eigen-1971"></a>Eigen, M. (1971). Selforganization of matter and the evolution of biological macromolecules. *Naturwissenschaften*, 58(10), 465-523.
- <a id="ref-evochora-2026"></a>Evochora Project. (2026). A completed selective sweep in an Evochora run: the restrictive setting of a reproduction switch displaces the permissive one (Version 1) [Data set]. Zenodo. [https://doi.org/10.5281/zenodo.22081453](https://doi.org/10.5281/zenodo.22081453)
- <a id="ref-hamilton-1964"></a>Hamilton, W. D. (1964). The genetical evolution of social behaviour. I. *Journal of Theoretical Biology*, 7(1), 1-16.
- <a id="ref-hickinbotham-2016"></a>Hickinbotham, S., Clark, E., Nellis, A., Stepney, S., Clarke, T., & Young, P. (2016). Maximizing the adjacent possible in automata chemistries. *Artificial Life*, 22(1), 49-75.
- <a id="ref-hickinbotham-2021"></a>Hickinbotham, S., Stepney, S., & Hogeweg, P. (2021). Nothing in evolution makes sense except in the light of parasitism: Evolution of complex replication strategies. *Royal Society Open Science*, 8(8), 210441.
- <a id="ref-hutton-2002"></a>Hutton, T. J. (2002). Evolvable self-replicating molecules in an artificial chemistry. *Artificial Life*, 8(4), 341-356.
- <a id="ref-lane-martin-2010"></a>Lane, N., & Martin, W. (2010). The energetics of genome complexity. *Nature*, 467(7318), 929–934.
- <a id="ref-lynch-2007"></a>Lynch, M. (2007). *The Origins of Genome Architecture*. Sinauer Associates.
- <a id="ref-mayr-1963"></a>Mayr, E. (1963). *Animal Species and Evolution*. Harvard University Press.
- <a id="ref-maynard-smith-price-1973"></a>Maynard Smith, J., & Price, G. R. (1973). The logic of animal conflict. *Nature*, 246(5427), 15-18.
- <a id="ref-maynard-smith-1995"></a>Maynard Smith, J., & Szathmáry, E. (1995). *The Major Transitions in Evolution*. Oxford University Press.
- <a id="ref-nowak-may-1992"></a>Nowak, M. A., & May, R. M. (1992). Evolutionary games and spatial chaos. *Nature*, 359(6398), 826-829.
- <a id="ref-odling-smee-2003"></a>Odling-Smee, F. J., Laland, K. N., & Feldman, M. W. (2003). *Niche Construction: The Neglected Process in Evolution*. Princeton University Press.
- <a id="ref-ofria-2004"></a>Ofria, C., & Wilke, C. O. (2004). Avida: A software platform for research in digital evolution. *Artificial Life*, 10(2), 191-229.
- <a id="ref-plantec-2023"></a>Plantec, E., Hamon, G., Etcheverry, M., Oudeyer, P.-Y., Moulin-Frier, C., & Chan, B. W.-C. (2023). Flow-Lenia: Towards open-ended evolution in cellular automata through mass conservation and parameter localization. In *Proceedings of the Artificial Life Conference 2023*. MIT Press. Preprint: [https://arxiv.org/abs/2212.07906](https://arxiv.org/abs/2212.07906)
- <a id="ref-ray-1991"></a>Ray, T. S. (1991). An approach to the synthesis of life. In *Artificial Life II* (pp. 371-408). Addison-Wesley.
- <a id="ref-schluter-2000"></a>Schluter, D. (2000). *The Ecology of Adaptive Radiation*. Oxford University Press.
- <a id="ref-schrodinger-1944"></a>Schrödinger, E. (1944). *What is Life? The Physical Aspect of the Living Cell*. Cambridge University Press.
- <a id="ref-sharma-2023"></a>Sharma, A., Czégel, D., Lachmann, M., Kempes, C. P., Walker, S. I., & Cronin, L. (2023). Assembly theory explains and quantifies selection and evolution. *Nature*, 622(7982), 321–328.
- <a id="ref-soros-2014"></a>Soros, L. B., & Stanley, K. O. (2014). Identifying necessary conditions for open-ended evolution through the artificial life world of Chromaria. In *Artificial Life 14* (pp. 793-800). MIT Press.
- <a id="ref-taylor-2014"></a>Taylor, T. (2014). Evolution in virtual worlds. In M. Grimshaw (Ed.), *The Oxford Handbook of Virtuality*. Oxford University Press. Preprint: [https://arxiv.org/abs/1710.06055](https://arxiv.org/abs/1710.06055)
- <a id="ref-taylor-2015"></a>Taylor, T. (2015). Requirements for open-ended evolution in natural and artificial systems. arXiv:1507.07403. [https://arxiv.org/abs/1507.07403](https://arxiv.org/abs/1507.07403)
- <a id="ref-wagner-2005"></a>Wagner, A. (2005). *Robustness and Evolvability in Living Systems*. Princeton University Press.
- <a id="ref-wagner-2013"></a>Wagner, A. P., Zaman, L., Dworkin, I., & Ofria, C. (2013). Behavioral strategy chases promote the evolution of prey intelligence. Preprint: [https://arxiv.org/abs/1310.1369](https://arxiv.org/abs/1310.1369)
- <a id="ref-zaman-2014"></a>Zaman, L., Meyer, J. R., Devangam, S., Bryson, D. M., Lenski, R. E., & Ofria, C. (2014). Coevolution drives the emergence of complex traits and promotes evolvability. *PLOS Biology*, 12(12), e1002023.
- <a id="ref-moreno-2022"></a>Moreno, F., & Ofria, C. (2022). Exploring the Fraternal Transitions to Multicellularity in Digital Evolution. *Artificial Life*, 28(1), 1-24.