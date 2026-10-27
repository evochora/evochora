## Task: Extend the Instruction Set with Negated Conditionals
Objective: Implement a set of 14 negated conditional instructions to make the instruction set more orthogonal and to serve as a foundation for fixing bugs related to conditional procedure calls.
**Requirements:**
1) **Create New Instructions:** For each of the existing 14 `ConditionalInstruction` types, create a corresponding negated instruction. The new instruction's logic must be the exact opposite of the original. When the original instruction would set the `skipNextInstruction` flag to `true`, the negated version must set it to `false`, and vice-versa.

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
    - All test must pass after the update.
    - Update ASSEMBLY_SPEC.me to include the new instructions.