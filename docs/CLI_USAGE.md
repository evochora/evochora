# Evochora CLI Usage Guide

1. [Overview](#1-overview)
   - [Environment Variables](#environment-variables)
2. [Getting Help](#2-getting-help)
3. [Configuration](#3-configuration)
   - [Project Directory](#project-directory)
   - [Configuration File](#configuration-file)
   - [Configuration File Format](#configuration-file-format)
4. [Start Simulation Node](#4-start-simulation-node)
   - [Start a Run (`node run`)](#start-a-run-node-run)
   - [Continue a Run (`node resume`)](#continue-a-run-node-resume)
   - [Fork a Run (`node fork`)](#fork-a-run-node-fork)
   - [View Indexed Runs (`node show`)](#view-indexed-runs-node-show)
5. [Compile EvoASM (Evochora Assembly) Programs](#5-compile-evoasm-evochora-assembly-programs)
   - [Source Roots (`--source-root`)](#source-roots---source-root)
   - [Flags (`--define`)](#flags---define)
   - [Values (`--value`)](#values---value)
   - [Environment Parameters (`--env`)](#environment-parameters---env)
   - [JSON Output (ProgramArtifact)](#json-output-programartifact)
6. [Inspect Storage Data](#6-inspect-storage-data)
7. [Render Simulation Videos](#7-render-simulation-videos)
8. [Clean Up Simulation Runs](#8-clean-up-simulation-runs)
9. [Exit Codes](#9-exit-codes)
10. [Troubleshooting](#10-troubleshooting)

## 1. Overview

`bin/evochora` is the command line interface of Evochora. It starts the node, the process that executes a run, records it and serves the web frontend, and it works on programs and on recorded runs. The [User Guide](USER_GUIDE.md#key-concepts) explains these terms.

```
bin/evochora [(-c | --config) <file>] [-h | --help] [-V | --version] <command> [<subcommand>] [<options>]
```

On Windows, the start script is `bin\evochora.bat`.

Global options, given before the command:

- `-c`, `--config <file>`: Use this configuration file. See [Configuration](#3-configuration).
- `-h`, `--help`: Show the commands, or after a command its options. See [Getting Help](#2-getting-help).
- `-V`, `--version`: Print the version of the build and the commit it was made from. A numbered release also prints its DOI.

Commands:

- [`node`](#4-start-simulation-node): Start a node to start a new run (`node run`), continue an interrupted one (`node resume`), record a stretch of one again as a new run (`node fork`), or serve the recorded runs without simulating (`node show`).
- [`compile`](#5-compile-evoasm-evochora-assembly-programs): Compile an EvoASM program.
- [`inspect`](#6-inspect-storage-data): Print a recorded tick of a run.
- [`video`](#7-render-simulation-videos): Render a recorded run into a video.
- [`cleanup`](#8-clean-up-simulation-runs): Delete recorded runs.

### Environment Variables

The start script reads two environment variables:

- `EVOCHORA_OPTS`: Options for the Java virtual machine, such as the maximum heap (default: 8 GB).
  ```bash
  EVOCHORA_OPTS="-Xmx16g" bin/evochora node run
  ```
  On Windows, in the command prompt and in PowerShell:
  ```bat
  set EVOCHORA_OPTS=-Xmx16g
  bin\evochora.bat node run
  ```
  ```powershell
  $env:EVOCHORA_OPTS = "-Xmx16g"
  bin\evochora.bat node run
  ```
- `EVOCHORA_JAVA_HOME`: The installation directory of a Java 25 or newer to run Evochora on, instead of the Java the archive brings.
  ```bash
  EVOCHORA_JAVA_HOME=/opt/jdk-25 bin/evochora node run
  ```
  On Windows, `set EVOCHORA_JAVA_HOME=C:\jdk-25` in the command prompt or `$env:EVOCHORA_JAVA_HOME = "C:\jdk-25"` in PowerShell, as for `EVOCHORA_OPTS`.

---

## 2. Getting Help

The built-in help is the authoritative source for all options and their defaults. This document describes what the commands do and how their options work together.

```bash
# Show all available commands
bin/evochora --help

# Show help for a command
bin/evochora help node
bin/evochora help compile
bin/evochora help inspect
bin/evochora help video
bin/evochora help cleanup

# Show help for a subcommand
bin/evochora node resume --help
bin/evochora node fork --help
bin/evochora inspect storage --help
bin/evochora video lineage --help
```

---

## 3. Configuration

### Project Directory

The project directory is the directory with your configuration, your ancestors and the data of your runs. The node reads `config/evochora.conf` from it, and the configuration resolves its relative source roots and its data directory against it.

By default, the project directory is the installation, the unpacked release archive. When Evochora runs from a build or an IDE instead, it is the working directory. To use another directory, pass the JVM option `-Devochora.home=<directory>` in `EVOCHORA_OPTS`. The [User Guide](USER_GUIDE.md#1-install-and-start) describes how to work in the project directory.

### Configuration File

`--config` names the configuration file, relative to the working directory. It must come before the command:

```bash
bin/evochora --config config/my-experiment.conf node run
```

Without `--config`, the configuration file is `config/evochora.conf` of the [project directory](#project-directory). If the project directory has no such file, only the defaults of `reference.conf` apply, and a warning says so. If the file `--config` names does not exist, or if a configuration file has invalid syntax, the command logs an error and exits.

### Configuration File Format

Configuration files are written in [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md). [`config/evochora.conf`](../config/evochora.conf) is the configuration of the project directory. An experiment has a file of its own next to it, which begins with `include required("evochora.conf")` and sets only what the experiment changes. The [User Guide](USER_GUIDE.md#3-configure-an-experiment) describes how to set one up. [`reference.conf`](../src/main/resources/reference.conf) explains every setting and supplies the default for everything the other files do not set.

---

## 4. Start Simulation Node

Every `node` subcommand starts a node. Ctrl+C stops the node. A run that was stopped this way can be continued later with [`node resume`](#continue-a-run-node-resume).

Unless the configuration says otherwise, the node serves the visualizer at `http://localhost:8081/visualizer/`, the analyzer at `http://localhost:8081/analyzer/` and the HTTP API, which is documented at `http://localhost:8081/api-docs/`. It also opens the database to SQL tools and logs the connection URL at startup. What the node runs, and on which ports, is part of the configuration. [`reference.conf`](../src/main/resources/reference.conf) explains these settings under `processes`.

### Start a Run (`node run`)

Starts a new run. The node compiles the ancestors named in the configuration, places them in the environment, simulates and records the run.

```
bin/evochora node run
```

### Continue a Run (`node resume`)

Continues an interrupted run from the last full state it recorded.

```
bin/evochora node resume [(-r | --run) <run-id>] [(-s | --storage) <name>]
```

Options:

- `-r`, `--run <run-id>`: ID of the run to continue (default: the newest run in storage).
- `-s`, `--storage <name>`: Storage resource the run is read from, one of the resources under `pipeline.resources` in the configuration (default: `tick-storage`).

Examples:

```bash
# Continue the newest run
bin/evochora node resume

# Continue a named run
bin/evochora node resume --run <run-id>
```

### Fork a Run (`node fork`)

Records a stretch of an existing run again as a new run, typically more densely than the original. The new run may begin a little before `--from` and end a little after `--to`. The more densely it records, the closer to `--from` it begins. After its last tick the simulation pauses. A `POST` request to `/pipeline/api/service/simulation-engine/resume` of the HTTP API continues it.

```
bin/evochora node fork --from <tick> --to <tick> [(-r | --run) <run-id>]
    [(-p | --profile) <name>] [--sampling-interval <n>] [(-s | --storage) <name>]
```

Options:

- `--from <tick>`: First tick that must be recorded (required).
- `--to <tick>`: Last tick that must be recorded (required).
- `-r`, `--run <run-id>`: ID of the run to fork (default: the newest run in storage).
- `-p`, `--profile <name>`: Tuning profile the fork is recorded with (default: the configured one). The [User Guide](USER_GUIDE.md#3-configure-an-experiment) explains the profiles.
- `--sampling-interval <n>`: Number of ticks from one recorded tick to the next, in place of the profile's.
- `-s`, `--storage <name>`: Storage resource the run is read from (default: `tick-storage`).

Examples:

```bash
# Record ticks 150000..200000 of the newest run again with the detailed profile
bin/evochora node fork --from 150000 --to 200000 --profile detailed

# Fork a named run and record every 10th tick
bin/evochora node fork --run <run-id> --from 150000 --to 200000 --sampling-interval 10
```

### View Indexed Runs (`node show`)

Starts the node without simulating. It serves the runs already in the database to the visualizer and the analyzer.

```
bin/evochora node show
```

---

## 5. Compile EvoASM (Evochora Assembly) Programs

`compile` compiles an EvoASM program and prints the result, a `ProgramArtifact`, as JSON to standard output. A compilation error is printed to standard error.

```
bin/evochora compile (-f | --file) <file> [--source-root <path>[:<PREFIX>]]...
    [--define <NAME>[=<NUMBER>|true|false]]... [--value <NAME>=<TEXT>]...
    [(-e | --env) <dimensions>:<topology>]
```

Options:

- `-f`, `--file <file>`: Main file of the program, a path under a source root (required).
- `--source-root <path>[:<PREFIX>]`: Directory or URL that paths are resolved against. See [Source Roots](#source-roots---source-root).
- `--define <NAME>[=<NUMBER>|true|false]`: Flag for conditional compilation. See [Flags](#flags---define).
- `--value <NAME>=<TEXT>`: Value of the main program. See [Values](#values---value).
- `-e`, `--env <dimensions>:<topology>`: Environment the program is laid out for (default: the environment of the configuration). See [Environment Parameters](#environment-parameters---env).

Examples:

```bash
# Compile for the environment of the configuration
bin/evochora compile --source-root assembly/examples --file=simple.evo

# Compile for another environment
bin/evochora compile --source-root assembly/examples --file=simple.evo --env=256x256:bound

# Use two source roots, the library with the prefix LIB
bin/evochora compile --source-root assembly/ancestors --source-root assembly/lib:LIB --file=default/main.evo

# Set flags for conditional compilation and a value of the main program
bin/evochora compile --source-root assembly/examples --file=simple.evo --define AGGRESSIVE --define REDUNDANCY=2 --value STEPS=DATA:20
```

### Source Roots (`--source-root`)

A source root is a directory or an HTTP URL from which the compiler resolves the main file and the paths in `.IMPORT`, `.REQUIRE` and `.SOURCE`. All paths in directives are relative to their source root, never to the file that names them.

- `--source-root <path>`: The unprefixed source root.
- `--source-root <path>:<PREFIX>`: A named source root, such as `./predator:PRED` or `https://example.com/organisms:PRED`. A path into it is written with the prefix, in `--file` as in a directive: `.IMPORT "PRED:lib/move.evo" AS MOVE`. The prefix must match `[A-Z][A-Z0-9_]+`, so it has at least two characters. A single letter would collide with a Windows drive letter like `C:\`.

The option may be repeated. Without it, the directory of the main file is the only source root.

### Flags (`--define`)

Decides a flag for the conditional compilation directives (`.IFDEF` and its relatives, see [EVOASM_SPEC.md](EVOASM_SPEC.md#conditional-compilation)) before the first line of the program:

- `--define NAME` or `--define NAME=true`: Sets the flag without a value.
- `--define NAME=NUMBER`: Sets the flag with that value, written in decimal or as `0x…`, `0o…` or `0b…`, with an optional minus.
- `--define NAME=false`: Switches the flag off, so that a `.DEFINE DEFAULT` of it in the program does not take effect.

A flag the option does not name is free, and the program's `.DEFINE DEFAULT` of it takes effect. The option may be repeated. Flag names, `true` and `false` are case-insensitive. In a simulation the same flags are decided per organism in the configuration: `defines { NAME = true }`, `NAME = 2` or `NAME = false` in the organism entry.

### Values (`--value`)

Sets a value of the main program. Nothing imports the main program, so no `USING VALUE` can bind its values. Instead, the main program's `.REQUIRE VALUE` of that name takes the value in place of its default (see [EVOASM_SPEC.md](EVOASM_SPEC.md#require)).

- `--value NAME=TEXT`: The text is an operand as a `.CONST` takes it: a literal, a vector, a name of the main program or an expression with them, such as `--value STEPS=DATA:20` or `--value 'STEPS=DATA:(WIDTH/2)'`.

The option may be repeated. Value names are case-insensitive. A value no `.REQUIRE VALUE` of the main program names is ignored, as a flag no `.IFDEF` tests is. In a simulation the same values are set per organism in the configuration (`values { … }` in the organism entry).

### Environment Parameters (`--env`)

`--env=<dimensions>:<topology>` lays the program out for the given environment:

- `dimensions`: Size of the environment along each axis, such as `128x128`, `2048x2048` or, in three dimensions, `1024x1024x128`.
- `topology`: `torus` or `bound`, as in the simulation's configuration. It cannot be left out.

Without `--env`, the program is laid out for the environment of the configuration. `compile` reads nothing else from the configuration: source roots, flags and values come from the command line.

### JSON Output (ProgramArtifact)

The JSON object contains, among others:

- `programId`: Unique identifier of the program.
- `sources`: One entry per placement and source file, with the placement's alias chain, the path as written, the resolved path and the lines.
- `machineCodeLayout`: The code, one entry per cell with its `position` relative to the program's origin and its encoded `instruction`.
- `labelValueToName`, `labelNameToValue`: The value each label stands for in the machine code, by value and by name.
- `registerAliasMap`: The register aliases of the program.
- `procNameToParamNames`: The procedures and their parameters.
- `sourceMap`: Source mapping for debugging.
- `tokenMap`: Token information for syntax highlighting.
- `envProps`: The environment the program was laid out for (`worldShape`, `isToroidal`, `strides`, `totalCells`).

---

## 6. Inspect Storage Data

`inspect storage` prints one recorded tick of a run, to check what the run recorded. If the run did not record that tick, the command lists the tick ranges it found instead.

```
bin/evochora inspect storage (-t | --tick) <tick> (-r | --run) <run-id>
    [(-f | --format) (summary | json | raw)] [(-s | --storage) <name>]
```

Options:

- `-t`, `--tick <tick>`: Tick to print (required).
- `-r`, `--run <run-id>`: ID of the run (required).
- `-f`, `--format <format>`: Output format (default: `summary`).
  - `summary`: The run, the tick and its capture time, the number and size of the organisms and of the non-empty cells, how the tick is stored, and the first organisms and cells.
  - `json`: The complete tick as JSON.
  - `raw`: The tick as a protobuf message in text form.
- `-s`, `--storage <name>`: Storage resource the run is read from (default: `tick-storage`).

Examples:

```bash
# Print the summary of a tick
bin/evochora inspect storage --tick=1000 --run=<run-id>

# Print the complete tick as JSON
bin/evochora inspect storage --tick=1000 --run=<run-id> --format=json

# Read from another storage resource
bin/evochora inspect storage --tick=1000 --run=<run-id> --storage=<name>
```

---

## 7. Render Simulation Videos

`video` renders a recorded run into a video file. It requires ffmpeg to be installed and available in your `PATH`. A renderer subcommand selects how the environment is drawn, and each renderer adds its own options to the shared ones.

```
bin/evochora video <renderer> [(-r | --run) <run-id>] [--out <file>]
    [--format (mkv | mp4 | avi | mov | webm)] [--fps <n>] [--sampling-interval <n>]
    [--from <tick>] [--to <tick>] [--preset (ultrafast | fast | medium | slow)]
    [--crf <n>] [--threads <n>] [--overlay <name>[,<name>]...] [(-s | --storage) <name>]
    [--verbose] [<renderer options>]
```

Renderers:

- `exact`: Draws one pixel per cell, with detailed organism markers.
- `minimap`: Draws an aggregated overview in the style of the visualizer's minimap.
- `lineage`: Draws organisms as glows colored by genome lineage. With `--clade`, it draws the given clades in distinct hues and everything else in gray.
- `descent`: Draws organisms as glows colored by line of descent below the youngest common ancestor of the living. With `--root`, it colors the lines below one organism for the whole video.
- `density`: Draws a heatmap of the instruction pointers.

Shared options:

- `-r`, `--run <run-id>`: ID of the run (default: the newest run).
- `--out <file>`: Output file, relative to the working directory (default: `simulation.mkv`). Its extension follows `--format`. An existing file is overwritten.
- `--format <format>`: Video format (default: `mkv`, which stays playable if rendering is interrupted).
- `--fps <n>`: Frames per second of the video (default: 60).
- `--sampling-interval <n>`: Render only the ticks that are multiples of `<n>`, best a multiple of the run's sampling interval (default: every recorded tick).
- `--from <tick>`, `--to <tick>`: First and last tick to render.
- `--preset <preset>`: ffmpeg encoding preset (default: `fast`). Not used for WebM.
- `--crf <n>`: H.264 constant rate factor for `mkv`, `mp4`, `avi` and `mov`, from 0 (lossless) to 51 (worst quality) (default: 18).
- `--threads <n>`: Number of threads that render in parallel (default: 1).
- `--overlay <name>[,<name>]...`: Overlays to draw on every frame: `info`, `diversity`, `graph`, `logo`.
- `-s`, `--storage <name>`: Storage resource the run is read from (default: `tick-storage`).
- `--verbose`: Show the output of ffmpeg.

Each renderer documents its own options, such as `--scale`, `--glow-size`, `--hue-shift`, `--clade`, `--root`, `--cluster-grid` or `--blur-radius`, in `bin/evochora video <renderer> --help`.

Examples:

```bash
# Render a run to simulation.mkv in the working directory
bin/evochora video minimap --run <run-id>

# Specify the output file and its format
bin/evochora video minimap --run <run-id> --out simulation.mp4 --format mp4

# Render every 100th tick at 30 frames per second
bin/evochora video exact --run <run-id> --fps 30 --sampling-interval 100

# Render a range of ticks
bin/evochora video minimap --run <run-id> --from 1000 --to 5000

# Add the info overlay (tick number, organisms alive, births so far)
bin/evochora video lineage --run <run-id> --overlay info

# Color two clades and gray out everything else
bin/evochora video lineage --run <run-id> --clade <genomeHash>:21 --clade <genomeHash>:210

# Render with four threads
bin/evochora video minimap --run <run-id> --threads 4
```

---

## 8. Clean Up Simulation Runs

`cleanup` deletes recorded runs from storage, from the database and from the topics of the message broker. A glob pattern on the run IDs selects the runs: `*` matches any characters, `?` a single one. Without `--force`, the command only previews what it would delete: for each of the three, the runs it would keep and the runs it would delete, and a summary.

```
bin/evochora cleanup (--keep <pattern> | --delete <pattern>) [--storage] [--database] [--topics]
    [--compact] [--force]
bin/evochora cleanup --compact [--force]
```

Options:

- `--keep <pattern>`: Keep the runs whose IDs match the pattern and delete all others.
- `--delete <pattern>`: Delete the runs whose IDs match the pattern and keep all others.
- `--storage`, `--database`, `--topics`: Clean up only the named ones (default: all three).
- `--compact`: Compact the database afterwards to reclaim disk space. This works only while nothing else is connected to the database, and only if the database is among the targets. It can also be given alone, without a pattern.
- `--force`: Delete and compact. Without it, nothing is changed.

Examples:

```bash
# Preview what would be deleted
bin/evochora cleanup --keep "20260117*"

# Delete all runs except the matching ones
bin/evochora cleanup --keep "20260117*" --force

# Preview deleting the matching runs
bin/evochora cleanup --delete "20260122*"

# Clean up specific targets only
bin/evochora cleanup --keep "20260117*" --storage
bin/evochora cleanup --keep "20260117*" --database --topics

# Delete the matching runs and compact the database
bin/evochora cleanup --delete "20260122*" --compact --force
```

---

## 9. Exit Codes

- `0`: Success.
- `1`: The command failed, for example on a compilation error, a run or file that was not found, or a configuration that could not be loaded.
- `2`: The command line was invalid, for example an unknown option or a missing required option.

---

## 10. Troubleshooting

### Node doesn't start

- Check that the configuration file exists and is valid. The error names the project directory and the file.
- Check that the ports the configuration uses are not already in use.
- Check the log for error messages.

### Compilation fails

- Check that the main file exists under its source root.
- Check that paths in directives are written relative to their source root.
- See [EVOASM_SPEC.md](EVOASM_SPEC.md) for the language.

### Configuration errors

- Check that the file uses valid HOCON syntax.
- Check that an experiment file begins with `include required("evochora.conf")`. An `include` without `required` skips a missing file silently.
- See [`reference.conf`](../src/main/resources/reference.conf) for every setting and its default.

### Warning that a run was written by another build

- Every run records the revision of the build that wrote it. A run is reproduced exactly only by that build, so another build warns when it reads the run.
- To read or continue the run faithfully, use the build that wrote it. The [User Guide](USER_GUIDE.md#8-reproduce-a-run) describes how to keep that build with the run.
- A revision ending in `-dirty` came from uncommitted changes, so that build cannot be made again.

### Inspect storage fails

- Check that the run ID exists in storage.
- Check that the run recorded the tick. The command lists the tick ranges it found.
- Check that `--storage` names the storage resource the run was recorded to.
