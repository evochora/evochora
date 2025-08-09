# Evochora Assembler Specification

This document describes the syntax, directives, and the complete instruction set for programming organisms in the Evochora simulation.

### 1. Basic Syntax

- Comments: Any character after a hash (`#`) is treated as a comment and ignored by the assembler.
- Labels: A label is defined by a name followed by a colon (e.g., `MY_LABEL:`). Label names are case-insensitive and cannot be the same as instruction names.
- Case-insensitivity: Instructions, directives, and register names are case-insensitive.

---

### 2. Organism State (CPU & Stacks)

- IP (Instruction Pointer): Absolute world coordinate of the next instruction.
- DP (Data Pointer): Absolute world coordinate for memory access (PEEK/POKE/SCAN).
- DV (Direction Vector): Direction of movement for IP.
- ER (Energy Register): Life energy.
- DRs (Data Registers): 8 general-purpose registers (%DR0–%DR7).
- DS (Data Stack): LIFO memory for temporary data (operands/results).
  - Limit: DS_MAX_DEPTH (default 1024). Over-/Underflow fails the current instruction.
- RS (Return Stack): Used exclusively by CALL/RET for return addresses and PROC frames.
  - Limit: RS_MAX_DEPTH (default 1024). Over-/Underflow fails the current instruction.
- PRs (PROC Registers): PROC-local temporaries saved/restored automatically per CALL/RET frame.
  - Count: Configurable (default 2, e.g., PR0, PR1). Not directly part of instruction encodings.

---

### 3. Directives

- `.ORG X|Y`: Sets the program origin (relative to program start).
- `.DIR X|Y`: Sets the assembly direction vector for argument placement and linearization.
- `.REG %NAME ID`: Assigns an alias to a data register (0–7).
- `.DEFINE NAME VALUE`: Defines a textual constant (used in assembly).
- `.MACRO $NAME [params...]` … `.ENDM`: Defines a macro.
- `.ROUTINE NAME [params...]` … `.ENDR`: Defines a routine template (inline expansion).
- `.INCLUDE LIB.ROUTINE AS INSTANCE WITH [args...]`: Expands a routine template.
  - Signature-based deduplication: first usage emits code; subsequent equal signatures produce aliases to the first instance.
- `.INCLUDE_STRICT LIB.ROUTINE AS INSTANCE WITH [args...]`: Forces a new instance (no dedup).
- `.PROC LIB.NAME` … `.ENDP`: Defines a procedure body (linkable, callable via CALL).
  - Inside a PROC:
    - `.EXPORT LIB.NAME`: Marks the PROC as exported and importable by other modules.
    - `.REQUIRE OTHER.LIB.NAME`: Declares a dependency that must be imported by the program.
- `.IMPORT LIB.NAME AS ALIAS`: Binds an exported PROC under the given ALIAS at the call site.
- `.PLACE TYPE:VALUE X|Y`: Places a Symbol at a relative coordinate.

---

### 4. Argument Types

- Register: `%DR0` … `%DR7`.
- Literal: `TYPE:VALUE` (e.g., `DATA:123`).
- Vector: `X|Y` (e.g., `1|0`); dimension equals the world dimensions.
- Label: A defined label name.

---

### 5. Instruction Set

#### Data & Memory

- `SETI %REG_TARGET LITERAL`: Set a register to a literal value.
- `SETR %REG_TARGET %REG_SOURCE`: Copy a register value.
- `SETV %REG_TARGET VECTOR|LABEL`: Set a register to a vector value.
- `PUSH %REG_SOURCE`: Push a register value onto DS.
- `POP %REG_TARGET`: Pop a value from DS into a register.
- `PUSI LITERAL`: Push a literal value onto DS.

#### Stack Operations (DS only)

- `DUP`: Duplicate TOS. DS: [..., A] → [..., A, A].
- `SWAP`: Swap top two elements. DS: [..., A, B] → [..., B, A].
- `DROP`: Remove TOS. DS: [..., A] → [...].
- `ROT`: Rotate top three. DS: [..., A, B, C] → [..., B, C, A].

#### Arithmetic & Logic

Register/Immediate variants:
- `ADDR %A %B`: A = A + B (scalar & vector).
- `SUBR %A %B`: A = A - B (scalar & vector).
- `ADDI %R LIT`: R = R + LITERAL.
- `SUBI %R LIT`: R = R - LITERAL.
- `MULR %A %B`: A = A * B (scalar).
- `MULI %R LIT`: R = R * LITERAL.
- `DIVR %A %B`: A = A / B (scalar, integer div).
- `DIVI %R LIT`: R = R / LITERAL.
- `MODR %A %B`: A = A % B (scalar).
- `MODI %R LIT`: R = R % LITERAL.

