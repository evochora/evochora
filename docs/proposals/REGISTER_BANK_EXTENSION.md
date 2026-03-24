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

Base IDs use 256-spacing for compact lookup tables (total table size: 2048 entries, ~8KB).

| Bank | Prefix | Base ID | Count | Type | Scope | Call Behavior | New? |
|------|--------|---------|-------|------|-------|---------------|------|
| Data | %DR | 0 | 8 | Scalar | Global | Global | — |
| Location | %LR | 256 | 4 | Vector | Global | Global | — |
| Proc Data | %PDR | 512 | 8 | Scalar | Per Call | Stack-Saved | Rename from %PR |
| Proc Location | %PLR | 768 | 4 | Vector | Per Call | Stack-Saved | **NEW** |
| Formal Data | %FDR | 1024 | 8 | Scalar | Per Call | Stack-Saved | Rename from %FPR |
| Formal Location | %FLR | 1280 | 4 | Vector | Per Call | Stack-Saved | **NEW** |
| Static Data | %SDR | 1536 | 8 | Scalar | Per Procedure | Persistent | **NEW** |
| Static Location | %SLR | 1792 | 4 | Vector | Per Procedure | Persistent | **NEW** |

**No backward compatibility** is maintained — neither for assembly files nor for serialized simulation data (Protobuf, H2 database). Old simulations cannot be loaded with new code. Backward compatibility must never lead to design compromises. Protobuf field names are renamed freely (Phase A). New Protobuf fields use new field numbers.

### Key Properties

**Location Register Write Restriction:** All location banks (LR, PLR, FLR, SLR) enforce the same constraint as LR today — they can only receive values from other location registers or from the DP (via DPLR). Data registers cannot write to location registers. This prevents organisms from teleporting to arbitrary coordinates.

Enforcement mechanism: `Organism.writeOperand(int id, Object value)` rejects location bank IDs — `instructionFailed("Cannot write to location register via data instruction")`. All existing instructions (DataInstruction, ArithmeticInstruction, BitwiseInstruction, etc.) that use `writeOperand()` are automatically blocked from writing to location banks. Zero changes to existing instructions needed.

New method `writeLocationOperand(int id, int[] value)` on `Organism` — exclusively for location instructions (DPLR, LRLR, POPL, CRLR, etc.). Only these may write to location banks. `LocationInstruction.java` is updated from `writeOperand()` to `writeLocationOperand()`.

Compiler-side enforcement (ISA type system DATA_REGISTER vs LOCATION_REGISTER) is noted as an optional future improvement, not required for this proposal.

**Static Registers (SDR/SLR):** Persistent per procedure definition. The organism holds active SDR/SLR arrays for direct access via `readOperand()`/`writeOperand()` (SDR) and `writeLocationOperand()` (SLR) — zero map lookups in the instruction hotpath. Additionally, a persistent backing store (`Map<String, Object[]>` for SDR, `Map<String, int[][]>` for SLR, keyed by qualified procedure name) preserves state across calls. On CALL: save caller's active SDR/SLR to map (if caller is a procedure), load callee's values from map into active arrays (or initialize to defaults if first call). On RET: write active SDR/SLR back to map, restore caller's values from map. Map lookups occur only at CALL/RET boundaries (once per procedure call), never per instruction.

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

Before Phase A, run `./gradlew jmh` and record the baseline throughput (ticks/sec) for all parameter combinations. After each phase that modifies runtime code (Phase C: writeOperand/writeLocationOperand split, Phase D2: flat register array, Phase E: PLR save/restore in ProcedureCallHandler, Phase F: SDR/SLR swap in ProcedureCallHandler, Phase G: FLR binding in ProcedureCallHandler), re-run the benchmark and compare against baseline. No performance regression is acceptable. If regression is detected, the phase must be reworked before proceeding.

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
- **Forbidden-bank check** (hard-coded in handler): If the target register's bank is FDR or FLR, emit error: `"Register %FDR0 cannot be aliased — FDR registers are managed by the CALL binding mechanism."` This is reg-feature knowledge (which banks are inherently non-aliasable), not ParserState infrastructure. The list of forbidden banks grows as formal banks are added: FDR (Phase B), FLR (Phase G).
- **Scope validation** via `ParserState` available-register-banks mechanism (see below). If the target register's bank is not currently available, emit error: `"Register %PDR0 is not available in the current scope."` Zero dependency on the proc feature — the check queries generic `ParserState` infrastructure.
- **Bounds validation** at parse time: extract index from register token, validate against Config constant for the bank. Error on out-of-bounds.
- Validation order: forbidden-bank → scope-availability → bounds. Each check produces a distinct, actionable error message.

*RegAnalysisHandler.isValidRegister():*
- Rewrite from `substring(1,3)` logic to `startsWith` if-chain. One branch per bank, each extracting the index and checking against Config bounds. In Phase B: `%DR` (NUM_DATA_REGISTERS), `%PDR` (NUM_PDR_REGISTERS), `%FDR` (NUM_FDR_REGISTERS), `%LR` (NUM_LOCATION_REGISTERS). Phase D replaces the if-chain with RegisterBank enum iteration. Note: prefix order is irrelevant for correctness — no bank prefix is a prefix of another.
- Bounds validation at analysis time is intentionally redundant with parse-time validation in RegDirectiveHandler. This is defense-in-depth: the analysis handler validates ALL register references (including those in instructions like `ADDI %PDR99 DATA:1`), not just `.REG` targets. The `.REG`-specific validation in RegDirectiveHandler is an additional fail-fast check.

**ParserState — available-register-banks mechanism:**

