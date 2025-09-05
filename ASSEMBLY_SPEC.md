# Evochora Assembly: Language Reference

## 1. Introduction & Overview

Evochora is a digital ecosystem designed to simulate the evolution of simple, programmable organisms. At its core, it provides a playground for exploring concepts of artificial life, emergent behavior, and evolutionary strategies. The simulation takes place in a customizable, n-dimensional world where organisms must compete for resources, primarily energy and computing time, to survive and reproduce. Each organism is an autonomous agent controlled by a program written in a custom assembly language. This language is the key to defining an organism's behavior—from basic movement and environmental interaction to complex decision-making and replication strategies.

This document serves as the official reference for the Evochora assembly language. It provides the necessary details to understand the virtual machine that powers each organism, the syntax of the language, and the full instruction set available for programming their actions and logic. Whether you are designing a simple energy-seeker or a complex replicating creature, this guide contains the information needed to bring your digital life-forms into existence.

---

## 2. The Simulation Environment

The world of Evochora is a discrete, n-dimensional grid where all interactions take place. The size and properties of this grid are defined in the simulation's configuration.

### The Grid

The environment is structured as a grid with configurable dimensions. Each point on this grid, identified by its coordinates, can hold a single "Molecule".

**Note on Dimensionality Limits**: Some instructions that use bitmasks for direction encoding (such as SPNP, SNT*, B2V, and V2B) are limited to worlds with **n ≤ Config.VALUE_BITS/2 dimensions** due to register size constraints. Each dimension requires 2 bits in the direction bitmask (one for +1, one for -1 direction), limiting the total to half the available value bits.

### Toroidal Space

The world is toroidal, meaning it wraps around at the edges. An organism moving past the right edge will reappear on the left, and one moving past the top will reappear on the bottom. This creates a continuous space without boundaries.

### Molecules

Every cell in the grid contains a **Molecule**, which is the fundamental unit of information and matter. A molecule has two properties: a **type** and a **value**. The type determines its function and how organisms interact with it. There are four primary types:

* **`CODE`**: Represents an executable instruction for an organism's virtual machine.

* **`DATA`**: Represents a generic data value that can be manipulated by instructions. These values can also be arguments for instructions.

* **`ENERGY`**: A resource that organisms can consume to replenish their own energy reserves (ER).

* **`STRUCTURE`**: Represents physical matter, like the body of an organism.

### Ownership

Any grid cell can be "owned" by an organism. This ownership is tracked separately from the molecule in the cell and is crucial for certain instructions like `SEEK` or `PEEK`, which behave differently depending on whether the target cell is owned by the acting organism, a foreign organism, or is unowned. An organism may also treat cells owned by its direct parent as accessible, i.e., as if they were its own, for the purpose of certain instructions (see `SEEK` and `IFM*`). Some instructions treat unowned cells as foreign for cost purposes.

---

## 3. The Organism's Virtual Machine

Each organism operates like a simple computer, equipped with its own set of registers, pointers, and stacks. This internal architecture, or virtual machine (VM), executes the machine code that dictates the organism's state and actions. This machine code, stored as `CODE` and `DATA` molecules in the environment's grid, can be written by the user in the Evochora assembly language.

### Pointers

Pointers are special-purpose registers that define an organism's position and orientation in the world.

* **Instruction Pointer (`IP`)**: An absolute coordinate in the grid that points to the next `CODE` molecule the organism will execute.

* **Data Pointers (`DPx`)**: A set of absolute coordinates used as the base for all world-interaction instructions. An organism has multiple DPs (e.g., `%DP0`, `%DP1`), but only one is "active" at a time to serve as the origin for instructions like `PEEK` and `SEEK`.

* **Direction Vector (`DV`)**: A unit vector that determines the direction in which the `IP` advances after executing an instruction.

### Registers

Registers are the primary working memory of the organism. They can hold either scalar values (any molecule type like `CODE`, `DATA`, `ENERGY`, or `STRUCTURE`) or vector values (e.g., `1|0`).

