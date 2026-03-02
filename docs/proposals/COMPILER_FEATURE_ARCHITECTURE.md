# Compiler Feature Architecture

## Vision

A compiler organized by **features**, not by phases. Each feature is a self-contained
plugin that registers itself into the phases it needs. Phases are pure orchestrators
that know nothing about specific features — they only know their handler interfaces
and dispatch through registries.

Adding a new feature means creating one package with all its components and one
registration file. No phase code is touched.

## Architectural Principles

1. **Feature = Package** — all components of a feature live in one package
2. **Phases are dumb orchestrators** — they dispatch via registries, contain zero feature knowledge
3. **Features register themselves** — each feature has a registration class that wires its components into the phase registries
4. **Handler interfaces stay in their phases** — features depend on phases (Feature → Phase), which is the natural dependency direction; only the reverse (Phase → Feature) is a violation
5. **No cross-feature imports** — features are independent plugins
6. **The orchestrator (Compiler.java) only connects phases** — no business logic
7. **Three pure data format layers** — Token (Phase 1-3), AST (Phase 3-7), IR (Phase 7-11); no cross-dependencies between layers
8. **`SourceInfo` (from `compiler/api/`) is the only shared type** between data format layers — reused internally for source tracking

---

## Current Feature Inventory

14 features spanning 11 phases, with 7 phase-level registries:

| # | Feature | Phases Active In | Components |
|---|---------|-----------------|------------|
| 1 | **instruction** | 3, 4, 7, 8, 10, 11 | InstructionNode, InstructionAnalysisHandler, InstructionNodeConverter, + violations in Parser, ~~TokenMapGenerator~~ (C3), Linker, IrGenContext |
| 2 | **label** | 3, 4, 7, 9, 11 | LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter, + violation in Parser |
| 3 | **proc** | 3, 4, 5, 6, 7, 8, 10 | ProcedureNode, ExportNode, ProcDirectiveHandler, PregNode, PregDirectiveHandler, ProcedureSymbolCollector, ProcedureAnalysisHandler, PregAnalysisHandler, ProcedureNodeConverter, ProcedureMarshallingRule, CallerMarshallingRule, CallBindingCaptureRule, RefValBindingCaptureRule, + violations in ~~TokenMapGenerator~~ (C3), Linker, Compiler. `.PREG` is part of proc because it aliases procedure registers and only exists inside `.PROC` blocks. |
| 4 | **reg** | 3, 4, 6 | RegNode, RegDirectiveHandler, RegAnalysisHandler, + AstPostProcessor |
| 5 | **define** | 3, 4, 6, 7 | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefineNodeConverter, + violation in AstPostProcessor |
| 6 | **org** | 3, 7, 9 | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler |
| 7 | **dir** | 3, 7, 9 | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler |
| 8 | **place** | 3, 7, 9 | PlaceNode, PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler, + placement AST/IR types |
| 9 | **import** | 0, 2, 3, 4, 7 | ImportNode, ImportSourceHandler, ImportDirectiveHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter |
| 10 | **require** | 0, 3, 4, 7 | RequireNode, RequireDirectiveHandler, RequireSymbolCollector, RequireAnalysisHandler, RequireNodeConverter |
| 11 | **source** | 0, 2 | SourceDirectiveHandler, SourceLoader |
| 12 | **macro** | 2 | MacroDirectiveHandler, MacroDefinition, + hardcoded expandMacro() in PreProcessor |
| 13 | **repeat** | 2 | RepeatDirectiveHandler, CaretDirectiveHandler |
| 14 | **ctx** | 2, 3, 7, 9 | PushCtxNode, PopCtxNode, PopCtxDirectiveHandler (preprocessor), PushCtxDirectiveHandler (parser), PopCtxDirectiveHandler (parser), PushCtxNodeConverter, PopCtxNodeConverter, PushCtxLayoutHandler, PopCtxLayoutHandler |

---

## Current Phase Registries

| Phase | Registry | Interface | Key Type |
|-------|----------|-----------|----------|
| Phase 2 (PreProcessor) | `PreProcessorDirectiveRegistry` | `IPreProcessorDirectiveHandler` | Directive name string |
| Phase 3 (Parser) | `ParserDirectiveRegistry` | `IParserDirectiveHandler` | Directive name string |
| Phase 4 (Semantics) | `AnalysisHandlerRegistry` | `IAnalysisHandler` + `ISymbolCollector` | AST node class |
| Phase 7 (IrGen) | `IrConverterRegistry` | `IAstNodeToIrConverter<T>` | AST node class |
| Phase 8 (Emission) | `EmissionRegistry` | `IEmissionRule` | Ordered list (no key) |
| Phase 9 (Layout) | `LayoutDirectiveRegistry` | `ILayoutDirectiveHandler` | Namespace:name string |
| Phase 10 (Linking) | `LinkingRegistry` | `ILinkingRule` | Ordered list (no key) |

