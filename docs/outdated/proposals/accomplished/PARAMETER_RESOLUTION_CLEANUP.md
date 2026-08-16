# Parameter Resolution Cleanup + Symbol Type Safety

## Problem

Four architectural issues in the compiler's type system and symbol resolution:

### 1. Misleading Symbol Type Names

`Symbol.Type.VARIABLE` and `LOCATION_VARIABLE` are used exclusively for procedure parameters. "Variable" implies a named storage location (like `int x`); "parameter" is what they actually are.

### 2. Module Aliases Share Symbol Type with Register Aliases

`.IMPORT` and `.REQUIRE` aliases (ImportSymbolCollector, RequireSymbolCollector) are registered as `Symbol.Type.ALIAS` — the same type used by `.REG` register aliases. InstructionAnalysisHandler accepts `ALIAS` in register positions without checking whether the symbol is actually a register alias. A module alias used as an instruction argument (e.g., `SETI MATH 42` where MATH is an `.IMPORT` alias) passes Phase 4 analysis but fails late at Phase 10 linking. Module aliases need their own Symbol type.

### 3. Missing Type Safety for Register Aliases

Register aliases (`Symbol.Type.ALIAS`) are accepted in both REGISTER and LOCATION_REGISTER argument positions by InstructionAnalysisHandler. This means `.REG %POS %LR0` followed by `SETI %POS 42` compiles without error — but crashes at runtime because SETI writes to a location register via writeOperand, which rejects location bank IDs.

Parameters already have this type safety (VARIABLE → REGISTER only, LOCATION_VARIABLE → LOCATION_REGISTER only, added in Phase G of the Register Bank Extension). Aliases should have the same. The same type safety gap exists in CallAnalysisHandler: identifier arguments in REF/VAL/LREF/LVAL positions are accepted without symbol type validation.

### 4. Duplicate Scope Mechanism for Parameters

Procedure parameters are resolved in Phase 7 (IrGenContext) via a separate scope stack (`procParamScopes`, `procLocationParamScopes`) that duplicates the SymbolTable's scope management. This violates the Single Source of Truth principle. The SymbolTable already has correct proc-scopes from Phase 4 (ProcedureSymbolCollector), and Phase 6 (AstPostProcessor) already uses ScopeTracker for scope-aware alias resolution. Parameters should use the same mechanism.

Currently:
- Register aliases: resolved in Phase 6 via SymbolTable + IRegisterAlias → RegisterNode
- Parameters: resolved in Phase 7 via IrGenContext.resolveProcedureParam → IrReg

After cleanup:
- Register aliases: resolved in Phase 6 via SymbolTable + IRegisterAlias → RegisterNode (unchanged)
- Parameters: resolved in Phase 6 via SymbolTable + IParameterBinding → RegisterNode (same mechanism)

## Solution

Four implementation steps, each self-contained, compilable, and testable.

### Dependencies

```text
Step 1 (Parameter Rename) ─┬─→ Step 3 (Call Validation) ─→ Step 4 (Parameter Resolution)
Step 2 (Alias Type Split) ─┘
```

Steps 1 and 2 are independent of each other. Step 3 requires both. Step 4 requires Step 1 and is cleaner after Step 3.

### Target Symbol Type Enum

After all steps, `Symbol.Type` contains:

```java
LABEL,                     // Label definition
CONSTANT,                  // Named constant (.DEFINE)
PROCEDURE,                 // Procedure definition (.PROC)
MODULE_ALIAS,              // .IMPORT/.REQUIRE AS (module alias, not a register)
REGISTER_ALIAS_DATA,       // .REG %X %DR0 (target is data bank: DR, PDR, SDR)
REGISTER_ALIAS_LOCATION,   // .REG %X %LR0 (target is location bank: LR, PLR, SLR)
PARAMETER_DATA,            // REF/VAL procedure parameter (→ FDR)
PARAMETER_LOCATION         // LREF/LVAL procedure parameter (→ FLR)
```

---

### Step 1: Parameter Rename

Pure mechanical rename. No behavioral change. All existing tests remain green.

**Symbol.java** (`model/symbols/`):
- Rename `VARIABLE` → `PARAMETER_DATA`
- Rename `LOCATION_VARIABLE` → `PARAMETER_LOCATION`
- `VARIABLE` and `LOCATION_VARIABLE` are deleted — no remaining usage.