`ParserState` maintains a reference-counted set of available register banks:
- `addAvailableRegisterBanks(String... banks)` — increments counter per bank
- `removeAvailableRegisterBanks(String... banks)` — decrements counter per bank
- `isRegisterBankAvailable(String bank)` — returns `count > 0`

Initial state: `{"DR", "LR"}` with counter 1 (global banks, always available — never removed).

`ProcDirectiveHandler`: on scope push calls `state.addAvailableRegisterBanks("PDR")`, on scope pop calls `state.removeAvailableRegisterBanks("PDR")`. FDR is intentionally excluded — formal data registers are populated by the CALL binding mechanism, not by user code. Direct access to FDR is forbidden (`CompilerErrorCode.PARAM_PERCENT`), and a `.REG` alias on FDR would circumvent this restriction. FDR must never appear in `availableRegisterBanks`. Phase D3 makes this generic: ProcDirectiveHandler derives the bank list from `RegisterBank.allProcScoped()` filtered by `!isForbidden`. Subsequent phases (E, F, G) add banks as enum entries; the ProcDirectiveHandler picks them up automatically. `"FLR"` is excluded via `isForbidden = true` (formal location registers are populated by LREF/LVAL bindings, not directly).

Reference-counting (not boolean) ensures correctness with nested procs — an inner pop does not remove the bank while the outer scope is still active. No coupling between reg and proc features: proc declares what it provides, reg queries what is available, ParserState mediates.

**Assembly files:**
- 11 sites in 2 files (energy.evo, reproduce.evo): `.PREG %ALIAS %PDR0` → `.REG %ALIAS %PDR0`

**Test migration:**
- `PregDirectiveTest.java`: **delete**. Test cases are recreated as new tests in `RegDirectiveTest.java`: `.REG %ALIAS %PDR0` inside `.PROC` (success), scope validation outside `.PROC` (error), bounds validation (error).
- 6 test files updated: `.PREG` → `.REG` in test source strings, `PregNode` imports → `RegNode` imports, `PregNode` assertions → `RegNode` assertions. Affected files: `ProcedureDirectiveTest.java`, `SemanticAnalyzerTest.java`, `IrGeneratorTest.java`, `EmissionIntegrationTest.java`, `ModuleSourceDefineIntegrationTest.java`, `UsingClauseIntegrationTest.java`.

### Phase C: writeOperand/writeLocationOperand Split — **DONE**

Establishes the runtime safety architecture for location register write restriction. Independent from all other phases — only prerequisite is Phase A (naming). Affects only runtime code, no compiler changes.

**Runtime:**
- `Organism.isLocationBank(int id)`: new helper method. In Phase C, checks only LR range: `id >= LR_BASE && id < LR_BASE + NUM_LOCATION_REGISTERS`. Phase D replaces this with RegisterBank enum lookup. Single source of truth for "is this a location register?"
- `Organism.writeOperand(int id, Object value)`: reject IDs where `isLocationBank(id)` returns true → `instructionFailed("Cannot write to location register via data instruction")`
- `Organism.writeLocationOperand(int id, int[] value)`: new method, exclusively for location instructions. Validates that `isLocationBank(id)` returns true. Writes the vector value to the appropriate location register.
- `Instruction.java`: new protected helper method `writeLocationOperand(int id, int[] value)` analogous to `writeOperand()`, delegates to `organism.writeLocationOperand()`
- `LocationInstruction.java`: All direct `org.setLr()`/`org.getLr()` calls replaced with bank-independent dispatch: 4 write sites (DPLR, POPL, LRLR, CRLR) route through `writeLocationOperand()`, 5 read sites (SKLR, PUSL, LRDR, LRDS, LRLR source) route through `org.readOperand()`. The `toLrIndex()` helper method is deleted. 2 existing `writeOperand()` calls (LRDR, LSDR) remain unchanged — they write to data registers, not location registers.
- Zero changes to DataInstruction, ArithmeticInstruction, BitwiseInstruction, StateInstruction, VectorInstruction, EnvironmentInteractionInstruction — these continue calling `writeOperand()` and are automatically blocked from writing to location banks

**Test (unit):** Create an Organism. Write via `writeOperand()` to DR → success. Write via `writeOperand()` to LR → `instructionFailed` called, write rejected. Write via `writeLocationOperand()` to LR → success. Write via `writeLocationOperand()` to DR → error (not a location bank).

**Test (integration):** Assembly program executing `SETI %DR0 DATA:42` → success. Assembly program attempting to write a scalar to a location register (if the assembler permits the syntax) → runtime error. If the assembler rejects the syntax at compile time, verify the runtime enforcement directly via unit test.

### Phase D: Register Bank Encapsulation

Encapsulates the register bank concept so that adding a new bank on the Java side requires only two changes: a RegisterBank enum entry and a Config constant. All dispatch, validation, serialization, and save/restore logic works generically through the enum. The JS visualizer remains separate (technology boundary) and must be extended manually per bank.

Depends on Phase C (writeOperand/writeLocationOperand architecture). Must be completed before Phase E (PLR) so all subsequent phases benefit from the encapsulation.

#### D1: RegisterBank Enum + Base ID Migration

Creates the central definition source for all bank metadata AND migrates all base IDs from 1000-spacing to 256-spacing. This is not purely additive — the base ID migration changes `Instruction.java` constants and propagates through the entire stack (compiler, serialization, visualizer, tests).