* **Global Data Registers (`%DRx`)**: A set of 8 general-purpose registers (`%DR0` to `%DR7`) for storing and manipulating data. Their values persist across procedure calls.

* **Procedure-Local Registers (`%PRx`)**: A set of 2 temporary registers (`%PR0`, `%PR1`) intended for use within a procedure. Their values are automatically saved to the Call Stack upon a `CALL` instruction and restored upon `RET`, making them safe to use without interfering with the caller's state.

* **Formal Parameter Registers (`%FPRx`)**: A set of 8 internal registers (`%FPR0` to `%FPR7`) used exclusively for parameter passing in procedures defined with a `.WITH` clause. They cannot be accessed directly by name in the code.

* **Location Registers (`%LRx`)**: A set of 4 registers (`%LR0` to `%LR3`) for storing vector values (coordinates or direction vectors).

### Stacks

The VM includes three distinct stacks to manage data and control flow.

* **Data Stack (`DS`)**: A last-in, first-out (LIFO) stack for temporary data storage. It is used by `PUSH`/`POP` instructions and the stack-based variants of arithmetic and logic instructions (e.g., `ADDS`).

* **Location Stack (`LS`)**: A dedicated LIFO stack for storing vector values, separate from the `DS`.

* **Call Stack (`CS`)**: This stack is managed automatically by `CALL` and `RET` instructions. It stores return addresses and the state of the `%PRx` registers, enabling structured programming with nested procedure calls.

### Energy (ER)

The Energy Register (`ER`) holds the organism's life force. Energy is the central resource that governs an organism's ability to act and survive. This system is designed to create evolutionary pressure, rewarding efficient and well-adapted behaviors.

* **Instruction Cost**: Every instruction an organism executes has a base energy cost, which is deducted from the `ER`.

* **Action-Specific Costs**: More complex actions have additional costs. For example, placing a molecule with `POKE` costs energy proportional to the molecule's value, and consuming a `STRUCTURE` molecule owned by another organism also costs energy.

* **Error Penalty**: An invalid operation (like dividing by zero or a stack underflow) will cause the instruction to fail and incur an additional energy penalty.

* **Gaining Energy**: Organisms can replenish their energy by consuming `ENERGY` molecules from the environment using the `PEEK` instruction.

* **Checking Energy**: The `NRG` and `NRGS` instructions allow an organism to check its current energy level.

* **Death**: If an organism's `ER` drops to zero or below, it dies and is removed from the simulation.

---

## 4. Basic Syntax

The syntax of Evochora assembly is designed to be simple and readable. It follows standard conventions for assembly languages.

### Statement Structure

A typical line of code consists of an optional label, followed by an instruction or a directive, and then its arguments. Each component is separated by whitespace. Each statement must end with a newline character; you cannot place multiple instructions on the same line. Empty lines are ignored.

```
OPCODE ARGUMENT1 ARGUMENT2  # Comment
```

### Comments

Any text following a hash symbol (`#`) is treated as a comment and is ignored by the compiler. This is useful for documenting code.

```
# This is a full-line comment.
SETI %DR0 DATA:100  # This is an inline comment.
```

### Labels

A label is a name followed by a colon (`:`). It marks a specific location in the code, which can then be used as a target for jump and call instructions (`JMPI`, `CALL`). A label can be on the same line as an instruction or on its own line, in which case it points to the next instruction or directive.

```
START_LOOP:
  ADDI %DR0 DATA:1
  JMPI START_LOOP
```

### Case-Insensitivity

Instructions, directives, and register names are case-insensitive. `SETI`, `seti`, and `SeTi` are all treated as the same instruction. Similarly, `%DR0` is the same as `%dr0`. Label names are also case-insensitive.

### Whitespace

Spaces and tabs are used to separate elements in a statement. Multiple whitespace characters are treated as a single separator.

---

## 5. Data Types and Literals

All values in Evochora are fundamentally represented as **Molecules**, but the assembly language provides several convenient ways to write them in code. These are known as literals.

