# Contributing to Evochora

Thank you for your interest in contributing to Evochora! We are building a foundational platform for digital evolution research and welcome contributions from developers, biologists, and complexity scientists.

## Quick Start (How to Build)

You need **Java 21** and **Git**.

```bash
# Clone the repository
git clone https://github.com/evochora/evochora.git
cd evochora

# Build & test
./gradlew build

# Run the node in dev mode (uses ./evochora.conf by default)
./gradlew run --args="node run"
```

Open the web frontend in your browser:
`http://localhost:8081/visualizer/`


## Configuration Overview

Evochora is configured via a HOCON configuration file, typically named [`./evochora.conf`](./evochora.conf).

A complete example configuration is provided as [`./evochora.conf`](./evochora.conf) in the repository and included in the distribution.

## Writing Your Own Organisms

Organisms are programmed in **EvoASM**, Evochora's custom assembly language. The primordial organism in `assembly/primordial/main.evo` is a good starting point.

**Quick Start:**
1. Edit or create a `.evo` file in `assembly/`
2. Configure it in `evochora.conf` (see `simulation-engine.options.organisms`)
3. Run `./gradlew run --args="node run"` - the engine compiles th eorganisms evoASM code automatically

**Resources:**
- **Language Reference:** [docs/ASSEMBLY_SPEC.md](docs/ASSEMBLY_SPEC.md)
- **Syntax Highlighting:** VS Code/Cursor extension in [`extensions/vscode/`](extensions/vscode/README.md)

## Command Line Interface (CLI)

The Evochora CLI is the main entry point for running simulations and tools.

**Main commands:**

- `node` – Run and control the simulation node (pipeline, services, HTTP API)
- `compile` – Compile EvoASM (Evochora Assembly) programs for the Evochora VM
- `inspect` – Inspect stored simulation data (ticks, runs, resources)
- `video` – Render simulation runs into videos (requires `ffmpeg`)

Further CLI documentation and fully worked examples:

**[CLI Usage Guide](docs/CLI_USAGE.md)** – All commands, parameters, and usage examples (including `node`, `compile`, `inspect`, and `video`).


## How can you help?

1. **Code Contributions**: Fix bugs, add features, or improve the VM performance.
2. **Scientific Discussion**: Propose new "laws of physics" for the simulation in our Discussions.
3. **Primordial Design**: Create efficient ancestors in Assembly (`.evo` files).


## Where to start?
 * [Good First Issue](https://github.com/evochora/evochora/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22action%3A%20good%20first%20issue%22): Start here! Small tasks, minimal context needed.
 * [Help Wanted](https://github.com/evochora/evochora/issues?q=state%3Aopen%20label%3A%22action%3A%20help%20wanted%22): Slightly more complex, but we need hands on deck.

## Questions?
 * Join the Evochora Discord (Community Chat): [![Discord](https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat-square&logo=discord)](https://discord.gg/t9yEJc4MKX)
 * Use GitHub Discussions: [Discussions](https://github.com/evochora/evochora/discussions)

## Development Standards (Important!)

We maintain strict architectural standards to keep the simulation deterministic and performant.

If you are wondering why the project uses a custom Assembly language or a distributed microservice architecture instead of a simple Python script, please read **[Architecture & Design Decisions](docs/ARCHITECTURE_DECISIONS.md)** first.

Before writing code, please read **[AGENTS.md](AGENTS.md)**. It contains:
* The architecture rules (Hexagonal Architecture).
* Testing guidelines (Unit vs. Integration vs. Benchmark).
* Coding style (Java/Kotlin standards).

## Pull Request Process

1. Create a new branch for your feature (`git checkout -b feature/my-feature`).
2. Write tests for your changes (see `AGENTS.md`).
3. Ensure `./gradlew test` passes.
4. Submit a Pull Request targeting the `main` branch.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