**ProcedureSymbolCollector.java** (`features/proc/`):
- `Symbol.Type.VARIABLE` → `Symbol.Type.PARAMETER_DATA` (lines 30, 35)
- `Symbol.Type.LOCATION_VARIABLE` → `Symbol.Type.PARAMETER_LOCATION` (lines 40, 45)

**InstructionAnalysisHandler.java** (`features/instruction/`):
- `Symbol.Type.VARIABLE` → `Symbol.Type.PARAMETER_DATA` (line 103)
- `Symbol.Type.LOCATION_VARIABLE` → `Symbol.Type.PARAMETER_LOCATION` (line 113)
- Logic unchanged: PARAMETER_DATA → REGISTER only, PARAMETER_LOCATION → LOCATION_REGISTER only.

**TokenKindMapper.java** (`frontend/tokenmap/`):
- `case VARIABLE, LOCATION_VARIABLE` → `case PARAMETER_DATA, PARAMETER_LOCATION`
- Map target: `TokenKind.VARIABLE` → `TokenKind.PARAMETER`

**TokenKind.java** (`api/`):
- Rename `VARIABLE` → `PARAMETER`
- New: `REGISTER` — for physical register tokens (`%DR0`, `%LR1`) that are not aliases and not parameters. Previously misclassified as `VARIABLE`.

**ProcedureTokenMapContributor.java** (`features/proc/`):
- Line 40: `TokenKind.VARIABLE` → `TokenKind.PARAMETER`

**TokenMapGenerator.java** (`frontend/tokenmap/`):
- Line 222: `TokenKind.VARIABLE` → `TokenKind.REGISTER` — physical register tokens (`%DR0`, `%LR1` etc.) that are not aliases. Previously misclassified as VARIABLE.

**Visualizer:**
- `ParameterTokenHandler.js` line 26: `tokenInfo.tokenType === 'PARAMETER'` instead of `'VARIABLE'`
- `RegisterTokenHandler.js` line 20: `type === 'ALIAS' || type === 'REGISTER'` instead of `type === 'ALIAS' || (type === 'VARIABLE' && token.startsWith('%'))`

**Tests:**
- All existing tests remain green (pure rename).

---

### Step 2: Alias Type Split

Split `Symbol.Type.ALIAS` into three distinct types. Introduces type safety for register aliases in instructions and separates module aliases.

**Symbol.java** (`model/symbols/`):
- Delete `ALIAS`
- Add: `MODULE_ALIAS`, `REGISTER_ALIAS_DATA`, `REGISTER_ALIAS_LOCATION`

**RegAnalysisHandler.java** (`features/reg/`):
- Determine alias type from target register bank during the existing validation loop in `processRegDirective`: if the matched `RegisterBank.isLocation` → `REGISTER_ALIAS_LOCATION`, else → `REGISTER_ALIAS_DATA`
- Currently uses 4-arg Symbol constructor with RegNode — no change to constructor, just the Symbol.Type

**ImportSymbolCollector.java** (`features/importdir/`):
- `Symbol.Type.ALIAS` → `Symbol.Type.MODULE_ALIAS`

**RequireSymbolCollector.java** (`features/require/`):
- `Symbol.Type.ALIAS` → `Symbol.Type.MODULE_ALIAS`

**InstructionAnalysisHandler.java** (`features/instruction/`):
- Replace the single `ALIAS` branch (line 93) with three branches:
  - `REGISTER_ALIAS_DATA` → accepted only in REGISTER position
  - `REGISTER_ALIAS_LOCATION` → accepted only in LOCATION_REGISTER position
  - `MODULE_ALIAS` → compile-time error: "Module alias '%s' cannot be used as an instruction argument."

**AstPostProcessor.java** (`frontend/postprocess/`):
- Replace `Symbol.Type.ALIAS` check (line 105) with both register alias types:

```java
if ((symbol.type() == Symbol.Type.REGISTER_ALIAS_DATA || symbol.type() == Symbol.Type.REGISTER_ALIAS_LOCATION)
        && symbol.node() instanceof IRegisterAlias alias) {
    createRegisterReplacement(idNode, identifierName.toUpperCase(), alias.register());
    return;
}
```

The `instanceof IRegisterAlias` guard is defense-in-depth — the type split already separates register aliases from module aliases, but the instanceof check prevents regressions.

**TokenKindMapper.java** (`frontend/tokenmap/`):
- Replace `case ALIAS → TokenKind.ALIAS` with:
  - `case REGISTER_ALIAS_DATA, REGISTER_ALIAS_LOCATION → TokenKind.ALIAS`
  - `case MODULE_ALIAS → TokenKind.MODULE_ALIAS`