### Registers

The most common way to reference a value is through a register. Registers are specified by their name, such as `%DR0` or `%PR1`. They can be used as arguments in most instructions.

### Typed Literals

A typed literal explicitly specifies both the type and the value of a molecule. This is the standard way to provide immediate values to instructions.

* **Syntax**: `TYPE:VALUE`
* **Examples**:
    * `DATA:42` (A data value of 42)
    * `ENERGY:500` (An energy value of 500)
    * `STRUCTURE:1` (A structure value of 1)
    * `CODE:0` (An empty cell, which is also the `NOP` instruction)
* **Number Formats**: The `VALUE` part can be a decimal number (e.g., `100`), a hexadecimal number (e.g., `0xFF`), a binary number (e.g., `0b1010`), or an octal number (e.g., `0o77`).

### Vector Literals

A vector literal is used to define coordinates or direction vectors. The components are separated by a pipe (`|`). The number of components must match the dimensions of the world. Each component can be specified in any of the supported number formats.

* **Syntax**: `VALUE1|VALUE2|...`
* **Examples**:
    * `1|0` (A vector pointing right in a 2D world)
    * `10|-5` (A vector pointing to the relative coordinate (10, -5))
    * `0xFF|0b1010` (A valid vector using mixed number formats)

### Labels as Literals

When a label is used as an argument for an instruction that expects a vector (like `JMPI` or `SETV`), the compiler automatically calculates the relative vector from the instruction's location to the label's location. This allows for position-independent code.

* **Example**:
    ```
    SETV %DR0 MY_TARGET  # DR0 will hold the vector pointing to MY_TARGET
    JMPI MY_TARGET      # Jumps to the location of MY_TARGET
    ...
    MY_TARGET: NOP
    ```

---

## 6. Instruction Set

The instruction set defines the fundamental operations an organism can perform. Many instructions come in three variants, identified by the last letter of their opcode:

* `...R` (**Register**): The operation uses two registers.
* `...I` (**Immediate**): The operation uses a register and a literal value.
* `...S` (**Stack**): The operation uses values from the Data Stack (`DS`).

### Data and Memory Operations

* `SETI %REG <Literal>`: Sets `<%REG>` to the immediate `<Literal>`. (Cost: 1)
* `SETR %DEST_REG %SRC_REG`: Copies the value from `<%SRC_REG>` to `<%DEST_REG>`. (Cost: 1)
* `SETV %REG <Vector|Label>`: Sets `<%REG>` to the specified vector or the vector to the label. (Cost: 1)
* `PUSH %REG`: Pushes the value of `<%REG>` onto the Data Stack. (Cost: 1)
* `POP %REG`: Pops a value from the Data Stack into `<%REG>`. (Cost: 1)
* `PUSI <Literal>`: Pushes the immediate `<Literal>` onto the Data Stack. (Cost: 1)
* `DUP`: Duplicates the top value on the Data Stack. (Cost: 1)
* `SWAP`: Swaps the top two values on the Data Stack. (Cost: 1)
* `DROP`: Discards the top value on the Data Stack. (Cost: 1)
* `ROT`: Rotates the top three values on the Data Stack. (Cost: 1)

### Arithmetic Operations

These instructions operate on scalar `DATA` values. `ADD` and `SUB` also support component-wise vector arithmetic.

* `ADDR %REG1 %REG2`, `ADDI %REG1 <Literal>`, `ADDS`: Performs addition. (Cost: 1)
* `SUBR %REG1 %REG2`, `SUBI %REG1 <Literal>`, `SUBS`: Performs subtraction. (Cost: 1)
* `MULR %REG1 %REG2`, `MULI %REG1 <Literal>`, `MULS`: Performs multiplication (scalars only). (Cost: 1)
* `DIVR %REG1 %REG2`, `DIVI %REG1 <Literal>`, `DIVS`: Performs division (scalars only). (Cost: 1)
* `MODR %REG1 %REG2`, `MODI %REG1 <Literal>`, `MODS`: Performs modulo (scalars only). (Cost: 1)
* `DOTR %DEST_REG %VEC_REG1 %VEC_REG2`, `DOTS`: Calculates the dot product of two vectors. (Cost: 1)
* `CRSR %DEST_REG %VEC_REG1 %VEC_REG2`, `CRSS`: Calculates the 2D cross product of two vectors. (Cost: 1)