| Phase 5 (TokenMap) | `TokenMapContributorRegistry` | `ITokenMapContributor` | AST node class |

**Missing registries** (feature logic hardcoded in phase):
- Phase 6 (PostProcess) — no registry, logic hardcoded in `AstPostProcessor`

---

## Data Format Layers

The compiler has three distinct data representations. Each belongs to a phase group,
and each is **pure** — no cross-dependencies between layers.

```
model/token/  ──→  Phase 1 (Lexer) → Phase 2 (PreProcessor) → Phase 3 (Parser)
model/ast/    ──→  Phase 3 (Parser) → Phase 4 (Semantics) → Phase 5 (TokenMap)
                   → Phase 6 (PostProcess) → Phase 7 (IrGen)
model/ir/     ──→  Phase 7 (IrGen) → Phase 8 (Emission) → Phase 9 (Layout)
                   → Phase 10 (Link) → Phase 11 (Emit)
```

**Rules:**
- Token does NOT import AST or IR
- AST does NOT import Token or IR
- IR does NOT import Token or AST
- `SourceInfo` is the only type shared across all three layers

Currently AST nodes store Token references (e.g., `InstructionNode(Token opcode, ...)`).
This must be refactored: AST nodes store extracted values (String, int) + SourceInfo.
The Parser is the boundary that reads Tokens and produces AST nodes with extracted data.

`SourceLocatable` is modernized to `ISourceLocatable` and moved to `model/ast/`.
The method changes from `getSourceFileName()` to `sourceInfo()` returning `SourceInfo`.
AST nodes that originate from source code implement `ISourceLocatable`; synthetic nodes
(PushCtxNode, PopCtxNode) do not.

---

## Target Package Structure

