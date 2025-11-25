# Evochora

**A collaborative platform for research into the foundational physics of digital evolution.**

Evochora is an advanced open-source scientific reasearch platform to simulate artificial life and investigate the fundamental prerequisites for open-ended evolution. Unlike traditional systems with fixed, hard-coded "physics," Evochora provides a rich, n-dimensional environment where the rules governing evolution are themselves objects of scientific inquiry. Organisms are embodied agents that must navigate their world, actively forage for energy, and solve the mechanical, metabolic, and ecological challenges of self-replication — all using a low-level assembly language, making their behaviors fully evolvable.

The platform is architected for scalability: simulations can run on a single machine for initial experiments or be deployed in a distributed cloud environment for massive-scale, long-duration evolutionary studies. By making the "laws" of the digital universe modular and extensible, Evochora invites the scientific community to collaboratively explore what properties an environment must possess for complex innovation to emerge.

## Key Features

- **N-Dimensional Spatial Worlds**: Configurable grid size and dimensionality (2D to n-D), bounded or toroidal topology
- **Embodied Agency**: Organisms navigate via instruction pointers (IP) and data pointers (DPs) with enforced locality
- **Rich Virtual Machine**: Versatile registers, three distinct stacks (data, call, location), and a complete assembly language
- **Intrinsic Selection Pressure**: Survival requires active energy foraging; every instruction costs energy
- **Extensible Physics**: Pluggable systems for energy distribution, mutation models, and more
- **Full Determinism**: Reproducible experiments via fixed random seeds and deterministic conflict resolution
- **Scalable Architecture**: In-memory execution → persistent storage → indexing → web-based debugging
- **Cloud-Ready**: Designed to scale from single-machine prototyping to distributed cloud deployments

https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859

## Quick Start

### Prerequisites

- Java 21
- Gradle (wrapper included)

### Build & Run

```bash
# Build the project
./gradlew build

# Create executable JAR
./gradlew jar

# Start the simulation node
./gradlew run --args="node run"

# Or use custom configuration
./gradlew run --args="--config evochora.conf node run"
```

### Compile Assembly Code

```bash
# Option 1: Gradle task
./gradlew compile -Pfile="assembly/examples/simple.evo"

# Option 2: Gradle run with args
./gradlew run --args="compile --file=assembly/examples/simple.evo"

# Option 3: Standalone JAR
java -jar build/libs/evochora.jar compile --file=assembly/examples/simple.evo
```

### Render Simulation Videos

```bash
# Render video from simulation run
./gradlew run --args="video --run-id <run-id> --out simulation.mp4"

# See all video rendering options
./gradlew run --args="help video"
```

### HTTP API

When the node is running, it exposes a REST API (default: `http://localhost:8081`) for controlling the simulation pipeline.

*(TODO: Link to auto-generated API documentation, e.g., OpenAPI/Swagger, once available.)*

## Documentation

- **[Assembly Language Specification](docs/ASSEMBLY_SPEC.md)** - Complete instruction set, syntax, and directives
- **[CLI Usage Guide](docs/CLI_USAGE.md)** - Command-line interface and HTTP API reference
- **[Assembly Compile Usage](docs/ASSEMBLY_COMPILE_USAGE.md)** - Compiler usage and integration with AI tools

## Architecture Overview

Evochora is built on three core principles:

**1. Embodied Agency**  
Organisms are not abstract computational entities but embodied agents with:
- An **Instruction Pointer (IP)** that executes CODE molecules
- **Data Pointers (DPs)** that serve as "limbs" for world interaction
- A rich internal state (registers, stacks) providing a low-level toolkit for evolution

**2. Metabolic Economy**  
Survival requires active resource management:
- Every instruction costs energy (deducted from internal Energy Register)
- ENERGY molecules must be foraged using DPs
- STRUCTURE molecules act as obstacles, costing additional energy
- Death occurs when energy reaches zero

**3. Extensible Physics**  
Core systems are modular and pluggable:
- Energy distribution models (random, geyser-based, custom)
- Mutation models (background radiation, replication errors, genomic rearrangements)
- Future: Digital chemistry, inter-organism communication, and more

## Research Avenues

Evochora is designed to address fundamental questions in artificial life:

- **Primordial Organisms**: Designing viable ancestors that solve the replication/energy/space trilemma
- **Mutation Models**: Exploring how different mutation regimes influence evolutionary trajectories
- **Emergence of Sociality**: Investigating cooperation, communication, and the evolution of multicellularity
- **Digital Chemistry**: Moving beyond single-resource worlds to complex metabolic networks
- **Open-Ended Evolution**: Identifying the fundamental prerequisites for sustained innovation

See our [scientific paper](docs/SCIENTIFIC_OVERVIEW.md) for a detailed research agenda.

## Project Status & Roadmap

**Current Status**: Functional platform with stable VM, compiler, data pipeline, and a working primordial organism capable of sustainable replication.

**Immediate Roadmap**:
1. ✅ Core VM and assembly language implementation
2. ✅ Data pipeline with persistence and indexing
3. ✅ **Viable primordial organism** (functional and replicating!)
4. 🔄 **Conduct "Experiment Null"** (first long-duration simulation - in progress)
5. 📋 Implement pluggable mutation system
6. 📋 Multi-threaded simulation engine
7. 📋 Cloud-native deployment architecture

**Long-Term Vision**:
- Inter-organism communication primitives
- Digital chemistry with configurable reaction tables
- Higher-dimensional visualization tools
- Statistical analysis services for population dynamics

## Contributing

We welcome contributions of all kinds:

- **Scientific Discussion**: Share ideas about the "laws" of our digital universe
- **Code Contributions**: Help build the platform (VM, compiler, data pipeline, analysis tools)
- **Primordial Design**: Design and test ancestral organisms
- **Documentation**: Improve docs, write tutorials, create examples
- **Testing**: Report bugs, write tests, improve reproducibility

**TODO**: Detailed contributing guidelines coming soon. For now:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Follow code style in [AGENTS.md](AGENTS.md)
4. Write tests (see Testing Guidelines in AGENTS.md)
5. Submit a pull request

## Community

- **Source Code**: [GitHub Repository](https://github.com/yourusername/evochora) *(TODO: Add actual link)*
- **Issue Tracker**: [GitHub Issues](https://github.com/yourusername/evochora/issues) *(TODO: Add actual link)*
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/evochora/discussions) *(TODO: Add actual link)*
- **Chat**: [Discord Server](https://discord.gg/yourinvite) *(TODO: Add actual link)*
- **Live Demo**: [Web Debugger](https://demo.evochora.org) *(TODO: Add actual link)*

## License

This project is open-source and available under one of the following licenses (to be decided):
- [MIT License](https://opensource.org/licenses/MIT)
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

**TODO**: Final license selection pending discussion.

## Citation

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

**TODO**: Update citation once published.

## Acknowledgments

This project builds on decades of artificial life research, including seminal work on Tierra (Ray, 1991), Avida (Ofria & Wilke, 2004), and the broader A-Life community's investigations into open-ended evolution.

---

**Note**: Evochora is in active development. Some features described in documentation may be planned but not yet implemented. See our [roadmap](#project-status--roadmap) for current status.


Discord: [![Discord](https://img.shields.io/discord/1442908877648822466?label=Join%20Community&logo=discord&style=flat-square)](DEIN_EINLADUNGS_LINK)

Simulation Demo: http://evochora.org/visualizer/
API Doc: http://evochora.org/api-docs/