### Bitwise Operations

These instructions operate on the integer value of scalars.

* `ANDR %REG1 %REG2`, `ANDI %REG1 <Literal>`, `ANDS`: Bitwise AND. (Cost: 1)
* `ORR %REG1 %REG2`, `ORI %REG1 <Literal>`, `ORS`: Bitwise OR. (Cost: 1)
* `XORR %REG1 %REG2`, `XORI %REG1 <Literal>`, `XORS`: Bitwise XOR. (Cost: 1)
* `NADR %REG1 %REG2`, `NADI %REG1 <Literal>`, `NADS`: Bitwise NAND. (Cost: 1)
* `NOT %REG`, `NOTS`: Bitwise NOT. (Cost: 1)
* `SHLR %REG_VAL %REG_AMT`, `SHLI %REG_VAL <Literal>`, `SHLS`: Logical shift left. (Cost: 1)
* `SHRR %REG_VAL %REG_AMT`, `SHRI %REG_VAL <Literal>`, `SHRS`: Logical shift right. (Cost: 1)

#### Rotation and Bit Utilities

These operate on the lower VALUE_BITS of the scalar and preserve the molecule type.

* `ROTR %VAL_REG %AMT_REG`, `ROTI %VAL_REG <Amount>`, `ROTS`: Rotate bits.
  - The signed amount determines direction: positive rotates left, negative rotates right.
  - `ROTS` pops `<Amt>` then `<Val>` from the Data Stack and pushes the rotated result. All stack ops are destructive.
  - Rotations by 0 or by the full bit-width leave the value unchanged.
* `PCNR %DEST_REG %SRC_REG`, `PCNS`: Population count (number of set bits in the lower VALUE_BITS).
  - `PCNR` writes the count into `%DEST_REG` with the same type as `%SRC_REG`.
  - `PCNS` pops a scalar and pushes the count with type `DATA`.
* `BSNR %DEST_REG %SRC_REG %N_REG`, `BSNI %DEST_REG %SRC_REG <N>`, `BSNS`: Bit Scan N-th.
  - Finds the n-th set bit in `%SRC` and writes a bitmask with only that bit set.
  - N is 1-based. Positive N scans from LSB to MSB; negative N scans from MSB to LSB. N=0 is invalid.
  - Failure conditions: N=0 or fewer than |N| set bits. On failure, the instruction fails and `%DEST_REG` (or the stack result for `BSNS`) is set to 0.

#### Random Bit Selection

Selects a single set bit from a source mask at random. If the source mask is 0, the result is 0. Randomness is provided by the simulation's seeded RNG for reproducibility.

* `RBIR %DEST_REG %SOURCE_REG`, `RBII %DEST_REG <Mask_Lit>`, `RBIS`
  - From the source bitmask, randomly selects one of the set bits and writes a mask containing only that bit.
  - Register variants preserve the molecule type of the source; immediates use `DATA`.
  - Stack variant pops the source mask and pushes the result.

#### Scan Passable Neighbors (SPNP)

Scans axis-aligned neighbors around the active DP and returns a bitmask of passable directions. A neighbor is passable if the cell is empty or owned by self or direct parent. For dimension d (0-indexed), bit 2·d indicates the +1 direction, bit 2·d+1 indicates the −1 direction. **Supports up to n ≤ Config.VALUE_BITS/2 dimensions due to bitmask size limitations.**

* `SPNR %MASK_REG`, `SPNS`
  - Register variant writes the mask to `%MASK_REG`.
  - Stack variant pushes the mask onto the Data Stack.
  - If no neighbors are passable, the mask is `DATA:0`.

