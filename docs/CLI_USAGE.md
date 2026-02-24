# Evochora CLI Usage Guide

## Overview

The Evochora CLI (`bin/evochora`) is the main entry point for running the simulation node and tools:

- **Node**: start and control the simulation pipeline.
- **Compile**: compile EvoASM (Evochora Assembly) programs.
- **Inspect**: inspect stored simulation data.
- **Video**: render simulation runs into videos.
- **Cleanup**: clean up old simulation runs from storage, database, and topics.

For day-to-day usage (including production deployments and release archives), the **start script** is the primary entry point:

- On Linux/macOS: `bin/evochora`
- On Windows: `bin\evochora.bat`

Gradle (`./gradlew run --args="..."`) and the standalone JAR (`java -jar ...`) are mainly intended for **developers working in the source repository** (see sections further below).

---

## Getting Help

```bash
# Show all available commands
bin/evochora --help

# Show help for a specific command
bin/evochora help node
bin/evochora help compile
bin/evochora help video
bin/evochora help inspect
bin/evochora help cleanup

# Show help for inspect storage subcommand
bin/evochora inspect storage --help
```

The built-in help is the authoritative source for all options and flags. This document focuses on the most common workflows and parameters.

---

## Start Simulation Node

```bash
# Start the node with default configuration
bin/evochora node run

# Start with a custom configuration file
bin/evochora --config config/my-config.conf node run
```

The node will:

- Load the configuration from the specified file  
  - With `--config`: the file you pass (e.g. `config/my-config.conf`)  
  - Without `--config`: `config/evochora.conf`
- Start all configured services (simulation engine, persistence, indexers, HTTP server, etc.)
- Expose the HTTP API for monitoring and control
- Run until interrupted (Ctrl+C)

---

## Compile EvoASM (Evochora Assembly) Programs

The `compile` command translates EvoASM source files into a JSON `ProgramArtifact` that contains machine code layout, labels, procedures, source mapping and more.

```bash
# Basic compilation (uses default environment from config)
bin/evochora compile --file=assembly/examples/simple.evo

# With custom environment
bin/evochora compile --file=assembly/examples/simple.evo --env=200x200:flat
bin/evochora compile --file=assembly/examples/simple.evo --env=1000x1000x100:toroidal
```

### Environment Parameters (`--env`)

Syntax:

- `--env=<dimensions>[:<toroidal>]`
- `dimensions`: world dimensions, e.g. `100x100`, `2000x2000`, `1000x1000x100`
- `toroidal` (optional): `toroidal` (default) or `flat`

Examples:

- `100x100` → 100x100 world, toroidal (default)
- `2000x2000:flat` → 2000x2000 world, flat
- `1000x1000x100:toroidal` → 3D world, toroidal

### JSON Output (ProgramArtifact)

Compilation produces a JSON object with (among others):

- `programId`: unique identifier
- `sources`: source file contents
- `machineCodeLayout`: generated machine code (linear address → instruction)
- `labelAddressToName`: label addresses and names
- `registerAliasMap`: register aliases used by the compiler
- `procNameToParamNames`: procedures and their parameters
- `sourceMap`: source mapping for debugging
- `tokenMap`: token information for syntax highlighting
- `envProps`: environment properties (worldShape, isToroidal)

### Inspect Storage Data

```bash
# Inspect tick data from storage (summary format)
bin/evochora inspect storage --tick=1000 --run-id=my-simulation-run

# Inspect with JSON output format
bin/evochora inspect storage --tick=1000 --run-id=my-simulation-run --format=json

# Inspect with raw protobuf output
bin/evochora inspect storage --tick=1000 --run-id=my-simulation-run --format=raw

# Use custom storage resource
bin/evochora inspect storage --tick=1000 --run-id=my-simulation-run --storage=custom-storage
```

The `inspect storage` command allows you to examine tick data that has been persisted to storage for debugging purposes. It supports three output formats:

- **summary** (default): Human-readable summary with key information
- **json**: Complete tick data in JSON format
- **raw**: Raw protobuf message output

**Parameters:**
- `--tick, -t`: Tick number to inspect (required)
- `--run-id, -r`: Simulation run ID (required)
- `--format, -f`: Output format: json, summary, raw (default: summary)
- `--storage, -s`: Storage resource name (default: tick-storage)

