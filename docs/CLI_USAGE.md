# Evochora CLI Usage Guide

## Overview
The Evochora CLI provides the main entry point for running the simulation node and compiling assembly files.

## Available Commands

### Show Help
```bash
# Show all available commands
./gradlew run --args="--help"

# Show help for specific command
./gradlew run --args="help node"
./gradlew run --args="help compile"
./gradlew run --args="help video"
./gradlew run --args="help inspect"

# Show help for inspect storage subcommand
./gradlew run --args="inspect storage --help"
```

### Start Simulation Node
```bash
# Start the node with default configuration
./gradlew run --args="node run"

# Start with custom configuration file
./gradlew run --args="--config my-config.conf node run"
```

The node will:
- Load the configuration from the specified file (or `evochora.conf` by default)
- Start all configured services (simulation engine, persistence, indexers, etc.)
- Expose the HTTP API for monitoring and control
- Run until interrupted (Ctrl+C)

### Compile Assembly Files
```bash
# Basic compilation
./gradlew run --args="compile --file=assembly/examples/simple.evo"

# With custom environment
./gradlew run --args="compile --file=assembly/examples/simple.evo --env=200x200:flat"
```

See `ASSEMBLY_COMPILE_USAGE.md` for detailed compilation documentation.

### Inspect Storage Data
```bash
# Inspect tick data from storage (summary format)
./gradlew run --args="inspect storage --tick=1000 --run=my-simulation-run"

# Inspect with JSON output format
./gradlew run --args="inspect storage --tick=1000 --run=my-simulation-run --format=json"

# Inspect with raw protobuf output
./gradlew run --args="inspect storage --tick=1000 --run=my-simulation-run --format=raw"

# Use custom storage resource
./gradlew run --args="inspect storage --tick=1000 --run=my-simulation-run --storage=custom-storage"
```

The `inspect storage` command allows you to examine tick data that has been persisted to storage for debugging purposes. It supports three output formats:

- **summary** (default): Human-readable summary with key information
- **json**: Complete tick data in JSON format
- **raw**: Raw protobuf message output

**Parameters:**
- `--tick, -t`: Tick number to inspect (required)
- `--run, -r`: Simulation run ID (required)
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

```bash
# Basic video rendering from a simulation run
./gradlew run --args="video --run-id <run-id> --out simulation.mp4"

# Render with custom frame rate and sampling
./gradlew run --args="video --run-id <run-id> --out simulation.mp4 --fps 30 --sampling-interval 100"

# Render specific tick range
./gradlew run --args="video --run-id <run-id> --out simulation.mp4 --start-tick 1000 --end-tick 5000"

# Render with overlay (tick number, timestamp, run ID)
./gradlew run --args="video --run-id <run-id> --out simulation.mp4 --overlay-tick --overlay-time --overlay-run-id"

# Render with custom quality and format
./gradlew run --args="video --run-id <run-id> --out simulation.webm --format webm --preset fast"

# Use parallel rendering for better performance
./gradlew run --args="video --run-id <run-id> --out simulation.mp4 --threads 4"

# See all available options
./gradlew run --args="help video"
```

The `video` command renders simulation data into video files using ffmpeg. It supports various output formats (MP4, AVI, MOV, WebM), quality presets, frame rate control, and optional text overlays.

**Key Features:**
- **Performance optimized**: Uses efficient rendering with parallel frame processing support
- **Flexible sampling**: Render every Nth tick to reduce video size
- **Tick range filtering**: Render only specific tick ranges
- **Text overlays**: Add tick number, timestamp, or run ID to video
- **Multiple formats**: Support for MP4, AVI, MOV, WebM
- **Quality presets**: Control encoding speed/quality tradeoff
- **Progress tracking**: Real-time progress bar with ETA, throughput, and remaining time

**Basic Parameters:**
- `--run-id, -r`: Simulation run ID (required, or auto-discover latest run)
- `--out, -o`: Output filename (required, default: simulation.mp4)
- `--fps`: Frames per second for output video (default: 60)
- `--sampling-interval`: Render every Nth tick (default: 1)
- `--cell-size`: Size of each cell in pixels (default: 4)
- `--storage`: Storage resource name (default: tick-storage)

