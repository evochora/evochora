<div align="center">

| <h3>👉 <a href="http://evochora.org">SEE LIVE DEMO</a></h3><code>All web based, no installation, full simulation run,<br>with analytics and visual debugger.</code> |
| :---: |

<br>

<a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-2ea44f?style=flat&logo=opensourceinitiative&logoColor=white" height="28"></a>&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://discord.gg/1442908877648822466"><img src="https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat&logo=discord&logoColor=white" height="28"></a>&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/evochora/evochora/actions"><img src="https://img.shields.io/github/actions/workflow/status/evochora/evochora/build.yml?branch=main&style=flat&logo=github&logoColor=white&label=Build" height="28"></a>

</div>


# Evochora
A distributed platform for research into the foundational physics of digital evolution.

<br>

<video src="https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859" loop></video>

<br>

## Table of Contents

- [Evochora](#evochora)
- [The Vision: Embodied Artificial Life](#the-vision-embodied-artificial-life)
- [Key Features](#key-features)
- [Current Research Status: The "Zombie" Frontier](#current-research-status-the-zombie-frontier)
- [Scientific Overview & Background](#scientific-background)
- [Quick Start (Run a Simulation)](#quick-start-run-a-simulation)
- [Configuration Overview](#configuration-overview)
- [Command Line Interface (CLI)](#command-line-interface-cli)
- [Development & Local Build](#development--local-build)
- [Architecture at a Glance](#architecture-at-a-glance)
    - [Runtime: The Digital Physics of Evochora](#runtime-the-digital-physics-of-evochora)
    - [Data pipeline](#data-pipeline)
- [Roadmap – Planned Platform Features](#roadmap--planned-platform-features)
- [Contributing](#contributing)
- [Community & Links](#community--links)

<br>

## The Vision: Embodied Artificial Life

Evochora represents a shift in Artificial Life research: Unlike traditional systems (as *Tierra* or *Avida*), Evochora moves beyond simulating abstract logic. Instead, it simulates **embodied agents** in a rigorous spatial environment. 

Rather than executing high-level scripts, each organism in Evochora runs its own instance of a **low-level Assembly VM**. These agents share a common, n-dimensional memory space — the "Chora". They do not just process data; they occupy territory, harvest energy, and must evolve their own machine-code algorithms to survive and replicate.

By making the "laws" of the digital universe modular and extensible, Evochora invites the scientific community to collaboratively explore what properties an environment must possess for complex innovation to emerge.

The platform is engineered for massive scale, featuring a custom compiler, a distributed data pipeline, and a web-based visualizer to inspect the internal state of any organism in real-time.

👉 **Want to start a simulation right away? See [Quick Start](#quick-start-run-a-simulation).**

<br>

## Key Features

- **N-Dimensional Spatial Worlds**: Configurable grid size and dimensionality (2D to n-D), bounded or toroidal topology
- **Embodied Agency**: Organisms navigate via instruction pointers (IP) and data pointers (DPs) with enforced locality
- **Rich Virtual Machine**: Versatile registers, three distinct stacks (data, call, location), and a complete Evochora Assembly (EvoASM) language
- **Intrinsic Selection Pressure**: Survival requires active energy foraging; every instruction costs energy
- **Extensible Physics**: Pluggable systems for energy distribution, mutation models, and more
- **Full Determinism**: Reproducible experiments via fixed random seeds and deterministic conflict resolution
- **Scalable Architecture**: In-memory execution → persistent storage → indexing → web-based debugging
- **Cloud-Ready**: Designed to scale from single-machine prototyping to distributed cloud deployments

<br>

## Current Research Status: The "Zombie" Frontier

**We are looking for collaborators to help design the "Laws of Physics" for this universe.**

Technically, the engine is fully operational. We have successfully evolved viable ancestors capable of sustaining stable populations for over **500,000 ticks**, producing lineage trees deeper than 15 generations.

**However, we are facing the expected first hurdle of Open-Ended Evolution:**
In its current unconstrained state, the system behaves like a raw physical medium and tends towards a **"Grey Goo"** scenario. Damaged organisms can enter aggressive "Zombie" loops that overwrite the shared memory space. Unlike legacy systems (*Tierra*, *Avida*) that solved this via artificial CPU quotas, we treat this as a signal to implement **Thermodynamics**. We aim to stabilize the system through physical costs (Entropy), turning this hurdle into a driver for emergent complexity.

This is not a software bug, but a theoretical challenge: **How do we engineer stability without hard-coding behavior?**

We invite **Systems Engineers** and **ALife Researchers** to join the lab. We need help designing mechanisms for:
1.  **Thermodynamics (Entropy):** Implementing system-wide energy loss to limit destructive infinite loops.
2.  **Spatial Ownership:** Enforcing "property rights" at the VM level to prevent accidental overwrites.
3.  **Robust Genetics:** Implementing "Fuzzy Addressing" to make code execution resilient to mutation.

👉 **Interested? Read the details in [OPEN_RESEARCH_QUESTIONS.md](docs/OPEN_RESEARCH_QUESTIONS.md)**

<br>

## Scientific Background

One of the most profound goals in science is to understand whether the evolutionary path taken on Earth is a unique accident or the result of a universal principle. Evochora addresses this by creating a "digital universe" where the rules of physics and evolution are not pre-supposed but are themselves objects of research.

By making the "laws" of the digital universe modular and extensible, Evochora invites the scientific community to collaboratively explore what properties an environment must possess for complex innovation—and eventually open-ended evolution—to emerge.

If you want to dive deeper into the scientific motivation and research questions (open-ended evolution, embodied agency, digital chemistry, distributed architectures), start here:

👉 **See the full [SCIENTIFIC_OVERVIEW.md](docs/SCIENTIFIC_OVERVIEW.md)** – Detailed research agenda, architecture, and long-term vision.

<br>

## Key Differentiators

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

## Quick Start (Run a Simulation)

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

### 3. Open the Web UI

Once the node is running, it will by default execute the primordial organism defined in [`assembly/primordial/main.evo`](./assembly/primordial/main.evo) as configured in [`config/evochora.conf`](./evochora.conf).  

Open the visualizer in your browser to see it:
`http://localhost:8081/visualizer/`

<br>

## Usage Modes

Evochora supports multiple usage and deployment modes:

- **In-Process Mode (current default)**  
  All core components (Simulation Engine, Persistence Service, Indexer, HTTP server) run in a single process or container.  
  Best for local experiments, quick iteration, and single-machine runs.

- **Planned Distributed Cloud Mode**  
  Each service (Simulation Engine, Persistence, Indexer, HTTP server, etc.) runs in its own container or process and can be scaled horizontally. Intended for large-scale, long-duration experiments and cloud deployments.

The current releases focus on the in-process mode; the distributed mode is part of the roadmap.

<br>

## Configuration Overview

Evochora is configured via a HOCON configuration file, typically named [`config/evochora.conf`](./evochora.conf).

A complete example configuration is provided as [`config/evochora.conf`](./evochora.conf) in the repository and included in the distribution.

## Command Line Interface (CLI)

The Evochora CLI is the main entry point for running simulations and tools.

**Main commands:**

- `node` – Run and control the simulation node (pipeline, services, HTTP API)
- `compile` – Compile EvoASM (Evochora Assembly) programs for the Evochora VM
- `inspect` – Inspect stored simulation data (ticks, runs, resources)
- `video` – Render simulation runs into videos (requires `ffmpeg`)

Further CLI documentation and fully worked examples:

👉  **[CLI Usage Guide](docs/CLI_USAGE.md)** – All commands, parameters, and usage examples (including `node`, `compile`, `inspect`, and `video`).

<br>

## Development & Local Build

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

- [`CONTRIBUTING.md`](./CONTRIBUTING.md) – Contribution workflow and expectations.
- [`AGENTS.md`](./AGENTS.md) – Coding conventions, architecture and compiler/runtime design principles, testing rules.

<br>

## Architecture at a Glance

Evochora is built as a modular stack:

- **Compiler**  
  Translates EvoASM into VM instructions and layouts via an immutable phase pipeline (preprocessor, parser, semantic analyzer, IR generator, layout engine, emitter).

- **Runtime / Virtual Machine**  
  Each organism is an independent VM with its own registers, stacks, and pointers in an n-dimensional world of typed Molecules (CODE, DATA, ENERGY, STRUCTURE).  
  Strong locality and an energy-first design create intrinsic selection pressure.

- **Data Pipeline**  
  Simulation Engine → queue → Persistence Service → storage → Indexer → queryable indexes for debugging and analysis.

- **Node & HTTP API**  
  Orchestrates services and resources, exposes REST endpoints (e.g. `/api/pipeline/...`) and powers the web-based visualizer.


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

Every service in this diagram can be deployed in docker or a dedicated machine, the communication resources between the services (queue, storage, database, etc.) use implementation abstract interfaces and can easily implemented as cloud resources. As this is still in development we still see this a a roadmap topic.

<br>

## Roadmap – Planned Platform Features

Some key directions for the technical evolution of Evochora:

- **Distributed Cloud Mode** – Run Simulation Engine, Persistence Service, Indexer, HTTP server, etc. as separate processes/containers with horizontal scaling for large experiments.
- **Multithreaded Simulation Engine** – Parallelize the plan/resolve/execute phases across CPU cores to support larger worlds and more organisms on a single machine.
- **Pluggable Mutation System** – Make mutation models first-class plugins (e.g., replication errors, background radiation, genomic rearrangements) to study their impact on open-ended evolution.
- **Extended Data Pipeline & Resume Support** – More scalable, cloud-native persistence and indexing with the ability to resume simulations from stored states.

👉 **Project Board & Roadmap:** [GitHub Projects](https://github.com/orgs/evochora/projects/1/views/1)

<br>

## Contributing

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
