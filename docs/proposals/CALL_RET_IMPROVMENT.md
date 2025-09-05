# Implementation Tasks for evochora Compiler and Runtime
This document outlines three distinct, sequential tasks to enhance the evochora compiler and runtime.

## Task 1: Extend the Instruction Set with Negated Conditionals
Objective: Implement a set of 14 negated conditional instructions to make the instruction set more orthogonal and to serve as a foundation for fixing bugs related to conditional procedure calls.**Requirements:**1) **Create New Instructions:** For each of the existing 14 `ConditionalInstruction` types, create a corresponding negated instruction. The new instruction's logic must be the exact opposite of the original. When the original instruction would set the `skipNextInstruction` flag to `true`, the negated version must set it to `false`, and vice-versa.

2) **Naming Convention:** Use the following mapping for the new instruction mnemonics:

    - `IFR`  -> `INR`  (If Not Equal Register)
    - `IFS`  -> `INS`  (If Not Equal Stack)
    - `LTR`  -> `GETR` (Less Than Register, Negated -> Greater or Equal)
    - `LTI`  -> `GETI` (Less Than Immediate, Negated -> Greater or Equal)
    - `LTS`  -> `GETS` (Less Than Stack, Negated -> Greater or Equal)
    - `GTR`  -> `LETR` (Greater Than Register, Negated -> Less or Equal)
    - `GTI`  -> `LETI` (Greater Than Immediate, Negated -> Less or Equal)
    - `GTS`  -> `LETS` (Greater Than Stack, Negated -> Less or Equal)
    - `IFTR` -> `INTR` (If Type Register, Negated -> If Not Type)
    - `IFTI` -> `INTI` (If Type Immediate, Negated -> If Not Type)
    - `IFTS` -> `INTS` (If Type Stack, Negated -> If Not Type)
    - `IFMR` -> `INMR` (If Mine Register, Negated -> If Not Mine)
    - `IFMI` -> `INMI` (If Mine Immediate, Negated -> If Not Mine)
    - `IFMS` -> `INMS` (If Mine Stack, Negated -> If Not Mine)

3) **Implementation Details:**
    - Implement these instructions within the `org.evochora.runtime.isa.instructions.ConditionalInstruction` class and register them in the static `init()` method of its super class with a unique ID.
    - Ensure each new instruction is fully integrated and functional within the VM.
    - Add corresponding tests to `VMConditionalInstructionTest.java` to verify the logic of each new negated instruction.

## Task 2: Implement REF/VAL Parameter System in Compiler
Objective: Overhaul the procedure parameter system to support call-by-reference (REF) and call-by-value (VAL) semantics, allowing literals to be passed as parameters and making the programmer's intent explicit. Support only the new syntax that is described here, the support of the old syntax (WITH) must be removed from the compiler.**Requirements:**1) **Syntax Update (Lexer & Parser):**

- Introduce two new keywords: `REF` and `VAL`.
- Update the `.PROC` directive syntax to: `.PROC <name> [REF <p1> <p2>...] [VAL <p3> <p4>...]`
- Update the `CALL` instruction syntax to: `CALL <name> [REF <a1> <a2>...] [VAL <a3> <a4>...]`

- **Syntax Rules:**
    - The `REF` and `VAL` parameter blocks are both optional. A procedure can have none, one, or both.
    - The order of `REF` and `VAL` blocks is flexible.
    - Each keyword (`REF`, `VAL`) may appear at most once per definition or call.

2) **Semantic Analysis:**
    - Enforce all syntax rules defined above.
    - Verify that all arguments passed to a `REF` parameter are registers.
    - Allow both literals and registers to be passed to a `VAL` parameter.
    - Ensure that the parameter lists at the call site match the procedure definition in terms of count, and modes (`REF`/`VAL`).

3) **Code Generation (Marshalling Logic):**
    - **Caller Side (`CallerMarshallingRule.java`):**
        - **Before `CALL`:** For each parameter, generate `PUSI` for `VAL` literals, and `PUSH` for `VAL` registers and `REF` registers.
        - **After `CALL`:** For each `REF` parameter, generate a `POP` into the original argument register. For each `VAL` parameter, generate a `DROP`.

    - **Callee Side (`ProcedureMarshallingRule.java`):**
        - **Prolog:** At the beginning of the procedure, generate a `POP` for _every_ incoming parameter into its assigned formal parameter register (`%FPRx`).
        - **Epilog:** Before the `RET` instruction, generate a `PUSH` for _only_ the `REF` parameters from their `%FPRx`.
    - **Procedure Body:** Inside the procedure body, all parameters are treated as standard registers (`%FPRx`) regardless of their `REF` or `VAL` mode.

4) **Handler / Plugin registration system:**
    - Most compiler phases have an handler or plugin system
    - Please keep the main class of every compiler phase as lean as possible, it should only be used to delegate as much as possible to to its handlers / plugins.
    - You can finde the handler / plugin system in of each phase in its sub-package.
    - Please add a new handler / plugin for the new parameter system, and do not put logic into the main class of a compiler phase if it can be avoided.

## Task 3: Implement Safe Conditional Procedure Calls and Returns

Objective: Fix the logical error where a conditional instruction preceding a CALL or RET only affects the first machine instruction of the generated sequence (e.g., a PUSH), instead of the entire operation.**Requirements:**1) **Conditional `CALL` Handling (`CallerMarshallingRule.java`):**

- The emission rule must inspect the IR item immediately preceding a `CALL`.
- If the preceding item is a conditional instruction (e.g., `IFR`), the compiler must transform its output as follows:
    1. Do **not** emit the original conditional instruction.
    2. Instead, emit its **negated** counterpart (e.g., `INR`). The compiler must be able to resolve the negated mnemonic for every conditional instruction.
    3. Emit a `JMPI` instruction that targets a new, unique label.
    4. Emit the entire standard marshalling sequence for the `CALL` (`PUSH`/`PUSI`, `CALL`, `POP`/`DROP`).
    5. Emit the unique label from step 3 immediately after the sequence.

2) **Conditional `RET` Handling (`ProcedureMarshallingRule.java`):**
    - Apply the same transformation logic for `RET` instructions within a procedure.
    - If a `RET` is preceded by a conditional, the compiler must use the corresponding negated conditional and a `JMPI` to jump over the entire epilog sequence (`PUSH` for `REF` params) and the `RET` itself.
    
   Example Transformation:
   Original Code:
   ```
   IFR %DR0 %DR1
   CALL MY_PROC REF %DR2
   ```
   
3) Generated Machine Code:
   ```
   INR %DR0 %DR1 # Note: Negated instruction
   JMPI L_SKIP_CALL_1
   PUSH %DR2
   CALL MY_PROC
   POP %DR2
   L_SKIP_CALL_1:
   ```