**TokenKind.java** (`api/`):
- New: `MODULE_ALIAS` — for module alias tokens (.IMPORT/.REQUIRE AS names). Enables future visualizer annotation of module aliases.

**Tests:**
- `.REG %POS %LR0` + `SETI %POS 42` → compile error (location register alias in data instruction)
- `.REG %COUNTER %DR0` + `CRLR %COUNTER` → compile error (data register alias in location instruction)
- `.REG %POS %LR0` + `SKLR %POS` → OK (location register alias in location instruction)
- `.REG %COUNTER %DR0` + `SETI %COUNTER 42` → OK (data register alias in data instruction)
- Module alias as instruction argument → compile error
- Existing alias tests remain green

---

### Step 3: CallAnalysisHandler Type Safety

Add symbol type validation for identifier arguments in CALL instructions. Requires Step 1 and Step 2 (all new Symbol types must exist).

**CallAnalysisHandler.java** (`features/proc/`):
- Add symbol resolution for IdentifierNode arguments in REF, VAL, LREF, LVAL validation loops. For each IdentifierNode argument, call `symbolTable.resolve()` and check the symbol type:
  - REF/VAL arguments: accept `REGISTER_ALIAS_DATA` and `PARAMETER_DATA`. Reject `REGISTER_ALIAS_LOCATION` (error: "REF argument '%s' is a location register alias, expected a data register."). Reject `PARAMETER_LOCATION` (error: "REF argument '%s' is a location parameter, expected a data register."). Reject `MODULE_ALIAS` (error: "Module alias '%s' cannot be used as a CALL argument.").
  - LREF/LVAL arguments: accept `REGISTER_ALIAS_LOCATION` and `PARAMETER_LOCATION`. Reject `REGISTER_ALIAS_DATA` (error: "LREF argument '%s' is a data register alias, expected a location register."). Reject `PARAMETER_DATA` (error: "LREF argument '%s' is a data parameter, expected a location register."). Reject `MODULE_ALIAS` (error: "Module alias '%s' cannot be used as a CALL argument.").
  - Unresolved identifiers in REF/LREF positions → error (must resolve to a register). Unresolved identifiers in VAL/LVAL positions → allow (may be forward-referenced labels).

**Tests:**
- `.REG %MY_REG %DR0` + `CALL proc LREF %MY_REG` → compile error (data register alias as LREF argument)
- `.REG %MY_LOC %LR0` + `CALL proc REF %MY_LOC` → compile error (location register alias as REF argument)
- `.REG %MY_LOC %LR0` + `CALL proc LREF %MY_LOC` → OK (location register alias as LREF argument)
- `.REG %MY_REG %DR0` + `CALL proc REF %MY_REG` → OK (data register alias as REF argument)
- Data parameter as LREF argument → compile error
- Location parameter as REF argument → compile error
- Module alias as CALL REF argument → compile error
- Module alias as CALL LREF argument → compile error

---

### Step 4: Parameter Resolution via SymbolTable

Move parameter resolution from Phase 7 (IrGenContext) to Phase 6 (AstPostProcessor) using the existing ScopeTracker and SymbolTable infrastructure. Requires Step 1. Cleaner after Step 3 (CallAnalysisHandler already validates before resolution changes).

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

**ProcedureSymbolCollector.java** (`features/proc/`):
- REF/VAL parameters: `Symbol.Type.PARAMETER_DATA` with `new ParameterBinding("%FDR" + dataIndex)`
- LREF/LVAL parameters: `Symbol.Type.PARAMETER_LOCATION` with `new ParameterBinding("%FLR" + locationIndex)`
- dataIndex increments across REF + VAL (all map to FDR)
- locationIndex increments across LREF + LVAL (all map to FLR)

**AstPostProcessor.java** (`frontend/postprocess/`):
- In `collectReplacements()`, after existing register alias resolution, add parameter resolution:

```java
if ((symbol.type() == Symbol.Type.PARAMETER_DATA || symbol.type() == Symbol.Type.PARAMETER_LOCATION)
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

In `convertOperand()`: delete the `resolveProcedureParam` call and its surrounding if-block (lines 225-228). The IdentifierNode branch becomes:

```java
} else if (node instanceof IdentifierNode id) {
    String nameU = id.text().toUpperCase();
    Optional<IrOperand> constOpt = resolveConstant(nameU);
    if (constOpt.isPresent()) {
        return constOpt.get();
    }
    return new IrLabelRef(id.text());
}
```

After Phase 6, the only IdentifierNodes reaching Phase 7 are constants (resolved here via `resolveConstant`) and label/procedure references (emitted as `IrLabelRef`, linked in Phase 10). All register aliases and parameters have been replaced by RegisterNodes in Phase 6 and take the `RegisterNode` branch (line 214). The `IrLabelRef` fallthrough is correct — an IdentifierNode that is neither constant nor label would be a compiler bug, caught as an undefined-label error in Phase 10 linking.

**ProcedureNodeConverter.java** (`features/proc/`) — DELETE:
- `allDataParams` list building
- `allLocationParams` list building
- `ctx.pushProcedureParams(allDataParams)`
- `ctx.pushProcedureLocationParams(allLocationParams)`
- `ctx.popProcedureLocationParams()`
- `ctx.popProcedureParams()`

What remains: emit proc_enter/proc_exit directives (with lrefArity/lvalArity for marshalling) and convert body via `ctx.convert()`.

**CallNodeConverter.java** (`features/proc/`):
- No changes needed. `ctx.convertOperand()` already handles RegisterNode (which parameters become after Phase 6 resolution) and IdentifierNode (for labels). The `resolveProcedureParam` call is inside `convertOperand` (deleted in IrGenContext above), not in CallNodeConverter.

**Compiler.java:**
- No changes needed. Phase 6 AstPostProcessor already has ScopeTracker. Phase 7 IrGenerator doesn't need new dependencies.

**Tests:**
- Existing parameter scoping tests (RegisterAliasScopeTest) adapted to new Symbol types
- Parameters inside proc resolve to correct FDR/FLR RegisterNodes after Phase 6
- Parameters outside proc scope are not visible
- Shadowing: proc-level parameter shadows module-level alias
- IrGenContext has no procParamScopes/procLocationParamScopes fields (verified via grep)
- All existing integration tests and CLI smoke tests green

---

## Verification

After all steps:

1. **Single Source of Truth**: SymbolTable is the sole authority for all symbol resolution (aliases, parameters, constants, labels). IrGenContext has no scope management.
2. **Phase consistency**: All symbolic register references (aliases AND parameters) resolved in Phase 6. Phase 7 does only AST→IR conversion.
3. **Type safety**: Data symbols (REGISTER_ALIAS_DATA, PARAMETER_DATA) accepted only in REGISTER positions. Location symbols (REGISTER_ALIAS_LOCATION, PARAMETER_LOCATION) accepted only in LOCATION_REGISTER positions. Module aliases (MODULE_ALIAS) rejected in all instruction and CALL argument positions. Compile-time errors for type mismatches.
4. **Correct naming**: Explicit Symbol types — no ambiguous "VARIABLE" or overloaded "ALIAS".
5. **No duplicate scope tracking**: IrGenContext's procParamScopes/procLocationParamScopes eliminated.

## Complete Symbol Type Table

| Symbol.Type | Purpose | InstructionAnalysisHandler | CallAnalysisHandler | TokenKind | Resolution Phase |
|---|---|---|---|---|---|
| REGISTER_ALIAS_DATA | Data register alias (.REG %X %DR0) | REGISTER only | REF/VAL only | ALIAS | Phase 6 (IRegisterAlias) |
| REGISTER_ALIAS_LOCATION | Location register alias (.REG %X %LR0) | LOCATION_REGISTER only | LREF/LVAL only | ALIAS | Phase 6 (IRegisterAlias) |
| MODULE_ALIAS | Module alias (.IMPORT/.REQUIRE AS) | Rejected (error) | Rejected (error) | MODULE_ALIAS | Not resolved (namespace prefix) |
| PARAMETER_DATA | Data proc parameter (REF/VAL) | REGISTER only | REF/VAL only | PARAMETER | Phase 6 (IParameterBinding) |
| PARAMETER_LOCATION | Location proc parameter (LREF/LVAL) | LOCATION_REGISTER only | LREF/LVAL only | PARAMETER | Phase 6 (IParameterBinding) |
| CONSTANT | Named constant (.DEFINE) | LITERAL | — | CONSTANT | Phase 6 (flat map) |
| LABEL | Label definition | LABEL, VECTOR | — | LABEL | Phase 10 (Linking) |
| PROCEDURE | Procedure definition (.PROC) | LABEL | procedure target | PROCEDURE | Phase 10 (Linking) |
| — (no Symbol) | Physical register (%DR0, %LR1) | — (RegisterNode) | — (RegisterNode) | REGISTER | Not resolved (already concrete) |