**Advanced Options:**
- `--start-tick`: Start rendering from this tick (inclusive)
- `--end-tick`: Stop rendering at this tick (inclusive)
- `--preset`: ffmpeg encoding preset: ultrafast/fast/medium/slow (default: fast)
- `--format`: Output video format: mp4/avi/mov/webm (default: mp4)
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

**Note**: The `video` command requires ffmpeg to be installed and available in your PATH. For a complete list of all options and their descriptions, run `./gradlew run --args="help video"`.

## Configuration

### Custom Configuration File
You can specify a custom configuration file using the `--config` parameter:

```bash
./gradlew run --args="--config my-config.conf node run"
```

**Important notes:**
- The `--config` parameter must come **before** the command (e.g., before `node run`)
- If the file is not found, the CLI will log an ERROR and use fallback configuration
- If the file has invalid syntax, the CLI will log an ERROR with details and use fallback configuration
- Default configuration file: `evochora.conf` in the project root

### Configuration File Format
The configuration file uses HOCON format (`.conf` extension). See `evochora.conf` for a complete example.

## HTTP API for Pipeline Control

When the node is running, it exposes a REST API for controlling and monitoring the data pipeline.

### Pipeline-wide Control
- `GET /api/pipeline/status` - Get overall pipeline status
- `POST /api/pipeline/start` - Start all services
- `POST /api/pipeline/stop` - Stop all services
- `POST /api/pipeline/restart` - Restart all services
- `POST /api/pipeline/pause` - Pause all services
- `POST /api/pipeline/resume` - Resume all services

### Individual Service Control
- `GET /api/pipeline/service/{serviceName}/status` - Get service status
- `POST /api/pipeline/service/{serviceName}/start` - Start specific service
- `POST /api/pipeline/service/{serviceName}/stop` - Stop specific service
- `POST /api/pipeline/service/{serviceName}/restart` - Restart specific service
- `POST /api/pipeline/service/{serviceName}/pause` - Pause specific service
- `POST /api/pipeline/service/{serviceName}/resume` - Resume specific service

### Example API Usage
```bash
# Get pipeline status
curl http://localhost:8080/api/pipeline/status

# Start all services
curl -X POST http://localhost:8080/api/pipeline/start

# Pause specific service
curl -X POST http://localhost:8080/api/pipeline/service/simulation/pause

# Get specific service status
curl http://localhost:8080/api/pipeline/service/simulation/status
```

## Running with JAR

You can also build a standalone JAR and run it without Gradle:

```bash
# Build the JAR
./gradlew jar

# Run the node
java -jar build/libs/evochora.jar node run

# Run with custom config
java -jar build/libs/evochora.jar --config my-config.conf node run

# Compile assembly
java -jar build/libs/evochora.jar compile --file=assembly/examples/simple.evo

# Inspect storage data
java -jar build/libs/evochora.jar inspect storage --tick=1000 --run=my-simulation-run

# Render video
java -jar build/libs/evochora.jar video --run-id my-simulation-run --out simulation.mp4
```

## Exit Codes

- `0` - Success
- `1` - Command error (invalid arguments, compilation error, etc.)
- `2` - System error (file not found, configuration error, etc.)

## Troubleshooting

### Node doesn't start
- Check that the configuration file exists and is valid
- Check that required ports (e.g., 8080 for HTTP API) are not already in use
- Check the logs for error messages

### Compilation fails
- Verify the assembly file exists
- Check for syntax errors in the assembly code
- See `ASSEMBLY_SPEC.md` for assembly language documentation

### Configuration errors
- Verify the configuration file uses valid HOCON syntax
- Check that all required configuration keys are present
- See `evochora.conf` for a complete configuration example

### Inspect storage fails
- Verify the simulation run ID exists in storage
- Check that the specified tick number exists in the batch files
- Ensure the storage resource is properly configured
- Use `--format=summary` for human-readable output or `--format=json` for detailed inspection
