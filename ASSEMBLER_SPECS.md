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
  - Count: Configurable (default 2, e.g., PR0, PR1).
  - Addressing: In PROC bodies, PRs are usable as register operands via %PR0/%PR1 or via PROC-local aliases declared with `.PREG %NAME 0|1`.
  - Lifetime: Frame-local; each CALL creates a new PR frame (auto-saved/restored on RET).

---

### 3. Argument Types

- Register: `%DR0` … `%DR7`.
- Literal: `TYPE:VALUE` (e.g., `DATA:123`).
- Vector: `X|Y` (e.g., `1|0`); dimension equals the world dimensions.
- Label: A defined label name.

---

### 4. Instruction Set

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

### 5. Directives (Detailed)

This section describes all assembler directives with syntax, semantics, and examples.

1) .ORG – Set Program Origin
- Syntax: `.ORG X|Y`
- Semantics: Sets the origin for placing the next instruction and its arguments; coordinates are program-relative.
- Example:
  ```
  .ORG 0|0
  NOP
  .ORG 5|5
  NOP
  ```

2) .DIR – Set Assembly Direction Vector
- Syntax: `.DIR X|Y`
- Semantics: Controls how subsequent instruction arguments are laid out relative to the opcode cell; also used for linearization during assembly.
- Example:
  ```
  .ORG 0|0
  .DIR 1|0     # arguments placed to the right
  SETI %DR0 DATA:42
  .DIR 0|1     # arguments placed below
  SETI %DR1 DATA:7
  ```

3) .REG – Define Register Alias
- Syntax: `.REG %NAME ID`
- Semantics: Binds a symbolic name to a data register index (0–7).
- Example:
  ```
  .REG %ACC 0
  .REG %TMP 1
  SETI %ACC DATA:10
  SETR %TMP %ACC
  ```

4) .DEFINE – Textual Constant
- Syntax: `.DEFINE NAME VALUE`
- Semantics: Replaces NAME with VALUE during assembly (after extraction).
- Example:
  ```
  .DEFINE TEN DATA:10
  .ORG 0|0
  SETI %DR0 TEN
  ```

5) .MACRO … .ENDM – Macros
- Syntax:
  ```
  .MACRO NAME PARAM1 [PARAM2 ...]
    # macro body
  .ENDM
  ```
- Semantics: Textual expansion with parameter substitution.
- Invocation:
  - Use the macro name directly: `NAME ARG1 ARG2`
  - (Optional compatibility) If you previously used `$NAME`, invocations without `$` are accepted as well.
- Example:
  ```
  .REG %A 0
  .REG %B 1
  .MACRO ADD_TO_A VAL
      ADDI %A VAL
  .ENDM

  .ORG 0|0
  ADD_TO_A DATA:5
  ADD_TO_A DATA:3
  ```

6) .ROUTINE … .ENDR – Routine Templates
- Syntax:
  ```
  .ROUTINE LIB.NAME P1 [P2 ...]
      # template body using P1/P2...
  .ENDR
  ```
- Semantics: Defines a parameterized template expanded by .INCLUDE/.INCLUDE_STRICT.
- Example:
  ```
  .ROUTINE UTIL.INC REG
      ADDI REG DATA:1
  .ENDR

  .INCLUDE UTIL.INC AS INC0 WITH %DR0
  .INCLUDE UTIL.INC AS INC1 WITH %DR1   # same signature -> alias to first
  ```

7) .INCLUDE – Expand Routine Template (Signature-Dedup)
- Syntax: `.INCLUDE LIB.ROUTINE AS INSTANCE WITH [args...]`
- Semantics:
  - First use of a given (routine + args) emits code labeled INSTANCE.
  - Subsequent identical signatures define INSTANCE as an alias (trampoline) to the first.
- Example:
  ```
  .ROUTINE UTIL.ID2 R
      NOP
      ID2_END:
      NOP
  .ENDR

  .INCLUDE UTIL.ID2 AS ID2_A WITH %DR0
  .INCLUDE UTIL.ID2 AS ID2_B WITH %DR0   # alias to ID2_A (no second emission)
  ```

