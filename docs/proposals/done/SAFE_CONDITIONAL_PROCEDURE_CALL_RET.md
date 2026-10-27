## Task: Implement Safe Conditional Procedure Calls and Returns

Objective: Fix the logical error where a conditional instruction preceding a CALL or RET only affects the first machine instruction of the generated sequence (e.g., a PUSH), instead of the entire operation.**Requirements:**1) **Conditional `CALL` Handling (`CallerMarshallingRule.java`):**

- The emission rule must inspect the IR item immediately preceding a `CALL`.
- If the preceding item is a conditional instruction (e.g., `IFR`), the compiler must transform its output as follows:
    1. Do **not** emit the original conditional instruction.
    2. Instead, emit its **negated** counterpart (e.g., `INR`). The compiler must be able to resolve the negated mnemonic for every conditional instruction.
    3. Emit a `JMPI` instruction that targets a new, unique label.
    4. Emit the entire standard marshalling sequence for the `CALL` (`PUSH`/`PUSI`, `CALL`, `POP`/`DROP`).
    5. Emit the unique label from step 3 immediately after the sequence.

Negated conditional instructions:
- `IFR`  <-> `INR`
- `IFS`  <-> `INS`
- `LTR`  <-> `GETR`
- `LTI`  <-> `GETI`
- `LTS`  <-> `GETS`
- `GTR`  <-> `LETR`
- `GTI`  <-> `LETI`
- `GTS`  <-> `LETS`
- `IFTR` <-> `INTR`
- `IFTI` <-> `INTI`
- `IFTS` <-> `INTS`
- `IFMR` <-> `INMR`
- `IFMI` <-> `INMI`
- `IFMS` <-> `INMS`

2) **Conditional `RET` Handling (`ProcedureMarshallingRule.java`):**
    - Apply the same transformation logic for `RET` instructions within a procedure.
    - If a `RET` is preceded by a conditional, the compiler must use the corresponding negated conditional and a `JMPI` to jump over the entire epilog sequence (`PUSH` for `REF` params) and the `RET` itself.

   Example Transformation:
   Original Code:
   ```
   IFR %DR0 %DR1
   CALL MY_PROC REF %DR2
   ```

   Generated Machine Code:
   ```
   INR %DR0 %DR1 # Note: Negated instruction
   JMPI L_SKIP_CALL_1
   PUSH %DR2
   CALL MY_PROC
   POP %DR2
   L_SKIP_CALL_1:
   ```

3) **Tests:**
    - Add tests to check the to verify the behavior of the changes.
    - All tests must pass after the update.