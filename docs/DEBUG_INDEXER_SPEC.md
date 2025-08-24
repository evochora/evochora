# Specification: DebugIndexer

This document describes the architecture, responsibilities, and detailed processing logic of the `DebugIndexer` component within the Evochora simulation pipeline.

## 1. Core Principles

* **Purpose**: The `DebugIndexer` is an offline component that reads raw, performantly-written simulation data (`..._raw.sqlite`) and transforms it into a richly annotated, display-ready format (`..._debug.sqlite`) for the web debugger.
* **Architecture**: The indexer runs as a standalone process, parallel to the simulation (implemented as a separate thread in the local setup). It completely decouples computationally expensive data preparation from the "hot path" of the simulation.
* **Robustness**: The indexer is designed to handle incomplete or inconsistent data (e.g., missing `ProgramArtifacts` for forked organisms or runtime-modified machine code). It must not crash under these circumstances but should instead deliver as much information as possible, clearly marking any issues in the resulting dataset.

---

## 2. Startup Behavior and State Management

The indexer is designed to be stoppable and resumable at any time.

* **Default Mode (e.g., `run index` command):**
    1.  The indexer scans the `runs/` directory for the **latest** `..._raw.sqlite` file based on timestamp.
    2.  It derives the target database name by replacing `_raw` with `_debug` (e.g., `sim_run_..._debug.sqlite`).
    3.  It checks if this target database already exists.
        * **If YES**: It connects, queries for the highest processed tick number (`SELECT MAX(tick_number) FROM prepared_ticks`), and resumes processing the `raw` database from the next tick onwards.
        * **If NO**: It creates the `debug` database from scratch and starts processing from tick 0.

* **Manual Mode (e.g., `run index <path_to_raw_file>` command):**
    1.  The indexer is given a path to a **specific** `..._raw.sqlite` file.
    2.  It then follows the exact same logic as in steps 2 and 3 of the Default Mode, but relative to the specified file.

---

## 3. Transformation Logic: `RawTickState` → `PreparedTickState`

The core of the indexer is the transformation of raw data.

### 3.1. `worldState` (Visual World Data)

* **`cells`**: For each `RawCellState`, a `PreparedTickState.Cell` is created. This includes decoding the raw `molecule` integer into its `type` (String, e.g., `"CODE"`) and `value`. For `CODE` molecules, the opcode name is looked up via the static ISA table in `org.evochora.runtime.isa.Instruction`.
* **`organisms`**: For each non-dead `RawOrganismState`, a `PreparedTickState.OrganismBasic` is created containing the essential visual data (`id`, `programId`, `position`, `energy`, etc.).

### 3.2. `organismDetails` (Sidebar Data)

Detailed information is prepared for each organism:

* **`basicInfo`**: Directly populated from the metadata in `RawOrganismState`.

* **`nextInstruction`**:
    * **Display**: **Always** shows canonical register names (e.g., `%DR0`) and never aliases, ensuring it is 100% robust and independent of any `ProgramArtifact`.
    * **Logic**:
        1.  Attempts to load the corresponding `ProgramArtifact` via the `programId`.
        2.  **With Artifact**: Performs a full disassembly using `RuntimeDisassembler`. `runtimeStatus` is set to `"OK"`.
        3.  **Without Artifact**: Performs a best-effort disassembly. Opcodes are looked up via the static ISA, but operands are shown as raw values (e.g., `[10|5]` instead of a label name). `runtimeStatus` is set to `"CODE_UNAVAILABLE"`.
        4.  **Compiler-Generated Code**: If the instruction's address in the `sourceMap` points to a source line that is also the source of another instruction (e.g., a `PUSH` for a `CALL`), `runtimeStatus` is set to `"COMPILER_GENERATED"`.

* **`internalState`**:
    * **Register Display**: **Always** shows the state of physical registers (`%DR0`, `%PR1`, `%LR0`, etc.) and their values, without aliases.
    * **Stacks (`dataStack`, `locationStack`):** Shows the raw, formatted values of the stacks.
    * **`callStack`**:
        * **Without Artifact**: Shows only the raw VM data (e.g., procedure names, return addresses).
        * **With Artifact**: Shows the fully resolved signature, including the full parameter binding chain: `PROCNAME [x|y] WITH PARAM1[%DR0=D:42]`. The logic must correctly resolve chains (`FPR` -> `FPR` -> `DR`) across nested calls.

### 3.3. `sourceView` (Source Code View)

* **Condition**: This view is **always** generated if a `ProgramArtifact` for the organism is available in the `raw.sqlite` database.
* **Logic**: The complex logic for creating annotations is encapsulated in a dedicated `SourceAnnotator` component.

---

## 4. Specification: `SourceAnnotator` & Annotations

### 4.1. Data Structures for Annotations

Annotations are communicated to the frontend via a robust, token-based structure.

* **`PreparedTickState.InlineSpan`:**
    ```java
    public record InlineSpan(
        int lineNumber,
        String tokenToAnnotate, // The exact token to be annotated (e.g., "%COUNTER")
        int occurrence,         // The 1-based occurrence of that token in the line
        String annotationText,  // The text to display (e.g., "[D:42]")
        String kind             // The type of annotation (e.g., "reg", "jump")
    ) {}
    ```

* **`PreparedTickState.SourceLine`:**
    ```java
    public record SourceLine(
        int number,
        String content,
        boolean isCurrent,
        List<String> injectedProlog, // Disassembled code executed BEFORE this source line
        List<String> injectedEpilog   // Disassembled code executed AFTER this source line
    ) {}
    ```

### 4.2. Logic for Generating Annotations

* **Labels and Procedure Names**: The `SourceAnnotator` annotates all labels and procedure names in `CALL` instructions with their absolute target coordinates.
    * `annotationText`: `"[x|y]"`
    * `kind`: `"jump"`

* **Register Aliases and Procedure Parameters**: The annotator resolves all aliases and parameters down to the final physical register (`%DRx`, `%LRx`) and its current value.
    * `annotationText`: `[%DR0=D:42]`
    * `kind`: `"reg"`

* **Compiler-Generated Code**:
    1.  The indexer identifies all machine instructions that map to the same source line via the `sourceMap`.
    2.  It identifies the primary instruction (e.g., the `CALL`).
    3.  Instructions that occur **before** the primary instruction in the linear address space are disassembled and added to the `injectedProlog` list of the `SourceLine`.
    4.  Instructions that occur **after** are added to the `injectedEpilog` list.
    5.  If a generated instruction is currently being executed, the corresponding `SourceLine` is marked as `isCurrent=true`.

---

## 5. Data Formatting

* **Type Names**: The `DebugIndexer` **always** writes the full, unabbreviated type names (`"DATA"`, `"CODE"`, `"STRUCTURE"`, `"ENERGY"`) into the `debug.sqlite` database.
* **Frontend Responsibility**: The shortening of these names to single letters (`"D"`, `"C"`, `"S"`, `"E"`) is the sole responsibility of the presentation layer (the web debugger's JavaScript code).