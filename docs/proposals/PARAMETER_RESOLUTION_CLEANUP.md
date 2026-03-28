# Parameter Resolution Cleanup + Alias Type Safety

## Problem

Two architectural issues in the compiler's type system and symbol resolution:

### 1. Duplicate Scope Mechanism for Parameters

Procedure parameters are resolved in Phase 7 (IrGenContext) via a separate scope stack (`procParamScopes`, `procLocationParamScopes`) that duplicates the SymbolTable's scope management. This violates the Single Source of Truth principle. The SymbolTable already has correct proc-scopes from Phase 4 (ProcedureSymbolCollector), and Phase 6 (AstPostProcessor) already uses ScopeTracker for scope-aware alias resolution. Parameters should use the same mechanism.

Currently:
- Register aliases: resolved in Phase 6 via SymbolTable + IRegisterAlias → RegisterNode
- Parameters: resolved in Phase 7 via IrGenContext.resolveProcedureParam → IrReg

After cleanup:
- Register aliases: resolved in Phase 6 via SymbolTable + IRegisterAlias → RegisterNode (unchanged)
- Parameters: resolved in Phase 6 via SymbolTable + IParameterBinding → RegisterNode (same mechanism)

### 2. Missing Type Safety for Register Aliases

Register aliases (`Symbol.Type.ALIAS`) are accepted in both REGISTER and LOCATION_REGISTER argument positions by InstructionAnalysisHandler. This means `.REG %POS %LR0` followed by `SETI %POS 42` compiles without error — but crashes at runtime because SETI writes to a location register via writeOperand, which rejects location bank IDs.

Parameters already have this type safety (VARIABLE → REGISTER only, LOCATION_VARIABLE → LOCATION_REGISTER only, added in Phase G of the Register Bank Extension). Aliases should have the same.

### 3. Misleading Symbol Type Names

`Symbol.Type.VARIABLE` and `LOCATION_VARIABLE` are used exclusively for procedure parameters. "Variable" implies a named storage location (like `int x`); "parameter" is what they actually are.

## Solution

### Step 1: Alias Type Safety

Split `Symbol.Type.ALIAS` into data and location variants.

**Symbol.java** (`model/symbols/`):
- `ALIAS` — data register alias (target is DR, PDR, SDR, or FDR bank)
- New: `LOCATION_ALIAS` — location register alias (target is LR, PLR, SLR, or FLR bank)

**RegAnalysisHandler.java** (`features/reg/`):
- Determine alias type from target register bank: `RegisterBank.forId(resolvedId).isLocation` → LOCATION_ALIAS, else → ALIAS
- Currently uses 4-arg Symbol constructor with RegNode — no change to constructor, just the Symbol.Type

**InstructionAnalysisHandler.java** (`features/instruction/`):
- ALIAS → accepted only in REGISTER position (was: REGISTER or LOCATION_REGISTER)
- LOCATION_ALIAS → accepted only in LOCATION_REGISTER position (new case branch)
- Compile-time error: data alias in location instruction, location alias in data instruction

**TokenKindMapper.java** (`frontend/tokenmap/`):
- `ALIAS, LOCATION_ALIAS → TokenKind.ALIAS` (Visualizer does not distinguish)

**CallAnalysisHandler.java** (`features/proc/`):
- LREF/LVAL argument validation: if the argument resolves to an ALIAS symbol, reject it (data alias in location parameter position). Only accept LOCATION_ALIAS. Error: "LREF argument must be a location register, but '%MY_REG' is a data register alias."
- REF/VAL argument validation: if the argument resolves to a LOCATION_ALIAS symbol, reject it (location alias in data parameter position). Only accept ALIAS. Error: "REF argument must be a data register, but '%POS' is a location register alias."

**Tests:**
- Test: `.REG %POS %LR0` then `SETI %POS 42` → compile error (location alias in data instruction)
- Test: `.REG %COUNTER %DR0` then `CRLR %COUNTER` → compile error (data alias in location instruction)
- Test: `.REG %POS %LR0` then `SKLR %POS` → OK (location alias in location instruction)

### Step 2: Parameter Resolution via SymbolTable

Move parameter resolution from Phase 7 (IrGenContext) to Phase 6 (AstPostProcessor) using the existing ScopeTracker and SymbolTable infrastructure.

**New files:**

`model/ast/IParameterBinding.java`:
```java
/**
 * Capability interface for AST nodes that carry a parameter's target register binding.
 * Used by the AstPostProcessor to resolve parameter identifiers to RegisterNodes
 * without depending on specific feature node types.
 */
public interface IParameterBinding {
    /** Returns the target formal register (e.g., "%FDR0", "%FLR1"). */
    String targetRegister();
}
```

