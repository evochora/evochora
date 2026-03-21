# Register Bank Extension Proposal

## Motivation

The current register architecture has an asymmetry: Data Registers (DR) have proc-local (PR) and formal parameter (FPR) variants, but Location Registers (LR) do not. Organisms cannot pass positions as procedure parameters, cannot have proc-local location scratch registers, and cannot maintain persistent location state across procedure calls. Additionally, there is no mechanism for any register type to persist state across calls — procedures are purely stateless.

This proposal extends the register architecture to full parity between data and location registers, introduces persistent state registers, and establishes consistent naming across all banks.

## Current Architecture

| Bank | Prefix | Base ID | Count | Type | Scope | Saved on CALL |
|------|--------|---------|-------|------|-------|---------------|
| Data | %DR | 0 | 8 | Scalar | Global | No |
| Procedure | %PR | 1000 | 8 | Scalar | Per Call | Yes (stack) |
| Formal Param | %FPR | 2000 | 8 | Scalar | Per Call | Yes (binding) |
| Location | %LR | 3000 | 4 | Vector | Global | No |

**Problems:**
- No proc-local location registers (cannot do local position work without clobbering caller's LRs)
- No location parameter passing (cannot pass positions to procedures)
- No persistent state across calls (all proc-local state is lost on RET)
- Inconsistent naming: PR/FPR don't follow a pattern that extends to location variants
- `.PREG` is a separate directive when `.REG` could handle all banks

## Target Architecture

Consistent naming scheme: `[Scope][Type]R` where Scope ∈ {∅, P, F, S} and Type ∈ {D, L}.

| Bank | Prefix | Base ID | Count | Type | Scope | Saved on CALL | New? |
|------|--------|---------|-------|------|-------|---------------|------|
| Data | %DR | 0 | 8 | Scalar | Global | No | — |
| Location | %LR | 3000 | 4 | Vector | Global | No | — |
| Proc Data | %PDR | 1000 | 8 | Scalar | Per Call | Yes (stack) | Rename from %PR |
| Proc Location | %PLR | 4000 | 4 | Vector | Per Call | Yes (stack) | **NEW** |
| Formal Data | %FDR | 2000 | 8 | Scalar | Per Call | Yes (binding) | Rename from %FPR |
| Formal Location | %FLR | 5000 | 4 | Vector | Per Call | Yes (binding) | **NEW** |
| Static Data | %SDR | 6000 | 8 | Scalar | Per Procedure | Persistent | **NEW** |
| Static Location | %SLR | 7000 | 4 | Vector | Per Procedure | Persistent | **NEW** |

**No backward compatibility** is maintained — neither for assembly files nor for serialized simulation data (Protobuf, H2 database). Old simulations cannot be loaded with new code. Backward compatibility must never lead to design compromises. Protobuf field names are renamed freely (Phase A). New Protobuf fields use new field numbers.

### Key Properties

**Location Register Write Restriction:** All location banks (LR, PLR, FLR, SLR) enforce the same constraint as LR today — they can only receive values from other location registers or from the DP (via DPLR). Data registers cannot write to location registers. This prevents organisms from teleporting to arbitrary coordinates.

Enforcement mechanism: `Organism.writeOperand(int id, Object value)` rejects location bank IDs — `instructionFailed("Cannot write to location register via data instruction")`. All existing instructions (DataInstruction, ArithmeticInstruction, BitwiseInstruction, etc.) that use `writeOperand()` are automatically blocked from writing to location banks. Zero changes to existing instructions needed.

New method `writeLocationOperand(int id, int[] value)` on `Organism` — exclusively for location instructions (DPLR, LRLR, POPL, CRLR, etc.). Only these may write to location banks. `LocationInstruction.java` is updated from `writeOperand()` to `writeLocationOperand()`.

Compiler-side enforcement (ISA type system DATA_REGISTER vs LOCATION_REGISTER) is noted as an optional future improvement, not required for this proposal.

**Static Registers (SDR/SLR):** Persistent per procedure definition. The organism holds active SDR/SLR arrays (like `pdrs`, `fdrs`) for direct access via `readOperand()`/`writeOperand()` — zero map lookups in the instruction hotpath. Additionally, a persistent backing store (`Map<String, Object[]>` for SDR, `Map<String, int[][]>` for SLR, keyed by qualified procedure name) preserves state across calls. On CALL: save caller's active SDR/SLR to map (if caller is a procedure), load callee's values from map into active arrays (or initialize to defaults if first call). On RET: write active SDR/SLR back to map, restore caller's values from map. Map lookups occur only at CALL/RET boundaries (once per procedure call), never per instruction.

The first CALL initializes to defaults (0 for SDR, zero-vector for SLR). All calls — including recursive — share the same SDR/SLR state per procedure. This makes procedures stateful "organs" of the organism. Each procedure's SDR/SLR state is private (not accessible from other procedures).

**Location Instructions:** All existing location instructions (DPLR, SKLR, PUSL, POPL, LRLR, CRLR, etc.) work with all location banks transparently. No new instruction variants needed — the dispatch goes through `Organism.readOperand()`/`writeLocationOperand()` which routes by register ID range.

### Procedure Parameter Passing

Extended CALL syntax with LREF and LVAL for location parameters:

```asm
.PROC NAVIGATE REF rSpeed LREF lTarget
    ; rSpeed bound to FDR0 (scalar, by reference)
    ; lTarget bound to FLR0 (location, by reference)
    SKLR lTarget         ; jump DP to the passed location (lTarget = %FLR0)
    RET
.ENDP

CALL NAVIGATE REF %DR3 LREF %LR1
```

**Parameter keywords:**
- `REF` — scalar by reference (existing, binds to FDR)
- `VAL` — scalar by value (existing, binds to FDR)
- `LREF` — location by reference (new, binds to FLR, source must be a location register)
- `LVAL` — location by value (new, binds to FLR, source can be a location register or a label)

**LVAL with Labels:** When a label is passed as LVAL, the runtime resolves it via fuzzy matching at CALL time (same mechanism as SKJI) and copies the resulting position into the FLR. Inside the procedure, SKLR %FLR0 jumps the DP to that exact position. The fuzzy matching happens once at the call site.

### .REG Consolidation

`.PREG` is removed. `.REG` handles all banks. The scope of the alias is determined automatically from the target register's bank:

```asm
.REG %COUNTER %DR0        ; global alias (DR bank)
.REG %POSITION %LR0       ; global alias (LR bank)
.REG %LOCAL_TEMP %PDR2     ; proc-local alias (PDR bank)
.REG %TARGET_POS %PLR0     ; proc-local alias (PLR bank)
.REG %STATE_VAR %SDR0      ; static proc alias (SDR bank)
```

Global aliases (%DR, %LR targets) can appear anywhere. Proc-local aliases (%PDR, %PLR, %FDR, %FLR, %SDR, %SLR targets) must appear inside a `.PROC` block.

## Performance Verification

Before Phase A, run `./gradlew jmh` and record the baseline throughput (ticks/sec) for all parameter combinations. After each phase that modifies runtime code (Phase C: writeOperand/writeLocationOperand split, Phase D: PLR save/restore in ProcedureCallHandler, Phase E: SDR/SLR swap in ProcedureCallHandler, Phase F: FLR binding in ProcedureCallHandler), re-run the benchmark and compare against baseline. No performance regression is acceptable. If regression is detected, the phase must be reworked before proceeding.

Note: The JMH benchmark (`SimulationBenchmark.java`) uses `%DR0` etc. in its assembly programs, not `%PR`/`%FPR`, so Phase A's rename does not affect the benchmark source.

## Implementation Phases

Each phase is independently compilable and testable with all tests green. All changes extend existing feature packages (reg, proc, instruction). No new feature packages are created.

### Phase A: Rename PR→PDR, FPR→FDR + ProcFrame Record — **DONE**

Pure mechanical rename across the entire stack. No new functionality. Establishes the consistent naming foundation for all subsequent phases. Additionally, converts `Organism.ProcFrame` from `final class` to a Java `record` — records provide automatic equals/hashCode and less boilerplate, making subsequent phases that add fields to ProcFrame cleaner.

**Runtime:**
- `Config.java`: `NUM_PROC_REGISTERS` → `NUM_PDR_REGISTERS`, `NUM_FORMAL_PARAM_REGISTERS` → `NUM_FDR_REGISTERS`
- `Instruction.java`: `PR_BASE` → `PDR_BASE`, `FPR_BASE` → `FDR_BASE`, `resolveRegToken()` accepts `%PDR` and `%FDR`
- `Organism.java`: field names `prs` → `pdrs`, `fprs` → `fdrs`, all getters/setters. `ProcFrame` converted to `record ProcFrame(String procName, int[] absoluteReturnIp, int[] absoluteCallIp, Object[] savedPdrs, Object[] savedFdrs, Map<Integer, Integer> fdrBindings)`.
- `ProcedureCallHandler.java`: updated field references
- `GeneSubstitutionPlugin.java`, `GeneInsertionPlugin.java`: bank detection updated

**Compiler:**
- `Lexer.java`: patterns `%PDR\\d+`, `%FDR\\d+` (replace `%PR\\d+`, `%FPR\\d+`)
- `RegisterAliasEmissionContributor.java`: token prefix parsing
- `RegAnalysisHandler.java`, `InstructionAnalysisHandler.java`: register validation
- All feature files referencing `%PR`/`%FPR` string literals

**Data Pipeline:**
- `tickdata_contracts.proto`: field names/documentation updated
- `SimulationEngine.java`, `SimulationRestorer.java`, `OrganismStateConverter.java`: serialization

**Visualizer:**
- `AnnotationUtils.js`: `PR_BASE` → `PDR_BASE`, `FPR_BASE` → `FDR_BASE`, formatRegisterName/getRegisterValue
- `OrganismStateView.js`: section headers and field access

**Assembly files:**
- All `.evo` files: `%PR` → `%PDR`, `%FPR` → `%FDR`

**Documentation:**
- `ASSEMBLY_SPEC.md`: register descriptions, all examples
- Fix existing error: spec says "2 temporary registers (%PR0, %PR1)" but Config has 8

**Estimated scope:** ~80-100 sites across ~20 files. All tests green after rename.

### Phase B: .PREG → .REG Consolidation + Preg Deletion — **DONE**

Consolidates `.PREG` into `.REG` and deletes all Preg-specific types. After this phase, `.REG` is the single directive for all register aliases, and RegNode + Reg-handlers process all banks uniformly. All subsequent phases use the unified directive from the start.

**Compiler — deletions:**
- `PregDirectiveHandler.java`: delete
- `PregAnalysisHandler.java`: delete (RegAnalysisHandler handles all banks via RegNode)
- `PregPostProcessHandler.java`: delete (RegPostProcessHandler handles all banks via RegNode)
- `PregNodeConverter.java`: delete (RegNodeConverter handles all banks via RegNode)
- `PregNode.java`: delete (RegNode is used for all banks)
- `ProcFeature.java`: remove all 4 `.PREG` registrations (parserStatement, analysisHandler, postProcessHandler, irConverter)

**Compiler — modifications:**

*RegDirectiveHandler.java:*
- Accept all register banks as targets. The existing NUMBER fallback (lines 40-51, converting naked numbers to `%DR`) is deleted — register targets must always be explicit tokens (`%DR5`, `%PDR2`, etc.). No existing usages with naked numbers exist.
- **Forbidden-bank check** (hard-coded in handler): If the target register's bank is FDR or FLR, emit error: `"Register %FDR0 cannot be aliased — FDR registers are managed by the CALL binding mechanism."` This is reg-feature knowledge (which banks are inherently non-aliasable), not ParserState infrastructure. The list of forbidden banks grows as formal banks are added: FDR (Phase B), FLR (Phase F).
- **Scope validation** via `ParserState` available-register-banks mechanism (see below). If the target register's bank is not currently available, emit error: `"Register %PDR0 is not available in the current scope."` Zero dependency on the proc feature — the check queries generic `ParserState` infrastructure.
- **Bounds validation** at parse time: extract index from register token, validate against Config constant for the bank. Error on out-of-bounds.
- Validation order: forbidden-bank → scope-availability → bounds. Each check produces a distinct, actionable error message.

*RegAnalysisHandler.isValidRegister():*
- Rewrite from `substring(1,3)` logic to `startsWith` if-chain. One branch per bank, each extracting the index and checking against Config bounds. In Phase B: `%DR` (NUM_DATA_REGISTERS), `%PDR` (NUM_PDR_REGISTERS), `%FDR` (NUM_FDR_REGISTERS), `%LR` (NUM_LOCATION_REGISTERS). Subsequent phases (D, E, F) add one `startsWith` + bounds-check per new bank. Note: prefix order is irrelevant for correctness — no bank prefix is a prefix of another.
- Bounds validation at analysis time is intentionally redundant with parse-time validation in RegDirectiveHandler. This is defense-in-depth: the analysis handler validates ALL register references (including those in instructions like `ADDI %PDR99 DATA:1`), not just `.REG` targets. The `.REG`-specific validation in RegDirectiveHandler is an additional fail-fast check.

**ParserState — available-register-banks mechanism:**

`ParserState` maintains a reference-counted set of available register banks:
- `addAvailableRegisterBanks(String... banks)` — increments counter per bank
- `removeAvailableRegisterBanks(String... banks)` — decrements counter per bank
- `isRegisterBankAvailable(String bank)` — returns `count > 0`

Initial state: `{"DR", "LR"}` with counter 1 (global banks, always available — never removed).

`ProcDirectiveHandler`: on scope push calls `state.addAvailableRegisterBanks("PDR")`, on scope pop calls `state.removeAvailableRegisterBanks("PDR")`. FDR is intentionally excluded — formal data registers are populated by the CALL binding mechanism, not by user code. Direct access to FDR is forbidden (`CompilerErrorCode.PARAM_PERCENT`), and a `.REG` alias on FDR would circumvent this restriction. FDR must never appear in `availableRegisterBanks`. Subsequent phases (D, E, F) add `"PLR"`, `"SDR"`, `"SLR"` analogously — `"FLR"` is excluded for the same reason (formal location registers are populated by LREF/LVAL bindings, not directly).

Reference-counting (not boolean) ensures correctness with nested procs — an inner pop does not remove the bank while the outer scope is still active. No coupling between reg and proc features: proc declares what it provides, reg queries what is available, ParserState mediates.

**Assembly files:**
- 11 sites in 2 files (energy.evo, reproduce.evo): `.PREG %ALIAS %PDR0` → `.REG %ALIAS %PDR0`

**Test migration:**
- `PregDirectiveTest.java`: **delete**. Test cases are recreated as new tests in `RegDirectiveTest.java`: `.REG %ALIAS %PDR0` inside `.PROC` (success), scope validation outside `.PROC` (error), bounds validation (error).
- 6 test files updated: `.PREG` → `.REG` in test source strings, `PregNode` imports → `RegNode` imports, `PregNode` assertions → `RegNode` assertions. Affected files: `ProcedureDirectiveTest.java`, `SemanticAnalyzerTest.java`, `IrGeneratorTest.java`, `EmissionIntegrationTest.java`, `ModuleSourceDefineIntegrationTest.java`, `UsingClauseIntegrationTest.java`.

### Phase C: writeOperand/writeLocationOperand Split

Establishes the runtime safety architecture for location register write restriction. Independent from all other phases — only prerequisite is Phase A (naming). Affects only runtime code, no compiler changes.

**Runtime:**
- `Organism.isLocationBank(int id)`: new helper method. In Phase C, checks only LR range: `id >= LR_BASE && id < LR_BASE + NUM_LOCATION_REGISTERS`. Each subsequent phase that adds a location bank (D: PLR, E: SLR, F: FLR) extends this method with its range. Single source of truth for "is this a location register?"
- `Organism.writeOperand(int id, Object value)`: reject IDs where `isLocationBank(id)` returns true → `instructionFailed("Cannot write to location register via data instruction")`
- `Organism.writeLocationOperand(int id, int[] value)`: new method, exclusively for location instructions. Validates that `isLocationBank(id)` returns true. Writes the vector value to the appropriate location register.
- `Instruction.java`: new protected helper method `writeLocationOperand(int id, int[] value)` analogous to `writeOperand()`, delegates to `organism.writeLocationOperand()`
- `LocationInstruction.java`: 2 call sites changed from `writeOperand()` to `writeLocationOperand()`
- Zero changes to DataInstruction, ArithmeticInstruction, BitwiseInstruction, StateInstruction, VectorInstruction, EnvironmentInteractionInstruction — these continue calling `writeOperand()` and are automatically blocked from writing to location banks

**Test (unit):** Create an Organism. Write via `writeOperand()` to DR → success. Write via `writeOperand()` to LR → `instructionFailed` called, write rejected. Write via `writeLocationOperand()` to LR → success. Write via `writeLocationOperand()` to DR → error (not a location bank).

**Test (integration):** Assembly program executing `SETI %DR0 DATA:42` → success. Assembly program attempting to write a scalar to a location register (if the assembler permits the syntax) → runtime error. If the assembler rejects the syntax at compile time, verify the runtime enforcement directly via unit test.

### Phase D: PLR (Proc-Local Location Registers)

Adds proc-local location registers, saved/restored on CALL/RET like PDR. Depends on Phase C (writeLocationOperand enforcement in place).

**Runtime:**
- `Config.java`: `NUM_PLR_REGISTERS = 4`
- `Instruction.java`: `PLR_BASE = 4000`, `resolveRegToken()` for `%PLR`
- `Organism.java`: `plrs` list (List<Object>, initialized with zero-vectors), `getPlr()`/`setPlr()`, `readOperand()` dispatch, `writeLocationOperand()` dispatch for PLR range
- `ProcFrame` record: add `Object[] savedPlrs` component
- `ProcedureCallHandler.java`: save PLR on CALL, restore on RET
- `LocationInstruction.java`: validation bounds extended to cover PLR range. Dispatch through `Organism.readOperand()`/`writeLocationOperand()` handles routing.
- `GeneSubstitutionPlugin.java`: PLR bank detection for mutations

**Compiler:**
- `Lexer.java`: `%PLR\\d+` pattern
- `RegisterAliasEmissionContributor.java`: `%PLR` prefix
- `InstructionAnalysisHandler.java`: PLR validation for LOCATION_REGISTER argument type

**Data Pipeline:**
- `tickdata_contracts.proto`: `repeated Vector proc_location_registers = N;` + ProcFrame extension
- Serialization/deserialization updated

**Visualizer:**
- `AnnotationUtils.js`: `PLR_BASE: 4000`, formatRegisterName, getRegisterValueById
- `OrganismStateView.js`: PLR section in register display

**Test (integration):** Assembly program with two procedures. PROC_A stores current DP in %PLR0 via `DPLR %PLR0`, then calls PROC_B. PROC_B overwrites %PLR0 with a different position via `DPLR %PLR0`. PROC_B returns. Assert: %PLR0 in PROC_A's context is restored to the original DP position (not PROC_B's overwrite). Verify via register state inspection after compilation and simulated execution.

