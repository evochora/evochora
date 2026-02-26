# Local State Proposal

This document specifies the `.STATE`, `.LOAD`, and `.STORE` directives for managing persistent module-level data. These directives replace the manual pattern of `.ORG` + `.PLACE` + exported labels + `STATIC_LOAD`/`STATIC_STORE` macros.

This proposal builds on the [Module System](MODULE_SYSTEM.md) and is designed to work within its namespace and visibility rules.

## Motivation

Currently, persistent state in modules is managed manually:

```assembly
# Old pattern (manual)
.ORG 0|2
HARVEST_STATE: EXPORT
.PLACE DATA:1  1|2
.PLACE DATA:0  2|2
.PLACE DATA:89 3|2

.PROC HARVEST EXPORT
  STATIC3_LOAD HARVEST_STATE %PR0 %PR1 %PR2
  # ... logic ...
  STATIC3_STORE HARVEST_STATE %PR0 %PR1 %PR2
  RET
.ENDP
```

Problems with this approach:
- The programmer must manually manage grid positions with `.ORG` and `.PLACE`.
- The `STATIC_LOAD`/`STATIC_STORE` macros encode the field count in their name (e.g., `STATIC3_LOAD`).
- State labels must be exported for external initialization, breaking encapsulation.
- The DP save/restore boilerplate is error-prone.

## Directives

### `.STATE` / `.ENDS` — Declare a state block

Declares a named block of persistent data fields. The compiler places these as consecutive molecules on the grid and generates a label for the block.

```assembly
.STATE HARVEST_STATE
  FWD_MASK DATA:1
  KIDX DATA:0
  KLEFT DATA:89
.ENDS
```

This generates:
- A label `HARVEST_STATE` pointing to the first field.
- Three consecutive `DATA` molecules on the grid with initial values 1, 0, 89.

State blocks are module-level declarations (not inside procedures). They follow the same visibility rules as labels — private by default, exportable with `EXPORT`:

```assembly
.STATE HARVEST_STATE EXPORT
  FWD_MASK DATA:1
.ENDS
```

### `.LOAD` — Load state into registers

Loads all fields of a state block into the specified registers. The compiler generates the necessary DP manipulation: save DP, jump to the state label, read each field sequentially into the registers, restore DP.

```assembly
.LOAD HARVEST_STATE %PR0 %PR1 %PR2
```

The compiler validates that the number of registers matches the number of fields in the state block. A mismatch is a compiler error.

### `.STORE` — Store registers into state

Stores the specified register values back into the state block fields. Same DP manipulation as `.LOAD`, but writing instead of reading.

```assembly
.STORE HARVEST_STATE %PR0 %PR1 %PR2
```

Same validation: register count must match field count.

## Generated Code

For a state block with 3 fields, `.LOAD HARVEST_STATE %PR0 %PR1 %PR2` generates approximately:

```assembly
# Save DP
DPLS
# Jump DP to state location
SYNC
# ... DP navigation to HARVEST_STATE label ...
# Read fields sequentially
SCNI %PR0 1|0    # Read field 0
SEKI 1|0          # Advance
SCNI %PR1 1|0    # Read field 1
SEKI 1|0          # Advance
SCNI %PR2 1|0    # Read field 2
# Restore DP
SKLS
```

The exact generated code depends on the grid layout and the state block's position relative to the current code. This is an implementation detail that may evolve.

## Interaction with the Module System

- State blocks participate in the module's namespace. Fields are accessible by name within the module.
- Exported state blocks can be referenced from importing modules via qualified names (e.g., `E.HARVEST_STATE`).
- Each `.IMPORT` creates an independent copy of the state block, ensuring instance isolation.
- State initialization at runtime is done via an `INIT` procedure — no special constructor syntax.

## Example

```assembly
# energy.evo
.STATE HARVEST_STATE
  FWD_MASK DATA:1
  KIDX DATA:0
  KLEFT DATA:89
.ENDS

.PROC INIT EXPORT REF FWD KIDX KLEFT
  .STORE HARVEST_STATE FWD KIDX KLEFT
  RET
.ENDP

.PROC HARVEST EXPORT
  .LOAD HARVEST_STATE %PR0 %PR1 %PR2

  # ... harvesting logic using %PR0, %PR1, %PR2 ...

  .STORE HARVEST_STATE %PR0 %PR1 %PR2
  RET
.ENDP
```

```assembly
# main.evo
.IMPORT "./energy.evo" AS E

START:
  # Optional: initialize with custom values
  SETI %DR0 DATA:4
  SETI %DR1 DATA:0
  SETI %DR2 DATA:101
  CALL E.INIT REF %DR0 %DR1 %DR2

MAIN_LOOP:
  CALL E.HARVEST
  JMPI MAIN_LOOP
```