Stack (S-) variants:
- `ADDS`: pop b, pop a, push (a + b). Scalars and vectors supported; types must match in strict mode.
- `SUBS`: pop b, pop a, push (a - b). Scalars and vectors supported; types must match in strict mode.
- `MULS`: pop b, pop a, push (a * b). Scalars only; types must match in strict mode.
- `DIVS`: pop b, pop a, push (a / b). Scalars only; division by zero fails.
- `MODS`: pop b, pop a, push (a % b). Scalars only; modulo by zero fails.

#### Bitwise Operations

Register/Immediate variants:
- `NADR %A %B`: A = NOT(A AND B).
- `NADI %R LIT`: R = NOT(R AND LITERAL).
- `ANDR %A %B`: A = A AND B.
- `ANDI %R LIT`: R = R AND LITERAL.
- `ORR %A %B`: A = A OR B.
- `ORI %R LIT`: R = R OR LITERAL.
- `XORR %A %B`: A = A XOR B.
- `XORI %R LIT`: R = R XOR LITERAL.
- `NOT %R`: Bitwise NOT on register.

Stack (S-) variants:
- `NADS`: pop b, pop a, push NOT(a AND b). Scalars only; types must match in strict mode.
- `ANDS`: pop b, pop a, push (a AND b). Scalars only; types must match in strict mode.
- `ORS`: pop b, pop a, push (a OR b). Scalars only; types must match in strict mode.
- `XORS`: pop b, pop a, push (a XOR b). Scalars only; types must match in strict mode.
- `NOTS`: pop a, push NOT(a). Scalar only.

#### Shifts

Register/Immediate variants:
- `SHLI %R DATA:N`: Shift left by N (scalar).
- `SHRI %R DATA:N`: Shift right by N (scalar, arithmetic).

Stack (S-) variants:
- `SHLS`: pop value, pop count; push (value << count). Scalar only.
- `SHRS`: pop value, pop count; push (value >> count). Scalar only.

#### Control Flow (Jumps & Calls)

- `JMPI LABEL`: Jump to label (relative).
- `JMPR %REG_ADDR`: Jump to program-relative address in a register.
- `CALL LABEL`: Call subroutine. CALL pushes return info on RS; callee returns with RET.
- `RET`: Return from subroutine. Pops return info from RS and restores PRs.

#### Conditional Statements

- `IFR %A %B`: If `A == B` (types must match in strict mode).
- `GTR %A %B`: If `A > B` (scalar).
- `LTR %A %B`: If `A < B` (scalar).
- `IFI %R LIT`: If `R == LITERAL`.
- `GTI %R LIT`: If `R > LITERAL` (scalar).
- `LTI %R LIT`: If `R < LITERAL` (scalar).
- `IFTR %A %B`: If `type(A) == type(B)`.
- `IFTI %R LIT`: If `type(R) == type(LITERAL)`.

#### World Interaction & Organism State

- `PEEK %REG_TARGET %REG_VECTOR`: Read symbol at `DP + vector`, clear the cell (destructive).
- `PEKI %REG_TARGET X|Y`: Immediate-vector PEEK (destructive).
- `POKE %REG_SOURCE %REG_VECTOR`: Write symbol at `DP + vector`.
- `POKI %REG_SOURCE X|Y`: Immediate-vector POKE.
- `SCAN %REG_TARGET %REG_VECTOR`: Read symbol at `DP + vector` without clearing (non-destructive). Alias: `SCNR` equals `SCAN`.
- `SCNI %REG_TARGET X|Y`: SCAN with immediate vector (non-destructive).
- `SCNS`: Stack-vector SCAN (non-destructive). Pop vec (int[]), push scanned symbol.
- `SEEK %REG_VECTOR`: Move DP by register vector (if destination cell is empty).
- `SEKI X|Y`: Move DP by immediate vector (if destination cell is empty).
- `SEKS`: Stack-vector SEEK. Pop vec; DP += vec if destination is empty; else fail.
- `SYNC`: `DP = IP`.
- `TURN %REG_VECTOR`: `DV = vector`.
- `POS %REG_TARGET`: Write program-relative coordinate of IP to the register.
- `DIFF %REG_TARGET`: Write `DP - IP` to the register.
- `NRG %REG_TARGET`: Write energy (ER) to the register.
- `RAND %REG`: `REG = random(0..REG-1)`.

---