New file `RegisterBank.java` in `org.evochora.runtime.isa` (alongside `Instruction.java`, whose base constants RegisterBank replaces). Enum with one entry per existing bank (DR, LR, PDR, FDR). Each entry contains:
- `base` (int) — base register ID (256-spacing: DR=0, LR=256, PDR=512, FDR=1024)
- `count` (int) — references Config constant (e.g., `Config.NUM_DATA_REGISTERS`)
- `isLocation` (boolean)
- `callBehavior` (inner enum `CallBehavior` with values `GLOBAL`, `STACK_SAVED`, `PERSISTENT`) — GLOBAL for DR/LR (no save/restore), STACK_SAVED for PDR/FDR/PLR/FLR (snapshot to ProcFrame), PERSISTENT for SDR/SLR (swap via backing store)
- `isForbidden` (boolean) — true for FDR (and later FLR), cannot be aliased via `.REG`
- `prefix` (String, e.g., `"%DR"`)
- `prefixLength` (int)

Static helpers:
- `forId(int id)` — returns the bank for a register ID (or null). Implemented via D2's `ID_TO_SLOT` table + a small `SLOT_TO_BANK_ORDINAL` table (48 entries, one per register slot). In D1 (before D2), a temporary `int[] ID_TO_BANK_ORDINAL` table (size 2048) is used; D2 replaces it with the slot-based approach.
- `isLocationBank(int id)` — lookup + `bank.isLocation`
- `allSavedOnCall()` — filters `callBehavior == STACK_SAVED`
- `allProcScoped()` — filters `callBehavior != GLOBAL`

The 256-spacing keeps lookup tables compact: 8 banks × 256 = 2048 entries (~8KB). With max 8 registers per bank, 99.6% of slots are sentinel values — this sparse occupation is intentional and accepted for O(1) lookup performance (single array access vs O(n) comparisons in if-chains). The table fits comfortably in L1 cache.

No JMH checkpoint needed after D1 — base IDs change but dispatch code is unchanged until D2.

**Base ID migration:** All base IDs change from the old 1000-spacing to 256-spacing. This is a breaking change affecting Instruction.java constants, Protobuf serialization, JS constants, and all stored data. Since backward compatibility is not required, this is acceptable.

| Bank | Old Base | New Base |
|------|----------|----------|
| DR | 0 | 0 |
| LR | 3000 | 256 |
| PDR | 1000 | 512 |
| FDR | 2000 | 1024 |

New banks added in later phases use the remaining slots: PLR=768, FLR=1280, SDR=1536, SLR=1792.

**Test — existing:** All unit tests and all 5 CLI smoke tests must be green. Since the base ID migration changes all register IDs, the existing tests are the primary regression test. The CLI smoke test artifact baseline must be reset after D1 because compiled register IDs in the artifacts change. From the new baseline onward, smoke tests verify that artifacts remain stable.

**Test — new:** Self-referential `forId()` tests that do not hardcode base IDs:
- For each bank in `RegisterBank.values()`: `forId(bank.base)` returns exactly this bank
- For each bank: `forId(bank.base + bank.count - 1)` returns exactly this bank (last valid ID)
- For each bank: `forId(bank.base + bank.count)` does NOT return this bank (first ID after the bank)
- For each bank: `forId(bank.base - 1)` does NOT return this bank (last ID before the bank)
- For each bank: `isLocationBank(bank.base) == bank.isLocation`
- Edge cases: `forId(-1)` → null, `forId(TABLE_SIZE)` → null

These tests verify the lookup table initialization logic (off-by-one, gaps, boundaries), not concrete values. They remain green if the spacing changes.

#### D2: Flat Register Array in Organism

Replaces the error-prone if-chain dispatches in readOperand/writeOperand/writeLocationOperand with a single array lookup. The existing public API is preserved as a facade so all existing tests remain green without changes.

Internal storage changes from separate lists (`drs`, `pdrs`, `fdrs`, `lrs`) to a single `Object[] registers` array. Size computed from RegisterBank (sum of all `count` values). A static `int[] ID_TO_SLOT` lookup table (size 2048) maps sparse register IDs to contiguous array slots. A parallel `boolean[] IS_LOCATION_BY_ID` table (also size 2048, indexed by register ID — NOT by slot) enables O(1) location-bank checks. This duplicates information available via `RegisterBank.forId(id).isLocation`, but is intentional: the hotpath (`writeOperand`, `writeLocationOperand`) needs a single array lookup, not a `forId()` call + field access. This is a deliberate performance-over-DRY tradeoff for the instruction execution hotpath.

Additionally, a small `RegisterBank[] SLOT_TO_BANK` table (48 entries, one per register slot) enables `forId()` to work via `ID_TO_SLOT` → `SLOT_TO_BANK` instead of a separate 2048-entry table. The D1 temporary `ID_TO_BANK_ORDINAL` table is replaced by this approach.

All three dispatch methods perform a bounds check on `id` before any table access (`id < 0 || id >= TABLE_SIZE`). Invalid IDs → `instructionFailed()`, no ArrayIndexOutOfBoundsException.

- `readOperand(int id)`: bounds check → `ID_TO_SLOT[id]` → sentinel check → `registers[slot]`. Invalid ID: `instructionFailed()`.
- `writeOperand(int id, Object value)`: bounds check → `IS_LOCATION_BY_ID[id]` check → rejection for location IDs. Otherwise: `ID_TO_SLOT[id]` → array write. Invalid ID: `instructionFailed()`.
- `writeLocationOperand(int id, int[] value)`: bounds check → `IS_LOCATION_BY_ID[id]` check → rejection for non-location IDs. Otherwise: `ID_TO_SLOT[id]` → array write. Invalid ID: `instructionFailed()`.
- `isLocationBank(int id)`: bounds check → `IS_LOCATION_BY_ID[id]`.