#### Scan Neighbors by Type (SNT*)

Scans axis-aligned neighbors around the active DP and returns a bitmask indicating where neighbors match a specific molecule type. For dimension d (0-indexed), bit 2·d indicates the +1 direction, bit 2·d+1 indicates the −1 direction. **Supports up to n ≤ Config.VALUE_BITS/2 dimensions due to bitmask size limitations.**

* `SNTR %DEST_REG %TYPE_REG`, `SNTI %DEST_REG <Type_Lit>`, `SNTS`
  - Compares only the molecule type of the neighbor cell; the VALUE is ignored.
  - `<Type_Lit>` is any typed literal; only its type component is used (e.g., `ENERGY:0` selects ENERGY).
  - Register variants write the `DATA`-typed mask into `%DEST_REG`; stack variant pushes it.
  - If no neighbors match, the mask is `DATA:0`.

### Control Flow

* `JMPI <Label>`: Jumps to `<Label>`. (Cost: 1)
* `JMPR %VEC_REG>`: Jumps to the vector address in `<%VEC_REG>`. (Cost: 1)
* `JMPS`: Jumps to the vector address popped from the stack. (Cost: 1)
* `CALL <Label>`: Calls the procedure at `<Label>`. (Cost: 1)
* `RET`: Returns from a procedure. (Cost: 1)

### Conditional Instructions

These instructions skip the next instruction if the condition is false.

* `IFR %REG1 %REG2`, `IFI %REG1 <Literal>`, `IFS`: If values are equal. (Cost: 1)
* `LTR %REG1 %REG2`, `LTI %REG1 <Literal>`, `LTS`: If value of first argument is less than second. (Cost: 1)
* `GTR %REG1 %REG2`, `GTI %REG1 <Literal>`, `GTS`: If value of first argument is greater than second. (Cost: 1)
* `IFTR %REG1 %REG2`, `IFTI %REG1 <Literal>`, `IFTS`: If molecule types are equal. (Cost: 1)
* `IFMR %VEC_REG`, `IFMI <Vector>`, `IFMS`: If cell at `DP` + vector is owned by self or direct parent. The vector must be a unit vector. (Cost: 1)

#### Negated Conditional Instructions

These instructions are the logical opposites of the standard conditional instructions. They skip the next instruction if the original condition is met.

* `INR %REG1 %REG2`, `INI %REG1 <Literal>`, `INS`: If values are **not** equal. (Cost: 1)
* `GETR %REG1 %REG2`, `GETI %REG1 <Literal>`, `GETS`: If value of first argument is **greater than or equal to** second. (Cost: 1)
* `LETR %REG1 %REG2`, `LETI %REG1 <Literal>`, `LETS`: If value of first argument is **less than or equal to** second. (Cost: 1)
* `INTR %REG1 %REG2`, `INTI %REG1 <Literal>`, `INTS`: If molecule types are **not** equal. (Cost: 1)
* `INMR %VEC_REG`, `INMI <Vector>`, `INMS`: If cell at `DP` + vector is **not** owned by self or direct parent. The vector must be a unit vector. (Cost: 1)

### World Interaction

These instructions interact with the environment grid relative to the **active Data Pointer (`DP`)**. The vector argument must be a unit vector, meaning these instructions can only target adjacent cells.
Note on conflicts: If a world interaction loses conflict resolution for its target, its base energy cost is waived (cost 0). Any additional costs described below may still apply if taken before the conflict outcome (e.g., `POKE`).

* `PEEK %DEST_REG %VEC_REG`, `PEKI %DEST_REG <Vector>`, `PEKS`: Reads and consumes molecule at `DP` + vector, then clears ownership on that cell. (Base Cost: 1)
  - ENERGY: Adds its value to `ER` (no extra cost).
  - STRUCTURE: If the cell is not accessible (neither owned by self nor direct parent), pay additional `|value|` energy.
  - CODE/DATA: Pay an additional flat cost of `5` unless the cell is owned by self (ownerId != 0). Unowned and parent-owned cells still incur this `5` cost.
