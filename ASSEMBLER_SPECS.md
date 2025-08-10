# Evochora Assembler Specification

This document describes the syntax, directives, and the complete instruction set for programming organisms in the Evochora simulation.

### 1. Basic Syntax

* **Comments**: Any character after a hash (`#`) is treated as a comment and ignored by the assembler.

* **Labels**: A label is defined by a name followed by a colon (e.g., `MY_LABEL:`). Label names are case-insensitive and cannot be the same as instruction names.

* **Case-insensitivity**: Instructions, directives, and register names are case-insensitive.

### 2. Organism State (CPU & Stacks)

* **IP (Instruction Pointer):** Absolute world coordinate of the next instruction.

* **DP (Data Pointer):** Absolute world coordinate for memory access (PEEK/POKE/SCAN).

* **DV (Direction Vector):** Direction of movement for IP.

* **ER (Energy Register):** Life energy.

* **DRs (Data Registers):** 8 general-purpose registers (`%DR0`–`%DR7`) for global data storage.

* **PRs (PROC Registers):** 2 procedure-local temporary registers (`%PR0`, `%PR1`). Their values are automatically saved on `CALL` and restored on `RET`, making them safe for use within any procedure without interfering with the caller.

* **FPRs (Formal Parameter Registers):** 8 internal, unnamed registers used exclusively for parameter passing with `.PROC ... WITH`. They cannot be accessed directly by name but hold the values of formal parameters during a procedure call.

* **DS (Data Stack):** LIFO memory for temporary data. Limit: `DS_MAX_DEPTH`.

* **RS (Return Stack):** Used by `CALL`/`RET` to store return addresses and save/restore PRs and FPRs. Limit: `RS_MAX_DEPTH`.

### 3. Argument Types

* **Register:** `%DR0`…`%DR7`, `%PR0`…`%PR1`.

* **Literal:** `TYPE:VALUE` (e.g., `DATA:123`).

* **Vector:** `X|Y` (e.g., `1|0`); dimension equals the world dimensions.

* **Label:** A defined label name.

### 4. Instruction Set

#### Data & Memory

* `SETI %REG_TARGET LITERAL`: Set a register to a literal value.

* `SETR %REG_TARGET %REG_SOURCE`: Copy a register value. Supports DRs and PRs.

* `SETV %REG_TARGET VECTOR|LABEL`: Set a register to a vector value.

* `PUSH %REG_SOURCE`: Push a register value onto DS.

* `POP %REG_TARGET`: Pop a value from DS into a register.

* `PUSI LITERAL`: Push a literal value onto DS.

#### Stack Operations (DS only)

* `DUP`: Duplicate TOS. DS: \[..., A\] → \[..., A, A\].

* `SWAP`: Swap top two elements. DS: \[..., A, B\] → \[..., B, A\].

* `DROP`: Remove TOS. DS: \[..., A\] → \[...\].

* `ROT`: Rotate top three. DS: \[..., A, B, C\] → \[..., B, C, A\].

#### Arithmetic & Logic

* **Register/Immediate variants:** `ADDR`, `SUBR`, `ADDI`, `SUBI`, `MULR`, `MULI`, `DIVR`, `DIVI`, `MODR`, `MODI`.

* **Stack (S-) variants:** `ADDS`, `SUBS`, `MULS`, `DIVS`, `MODS`.

#### Bitwise Operations

* **Register/Immediate variants:** `NADR`, `NADI`, `ANDR`, `ANDI`, `ORR`, `ORI`, `XORR`, `XORI`, `NOT`.

* **Stack (S-) variants:** `NADS`, `ANDS`, `ORS`, `XORS`, `NOTS`.

#### Shifts

* **Register/Immediate variants:** `SHLI %R DATA:N`, `SHRI %R DATA:N`.

* **Stack (S-) variants:** `SHLS`, `SHRS`.

#### Control Flow (Jumps & Calls)

* `JMPI LABEL`: Jump to label (relative).

* `JMPR %REG_ADDR`: Jump to program-relative address in a register.

* `CALL LABEL`: Call subroutine. `CALL` pushes return info on RS; callee returns with `RET`.

* `RET`: Return from subroutine. Pops return info from RS and restores PRs and FPRs.

#### Conditional Statements

* `IFR %A %B`: If `A == B`.

* `GTR %A %B`: If `A > B` (scalar).

* `LTR %A %B`: If `A < B` (scalar).

* `IFI %R LIT`: If `R == LITERAL`.

* `GTI %R LIT`: If `R > LITERAL` (scalar).

* `LTI %R LIT`: If `R < LITERAL` (scalar).

* `IFTR %A %B`: If `type(A) == type(B)`.

* `IFTI %R LIT`: If `type(R) == type(LITERAL)`.

#### World Interaction & Organism State