### Phase E: SDR/SLR (Static Persistent Registers)

Adds persistent state registers. Independent from Phase D (PLR). Depends on Phase A (naming, ProcFrame record) and Phase C (writeLocationOperand enforcement for SLR).

**Runtime:**
- `Config.java`: `NUM_SDR_REGISTERS = 8`, `NUM_SLR_REGISTERS = 4`
- `Instruction.java`: `SDR_BASE = 6000`, `SLR_BASE = 7000`, `resolveRegToken()`
- `Organism.java`: active arrays `sdrs` (List<Object>) and `slrs` (List<Object>) for direct `readOperand()`/`writeOperand()` (SDR) and `writeLocationOperand()` (SLR) access. Backing store: `Map<String, Object[]> sdrState`, `Map<String, int[][]> slrState` keyed by qualified procedure name.
- `ProcedureCallHandler.java`: on CALL, save caller's active SDR/SLR to map (if caller is a procedure), load callee's SDR/SLR from map into active arrays (or initialize to defaults if first call). On RET, write active SDR/SLR back to map, restore caller's values from map.
- `GeneSubstitutionPlugin.java`: SDR/SLR bank detection

**Compiler:**
- `Lexer.java`: `%SDR\\d+`, `%SLR\\d+` patterns
- `RegisterAliasEmissionContributor.java`: `%SDR`, `%SLR` prefixes
- `InstructionAnalysisHandler.java`: SDR validation for REGISTER type, SLR for LOCATION_REGISTER type

