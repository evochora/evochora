<img width="1080" height="218" alt="Screenshot From 2025-12-08 02-26-35" src="https://github.com/user-attachments/assets/33b22b45-4d5c-44fc-9732-f27a3e5696db" />


<div align="center">

| <h3>👉 <a href="http://evochora.org">SEE LIVE DEMO</a></h3><code>All web based, no installation, full simulation run,<br>with analytics and visual debugger.</code> |
| :---: |

<br>

<a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-2ea44f?style=flat&logo=opensourceinitiative&logoColor=white" height="28"></a>&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://discord.gg/1442908877648822466"><img src="https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat&logo=discord&logoColor=white" height="28"></a>&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/evochora/evochora/actions"><img src="https://img.shields.io/github/actions/workflow/status/evochora/evochora/build.yml?branch=main&style=flat&logo=github&logoColor=white&label=Build" height="28"></a>

</div>

<video src="https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859" loop></video>

<br>

## Table of Contents

- I. [The Mission: Breaking the "Boring Billion"](#i-the-mission-breaking-the-boring-billion)
- II. [The Platform](#ii-the-platform)
  - [Key Features](#key-features)
  - [Why Evochora? (Comparison)](#why-evochora-comparison)
  - [Request for Comments & Collaboration](#request-for-comments--collaboration)
- III. [System Architecture](#iii-system-architecture)
  - [Compiler](#compiler)
  - [Runtime: The Digital Physics of Evochora](#runtime-the-digital-physics-of-evochora)
  - [Data pipeline](#data-pipeline)
  - [Web frontends](#web-frontends)
- IV. [User Guide](#iv-user-guide)
  - [Quick Start (Run a Simulation)](#quick-start-run-a-simulation)
  - [Configuration Overview](#configuration-overview)
  - [Command Line Interface (CLI)](#command-line-interface-cli)
  - [Development & Local Build](#development--local-build)
  - [Contributing](#contributing)
- [Platform Engineering Roadmap](#platform-engineering-roadmap)
- [Community & Links](#community--links)

<br>

## I. The Mission: Breaking the "Boring Billion"

The biological evolution on Earth stagnated for over 1 billion years (the "Boring Billion") because prokaryotes were energetically constrained. Traditional ALife platforms (like *Tierra* or *Avida*) are stuck in a similar trap: they achieve stability through artificial constraints (CPU quotas), which prevents the emergence of open-ended complexity.

**Evochora fixes this by simulating rigorous physics.**

Instead of executing scripts in a sandbox, agents are **fully embodied**. They occupy space and must harvest energy to pay for every CPU cycle. This architecture finally allows us to investigate **Major Evolutionary Transitions**:

* **The Milestone:** We have achieved **viable self-replication** (see video) capable of sustaining populations for over 500,000 ticks. Agents navigate the grid, harvest resources, and copy their 1500-instruction genome without central oversight.
* **The Physics of Stability:** Unlike legacy systems that "patch" aggressive replication with artificial rules, we use **Thermodynamics** (Entropy). By introducing energy loss and waste heat, we solve the "Grey Goo" problem *without* sacrificing the evolvability of the code.
* **The Frontier (Ecosystems & Niche Construction):** We are expanding the physics to support complex **Reaction Chains** (e.g., `A + B -> Energy + Waste`). This allows for the emergence of trophic levels, where the waste of one species becomes the resource for another.
* **The Frontier (Digital Eukaryogenesis):** The VM allows agents to `FORK` internal execution threads and send fuzzy signals between them. This enables the evolution of "Digital Mitochondria"—dedicated background threads for metabolism—paving the way for true multicellularity.

👉 **[Read the full Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)** or **[Jump to Quick Start](#quick-start-run-a-simulation)**

<br>

## II. The Platform

## Key Features
- **N-Dimensional Spatial Worlds**: Configurable grid size and dimensionality (2D to n-D), bounded or toroidal topology.
- **High-Performance Simulation Core**: The core runs on a flattened `int32` memory grid for maximum **CPU cache locality**, avoiding Java object overhead.
- **Embodied Agency**: Organisms have no "god mode" access. They must navigate via instruction pointers (IP) and data pointers (DPs) to interact with the world.
- **Intrinsic Selection Pressure**: Survival requires active energy foraging; every instruction costs thermodynamic energy.
- **Custom Compiler Stack**: Includes a full multi-pass compiler converting high-level `EvoASM` assembly into raw executable molecules.
- **Distributed Architecture**: The simulation engine is decoupled from analytics (via Protobuf/Queue) to allow massive scaling from local laptops to distributed cloud clusters.
- **Extensible Physics**: Pluggable systems for energy distribution, mutation models, and reaction chemistry.
- **Full Determinism**: Reproducible experiments via fixed random seeds and deterministic conflict resolution.

<br>

## Why Evochora? (Comparison)

| Feature / Aspect | Tierra (Ray, 1991) | Avida (Ofria et al., 2004) | Lenia (Chan, 2019) | **Evochora (Current)** |
| :--- | :--- | :--- | :--- | :--- |
| **Core Concept** | Self-replicating code in linear RAM ("Soup") | Agents solving logic tasks in 2D grid | Continuous cellular automata (Math-Biology) | **Embodied agents** in n-Dimensional space |
| **Physics / Environment** | CPU cycles & memory access (Fixed) | Rewards for logical tasks (NOT, AND) (Fixed) | Differential equations (flow, kernel) (Fixed) | **Extensible** via Plugins (e.g., Energy, Mutation*) |
| **Organism Body** | **Disembodied** (Code string only) | **Disembodied** (CPU + Memory buffer) | Morphological patterns (solitons) | **Embodied** (IP + DPs navigating spatial grid) |
| **Interaction Model** | Parasitism (reading neighbor's RAM) | Limited (mostly competition for space) | Collision, fusion & repulsion of patterns | **Direct & Spatial** (via DPs) & **Signaling*** |
| **Evolutionary Driver** | Implicit competition for memory/CPU | **Directed** (user-defined rewards) | Spontaneous pattern formation | **Open-ended** (via metabolic & spatial constraints) |
| **Execution Model** | Sequential (Single IP) | Sequential (Single IP) | Parallel (Continuous dynamics) | **Parallel & Multi-threaded** (via FORK)* |
| **Primary Research Focus** | Ecology of code & parasites | Evolution of complex logic functions | Self-organizing morphology | **Bioenergetics & Major Transitions** |

*\* Features supported by the core architecture and identified as primary research avenues.*

<br>

## Request for Comments & Collaboration

Evochora addresses the stagnation of current ALife systems by creating a "digital universe" where the rules of physics are not pre-supposed but are themselves objects of research. By making these "laws" modular and extensible, we invite the scientific community to collaboratively explore what properties an environment must possess for **Open-Ended Evolution** to emerge. We are looking for **Systems Engineers** and **ALife Researchers** to help design and implement:

- **Thermodynamics (Entropy)**: Designing a system-wide energy loss model to naturally suppress infinite loops and drive efficiency.
- **Spatial Ownership**: Implementing VM-level "property rights" to distinguish between aggressive overwriting (attacks) and offspring placement.
- **Fuzzy Addressing (SignalGP)**: Moving from absolute memory addresses to pattern-matching jumps to make the genome resilient to mutation and enable **Digital Eukaryogenesis**.

👉 **Deep dive into the problems:** [Read OPEN_RESEARCH_QUESTIONS.md](docs/OPEN_RESEARCH_QUESTIONS.md)

<br>

## III. System Architecture

Evochora is built as a modular stack:

- **Compiler**  
  Translates EvoASM into VM instructions and layouts via an immutable phase pipeline (preprocessor, parser, semantic analyzer, IR generator, layout engine, and emitter).

- **Runtime / Virtual Machine**  
  Each organism is an independent VM with its own registers, stacks, and pointers in an n-dimensional world of typed Molecules (CODE, DATA, ENERGY, STRUCTURE).  
  Strong locality and an energy-first design create intrinsic selection pressure.

- **Data Pipeline**  
  Simulation Engine → queue → Persistence Service → storage → Indexer → queryable indexes for debugging and analysis.

- **Node & HTTP API**  
  Orchestrates services and resources, exposes REST endpoints (e.g. `/api/pipeline/...`) and powers the web-based visualizer.

<br>

### Compiler

The Evochora compiler is a multi-pass pipeline that transforms human-readable EvoASM assembly code into a `ProgramArtifact` ready for execution. This design ensures determinism and separates concerns into a **frontend** (parsing and analysis) and a **backend** (layout and code generation). Most phases are extensible via handler registries, allowing new features to be added in a modular way.

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

For more details on the assembly language and the compiler's internal representation, see:<br>
👉 **[Evochora Assembly: Language Reference](docs/ASSEMBLY_SPEC.md)**<br>
👉 **[Compiler Intermediate Representation (IR) Specification](docs/COMPILER_IR_SPEC.md)**

<br>

### Runtime: The Digital Physics of Evochora

The Evochora platform is architected from the ground up to serve as a flexible and high-performance testbed for exploring the prerequisites of open-ended evolution. Its design is guided by the principles of modularity, spatial embodiment, and extensible physics. This section details the core, currently implemented components of the system.

#### Conceptual Architecture of an Evochora Organism
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
         |   +--------------------------+      +-----------------------+ |
         |                 ^                                  ^          |
         |         (Move/Read/Write)                      (Control)      |
         |                 |                                  |          |
         |   +-------------v----------------------------------v------+   |
         |   |                  Virtual Machine                      |   |
         |   |                                                       |   |
         |   |  Registers: [DRs] [PRs] [FPRs] [LRs] (Locations)      |   |
         |   |                                                       |   |
         |   |  Stacks:    [Data Stack] [Call Stack] [Loc. Stack]    |   |
         |   |                                                       |   |
         |   |  Metabolism: [Energy Register (ER)] --(Cost)--> 0     |   |
         |   +-------------------------------------------------------+   |
         +---------------------------------------------------------------+

👉 **See Assembly specification:** [EvoASM Reference](docs/ASSEMBLY_SPEC.md) for more details

<br>

### Data pipeline

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

Every service in this diagram can be deployed in Docker or a dedicated machine. The communication resources between the services (queue, storage, database, etc.) use implementation-abstract interfaces and can be easily implemented as cloud resources. As this is still in development, we still see this as a roadmap topic.

<br>

### Web frontends

Evochora includes two primary web-based frontends for interacting with the simulation data: the **Visualizer** and the **Analyzer**. Both are built with modern vanilla JavaScript, emphasizing performance and direct API interaction without heavy frameworks.

The backend is powered by a lightweight **Javalin** HTTP server. Backend `Controller` classes register API endpoints (e.g., `/api/visualizer/...`) that are called by the frontend. These controllers, in turn, interact with backend services like the `ServiceRegistry` to fetch simulation data.

-   **Visualizer**: Provides a high-fidelity, tick-by-tick view of the simulation. It allows you to step through time, inspect the state of individual organisms (registers, stacks), and debug the execution of their EvoASM code live in the browser.
-   **Analyzer**: Offers a high-level overview of simulation metrics over the entire run. It features a pluggable interface for adding new metrics and visualizations. For maximum flexibility, the Analyzer can perform range requests on Parquet files served by the backend, enabling custom queries and analysis directly in the browser using DuckDB-WASM.

The following diagram shows which frontend components communicate with which backend controllers:

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
👉 **[Full HTTP API Reference](http://evochora.org/api-docs/)**


<br>

## IV. User Guide

### Quick Start (Run a Simulation)

### Requirements
- Java 21 (JRE or JDK)
- A terminal shell (Linux, macOS, WSL on Windows)

### Start the Simulation Node
Download and unpack the latest distribution from the [GitHub Releases page](https://github.com/evochora/evochora/releases).

```bash
cd evochora-<version>
bin/evochora node run
```

This will:

- Load configuration from [`config/evochora.conf`](./evochora.conf).
- Start the in-process simulation node (simulation engine, persistence, indexer, HTTP server)
- Run until you terminate it (Ctrl + C)

**Note on Storage:** By default, Evochora records high-fidelity telemetry for every tick to allow perfect replay and debugging. For long-running experiments or huge environments, ensure you have sufficient disk space or adjust the configuration to reduce logging frequency (see Config docs).

#### Open the Web UI

Once the node is running, it will by default execute the primordial organism defined in [`assembly/primordial/main.evo`](./assembly/primordial/main.evo) as configured in [`config/evochora.conf`](./evochora.conf).  

Open the visualizer in your browser to see it:
`http://localhost:8081/visualizer/`

<br>

### Usage Modes

Evochora supports multiple usage and deployment modes:

- **In-Process Mode (current default)**  
  All core components (Simulation Engine, Persistence Service, Indexer, HTTP server) run in a single process or container.  
  Best for local experiments, quick iteration, and single-machine runs.

- **Planned Distributed Cloud Mode**  
  Each service (Simulation Engine, Persistence, Indexer, HTTP server, etc.) runs in its own container or process and can be scaled horizontally. Intended for large-scale, long-duration experiments and cloud deployments.

The current releases focus on the in-process mode; the distributed mode is part of the roadmap.

<br>

### Configuration Overview

Evochora is configured via a HOCON configuration file, typically named [`config/evochora.conf`](./evochora.conf).

A complete example configuration is provided as [`config/evochora.conf`](./evochora.conf) in the repository and included in the distribution.

### Command Line Interface (CLI)

The Evochora CLI is the main entry point for running simulations and tools.

**Main commands:**

- `node` – Run and control the simulation node (pipeline, services, HTTP API)
- `compile` – Compile EvoASM (Evochora Assembly) programs for the Evochora VM
- `inspect` – Inspect stored simulation data (ticks, runs, resources)
- `video` – Render simulation runs into videos (requires `ffmpeg`)

Further CLI documentation and fully worked examples:

👉 **[CLI Usage Guide](docs/CLI_USAGE.md)** – All commands, parameters, and usage examples (including `node`, `compile`, `inspect`, and `video`).

<br>

### Development & Local Build

If you want to develop Evochora itself:

```bash
# Clone the repository
git clone https://github.com/evochora/evochora.git
cd evochora

# Build & test
./gradlew build

# Run the node in dev mode (uses ./evochora.conf by default)
./gradlew run --args="node run"
```

See also:

👉  [`CONTRIBUTING.md`](./CONTRIBUTING.md) – Contribution workflow and expectations.<br>
👉  [`AGENTS.md`](./AGENTS.md) – Coding conventions, architecture and compiler/runtime design principles, testing rules.

<br>

### Contributing

We welcome contributions of all kinds:

- Scientific discussion about the "laws" of the digital universe
- Code contributions (VM, compiler, data pipeline, analysis tools, web visualizer)
- Experiment design and benchmark scenarios
- Documentation, tutorials, and examples
- Testing

Basic contribution workflow:

1. Fork the repository
2. Create a feature branch (e.g. `git checkout -b feature/amazing-feature`)
3. Follow the style and guidelines in `AGENTS.md`
4. Add tests where appropriate
5. Open a Pull Request with a clear description and rationale

<br>

## Platform Engineering Roadmap

Some key directions for the technical evolution of Evochora:

- **Distributed Cloud Mode** – Run Simulation Engine, Persistence Service, Indexer, HTTP server, etc. as separate processes/containers with horizontal scaling for large experiments.
- **Multithreaded Simulation Engine** – Parallelize the plan/resolve/execute phases across CPU cores to support larger worlds and more organisms on a single machine.
- **Pluggable Mutation System** – Make mutation models first-class plugins (e.g., replication errors, background radiation, genomic rearrangements) to study their impact on open-ended evolution.
- **Extended Data Pipeline & Resume Support** – More scalable, cloud-native persistence and indexing with the ability to resume simulations from stored states.

👉 **Project Board & Roadmap:** [GitHub Projects](https://github.com/orgs/evochora/projects/1/views/1)

<br>

---

## Community & Links

- Discord (Community Chat):  
  [![Discord](https://img.shields.io/discord/1442908877648822466?label=Join%20Community&logo=discord&style=flat-square)](https://discord.gg/1442908877648822466)

- Live Visualizer Demo:  
  http://evochora.org/

- API Documentation (developer-focused):  
  http://evochora.org/api-docs/

- Key documentation in this repository:
    - [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)
    - [CLI Usage Guide](docs/CLI_USAGE.md)
    - [Assembly Specification](docs/ASSEMBLY_SPEC.md) (EvoASM – Evochora Assembly)

---

## Logo

```text
  ■■■■■  ■   ■   ■■■    ■■■   ■   ■   ■■■   ■■■■     ■  
  ■      ■   ■  ■   ■  ■   ■  ■   ■  ■   ■  ■   ■   ■ ■ 
  ■      ■   ■  ■   ■  ■      ■   ■  ■   ■  ■   ■  ■   ■
  ■■■■    ■ ■   ■   ■  ■      ■■■■■  ■   ■  ■■■■   ■   ■
  ■       ■ ■   ■   ■  ■      ■   ■  ■   ■  ■ ■    ■■■■■
  ■       ■ ■   ■   ■  ■   ■  ■   ■  ■   ■  ■  ■   ■   ■
  ■■■■■    ■     ■■■    ■■■   ■   ■   ■■■   ■   ■  ■   ■
```
---

## License & Citation

Evochora is open-source and available under the **MIT License** (see [`LICENSE`](./LICENSE)).

If you use Evochora in your research, please cite:

```bibtex
@article{evochora2025,
  title={Evochora: A Collaborative Platform for Research into the Foundational Physics of Digital Evolution},
  author={[Authors]},
  journal={[Journal]},
  year={2025},
  note={In preparation}
}
```

---

**Note**: Evochora is in active development. Some features described in documentation may be planned but not yet implemented. See the project documentation and roadmap for the current status.
