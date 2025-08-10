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

Arguments fall into a small, well-defined type system. Many instructions only accept scalars (DATA), while others accept vectors (VEC). Type-sensitive predicates (IFTR/IFTI) compare the dynamic type, not the value.

* **Register:** `%DR0`…`%DR7`, `%PR0`…`%PR1`. These hold values (either scalar DATA or VEC) and are case-insensitive.

* **Literal:** `TYPE:VALUE`. Currently supported immediate type:
  - `DATA:N` — a signed integer scalar. Examples:
    - `DATA:0`, `DATA:-12`, `DATA:42`
    - Numeric bases: `DATA:0xFF` (hex), `DATA:0b1010` (binary), `DATA:077` (octal)
    - Separators: underscores are allowed for readability, e.g. `DATA:1_000_000`
  Notes:
  - DATA is the only scalar literal type; vector literals use the Vector syntax (below).
  - When an instruction requires a scalar and receives a non-scalar, it fails.

* **Vector (VEC):** `X|Y[|Z...]` (e.g., `1|0`, `-1|2|0`). Dimensionality must match the world’s dimension. Vectors are used for:
  - Relative addressing (e.g., `PEEK`, `SEEK`, `JMPI`, `SETV`).
  - Direction settings (e.g., `TURN` with a unit vector).
  - Stack variants that pop a vector expect a properly dimensioned VEC on the stack.

* **Label:** A defined label name (case-insensitive). When used in a vector position context (e.g., `JMPI LABEL`, `SETV %DR0 LABEL`), the assembler resolves it to the relative vector delta from the current IP at assemble time.

Type overview:
- **DATA (scalar):** signed integer payload; used by arithmetic/bitwise/shift/compare.
- **VEC (vector):** multi-component integer tuple matching world dimensions.
- Many instructions specify “scalar-only”; passing VEC where DATA is required fails.
- IFTR/IFTI compare the type tags (DATA vs VEC), not the numeric payload.

Examples:
- `ADDI %DR0 DATA:1`        ; scalar immediate
- `SETV %DR1 -1|0`          ; vector literal (2D)
- `JMPI TARGET_LABEL`       ; label resolved to a relative vector at assembly
- `PEKI %DR2 0|1|0`         ; vector in 3D world

### 4. Instruction Set

This section lists each instruction with all of its variants grouped together (Register “R”, Immediate “I”, and Stack “S”). For each instruction you’ll find syntax, operands, effects, side effects, energy cost, errors, and a short example. Unless stated otherwise:
- Register operands accept `%DRx` and `%PRx`.
- Stack variants use the Data Stack (DS).
- Vector arguments use `X|Y` (or more components if the world has higher dimensions).
- Values are encoded Symbols; arithmetic/bitwise operate on scalar payloads.

Energy model:
- All energy costs are configurable by the simulator. The “Energy:” line indicates that an instruction consumes or transfers energy according to the configured schedule.
- Some world interactions may transfer energy instead of strictly consuming it. For example, reading an ENERGY symbol can increase ER; writing symbols may include surcharges depending on the symbol being written.

Energy costs:
- Energy costs are configurable in the simulator; the values shown here are the default costs intended for balancing. When in doubt, prefer the per-instruction “Energy:” line. Typical defaults:
  - Moves/sets (SETx), stack data-moves (PUSH/POP/PUSI), arithmetic/bitwise/shifts, simple compares: 1
  - Control flow (JMPI/JMPR): 1; CALL/RET: 2
  - World reads (PEEK/SCAN): 2; DP move (SEEK): 3; World writes (POKE): 5
  - State sync/utility (SYNC, TURN, POS, DIFF): 1; Energy read (NRG/NRGS): 0; RAND: 2

#### Data & Memory

- SET family (move/set value)
  - Variants: SETI (imm), SETR (reg), SETV (vec/label)
  - Syntax:
    - SETI %REG_TARGET DATA:N
    - SETR %REG_TARGET %REG_SOURCE
    - SETV %REG_TARGET X|Y[|Z...] | LABEL
  - Operands: target register; source is an immediate DATA, a register, or a vector/label.
  - Effect:
    - SETI: REG_TARGET := DATA literal (scalar)
    - SETR: REG_TARGET := value(%REG_SOURCE) (deep copy)
    - SETV: REG_TARGET := resolved vector (from literal vector or label delta)
  - Energy: 1
  - Errors: invalid literal type/format; wrong vector dimensionality
  - Example:
    - SETI %DR0 DATA:42
    - SETR %PR0 %DR1
    - SETV %DR2 -1|0