* `SCAN %DEST_REG %VEC_REG`, `SCNI %DEST_REG <Vector>`, `SCNS`: Reads molecule at `DP` + vector without consuming it. (Cost: 1)
* `POKE %SRC_REG %VEC_REG`, `POKI %SRC_REG <Vector>`, `POKS`: Writes molecule from `<%SRC_REG>` or stack to an empty cell at `DP` + vector. (Base Cost: 1)
  - Additional cost: ENERGY or STRUCTURE writes cost `|value|`; CODE or DATA writes cost `5`.
  - Note: The additional cost is charged even if the target cell is occupied and the write fails.
* `SEEK %VEC_REG`, `SEKI <Vector>`, `SEKS`: Moves active `DP` by vector if target cell is empty or accessible (owned by self or direct parent). (Cost: 1)

### State and Location Operations

* `NOP`: No operation. (Cost: 1)
* `SYNC`: Sets active `DP` = `IP`. (Cost: 1)
* `TURN %VEC_REG`, `TRNI <Vector>`, `TRNS`: Sets `DV` to the specified vector. The instruction will fail if the vector is not a unit vector. (Cost: 1)
* `POS %REG`, `POSS`: Stores the organism's position relative to its start (`IP` - `InitialIP`) in `<%REG>` or on the stack. (Cost: 1)
* `DIFF %REG`, `DIFS`: Stores the vector `DP` - `IP` in `<%REG>` or on the stack. (Cost: 1)
* `NRG %REG`, `NRGS`: Stores current `ER` in `<%REG>` or on the stack. (Cost: 1)
* `RAND %REG`, `RNDS`: Stores a random number [0, `<%REG>`) back into `<%REG>` or on the stack. (Cost: 1)
* `FORK %DP_VEC_REG %NRG_REG %DV_VEC_REG`: Creates a child organism. (Cost: 10 + energy)
* `FRKI <DP_Vec> <NRG_Lit> <DV_Vec>`, `FRKS`: Creates a child organism (immediate/stack variants). (Cost: 1)
* `ADPR %REG`, `ADPI <Literal>`, `ADPS`: Sets the active Data Pointer index. (Cost: 1)

### Location Stack and Register Operations

These instructions manage the Location Stack (`LS`) and Location Registers (`%LRx`).

* `DUPL`, `SWPL`, `DRPL`, `ROTL`: Standard stack operations (Duplicate, Swap, Drop, Rotate) for the `LS`. (Cost: 1)
* `DPLS`: Pushes the active `DP` onto the `LS`. (Cost: 1)
* `SKLS`: Pops a vector from `LS` and sets it as the active `DP`. (Cost: 1)
* `LSDS`: Pops a vector from `LS` and pushes it onto the `DS`. (Cost: 1)
* `DPLR %LR<Index>`: Copies the active `DP` into `%LR<Index>`. (Cost: 1)
* `SKLR %LR<Index>`: Sets the active `DP` to the vector stored in `%LR<Index>`. (Cost: 1)
* `PUSL %LR<Index>`: Pushes the vector from `%LR<Index>` onto the `LS`. (Cost: 1)
* `POPL %LR<Index>`: Pops a vector from `LS` into `%LR<Index>`. (Cost: 1)
* `LRDR %DEST_REG %LR<Index>`: Copies the vector from `%LR<Index>` into `<%DEST_REG>`. (Cost: 1)
* `LRDS %LR<Index>`: Pushes the vector from `%LR<Index>` onto the `DS`. (Cost: 1)
* `LSDR %DEST_REG`: Copies the top vector from `LS` into `<%DEST_REG>` without popping. (Cost: 1)

### Vector Component Operations

These instructions provide atomic control over the components of a vector, allowing for dynamic construction and manipulation of vectors at runtime.