```
org.evochora.compiler
├── model/                              # Data format layers
│   │                                   # SourceInfo from compiler/api/ used by all layers
│   ├── token/                          # Phase 1-3 data format
│   │   ├── Token.java
│   │   └── TokenType.java
│   ├── ast/                            # Phase 3-7 data format (core grammar only)
│   │   ├── AstNode.java               # No Token imports
│   │   ├── ISourceLocatable.java      # Capability interface: sourceInfo() → SourceInfo
│   │   ├── InstructionNode.java        # Core grammar, not a feature
│   │   ├── IdentifierNode.java         # Core grammar
│   │   ├── NumberLiteralNode.java      # Core grammar
│   │   ├── RegisterNode.java          # Core grammar
│   │   ├── TypedLiteralNode.java      # Core grammar
│   │   └── VectorLiteralNode.java     # Core grammar
│   └── ir/                             # Phase 7-11 data format
│       ├── IrProgram.java              # Moved from compiler/ir/
│       ├── IrInstruction.java
│       └── ...
├── features/                           # Self-contained feature plugins
│   ├── instruction/                    # Core instruction handling
│   │   ├── InstructionAnalysisHandler.java
│   │   ├── InstructionNodeConverter.java
│   │   ├── InstructionTokenMapContributor.java  # NEW (extracted from TokenMapGenerator)
│   │   └── InstructionFeature.java     # Registration
│   ├── label/
│   │   ├── LabelNode.java
│   │   ├── LabelSymbolCollector.java
│   │   ├── LabelAnalysisHandler.java
│   │   ├── LabelNodeConverter.java
│   │   └── LabelFeature.java
│   ├── proc/
│   │   ├── ProcedureNode.java
│   │   ├── ExportNode.java
│   │   ├── ProcDirectiveHandler.java
│   │   ├── PregNode.java              # .PREG is part of proc (aliases PRs, only inside .PROC)
│   │   ├── PregDirectiveHandler.java
│   │   ├── ProcedureSymbolCollector.java
│   │   ├── ProcedureAnalysisHandler.java
│   │   ├── PregAnalysisHandler.java
│   │   ├── ProcAliasState.java        # NEW — PR alias state, implements IScopedParserState
│   │   ├── ProcPostProcessHandler.java # NEW — resolves PR aliases (runs before RegPostProcessHandler)
│   │   ├── ProcedureNodeConverter.java
│   │   ├── ProcedureMarshallingRule.java
│   │   ├── CallerMarshallingRule.java
│   │   ├── CallBindingCaptureRule.java
│   │   ├── RefValBindingCaptureRule.java
│   │   ├── CallSiteBindingRule.java    # NEW (extracted from Linker)
│   │   ├── ProcedureTokenMapContributor.java  # NEW (extracted from TokenMapGenerator)
│   │   └── ProcFeature.java           # Registration
│   ├── reg/
│   │   ├── RegNode.java
│   │   ├── RegDirectiveHandler.java
│   │   ├── RegAnalysisHandler.java
│   │   ├── RegisterAliasState.java    # DR+LR alias state, implements IScopedParserState
│   │   ├── RegPostProcessHandler.java  # NEW (extracted from AstPostProcessor)
│   │   └── RegFeature.java
│   ├── define/
│   │   ├── DefineNode.java
│   │   ├── DefineDirectiveHandler.java
│   │   ├── DefineAnalysisHandler.java
│   │   ├── DefinePostProcessHandler.java  # NEW (extracted from AstPostProcessor)
│   │   ├── DefineNodeConverter.java
│   │   └── DefineFeature.java
│   ├── org/
│   │   ├── OrgNode.java
│   │   ├── OrgDirectiveHandler.java
│   │   ├── OrgNodeConverter.java
│   │   ├── OrgLayoutHandler.java
│   │   └── OrgFeature.java
│   ├── dir/
│   │   ├── DirNode.java
│   │   ├── DirDirectiveHandler.java
│   │   ├── DirNodeConverter.java
│   │   ├── DirLayoutHandler.java
│   │   └── DirFeature.java
│   ├── place/
│   │   ├── PlaceNode.java
│   │   ├── PlaceDirectiveHandler.java
│   │   ├── PlaceNodeConverter.java
│   │   ├── PlaceLayoutHandler.java
│   │   ├── placement/                  # Placement AST + IR sub-types
│   │   │   └── ...
│   │   └── PlaceFeature.java
│   ├── importdir/
│   │   ├── ImportNode.java
│   │   ├── ImportSourceHandler.java
│   │   ├── ImportDirectiveHandler.java
│   │   ├── ImportSymbolCollector.java
│   │   ├── ImportAnalysisHandler.java
│   │   ├── ImportNodeConverter.java
│   │   └── ImportFeature.java
│   ├── require/
│   │   ├── RequireNode.java
│   │   ├── RequireDirectiveHandler.java
│   │   ├── RequireSymbolCollector.java
│   │   ├── RequireAnalysisHandler.java
│   │   ├── RequireNodeConverter.java
│   │   └── RequireFeature.java
│   ├── source/
│   │   ├── SourceDirectiveHandler.java
│   │   └── SourceFeature.java
│   ├── macro/
│   │   ├── MacroDirectiveHandler.java
│   │   ├── MacroDefinition.java
│   │   ├── MacroExpansionHandler.java  # NEW (extracted from PreProcessor)
│   │   └── MacroFeature.java
│   ├── repeat/
│   │   ├── RepeatDirectiveHandler.java
│   │   ├── CaretDirectiveHandler.java
│   │   └── RepeatFeature.java
│   └── ctx/
│       ├── PushCtxNode.java
│       ├── PopCtxNode.java
│       ├── CtxPopPreProcessorHandler.java  # Preprocessor-level .POP_CTX (chain popping)
│       ├── PushCtxDirectiveHandler.java    # Parser-level .PUSH_CTX
│       ├── PopCtxDirectiveHandler.java     # Parser-level .POP_CTX
│       ├── PushCtxNodeConverter.java
│       ├── PopCtxNodeConverter.java
│       ├── PushCtxLayoutHandler.java
│       ├── PopCtxLayoutHandler.java
│       └── CtxFeature.java
├── util/                               # Infrastructure utilities
│   ├── SourceLoader.java
│   └── ModuleContextTracker.java       # Shared by Phase 4 + Phase 6
├── api/                                # Public API (unchanged)
├── internal/                           # API serialization helpers (unchanged)
├── ICompilerFeature.java               # Feature registration interface
├── IFeatureRegistrationContext.java    # Registration API for features (pure declarative, no getters)
├── FeatureRegistry.java               # Collects registrations, provides getters for Compiler
├── Compiler.java                       # Orchestrator (thin)
├── frontend/                           # Pure phase orchestrators + registries
│   ├── lexer/                          # Phase 1: Lexer.java only
│   ├── preprocessor/                   # Phase 2: PreProcessor.java + registry
│   ├── parser/                         # Phase 3: Parser.java + ParsingContext + ParserState + IScopedParserState + registry
│   ├── semantics/                      # Phase 4: SemanticAnalyzer.java + registry
│   ├── tokenmap/                       # Phase 5: TokenMapGenerator.java + registry (NEW)
│   ├── postprocess/                    # Phase 6: AstPostProcessor.java + registry (NEW)
│   ├── irgen/                          # Phase 7: IrGenerator.java + registry
│   └── module/                         # Phase 0: DependencyScanner.java
└── backend/                            # Backend phase orchestrators + registries
    ├── layout/                         # Phase 9: LayoutEngine.java + registry
    ├── link/                           # Phase 10: Linker.java + registry
    └── emit/                           # Phase 8+11: EmissionRegistry + Emitter.java
```