**Data Pipeline:**
- `tickdata_contracts.proto`: persistent state serialization (active SDR/SLR arrays + backing store map of procedure names to register snapshots)
- Serialization/deserialization

**Visualizer:**
- `AnnotationUtils.js`: `SDR_BASE: 6000`, `SLR_BASE: 7000`
- `OrganismStateView.js`: SDR/SLR sections, displayed per-procedure in call stack view

**Test (integration):** Assembly program with a procedure COUNTER that increments %SDR0 via `ADDI %SDR0 DATA:1`. Main program calls COUNTER three times. Assert after first call: %SDR0 == 1. Assert after second call: %SDR0 == 2. Assert after third call: %SDR0 == 3. Additionally test isolation: a second procedure COUNTER_B reads its own %SDR0. Assert: COUNTER_B's %SDR0 == 0 (independent from COUNTER's state).

### Phase F: FLR + LREF/LVAL (Location Parameter Passing)

Adds location parameter passing. Depends on Phase D (PLR exists as LREF source).

**Runtime:**
- `Config.java`: `NUM_FLR_REGISTERS = 4`
- `Instruction.java`: `FLR_BASE = 5000`, `resolveRegToken()`
- `Organism.java`: `flrs` list, `getFlr()`/`setFlr()`, readOperand/writeLocationOperand dispatch
- `ProcFrame` record: add `Object[] savedFlrs` component + `Map<Integer, Integer> flrBindings`
- `ProcedureCallHandler.java`: FLR binding on CALL (copy location value from source register to FLR), restore on RET. For LREF: write FLR back to source register before restore.

