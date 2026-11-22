# Development Guide

This guide shows how to build, run and test Evochora from its sources, and what a pull request needs.

- [What You Need](#what-you-need)
- [Useful Documentation](#useful-documentation)
- [Build and Run](#build-and-run)
- [Test](#test)
- [Pull Requests](#pull-requests)

## What You Need

You need a JDK 25 and Git. The build fetches everything else itself.

## Useful Documentation

- [Architecture](ARCHITECTURE.md): how Evochora is built, its packages and how they depend on each other.
- [Architecture & Design Decisions](ARCHITECTURE_DECISIONS.md): why the main parts were built the way they are.
- [Benchmarking](BENCHMARKING.md): how to measure what a change does to performance.

## Build and Run

```bash
git clone https://github.com/evochora/evochora.git
cd evochora
./gradlew build
./gradlew run --args="node run"
```

`build` compiles everything and runs the tests. `run` starts a node with the default ancestor; open `http://localhost:8081/visualizer/` to watch it, and stop it with Ctrl+C. On Windows, write `gradlew` instead of `./gradlew`.

The checkout is your [project directory](../USER_GUIDE.md#1-install-and-start): the node reads `config/evochora.conf` and writes the data of its runs to `data/`, which Git ignores.

## Test

```bash
./gradlew test          # all Java tests
./gradlew unit          # only the unit tests
./gradlew integration   # only the integration tests
./gradlew webTest       # the tests of the web frontend
```

Every Java test carries `@Tag("unit")` or `@Tag("integration")`. A unit test is fast and touches no files, network or database; every other test is an integration test.

## Pull Requests

Before you open a pull request, add tests for what you changed and run:

```bash
./gradlew check
```

`check` runs the Java tests, the web frontend tests and the architecture tests, lints the code, and checks the JavaDoc, the licenses of the dependencies and the test coverage.

In the pull request, describe what you changed, why, and how you checked it. Once it is open, the CI runs the same checks on Linux, Windows and macOS, and automated reviews comment on it.