`model/ast/ParameterBinding.java`:
```java
/**
 * Synthetic AST node carrying a parameter's compile-time register binding.
 * Created by ProcedureSymbolCollector and stored on the Symbol's node field.
 * Not part of the parsed AST — exists solely as a data carrier for resolution.
 */
public record ParameterBinding(String targetRegister) implements AstNode, IParameterBinding {
    @Override public List<AstNode> getChildren() { return List.of(); }
}
```

**Symbol.java** (`model/symbols/`):
- Rename `VARIABLE` → `PARAMETER` (VARIABLE is deleted — no remaining usage)
- Rename `LOCATION_VARIABLE` → `LOCATION_PARAMETER` (LOCATION_VARIABLE is deleted — no remaining usage)

**TokenKind.java** (`api/`):
- Rename `VARIABLE` → `PARAMETER`
- New: `REGISTER` — for physical register tokens (`%DR0`, `%LR1`) that are not aliases and not parameters. Previously these were misclassified as `VARIABLE`.

**ProcedureSymbolCollector.java** (`features/proc/`):
- REF/VAL parameters: `Symbol.Type.PARAMETER` with `new ParameterBinding("%FDR" + dataIndex)`
- LREF/LVAL parameters: `Symbol.Type.LOCATION_PARAMETER` with `new ParameterBinding("%FLR" + locationIndex)`
- dataIndex increments across REF + VAL (all map to FDR)
- locationIndex increments across LREF + LVAL (all map to FLR)

**AstPostProcessor.java** (`frontend/postprocess/`):
- In `collectReplacements()`, after existing ALIAS resolution, add PARAMETER/LOCATION_PARAMETER resolution:

```java
if ((symbol.type() == Symbol.Type.PARAMETER || symbol.type() == Symbol.Type.LOCATION_PARAMETER)
        && symbol.node() instanceof IParameterBinding pb) {
    createRegisterReplacement(idNode, identifierName.toUpperCase(), pb.targetRegister());
    return;
}
```

This uses the existing ScopeTracker — when inside a proc, symbolTable.resolve() finds the parameter in the proc's scope. Outside the proc, parameters are not visible. Shadowing works correctly (proc-scope parameter shadows module-scope alias with the same name).

**IrGenContext.java** (`frontend/irgen/`) — DELETE:
- Field `procParamScopes` (Deque<Map<String, Integer>>)
- Field `procLocationParamScopes` (Deque<Map<String, Integer>>)
- Method `pushProcedureParams(List<String>)`
- Method `popProcedureParams()`
- Method `pushProcedureLocationParams(List<String>)`
- Method `popProcedureLocationParams()`
- Method `resolveProcedureParam(String)`

In `convertOperand()`: remove the `resolveProcedureParam` call. Parameter identifiers are already replaced by RegisterNodes in Phase 6. Phase 7 never sees them. The `convertOperand` method only handles RegisterNode, NumberLiteralNode, TypedLiteralNode, VectorLiteralNode, and IdentifierNode (for constants and labels). No parameter-specific code needed.

**ProcedureNodeConverter.java** (`features/proc/`) — DELETE:
- `allDataParams` list building
- `allLocationParams` list building
- `ctx.pushProcedureParams(allDataParams)`
- `ctx.pushProcedureLocationParams(allLocationParams)`
- `ctx.popProcedureLocationParams()`
- `ctx.popProcedureParams()`

What remains: emit proc_enter/proc_exit directives (with lrefArity/lvalArity for marshalling) and convert body via `ctx.convert()`.

**CallNodeConverter.java** (`features/proc/`):
- Remove `resolveProcedureParam` call. Parameter identifiers are already resolved to RegisterNodes by Phase 6.

**InstructionAnalysisHandler.java** (`features/instruction/`):
- Rename VARIABLE → PARAMETER, LOCATION_VARIABLE → LOCATION_PARAMETER
- PARAMETER → accepted only in REGISTER position (unchanged logic, new name)
- LOCATION_PARAMETER → accepted only in LOCATION_REGISTER position (unchanged logic, new name)

**CallAnalysisHandler.java** (`features/proc/`):
- No Symbol.Type references remaining (legacy WITH validation was removed). No changes needed for the rename.

**TokenKindMapper.java** (`frontend/tokenmap/`):
- `PARAMETER, LOCATION_PARAMETER → TokenKind.PARAMETER`

