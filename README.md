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
- [Why Evochora?](#why-evochora)
- [Key Capabilities](#key-capabilities)
- [User Guide](#user-guide)
- [How It Works](#how-it-works)
- [Comparison with Other Platforms](#comparison-with-other-platforms)
- [Research Directions](#research-directions)
- [Contributing & Community](#contributing--community)
- [License & Citation](#license--citation)

<br>

## Why Evochora?

Pioneering platforms like Tierra and Avida demonstrated that digital evolution can produce ecological dynamics and complex functions — but their reliance on global culling or task-based rewards imposes fixed selection pressures that may limit long-term evolvability. Evochora takes a different approach: organisms are spatially distributed code in an n-dimensional grid, interacting with their surroundings exclusively through local pointers. Survival is not a task to be solved but a constant energetic balancing act. This makes the physics of the world — not an external reward function — the primary driver of selection.

For the full scientific motivation, see the [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md). For the personal story behind the project, see the [Origin Story](docs/ORIGIN_STORY.md).

<br>

## Key Capabilities

### Design Your Experiments
- **Spatial Worlds** — Configurable grid size and dimensionality (2D to n-D), bounded or toroidal
- **Custom Organisms** — Write organisms in EvoASM, a spatial assembly language with an extensible multi-pass compiler
- **Configurable Thermodynamics** — Configurable energy and entropy costs; customize selection pressure or write your own thermodynamic rules replacing or extending existing policies, decay rules, and resource distribution
- **Mutation Models** — Configure existing gene insertion, substitution, deletion, and duplication rules or write your own mutation plugins
- **Extensible Instruction Set** — Customize or extend the VM instruction set without modifying the core runtime

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

## User Guide

### Configuration

Evochora is configured via [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) configuration files. The main configuration file [`config/evochora.conf`](./evochora.conf) is extensively documented with inline comments and included in the distribution.

For custom experiments, create a `config/local.conf` to override specific settings without modifying the default configuration:

```hocon
include "evochora.conf"

simulation-engine.options.environment {
  shape = [1024, 800]
  topology = "TORUS"
}
```

Start the node with your custom configuration:

```bash
bin/evochora -c config/local.conf node run
```

### Writing Your Own Organisms

Organisms are programmed in **EvoASM**, Evochora's custom spatial assembly language. The default primordial organism ([`assembly/primordial/main.evo`](./assembly/primordial/main.evo)) is a conservative replicator: a main loop checks the energy level and decides whether to call the reproduction or energy harvesting subroutine. It is enclosed in a STRUCTURE shell but does not overwrite other organisms.

This design leaves room for experimentation. For example:
- **Aggressive variant** — overwrite neighboring organisms' code to claim territory
- **Defensive variant** — add a subroutine that periodically repairs the STRUCTURE shell against aggressive neighbors
- **Cooperative variant** — write data molecules into the environment as signals; other organisms can read them to coordinate behavior or share energy
- **Efficient variant** — optimize the energy harvesting routine to cover more ground with fewer instructions

To create your own organism:

1. Edit or create a `.evo` file in `assembly/`
2. Configure it in `evochora.conf` (see `simulation-engine.options.organisms`)
3. Run `bin/evochora node run` — the engine compiles automatically

**Resources:**
- **Language Reference:** [docs/ASSEMBLY_SPEC.md](docs/ASSEMBLY_SPEC.md)
- **Syntax Highlighting:** VS Code/Cursor extension in [`extensions/vscode/`](extensions/vscode/README.md)

### Command Line Interface (CLI)

The Evochora CLI is the main entry point for running simulations and tools.

- `node run` — Start the simulation node (engine, pipeline, HTTP server)
- `compile` — Compile EvoASM programs for the Evochora VM
- `inspect` — Inspect stored simulation data (ticks, runs, resources)
- `video` — Render simulation runs into videos (requires `ffmpeg`)

For full documentation and worked examples, see the [CLI Usage Guide](docs/CLI_USAGE.md).

### Development & Local Build

```bash
git clone https://github.com/evochora/evochora.git
cd evochora
./gradlew build
./gradlew run --args="node run"
```

Open the visualizer: `http://localhost:8081/visualizer/`

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the full contribution guide, including coding conventions and architecture guidelines.

### Extending the Platform

Evochora is extensible at multiple levels through Java plugin interfaces:

- **Mutation plugins** — Implement `IBirthHandler` to define new mutation operators that run during organism reproduction. Built-in plugins: gene insertion, substitution, deletion, and duplication.
- **Environment plugins** — Implement `ITickPlugin` to add new environmental processes. Built-in plugins: solar radiation, geysers, and seed energy distribution.
- **Death handlers** — Implement `IDeathHandler` to control what happens when an organism dies (e.g., decay into energy).
- **Analytics plugins** — Extend `AbstractAnalyticsPlugin` to define custom metrics exported as Parquet. Built-in plugins: population metrics, vital stats, age distribution, genome diversity, and more.
- **Compiler extensions** — Each compiler phase is extensible via handler registries (directives, IR converters, layout, linking, emission).
- **VM instructions** — Add new instructions to the ISA via the instruction registry.

<br>

## How It Works

Evochora is built as a modular stack of four components: Runtime, Compiler, Data Pipeline, and Web Frontends.
Each layer is independently extensible through plugins and registries.

### Runtime

The runtime implements the simulation environment and its physical laws. It provides the n-dimensional grid in which organisms exist as spatially distributed code, subject to thermodynamic constraints. Each organism is an independent virtual machine with its own registers, stacks, and pointers. Organisms interact with the world exclusively through their Instruction Pointer (IP) and Data Pointers (DPs) — they have no global view of the simulation.

Thermodynamic costs are fully configurable: actions can cost energy, produce entropy, or both. An organism dies when its energy is depleted or its entropy exceeds its threshold.

Unlike classical von Neumann architectures, jump instructions in Evochora do not target exact addresses. Instead, they resolve targets through fuzzy label matching based on Hamming distance. This makes genomes inherently resilient to mutations that insert, delete, or shift code — a prerequisite for meaningful evolution.

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

The Evochora compiler is a multi-pass pipeline that transforms human-readable EvoASM assembly code into a `ProgramArtifact` ready for execution. The design separates concerns into a frontend (parsing and analysis) and a backend (layout and code generation). Most phases are extensible via handler registries, allowing new syntactical features to be added in a modular way. EvoASM supports procedures, scoped variables, macros, and spatial layout directives for placing code in n-dimensional space.

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

All pipeline services except the simulation engine are designed as idempotent competing consumers. All inter-service communication is abstracted behind resource interfaces (queues, storage, topics, databases). The default mode runs all components in a single process, but this architecture lays the groundwork for horizontal scaling and distributed deployments.

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

### Further Reading

For more details, see the [Assembly Specification](docs/ASSEMBLY_SPEC.md), [Compiler IR Specification](docs/COMPILER_IR_SPEC.md), [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md), and [Architecture Decisions](docs/ARCHITECTURE_DECISIONS.md).

<br>

## Comparison with Other Platforms

Evochora builds on the legacy of seminal Artificial Life platforms. This comparison highlights the different scientific questions and design trade-offs each system explores.

| Feature / Aspect | Tierra (Ray, 1991) | Avida (Ofria et al., 2004) | Lenia (Chan, 2019) | Evochora |
| :--- | :--- | :--- | :--- | :--- |
| **Core Concept** | Self-replicating code in linear RAM ("Soup") | Agents solving logic tasks in 2D grid | Continuous cellular automata (Math-Biology) | Spatial code execution in n-Dimensional grid |
| **Physics / Environment** | CPU cycles & memory access (Fixed) | Rewards for logical tasks (NOT, AND) (Fixed) | Differential equations (flow, kernel) (Fixed) | Extensible via Plugins (Thermodynamics, Resource Distribution) |
| **Organism Body** | Disembodied (Code string only) | Disembodied (CPU + Memory buffer) | Morphological patterns (solitons) | IP + DPs navigating spatial grid |
| **Interaction Model** | Parasitism (reading neighbor's RAM) | Limited (mostly competition for space) | Collision, fusion & repulsion of patterns | Direct & Spatial (via DPs); Planned: Signaling |
| **Evolutionary Driver** | Implicit competition for memory/CPU | Directed (user-defined rewards) | Spontaneous pattern formation | Metabolic & spatial constraints |
| **Execution Model** | Sequential (Single IP) | Sequential (Single IP) | Parallel (Continuous dynamics) | Sequential; Planned: Intra-organism parallelism (multiple execution threads) |
| **Primary Research Focus** | Ecology of code & parasites | Evolution of complex logic functions | Self-organizing morphology | User-defined evolution experiments |

<br>

## Research Directions

Evochora is designed as an open platform for a wide range of evolutionary experiments. Here are some research directions the architecture is built to support:

- **Mutation & Evolvability** — How do different mutation regimes affect long-term evolutionary potential? Compare gene insertion, substitution, deletion, and duplication strategies or design your own.
- **Thermodynamic Selection** — How do energy and entropy policies shape population dynamics? Explore the space between harsh and permissive environments.
- **Cooperation & Communication** — Can organisms that coordinate through environmental signals (molecules placed in the grid) outcompete solitary strategies?
- **Digital Chemistry** — Can reaction chains (A + B → C + Energy) lead to emergent trophic levels and metabolic recycling?
- **Digital Eukaryogenesis** — Can intra-organism parallelism lead to internal division of labor, analogous to the prokaryote-to-eukaryote transition?
- **Complexity Measurement** — How do you rigorously quantify whether evolution produces increasingly complex organisms? The data pipeline persists complete organism state for external analysis.
- **Open-Ended Evolution** — What conditions help a population overcome the next complexity hurdle? Investigate which combinations of physics, mutation, and environmental structure enable incremental gains in complexity.

For the full scientific context behind these questions, see the [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md).

<br>

## Contributing & Community

We welcome contributions of all kinds — code, experiment design, scientific discussion, documentation, and testing.

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the full contribution guide, including development standards, PR process, and good first issues.

For the current roadmap, see the [GitHub Project Board](https://github.com/orgs/evochora/projects/1/views/1).

**Community:**

- Discord: [![Discord](https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat-square&logo=discord)](https://discord.gg/t9yEJc4MKX)
- Live Demo: [http://evochora.org/](http://evochora.org/)
- API Documentation: [http://evochora.org/api-docs/](http://evochora.org/api-docs/)
- Key documentation:
    - [CLI Usage Guide](docs/CLI_USAGE.md)
    - [Assembly Specification](docs/ASSEMBLY_SPEC.md) (EvoASM)
    - [Architecture Decisions](docs/ARCHITECTURE_DECISIONS.md)

<br>

---

## License & Citation

Evochora is open-source and available under the **MIT License** (see [`LICENSE`](./LICENSE)).

If you use Evochora in your research, please cite:

```bibtex
@software{evochora2025,
  title={Evochora: Simulation Platform for Digital Evolution Research},
  author={[Authors]},
  url={https://github.com/evochora/evochora},
  year={2025}
}
```

---

_Full disclosure: This project uses AI coding assistants. Humans define the architecture, write specifications, and review generated code to ensure correctness and maintain the overall design._

---

**Note**: Evochora is in active development. Some features described in documentation may be planned but not yet implemented. See the project documentation and roadmap for the current status.
