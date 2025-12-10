# Task: Migrate Project to REF/VAL Syntax and Mark Legacy Tests (Step 8a)

**Objective:** Prepare the entire codebase for the final removal of the old `WITH` syntax. This is the first of a two-part finalization process. In this step, all assembly files and the majority of tests will be migrated to the new `REF`/`VAL` syntax. The compiler's ability to parse the old syntax will be temporarily retained to ensure stability. Tests or part of tests that specifically validate the old syntax will be marked for later removal.**Prerequisites - Assumed Code State:** It is assumed that at the start of this task, the compiler fully supports both the old `WITH` syntax and the new `REF`/`VAL` syntax. All tests for both systems are passing.**Constraint Checklist:*** The compiler's Java code for parsing and analyzing the `WITH` syntax must NOT be removed in this step.

* All assembly files (`.evo`) in the project must be migrated.

* The majority of tests must be migrated.

* Tests that become redundant must be clearly marked with the JUnit `@Tag("legacy-syntax")` annotation

* The system must be fully compilable, and all tests (new, migrated, and marked) must pass at the end of this step.

### 1. Migrate Assembly Source Files

**Action:** Update all assembly (`.evo`) files within the project to exclusively use the new `REF`/`VAL` syntax.* **Path to check:** `src/main/resources/org/evochora/organism/prototypes/`

* Search all `.evo` files in this directory and its subdirectories.

* Convert every instance of `.PROC ... WITH ...` and `CALL ... WITH ...` to the new syntax.

* **Conversion Guideline:** Since the old `WITH` syntax behaved as call-by-reference, convert all parameters to `REF`.

    - **Old:** `.PROC myProc WITH p1 p2`

    - **New:** `.PROC myProc REF p1 p2`

### 2. Migrate and Mark Tests

**Action:** Review the entire test suite and separate general logic tests from legacy-specific tests.* **Review all tests** that currently use the `WITH` syntax (e.g., in `ProcedureDirectiveTest.java`, `SemanticAnalyzerTest.java`, `CompilerEndToEndTest.java`).

* **Migrate General Tests:** If a test case covers a unique and important scenario (e.g., a specific error condition, a complex interaction) but happens to use the old `WITH` syntax, **migrate the test** to use the new `REF`/`VAL` syntax.

* **Mark Legacy-Specific Tests:** If a test's _only purpose_ is to validate the parsing or semantic analysis of the `WITH` syntax itself, and it would be redundant after a `REF`/`VAL` test is in place, do the following:

    1. **Do NOT delete the test.**

    2. Add the JUnit 5 annotation `@Tag("legacy-syntax")` directly above the test method. This will allow us to easily find and delete these tests in the next step.

  **Example:**

      import org.junit.jupiter.api.Tag;
      import org.junit.jupiter.api.Test;

      // ...

      @Test
      @Tag("legacy-syntax")
      void parsingWithDirectiveShouldSucceed() {
          // ... test code that uses "WITH" ...
      }
=======

### 3. Update Documentation

**Action:** Update the official language and compiler specifications to establish `REF`/`VAL` as the standard and mark `WITH` as deprecated.**File 1:** `ASSEMBLY_SPEC.md`* Move all documentation for `.PROC ... WITH ...` and `CALL ... WITH ...` to a new section at the end titled "Deprecated Syntax".

* Add a clear note in this new section that this syntax is outdated and will be removed in a future version.

* Ensure the main documentation body exclusively describes the `REF`/`VAL` system as the correct method for passing parameters.

* Add examples for REF and for VAL**File 2:** `COMPILER_IR_SPEC.md`* Update the specification for the `IrInstruction` for `CALL` to reflect the new structure with `refArguments` and `valArguments` as the primary, documented method.

* Add a note that the old structure (using the general `operands` list) is deprecated.