* `PEEK %REG_TARGET %REG_VECTOR`: Read symbol at `DP + vector`, clear the cell.

* `PEKI %REG_TARGET X|Y`: Immediate-vector PEEK.

* `POKE %REG_SOURCE %REG_VECTOR`: Write symbol at `DP + vector`.

* `POKI %REG_SOURCE X|Y`: Immediate-vector POKE.

* `SCAN %REG_TARGET %REG_VECTOR`: Read symbol at `DP + vector` without clearing.

* `SCNI %REG_TARGET X|Y`: SCAN with immediate vector.

* `SCNS`: Stack-vector SCAN. Pop vec, push scanned symbol.

* `SEEK %REG_VECTOR`: Move DP by register vector.

* `SEKI X|Y`: Move DP by immediate vector.

* `SEKS`: Stack-vector SEEK. Pop vec; DP += vec.

* `SYNC`: `DP = IP`.

* `TURN %REG_VECTOR`: `DV = vector`.

* `POS %REG_TARGET`: Write program-relative coordinate of IP to the register.

* `DIFF %REG_TARGET`: Write `DP - IP` to the register.

* `NRG %REG_TARGET`: Write energy (ER) to the register.

* `RAND %REG`: `REG = random(0..REG-1)`.

### 5. Directives (Detailed)

This section describes all assembler directives with syntax, semantics, and examples.

#### .PROC … .ENDP – Procedures

Procedures are reusable blocks of code that can be called from anywhere.

* **Syntax (with parameters):**

  ```
  .PROC LIB.NAME WITH PARAM1 [PARAM2 ...]
      .EXPORT LIB.NAME
      [.PREG %ALIAS 0|1]
      # Body uses PARAM1, PARAM2 as if they were registers.
      # %PR0, %PR1 can be used as temporary registers.
  .ENDP
  
  ```

* **Syntax (without parameters):**

  ```
  .PROC LIB.NAME
      # Body (typically uses the Data Stack for parameters)
  .ENDP
  
  ```

* **Semantics of `.WITH`:**

    * `WITH` declares **formal parameters** (`PARAM1`, `PARAM2`, etc.). Inside the procedure, these names are used just like registers.

    * Calling is done via `CALL LIB.NAME .WITH %ACTUAL_REG1 %ACTUAL_REG2`.

    * The assembler implements this using a **Copy-In/Copy-Out** mechanism:

        1. **Copy-In:** Before the `CALL`, the value from each actual register (e.g., `%ACTUAL_REG1`) is copied into a dedicated, internal Formal Parameter Register (FPR).

        2. **Execution:** The procedure executes, operating on these internal FPRs.

        3. **Copy-Out:** After the procedure returns (`RET`), the final value from each FPR is copied back into the corresponding actual register.

    * This ensures that changes made to parameters inside the procedure are reflected in the caller's registers, simulating **Call-by-Reference** behavior safely.

* **`.PREG %ALIAS 0|1`:**

    * Creates a local alias for the procedure-local registers `%PR0` or `%PR1`. These registers are ideal for temporary calculations as their values are automatically preserved across `CALL`/`RET` boundaries.

#### CALL … .WITH – Calling a Procedure with Parameters

* **Syntax:** `CALL TARGET .WITH %ACTUAL_REG1 [%ACTUAL_REG2 ...]`

    * `TARGET`: The label of the procedure (or an import alias).

    * `%ACTUAL_REG1`: The caller's register (DR or PR) passed as the first argument.

* The number of actual registers in the `CALL` must exactly match the number of formal parameters in the `.PROC` definition.

* **Example:**

  ```
  # Procedure definition
  .PROC UTIL.INCREMENT WITH VAL
      .EXPORT UTIL.INCREMENT
      ADDI VAL DATA:1
      RET  # RET is optional, it's added implicitly at .ENDP
  .ENDP
  
  # Main program
  .IMPORT UTIL.INCREMENT AS INC
  .REG %MY_COUNTER 3
  
  SETI %MY_COUNTER DATA:9
  CALL INC .WITH %MY_COUNTER
  # After this, %MY_COUNTER will hold the value 10.
  
  ```

#### Other Directives

* **.ORG X|Y:** Sets the origin for placing the next instruction.

* **.DIR X|Y:** Sets the assembly direction for instruction arguments.

* **.REG %NAME ID:** Binds a symbolic name to a data register (`%DR0`–`%DR7`).

* **.DEFINE NAME VALUE:** Replaces `NAME` with `VALUE` during assembly.

* **.EXPORT LIB.NAME:** Inside a `.PROC`, marks it as publicly importable.

* **.IMPORT LIB.NAME AS ALIAS:** Makes a public procedure available under a local alias.

* **.PLACE TYPE:VALUE X|Y:** Places an initial symbol in the world.

* **.MACRO / .ROUTINE:** Advanced templating features (see full documentation for details).