- PUSH / POP / PUSI
  - Variants: PUSH, POP, PUSI
  - Syntax:
    - PUSH %REG_SOURCE
    - POP %REG_TARGET
    - PUSI TYPE:VALUE
  - Operands: as above
  - Effect:
    - PUSH: DS.push(value(%REG_SOURCE))
    - POP: %REG_TARGET := DS.pop()
    - PUSI: DS.push(literal)
  - Energy: configurable
  - Energy: 1
  - Errors: POP on empty stack; invalid literal for PUSI
  - Example: PUSI DATA:7

#### Stack Operations (DS only)

- DUP
  - Syntax: DUP
  - Effect: [..., A] → [..., A, A]
  - Energy: 0
  - Errors: requires at least 1 item

- SWAP
  - Syntax: SWAP
  - Effect: [..., A, B] → [..., B, A]
  - Energy: 0
  - Errors: requires at least 2 items

- DROP
  - Syntax: DROP
  - Effect: [..., A] → [...]
  - Energy: 0
  - Errors: requires at least 1 item

- ROT
  - Syntax: ROT
  - Effect: [..., A, B, C] → [..., B, C, A]
  - Energy: 0
  - Errors: requires at least 3 items

#### Arithmetic

Each arithmetic instruction comes in Register (R), Immediate (I), and Stack (S) forms:
- ADDR, ADDI, ADDS
- SUBR, SUBI, SUBS
- MULR, MULI, MULS
- DIVR, DIVI, DIVS
- MODR, MODI, MODS

General semantics:
- R: OPxR %DEST %SRC  → %DEST := %DEST OP %SRC
- I: OPxI %DEST TYPE:N → %DEST := %DEST OP N
- S: OPxS              → Pops operands (rightmost is top-of-stack) and pushes result. For binary ops: [..., A, B] → [..., (A OP B)] where B is TOS.
- Operands: scalar data (DATA type). Non-scalar leads to failure.
- Energy: configurable
- Energy: 1 per operation
- Errors: division by zero; type mismatches.
- Example: ADDI %DR0 DATA:1

#### Bitwise

Bitwise instructions exist as Register (R), Immediate (I), and Stack (S) forms; NOT also has R and S:
- NADR, NADI (bitwise NAND)
- ANDR, ANDI; ORR, ORI; XORR, XORI
- NOT (register), NOTS (stack)
- NADS, ANDS, ORS, XORS

Semantics:
- Operands treated as unsigned integer scalars on their payload.
- NOT/NOTS invert all bits of the scalar.
- Energy: configurable
- Energy: 1 per operation
- Errors: type mismatch (non-scalar).
- Example: XORI %DR1 DATA:255

#### Shifts

- SHLI, SHRI (Register/Immediate)
  - Syntax: SHLI %R DATA:N, SHRI %R DATA:N
  - Effect: logical left/right shift by N
  - Energy: 1
  - Energy: configurable
  - Errors: negative shift; non-scalar

- SHLS, SHRS (Stack)
  - Syntax: SHLS, SHRS
  - Effect: Pops N, then A; pushes (A << N) or (A >>> N)
  - Energy: configurable
  - Energy: 1
  - Errors: insufficient stack items; non-scalar

#### Control Flow (Jumps & Calls)

- JMPI (Jump by Label/Vector)
  - Syntax: JMPI LABEL
  - Effect: IP := IP + delta(LABEL)
  - Energy: 1

- JMPR (Jump by Register)
  - Syntax: JMPR %REG_ADDR
  - Effect: IP := IP + vector in register
  - Energy: 1

- CALL
  - Syntax: CALL LABEL
  - Effect: Push return info to RS, save PR/FPR, jump to target
  - Energy: 2

- RET
  - Syntax: RET
  - Effect: Pop return info, restore PR/FPR, return to caller
  - Energy: 2

Errors:
- Unbalanced RS (RET without CALL)
- Invalid label resolution at assembly time

#### Conditional Statements

- IFR, IFI, IFTR, IFTI
  - IFR %A %B: if value(%A) == value(%B) then execute next; else skip
  - IFI %R LIT: compare with literal
  - IFTR %A %B: if type(%A) == type(%B)
  - IFTI %R LIT: compare type(%R) with type(literal)

