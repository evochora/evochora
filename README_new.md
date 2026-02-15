<div align="center">

  <img width="465" height="74" alt="logo" src="https://github.com/user-attachments/assets/4724aed1-a596-4e03-9c3c-41f791b0babd" />
  <br><code>Simulation Platform for Digital Evolution Research</code>

  <br><br>
  **Evochora is a scientific simulation platform for digital evolution research.<br>
  Organisms live in an n-dimensional environment with configurable thermodynamic selection pressure and are written in EvoASM, a spatial assembly language.<br>
  The compiler, the instruction set, and the environment are all extensible through plugins. A decoupled data pipeline persists raw simulation data that can be reindexed at any time, and all outputs are standard Parquet — ready for analysis in Python, R, or Jupyter. A web-based frontend allows tick-by-tick inspection and debugging of organism behavior.**

  <a href="http://evochora.org" style="font-size: 1.5em; font-weight: bold; text-decoration: none;" target="_blank">👉 SEE LIVE DEMO</a>
  <br>
  <sub>Runs in your browser. No installation required.</sub>

  <br>

  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-2ea44f?style=flat&logo=opensourceinitiative&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://discord.gg/t9yEJc4MKX">
    <img src="https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat&logo=discord&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/evochora/evochora/actions">
    <img src="https://img.shields.io/github/actions/workflow/status/evochora/evochora/build.yml?branch=main&style=flat&logo=github&logoColor=white&label=Build" height="28">
  </a>

</div>

<br><br>

<!--<video src="https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859" loop></video>-->
<!--<video src="https://github.com/user-attachments/assets/28c329bc-9554-4b10-8d65-049f00eeda86" loop></video>-->
<video src="https://github.com/user-attachments/assets/69b33e5b-074b-46d6-917c-9b9cf06e10ef" loop></video>

<br>

**Screenshots:**
<table>
  <tr>
    <td align="center" width="50%">
      <a href="https://github.com/user-attachments/assets/3bf0aa8e-f2bd-4b98-a03f-dd33d449ed93" target="_blank">
        <img alt="Web Visualizer" src="https://github.com/user-attachments/assets/3bf0aa8e-f2bd-4b98-a03f-dd33d449ed93" width="100%">
      </a>
      <br><strong>Web Visualizer</strong>
      <br><sub>Tick-by-tick inspection and debugging of organism state, registers, and EvoASM execution</sub>
    </td>
    <td align="center" width="50%">
      <a href="https://github.com/user-attachments/assets/c30c6bcc-cdbe-4637-befe-3d69b21319db" target="_blank">
        <img alt="Web Analyzer" src="https://github.com/user-attachments/assets/c30c6bcc-cdbe-4637-befe-3d69b21319db" width="100%">
      </a>
      <br><strong>Web Analyzer</strong>
      <br><sub>Population metrics, environment composition, and genome analytics via pluggable charts</sub>
    </td>
  </tr>
</table>

<br>

## Quick Start

**Requirements:** Java 21 (JRE or JDK)

