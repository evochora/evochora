# Evochora Assembler Specification

This document describes the syntax, directives, and the complete instruction set for programming organisms in the Evochora simulation.

### 1. Basic Syntax

- **Comments**: Any character after a hash (`#`) is treated as a comment and ignored by the assembler.
- **Labels**: A label is defined by a name followed by a colon (e.g., `MY_LABEL:`). Label names are case-insensitive and cannot be the same as instruction names.
- **Case-insensitivity**: Instructions, directives, and register names are case-insensitive.

---

### 2. Organism State (CPU Registers)

- **`IP` (Instruction Pointer)**: Absolute world coordinate of the next instruction.
- **`DP` (Data Pointer)**: Absolute world coordinate for memory access (`PEEK`, `POKE`, `SCAN`).
- **`DV` (Direction Vector)**: Direction of movement for the `IP`.
- **`ER` (Energy Register)**: Life energy.
- **`DRs` (Data Registers)**: 8 general-purpose registers (`%DR0`-%DR7`).
- **`DS` (Data Stack)**: LIFO memory for temporary data and return addresses.

---

### 3. Directives

- **`.ORG X|Y`**: Sets the origin for the following code, relative to the program start.
- **`.REG %NAME ID`**: Assigns an alias to a data register (`0-7`).
- **`.MACRO $NAME [params...]` ... `.ENDM`**: Defines a macro.
- **`.ROUTINE NAME [params...]` ... `.ENDR`**: Defines a routine template.
- **`.INCLUDE LIB.ROUTINE AS INSTANCE WITH [args...]`**: Creates a subroutine from a template.
- **`.PLACE TYPE:VALUE X|Y`**: Places a `Symbol` at a relative coordinate.

---

### 4. Argument Types

- **Register**: `%DR0` to `%DR7`.
- **Literal**: `TYPE:VALUE` (e.g., `DATA:123`).
- **Vector**: `X|Y` (e.g., `1|0`).
- **Label**: A defined label name.

---

### 5. Instruction Set

#### **Data & Memory**

- **`SETI %REG_TARGET LITERAL`**: Sets a register to a literal value.
- **`SETR %REG_TARGET %REG_SOURCE`**: Copies a register value.
- **`SETV %REG_TARGET VECTOR|LABEL`**: Sets a register to a vector value.
- **`PUSH %REG_SOURCE`**: Pushes a register value onto the stack.
- **`POP %REG_TARGET`**: Pops a value from the stack into a register.
- **`PUSI LITERAL`**: Pushes a literal value directly onto the stack.

#### **Arithmetic & Logic**

- **`ADDR %REG_A %REG_B`**: `A = A + B` (scalar & vector).
- **`SUBR %REG_A %REG_B`**: `A = A - B` (scalar & vector).
- **`ADDI %REG LITERAL`**: `REG = REG + LITERAL`.
- **`SUBI %REG LITERAL`**: `REG = REG - LITERAL`.
- **`MULR %REG_A %REG_B`**: `A = A * B` (scalar).
- **`MULI %REG LITERAL`**: `REG = REG * LITERAL`.
- **`DIVR %REG_A %REG_B`**: `A = A / B` (scalar, integer division).
- **`DIVI %REG LITERAL`**: `REG = REG / LITERAL`.
- **`MODR %REG_A %REG_B`**: `A = A % B` (scalar, modulo).
- **`MODI %REG LITERAL`**: `REG = REG % LITERAL`.

#### **Bitwise Operations**

- **`NADR %REG_A %REG_B`**: `A = NOT (A AND B)`.
- **`NADI %REG LITERAL`**: `REG = NOT (REG AND LITERAL)`.
- **`ANDR %REG_A %REG_B`**: `A = A AND B`.
- **`ANDI %REG LITERAL`**: `REG = REG AND LITERAL`.
- **`ORR %REG_A %REG_B`**: `A = A OR B`.
- **`ORI %REG LITERAL`**: `REG = REG OR LITERAL`.
- **`XORR %REG_A %REG_B`**: `A = A XOR B`.
- **`XORI %REG LITERAL`**: `REG = REG XOR LITERAL`.
- **`NOT %REG`**: `REG = NOT REG`.
- **`SHLI %REG DATA:N`**: Bit-shift left by N places.
- **`SHRI %REG DATA:N`**: Bit-shift right by N places (arithmetic).

#### **Control Flow (Jumps)**

- **`JMPI LABEL`**: Jumps to a label (relative jump).
- **`JMPR %REG_ADDR`**: Jumps to the program-relative address in the register.
- **`CALL LABEL`**: Calls a subroutine.
- **`RET`**: Returns from a subroutine.

#### **Conditional Statements**

- **`IFR %REG_A %REG_B`**: If `A == B`.
- **`GTR %REG_A %REG_B`**: If `A > B` (scalar).
- **`LTR %REG_A %REG_B`**: If `A < B` (scalar).
- **`IFI %REG LITERAL`**: If `REG == LITERAL`.
- **`GTI %REG LITERAL`**: If `REG > LITERAL` (scalar).
- **`LTI %REG LITERAL`**: If `REG < LITERAL` (scalar).
- **`IFTR %REG_A %REG_B`**: If `type(A) == type(B)`.
- **`IFTI %REG LITERAL`**: If `type(REG) == type(LITERAL)`.

#### **World Interaction & Organism State**

- **`PEEK %REG_TARGET %REG_VECTOR`**: Reads the symbol at `DP + vector` and clears the cell.
- **`PEKI %REG_TARGET VECTOR`**: Like PEEK, but with a vector literal.
- **`POKE %REG_SOURCE %REG_VECTOR`**: Writes a symbol at `DP + vector`.
- **`POKI %REG_SOURCE VECTOR`**: Like POKE, but with a vector literal.
- **`SCAN %REG_TARGET %REG_VECTOR`**: Reads the symbol at `DP + vector` without clearing it.
- **`SEEK %REG_VECTOR`**: Moves `DP` by the vector.
- **`SEKI VECTOR`**: Moves `DP` by the vector literal. **(NEW)**
- **`SYNC`**: `DP = IP`.
- **`TURN %REG_VECTOR`**: `DV = vector`.
- **`POS %REG_TARGET`**: Writes the program-relative coordinate of the `IP` to the register.
- **`DIFF %REG_TARGET`**: `TARGET = DP - IP`.
- **`NRG %REG_TARGET`**: `TARGET = ER`.
- **`RAND %REG`**: `REG = random(0, REG - 1)`.
- **`FORK %REG_DELTA %REG_ENERGY %REG_DV`**: Creates a clone.
