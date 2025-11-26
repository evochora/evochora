# Evochora

**A collaborative platform for research into the foundational physics of digital evolution.**

Evochora is an open-source research platform for simulating artificial life in rich, n-dimensional worlds. Organisms are embodied agents running on a low-level Evochora Assembly (EvoASM) virtual machine and must actively forage for energy, manage metabolism, and solve the mechanical and ecological challenges of self-replication.
By making the "laws" of the digital universe modular and extensible, Evochora invites the scientific community to collaboratively explore what properties an environment must possess for complex innovation to emerge.
The platform is architected for scalability: simulations can run on a single machine for initial experiments or be deployed in a distributed cloud environment for massive-scale, long-duration evolutionary studies.

---

- **Want to start a simulation right away?** See [Quick Start](#quick-start-using-a-downloaded-ziptar-distribution).
- **Want to understand the science behind Evochora?** See [Scientific Background](#scientific-background).

---

## Quick Start (Using a Downloaded ZIP/TAR Distribution)

### Requirements

- Java 21 (JRE or JDK)
- A terminal shell (Linux, macOS, WSL on Windows)

### Start the Simulation Node (In-Process Mode)

Download and unpack the latest distribution from the GitHub Releases page: https://github.com/rainco77/evochora/releases

```bash
cd evochora-<version>
bin/evochora node run
```

This will:

- Load configuration from [`config/evochora.conf`](./evochora.conf).
- Start the in-process simulation node (simulation engine, persistence, indexer, HTTP server)
- Run until you terminate it (Ctrl + C)

### 3. Open the Web UI

Once the node is running, it will by default execute the primordial organism defined in [`assembly/primordial/main.evo`](./assembly/primordial/main.evo) as configured in [`config/evochora.conf`](./evochora.conf).  
Open the visualizer in your browser to see it:

- Visualizer UI: `http://localhost:8081/visualizer/`

## Preview

Short demo of Evochora’s web-based visualizer:
https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859

- Visualizer: 2D view into the simulated world (cells, organisms, energy).
- Goal: Quickly see what kinds of dynamics Evochora can produce.
- Live visualizer demo (hosted): http://evochora.org/visualizer/

---

## Key Features

- **N-Dimensional Spatial Worlds**: Configurable grid size and dimensionality (2D to n-D), bounded or toroidal topology
- **Embodied Agency**: Organisms navigate via instruction pointers (IP) and data pointers (DPs) with enforced locality
- **Rich Virtual Machine**: Versatile registers, three distinct stacks (data, call, location), and a complete Evochora Assembly (EvoASM) language
- **Intrinsic Selection Pressure**: Survival requires active energy foraging; every instruction costs energy
- **Extensible Physics**: Pluggable systems for energy distribution, mutation models, and more
- **Full Determinism**: Reproducible experiments via fixed random seeds and deterministic conflict resolution
- **Scalable Architecture**: In-memory execution → persistent storage → indexing → web-based debugging
- **Cloud-Ready**: Designed to scale from single-machine prototyping to distributed cloud deployments


## Scientific Background

If you are primarily interested in the scientific motivation and research questions (open-ended evolution, embodied agency, digital chemistry, distributed architectures), start here:

- **[Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)** – Detailed research agenda, architecture, and long-term vision.

---

## Usage Modes

Evochora supports multiple usage and deployment modes:

- **In-Process Mode (current default)**  
  All core components (Simulation Engine, Persistence Service, Indexer, HTTP server) run in a single process or container.  
  Best for local experiments, quick iteration, and single-machine runs.

- **Planned Distributed Cloud Mode**  
  Each service (Simulation Engine, Persistence, Indexer, HTTP server, etc.) runs in its own container or process and can be scaled horizontally. Intended for large-scale, long-duration experiments and cloud deployments.

The current releases focus on the in-process mode; the distributed mode is part of the roadmap.

---

## Configuration Overview

Evochora is configured via a HOCON configuration file, typically named [`config/evochora.conf`](./evochora.conf).

A complete example configuration is provided as [`config/evochora.conf`](./evochora.conf) in the repository and included in the distribution.

---

## Command Line Interface (CLI)

The Evochora CLI is the main entry point for running simulations and tools.

**Main commands:**

- `node` – Run and control the simulation node (pipeline, services, HTTP API)
- `compile` – Compile EvoASM (Evochora Assembly) programs for the Evochora VM
- `inspect` – Inspect stored simulation data (ticks, runs, resources)
- `video` – Render simulation runs into videos (requires `ffmpeg`)

Further CLI documentation and fully worked examples:

- **[CLI Usage Guide](docs/CLI_USAGE.md)** – All commands, parameters, and usage examples (including `node`, `compile`, `inspect`, and `video`).

---

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

For deeper detail and scientific background, see:

- [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)
- [Assembly Language Specification](docs/ASSEMBLY_SPEC.md) (EvoASM – Evochora Assembly)
- [Compiler IR Specification](docs/COMPILER_IR_SPEC.md)

---

## Roadmap – Planned Platform Features

Some key directions for the technical evolution of Evochora:

- **Distributed Cloud Mode** – Run Simulation Engine, Persistence Service, Indexer, HTTP server, etc. as separate processes/containers with horizontal scaling for large experiments.
- **Multithreaded Simulation Engine** – Parallelize the plan/resolve/execute phases across CPU cores to support larger worlds and more organisms on a single machine.
- **Pluggable Mutation System** – Make mutation models first-class plugins (e.g., replication errors, background radiation, genomic rearrangements) to study their impact on open-ended evolution.
- **Extended Data Pipeline & Resume Support** – More scalable, cloud-native persistence and indexing with the ability to resume simulations from stored states.

---

## Development & Local Build

If you want to develop Evochora itself:

```bash
# Clone the repository
git clone https://github.com/yourusername/evochora.git
cd evochora

# Build & test
./gradlew build

# Run the node in dev mode (uses ./evochora.conf by default)
./gradlew run --args="node run"
```

See also:

- [`CONTRIBUTING.md`](./CONTRIBUTING.md) – Contribution workflow and expectations.
- [`AGENTS.md`](./AGENTS.md) – Coding conventions, architecture and compiler/runtime design principles, testing rules.

---

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
  http://evochora.org/visualizer/

- API Documentation (developer-focused):  
  http://evochora.org/api-docs/

- Key documentation in this repository:
    - [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md)
    - [CLI Usage Guide](docs/CLI_USAGE.md)
    - [Assembly Specification](docs/ASSEMBLY_SPEC.md) (EvoASM – Evochora Assembly)

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