Existing public methods (`getPdrs()`, `getLrs()`, `setPdr()`, `setLr()`, `getDr()`, `setDr()`, `getFdr()`, `setFdr()`, etc.) remain as facade — they read/write via slot offsets from the flat array. ProcFrame, ProcedureCallHandler, serialization, and tests need NO changes in this step.

`VirtualMachine.collectRegisterValues()`: Fix pre-existing bug where the LOCATION_REGISTER branch compared full register IDs (256+ range) against `NUM_LOCATION_REGISTERS` (= 4). Change to `readOperand(registerId)` — analogous to the existing REGISTER branch, which already uses `readOperand()` correctly.

Mutations plugins (`GeneSubstitutionPlugin.java`, `GeneInsertionPlugin.java`): migrate bank detection and bank-name-to-base-ID switches from hardcoded if-chains/switch-statements to RegisterBank iteration. These are runtime code and migrate in D2 alongside the Organism changes.

**Test:** All existing tests must pass unchanged — the facade guarantees identical behavior. Additionally: JMH benchmark against baseline (Phase A baseline) to measure performance effect of the flat array.

#### D3: Compiler Migration to RegisterBank Enum

Eliminates manually maintained if-chains and switch statements in all compiler handlers. New banks are automatically recognized once added to the enum.

- `Lexer.java`: `isValidRegisterPattern()` iterates over `RegisterBank.values()` with `text.matches(bank.prefix + "\\d+")` instead of hardcoded separate `text.matches()` calls.
- `RegDirectiveHandler.java`: bank extraction and bounds check iterate over RegisterBank. `FORBIDDEN_BANKS` set derived from `RegisterBank.values()` filtered by `isForbidden`. Scope validation remains indirect via `ParserState.isRegisterBankAvailable(bank)` — the enum is NOT queried directly for scope. ProcDirectiveHandler uses the enum to determine which banks to register (see below), and RegDirectiveHandler queries ParserState. This preserves the decoupling between reg and proc features.
- `RegAnalysisHandler.isValidRegister()`: iterates over RegisterBank, matches `startsWith(bank.prefix)`, checks bounds against `bank.count`.
- `InstructionAnalysisHandler.java`: validation uses `RegisterBank.forId()` and `bank.isLocation` instead of hardcoded prefix checks.
- `RegisterAliasEmissionContributor.java`: `resolveRegisterId()` iterates over RegisterBank, matches prefix, computes `bank.base + index`.
- `ProcDirectiveHandler.java`: `addAvailableRegisterBanks` / `removeAvailableRegisterBanks` derives the bank list from `RegisterBank.allProcScoped()` filtered by `!isForbidden`. This is the only place where ProcDirectiveHandler interacts with the enum.
- `Instruction.java`: `resolveRegToken()` iterates over RegisterBank instead of hardcoded if-chain. Legacy base constants (`PDR_BASE`, `FDR_BASE`, `LR_BASE`) remain as aliases referencing `RegisterBank.PDR.base` etc. for backward compatibility within the codebase.
- `ProcedureMarshallingRule.java` and `CallNodeConverter.java`: retain hardcoded `%FDR` references. FDR marshalling (prologue POP into %FDR, epilogue PUSH from %FDR) is calling-convention semantics, not a generic bank property. These references are intentionally NOT generified — they encode the REF/VAL parameter passing mechanism. Phase G adds analogous FLR marshalling for LREF/LVAL.

**Test:** All compiler tests green. All 5 CLI smoke tests green.

#### D4: ProcFrame + ProcedureCallHandler Simplification

ProcFrame currently has separate fields per bank (`savedPdrs`, `savedFdrs`). Each new bank would add another field plus changes to all 8+ constructor call-sites. A single `Object[] savedRegisters` makes ProcFrame bank-independent.

- `ProcFrame` record: from `ProcFrame(String procName, int[] absoluteReturnIp, int[] absoluteCallIp, Object[] savedPdrs, Object[] savedFdrs, Map<Integer, Integer> fdrBindings)` to `ProcFrame(String procName, int[] absoluteReturnIp, int[] absoluteCallIp, Object[] savedRegisters, Map<Integer, Integer> parameterBindings)`. The map is renamed from `fdrBindings` to `parameterBindings` — it holds all parameter bindings (FDR keys now, FLR keys added in Phase G) in a single generic map. Keys are full register IDs (e.g., FDR_BASE+0, FLR_BASE+0), so banks are distinguishable by ID range.
- `savedRegisters` is a **compact array** in RegisterBank enum order (NOT a copy of the full flat array). Built by iterating over `RegisterBank.allSavedOnCall()` in enum declaration order, concatenating each bank's register values. Example with PDR(8) + FDR(8): savedRegisters has 16 entries — slots 0–7 are PDR values, slots 8–15 are FDR values. The restore logic iterates in the same enum order and knows each bank's offset and count, so it can write the values back to the correct flat-array slots.
- `Organism.java`: new methods `snapshotStackSavedRegisters()` (returns `Object[]`) and `restoreStackSavedRegisters(Object[] snapshot)`. These encapsulate the iteration over `RegisterBank.allSavedOnCall()` in enum declaration order and the compact array layout. ProcedureCallHandler calls only these two methods and knows no bank details.
- `ProcedureCallHandler.java`: on CALL, calls `organism.snapshotStackSavedRegisters()` to build the compact array. On RET, calls `organism.restoreStackSavedRegisters(frame.savedRegisters())`. No bank-specific logic.
- All ProcFrame constructor call-sites (ProcedureCallHandler, tests, SimulationRestorer): adapt to new signature.

Note: `parameterBindings` (renamed from `fdrBindings`) has different semantics from `savedRegisters` — it holds parameter bindings (register ID → source register ID), not saved/restored values. The map is generic: FDR bindings use FDR_BASE+i as keys, FLR bindings (Phase G) use FLR_BASE+i as keys. Phase G simply adds FLR entries to the same map — no ProcFrame signature change needed.

