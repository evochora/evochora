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

### Key Properties

**Location Register Write Restriction:** All location banks (LR, PLR, FLR, SLR) enforce the same constraint as LR today — they can only receive values from other location registers or from the DP (via DPLR). Data registers cannot write to location registers. This prevents organisms from teleporting to arbitrary coordinates.

**Static Registers (SDR/SLR):** Persistent per procedure definition. The first CALL initializes them to defaults (0 for SDR, zero-vector for SLR). Subsequent CALLs find the values from the previous RET. All calls — including recursive — share the same SDR/SLR state. This makes procedures stateful "organs" of the organism. Each procedure's SDR/SLR state is private (not accessible from other procedures).

**Location Instructions:** All existing location instructions (DPLR, SKLR, PUSL, POPL, LRLR, CRLR, etc.) work with all location banks transparently. No new instruction variants needed — the dispatch goes through `Organism.readOperand()`/`writeOperand()` which routes by register ID range.

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

## Implementation Phases

Each phase is independently compilable and testable with all tests green.

### Phase A: Rename PR→PDR, FPR→FDR

Pure mechanical rename across the entire stack. No new functionality. Establishes the consistent naming foundation for all subsequent phases.

**Runtime:**
- `Config.java`: `NUM_PROC_REGISTERS` → `NUM_PDR_REGISTERS`, `NUM_FORMAL_PARAM_REGISTERS` → `NUM_FDR_REGISTERS`
- `Instruction.java`: `PR_BASE` → `PDR_BASE`, `FPR_BASE` → `FDR_BASE`, `resolveRegToken()` accepts both `%PDR` and `%FDR`
- `Organism.java`: field names `prs` → `pdrs`, `fprs` → `fdrs`, all getters/setters/ProcFrame fields
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

### Phase B: PLR (Proc-Local Location Registers)

Adds proc-local location registers, saved/restored on CALL/RET like PDR.

**Runtime:**
- `Config.java`: `NUM_PLR_REGISTERS = 4`
- `Instruction.java`: `PLR_BASE = 4000`, `resolveRegToken()` for `%PLR`
- `Organism.java`: `plrs` list (List<Object>, initialized with zero-vectors), `getPlr()`/`setPlr()`, `readOperand()`/`writeOperand()` dispatch
- `ProcFrame`: add `savedPlrs` field
- `ProcedureCallHandler.java`: save PLR on CALL, restore on RET
- `LocationInstruction.java`: `toLrIndex()` generalized — any location bank ID maps to the correct register. The existing dispatch through `Organism.readOperand()`/`writeOperand()` handles this, but validation bounds must cover PLR range.
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

**Test:** Assembly program that stores a DP position in %PLR0 inside a procedure, calls another procedure that overwrites %PLR0, returns, and verifies the original value was restored.

### Phase C: SDR/SLR (Static Persistent Registers)

Adds persistent state registers. Independent from Phase B.

**Runtime:**
- `Config.java`: `NUM_SDR_REGISTERS = 8`, `NUM_SLR_REGISTERS = 4`
- `Instruction.java`: `SDR_BASE = 6000`, `SLR_BASE = 7000`, `resolveRegToken()`
- `Organism.java`: `Map<String, Object[]> sdrState`, `Map<String, int[][]> slrState` — keyed by qualified procedure name. `readOperand()`/`writeOperand()` dispatch for SDR/SLR ranges.
- `ProcedureCallHandler.java`: on CALL, load SDR/SLR from persistent map (or initialize to defaults if first call). On RET, write current SDR/SLR back to map.
- `GeneSubstitutionPlugin.java`: SDR/SLR bank detection

**Compiler:**
- `Lexer.java`: `%SDR\\d+`, `%SLR\\d+` patterns
- `RegisterAliasEmissionContributor.java`: `%SDR`, `%SLR` prefixes
- `InstructionAnalysisHandler.java`: SDR validation for REGISTER type, SLR for LOCATION_REGISTER type

**Data Pipeline:**
- `tickdata_contracts.proto`: persistent state serialization (map of procedure names to register snapshots)
- Serialization/deserialization

**Visualizer:**
- `AnnotationUtils.js`: `SDR_BASE: 6000`, `SLR_BASE: 7000`
- `OrganismStateView.js`: SDR/SLR sections, displayed per-procedure in call stack view

