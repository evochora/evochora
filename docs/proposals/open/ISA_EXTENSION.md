Adapted Specification for New ISA Instructions (v2)
This document specifies the behavior and implementation details for new instructions in the PEEK, POKE, SCAN, PPKR, SEEK, DEL, and OVWR families.

1. General Behavior & Rules
   The instructions interact with molecules at a target coordinate in the environment. The target coordinate is calculated by adding a vector to the organism's current Active Data Pointer (DP).

Unity Vector Requirement: All instructions that use a vector to calculate a target coordinate must validate that the vector is a "unity vector" (a vector where only one component is non-zero and its value is either 1 or -1). If the vector is not a unity vector, the instruction must immediately fail by calling organism.instructionFailed() with a descriptive message and abort its execution.

Instruction Families:

PEEK-like: Reads and consumes (removes) the molecule from the target cell. Fails if the cell is empty. 
POKE-like: Writes a molecule to the target cell. Fails if the cell is not empty.
SCAN-like: Reads a molecule from the target cell without consuming it (read-only).
PPKR-like: A combined, consuming PEEK followed by a POKE at the same target coordinate.
SEEK-like: Moves the Active DP to the target coordinate. Fails if the target cell is occupied by a foreign organism.
DEL-like: Consumes (removes) the molecule from the target cell without reading it. Does not fail on empty cells.
OVWR-like: Writes a molecule to the target cell, overwriting any existing molecule.
2. Instruction Definitions
   The operand order for all new instructions is <source_1>, <source_2>.

PEEK, POKE, PPKR, DEL, OVWR Families (in EnvironmentInteractionInstruction.java)

PERR <vec_reg> <dest_reg>: Peek using vector from <vec_reg>, result to <dest_reg>.
PERS <vec_reg> <dest_stack>: Peek using vector from <vec_reg>, result to data stack.
PERI <vec_imm> <dest_reg>: Peek using immediate vector, result to <dest_reg>.
PESR <vec_stack> <dest_reg>: Peek using vector from stack, result to <dest_reg>.
PESS <vec_stack> <dest_stack>: Peek using vector from stack, result to data stack.
PESI <vec_imm> <dest_stack>: Peek using immediate vector, result to data stack.
PORR <source_reg> <vec_reg>: Poke from <source_reg> using vector from <vec_reg>.
PORS <source_reg> <vec_stack>: Poke from <source_reg> using vector from stack.
PORI <source_reg> <vec_imm>: Poke from <source_reg> using immediate vector.
POSR <source_stack> <vec_reg>: Poke from stack using vector from <vec_reg>.
POSS <source_stack> <vec_stack>: Poke from stack using vector from stack.
POSI <source_stack> <vec_imm>: Poke from stack using immediate vector.
POIR <source_imm> <vec_reg>: Poke immediate value using vector from <vec_reg>.
POIS <source_imm> <vec_stack>: Poke immediate value using vector from stack.
POII <source_imm> <vec_imm>: Poke immediate value using immediate vector.
PPRR <reg> <vec_reg>: Peek from target (specified by <vec_reg>) into <reg>, then Poke value originally from <reg> to target.
PPRS <reg> <vec_stack>: Peek from target (specified by <vec_stack>) into <reg>, then Poke value originally from <reg> to target.
PPRI <reg> <vec_imm>: Peek from target (specified by <vec_imm>) into <reg>, then Poke value originally from <reg> to target.
PPSR <stack> <vec_reg>: Peek from target (specified by <vec_reg>) onto stack, then Poke value originally from stack to target.
PPSS <stack> <vec_stack>: Peek from target (specified by <vec_stack>) onto stack, then Poke value originally from stack to target.
PPSI <stack> <vec_imm>: Peek from target (specified by <vec_imm>) onto stack, then Poke value originally from stack to target.
DELR <vec_reg>: Delete molecule at coordinate specified by vector in <vec_reg>.
DELS <vec_stack>: Delete molecule at coordinate specified by vector from stack.
DELI <vec_imm>: Delete molecule at coordinate specified by immediate vector.
OVWR <source_reg> <vec_reg>: Overwrite cell at vector <vec_reg> with value from <source_reg>.
OVWS <source_stack> <vec_stack>: Overwrite cell at vector from stack with value from stack.
OVWI <source_reg> <vec_imm>: Overwrite cell at immediate vector with value from <source_reg>.

SCAN and SEEK Families (in StateInstruction.java)

SCRR <vec_reg> <dest_reg>: Scan using vector from <vec_reg>, result to <dest_reg>.
SCRS <vec_reg> <dest_stack>: Scan using vector from <vec_reg>, result to data stack.
SCRI <vec_reg> <dest_reg>: Scan using vector from <vec_reg>, result to <dest_reg>. (The immediate operand is unused).
SCSR <vec_stack> <dest_reg>: Scan using vector from stack, result to <dest_reg>.
SCSS <vec_stack> <dest_stack>: Scan using vector from stack, result to data stack.
SCSI <vec_stack> <dest_stack>: Scan using vector from stack, result to data stack. (The immediate operand is unused).
SEKR <vec_reg>: Move Active DP using vector from <vec_reg>.

3. Implementation & Testing Clarifications
   Implementation: Logic should be added to the appropriate handler methods (e.g., handlePeek, handlePoke, handleScan, handleSeek) or new handlers can be created for clarity.
   Compiler Tests: For each new instruction, add a line to the appropriate test in the compiler/instructions package (e.g., EnvironmentInteractionInstructionCompilerTest.java) to ensure it compiles successfully. This validates its registration in Instruction.java.
   Runtime Tests:
   For each new instruction, add specific unit tests to the appropriate test file in the runtime/instructions package (e.g., VMEnvironmentInteractionInstructionTest.java).
   Test Structure: Each test should set up the environment and organism state, execute a single instruction via sim.tick(), and then assert all expected outcomes (e.g., changes to registers, stack, environment cells, and organism energy).
   Test Coverage: Create tests for success cases, failure cases (e.g., POKE on an occupied cell), and edge cases (e.g., PEEK on an empty cell).
   Vector Tests: Explicitly test the unity-vector requirement by creating tests that pass a non-unity vector to relevant instructions and assert that the instruction fails as expected.
   placeInstruction Helper: The test helper method placeInstruction in test files must be corrected. Immediate arguments that represent a full molecule's integer value must be placed with Molecule.fromInt(arg) and not new Molecule(Config.TYPE_DATA, arg) to avoid a "double-packing" bug that corrupts the molecule's type.
   This comprehensive specification should provide a solid foundation for completing the task.