**Transition facades (removed in D5):**

Between D4 and D5, the Java model uses the compact `savedRegisters` array but the Protobuf schema and DTOs still have separate fields per bank. This requires temporary conversion logic:

- `Organism.java`: `restorePdrs()` / `restoreFdrs()` remain — SimulationRestorer needs them until D5 when the Protobuf schema changes. Removed in D5.
- `SimulationEngine.java` (transition): splits compact `ProcFrame.savedRegisters()` back into separate Proto fields `saved_pdrs` / `saved_fdrs` via `RegisterBank.allSavedOnCall()` iteration. Removed in D5.
- `SimulationRestorer.java` (transition): assembles separate Proto fields into compact `savedRegisters` array via `RegisterBank.allSavedOnCall()` iteration in enum order. Removed in D5.
- `OrganismStateConverter.java`: no change in D4 — `convertProcFrame()` reads from the Protobuf message (not Java ProcFrame), so the ProcFrame record change does not affect it. Normal schema migration in D5.
- `ProcFrameView.java`: remains unchanged (separate `savedPdrs` / `savedFdrs` fields). Changed to `List<RegisterValueView> savedRegisters` in D5.

**Test:** All tests green after adapting to new ProcFrame signature.

#### D5: Serialization + API + Visualizer Encapsulation

Completes the flat-array encapsulation through the entire stack. Split into three independently testable sub-steps.

##### D5a: `fdrBindings` → `parameterBindings` Rename (Java runtime only)

Pure mechanical rename of Java variables and the ProcFrame record field. Proto stays unchanged. **ProcFrameView (API DTO) stays unchanged** — its `fdrBindings` field name is serialized as a JSON key and read by the JS frontend. Renaming it here would break 20+ JS call sites. ProcFrameView is renamed in D5b together with the JS migration.

- `ProcFrame` record: `fdrBindings` → `parameterBindings`
- `ProcedureCallHandler.java`: all references
- `SimulationEngine.java`: all references (still writes to proto `fdr_bindings` field)
- `SimulationRestorer.java`: all references (still reads from proto `fdr_bindings` field)
- `ProcFrameView.java`: **NOT renamed** — API DTO field stays `fdrBindings` until D5b
- `OrganismStateConverter.java`: no change needed (local variable already named `bindings`)

**Test:** All tests green — purely mechanical rename.

##### D5b: Protobuf + Java Serialization + DTOs + JS Visualizer

Atomic API migration. Proto schema, Java serialization/deserialization, DTOs, RestoreBuilder, and JS Visualizer are changed together because they form one API contract. After D5b, adding a new register bank requires zero changes to serialization code (Java) and one entry in the JS `REGISTER_BANKS` array (Visualizer). Backward compatibility is explicitly not required.

**Proto schema** (`tickdata_contracts.proto`):

All Proto messages get clean sequential field numbers starting at 1 — no legacy numbering artifacts.

- `OrganismState`: ALL separate register fields (`data_registers`, `proc_data_registers`, `formal_data_registers`, `location_registers`) replaced by a single `repeated RegisterValue registers`. Slot order matches the flat array. Location registers use the `RegisterValue.vector` variant.
- `OrganismRuntimeState`: same — separate register fields replaced by `repeated RegisterValue registers`.
- `ProcFrame`: `saved_pdrs`, `saved_fdrs` replaced by `repeated RegisterValue saved_registers`. `fdr_bindings` renamed to `parameter_bindings`.

**Java serialization:**
- `SimulationEngine.java`: writes Organism's flat array sequentially into protobuf `registers` field. ProcFrame writes `saved_registers` and `parameter_bindings` directly. D4 transition splitting removed.
- `SimulationRestorer.java`: reads protobuf `registers` field as flat `Object[]`, passes directly to `RestoreBuilder.registers(Object[])`. `convertProcFrame()` reads `saved_registers` and `parameter_bindings` directly. D4 transition assembly removed.
- `OrganismStateConverter.java`: `resolveRegisterValue()` uses RegisterBank iteration and slot-based lookup instead of ID-based if-chain. `convertProcFrame()` converts `saved_registers` to `List<RegisterValueView> savedRegisters`.
- `ProcFrameView.java`: separate `savedPdrs`/`savedFdrs` fields replaced by `List<RegisterValueView> savedRegisters`. `fdrBindings` renamed to `parameterBindings`.
- `OrganismRuntimeView.java`: separate register lists replaced by `List<RegisterValueView> registers` (flat array, slot order matching RegisterBank). The API consumer (JS Visualizer) interprets slots via bank metadata.
- `H2DatabaseReader.java`: indirectly via OrganismStateConverter.
- `SimulationParameters.java`: JavaDoc comments updated.

**Organism RestoreBuilder:**
- Separate register setters (`dataRegisters()`, `procDataRegisters()`, `formalDataRegisters()`, `locationRegisters()`) replaced by a single `registers(Object[])` setter. The deserialization path (SimulationRestorer) passes the flat array directly — no roundtrip through separate lists.

**JS Visualizer:**

`INSTRUCTION_CONSTANTS` is fully replaced by `REGISTER_BANKS` — no parallel constant sources. A derived lookup `BANK_BY_NAME` provides O(1) name-based access.