8) .INCLUDE_STRICT – Force Fresh Instance
- Syntax: `.INCLUDE_STRICT LIB.ROUTINE AS INSTANCE WITH [args...]`
- Semantics: Always emits a new instance, even for identical signatures.
- Example:
  ```
  .INCLUDE UTIL.ID2 AS ID2_A WITH %DR0
  .INCLUDE_STRICT UTIL.ID2 AS ID2_C WITH %DR0  # second, independent emission
  ```

9) .PROC … .ENDP – Procedures (Linkable; DS-ABI or Register-ABI)
- Two forms:
  1) DS-ABI (stack-based):
     - Syntax:
       ```
       .PROC LIB.NAME
           .EXPORT LIB.NAME
           [.REQUIRE OTHER.LIB.NAME]
           # Body uses DS; arguments/results on the data stack
       .ENDP
       ```
     - Semantics: No formal registers; CALL sites must pass arguments via DS (push/push/CALL). `.WITH` is not permitted for DS-ABI procs.
  2) Register-ABI (WITH formals):
     - Syntax:
       ```
       .PROC LIB.NAME WITH A [B ...]
           .EXPORT LIB.NAME
           [.REQUIRE OTHER.LIB.NAME]
           [.PREG %NAME 0|1]   # optional PROC-local aliases for PR0/PR1
           # Body uses A/B as register operands (formals) and optional %PR0/%PR1 via .PREG
       .ENDP
       ```
     - Formals (A, B, …) behave like register operands inside the body. CALL sites bind actual registers at the call via `.WITH`.
     - `.PREG %NAME 0|1` defines PROC-local aliases for PR0/PR1, usable as register operands within the PROC.
- .ENDP auto-RET:
  - If no `RET` appears in the body, a `RET` is implicitly appended at `.ENDP`.
- CALL binding (Register-ABI only):
  - Syntax: `CALL TARGET .WITH %ACT1 [%ACT2 ...]`
    - TARGET can be an import alias or the direct kernel label (e.g., `LIB.NAME`).
    - Arity: Number of actuals must equal number of formals declared by `.PROC ... WITH`.
    - Actuals: DRs (e.g., `%DR3` or `.REG` alias) or PRs (via `%PR0/%PR1` or `.PREG` alias).
  - Execution model (reference semantics):
    1) Copy-in: for each i, formal[i] := actual[i].
    2) CALL kernel (uses RS; PRs are auto-saved/restored).
    3) Copy-back: for each i, actual[i] := formal[i].
  - Aliasing: If the same actual is bound to multiple formals, the last formal’s copy-back wins (deterministic left-to-right).
  - Enforcement:
    - Register-ABI procs (declared WITH) must be called with `.WITH`.
    - `.WITH` is rejected for DS-ABI procs.
