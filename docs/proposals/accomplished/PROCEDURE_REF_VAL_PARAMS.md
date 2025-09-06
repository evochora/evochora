## Task: Implement REF/VAL Parameter System in Compiler
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
    - Most compiler phases have a handler or plugin system
    - Please keep the main class of every compiler phase as lean as possible, it should only be used to delegate as much as possible to to its handlers / plugins.
    - You can find the handler / plugin system of each phase in its sub-package.
    - Please add a new handler / plugin for the new parameter system, and do not put logic into the main class of a compiler phase if it can be avoided.

6) Fix all tests that are broken by the new syntax. All test must pass after the update.

7) Update ASSEMBLY_SPEC.me to document new syntax and remove old syntax.