**Compiler:**
- `Lexer.java`: `%FLR\\d+` pattern
- `RegDirectiveHandler.java`: add `"FLR"` to the forbidden-bank list (alongside FDR). FLR registers are managed by LREF/LVAL bindings, not directly aliasable. `.REG %ALIAS %FLR0` must always produce an error.
- `RegAnalysisHandler.java`: add `%FLR` startsWith branch + bounds check against `NUM_FLR_REGISTERS`
- `ProcDirectiveHandler.java`: parse LREF/LVAL parameter declarations. Note: FLR is NOT added to `availableRegisterBanks` — FLR is a forbidden bank (same as FDR).
- `ProcedureNode.java`: add `lrefParameters`/`lvalParameters` lists (ParamDecl with location flag)
- `CallStatementHandler.java`: parse LREF/LVAL at call site
- `CallNode.java`: add `lrefArguments`/`lvalArguments` fields
- `CallAnalysisHandler.java`: validate LREF sources are location registers, LVAL sources are location registers (labels handled in Phase G)
- `CallNodeConverter.java`: emit IrCallInstruction with location operands
- `IrCallInstruction.java`: add `lrefOperands`/`lvalOperands` fields
- `CallerMarshallingRule.java`: location parameter marshalling (PUSL/POPL for location stack)
- `CallSiteBindingRule.java`: FLR bindings