---

## Feature Registration Pattern

Each feature has a registration class that implements `ICompilerFeature`:

```java
// compiler/ICompilerFeature.java
public interface ICompilerFeature {
    String name();  // for diagnostics/logging
    void register(IFeatureRegistrationContext ctx);
}

// compiler/IFeatureRegistrationContext.java — pure declarative, no state accessors
public interface IFeatureRegistrationContext {
    // Phase 0: Dependency Scanning
    void dependencyScanHandler(IDependencyScanHandler handler);
    // Phase 2: Preprocessing
    void preprocessor(String directive, IPreProcessorDirectiveHandler handler);
    // Phase 3: Parsing
    void parser(String directive, IParserDirectiveHandler handler);
    // Phase 4: Semantic Analysis
    void symbolCollector(Class<? extends AstNode> nodeType, ISymbolCollector collector);
    void analysisHandler(Class<? extends AstNode> nodeType, IAnalysisHandler handler);
    // Phase 5: Token Map Generation
    void tokenMapContributor(Class<? extends AstNode> nodeType, ITokenMapContributor contributor);
    // Phase 6: AST Post-Processing
    void postProcessHandler(Class<? extends AstNode> nodeType, IPostProcessHandler handler);
    // Phase 7: IR Generation
    <T extends AstNode> void irConverter(Class<T> nodeType, IAstNodeToIrConverter<T> converter);
    // Phase 8: IR Rewriting (Emission)
    void emissionRule(IEmissionRule rule);
    // Phase 9: Layout
    void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler);
    // Phase 10: Linking
    void linkingRule(ILinkingRule rule);
}
```

Example — the `import` feature:

```java
// compiler/features/importdir/ImportFeature.java
public class ImportFeature implements ICompilerFeature {
    @Override
    public String name() { return "import"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // moduleTokens flows through PreProcessorContext at execution time, not here
        ctx.preprocessor(".IMPORT", new ImportSourceHandler());
        ctx.parser(".IMPORT", new ImportDirectiveHandler());
        ctx.symbolCollector(ImportNode.class, new ImportSymbolCollector());
        ctx.analysisHandler(ImportNode.class, new ImportAnalysisHandler());
        ctx.irConverter(ImportNode.class, new ImportNodeConverter());
    }
}
```

Example — the `proc` feature (spans many phases, includes `.PREG`):

```java
// compiler/features/proc/ProcFeature.java
public class ProcFeature implements ICompilerFeature {
    @Override
    public String name() { return "proc"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.parser(".PROC", new ProcDirectiveHandler());
        ctx.parser(".PREG", new PregDirectiveHandler());
        ctx.symbolCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        ctx.analysisHandler(ProcedureNode.class, new ProcedureAnalysisHandler());
        ctx.analysisHandler(PregNode.class, new PregAnalysisHandler());
        ctx.tokenMapContributor(ProcedureNode.class, new ProcedureTokenMapContributor());
        ctx.postProcessHandler(PregNode.class, new ProcPostProcessHandler());
        ctx.irConverter(ProcedureNode.class, new ProcedureNodeConverter());
        ctx.emissionRule(new CallBindingCaptureRule());
        ctx.emissionRule(new RefValBindingCaptureRule());
        ctx.emissionRule(new ProcedureMarshallingRule());
        ctx.emissionRule(new CallerMarshallingRule());
        ctx.linkingRule(new CallSiteBindingRule());
    }
}
```

The Compiler discovers features at startup:

```java
// In Compiler.java — the ONLY place that knows about all features
List<ICompilerFeature> features = List.of(
    new InstructionFeature(),
    new LabelFeature(),
    new ProcFeature(),       // includes .PREG (PR aliases only exist inside .PROC)
    new RegFeature(),
    new DefineFeature(),
    new OrgFeature(),
    new DirFeature(),
    new PlaceFeature(),
    new ImportFeature(),
    new RequireFeature(),
    new SourceFeature(),
    new MacroFeature(),
    new RepeatFeature(),
    new CtxFeature()
);
FeatureRegistry registry = new FeatureRegistry();
features.forEach(f -> f.register(registry));
```

Phase registries are then populated from the context — no phase knows any feature.