* `VGTR %Dst_Reg %Src_Vec %Idx_Reg`, `VGTI %Dst_Reg %Src_Vec <Idx_Lit>`, `VGTS`: **V**ector **G**e**t**: Extracts a single scalar component from a source vector. The `R` variant uses a register for the index, `I` uses an immediate literal, and `S` operates entirely on the stack.

* `VSTR %Vec_Reg %Idx_Reg %Val_Reg`, `VSTI %Vec_Reg <Idx_Lit> <Val_Lit>`, `VSTS`:**V**ector **S**e**t**: Modifies a single scalar component within a target vector. The variants follow the same pattern as `VGET`. Note that the `I` variant for `VSTI` takes an immediate index but a register for the value.

* `VBLD %Dest_Reg`, `VBLS`: **V**ector **B**ui**ld**: Constructs a new vector from N scalar values taken from the top of the Data Stack (where N is the dimensionality of the world). `VBLD` stores the result in a register, while `VBLS` pushes it back onto the stack.

#### Bit to Unit Vector (B2V)

Converts a single-bit direction mask into an n-dimensional unit vector using the convention: for dimension d, bit 2·d = +1 direction, bit 2·d+1 = −1 direction. **Supports up to n ≤ Config.VALUE_BITS/2 dimensions due to bitmask size limitations.**

* `B2VR %VEC_REG %MASK_REG`, `B2VI %VEC_REG <Mask_Lit>`, `B2VS`
  - Fails if the mask is 0 or contains more than one set bit.
  - Immediate variant uses `DATA` for the literal; stack variant pops mask and pushes the vector.

#### Unit Vector to Bit (V2B)

Converts an n-dimensional unit vector into a single-bit direction mask using the same convention as `B2V`: for dimension d, bit 2·d = +1 direction, bit 2·d+1 = −1 direction. **Supports up to bitmask size limitations.**

* `V2BR %MASK_REG %VEC_REG`, `V2BI %MASK_REG <Vector>`, `V2BS`
  - Fails if the vector is not a unit vector with exactly one non-zero component of magnitude 1.
  - Register variants write a `DATA`-typed mask into `%MASK_REG`; stack variant pops the vector and pushes the mask.

#### Rotate Right in Plane (RTR*)

Performs a 90° clockwise rotation of a vector within the plane defined by two axes. For a vector V and axes i and j, the result V' is:

- V'[i] = V[j]
- V'[j] = -V[i]
- V'[k] = V[k] for all k ≠ i, j

This does not require the vector to be a unit vector.

Failure conditions: i or j out of bounds for the world's dimensionality, or i == j. On failure, the target vector remains unchanged.

Opcodes:

* `RTRR %VEC_IO %AXIS1_REG %AXIS2_REG` (Cost: 1)
  - Rotates the vector in `%VEC_IO`. Reads axes from scalar registers. Writes back into `%VEC_IO`.

* `RTRI %VEC_IO <Axis1_Lit> <Axis2_Lit>` (Cost: 1)
  - Same as `RTRR`, but axis indices are immediate `DATA` literals (e.g., `DATA:0`, `DATA:1`).

* `RTRS` (Cost: 1)
  - Stack order (top to bottom): Axis2, Axis1, Vector. Pops all, pushes rotated vector back.

---

## 7. Compiler Directives

Directives are special commands that instruct the compiler on how to assemble the code. They are not executable instructions for the organism.

### Definitions and Aliases

* `.DEFINE <NAME> <VALUE>`: Creates a simple text substitution. The compiler will replace every occurrence of `<NAME>` with `<VALUE>` before parsing.
* `.REG <%ALIAS> <%REGISTER>`: Assigns a custom name (`<%ALIAS>`) to a register (e.g., `.REG %COUNTER %DR0`).

### Layout Control

