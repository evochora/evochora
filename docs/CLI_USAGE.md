# Evochora CLI Usage Guide

## Overview
The Evochora CLI provides a simple interface to control the simulation pipeline services. The new implementation features robust auto-pause logic and clear service lifecycle management.

## Commands

### `start [service]`
- **`start`** - Start all services in the correct order (simulation → persistence → indexer → debug server)
- **`start simulation`** - Start only the simulation engine
- **`start persistence`** - Start only the persistence service
- **`start indexer`** - Start only the debug indexer
- **`start server`** - Start only the debug server

### `pause [service]`
- **`pause`** - Pause all running services
- **`pause simulation`** - Pause only the simulation engine
- **`pause persistence`** - Pause only the persistence service
- **`pause indexer`** - Pause only the debug indexer
- **`pause server`** - Stop the debug server

### `resume [service]`
- **`resume`** - Resume all paused services
- **`resume simulation`** - Resume only the simulation engine
- **`resume persistence`** - Resume only the persistence service
- **`resume indexer`** - Resume only the debug indexer
- **`resume server`** - Start the debug server

### `status`
Display the current status of all services, including:
- **started** - Service is running and processing data
- **paused** - Service is manually paused by user
- **auto-paused** - Service automatically paused due to no work available
- **stopped** - Service is not running

### `loglevel [logger] [level]`
Control logging verbosity for debugging and monitoring:

- **`loglevel`** - Show current log levels for all loggers
- **`loglevel [level]`** - Set default log level for all loggers (TRACE, DEBUG, INFO, WARN, ERROR)
- **`loglevel [logger] [level]`** - Set log level for specific logger
- **`loglevel reset`** - Reset all log levels to config.jsonc values

**Available loggers:**
- `sim` - Simulation engine
- `persist` - Persistence service  
- `indexer` - Debug indexer
- `web` - Web debug server
- `cli` - CLI interface

**Available levels:**
- `TRACE` - Most verbose, shows all operations
- `DEBUG` - Detailed debugging information
- `INFO` - General information messages
- `WARN` - Warning messages only
- `ERROR` - Error messages only

**Examples:**
```
>>> loglevel DEBUG          # Set all loggers to DEBUG
>>> loglevel sim TRACE      # Set simulation engine to TRACE
>>> loglevel indexer WARN   # Set indexer to WARN only
>>> loglevel reset          # Reset to config.jsonc values
```

### `exit` or `quit`
Gracefully shutdown all services and exit the CLI.

## Auto-Pause Logic

### How it works:
1. **Persistence Service**: Automatically pauses when no ticks are available in the queue, checks every second for new work
2. **Debug Indexer**: Automatically pauses when no new ticks are available to process, checks every second for new work
3. **Simulation Engine**: Never auto-pauses (always runs when started unless manually paused)

### Auto-pause vs Manual Pause:
- **Auto-pause**: Services automatically pause when idle and resume when work becomes available
- **Manual Pause**: Services pause and stay paused until manually resumed by the user

### Important Behavior:
- **Auto-paused services** will automatically wake up when new work becomes available
- **Manually paused services** will NOT automatically wake up, even if work is available
- **Manual pause takes precedence** over auto-pause - once manually paused, a service stays paused until explicitly resumed

### Database Handling:
- **Auto-pause**: Database connections remain open for quick resume
- **Manual Pause**: Database connections are cleanly closed with WAL checkpointing to ensure data integrity
- **Batch Completion**: When pausing (auto or manual), services complete their current batch before entering pause mode

### Data Integrity:
- **WAL Checkpointing**: When manually pausing, all pending changes in Write-Ahead Log (WAL) are checkpointed to the main database
- **SHM Cleanup**: Shared memory files are properly synchronized before closing
- **Batch Flushing**: Any incomplete batches are executed and committed before pausing
- **Full WAL Checkpoint**: `PRAGMA wal_checkpoint(FULL)` ensures all WAL changes are written to the main SQLite database
- **WAL File Closure**: Both PersistenceService and DebugIndexer properly close WAL files to prevent file locking issues
- **WAL Mode Disable**: `PRAGMA journal_mode=DELETE` ensures WAL mode is properly disabled before closing
- **Multiple Database WAL Closure**: DebugIndexer closes WAL files from both debug database and raw database connections