---

## Violations Resolved

After full implementation, every violation from COMPILER_PACKAGE_STRUCTURE_CLEANUP.md
is resolved:

| Violation | How It's Resolved |
|-----------|-------------------|
| Shared types in phase packages | Token/TokenType in `compiler/model/`, core AST in `compiler/model/ast/`, feature nodes in `features/*/` |
| SourceDirectiveHandler calls Lexer | Pre-lexed tokens via `FeatureRegistrationContext.sourceTokens()` |
| Macro expansion hardcoded in PreProcessor | Extracted to `MacroExpansionHandler`, registered as fallback handler |
| Parser handlers downcast ParsingContext | `ParsingContext` extended with `expression()`, `declaration()`, `state()`. `ParserState` is a generic type-safe container — features store their own state classes, phase code has no feature imports. Only `registerProcedure()` cast remains (resolved in D13). |
| Compiler.java contains business logic | Procedure metadata extraction moves to `ProcFeature` components |
| IrGenContext instanceof chains | `ISourceLocatable.sourceInfo()` — IrGenContext uses SourceInfo via capability interface |
| TokenMapGenerator hardcodes features | Registry-based `ITokenMapContributor` dispatch. **RESOLVED** in C3: `ProcedureTokenMapContributor` + `InstructionTokenMapContributor` extracted, `Scope.name()` eliminates all `ProcedureNode` references from `TokenMapGenerator`. |
| LabelRefLinkingRule imports SymbolTable | Pre-resolved qualified names map, or SymbolTable in `compiler/model/` |
| Linker hardcodes CALL | `CallSiteBindingRule` in `features/proc/` |
| Magic opcode strings | Constants in feature packages or shared `Opcodes` class |
| Frontend imports runtime Config | `MachineConstraints` in `compiler/model/` |

---

## Implementation Steps

**Invariant: Every step compiles and all tests pass.** Steps are ordered so that problems
surface immediately through compilation and tests, not at the end.

### Phase A: Data Model Relocation

Pure package moves — only import paths change, no behavioral changes.

| Step | Description |
|------|-------------|
| A1 | Move Token + TokenType → `compiler/model/token/`. **DONE.** |
| A2 | Move core AST nodes → `compiler/model/ast/` (AstNode, InstructionNode, IdentifierNode, NumberLiteralNode, RegisterNode, TypedLiteralNode, VectorLiteralNode). Keep Token references and SourceLocatable as-is — decoupled in Phase B. **DONE.** |
| A3 | Move IR types → `compiler/model/ir/` (from existing `compiler/ir/`). **DONE.** |

After Phase A all three data format layers are in their target packages.
AST still imports Token — that's intentional, decoupled incrementally in Phase B.

### Phase B: AST-Token Decoupling

Incremental refactoring of the 7 core AST nodes in `model/ast/`. Feature-specific AST nodes
(DefineNode, ImportNode, LabelNode, etc. in `frontend/parser/features/`) remain unchanged
here — they are decoupled when moved to `features/` in Phase D (see per-step notes there).

| Step | Description |
|------|-------------|
| B1 | Modernize `SourceLocatable` → `ISourceLocatable` in `model/ast/`. Method changes from `getSourceFileName()` to `sourceInfo()` returning `SourceInfo`. Move from `frontend/parser/ast/` to `model/ast/`. Update all 5 current implementers (InstructionNode, LabelNode, RequireNode, ProcedureNode, ImportNode) and consumers (ModuleContextTracker, SemanticAnalyzer). |
| B2 | Simplify IrGenContext: replace `getRepresentativeToken()` instanceof chains with `ISourceLocatable.sourceInfo()`. |
| B3 | Refactor core AST nodes to store extracted values + SourceInfo instead of Token references. **One node per commit**, consumers updated with each node. All 7 nodes implement `ISourceLocatable`. See node specifications below. |
| B4 | Verify data format purity: `model/ast/` has zero imports from `model/token/` or `model/ir/`. |

**B3 Node Specifications (recommended commit order — simplest to largest blast radius):**

| # | Node | Current Fields | New Fields | Type |
|---|------|---------------|------------|------|
| 1 | NumberLiteralNode | `(Token numberToken)` | `(int value, SourceInfo sourceInfo)` | record |
| 2 | IdentifierNode | `(Token identifierToken)` | `(String text, SourceInfo sourceInfo)` | record |
| 3 | TypedLiteralNode | `(Token type, Token value)` | `(String typeName, int value, SourceInfo sourceInfo)` | record |
| 4 | VectorLiteralNode | `(List<Token> components)` | `(List<Integer> values, SourceInfo sourceInfo)` | record |
| 5 | RegisterNode | `(String name, String originalAlias, SourceInfo sourceInfo, Token registerToken)` | `(String name, String originalAlias, SourceInfo sourceInfo)` — remove Token field, rename `getSourceInfo()` → `sourceInfo()` | class |
| 6 | InstructionNode | `(Token opcode, List<AstNode> args, List<AstNode> refArgs, List<AstNode> valArgs)` | `(String opcode, List<AstNode> args, List<AstNode> refArgs, List<AstNode> valArgs, SourceInfo sourceInfo)` | record |

