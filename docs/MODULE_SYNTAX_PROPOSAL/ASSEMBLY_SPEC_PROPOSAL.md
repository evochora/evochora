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
* **Formal Parameter Registers (`%FPRx`)**: A set of 8 internal registers (`%FPR0` to `%FPR7`) used exclusively for parameter passing in procedures. They cannot be accessed directly by name in the code.
* **Location Registers (`%LRx`)**: A set of 4 registers (`%LR0` to `%LR3`) for storing vector values (coordinates or direction vectors).

### Stacks

The VM includes three distinct stacks to manage data and control flow.

* **Data Stack (`DS`)**: A last-in, first-out (LIFO) stack for temporary data storage. It is used by `PUSH`/`POP` instructions and the stack-based variants of arithmetic and logic instructions (e.g., `ADDS`).
* **Location Stack (`LS`)**: A dedicated LIFO stack for storing vector values, separate from the `DS`.
* **Call Stack (`CS`)**: This stack is managed automatically by `CALL` and `RET` instructions. It stores return addresses and the state of the `%PRx` registers, enabling structured programming with nested procedure calls.

### Energy (ER)

The Energy Register (`ER`) holds the organism's life force. Energy is the central resource that governs an organism's ability to act and survive. This system is designed to create evolutionary pressure, rewarding efficient and well-adapted behaviors.

* **Instruction Cost**: Every instruction an organism executes has a base energy cost, which is deducted from the `ER`.
* **Action-Specific Costs**: More complex actions have additional costs.
* **Error Penalty**: An invalid operation will cause the instruction to fail and incur an additional energy penalty.
* **Gaining Energy**: Organisms can replenish their energy by consuming `ENERGY` molecules from the environment.
* **Death**: If an organism's `ER` drops to zero or below, it dies.

---

## 4. Basic Syntax

The syntax of Evochora assembly is designed to be simple and readable.

### Statement Structure

A typical line of code consists of an optional label, followed by an instruction or a directive, and then its arguments, separated by whitespace. Each statement must end with a newline.

`OPCODE ARGUMENT1 ARGUMENT2  # Comment`

### Comments

Any text following a hash symbol (`#`) is a comment.

### Labels

A label is a name followed by a colon (`:`) and marks a location for jump and call instructions.

```assembly
START_LOOP:
  ADDI %DR0 DATA:1
  JMPI START_LOOP