**Example output (summary format):**
```
Found batch file: my-simulation-run/batch_0000000000000001000_0000000000000001999.pb
=== Tick Data Summary ===
Simulation Run ID: my-simulation-run
Tick Number: 1000
Capture Time: 2024-01-15T10:30:45.123Z
Organisms: 150 alive
Cells: 2500 non-empty
RNG State: 32 bytes
Strategy States: 5

=== Organism Summary ===
  ID: 1, Energy: 100, Dead: false
  ID: 2, Energy: 85, Dead: false
  ...

=== Cell Summary ===
  Index: 100, Type: 1, Value: 5, Owner: 1
  Index: 101, Type: 2, Value: 3, Owner: 2
  ...
```

### Render Simulation Videos

The `video` command renders simulation data into video files using ffmpeg. It defaults to the MKV container format, which is resilient to interruptions during rendering. If the process is stopped, the video file remains playable.

```bash
# Basic video rendering (defaults to simulation.mkv)
bin/evochora video --run-id <run-id>

# Specify output file and format
bin/evochora video --run-id <run-id> --out simulation.mp4 --format mp4

# Render with custom frame rate and sampling
bin/evochora video --run-id <run-id> --out simulation.mkv --fps 30 --sampling-interval 100

# Render specific tick range
bin/evochora video --run-id <run-id> --out simulation.mkv --start-tick 1000 --end-tick 5000

# Render with overlay (tick number, timestamp, run ID)
bin/evochora video --run-id <run-id> --out simulation.mkv --overlay-tick --overlay-time --overlay-run-id

# Render with custom quality and format
bin/evochora video --run-id <run-id> --out simulation.webm --format webm --preset fast

# Use parallel rendering for better performance
bin/evochora video --run-id <run-id> --out simulation.mkv --threads 4

# See all available options
bin/evochora help video
```

The `video` command renders simulation data into video files using ffmpeg. It supports various output formats (MKV, MP4, AVI, MOV, WebM), quality presets, frame rate control, and optional text overlays.

**Key Features:**
- **Resilient by default**: Uses MKV format to prevent corrupted files if rendering is interrupted.
- **Performance optimized**: Uses efficient rendering with parallel frame processing support
- **Flexible sampling**: Render every Nth tick to reduce video size
- **Tick range filtering**: Render only specific tick ranges
- **Text overlays**: Add tick number, timestamp, or run ID to video
- **Multiple formats**: Support for MKV, MP4, AVI, MOV, WebM
- **Quality presets**: Control encoding speed/quality tradeoff
- **Progress tracking**: Real-time progress bar with ETA, throughput, and remaining time

**Basic Parameters:**
- `--run-id, -r`: Simulation run ID (required, or auto-discover latest run)
- `--out, -o`: Output filename (required, default: simulation.mkv)
- `--fps`: Frames per second for output video (default: 60)
- `--sampling-interval`: Render every Nth tick (default: 1)
- `--cell-size`: Size of each cell in pixels (default: 4)
- `--storage`: Storage resource name (default: tick-storage)

**Advanced Options:**
- `--start-tick`: Start rendering from this tick (inclusive)
- `--end-tick`: Stop rendering at this tick (inclusive)
- `--preset`: ffmpeg encoding preset: ultrafast/fast/medium/slow (default: fast)
- `--format`: Output video format: mkv/mp4/avi/mov/webm (default: mkv)
- `--threads`: Number of threads for parallel rendering (default: 1)