**ProcedureTokenMapContributor.java** (`features/proc/`):
- Line 41: `TokenKind.VARIABLE` → `TokenKind.PARAMETER` (parameter tokens in the TokenMap)

**TokenMapGenerator.java** (`frontend/tokenmap/`):
- Line 222: `TokenKind.VARIABLE` → `TokenKind.REGISTER` — physical register tokens (`%DR0`, `%LR1` etc.) that are not aliases. Previously misclassified as VARIABLE.

**Visualizer:**
- `ParameterTokenHandler.js`: `tokenInfo.tokenType === 'PARAMETER'` instead of `'VARIABLE'`
- `RegisterTokenHandler.js`: `type === 'REGISTER'` instead of `type === 'VARIABLE' && token.startsWith('%')`

**Compiler.java:**
- No changes needed. Phase 6 AstPostProcessor already has ScopeTracker. Phase 7 IrGenerator doesn't need new dependencies.

### Verification

After both steps:

1. **Single Source of Truth**: SymbolTable is the sole authority for all symbol resolution (aliases, parameters, constants, labels). IrGenContext has no scope management.
2. **Phase consistency**: All symbolic register references (aliases AND parameters) resolved in Phase 6. Phase 7 does only AST→IR conversion.
3. **Type safety**: Data symbols (ALIAS, PARAMETER) accepted only in REGISTER positions. Location symbols (LOCATION_ALIAS, LOCATION_PARAMETER) accepted only in LOCATION_REGISTER positions. Compile-time errors for type mismatches.
4. **Correct naming**: PARAMETER/LOCATION_PARAMETER instead of VARIABLE/LOCATION_VARIABLE.
5. **No duplicate scope tracking**: IrGenContext's procParamScopes/procLocationParamScopes eliminated.

### Complete Symbol Type Table

| Symbol.Type | Purpose | InstructionAnalysisHandler | TokenKind | Resolution Phase |
|---|---|---|---|---|
| ALIAS | Data register alias (.REG %X %DR0) | REGISTER only | ALIAS | Phase 6 (IRegisterAlias) |
| LOCATION_ALIAS | Location register alias (.REG %X %LR0) | LOCATION_REGISTER only | ALIAS | Phase 6 (IRegisterAlias) |
| PARAMETER | Data proc parameter (REF/VAL) | REGISTER only | PARAMETER | Phase 6 (IParameterBinding) |
| LOCATION_PARAMETER | Location proc parameter (LREF/LVAL) | LOCATION_REGISTER only | PARAMETER | Phase 6 (IParameterBinding) |
| CONSTANT | Named constant (.DEFINE) | LITERAL | CONSTANT | Phase 6 (flat map) |
| LABEL | Label definition | LABEL, VECTOR | LABEL | Phase 10 (Linking) |
| PROCEDURE | Procedure definition (.PROC) | LABEL | PROCEDURE | Phase 10 (Linking) |
| — (no Symbol) | Physical register (%DR0, %LR1) | — (RegisterNode) | REGISTER | Not resolved (already concrete) |

### Tests

**Step 1 tests (alias type safety):**
- `.REG %POS %LR0` + `SETI %POS 42` → compile error (location alias in data instruction)
- `.REG %COUNTER %DR0` + `CRLR %COUNTER` → compile error (data alias in location instruction)
- `.REG %POS %LR0` + `SKLR %POS` → OK (location alias in location instruction)
- `.REG %COUNTER %DR0` + `SETI %COUNTER 42` → OK (data alias in data instruction)
- `.REG %MY_REG %DR0` + `CALL proc LREF %MY_REG` → compile error (data alias as LREF argument)
- `.REG %MY_LOC %LR0` + `CALL proc REF %MY_LOC` → compile error (location alias as REF argument)
- `.REG %MY_LOC %LR0` + `CALL proc LREF %MY_LOC` → OK (location alias as LREF argument)
- `.REG %MY_REG %DR0` + `CALL proc REF %MY_REG` → OK (data alias as REF argument)
- Existing alias tests remain green

**Step 2 tests (parameter resolution):**
- Existing parameter scoping tests (RegisterAliasScopeTest) adapted to new Symbol types
- Parameters inside proc resolve to correct FDR/FLR RegisterNodes after Phase 6
- Parameters outside proc scope are not visible
- Shadowing: proc-level parameter shadows module-level alias
- IrGenContext has no procParamScopes/procLocationParamScopes fields (verified via grep)
- All existing integration tests and CLI smoke tests green