Key accessor changes per node:
- **NumberLiteralNode**: `getValue()` / `numberToken().value()` → `value()`. Parsing moves to Parser.
- **IdentifierNode**: `identifierToken().text()` → `text()`.
- **TypedLiteralNode**: `type().text()` → `typeName()`, `value().text()` → consumers use `value()` directly (int). Value parsing moves to Parser.
- **VectorLiteralNode**: `components().stream().mapToInt(tok -> parseInt(tok.text()))` → `values()` directly. Parsing moves to Parser.
- **RegisterNode**: `registerToken().text()` → `getName()` (already exists), `registerToken().fileName()/line()` → `sourceInfo().fileName()/lineNumber()` (SourceInfo already exists, Token field removed).
- **InstructionNode**: `opcode().text()` → `opcode()` (same accessor name, return type changes Token → String), `getSourceFileName()` → `sourceInfo().fileName()`.

### Phase C: Registration Infrastructure

Create the interfaces and registries needed for feature consolidation.

| Step | Description |
|------|-------------|
| C1 | Create `ICompilerFeature` + `IFeatureRegistrationContext` + `FeatureRegistry` in `compiler/`. Create `IDependencyScanHandler` + `IDependencyScanContext` in `frontend/module/`. Create stub interfaces `ITokenMapContributor`/`ITokenMapContext` in `frontend/tokenmap/` and `IPostProcessHandler`/`IPostProcessContext` in `frontend/postprocess/`. |
| C2 | Extend `ParsingContext` with `expression()`, `declaration()`, `state()`. Create `ParserState` (generic type-safe container) and `RegisterAliasState` (temporarily in `parser/` — moves to `features/reg/` in D8). Eliminate 6 of 7 handler casts. Only `registerProcedure()` cast remains in ProcDirectiveHandler (resolved in D13). **DONE.** |
| C3 | Refactor `TokenMapGenerator` to dispatch through `TokenMapContributorRegistry`. Populate `ITokenMapContext` (3 methods). Extract `ProcedureTokenMapContributor` and `InstructionTokenMapContributor`. Add `Scope.name()` to `SymbolTable.Scope` and `deriveModuleName()` to `ModuleId` for module-qualified scope names (e.g., `MAIN.INIT`). Replace `findScopeByName()` with direct Scope-object tracking. Eliminates all `ProcedureNode` references from `TokenMapGenerator`. **DONE.** |
| C4 | Create `IPostProcessHandler` + `PostProcessHandlerRegistry`, refactor `AstPostProcessor` to dispatch through registry. |
| C5 | Extract `MacroExpansionHandler` from `PreProcessor.expandMacro()`, register as handler in `PreProcessorDirectiveRegistry`. |
| C6 | Extract `CallSiteBindingRule` from `Linker`, move CALL detection into linking rule. |
| C7 | Create `IScopedParserState` interface in `parser/`. Add `pushScope()`/`popScope()` to `ParserState` — propagates to all registered `IScopedParserState` objects. Pure parser infrastructure, no feature imports. |

### Phase D: Feature Consolidation

Move all feature components into `compiler/features/*/` packages, one feature at a time.
Each step creates the feature package, moves all components, creates the `XxxFeature`
registration class, and updates the corresponding registry initialization.

Feature-specific AST nodes that still hold Token references are decoupled from Token
as part of their move (same approach as Phase B: replace Token fields with extracted
values + SourceInfo, implement `ISourceLocatable`). Steps marked with **[+decouple]**
include this Token decoupling work.