**Overlay Options:**
- `--overlay-tick`: Show tick number overlay
- `--overlay-time`: Show timestamp overlay
- `--overlay-run-id`: Show run ID overlay
- `--overlay-position`: Overlay position: top-left/top-right/bottom-left/bottom-right (default: top-left)
- `--overlay-font-size`: Overlay font size in pixels (default: 24)
- `--overlay-color`: Overlay text color (e.g., white, yellow, #FF0000) (default: white)

**Other Options:**
- `--verbose`: Show detailed debug output from ffmpeg

**Note**: The `video` command requires ffmpeg to be installed and available in your PATH. For a complete list of all options and their descriptions, run `bin/evochora help video`.

---

## Clean Up Simulation Runs

The `cleanup` command removes old simulation runs from storage, database, and Artemis topics. It uses glob patterns to specify which runs to keep or delete.

```bash
# Preview what would be deleted (dry-run, default)
bin/evochora cleanup --keep "20260117*"

# Actually delete (requires --force)
bin/evochora cleanup --keep "20260117*" --force

# Delete runs matching a pattern (keep all others)
bin/evochora cleanup --delete "20260122*"

# Target specific areas only
bin/evochora cleanup --keep "20260117*" --storage
bin/evochora cleanup --keep "20260117*" --database --topics
```

**Parameters:**

| Parameter | Description |
|-----------|-------------|
| `--keep <pattern>` | Glob pattern for runs to KEEP (delete all non-matching runs) |
| `--delete <pattern>` | Glob pattern for runs to DELETE (keep all non-matching runs) |
| `--storage` | Target storage directories only |
| `--database` | Target database schemas only |
| `--topics` | Target Artemis topics/queues only |
| `--force` | Execute deletion (default: dry-run preview only) |

**Notes:**
- `--keep` and `--delete` are mutually exclusive (use one or the other)
- If no area flags are specified, all three areas are targeted
- The default mode is **dry-run** - nothing is deleted without `--force`
- Pattern uses glob syntax: `*` matches any characters, `?` matches single character

**Example output (dry-run):**
```
=== Cleanup Preview (dry-run mode, use --force to execute) ===
Pattern: KEEP runs matching "20260117*"

Storage (/home/user/evochora/data/storage):
  ✓ KEEP   20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb
  ✗ DELETE 20260122-23234622-42ca3fea-4793-45bd-8dc8-0453cfa2319d
  ✗ DELETE 20260123-00013250-d6c3c75a-3129-4dde-ab77-4aad8af9c207
  ...

Database (jdbc:h2:.../indexdb):
  ✓ KEEP   SIM_20260117_22042059_D59177FC_1EBA_4CE8_85A6_36BD2032E3CB
  ✗ DELETE SIM_20260122_23234622_42CA3FEA_4793_45BD_8DC8_0453CFA2319D
  ...

Topics (/home/user/evochora/data/topic):
  ✓ KEEP   batch-topic_20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb
  ✗ DELETE batch-topic_20260122-23234622-42ca3fea-4793-45bd-8dc8-0453cfa2319d
  ...

=== Summary ===
Storage:  1 kept, 93 to delete
Database: 1 kept, 93 to delete
Topics:   14 kept, 190 to delete

Run with --force to execute deletion.
```

---

## Configuration

### Custom Configuration File

You can specify a custom configuration file using the `--config` parameter:

```bash
bin/evochora --config my-config.conf node run
```

**Important notes:**

- The `--config` parameter must come **before** the command (e.g., before `node run`).
- If the file is not found, the CLI will log an ERROR and exit.
- If the file has invalid syntax, the CLI will log an ERROR with details and exit.
- Default configuration file: `config/evochora.conf`

### Configuration File Format

The configuration file uses HOCON format (`.conf` extension). See `config/evochora.conf` for the experiment template and `src/main/resources/reference.conf` for all defaults.

## Running with JAR (Developers)

You can also build a standalone JAR and run it without the start script or Gradle. This is mainly useful for development and integration scenarios:

```bash
# Build the JAR
./gradlew jar

# Run the node
java -jar build/libs/evochora.jar node run

# Run with custom config
java -jar build/libs/evochora.jar --config my-config.conf node run

# Compile EvoASM
java -jar build/libs/evochora.jar compile --file=assembly/examples/simple.evo

# Inspect storage data
java -jar build/libs/evochora.jar inspect storage --tick=1000 --run-id=my-simulation-run

# Render video
java -jar build/libs/evochora.jar video --run-id my-simulation-run --out simulation.mp4
```

## Exit Codes

- `0` - Success
- `1` - Command error (invalid arguments, compilation error, etc.)
- `2` - System error (file not found, configuration error, etc.)

## Troubleshooting

### Node doesn't start
- Check that the configuration file exists and is valid.
- Check that required ports (e.g., 8081 for HTTP API) are not already in use.
- Check the logs for error messages.

### Compilation fails
- Verify the EvoASM source file exists.
- Check for syntax errors in the assembly code
- See `ASSEMBLY_SPEC.md` for assembly language documentation

### Configuration errors
- Verify the configuration file uses valid HOCON syntax
- Check that all required configuration keys are present
- See `config/evochora.conf` for the experiment template and `src/main/resources/reference.conf` for all defaults

### Inspect storage fails
- Verify the simulation run ID exists in storage
- Check that the specified tick number exists in the batch files
- Ensure the storage resource is properly configured
- Use `--format=summary` for human-readable output or `--format=json` for detailed inspection