**Data Pipeline:**
- `tickdata_contracts.proto`: FLR fields + ProcFrame FLR bindings
- Serialization/deserialization

**Visualizer:**
- `AnnotationUtils.js`: `FLR_BASE: 5000`, formatting
- `OrganismStateView.js`: FLR section, location parameter display in call stack
- `ParameterTokenHandler.js`: location parameter binding chain display

**Test (integration):** Assembly program: main stores current DP in %LR0 via `DPLR %LR0`. Defines `.PROC NAV LREF lPos` that executes `SKLR lPos` (which resolves to `SKLR %FLR0`) to move DP to the passed location, then returns. Main calls `CALL NAV LREF %LR0`. Assert: after CALL, the DP inside NAV moved to the location that was stored in %LR0. Assert: after RET, the LREF write-back updated %LR0 if NAV modified %FLR0 (or left it unchanged if not).

### Phase G: LVAL with Labels

Extends LVAL to accept labels. Depends on Phase F.

**Runtime:**
- `ProcedureCallHandler.java`: when LVAL operand is a label hash (IrLabelRef), resolve to position via fuzzy matching (reuse SKJI resolution logic from LocationInstruction) and copy resulting position into FLR.

**Compiler:**
- `CallAnalysisHandler.java`: accept IdentifierNode (label) in LVAL position
- `CallNodeConverter.java`: emit label reference as LVAL operand