Download and unpack the latest distribution from the [GitHub Releases page](https://github.com/evochora/evochora/releases).

```bash
cd evochora-<version>
bin/evochora node run
```

Open the visualizer in your browser: `http://localhost:8081/visualizer/`

The node starts a complete in-process simulation with the default primordial organism, including persistence, indexing, and the web frontend. Press Ctrl+C to stop.

> **Resource requirements:** Evochora records every tick for full replay. Allow sufficient disk space for long-running experiments and 8GB+ heap memory. The system warns at startup if memory is insufficient.

<br>

## Table of Contents

- [Quick Start](#quick-start)
- [Key Capabilities](#key-capabilities)
- [How It Works](#how-it-works)
- [Comparison with Other Platforms](#comparison-with-other-platforms)
- [Research Directions](#research-directions)
- [User Guide](#user-guide)
- [Contributing & Community](#contributing--community)
- [License & Citation](#license--citation)

<br>

## Key Capabilities

### Design Your Experiments
- **Spatial Worlds** — Configurable grid size and dimensionality (2D to n-D), bounded or toroidal
- **Custom Organisms** — Write organisms in EvoASM, a spatial assembly language with an extensible multi-pass compiler
- **Configurable Thermodynamics** — Configurable energy and entropy costs; customize selection pressure or write your own thermodynamic rules replacing or extending existing policies, decay rules, and resource distribution
- **Mutation Models** — Configure existing gene insertion, substitution, deletion, and duplication rules or write your own mutation plugins
- **Extensible Instruction Set** — Customize or extend the VM instructions set without modifying the core runtime

### Analyze Your Results
- **Standard Formats** — All simulation data exports as Parquet, ready for Python, R, or Jupyter
- **Pluggable Analytics** — Add custom metrics as analytics plugins with built-in chart visualization; built-in plugins cover population, vital stats, age distribution, genome diversity, and more
- **Web-Based Inspection** — Step through every tick, inspect organism registers, stacks, and debug EvoASM execution in the browser

### Trust Your Results
- **Deterministic Simulation** — Seed-based; identical input produces identical output, guaranteed
- **Complete Data Persistence** — Every tick is recorded; nothing is lost or aggregated away
- **Decoupled Data Pipeline** — Raw data can be reindexed at any time with new or modified analytics plugins
- **No Built-In Selection Bias** — By default, no global culling or task-based rewards; organisms survive through their own actions in a spatial environment

<br>

## How It Works

Evochora is built as a modular stack of four components: Runtime, Compiler, Data Pipeline and Webfrondends.
Each layer is independently extensible through plugins and registries.

### Runtime

The runtime implements the simulation environment and its physical laws. It provides spatial embodiment and thermodynamic constraints within an n-dimensional grid. Each organism is an independent virtual machine with its own registers, stacks, and pointers. Organisms interact with the world exclusively through their Instruction Pointer (IP) and Data Pointers (DPs) — they have no global view of the simulation.

Thermodynamic costs are fully configurable: actions can cost energy, produce entropy, or both. An organism dies when its energy is depleted or its entropy exceeds its threshold.

#### Conceptual Architecture of an Evochora Organism
```text
         +---------------------------------------------------------------+
         |                 Evochora "World" (n-D Grid)                   |
         |                                                               |
         |   [ ENERGY ]      [ STRUCTURE ]      [ CODE ]      [ DATA ]   |
         +-------^-----------------^----------------^-------------^------+
                 |                 |                |             |
    Interaction: |                 |                |             |
             (HARVEST)          (BLOCK)         (EXECUTE)      (READ)
                 |                 |                |             |
                 |                 |                |             |
         +-------|-----------------|----------------|-------------|------+
         |       |    ORGANISM     |                |             |      |
         |       |                 |                |             |      |
         |   +---v-----------------v----+      +----v-------------v----+ |
         |   |    Data Pointers (DPs)   |      |   Inst. Pointer (IP)  | |
         |   | [DP 0] [DP 1] ... [DP n] |<-----|                       | |
         |   +-------------^------------+      +-----------^-----------+ |
         |                 |                               |             |
         |         (Move/Read/Write)                   (Control)         |
         |                 |                               |             |
         |   +-------------v-------------------------------v---------+   |
         |   |                  Virtual Machine                      |   |
         |   |                                                       |   |
         |   |  Registers: [DRs] [PRs] [FPRs] [LRs] (Locations)      |   |
         |   |                                                       |   |
         |   |  Stacks:    [Data Stack] [Call Stack] [Loc. Stack]    |   |
         |   |                                                       |   |
         |   |  Metabolism: [Thermodynamics (ER/SR)] --(Cost)--> 0   |   |
         |   +-------------------------------------------------------+   |
         +---------------------------------------------------------------+
```

<br>

### Compiler

The Evochora compiler is a multi-pass pipeline that transforms human-readable EvoASM assembly code into a `ProgramArtifact` ready for execution. The design ensures separates concerns into a frontend (parsing and analysis) and a backend (layout and code generation). Most phases are extensible via handler registries, allowing new syntactiacal features to be added in a modular way.

#### Compiler Frontend
The frontend parses the source code and transforms it into a machine-independent Intermediate Representation (IR).

1.  **Preprocessor**: Handles macros (`#macro`) and file inclusions (`#include`).
2.  **Parser**: Converts tokens into an Abstract Syntax Tree (AST). Extensible via `DirectiveHandlerRegistry` for new directives.
3.  **Semantic Analyzer**: Validates the AST, checks types, and builds a symbol table.
4.  **IR Generator**: Converts the AST into an Intermediate Representation (IR). Extensible via `IrConverterRegistry`.

#### Compiler Backend
The backend takes the IR and generates the final, executable `ProgramArtifact`.

5.  **Layout Engine**: Determines the final spatial coordinates of all molecules. Extensible via `LayoutDirectiveRegistry`.
6.  **Linker**: Resolves symbolic references (e.g., labels). Extensible via `LinkingRegistry`.
7.  **Emitter**: Generates the final binary machine code. Extensible via `EmissionRegistry`.

```text
 ┌──────────────────┐
 │   EvoASM File    │
 └────────┬─────────┘
          │
          ▼
 ┌──────────────────┐
 │  Preprocessor    │
 └────────┬─────────┘
          │ (Token Stream)
          ▼
 ┌──────────────────┐
 │      Parser      │
 └────────┬─────────┘
          │ (AST)
          ▼
 ┌──────────────────┐
 │ Semantic Analyzer│
 └────────┬─────────┘
          │ (Validated AST)
          ▼
 ┌──────────────────┐
 │   IR Generator   │
 └────────┬─────────┘
          │ (IR)
          ▼
 ┌──────────────────┐
 │   Layout Engine  │
 └────────┬─────────┘
          │ (Placed IR)
          ▼
 ┌──────────────────┐
 │      Linker      │
 └────────┬─────────┘
          │ (Linked IR)
          ▼
 ┌──────────────────┐
 │     Emitter      │
 └────────┬─────────┘
          │
          ▼
 ┌──────────────────┐
 │ Program Artifact │
 └──────────────────┘
```

<br>

### Data Pipeline

The simulation engine is decoupled from data processing. Raw simulation data flows through a queue into a persistence service, then through indexers into a queryable database. This separation means the simulation can run at full speed while data is processed independently — and raw data can be reindexed at any time with new or modified indexers.

All pipeline services except the simulation engine are designed as idempotent competing consumers. The communication between services uses implementation-abstract resource interfaces (queues, storage, topics, databases), so the pipeline is ready for horizontal scaling in a distributed deployment.

```text
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

<br>

### Web Frontends

Evochora includes two web-based frontends for interacting with simulation data:

-   **Visualizer**: Provides a tick-by-tick view of the simulation. Step through time, inspect the state of individual organisms (registers, stacks), and debug the execution of their EvoASM code in the browser.
-   **Analyzer**: Offers a high-level overview of simulation metrics over the entire run. It features a pluggable interface for adding new metrics and visualizations. The Analyzer performs range requests on Parquet files served by the backend, enabling custom queries and analysis directly in the browser using DuckDB-WASM.

```text
       Browser (JavaScript Apps)                       Node (Java Backend)
  ┌──────────────────────────────────┐         ┌──────────────────────────────────┐
  │                                  │         │                                  │
  │  ┌───────────────────────────┐   │         │  ┌───────────────────────────┐   │
  │  │      Visualizer App       │   │         │  │ Visualizer Controllers    │   │
  │  │---------------------------│   │         │  │ - EnvironmentController   │   │
  │  │ - EnvironmentApi.js       │───┼───HTTP──┼─►│ - OrganismController      │   │
  │  │ - OrganismApi.js          │   │         │  │ - SimulationController    │   │
  │  │ - SimulationApi.js        │   │         │  └───────────────────────────┘   │
  │  └───────────────────────────┘   │         │                                  │
  │                                  │         │                                  │
  │  ┌───────────────────────────┐   │         │                                  │
  │  │       Analyzer App        │   │         │                                  │
  │  │---------------------------│   │         │  ┌───────────────────────────┐   │
  │  │ - AnalyticsApi.js         │───┼───HTTP──┼─►│    AnalyticsController    │   │
  │  │ - DuckDBClient.js (WASM)  │   │         │  └───────────────────────────┘   │
  │  └───────────────────────────┘   │         │                                  │
  │                                  │         │  ┌───────────────────────────┐   │
  │                                  │         │  │     PipelineController    │   │
  │      (Admin Tools/Scripts)   ────┼───HTTP──┼─►│ (For pipeline management) │   │
  │                                  │         │  └───────────────────────────┘   │
  │                                  │         │                                  │
  └──────────────────────────────────┘         └──────────────────────────────────┘
```

<br>

For more details, see the [Assembly Specification](docs/ASSEMBLY_SPEC.md), [Compiler IR Specification](docs/COMPILER_IR_SPEC.md), [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md), and [Architecture Decisions](docs/ARCHITECTURE_DECISIONS.md).