| Step | Feature | Files to Move | New Files |
|------|---------|--------------|-----------|
| D1 | repeat | RepeatDirectiveHandler, CaretDirectiveHandler | RepeatFeature.java |
| D2 | source | SourceDirectiveHandler | SourceFeature.java |
| D3 | macro | MacroDirectiveHandler, MacroDefinition, MacroExpansionHandler | MacroFeature.java |
| D4 | ctx | PushCtx/PopCtx Nodes + Parser Handlers + Preprocessor PopCtxHandler + Converters + LayoutHandlers | CtxFeature.java |
| D5 | org | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler | OrgFeature.java |
| D6 | dir | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler | DirFeature.java |
| D7 | define | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefinePostProcessHandler, DefineNodeConverter. **[+decouple]** DefineNode: Token `name` → String + SourceInfo | DefineFeature.java |
| D8 | reg | See D8 details below. | RegFeature.java |
| D9 | label | LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter. **[+decouple]** LabelNode: Token `labelToken` → String + SourceInfo | LabelFeature.java |
| D10 | place | PlaceNode, PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler, placement/. **[+decouple]** placement sub-nodes: RangeValueComponent, SingleValueComponent, SteppedRangeValueComponent, WildcardValueComponent — Token fields → extracted values + SourceInfo | PlaceFeature.java |
| D11 | require | RequireNode, RequireDirectiveHandler, RequireSymbolCollector, RequireAnalysisHandler, RequireNodeConverter. **[+decouple]** RequireNode: Token `path`, `alias` → String + SourceInfo | RequireFeature.java |
| D12 | importdir | ImportNode, ImportSourceHandler, ImportDirectiveHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter. **[+decouple]** ImportNode: Token `path`, `alias` + UsingClause Tokens → String + SourceInfo | ImportFeature.java |
| D13 | proc | See D13 details below. | ProcFeature.java |
| D14 | instruction | InstructionAnalysisHandler, InstructionNodeConverter, InstructionTokenMapContributor | InstructionFeature.java |

Order rationale: start with simple features (few phases), end with complex ones
(proc spans 6+ phases). Each step is independently committable with all tests green.
D1-D6 have no Token decoupling; D7-D13 include Token decoupling of their feature nodes.

**D8 details (reg):**

Move to `features/reg/`: RegNode, RegDirectiveHandler, RegAnalysisHandler, RegPostProcessHandler (NEW, extracted from AstPostProcessor).

Additionally:
1. Move `RegisterAliasState` from `parser/` (temporary C2 location) to `features/reg/`. Add `implements IScopedParserState`.
2. Remove `getGlobalRegisterAliases()` from Parser. Compiler.java reads aliases via `parser.state().get(RegisterAliasState.class).getGlobalAliases()` (or equivalent extraction pattern, see F2).
3. Update tests that call `parser.getGlobalRegisterAliases()`: RegDirectiveTest, DefineDirectiveTest, ModuleSourceDefineIntegrationTest.
4. **[+decouple]** RegNode: Token `alias`, `register` → String + SourceInfo.

**D13 details (proc — includes preg):**

Move to `features/proc/`: ProcedureNode, ExportNode, ProcDirectiveHandler, PregNode, PregDirectiveHandler, ProcedureSymbolCollector, ProcedureAnalysisHandler, PregAnalysisHandler, ProcedureNodeConverter, ProcedureMarshallingRule, CallerMarshallingRule, CallBindingCaptureRule, RefValBindingCaptureRule, CallSiteBindingRule, ProcedureTokenMapContributor.

Additionally:
1. Create `ProcAliasState` (implements `IScopedParserState`) in `features/proc/` — manages PR aliases. PregDirectiveHandler uses ProcAliasState instead of RegisterAliasState.
2. Create `ProcPostProcessHandler` in `features/proc/` — resolves PR aliases in AST. Registered with higher priority than RegPostProcessHandler so PR aliases shadow DR/LR aliases on name conflict.
3. ProcDirectiveHandler: replace `context.state().getOrCreate(RegisterAliasState.class, ...)` with `context.state().pushScope()` / `context.state().popScope()` (generic, no feature imports). Replace `context.declaration()` (already done in C2). Resolve `registerProcedure()` cast — procedure registration moves to ProcFeature or a clean cross-phase extraction pattern.
4. Remove from Parser: `registerProcedure()`, `getProcedureTable()`, `procedureTable` field.
5. Update tests: ProcedureDirectiveTest (calls `parser.getProcedureTable()`), PregDirectiveTest.
6. **[+decouple]** ProcedureNode: Token `name`, `parameters`, `refParameters`, `valParameters` → String/List\<String\> + SourceInfo. ExportNode: Token `exportedName` → String + SourceInfo. PregNode: Token `alias`, `targetRegister` → String + SourceInfo.

### Phase E: Wiring

| Step | Description |
|------|-------------|
| E1 | Wire `CompilerFeature` registration in Compiler.java: create all features, register via `FeatureRegistrationContext`, replace all `initializeWithDefaults()` in registries. |
| E2 | Delete empty `frontend/*/features/` directories. |
| E3 | Move remaining shared types (SymbolTable, Symbol, ModuleId, ModuleDescriptor, DependencyGraph, ModuleContextTracker, SourceLoader) to `compiler/model/` and `compiler/util/`. |

