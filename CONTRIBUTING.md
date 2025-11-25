# Contributing to Evochora

Thank you for your interest in contributing to Evochora! We are building a foundational platform for digital evolution research and welcome contributions from developers, biologists, and complexity scientists.

## How can you help?

1. **Code Contributions**: Fix bugs, add features, or improve the VM performance.
2. **Scientific Discussion**: Propose new "laws of physics" for the simulation in our Discussions.
3. **Primordial Design**: Create efficient ancestors in Assembly (`.evo` files).

## Getting Started

1. **Fork the repository** on GitHub.
2. **Clone your fork** locally.
3. **Setup**: Ensure you have Java 21 installed.
4. **Build**: Run `./gradlew build` to verify everything works.

## Development Standards (Important!)

We maintain strict architectural standards to keep the simulation deterministic and performant.

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