**Test:** Assembly program with a procedure that increments %SDR0 on each call. Three calls verify values 1, 2, 3.

### Phase D: FLR + LREF/LVAL (Location Parameter Passing)

Adds location parameter passing. Depends on Phase B (PLR exists as LREF source).

**Runtime:**
- `Config.java`: `NUM_FLR_REGISTERS = 4`
- `Instruction.java`: `FLR_BASE = 5000`, `resolveRegToken()`
- `Organism.java`: `flrs` list, `getFlr()`/`setFlr()`, readOperand/writeOperand dispatch
- `ProcFrame`: add `savedFlrs` field + `flrBindings` map (analogous to fdrBindings)
- `ProcedureCallHandler.java`: FLR binding on CALL (copy location value from source register to FLR), restore on RET. For LREF: write FLR back to source register before restore.

**Compiler:**
- `Lexer.java`: `%FLR\\d+` pattern
- `ProcDirectiveHandler.java`: parse LREF/LVAL parameter declarations
- `ProcedureNode.java`: add `lrefParameters`/`lvalParameters` lists (ParamDecl with location flag)
- `CallStatementHandler.java`: parse LREF/LVAL at call site
- `CallNode.java`: add `lrefArguments`/`lvalArguments` fields
- `CallAnalysisHandler.java`: validate LREF sources are location registers, LVAL sources are location registers (labels handled in Phase E)
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

**Test:** Assembly program: `.PROC NAV LREF lPos` stores DP in %LR0, calls `NAV LREF %LR0`, procedure uses `SKLR %FLR0`, verifies DP moved.

### Phase E: LVAL with Labels

Extends LVAL to accept labels. Depends on Phase D.

**Runtime:**
- `ProcedureCallHandler.java`: when LVAL operand is a label hash (IrLabelRef), resolve to position via fuzzy matching (reuse SKJI resolution logic) and copy into FLR.

**Compiler:**
- `CallAnalysisHandler.java`: accept IdentifierNode (label) in LVAL position
- `CallNodeConverter.java`: emit label reference as LVAL operand

**Test:** Assembly program: label `TARGET:` at known position, `CALL myProc LVAL TARGET`, procedure uses `SKLR %FLR0` to jump there.

### Phase F: .PREG → .REG Consolidation

Depends on Phase A (names finalized).

**Compiler:**
- `RegDirectiveHandler.java`: accept all register banks as targets. Determine scope from target bank: DR/LR → global, PDR/PLR/FDR/FLR/SDR/SLR → procedure-scoped.
- `PregDirectiveHandler.java`: delete
- `ProcFeature.java`: remove `.PREG` registration
- `RegFeature.java` or `ProcFeature.java`: ensure `.REG` is registered (already is)

**Assembly files:**
- All `.PREG %ALIAS %PR0` → `.REG %ALIAS %PDR0`

**Test:** `.REG %MY_LOCAL %PDR2` inside a procedure, use alias, verify correct register.

### Phase G: Documentation

Update all documentation to reflect the new architecture. Can be done incrementally alongside other phases, finalized after Phase F.

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

### Phase H: Mutation System

Update mutation plugins for new register banks. Can be done after Phase D.

**Runtime:**
- `GeneSubstitutionPlugin.java`: bank detection covers all 8 ID ranges, bank-aware +1/-1 mutation stays within bank boundaries
- `GeneInsertionPlugin.java`: register selection includes new banks when generating synthetic instructions

**Test:** Verify mutations on registers in new banks stay within correct bank boundaries.

## End-State Verification

After all phases are complete:

1. **8 register banks** with consistent `[Scope][Type]R` naming
2. **Full parity** between data and location registers at every scope level
3. **Persistent proc state** via SDR/SLR — procedures are stateful "organs"
4. **Location parameter passing** via LREF/LVAL — positions can be passed to procedures
5. **LVAL with labels** — fuzzy-matched positions as procedure arguments
6. **Single .REG directive** — scope determined by target bank
7. **Location write restriction** enforced across all location banks
8. **All location instructions** work transparently with LR, PLR, FLR, SLR
9. **Visualizer** displays all 8 banks with correct names
10. **Documentation** fully updated (ASSEMBLY_SPEC, README, SCIENTIFIC_OVERVIEW)
11. **Mutation system** bank-aware for all 8 banks
12. **All assembly files** migrated to new names