- Examples:
  - Register-ABI with formals and PR alias:
    ```
    .PROC LIB.TACTICS.SCAN2 WITH DV TY
        .EXPORT LIB.TACTICS.SCAN2
        .PREG %TMP 0            # %TMP aliases PR0 (frame-local temp)
        SCAN TY DV
        RET
    .ENDP

    .IMPORT LIB.TACTICS.SCAN2 AS SCAN2

    # Use two DRs
    CALL SCAN2 .WITH %DR2 %DR4

    # Use a PR as actual (in/out)
    .PREG %P0 0
    CALL SCAN2 .WITH %P0 %DR4
    ```
  - DS-ABI (no WITH):
    ```
    .PROC LIB.MATH.SUM2
        .EXPORT LIB.MATH.SUM2
        ADDS
        RET
    .ENDP

    # CALL via DS (push/push/CALL); .WITH not allowed

    ---

    ### Best Practices

    - Prefer formal names over raw %DRn in PROC bodies that declare WITH:
      - The assembler maps formals A, B, … to internal %DR0, %DR1, … slots. Using naked %DRn in such bodies can accidentally overlap with those slots.
      - Use the formals (A/B/…) consistently to avoid unintended aliasing.
    - Use .PREG PRs for temporaries that must survive sub-calls:
      - Declare `.PREG %NAME 0|1` and use %NAME (or %PR0/%PR1) as frame-local temps. PRs are auto-saved/restored by CALL/RET.
      - If you pass a PR as an actual via `.WITH`, copy-in/out semantics apply and the caller’s PR will reflect callee changes after return.
    - DS-ABI vs Register-ABI:
      - DS-ABI (no WITH): pass via DS (push/push/CALL). `.WITH` is not permitted.
      - Register-ABI (WITH): bind actuals via `CALL … .WITH …` to formals, which act as in/out references (copy-in/out around the call).
    - Aliasing behavior for `.WITH`:
      - Binding the same actual register to multiple formals is allowed; the last formal’s copy-back wins deterministically (left-to-right).
    - Macro naming:
      - Define macros without `$` (e.g., `.MACRO NAME …`), and invoke them by name (e.g., `NAME ARG`).
      - Avoid name collisions between macros, procs, routines, and labels for readability.
    ```

10) .EXPORT – Mark PROC as Public
- Syntax: `.EXPORT LIB.NAME`
- Semantics: Inside a PROC, marks it importable from other modules/programs.
- Example:
  ```
  .PROC LIB.UTIL.ID
      .EXPORT LIB.UTIL.ID
      # identity on DS
      RET
  .ENDP
  ```

11) .REQUIRE – Declare Dependencies
- Syntax: `.REQUIRE OTHER.LIB.NAME`
- Semantics: Inside a PROC, declares a dependency that must be imported somewhere in the program; assembler validates presence of .IMPORT.
- Example:
  ```
  .PROC LIB.TASK.DO
      .EXPORT LIB.TASK.DO
      .REQUIRE LIB.HELPER.CHECK
      # body...
  .ENDP
  # elsewhere:
  .IMPORT LIB.HELPER.CHECK AS CHECK
  ```

12) .IMPORT – Bind Exported PROC under Alias
- Syntax: `.IMPORT LIB.NAME AS ALIAS`
- Semantics:
  - Emits a single, parameter-independent kernel instance of the PROC (if not emitted yet).
  - Maps ALIAS to the kernel entry (no body duplication per alias).
- Example:
  ```
  .IMPORT LIB.MATH.SUM2 AS SUM2
  ```

13) CALL … .WITH – Register-ABI binding (copy-in/out semantics)
- Syntax: `CALL TARGET .WITH %ACT1 [%ACT2 ...]`
  - TARGET: an import alias or the direct kernel label (e.g., `LIB.NAME`).
  - Only valid for procs declared with `.PROC ... WITH`.
- Semantics:
  - Validation:
    - Arity equals the number of formals.
    - Actuals: DRs (`%DR...` or `.REG` aliases) or PRs (`%PR0/%PR1` or `.PREG` aliases in the current PROC).
  - Execution model (reference semantics):
    1) Copy-in: for each i, formal[i] := actual[i].
    2) CALL kernel (uses RS; PRs are auto-saved/restored).
    3) Copy-back: for each i, actual[i] := formal[i].
  - Aliasing: If the same actual is bound to multiple formals, the last formal’s copy-back wins (deterministic left-to-right).
  - DS-ABI: `.WITH` is not permitted for procs without a WITH list.

14) .PLACE – Place a Symbol at a Relative Coordinate
- Syntax: `.PLACE TYPE:VALUE X|Y`
- Semantics: Places an initial symbol (CODE/DATA/ENERGY/STRUCTURE) at the given relative coordinate.
- Example:
  ```
  .ORG 0|0
  .PLACE DATA:99  2|0
  .PLACE ENERGY:5 0|1
  ```

Putting it all together:
