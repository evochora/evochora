# Evochora Assembly: Language Reference

## 1. Introduction & Overview

Evochora is a digital ecosystem designed to simulate the evolution of simple, programmable organisms. At its core, it provides a playground for exploring concepts of artificial life, emergent behavior, and evolutionary strategies. The simulation takes place in a customizable, n-dimensional world where organisms must compete for resources, primarily energy and computing time, to survive and reproduce. Each organism is an autonomous agent controlled by a program written in a custom assembly language. This language is the key to defining an organism's behavior—from basic movement and environmental interaction to complex decision-making and replication strategies.

This document serves as the official reference for the Evochora assembly language. It provides the necessary details to understand the virtual machine that powers each organism, the syntax of the language, and the full instruction set available for programming their actions and logic. Whether you are designing a simple energy-seeker or a complex replicating creature, this guide contains the information needed to bring your digital life-forms into existence.

---

## 2. The Simulation Environment

The world of Evochora is a discrete, n-dimensional grid where all interactions take place. The size and properties of this grid are defined in the simulation's configuration.

### The Grid

The environment is structured as a grid with configurable dimensions. Each point on this grid, identified by its coordinates, can hold a single "Molecule".

### Toroidal Space

The world is toroidal, meaning it wraps around at the edges. An organism moving past the right edge will reappear on the left, and one moving past the top will reappear on the bottom. This creates a continuous space without boundaries.

### Molecules

Every cell in the grid contains a **Molecule**, which is the fundamental unit of information and matter. A molecule has two properties: a **type** and a **value**. The type determines its function and how organisms interact with it. There are four primary types:

* **`CODE`**: Represents an executable instruction for an organism's virtual machine.

* **`DATA`**: Represents a generic data value that can be manipulated by instructions. These values can also be arguments for instructions.

* **`ENERGY`**: A resource that organisms can consume to replenish their own energy reserves (ER).

* **`STRUCTURE`**: Represents physical matter, like the body of an organism.

### Ownership

Any grid cell can be "owned" by an organism. This ownership is tracked separately from the molecule in the cell and is crucial for certain instructions like `SEEK` or `PEEK`, which behave differently depending on whether the target cell is owned by the acting organism, a foreign organism, or is unowned.

---

## 3. The Organism's Virtual Machine

Each organism operates like a simple computer, equipped with its own set of registers, pointers, and stacks. This internal architecture, or virtual machine (VM), executes the machine code that dictates the organism's state and actions. This machine code, stored as `CODE` and `DATA` molecules in the environment's grid, can be written by the user in the Evochora assembly language.

### Pointers

Pointers are special-purpose registers that define an organism's position and orientation in the world.

* **Instruction Pointer (`IP`)**: An absolute coordinate in the grid that points to the next `CODE` molecule the organism will execute.

* **Data Pointer (`DP`)**: An absolute coordinate used as the base for all world-interaction instructions like `PEEK`, `POKE`, `SCAN`, and `SEEK`. It can be moved independently of the `IP`.

* **Direction Vector (`DV`)**: A unit vector that determines the direction in which the `IP` advances after executing an instruction.

### Registers

Registers are the primary working memory of the organism. They can hold either scalar values (any molecule type like `CODE`, `DATA`, `ENERGY`, or `STRUCTURE`) or vector values (e.g., `1|0`).

* **Global Data Registers (`%DRx`)**: A set of 8 general-purpose registers (`%DR0` to `%DR7`) for storing and manipulating data. Their values persist across procedure calls.

* **Procedure-Local Registers (`%PRx`)**: A set of 2 temporary registers (`%PR0`, `%PR1`) intended for use within a procedure. Their values are automatically saved to the Call Stack upon a `CALL` instruction and restored upon `RET`, making them safe to use without interfering with the caller's state.

* **Formal Parameter Registers (`%FPRx`)**: A set of 8 internal registers (`%FPR0` to `%FPR7`) used exclusively for parameter passing in procedures defined with a `.WITH` clause. They cannot be accessed directly by name in the code.

### Stacks

The VM includes two distinct stacks to manage data and control flow.

* **Data Stack (`DS`)**: A last-in, first-out (LIFO) stack for temporary data storage. It is used by `PUSH`/`POP` instructions and the stack-based variants of arithmetic and logic instructions (e.g., `ADDS`).

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

* `DUP`: Duplicates the top value on the Data Stack. (Cost: 0)

* `SWAP`: Swaps the top two values on the Data Stack. (Cost: 0)

* `DROP`: Discards the top value on the Data Stack. (Cost: 0)

* `ROT`: Rotates the top three values on the Data Stack. (Cost: 0)

### Arithmetic Operations

These instructions operate on scalar `DATA` values. `ADD` and `SUB` also support component-wise vector arithmetic.

* `ADDR %REG1 %REG2`, `ADDI %REG1 <Literal>`, `ADDS`: Performs addition. (Cost: 1)

* `SUBR %REG1 %REG2`, `SUBI %REG1 <Literal>`, `SUBS`: Performs subtraction. (Cost: 1)

* `MULR %REG1 %REG2`, `MULI %REG1 <Literal>`, `MULS`: Performs multiplication (scalars only). (Cost: 1)

* `DIVR %REG1 %REG2`, `DIVI %REG1 <Literal>`, `DIVS`: Performs division (scalars only). (Cost: 1)

* `MODR %REG1 %REG2`, `MODI %REG1 <Literal>`, `MODS`: Performs modulo (scalars only). (Cost: 1)