```javascript
const REGISTER_BANKS = [
    { name: "DR",  prefix: "%DR",  base: 0,    slotOffset: 0,  count: 8, isLocation: false },
    { name: "LR",  prefix: "%LR",  base: 256,  slotOffset: 8,  count: 4, isLocation: true  },
    { name: "PDR", prefix: "%PDR", base: 512,  slotOffset: 12, count: 8, isLocation: false },
    { name: "FDR", prefix: "%FDR", base: 1024, slotOffset: 20, count: 8, isLocation: false },
];
const BANK_BY_NAME = Object.fromEntries(REGISTER_BANKS.map(b => [b.name, b]));
```
Adding a new bank = one entry in `REGISTER_BANKS`. Architecturally identical to Java's `RegisterBank` enum.

- `AnnotationUtils.js`: ALL dispatch methods migrated to `REGISTER_BANKS` iteration — no hardcoded per-bank branches remain:
  - `getRegisterValueById(registerId, state)`: finds bank via `REGISTER_BANKS` iteration (descending by base), reads `state.registers[bank.slotOffset + index]`
  - `getRegisterValue(canonicalName, state)`: finds bank via `REGISTER_BANKS` iteration matching `canonicalName.startsWith(bank.prefix)` (e.g., `"%PDR0".startsWith("%PDR")`), extracts index from substring after prefix, reads `state.registers[bank.slotOffset + index]`
  - `formatRegisterName(registerId, registerType)`: when `registerType` is provided, uses `BANK_BY_NAME[registerType]` for lookup. ID-based fallback iterates `REGISTER_BANKS` (descending by base). Formats `%${bank.name}${registerId - bank.base}`.
  - `resolveToCanonicalRegister(token, artifact)`: finds bank via `REGISTER_BANKS` iteration (descending by base), formats canonical name
  - `resolveBindingChain` / `resolveBindingChainWithPath`: FDR_BASE references replaced by `BANK_BY_NAME.FDR.base`
  - `INSTRUCTION_CONSTANTS` deleted — `REGISTER_BANKS` is the single source.
  - After D5b, adding a new bank requires only a new `REGISTER_BANKS` entry — zero method changes.
- `OrganismStateView.js`: register display iterates over `REGISTER_BANKS`, rendering one section per bank from `state.registers[slotOffset..slotOffset+count]`. ProcFrame display reads `savedRegisters` and `parameterBindings` (renamed from `fdrBindings`). Call stack visualization resolves parameter bindings using `parameterBindings` map keys (register IDs) to display which source register is bound to which parameter register. No hardcoded per-bank sections.

**Test:** All pipeline tests, H2 tests, SimulationRestorer tests green after adaptation.

##### D5c: Organism Facade Removal (absorbs former D6)

After D2–D5b, facade methods in Organism remain from D2 as transitional API. These are now removed to clean up the API.

- `restorePdrs()` / `restoreFdrs()` removed — SimulationRestorer no longer needs them (uses `restoreStackSavedRegisters()`).
- `getPdrs()`, `getFdrs()`, `getLrs()`, `setPdr()`, `setFdr()`, `setLr()`, `getDr()`, `setDr()`, `getPdr()`, `getFdr()`, `getLr()` etc. — check which still have callers (compile errors on removal reveal this). Remove unused methods.
- `RestoreBuilder`: separate register setters already removed in D5b. Verify no remnants.
- `Instruction.java`: remove legacy base constant aliases (`PDR_BASE`, `FDR_BASE`, `LR_BASE`) if all callers have been migrated to `RegisterBank.X.base`.

**Test:** Compile errors reveal all remaining callers. After cleanup, all tests green.

### Phase E: PLR (Proc-Local Location Registers)

Adds proc-local location registers, saved/restored on CALL/RET like PDR. Depends on Phase C (writeLocationOperand enforcement) and Phase D (RegisterBank encapsulation).

After Phase D, adding PLR is significantly simpler: a RegisterBank enum entry + Config constant handles all generic dispatch, validation, serialization, and save/restore automatically.

**Runtime:**
- `Config.java`: `NUM_PLR_REGISTERS = 4`
- `RegisterBank.java`: new entry `PLR(768, Config.NUM_PLR_REGISTERS, true, CallBehavior.STACK_SAVED, false, "%PLR", 4)`
- Organism flat array automatically includes PLR slots (computed from RegisterBank). `readOperand()`, `writeLocationOperand()`, `isLocationBank()` route PLR via lookup tables. `ProcedureCallHandler` saves/restores PLR via generic `allSavedOnCall()` iteration.
- `LocationInstruction.java`: no changes needed — dispatch through `Organism.readOperand()`/`writeLocationOperand()` routes PLR automatically (Phase C architecture)
- `GeneSubstitutionPlugin.java`, `GeneInsertionPlugin.java`: PLR bank detection is automatic via RegisterBank iteration (established in D2). No manual changes needed.

**Compiler:**
- After Phase D3, compiler handlers use RegisterBank iteration — PLR is automatically recognized. No manual changes to Lexer, RegDirectiveHandler, RegAnalysisHandler, InstructionAnalysisHandler, or RegisterAliasEmissionContributor needed.
- `ProcDirectiveHandler.java`: PLR is automatically included in `addAvailableRegisterBanks()` via `allProcScoped().filter(!isForbidden)` from RegisterBank.

**Data Pipeline:**
- After Phase D5, serialization is flat-array-based — PLR slots are automatically included. No manual protobuf field additions needed.

**Visualizer (manual, technology boundary):**
- Add PLR entry to `REGISTER_BANKS` array in `AnnotationUtils.js`: `{ name: "PLR", base: 768, slotOffset: <computed>, count: 4, isLocation: true }`. All dispatch methods and OrganismStateView iterate over REGISTER_BANKS — no method changes needed, PLR section appears automatically.