**Test (integration):** Assembly program with a label `TARGET:` at a known position (e.g., via `.ORG 10|10`). Defines `.PROC JUMP_TO LVAL lDest` that executes `SKLR lDest`. Main calls `CALL JUMP_TO LVAL TARGET`. Assert: inside JUMP_TO, the DP moved to TARGET's resolved position (10|10). Assert: the fuzzy matching happened at CALL time (not inside the procedure).

### Phase H: Documentation

Update all documentation to reflect the new architecture. Can be done incrementally alongside other phases, finalized after Phase G.

**ASSEMBLY_SPEC.md:**
- Section 3 "Registers": complete rewrite with all 8 banks, correct counts
- Fix existing error: "2 temporary registers" → actual count from Config
- Section 6 "Control Flow": CALL syntax with LREF/LVAL
- Section 6 "Location Operations": all location banks work with location instructions
- Section 7 ".REG": expanded for all banks, automatic scope detection
- Section 7 ".PREG": removed
- Section 7 ".PROC": LREF/LVAL parameter types
- New section: SDR/SLR persistent state concept
- All code examples: %PR → %PDR, %FPR → %FDR

**README.md:**
- Register diagram (line 250): all 8 banks
- Register descriptions (lines 65-71): updated names and new banks

**SCIENTIFIC_OVERVIEW.md:**
- Register diagram (line 91): all 8 banks
- Register descriptions (lines 124-128): updated
- Section 4.4 (Eukaryogenesis): mention SDR/SLR as enabling persistent proc state — procedures become "organs" with internal memory, directly supporting the internal specialization hypothesis