### Phase F: Cleanup (optional, low priority)

| Step | Description |
|------|-------------|
| F1 | Refactor SourceDirectiveHandler to use pre-lexed tokens (same as ImportSourceHandler). |
| F2 | Extract cross-phase data extraction from Compiler.java: register alias data (currently `parser.getGlobalRegisterAliases()`) and procedure table data (currently `parser.getProcedureTable()`) flow through feature-provided mechanisms instead of Parser accessor methods. |
| F3 | Introduce MachineConstraints, remove runtime Config imports from features. |

### Phase G: LR Parameter Passing (open design — needs discussion before committing)

**Goal:** Enable passing LR values as procedure parameters, giving procedures their own
location register space without requiring a new register bank (LPR).

**Status:** The design below is a starting point. Several open questions remain (see below).
This phase is independent of Phases A-F and can be implemented at any time after D13.

**Runtime fact:** FPR stores `Object`, LR values are `int[]` (dimension-agnostic coordinate
vectors). The data stack is `Deque<Object>`. At the runtime level, LR values can already
flow through the data stack and FPR without code changes.

**Proposed approach — LR save/restore + location stack marshalling:**

| Step | Description |
|------|-------------|
| G1 | Runtime: ProcedureCallHandler saves/restores LRs on CALL/RETURN (same pattern as existing PR/FPR save/restore). This gives procedures their own LR space — a procedure can navigate freely via DP without affecting the caller's coordinates. |
| G2 | Compiler: ProcedureMarshallingRule generates PUSL/POPL (location stack) instead of PUSH/POP (data stack) for LR parameters. LR values never flow through the data stack or FPR, preserving the LR integrity invariant (LRs can only contain coordinates visited via DP). |
| G3 | Parser: Allow LR in `.PROC` parameter declarations (REF/VAL). |

**Open questions that must be resolved before implementation:**

1. **LR integrity with FPR path:** The PUSL/POPL approach preserves LR integrity (no data→LR
   path). But is the location stack the right mechanism, or should there be a more direct
   LR-to-LR parameter transfer? PUSL/POPL use the shared location stack, which could conflict
   with user code that also uses the location stack.

2. **Procedure-local LR aliases:** With LR save/restore (G1), a procedure effectively has its
   own LRs. Should `.REG` aliases for LR inside a `.PROC` work differently than global `.REG`
   aliases? Currently IScopedParserState handles this (proc-local `.REG %POS %LR0` disappears
   at `.ENDP`), but the semantics may need refinement — a proc-local LR alias refers to the
   procedure's saved LR, not the caller's.

3. **Full symmetry with DR/PR:** DRs have their own procedure-local bank (PR). With G1, LRs
   get save/restore but no separate bank. This means the callee's LRs start as copies of the
   caller's LRs (then diverge via DP). Is this the desired semantics, or should procedure LRs
   start uninitialized (like PRs)? If uninitialized, should there be a dedicated `.PREG`-like
   directive for LR parameter aliasing, or is `.REG` sufficient?

4. **Nested calls and stack depth:** LR save/restore adds 4 coordinate vectors per call frame.
   For deeply nested or recursive procedures, this increases memory usage. Is this acceptable,
   or should LR save/restore be opt-in (only for procedures that declare LR parameters)?

---

## Verification Criteria

After ALL steps are complete:

1. **All 14 features are each one package** under `compiler/features/`
2. **Every feature has a registration class** implementing `CompilerFeature`
3. **Phase classes contain zero feature-specific imports** — only handler interfaces from their own phase package
4. **No cross-feature imports** — features are independent
5. **Compiler.java is thin** — only phase orchestration and feature discovery
6. **All phase registries are populated via FeatureRegistrationContext** — no `initializeWithDefaults()`
7. **Data format layer purity**: `model/token/` has zero imports from `model/ast/` or `model/ir/`; `model/ast/` has zero imports from `model/token/` or `model/ir/`; `model/ir/` has zero imports from `model/token/` or `model/ast/`. The only shared type across layers is `SourceInfo`.
8. **AST nodes store no Token references** — only extracted values (String, int, etc.) + SourceInfo
9. **`SourceLocatable` modernized to `ISourceLocatable`** in `model/ast/` — returns `SourceInfo` instead of `String getSourceFileName()`; all source-tracked AST nodes implement it, synthetic nodes (PushCtxNode, PopCtxNode) do not
10. **All tests green** after each step

## Constraints

- Each step must leave all tests green
- Each step is an independent commit
- Discussion and approval required before each step
- No behavioral changes — same compiler output before and after
