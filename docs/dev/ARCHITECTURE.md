# Architecture

This document describes how Evochora is built, for developers who will read the code next. It names the parts, how they depend on each other, and what each does with a run from the first instruction to the charts in the browser. Why the parts were built this way is the subject of [Architecture & Design Decisions](ARCHITECTURE_DECISIONS.md).

## 1. The parts and how they depend

Evochora is one Java code base with five top-level packages under `org.evochora`. Each is a part with one job.

**Runtime** (`runtime`) is the simulation itself: the environment as a grid of molecules, the organisms with their virtual machines, the instruction set, and the physics plugins for energy, thermodynamics, mutation, death and label matching. `Simulation` advances the world by one tick; `VirtualMachine` executes one instruction of one organism. The runtime depends on no other part, so everything around it can change without touching it.

**Compiler** (`compiler`) turns a program written in EvoASM into the molecules that the runtime executes. Its result is a `ProgramArtifact`: the machine code with the position of every molecule, and the maps that link each position back to the source line it came from. The compiler depends on the runtime for one thing, the instruction set it compiles to; it sees it through the interface `IInstructionSet`.

**Data pipeline** (`datapipeline`) records a run and prepares it for reading. The `SimulationEngine` drives the simulation and writes what it records; services behind it store the recording, index it and compute the analytics. The pipeline depends on the runtime, whose state it records, and on the compiler, whose artifacts it keeps with a run.

**Node** (`node`) is the process that runs all of this. It starts the pipeline's services, the message brokers between them, the HTTP server with the controllers that the web frontends call, and the database servers. The node depends on the pipeline and the runtime.

**CLI** (`cli`) is the entry point: `node run`, `node resume`, `node fork` and `node show` start a node in one of its modes; `compile`, `inspect`, `video` and `cleanup` work on programs and recordings without a node. The CLI may depend on every other part.

The dependencies point in one direction, from the entry point down to the simulation:

```text
cli          →  node, datapipeline, compiler, runtime
node         →  datapipeline, runtime
datapipeline →  compiler, runtime
compiler     →  runtime
runtime      →  (nothing)
```

A sixth package, `util`, holds helpers that belong to no part and depend on nothing; every part but the runtime may use it. `PackageDependencyRulesTest` enforces this graph: an import that the graph does not allow fails the build and names the classes involved. The graph is repeated here for orientation; when the two disagree, the test is right.