**Test (integration):** Assembly program with two procedures. PROC_A stores current DP in %PLR0 via `DPLR %PLR0`, then calls PROC_B. PROC_B overwrites %PLR0 with a different position via `DPLR %PLR0`. PROC_B returns. Assert: %PLR0 in PROC_A's context is restored to the original DP position (not PROC_B's overwrite). Verify via register state inspection after compilation and simulated execution.

### Phase F: SDR/SLR (Static Persistent Registers)

Adds persistent state registers. Independent from Phase E (PLR). Depends on Phase A (naming), Phase C (writeLocationOperand enforcement for SLR), and Phase D (RegisterBank encapsulation).

SDR/SLR have special semantics NOT covered by generic encapsulation: the persistent backing store (`Map<String, Object[]>` / `Map<String, int[][]>` keyed by procedure name) and the CALL/RET swap logic. This bank-specific logic must be implemented in ProcedureCallHandler.

**Runtime:**
- `Config.java`: `NUM_SDR_REGISTERS = 8`, `NUM_SLR_REGISTERS = 4`
- `RegisterBank.java`: new entries `SDR(1536, Config.NUM_SDR_REGISTERS, false, CallBehavior.PERSISTENT, false, "%SDR", 4)` and `SLR(1792, Config.NUM_SLR_REGISTERS, true, CallBehavior.PERSISTENT, false, "%SLR", 4)`
- Organism: flat array automatically includes SDR/SLR slots. Backing store: `Map<String, Object[]> sdrState`, `Map<String, int[][]> slrState`.
- `ProcedureCallHandler.java`: on CALL, save caller's active SDR/SLR to map (if caller is a procedure), load callee's SDR/SLR from map into flat array slots (or initialize to defaults if first call). On RET, write active SDR/SLR back to map, restore caller's values. This is **bank-specific logic** — `CallBehavior.PERSISTENT` triggers this path, but the backing store management is not generic.
- `GeneSubstitutionPlugin.java`, `GeneInsertionPlugin.java`: SDR/SLR bank detection is automatic via RegisterBank iteration (established in D2). No manual changes needed.

**Compiler:**
- After Phase D3, compiler handlers use RegisterBank iteration — SDR/SLR are automatically recognized. No manual changes needed.
- `ProcDirectiveHandler.java`: SDR/SLR are automatically included in `addAvailableRegisterBanks()` via `allProcScoped().filter(!isForbidden)` from RegisterBank.

**Data Pipeline:**
- After Phase D5, flat-array serialization automatically includes active SDR/SLR slots in `OrganismState.registers`.
- Backing store serialization requires new Protobuf structures in `tickdata_contracts.proto`:
  ```protobuf
  message PersistentRegisterStore {
      repeated ProcedureRegisterSnapshot procedure_snapshots = 1;
  }
  message ProcedureRegisterSnapshot {
      string procedure_name = 1;
      repeated RegisterValue registers = 2;  // SDR + SLR values for this procedure
  }
  ```
  `OrganismState` gets a new field `PersistentRegisterStore persistent_register_store`. SimulationEngine serializes the `Map<String, Object[]>` sdrState and `Map<String, int[][]>` slrState into this structure. SimulationRestorer deserializes it back. This is SDR/SLR-specific and NOT covered by generic encapsulation.

**Visualizer (manual, technology boundary):**
- Add SDR and SLR entries to `REGISTER_BANKS` array in `AnnotationUtils.js`. All dispatch methods and OrganismStateView iterate over REGISTER_BANKS — no method changes needed, SDR/SLR sections appear automatically. SDR/SLR persistent state displayed per-procedure in call stack view may require additional OrganismStateView logic for the backing-store visualization (bank-specific, not generic).

**Test (integration):** Assembly program with a procedure COUNTER that increments %SDR0 via `ADDI %SDR0 DATA:1`. Main program calls COUNTER three times. Assert after first call: %SDR0 == 1. Assert after second call: %SDR0 == 2. Assert after third call: %SDR0 == 3. Additionally test isolation: a second procedure COUNTER_B reads its own %SDR0. Assert: COUNTER_B's %SDR0 == 0 (independent from COUNTER's state).

### Phase G: FLR + LREF/LVAL (Location Parameter Passing)

Adds location parameter passing. Depends on Phase E (PLR exists as LREF source) and Phase D (RegisterBank encapsulation).

**Runtime:**
- `Config.java`: `NUM_FLR_REGISTERS = 4`
- `RegisterBank.java`: new entry `FLR(1280, Config.NUM_FLR_REGISTERS, true, CallBehavior.STACK_SAVED, true, "%FLR", 4)` — note `isForbidden = true`
- Organism: flat array automatically includes FLR slots. `isLocationBank()` recognizes FLR via lookup table.
- `ProcFrame` record: no signature change needed — FLR bindings are added to the existing `parameterBindings` map using FLR_BASE+i as keys (established as generic in D4).
- `ProcedureCallHandler.java`: FLR binding on CALL — adds FLR_BASE+i → source register ID entries to `parameterBindings` (alongside existing FDR entries). Copy location value from source register to FLR slot. On RET: for LREF parameters, write FLR value back to source register before restore.
- `GeneSubstitutionPlugin.java`, `GeneInsertionPlugin.java`: FLR bank detection is automatic via RegisterBank iteration (established in D2). No manual changes needed.

