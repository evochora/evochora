


<div align="center">

  <img width="465" height="74" alt="logo" src="https://github.com/user-attachments/assets/4724aed1-a596-4e03-9c3c-41f791b0babd" />
  <br><code>Distributed Laboratory for Embodied Artificial Life</code>

  <br><br>

  <a href="http://evochora.org" style="font-size: 1.5em; font-weight: bold; text-decoration: none;">👉 SEE LIVE DEMO</a>
  <br>
  <sub>Runs in your browser. No installation required.</sub>

  

  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-2ea44f?style=flat&logo=opensourceinitiative&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://discord.gg/1442908877648822466">
    <img src="https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat&logo=discord&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/evochora/evochora/actions">
    <img src="https://img.shields.io/github/actions/workflow/status/evochora/evochora/build.yml?branch=main&style=flat&logo=github&logoColor=white&label=Build" height="28">
  </a>

</div>

<br><br>

<video src="https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859" loop></video>

<br>

## Table of Contents

- I. [The "Boring Billion" as Inspiration](#i-the-boring-billion-as-inspiration)
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

## I. The "Boring Billion" as Inspiration

Earth's evolution experienced a "Boring Billion"—a long period of slow innovation, likely held back by the energetic limits of prokaryotic life. This parallel is fascinating when looking at Artificial Life. Pioneering platforms like Tierra and Avida often introduce artificial constraints (like global culling or task-based rewards) to ensure population stability. These are effective, but they also probably sacrifice long-term evolvability by creating a very specific and constant selection pressure.

Evochora is an experiment to explore an alternative. What if the primary constraint isn't an external rule, but the physics of the world itself? In Evochora, organisms are fully embodied. They occupy space and underlie thermodynamic constraints. Survival is not a task to be solved, but a constant energetic balancing act.

This approach appears to be viable, and the results are encouraging:
* **A Working Primordial:** There is a self-replicating organism (see video) capable of sustaining populations for over 500,000 ticks. It navigates, harvests resources, and copies its 1500-instruction genome without any central oversight or pre-defined rewards.

The stable primordial organism is just the starting point. The platform's true potential lies in extending its physics to tackle deeper questions. The architecture is a foundation for future research, and here are some of the next frontiers it's designed to explore:

*   **Stability through Thermodynamics:** Can "grey goo" scenarios be prevented not by artificial rules, but by energy and entropy? The hypothesis is that thermodynamic constraints alone could be enough to foster stable ecosystems *without* sacrificing the evolvability of the code.
*   **Emergent Ecosystems & Niche Construction:** The physics engine is designed to be extensible with reaction chains (e.g., `A + B -> Energy + C`). This would create a testbed for exploring if trophic levels can emerge spontaneously, allowing organisms to alter their environment by creating waste that becomes a resource for others—a process known as Niche Construction.
*   **Robust Genomes & Communication:** To make genomes more resilient to mutations, a future extension could replace rigid memory addresses with "fuzzy jumps" that target patterns in the code. A basic signaling mechanism would also allow organisms to coordinate, both internally between threads and externally with neighbors, laying the groundwork for social behavior.
*   **Internal Parallelism (Digital Eukaryogenesis?):** The VM allows an organism to `FORK` its execution. With a signaling system in place for coordination, this could enable an internal division of labor—for instance, one thread for metabolism and another for replication. While true multicellularity is a very distant goal, this is a fascinating step to investigate if this mechanism could bring us one step closer to cellular coordination.

👉 **[Read the full Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)** or **[Jump to Quick Start](#quick-start-run-a-simulation)**

<br>

## II. The Platform

## Key Features
- **Spatial Worlds**: Configurable grid size and dimensionality (2D to n-D), bounded or toroidal shape containing molecules of different types.
- **Simulation Core**: The core runs on a flattened `int32` memory grid for maximum CPU cache locality, avoiding Java object overhead.
- **Embodied Agency**: Organisms must navigate via instruction pointers (IP) and data pointers (DPs) to interact with the molecules in their direct surrounding, and know nothing about the simulation itself.
- **Compiler Stack**: Includes a custom multi-pass compiler converting high-level and spatial `EvoASM` assembly into raw executable molecules.
- **Selection Pressure**: Survival requires actively dealing with thermodynamics. Every instruction costs energy and/or creates entropy that organisms need to manage.
- **Data Pipeline**: The simulation engine is decoupled from data processing to index and analyze raw simulation data (via Protobuf/Queue). The pipeline supports horizontal scaling in cloud infrastructure.
- **Web Frontends**: Simulation runs can be visualized and analyzed. The visualizer allows inspection of every simulation step and debugging of internal state and `EvoASM` execution for each organism. The analyzer can visualize population and environment metrics, and a video renderer can render full simulation runs. (all 2D only currently)
- **Extensibility**: Plugin systems for the simulation, the VM, each compiler pass, and the analyzer allow for customization.
- **Determinism**: The simulation is deterministic, ensuring experiments are reproducible with a given seed.

<br>

## Contextualizing Evochora in ALife Research

Evochora builds on the legacy of seminal Artificial Life platforms. This comparison highlights the different scientific questions and design trade-offs each system explores, positioning Evochora's focus on embodied agents and extensible physics.

| Feature / Aspect | Tierra (Ray, 1991) | Avida (Ofria et al., 2004) | Lenia (Chan, 2019) | Evochora |
| :--- | :--- | :--- | :--- | :--- |
| **Core Concept** | Self-replicating code in linear RAM ("Soup") | Agents solving logic tasks in 2D grid | Continuous cellular automata (Math-Biology) | Embodied agents in n-Dimensional space |
| **Physics / Environment** | CPU cycles & memory access (Fixed) | Rewards for logical tasks (NOT, AND) (Fixed) | Differential equations (flow, kernel) (Fixed) | Extensible via Plugins (e.g., Energy, Mutation)¹ |
| **Organism Body** | Disembodied (Code string only) | Disembodied (CPU + Memory buffer) | Morphological patterns (solitons) | Embodied (IP + DPs navigating spatial grid) |
| **Interaction Model** | Parasitism (reading neighbor's RAM) | Limited (mostly competition for space) | Collision, fusion & repulsion of patterns | Direct & Spatial (via DPs) & Signaling¹ |
| **Evolutionary Driver** | Implicit competition for memory/CPU | Directed (user-defined rewards) | Spontaneous pattern formation | Metabolic & spatial constraints |
| **Execution Model** | Sequential (Single IP) | Sequential (Single IP) | Parallel (Continuous dynamics) | Parallel & Multi-threaded (via FORK)¹ |
| **Primary Research Focus** | Ecology of code & parasites | Evolution of complex logic functions | Self-organizing morphology | Bioenergetics & Major Transitions |

¹ These capabilities are supported by the core architecture and represent key future research directions.

<br>

## Looking for Contributors

Evochora addresses the stagnation of current ALife systems by creating a "digital universe" where the rules of physics are not pre-supposed but are themselves objects of research. By making these "laws" modular and extensible, the scientific community is invited to collaboratively explore what properties an environment must possess for Open-Ended Evolution to emerge. Evochora is seeking for support of Systems Engineers and ALife Researchers to help design and implement basically every part of the system. Some examples:

- **Thermodynamics (Entropy)**: Designing a system-wide energy loss model to naturally suppress infinite loops and drive efficiency.
- **Spatial Ownership**: Implementing VM-level "property rights" to distinguish between aggressive overwriting (attacks) and offspring placement.
- **Fuzzy Addressing (SignalGP)**: Moving from absolute memory addresses to pattern-matching jumps to make the genome resilient to mutation and enable Digital Eukaryogenesis.

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

The Evochora compiler is a multi-pass pipeline that transforms human-readable EvoASM assembly code into a `ProgramArtifact` ready for execution. This design ensures determinism and separates concerns into a frontend (parsing and analysis) and a backend (layout and code generation). Most phases are extensible via handler registries, allowing new features to be added in a modular way.

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

The backend is powered by a lightweight Javalin HTTP server. Backend `Controller` classes register API endpoints (e.g., `/api/visualizer/...`) that are called by the frontend. These controllers, in turn, interact with backend services like the `ServiceRegistry` to fetch simulation data.

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

_Full disclosure: This system is build using AI coding tools extensively, but definitely not on autopilot. You can't build a custom VM, a compiler, and a distributed data pipeline by just pressing 'generate'. The workflow involved creating precise specifications for every module and rigorously reviewing/debugging the AI's output. The AI wrote the syntax, but the architecture and the logic are 100% manually engineered and verified._

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