### Bitwise Operations

These instructions operate on the integer value of scalars.

* `ANDR %REG1 %REG2`, `ANDI %REG1 <Literal>`, `ANDS`: Bitwise AND. (Cost: 1)

* `ORR %REG1 %REG2`, `ORI %REG1 <Literal>`, `ORS`: Bitwise OR. (Cost: 1)

* `XORR %REG1 %REG2`, `XORI %REG1 <Literal>`, `XORS`: Bitwise XOR. (Cost: 1)

* `NADR %REG1 %REG2`, `NADI %REG1 <Literal>`, `NADS`: Bitwise NAND. (Cost: 1)

* `NOT %REG`, `NOTS`: Bitwise NOT. (Cost: 1)

* `SHLI %REG <Literal>`, `SHLS`: Logical shift left. (Cost: 1)

* `SHRI %REG <Literal>`, `SHRS`: Logical shift right. (Cost: 1)

### Control Flow

* `JMPI <Label>`: Jumps to `<Label>`. (Cost: 1)

* `JMPR %VEC_REG>`: Jumps to the vector address in `<%VEC_REG>`. (Cost: 1)

* `JMPS`: Jumps to the vector address popped from the stack. (Cost: 1)

* `CALL <Label>`: Calls the procedure at `<Label>`. (Cost: 2)

* `RET`: Returns from a procedure. (Cost: 2)

### Conditional Instructions

These instructions skip the next instruction if the condition is false.

* `IFR %REG1 %REG2`, `IFI %REG1 <Literal>`, `IFS`: If values are equal. (Cost: 1)

* `LTR %REG1 %REG2`, `LTI %REG1 <Literal>`, `LTS`: If value of first argument is less than second. (Cost: 1)

* `GTR %REG1 %REG2`, `GTI %REG1 <Literal>`, `GTS`: If value of first argument is greater than second. (Cost: 1)

* `IFTR %REG1 %REG2`, `IFTI %REG1 <Literal>`, `IFTS`: If molecule types are equal. (Cost: 1)

* `IFMR %VEC_REG`, `IFMI <Vector>`, `IFMS`: If cell at `DP` + vector is owned by self. The vector must be a unit vector. (Cost: 1)

### World Interaction

These instructions interact with the environment grid relative to the Data Pointer (`DP`). The vector argument must be a unit vector, meaning these instructions can only target adjacent cells.

* `PEEK %DEST_REG %VEC_REG`, `PEKI %DEST_REG <Vector>`, `PEKS`: Reads and consumes molecule at `DP` + vector. If the molecule is `ENERGY`, its value is added to the organism's `ER`. For other types (like `DATA` or foreign `STRUCTURE`), its value is subtracted as an additional cost. (Base Cost: 2)

* `SCAN %DEST_REG %VEC_REG`, `SCNI %DEST_REG <Vector>`, `SCNS`: Reads molecule at `DP` + vector without consuming it. (Cost: 2)

* `POKE %SRC_REG %VEC_REG`, `POKI %SRC_REG <Vector>`, `POKS`: Writes molecule from `<%SRC_REG>` or stack to empty cell at `DP` + vector. (Cost: 5 + value)

* `SEEK %VEC_REG`, `SEKI <Vector>`, `SEKS`: Moves `DP` by vector if target cell is empty or owned by self. (Cost: 3)

### State Operations

* `NOP`: No operation. (Cost: 1)

* `SYNC`: Sets `DP` = `IP`. (Cost: 1)

* `TURN %VEC_REG`: Sets `DV` to the vector in `<%VEC_REG>`. The instruction will fail if the vector is not a unit vector. (Cost: 1)

* `POS %REG`: Stores the organism's relative position in `<%REG>`. (Cost: 1)

* `DIFF %REG`: Stores the vector `DP` - `IP` in `<%REG>`. (Cost: 1)

* `NRG %REG`, `NRGS`: Stores current `ER` in `<%REG>` or on the stack. (Cost: 0)

* `RAND %REG`: Stores a random number [0, `<%REG>`) back into `<%REG>`. (Cost: 2)

* `FORK %DP_VEC_REG %NRG_REG %DV_VEC_REG`: Creates a child at `DP` + `<%DP_VEC_REG>` with a starting direction from `<%DV_VEC_REG>`. The direction vector must be a unit vector. The child's starting energy is taken from `<%NRG_REG>`, and this amount is deducted from the parent's `ER`. (Cost: 10 + energy)

---

## 7. Compiler Directives

Directives are special commands that instruct the compiler on how to assemble the code. They are not executable instructions for the organism.

### Definitions and Aliases

* `.DEFINE <NAME> <VALUE>`: Creates a simple text substitution. The compiler will replace every occurrence of `<NAME>` with `<VALUE>` before parsing.

* `.REG <ALIAS> <REGISTER>`: Assigns a custom name (`<ALIAS>`) to a register (e.g., `.REG %COUNTER %DR0`).

### Layout Control

* `.ORG <Vector>`: Sets the absolute starting coordinate for the following code or data.

* `.DIR <Vector>`: Sets the direction in which the compiler places subsequent instructions and arguments in the grid.

* `.PLACE <Literal> <Vector>`: Places a molecule with the specified `<Literal>` value at a coordinate relative to the current origin.

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

* `.PREG <%ALIAS> <INDEX>`: Within a `.PROC` block, assigns an alias to a procedure-local register (`%PR0` or `%PR1`).

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