**Compiler:**
- After Phase D3, compiler handlers use RegisterBank iteration — FLR is automatically recognized as a forbidden bank (no `.REG` alias allowed), valid for LOCATION_REGISTER arguments. No manual changes to RegDirectiveHandler, RegAnalysisHandler, InstructionAnalysisHandler, RegisterAliasEmissionContributor, or Lexer needed.
- `ProcDirectiveHandler.java`: FLR is automatically excluded from `addAvailableRegisterBanks()` via `isForbidden` flag. Parse LREF/LVAL parameter declarations.
- `ProcedureNode.java`: add `lrefParameters`/`lvalParameters` lists (ParamDecl with location flag)
- `CallStatementHandler.java`: parse LREF/LVAL at call site
- `CallNode.java`: add `lrefArguments`/`lvalArguments` fields
- `CallAnalysisHandler.java`: validate LREF sources are location registers, LVAL sources are location registers (labels handled in Phase H)
- `CallNodeConverter.java`: emit IrCallInstruction with location operands
- `IrCallInstruction.java`: add `lrefOperands`/`lvalOperands` fields
- `CallerMarshallingRule.java`: location parameter marshalling (PUSL/POPL for location stack)
- `CallSiteBindingRule.java`: FLR bindings

**Data Pipeline:**
- Flat-array serialization automatically includes FLR slots.
- ProcFrame: no protobuf change needed — FLR bindings are added to the existing `parameter_bindings` map (generic, established in D5)

**Visualizer (manual, technology boundary):**
- Add FLR entry to `REGISTER_BANKS` array in `AnnotationUtils.js`. All dispatch methods and OrganismStateView iterate over REGISTER_BANKS — no method changes needed for basic display, FLR section appears automatically.
- `ParameterTokenHandler.js`: location parameter binding chain display (LREF/LVAL chain resolution through FLR, analogous to REF/VAL through FDR). This is new functionality, not generic bank registration.

**Test (integration):** Assembly program: main stores current DP in %LR0 via `DPLR %LR0`. Defines `.PROC NAV LREF lPos` that executes `SKLR lPos` (which resolves to `SKLR %FLR0`) to move DP to the passed location, then returns. Main calls `CALL NAV LREF %LR0`. Assert: after CALL, the DP inside NAV moved to the location that was stored in %LR0. Assert: after RET, the LREF write-back updated %LR0 if NAV modified %FLR0 (or left it unchanged if not).

### Phase H: LVAL with Labels

Extends LVAL to accept labels. Depends on Phase G.

**Runtime:**
- `ProcedureCallHandler.java`: when LVAL operand is a label hash (IrLabelRef), resolve to position via fuzzy matching (reuse SKJI resolution logic from LocationInstruction) and copy resulting position into FLR.

**Compiler:**
- `CallAnalysisHandler.java`: accept IdentifierNode (label) in LVAL position
- `CallNodeConverter.java`: emit label reference as LVAL operand

**Test (integration):** Assembly program with a label `TARGET:` at a known position (e.g., via `.ORG 10|10`). Defines `.PROC JUMP_TO LVAL lDest` that executes `SKLR lDest`. Main calls `CALL JUMP_TO LVAL TARGET`. Assert: inside JUMP_TO, the DP moved to TARGET's resolved position (10|10). Assert: the fuzzy matching happened at CALL time (not inside the procedure).

### Phase I: Documentation

Update all documentation to reflect the new architecture. Can be done incrementally alongside other phases, finalized after Phase H.

**ASSEMBLY_SPEC.md:**
- Section 3 "Registers": complete rewrite with all 8 banks, correct counts, 256-spacing base IDs
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

### Phase J: Mutation System Verification

Verifies that mutation plugins correctly handle all 8 register banks. After Phase D2, both plugins use RegisterBank iteration for bank detection — no code changes are expected. This phase is primarily a **test phase** that adds bank-boundary tests for the new banks (PLR, FLR, SDR, SLR) and confirms correct behavior.

**Runtime:**
- `GeneSubstitutionPlugin.java`: verify bank detection covers all 8 ID ranges via RegisterBank iteration (established in D2). Bank-aware +1/-1 mutation stays within bank boundaries.
- `GeneInsertionPlugin.java`: verify register selection includes all banks via RegisterBank iteration (established in D2).
- If any bank-specific edge cases are found (e.g., mutation across location/data boundary), fix them here.

**Test (unit):** For each new bank (PLR, FLR, SDR, SLR): create a register molecule with an ID at the bank boundary (e.g., PLR_BASE + NUM_PLR_REGISTERS - 1). Apply +1 mutation. Assert: result stays within bank (clamped to max index, does not overflow into next bank). Apply -1 mutation to bank base ID. Assert: result stays within bank (clamped to base, does not underflow into previous bank).

## End-State Verification

After all phases are complete:

1. **8 register banks** with consistent `[Scope][Type]R` naming and 256-spacing base IDs
2. **RegisterBank enum** as single source of truth for all bank metadata
3. **Flat register array** in Organism with O(1) lookup dispatch
4. **Full parity** between data and location registers at every scope level
5. **Persistent proc state** via SDR/SLR — procedures are stateful "organs"
6. **Location parameter passing** via LREF/LVAL — positions can be passed to procedures
7. **LVAL with labels** — fuzzy-matched positions as procedure arguments
8. **Single .REG directive** — scope determined by target bank
9. **Location write restriction** enforced via writeOperand/writeLocationOperand split across all location banks
10. **All location instructions** work transparently with LR, PLR, FLR, SLR
11. **Visualizer** displays all 8 banks via `REGISTER_BANKS` metadata iteration — new bank = one array entry, no method changes
12. **Documentation** fully updated (ASSEMBLY_SPEC, README, SCIENTIFIC_OVERVIEW)
13. **Mutation system** bank-aware for all 8 banks
14. **All assembly files** migrated to new names
15. **ProcFrame** bank-independent with single `savedRegisters` array and generic `parameterBindings` map
16. **No performance regression** verified via JMH benchmarks after each runtime-modifying phase