### Phase I: Mutation System

Update mutation plugins for new register banks. Can be done after Phase F.

**Runtime:**
- `GeneSubstitutionPlugin.java`: bank detection covers all 8 ID ranges, bank-aware +1/-1 mutation stays within bank boundaries
- `GeneInsertionPlugin.java`: register selection includes new banks when generating synthetic instructions

**Test (unit):** For each new bank (PLR, FLR, SDR, SLR): create a register molecule with an ID at the bank boundary (e.g., PLR_BASE + NUM_PLR_REGISTERS - 1). Apply +1 mutation. Assert: result stays within bank (clamped to max index, does not overflow into next bank). Apply -1 mutation to bank base ID. Assert: result stays within bank (clamped to base, does not underflow into previous bank).

## End-State Verification

After all phases are complete:

1. **8 register banks** with consistent `[Scope][Type]R` naming
2. **Full parity** between data and location registers at every scope level
3. **Persistent proc state** via SDR/SLR — procedures are stateful "organs"
4. **Location parameter passing** via LREF/LVAL — positions can be passed to procedures
5. **LVAL with labels** — fuzzy-matched positions as procedure arguments
6. **Single .REG directive** — scope determined by target bank
7. **Location write restriction** enforced via writeOperand/writeLocationOperand split across all location banks
8. **All location instructions** work transparently with LR, PLR, FLR, SLR
9. **Visualizer** displays all 8 banks with correct names
10. **Documentation** fully updated (ASSEMBLY_SPEC, README, SCIENTIFIC_OVERVIEW)
11. **Mutation system** bank-aware for all 8 banks
12. **All assembly files** migrated to new names
13. **ProcFrame** is a Java record with clean component structure
14. **No performance regression** verified via JMH benchmarks after each runtime-modifying phase