- GTR, LTR, GTI, LTI
  - GTR %A %B: if scalar(%A) > scalar(%B)
  - LTR %A %B: if scalar(%A) < scalar(%B)
  - GTI/LTI operate against literal

Energy:
- Configurable per conditional evaluation (the skipped-or-executed next instruction has its own cost, accounted separately).

Energy:
- 1 per conditional evaluation. The skipped-or-executed instruction has its own cost and is accounted separately.

Errors:
- Type mismatches for scalar comparisons
- LIT with invalid format/type

Example:
- IFI %DR0 DATA:0

#### World Interaction

These instructions interact with the world grid and therefore participate in the Conflict Resolver system (world reads/writes and DP moves are resolved consistently with other organisms).

- PEEK family (read into register/stack)
  - Variants: PEEK, PEKI, PEKS
  - Syntax:
    - PEEK %REG_TARGET %REG_VECTOR
    - PEKI %REG_TARGET X|Y
    - PEKS
  - Operands:
    - Register variants use a target register and a vector (from register or immediate).
    - PEKS pops a vector from DS and pushes the read value to DS.
  - Effect: Read the symbol at DP + vector and store its encoded value in the target (register or stack). Does not modify the world.
  - Energy: 2
  - Errors: invalid vector dimensionality; stack underflow (PEKS)
  - Example: PEKI %DR0 1|0

- SCAN family (read into register/stack)
  - Variants: SCAN, SCNI, SCNS
  - Syntax:
    - SCAN %REG_TARGET %REG_VECTOR
    - SCNI %REG_TARGET X|Y
    - SCNS
  - Effect: Same as PEEK family—reads and stores the value at DP + vector without modifying the world.
  - Energy: 2
  - Errors: same as PEEK family
  - Example: SCNS  (expects a vector on stack)

- POKE family (write to world)
  - Variants: POKE, POKI, POKS
  - Syntax:
    - POKE %REG_SOURCE %REG_VECTOR
    - POKI %REG_SOURCE X|Y
    - POKS
  - Operands:
    - POKE/POKI use source register and vector (register or immediate).
    - POKS pops two items from DS: first the VALUE, then the VECTOR (order matters).
  - Effect: Write the given VALUE into the world cell at DP + vector (if empty). If target is not empty, the instruction fails.
  - Energy: 5
  - Errors: attempting to write vectors as value; target occupied; stack underflow (POKS); invalid vector dimensionality
  - Example: POKI %DR0 0|1

- SEEK family (move DP)
  - Variants: SEEK, SEKI, SEKS
  - Syntax:
    - SEEK %REG_VECTOR
    - SEKI X|Y
    - SEKS
  - Effect:
    - Compute target := DP + vector. If target cell is empty, DP := target; else instruction fails.
    - SEKS pops vector from DS.
  - Energy: 3
  - Errors: invalid vector; target not empty; stack underflow (SEKS)
  - Example: SEKI -1|0

#### Organism State

These instructions only affect the organism’s internal state (registers, stacks, DP/DV, ER).

- SYNC
  - Syntax: SYNC
  - Effect: DP := IP (sets Data Pointer to current Instruction Pointer)
  - Energy: 1
  - Errors: none

- TURN
  - Syntax: TURN %REG_VECTOR
  - Effect: DV := vector (must be a unit vector)
  - Energy: 1
  - Errors: non-unit vector

- POS
  - Syntax: POS %REG_TARGET
  - Effect: REG_TARGET := (IP - initial_program_origin) vector
  - Energy: 1
  - Errors: none

- DIFF
  - Syntax: DIFF %REG_TARGET
  - Effect: REG_TARGET := (DP - IP), component-wise (current IP)
  - Energy: 1
  - Errors: none
  - Example: DIFF %DR0

- NRG / NRGS
  - Variants: NRG, NRGS
  - Syntax:
    - NRG %REG_TARGET
    - NRGS
  - Effect:
    - NRG writes current energy (ER) into REG_TARGET (as DATA scalar).
    - NRGS pushes current energy (ER) onto DS.
  - Energy: 0
  - Errors: none
  - Example: NRGS

- RAND
  - Syntax: RAND %REG
  - Operands: A register holding a DATA scalar upper bound N
  - Effect: %REG := random integer in [0, N-1]
  - Energy: 2
  - Errors: N <= 0 → writes 0 and fails
  - Example: RAND %DR3

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
