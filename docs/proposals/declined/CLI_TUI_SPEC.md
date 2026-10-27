# Specification: TUI Control for the Data Pipeline

## 1. Overview

The existing Command Line Interface (CLI) will be replaced by an interactive Terminal User Interface (TUI) to provide real-time visual monitoring and control of the entire data pipeline. The TUI is operated exclusively via the keyboard and features two primary modes: **Control Mode** for managing services and **Log Mode** for analyzing log output.

## 2. Visual Layout

The layout is divided into two main sections: an upper panel for control and a lower panel for log output. A dynamic footer displays the currently available commands. The active focus is visualized with a highlighted border.

### State 1: Focus on Control (Default)

The focus is on the upper panel for selecting and controlling services.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Evochora Pipeline Control                                                        │
├──────────────────────────────────────────────────────────────────────────────────┤
╔══════════════════════════════════════════════════════════════════════════════════╗
║ SERVICES            │ STATUS    │ DETAILS                                        ║
╟──────────────────────────────────────────────────────────────────────────────────╢
║ > Simulation        │ RUNNING   │ Tick: 1024 | TPS: 45.1 | Orgs: 5/2 | Queue: 12 ║
║   Indexer           │ RUNNING   │ Last Indexed: 1020 | Lag: 4                    ║
║   Server            │ OFFLINE   │ Status: Offline | Port: 7070 | Requests: 0     ║
╚══════════════════════════════════════════════════════════════════════════════════╝
├──────────────────────────────────────────────────────────────────────────────────┤
│ LOGS         |  sqlite_raw.... > indexer > sqlite_debug....                      │
├──────────────────────────────────────────────────────────────────────────────────┤
│ [INFO] Simulation tick 1023 persisted.                                           │
│ [INFO] Indexed tick 1018.                                                        │
│ ...                                                                              │
├──────────────────────────────────────────────────────────────────────────────────┤
│ (↑/↓) Navigate | (Enter) Run/Pause | (r) Reset | (Tab) Logs | (q) Quit           │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### State 2: Focus on Logs

The focus is on the lower log panel. Keyboard input now controls scrolling.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Evochora Pipeline Control                                                        │
├──────────────────────────────────────────────────────────────────────────────────┤
│ SERVICES            │ STATUS    │ DETAILS                                        │
├──────────────────────────────────────────────────────────────────────────────────┤
│ > Simulation        │ RUNNING   │ Tick: 1024 | TPS: 45.1 | Orgs: 5/2 | Queue: 12 │
│   Indexer           │ RUNNING   │ Last Indexed: 1020 | Lag: 4                    │
│   Server            │ OFFLINE   │ Status: Offline | Port: 7070 | Requests: 0     │
├──────────────────────────────────────────────────────────────────────────────────┤
╔══════════════════════════════════════════════════════════════════════════════════╗
║ LOGS         |  sqlite_raw.... > indexer > sqlite_debug....                      ║
╟──────────────────────────────────────────────────────────────────────────────────╢
║ [INFO] Simulation tick 1023 persisted.                                           ║
║ [INFO] Indexed tick 1018.                                                        ║
║ ...                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════════╝
├──────────────────────────────────────────────────────────────────────────────────┤
│ (↑/↓) Scroll | (Home/End) Top/Bottom | (Tab) Control | (q) Quit                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## 3. Interaction Model

### 3.1. Focus Switching

* **`Tab`**: Toggles the focus between **Control Mode** and **Log Mode**.

### 3.2. Control Mode

This is the default mode when the TUI starts.

* **`↑ / ↓`**: Navigates through the list of services (`Simulation`, `Debug Indexer`, `Debug Server`).

* **`Enter`**: Executes the context-aware primary action for the selected service:

    * **Simulation**:

        * If status is `IDLE` or `COMPILATION_FAILED`: Starts compilation and then execution.

        * If status is `RUNNING`: Pauses the simulation.

        * If status is `PAUSED`: Resumes the simulation.

    * **Debug Indexer / Debug Server**:

        * Toggles the start/stop state of the respective service.

* **`r`** (**R**eset): Resets the selected service and any dependent services to their initial state.

* **`q`** (**Q**uit): Exits the TUI and cleanly shuts down all running services.

### 3.3. Log Mode

In this mode, scrolling within the log panel is enabled.

* **`↑ / ↓`**: Scrolls up or down by one line.

* **`PageUp / PageDown`**: Scrolls up or down by a full page.

* **`Home / End`**: Jumps to the top or bottom of the log buffer.

* **`q`** (**Q**uit): Exits the TUI (globally available).

## 4. Service States and Displays

### 4.1. Simulation

This service combines the Compiler, Engine, and Persistence stages.

**Displayed Details:**

* **`Tick`**: The last successfully persisted tick.

* **`TPS`**: Ticks Per Second (based on persisted ticks).

* **`Orgs`**: Organism count in `alive / dead` format.

* **`Queue`**: Current size of the `InMemoryTickQueue`.

**Status States:**

* `IDLE`: Stopped, waiting for action.

* `COMPILING`: The code is currently being compiled.

* `RUNNING`: The simulation is running and persisting data.

* `PAUSED`: The simulation is paused.

* `COMPILATION_FAILED`: An error occurred during compilation.

* `ERROR`: A critical runtime error occurred.

* `FINISHED`: The simulation has completed successfully.

### 4.2. Debug Indexer

**Displayed Details:**

* **`Last Indexed`**: The last tick processed by the indexer.

* **`Lag`**: The difference between the simulation tick and the `Last Indexed` tick.

**Status States:**

* `RUNNING`: Actively indexing data.

* `IDLE`: Stopped.

* `ERROR`: An error occurred during indexing.

### 4.3. Debug Server

**Displayed Details:**

* **`Status`**: `Online` or `Offline`.

* **`Port`**: The network port the server is listening on.

* **`Requests`**: The number of HTTP requests processed since startup.

**Status States:**

* `ONLINE`: The server is running and accessible.

* `OFFLINE`: The server is stopped.

* `ERROR`: An error occurred during startup or operation.
