# Architecture

This document describes how Evochora is built: its packages, the role each of them plays, and how they depend on each other. Why the main choices were made is the subject of [Architecture & Design Decisions](ARCHITECTURE_DECISIONS.md).

1. [Overview](#1-overview)
2. [The compiler](#2-the-compiler)
3. [The runtime](#3-the-runtime)
4. [The data pipeline](#4-the-data-pipeline)
5. [The node and the frontends](#5-the-node-and-the-frontends)
6. [Determinism](#6-determinism)
7. [Distributed execution](#7-distributed-execution)

## 1. Overview

Evochora is one Java code base with five top-level packages under `org.evochora`. Each has one job.

**Runtime** (`runtime`, [section 3](#3-the-runtime)) is the simulation itself: the [environment](#the-environment), the [organisms](#the-organism), the instruction set and the [plugins](#plugins). `Simulation` advances the simulation [tick](#the-tick) by tick. The runtime computes and does nothing else. It reads no file and writes none, and it depends on no other package. Everything around it can therefore change without touching it.

**Compiler** (`compiler`, [section 2](#2-the-compiler)) turns a program written in [EvoASM](../EVOASM_SPEC.md) into the molecules that the runtime executes. Its result is a `ProgramArtifact`. It holds the machine code with the position of every molecule, and the maps that link each position back to the source line it came from. The compiler depends on the runtime, for the instruction set and for what else a program has to fit.

**Data pipeline** (`datapipeline`, [section 4](#4-the-data-pipeline)) exists because the runtime does no input or output. The `SimulationEngine` drives the simulation, captures the state of each recorded tick and hands it to a queue. Behind the queue, separate services store the [recording](#the-recording), index it and compute the analytics. Nothing but the processor and the memory sets the pace of the simulation. Only a recording so dense that the storage cannot keep up with it binds the simulation to the storage. The pipeline depends on the runtime, because it drives the simulation and records its state. It depends on the compiler, because it compiles the ancestors of a run and keeps the compiled programs with it.

**Node** (`node`, [section 5](#5-the-node-and-the-frontends)) is the process that runs all of this. It starts the pipeline's services, the message brokers between them, the HTTP server with the controllers that the web frontends call, and the database servers. The node depends on the pipeline and the runtime.

**CLI** (`cli`, [section 5](#5-the-node-and-the-frontends)) is the entry point. Its commands start a node in one of its modes, or they work on programs and recordings without one. The [CLI Usage Guide](../CLI_USAGE.md) describes them. The CLI may depend on every other package.

The dependencies point in one direction, from the entry point down to the simulation:

```text
cli          →  node, datapipeline, compiler, runtime
node         →  datapipeline, runtime
datapipeline →  compiler, runtime
compiler     →  runtime
runtime      →  (nothing)
```

The same graph as layers, from the entry point down to the simulation. The list above is the rule, the picture only illustrates it:

```text
            ┌──────────────┐
            │     cli      │
            └──────┬───────┘
                   ▼
            ┌──────────────┐
            │     node     │
            └──────┬───────┘
                   ▼
            ┌──────────────┐      ┌──────────────┐
            │ datapipeline │ ───▶ │   compiler   │
            └──────┬───────┘      └──────┬───────┘
                   ▼                     ▼
            ┌───────────────────────────────────┐
            │              runtime              │
            └───────────────────────────────────┘
```

A sixth package, `util`, holds helpers that belong to no package in particular and depend on nothing. Every package but the runtime may use it. `PackageDependencyRulesTest` enforces this graph: an import that the graph does not allow fails the build and names the classes involved. The graph is repeated here for orientation. When the two disagree, the test is right.

## 2. The compiler

The compiler turns an EvoASM program ([Assembly Specification](../EVOASM_SPEC.md)) into molecules, and molecules are all the runtime ever sees of it. The runtime executes what stands in the cells of the environment. It holds no symbol table, no procedure names and no source lines, and the dependency rules forbid it to reference the compiler at all. This is what lets mutated and self-modified code run. A program that evolution has changed has no source any more, and the runtime does not need one. Everything the compiler knows beyond the molecules is kept for people and tools in the run's [metadata](#the-recording): the names, the procedures, the line each molecule came from. The source view of the [visualizer](#5-the-node-and-the-frontends) reads it there and shows the source line next to the machine instruction compiled from it.

The compiler's input is the source text of a program with the modules it imports. Its output is a `ProgramArtifact`. The artifact holds the machine code as molecules at relative positions and the molecules the program places in the environment around itself. It also holds the maps from every molecule back to its source line. `Compiler` produces it in phases, in a fixed order that only this class knows. The frontend reads the text into tokens, builds a syntax tree (AST) from them and checks it. The backend turns the tree into an intermediate representation (IR) of instructions and directives, lays it out in the grid and emits the molecules. The phases are the pipeline. The language itself is built from features: packages that each add one capability of the language by registering handlers with the phases they take part in.

```text
 frontend                               backend
 ┌─────────────────────┐                ┌─────────────────────┐
 │        Lexer        │            ┌──▶│    IR generation    │
 └──────────┬──────────┘            │   └──────────┬──────────┘
            │ tokens                │              │ IR
            ▼                       │              ▼
 ┌─────────────────────┐            │   ┌─────────────────────┐
 │     Preprocessor    │            │   │     IR rewriting    │
 └──────────┬──────────┘            │   └──────────┬──────────┘
            │ tokens                │              │ IR
            ▼                       │              ▼
 ┌─────────────────────┐            │   ┌─────────────────────┐
 │        Parser       │            │   │        Layout       │
 └──────────┬──────────┘            │   └──────────┬──────────┘
            │ AST                   │              │ layout
            ▼                       │              ▼
 ┌─────────────────────┐            │   ┌─────────────────────┐
 │  Semantic analysis  │            │   │       Linking       │
 └──────────┬──────────┘            │   └──────────┬──────────┘
            │ AST                   │              │ linked
            ▼                       │              ▼
 ┌─────────────────────┐            │   ┌─────────────────────┐
 │      Token map      │            │   │       Emission      │
 └──────────┬──────────┘            │   └──────────┬──────────┘
            │ AST                   │              │
            ▼                       │              ▼
 ┌─────────────────────┐            │      ProgramArtifact
 │ AST post-processing │─ AST ──────┘
 └─────────────────────┘
```

The arrows carry the result of one phase into the next.

The phases follow a few rules that make the pipeline predictable. Each phase runs exactly once for all modules of a compilation, in the order above. It receives nothing but the result of the phase before it and returns its result, and no phase calls another. From the IR generator on, the IR is the only form the backend knows. What is not in the IR does not exist for it ([Compiler IR Specification](COMPILER_IR_SPEC.md)).

The features determine the language, and they follow rules too. A feature is one package with everything it needs across the phases: a parser handler, a semantic check, an IR converter, a rewrite rule. It registers its handlers with the phases it takes part in, and it holds no state. The state of a compilation flows through the phases. The core knows no feature, and `StandardFeatures` is the one place that lists them. A feature that builds on another declares that dependency, and no feature may reference another in any other way. Tests enforce these rules: the structure, the separation of the features, and the stability of the generated code.

What the language offers is organized by feature. There are features for procedures with parameters, for macros, for modules and imports, for the placement of code and molecules in the environment, for constants and computed expressions, for conditional compilation and for register aliases.

## 3. The runtime

The runtime is the simulation. It consists of an environment, the organisms in it, the machine that executes their instructions, and the loop that advances all of this one tick at a time. The rules of the simulation, what a molecule is, what an instruction does and what it costs, are laid down in the [Assembly Specification](../EVOASM_SPEC.md). This section explains how the runtime is put together: what it consists of, how a tick proceeds, and where plugins take part.

### The environment

The environment is a grid of cells in as many dimensions as the configuration sets. The edges either wrap around or bound the grid. Every cell holds exactly one molecule, and a molecule has a type and a value. A CODE molecule holds an instruction, a DATA molecule a number, an ENERGY molecule what an organism takes up to live on, and a STRUCTURE molecule matter that stands in the way. The [Assembly Specification](../EVOASM_SPEC.md#molecules) lists the other types. Every cell is owned by one organism or by none. The environment holds nothing else: an organism is present in it only through the cells it owns.

### The organism

An organism is two things. In the environment it is the cells it owns, its body, where its code and its data lie. Beside that it has a state the environment does not contain: pointers, registers, stacks, energy and entropy. The diagram shows both. Every tick, the virtual machine executes one instruction per organism. It reads the instruction at the organism's instruction pointer. Executing it changes the organism's state, or the environment through the data pointers.

```text
 ┌───────────────────────────────────────────────────────────────────────┐
 │                    Environment: n-dimensional grid                    │
 │                                                                       │
 │              [CODE]    [DATA]    [ENERGY]    [STRUCTURE]    [...]     │
 └─────────────────▲───────────────────────────────────▲─────────────────┘
                   │ execute code                      │
                   │              read, write, consume │
 ┌─────────────────┼───────────────────────────────────┼─────────────────┐
 │                 │             Organism              │                 │
 │     ┌───────────┴───────────┐           ┌───────────┴───────────┐     │
 │     │  Instruction pointer  │─ moves ──▶│     Data pointers     │     │
 │     └───────────────────────┘           └───────────────────────┘     │
 │                                                                       │
 │     ┌────────────────────────── State ──────────────────────────┐     │
 │     │ Data registers:      [DR0-7] [PDR0-7] [FDR0-7] [SDR0-7]   │     │
 │     │ Location registers:  [LR0-3] [PLR0-3] [FLR0-3] [SLR0-3]   │     │
 │     │ Stacks:              [DS] [LS] [CS]                       │     │
 │     │ Thermodynamics:      [ER] [SR]                            │     │
 │     │ Child ownership:     [MR]                                 │     │
 │     └───────────────────────────────────────────────────────────┘     │
 └───────────────────────────────────────────────────────────────────────┘
```

What the abbreviations stand for, and how each part of the state works, is laid down in the [Assembly Specification](../EVOASM_SPEC.md#3-the-organisms-virtual-machine).

### The tick

A tick is one step of the simulation in which every living organism executes one instruction. It proceeds in fixed stages:

```text
 ┌─────────────────────────────┐
 │        Tick plugins         │  sequential; energy enters the environment
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────┐
 │       Local execution       │  parallel; instructions that change only the organism
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────┐
 │     Conflict resolution     │  one winner per contested cell
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────┐
 │      Environment writes     │  sequential; instructions that change the environment
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────┐
 │       Death handlers        │  sequential; for the organisms that died in this tick
 └──────────────┬──────────────┘
                ▼
 ┌─────────────────────────────┐
 │       Birth handlers        │  sequential; for the children born in this tick
 └─────────────────────────────┘
```

Local execution runs on several threads because an instruction that changes only the organism cannot affect another one. Environment writes run in organism order, so the course of a run does not depend on the number of threads. When two organisms write into the same cell in one tick, one of them wins by a priority that changes every tick. Organisms die and children are created while instructions execute. The handlers that act on them run only after all writes are done, in a fixed order.

Randomness follows the same split. The sequential stages draw from one generator in their fixed order, and its state is part of the state of the run. An organism that needs a random value during local execution computes it instead. The value is a function of the seed the configuration gives the run, the tick, the organism's identity and the number of the draw, so it depends on neither the thread nor the order.

### Plugins

Plugins act at several points of a tick, each on one thing. The configuration names them and sets their options, so an experiment can change these rules without changing the runtime. There are six kinds:

- **Tick plugins** run at the start of every tick and may change anything. They answer how energy enters the environment, and how the environment changes over time.
- **Instruction interceptors** see an instruction before it is executed and may change or replace it.
- **Thermodynamic policies** set what an instruction costs in energy and entropy, priced by what it did.
- **Death handlers** decide what becomes of a dead organism's body. They see only its cells.
- **Birth handlers** work on a child before its first tick. The mutation operators are birth handlers.
- **The label matching strategy** decides how a jump finds its target label. It also decides whether a child keeps its parent's label values or is given new ones at birth.

A plugin that keeps state saves and restores it, so that a resumed run continues with the plugin where it was. The plugins that come with Evochora, and their options, are documented in the [reference configuration](../../src/main/resources/reference.conf).

## 4. The data pipeline

The runtime computes and keeps nothing. Everything that comes out of a run is made by the data pipeline. That is the recording, and it is what the two web frontends show: the visualizer with its views of a run, and the analyzer with its charts. This section follows the data from the simulation to the browser.

### The data flow

The simulation engine drives the simulation and, at a set interval, takes the state of a tick as raw data. The raw data go to a queue, and the engine goes on simulating. The queue is what keeps the pace of the simulation independent of the storage. A persistence service takes the raw data from the queue in batches and writes them to storage. It announces every batch on a topic, a channel that any number of readers can subscribe to. The indexers listen to the topic and turn the raw data into what the two frontends read. For the visualizer that is the state of every recorded tick, retrievable by tick. For the analyzer it is tables of measures over the run. The HTTP controllers of the node serve both to the browser.

```text
                       ┌───────────────────────┐
                       │   Simulation engine   │
                       └───────────┬───────────┘
                                   │ queue
                                   ▼
                       ┌───────────────────────┐
                       │ Persistence service * │
                       └───────────┬───────────┘
                                   │ storage, topic
             ┌─────────────────────┼─────────────────────┐
             ▼                     ▼                     ▼
     ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
     │  Environment  │     │    Organism   │     │   Analytics   │
     │   indexer *   │     │   indexer *   │     │   indexer *   │
     └───────┬───────┘     └───────┬───────┘     └───────┬───────┘
             │ database            │ database            │ storage
             ▼                     ▼                     ▼
     ┌─────────────────────────────────────┐     ┌───────────────┐
     │              Visualizer             │     │    Analyzer   │
     └─────────────────────────────────────┘     └───────────────┘

 * competing consumers
```

The boxes are services. The labels on the arrows name the resources they share.

The services do not call each other. They share resources: a queue, a topic, a storage and a database. Which implementation stands behind a resource is configuration. Today everything runs in one process with in-process resources, and the tests run the whole pipeline in memory. The services are built so that resources on a network could take their place. That is the subject of [section 7](#7-distributed-execution). Several instances of a service can read the same resource as competing consumers and share the work. A batch can then be delivered twice, so every service writes idempotently: a second delivery changes nothing.

On one machine, the same separation is what keeps the simulation at its own pace, lets a recording be indexed later or on another node, and lets the tests run the whole pipeline in memory. Why the pipeline took this form is told in [Architecture & Design Decisions](ARCHITECTURE_DECISIONS.md).

### The recording

A recorded tick holds the environment, every organism with its state, and every birth, death and mutation since the tick recorded before it. The events are complete in every recording, however sparse it is. What a sparse recording leaves out are the states in between.

How densely a run is recorded is part of its configuration. It sets how many ticks lie between two recorded ticks, and how the recorded ticks are written to storage. The configuration groups these settings into recording profiles and can define new ones. The density changes nothing in the run itself. It decides what is kept of the run, and how much storage that takes. It also has a limit. The simulation runs independently of the storage as long as the pipeline writes the recorded ticks away as fast as they arrive. A recording denser than the storage can take fills the queue, and the simulation then waits for the storage.

The recorded ticks are written with delta compression. At set points the complete state of the environment is written, and the recorded ticks between two complete states carry only the cells that changed. A complete state grows with the size of the environment, a delta only with what happened. A long run in a large environment therefore stays affordable. There are two kinds of delta. An incremental delta holds the changes since the recorded tick before it, an accumulated delta the changes since the last complete state. The first is small, the second lets a reader reach a tick in one step. The configuration sets how often an accumulated delta is written. Every reader of the raw data, the indexers included, rebuilds a tick from the last complete state and the deltas after it.

The metadata of a run are stored with its raw data. They are the configuration as it was resolved for the run, the seed, and the compiled programs of its ancestors. A recording is therefore complete in itself. Whoever has it and the build that wrote it can understand the run, show it, and continue it.

All raw data, the recorded ticks and the metadata, follow one data contract defined in Protocol Buffers. Every reader shares it, the services of the node as well as a tool outside the node that reads a recording directly.

Two operations work on the recording after the fact. A run can be resumed. The engine loads the last complete state, with the state of the random generator and of the plugins that was saved with it, and the run goes on. A stretch of a run can be forked. The engine loads the complete state before the stretch, computes the stretch again and records it at a finer interval. The result is a new run, and its metadata name the run and the ticks it was taken from. Neither operation changes the course of a run. The resumed ticks and the forked ticks are the ones the run took or would have taken without the interruption, and [section 6](#6-determinism) says why.

The analytics tables are computed from every n-th recorded tick, and the recording profile sets this n as well. A measure in the analyzer is therefore never finer than the recording, and a sparse recording gives coarser curves of the same run.

## 5. The node and the frontends

The services of the pipeline need their resources. Some resources are running programs: a broker for the queue and the topics, a database server, a storage service. Each of them either lies on the network or has to be started on the same machine before the services can run. The HTTP server that serves the frontends has to be started as well. This is the node: the program that starts everything a run needs on one machine and stops it again at the end. What it starts is called a process in the configuration and in the code, although all of them run inside the node itself. In the default configuration these are the brokers, the pipeline with its services and the HTTP server. Two optional processes are switched on as well: a management console for the index database, and a TCP server through which other programs can read the index. The configuration names the processes and the dependencies between them. The node starts them in that order and stops them in the reverse order. Because the configuration decides what a node consists of, a node without the pipeline is the same program and only serves recorded runs; this is what `node show` starts. A node whose resources lie on the network starts none of them locally.

The processes of a node do not call each other. What connects them are the resources of [section 4](#4-the-data-pipeline). The brokers are the processes that provide the queue and the topics. The controllers of the HTTP server read through the database and storage resources, the same ones the indexers write to. Every resource is addressed by configuration, so a process can move to another machine as soon as its resource is reachable over the network. The two brokers show the principle in the small. The queue broker and the topic broker are separate, so either can be replaced by a broker on the network while the other stays in the node.

Which services a node runs is configuration as well. The node that simulates runs the whole pipeline and records a run. The run may begin at the start, at the last complete state of an earlier run, or as a fork of one. The node that only shows runs the HTTP server over runs that are already recorded and indexed. Between the two, any part of the pipeline can form a node, because the services share nothing but their resources. The stages of a pipeline can therefore also run one after another, a recording first and its indexing later. The [CLI Usage Guide](../CLI_USAGE.md) describes the commands that start a node. Work on programs and recordings outside a run, such as compiling an ancestor or inspecting a recording, needs no node at all.

The visualizer and the analyzer are web applications. The HTTP server serves them as static files, and they read everything they show through its controllers. This is why a node that only shows needs nothing but the HTTP server, the database and the storage. The visualizer shows a run tick by tick, from the environment down to one organism, its state and the source line of the instruction it executes. The analyzer draws its charts from the tables in two ways. Either a controller merges and filters the rows of a table on the node, or the browser loads the files and runs the queries itself. A third set of controllers reports the state of the pipeline and controls it: start, stop, pause and resume.

## 6. Determinism

A run is determined by its seed, its configuration and the build that executes it. With the same three it takes the same course on any machine, cell for cell. Earlier sections have used this property; this section says where a run could depart from it and how each of these ways is closed.

The first way is the order of threads. Local execution runs on several threads, and which organism a thread reaches first changes from run to run. Nothing in that stage can depend on it. An instruction there changes only its own organism, and every read sees the environment as it was at the start of the tick. A random value is computed from seed, tick, organism and draw, not drawn from a shared generator. Everything that writes into the environment runs afterwards in organism order. A contested cell goes to the organism with the better priority, and the priority is computed, not timed. The runtime enforces this rule: the shared generator does not hand out a value inside the parallel stage, so no plugin can reintroduce the order of threads by accident. A write into the environment from that stage is caught the same way when the tests run. Beyond these guards, a test runs the same simulation with one thread and with several and compares the whole state tick by tick. A leak that slips past both guards shows up there as a difference.

The second way is the history of data structures. A hash map or a hash set returns its entries in an order that depends on how it was filled, not on what it holds. A run that goes straight through fills such a structure in one order, a run resumed from a complete state rebuilds it in another. Wherever that order could reach the outcome, the runtime fixes it by the content instead. The genome hash sorts the molecules of a body by their position before it hashes them. The persistent registers of procedures are written to the recording in the order of their label hashes. The compiler lays the molecules of a program out in the order of their coordinates, and the engine places them in that order. No structure prevents the next hash map from being iterated where its order matters. What catches it are the resume tests. A run is interrupted, rebuilt from its recording and continued, and its whole state is compared tick by tick with the uninterrupted run. A structure rebuilt in a different order shows up there as a difference, wherever it was introduced.

The third way is the arithmetic of the platform. The simulation itself computes in integers, which every machine computes alike. Floating point enters in the mutation plugins, where a power decides how far a value may change and how likely a block is to be deleted. The usual power function may differ in the last bit between processors and operating systems. A difference there would make the same run mutate differently on two machines. The plugins therefore use the strict variant of the function, which is defined to give the same bits everywhere. What keeps this closed is the fingerprint. A fixed run is simulated on Linux, Windows and macOS in the continuous integration, and a hash of its state is taken at intervals. The three hashes have to agree before a build is published.

Two things in a recording are not determined by seed, configuration and build: the identifier of the run, which is generated when it starts, and the time of day at which each tick was captured. Both are metadata. The tests clear them before they compare two recordings, and everything else has to agree to the bit.

The tests of this property come in three kinds, and the earlier paragraphs have named them where they close a way. The contract tests of the runtime build a minimal scenario for one mechanism each: label selection, the random values of an organism, the visibility of writes within a tick. Each shows that the result is the same under one thread and several, across a resume, and whether the grid is stored in tiles, as the runtime does, or row by row. The neutrality tests of the pipeline take a whole simulation through the real recording. They serialize it, rebuild it from the recording and continue it. Then they compare the whole state of every recorded tick with the uninterrupted run. One of them interrupts across a birth with mutations. A random state off by one draw would place different mutations there and still look like a plausible run. Another records the same run at two densities and shows that every tick both recorded is described identically. The fingerprint, finally, is the same kind of comparison across machines and builds.

## 7. Distributed execution

Evochora runs on one machine today, as one node with in-process resources. The pipeline is built so that this is a configuration and not a limit: its services share nothing but resources, several instances of a service share the work, and any part of the pipeline can form a node ([section 4](#4-the-data-pipeline), [section 5](#5-the-node-and-the-frontends)). Spreading the pipeline over several machines therefore takes one thing: implementations of the existing resource interfaces that reach over the network. These would be a storage on an object store, a database on a database server, and brokers outside the node. The services, the controllers and the frontends stay as they are, because none of them knows what stands behind a resource. The tests already run the same services against in-memory resources, which is the same exchange in the other direction.

The simulation itself runs as one process. A tick is one order of writes into the environment with one conflict resolution, and that is what makes a run deterministic ([section 6](#6-determinism)). It scales over the threads of a tick on one machine. Partitioning the environment across machines would have to produce the same order at the borders between the parts. The idea for that exists. An organism touches only the cells around its pointers, so the parts would have to exchange only what happens at their borders. It is an idea, not a design in this architecture.

---

Written with AI assistance under the project's [AI Policy](../../AI_POLICY.md). The author reviewed it and is responsible for its content.