### Manual Pause Process:
1. **Complete Current Batch**: Service finishes processing the current batch of data
2. **Execute Pending Batches**: Any incomplete batches are executed and committed
3. **WAL Checkpoint**: `PRAGMA wal_checkpoint(FULL)` writes all WAL changes to main database
4. **WAL Mode Disable**: `PRAGMA journal_mode=DELETE` disables WAL mode to close WAL files
5. **Raw Database WAL Closure**: Close WAL files from any open raw database connections
6. **WAL File Closure**: WAL and SHM files are properly closed to release file handles
7. **Database Close**: Connection is cleanly closed with all changes safely persisted
8. **Service Paused**: Service enters paused state with data integrity guaranteed

## Service Dependencies

Services have dependencies and must be started in order:
1. **Simulation Engine** - Generates simulation ticks
2. **Persistence Service** - Requires simulation engine to be running
3. **Debug Indexer** - Requires persistence service to be running
4. **Debug Server** - Requires indexer to be running

## Example Usage

```bash
# Start the entire pipeline
>>> start

# Check status
>>> status

# Pause simulation (persistence and indexer will auto-pause when idle)
>>> pause simulation

# Resume everything
>>> resume

# Pause specific service
>>> pause indexer

# Start specific service
>>> start indexer

# Exit
>>> exit
```

## Command Line Log Level Configuration

You can also set log levels directly when starting the CLI via Gradle, which overrides the config.jsonc settings:

### System Properties for Log Levels

**Default log level for all loggers:**
```bash
./gradlew run -Dlog.level=DEBUG
./gradlew run -Dlog.level=INFO
./gradlew run -Dlog.level=WARN
./gradlew run -Dlog.level=ERROR
./gradlew run -Dlog.level=TRACE
```

**Specific logger configuration:**
```bash
# Set specific loggers to different levels (use full Java class names)
./gradlew run -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -Dlog.org.evochora.server.http.DebugServer=WARN

# Combine default and specific settings
./gradlew run -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.persistence.PersistenceService=WARN
```

**For compilation tasks:**
```bash
./gradlew compile -Pfile="assembly/test/main.s" -Dlog.level=DEBUG
```

**For JAR execution:**
```bash
# Build the CLI JAR first
./gradlew cliJar

# Run with log level configuration
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Run with specific logger configuration (use full Java class names)
java -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Compile assembly with JAR and debug logging
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile assembly/test/main.s
```

### How Command Line Override Works

1. **System Properties take precedence** over config.jsonc settings
2. **`log.level`** sets the default level for all loggers
3. **`log.<logger>`** sets specific logger levels (e.g., `log.sim`, `log.persist`, `log.indexer`, `log.web`, `log.cli`)
4. **Fallback to config.jsonc** if no System Properties are set

### Available Logger Names

**For System Properties (use full Java class names):**
- `log.org.evochora.server.engine.SimulationEngine` - Simulation engine logging
- `log.org.evochora.server.persistence.PersistenceService` - Persistence service logging  
- `log.org.evochora.server.indexer.DebugIndexer` - Debug indexer logging
- `log.org.evochora.server.http.DebugServer` - Web debug server logging
- `log.org.evochora.server.ServiceManager` - CLI interface logging

**For CLI commands (use short aliases):**
- `sim` - Simulation engine logging
- `persist` - Persistence service logging  
- `indexer` - Debug indexer logging
- `web` - Web debug server logging
- `cli` - CLI interface logging

### Example Commands

**Gradle execution:**
```bash
# Start CLI with debug logging for all services
./gradlew run -Dlog.level=DEBUG

# Start with detailed simulation logging but quiet other services
./gradlew run -Dlog.level=WARN -Dlog.org.evochora.server.engine.SimulationEngine=TRACE

# Compile assembly with debug output
./gradlew compile -Pfile="assembly/primordial/main.s" -Dlog.level=DEBUG

# Start with mixed logging levels (use full Java class names)
./gradlew run -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -Dlog.org.evochora.server.http.DebugServer=ERROR
```

**JAR execution:**
```bash
# Build the CLI JAR first
./gradlew cliJar

# Start CLI with debug logging
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Start with mixed logging levels (use full Java class names)
java -Dlog.level=INFO -Dlog.org.evochora.server.engine.SimulationEngine=DEBUG -Dlog.org.evochora.server.indexer.DebugIndexer=TRACE -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar

# Compile assembly with debug output using JAR
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile assembly/primordial/main.s

# Compile with specific environment and debug logging
java -Dlog.level=DEBUG -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile assembly/test/main.s --env=2000x2000:flat
```

## Troubleshooting

- If a service fails to start, check that its dependencies are running
- Use `status` to see the current state of all services
- Services in "auto-paused" state will automatically resume when work becomes available
- Manually paused services require explicit resume commands
- Use `loglevel` command or System Properties to increase logging verbosity for debugging
