# Task: Remove Legacy 'WITH' Syntax (Step 8b)

**Objective:** Complete the procedure parameter refactoring by removing all remaining support for the old `WITH` syntax from the compiler's Java code. Additionally, all tests previously marked as "legacy-syntax" will be deleted.**Prerequisites - Assumed Code State:** It is assumed that all assembly files (`.s`) and all general logic tests have already been migrated to the new `REF`/`VAL` syntax. The only remaining uses of the `WITH` syntax are in unit tests marked with the `@Tag("legacy-syntax")` annotation. The documentation already marks the `WITH` syntax as deprecated. The compiler's Java code still supports both syntaxes at the start of this task.**Constraint Checklist:*** After this change, any use of the `WITH` keyword must result in a compilation error.

* All tests marked with `@Tag("legacy-syntax")` must be deleted.

* The resulting code must be cleaner and less complex.

* The system must be fully compilable, and all remaining tests must pass.

### 1. Remove Legacy Parser Logic

**File:** `src/main/java/org/evochora/compiler/frontend/parser/features/proc/ProcDirectiveHandler.java`* **Action:** Remove the logic that parses the `WITH` keyword and its parameters. Delete the `if/else if` block that checks for `context.peek().text().equalsIgnoreCase("WITH")`.**File:** `src/main/java/org/evochora/compiler/frontend/parser/Parser.java`* **Action:** In the private helper method for parsing `CALL` instructions, remove the code path that handles arguments without `REF` or `VAL` (the old `WITH` logic).

### 2. Clean Up Internal Representations (AST & IR)

**File:** `src/main/java/org/evochora/compiler/frontend/parser/features/proc/ProcedureNode.java`* **Action:** Remove the `List<Token> parameters` field. Update the constructor accordingly.**File:** `src/main/java/org/evochora/compiler/ir/IrInstruction.java`* **Action:** Remove the logic that populated the general `operands` list with parameters for a `CALL` IR instruction.

### 3. Clean Up Semantic Analysis

**File:** `src/main/java/org/evochora/compiler/frontend/semantics/analysis/InstructionAnalysisHandler.java`* **Action:** Remove the semantic analysis logic for the old `CALL ... WITH ...` syntax.

### 4. Delete Marked Tests

**Action:** Search the entire test suite for tests annotated with `@Tag("legacy-syntax")` and delete these test methods completely.

### 5. Finalize Documentation

**Action:** Remove all remaining mentions of the deprecated syntax from the documentation.**File 1:** `ASSEMBLY_SPEC.md`* Delete the entire "Deprecated Syntax" section. The documentation should now only describe the `REF`/`VAL` syntax.**File 2:** `COMPILER_IR_SPEC.md`* Remove the note about the deprecated structure of the `CALL` instruction. The specification should only document the final structure with `refArguments` and `valArguments`.