* `.ORG <Vector>`: Sets the starting coordinate for the following code. In the main source file, this coordinate is absolute. Inside a file included via `.INCLUDE`, the coordinate is **relative** to the position where the `.INCLUDE` directive was invoked.
* `.DIR <Vector>`: Sets the direction in which the compiler places subsequent instructions. This is always an absolute direction vector. When an included file finishes, the direction is restored to what it was before the include.
* `.PLACE <Literal> <Placement> [, <Placement> ...]`: Places one or more molecules with the specified `<Literal>` value at various coordinates. The coordinates are relative to the current origin (`.ORG`). Multiple placements can be specified on a single line, separated by commas. A `<Placement>` can be one of the following:
  * **Vector Literal**: A standard vector like `10|20` places a single molecule.
  * **Range**: A range like `1..10|20` places molecules along a line.
  * **2D Range**: A range like `1..10|20..30` places molecules in a rectangular area.
  * **Stepped Range**: A range like `10:2:20|5` places molecules at every second position.
  * **Wildcard**: A wildcard `*` can be used to fill an entire dimension. For example, `*|5` places molecules in every cell of row 5. Using a wildcard requires a compilation context (i.e., compiling with `EnvironmentProperties`).

### Macros

* `.MACRO <Name> [PARAM1 ...] / .ENDM`: Defines a macro, a template for code that is expanded inline. Parameters are optional. To invoke a macro, simply use its name followed by the arguments.
    ```
    .MACRO INCREMENT REG_TARGET
      ADDI REG_TARGET DATA:1
    .ENDM
    
    INCREMENT %DR0  # This line expands to "ADDI %DR0 DATA:1"
    ```

### Modules and Procedures

A **module** is a source file (e.g., `lib.s`) containing one or more `.PROC` definitions that can be reused. To create and use modules effectively, you combine `.PROC`, `.REQUIRE`, and `.INCLUDE`.

* `.PROC <Name> [EXPORT] [WITH <PARAM1> ...] / .ENDP`: Defines a procedure, a reusable block of code that can be called via `CALL`. By default, procedures are private to their file. The optional `EXPORT` keyword makes the procedure public, allowing it to be used by other modules.
* `.REQUIRE "<path>" AS <Alias>`: Declares a logical dependency on a module. A library file should use this to declare its own dependencies on other libraries.
* `.INCLUDE "<path>"`: Inlines the content of another source file. The main program file must use this directive to include the source code for all required modules, which gives the main program full control over the physical layout of the code in the environment.
* `.PREG <%ALIAS> <%PROCREGISTER>`: Within a `.PROC` block, assigns an alias to a procedure-local register (`%PR0` or `%PR1`).

#### Example of a Modular Program:

**File 1: `lib/math.s`**
```
# This module provides a simple math function.
.PROC MATH.ADD EXPORT WITH A B  # Make this procedure public
  ADDS
  RET
.ENDP
```

**File 2: `lib/utils.s`**
```
# This module depends on the math library.
.REQUIRE "lib/math.s" AS MATH

.PROC UTILS.INCREMENT_BOTH EXPORT WITH REG1 REG2
  # Use the procedure from the math library
  PUSH %REG1
  PUSI DATA:1
  CALL MATH.ADD
  POP %REG1

  PUSH %REG2
  PUSI DATA:1
  CALL MATH.ADD
  POP %REG2
  
  RET
.ENDP
```

**File 3: `main.s` (The main program)**
```
# 1. Include the source code for all required modules.
#    This places their machine code into the environment.
.ORG 50|0
.INCLUDE "lib/math.s"

.ORG 100|0
.INCLUDE "lib/utils.s"

# 2. Declare the dependency for the main program's code.
.REQUIRE "lib/utils.s" AS UTILS

# 3. Main program logic starts here.
.ORG 0|0
START:
  SETI %DR0 DATA:10
  SETI %DR1 DATA:20
  
  # Call the procedure from the utils library
  CALL UTILS.INCREMENT_BOTH .WITH %DR0 %DR1
  
  # %DR0 is now 11, %DR1 is now 21
  ...
```

### Scopes

* `.SCOPE <Name> / .ENDS`: Defines a named scope. Labels defined inside a scope are only visible within that scope, preventing name collisions.

```
