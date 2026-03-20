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

14 features spanning 11 phases, with 12 phase-level registries (10 existing + 2 planned in F3):

| # | Feature | Phases Active In | Components |
|---|---------|-----------------|------------|
| 1 | **instruction** | 3, 4, 7, 8, 10, 11 | InstructionNode, InstructionAnalysisHandler, InstructionNodeConverter, + violations in Parser, ~~TokenMapGenerator~~ (C3), Linker, IrGenContext |
| 2 | **label** | 3, 4, 7, 9, 11 | LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter, + violation in Parser |
| 3 | **proc** | 3, 4, 5, 6, 7, 8, 10, 11 | ProcedureNode, ProcDirectiveHandler, PregNode, PregDirectiveHandler, CallInstructionHandler, ProcedureSymbolCollector, ProcedureAnalysisHandler, PregAnalysisHandler, ProcedureNodeConverter, ProcedureMarshallingRule, CallerMarshallingRule, ProcedureEmissionContributor, + violations in ~~TokenMapGenerator~~ (C3), Linker, ~~Compiler~~ (C7). `.PREG` is part of proc because it aliases procedure registers and only exists inside `.PROC` blocks. |
| 4 | **reg** | 3, 4, 6 | RegNode, RegDirectiveHandler, RegAnalysisHandler, + AstPostProcessor |
| 5 | **define** | 3, 4, 6, 7 | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefineNodeConverter, + violation in AstPostProcessor |
| 6 | **org** | 3, 7, 9 | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler |
| 7 | **dir** | 3, 7, 9 | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler |
| 8 | **place** | 3, 7, 9 | PlaceNode, PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler, + placement AST/IR types |
| 9 | **import** | 0, 2, 3, 4, 7 | ImportNode, ImportSourceHandler, ImportDirectiveHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter |
| 10 | **require** | 0, 3, 4, 7 | RequireNode, RequireDirectiveHandler, RequireSymbolCollector, RequireAnalysisHandler, RequireNodeConverter |
| 11 | **source** | 0, 2 | SourceDirectiveHandler, SourceLoader |
| 12 | **macro** | 2 | MacroDirectiveHandler, MacroDefinition, MacroExpansionHandler |
| 13 | **repeat** | 2 | RepeatDirectiveHandler, CaretDirectiveHandler |
| 14 | **ctx** | 2, 3, 7, 9 | PushCtxNode, PopCtxNode, PopCtxDirectiveHandler (preprocessor), PushCtxDirectiveHandler (parser), PopCtxDirectiveHandler (parser), PushCtxNodeConverter, PopCtxNodeConverter, PushCtxLayoutHandler, PopCtxLayoutHandler |

---

## Current Phase Registries

| Phase | Registry | Interface | Key Type |
|-------|----------|-----------|----------|
| Phase 0 (DependencyScanning) | `DependencyScanner` | `IDependencyScanHandler` | Pattern-based | *(created in F3a)* |
| Phase 2 (PreProcessor) | `PreProcessorHandlerRegistry` | `IPreProcessorHandler` | Token text string |
| Phase 3 (Parser) | `ParserStatementRegistry` | `IParserStatementHandler` | Keyword string + default handler | *(renamed from ParserDirectiveRegistry in D13d)* |
| Phase 4 (ModuleSetup) | `ModuleSetupRegistry` | `IDependencySetupHandler<T>` | IDependencyInfo class | *(created in F3b, runs before AST walk)* |
| Phase 4 (Semantics) | `AnalysisHandlerRegistry` | `IAnalysisHandler` + `ISymbolCollector` | AST node class |
| Phase 7 (IrGen) | `IrConverterRegistry` | `IAstNodeToIrConverter<T>` | AST node class |
| Phase 8 (Emission) | `EmissionRegistry` | `IEmissionRule` | Ordered list (no key) |
| Phase 9 (Layout) | `LayoutDirectiveRegistry` | `ILayoutDirectiveHandler` | Namespace:name string |
| Phase 10 (Linking) | `LinkingRegistry` | `ILinkingRule` | Ordered list (no key) |
| Phase 10 (Linking) | `LinkingDirectiveRegistry` | `ILinkingDirectiveHandler` | Namespace:name string |

| Phase 5 (TokenMap) | `TokenMapContributorRegistry` | `ITokenMapContributor` | AST node class |
| Phase 6 (PostProcess) | `PostProcessHandlerRegistry` | `IPostProcessHandler` | AST node class |
| Phase 11 (Emitter) | `EmissionContributorRegistry` | `IEmissionContributor` | Ordered list (no key) |

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
│   │   ├── ProcDirectiveHandler.java
│   │   ├── PregNode.java              # .PREG is part of proc (aliases PRs, only inside .PROC)
│   │   ├── PregDirectiveHandler.java
│   │   ├── CallInstructionHandler.java  # NEW (extracted from Parser, D13.8)
│   │   ├── ProcedureSymbolCollector.java
│   │   ├── ProcedureAnalysisHandler.java
│   │   ├── PregAnalysisHandler.java
│   │   ├── PregPostProcessHandler.java
│   │   ├── ProcedureNodeConverter.java
│   │   ├── ProcedureMarshallingRule.java
│   │   ├── CallerMarshallingRule.java
│   │   ├── CallSiteBindingRule.java    # NEW (extracted from Linker)
│   │   ├── ProcedureTokenMapContributor.java  # NEW (extracted from TokenMapGenerator)
│   │   └── ProcFeature.java           # Registration
│   ├── reg/
│   │   ├── RegNode.java
│   │   ├── RegDirectiveHandler.java
│   │   ├── RegAnalysisHandler.java
│   │   ├── RegisterAliasEmissionContributor.java  # resolves reg aliases in emission phase
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
│   │   ├── MacroExpansionHandler.java
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
    void preprocessor(String name, IPreProcessorHandler handler);
    // Phase 3: Parsing
    void parser(String directive, IParserDirectiveHandler handler);
    void instructionParser(String opcode, IInstructionParsingHandler handler);
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
    void linkingDirectiveHandler(String namespace, String name, ILinkingDirectiveHandler handler);
    // Phase 11: Emission (metadata extraction from IR directives)
    void emissionContributor(IEmissionContributor contributor);
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
        ctx.instructionParser("CALL", new CallInstructionHandler());
        ctx.symbolCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        ctx.analysisHandler(ProcedureNode.class, new ProcedureAnalysisHandler());
        ctx.analysisHandler(PregNode.class, new PregAnalysisHandler());
        ctx.tokenMapContributor(ProcedureNode.class, new ProcedureTokenMapContributor());
        ctx.postProcessHandler(PregNode.class, new PregPostProcessHandler());
        ctx.irConverter(ProcedureNode.class, new ProcedureNodeConverter());
        // Emission rules transform IR only (List<IrItem> → List<IrItem>), no side-channel state.
        // Binding info flows through the IR data stream: CALL instruction's refOperands/valOperands
        // carry parameter bindings. Phase 10 linking rules read binding info directly from the IR.
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
| Macro expansion hardcoded in PreProcessor | **RESOLVED** in C9: Extracted to `MacroExpansionHandler`, dynamically registered by `MacroDirectiveHandler` via unified `PreProcessorHandlerRegistry` |
| Parser handlers downcast ParsingContext | `ParsingContext` extended with `expression()`, `declaration()`, `state()`. `ParserState` is a generic type-safe container — features store their own state classes, phase code has no feature imports. Only `registerProcedure()` cast remains (resolved in D13). |
| Compiler.java contains business logic | Procedure metadata extraction moves to `ProcFeature` components |
| IrGenContext instanceof chains | `ISourceLocatable.sourceInfo()` — IrGenContext uses SourceInfo via capability interface |
| TokenMapGenerator hardcodes features | Registry-based `ITokenMapContributor` dispatch. **RESOLVED** in C3: `ProcedureTokenMapContributor` + `InstructionTokenMapContributor` extracted, `Scope.name()` eliminates all `ProcedureNode` references from `TokenMapGenerator`. |
| LabelRefLinkingRule imports SymbolTable | Pre-resolved qualified names map, or SymbolTable in `compiler/model/`. **C4:** LabelRefLinkingRule now also receives `fileToModule` for module-qualified resolution. |
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
| C4 | Module-namespacing: all identifier namespaces (procedures, labels, constants, register-aliases) are module-qualified throughout the backend. Format: `MODULENAME.LOCALNAME`. IrGenContext gains `qualifyName()` + module-aware `registerConstant()`/`resolveConstant()`. LabelRefLinkingRule qualifies local and cross-module references. `procNameToParamNames` and register-alias keys in Compiler.java are qualified. TokenInfo gains `qualifiedName` field for frontend lookups. Breaking change: label hashes are now based on qualified names. **DONE.** |
| C5 | Source Root Infrastructure — see C5 details below. |
| C6 | EXPORT Prefix Syntax — see C6 details below. |
| C7 | Placement-Aware Module Naming (Import Alias Chains). Includes Emitter Registry: create `IEmissionContributor` + `EmissionContributorRegistry` in `backend/emit/`, extract `ProcedureEmissionContributor` from Compiler.java. See C7 details below. |
| C8 | Create `PostProcessHandlerRegistry` in `frontend/postprocess/`. Refactor `AstPostProcessor.collectPass()` to dispatch `IPostProcessHandler.collect()` per AST node class via registry (same pattern as `AnalysisHandlerRegistry`). Extract three handlers: `RegPostProcessHandler` (register alias collection), `DefinePostProcessHandler` (constant collection), `PregPostProcessHandler` (procedure register alias collection, higher priority than Reg). `IdentifierNode` replacement in `replacePass()` remains in `AstPostProcessor` as orchestrator infrastructure (not feature-specific — it applies all collected replacements generically). Handler execution order: handlers are called in registration order; `PregPostProcessHandler` must be registered before `RegPostProcessHandler` so PR aliases shadow DR/LR aliases on name conflict. |
| C9 | Rename `IPreProcessorDirectiveHandler` → `IPreProcessorHandler` and `PreProcessorDirectiveRegistry` → `PreProcessorHandlerRegistry` (the registry now handles all preprocessing operations, not just directives). Extract `MacroExpansionHandler` from `PreProcessor.expandMacro()`, implementing `IPreProcessorHandler`. `MacroDirectiveHandler` dynamically registers a `MacroExpansionHandler` per macro name via `PreProcessor.registerHandler()` when parsing `.MACRO` definitions. Unify `PreProcessor.expand()` dispatch: both DIRECTIVE and IDENTIFIER tokens go through the single registry — no collision risk (directives start with `.`, macro names are plain identifiers). Remove `macroTable`/`registerMacro()`/`getMacro()` from `PreProcessorContext` — the registry is the single source of truth. Note: `PreProcessorHandlerRegistry` is the only compiler registry that is mutated at processing time (macro definitions are user-defined). In Phase E, `MacroFeature.register()` registers only `.MACRO → MacroDirectiveHandler` (static). Dynamic expansion handlers are created at compile-time by the directive handler itself. |
| C10 | Extract `CallSiteBindingRule` from `Linker.link()` into `backend/link/features/`, implementing `ILinkingRule`. The rule detects CALL instructions, collects register names from `refOperands`/`valOperands`, resolves them to numeric IDs via `IInstructionSet`, and stores the bindings in `LinkingContext.callSiteBindings()`. `LinkingRegistry.initializeWithDefaults()` gains an `IInstructionSet` parameter. The Linker loses its only feature-specific logic — it becomes a pure rule dispatcher. |
| C11 | Create `IScopedParserState` interface in `parser/` with `pushScope()`/`popScope()`. `ParserState` gains a `List<IScopedParserState>` and broadcast `pushScope()`/`popScope()` methods. Scoped state objects are auto-registered when created via `getOrCreate()` (if they implement `IScopedParserState`). `RegisterAliasState` implements the interface (methods already existed). `ProcDirectiveHandler` calls `context.state().pushScope()` instead of directly managing `RegisterAliasState` — removes the cross-feature import. Pure parser infrastructure, no feature imports. |

**C5 details (Source Root Infrastructure):**

**Problem.** C4 derives module names from filenames only (`ModuleId.deriveModuleName()` extracts
the last path segment). Two files in different directories with the same name (e.g.,
`lib/math/bitwise.evo` and `lib/logic/bitwise.evo`) produce the identical module name `BITWISE`,
causing label hash collisions and silent overwrites.

**Solution.** Introduce Source Roots — an ordered list of base directories from which the
compiler resolves module files. Each source root has an optional **namespace prefix**. The
prefix is prepended to all module names derived from files under that root.

**Rules:**
1. Exactly **one** source root may be unprefixed — this is the **default source root**.
2. All other source roots **must** have a prefix.
3. The compiler validates this at startup.
4. In `.IMPORT` and `.SOURCE` directives, paths are resolved relative to a source root:
   - No qualifier → resolved from the **default** source root.
   - Explicit qualifier → resolved from the **named** source root: `"rootname:path/to/file.evo"`.
5. There is no search across multiple roots. If a file is not found under the specified root,
   it is a compiler error.

**Example configuration:**

```hocon
# reference.conf (defaults):
pipeline.services.simulation-engine.options {
  compiler {
    source-roots = [
      { path = "." }   # Default: main file directory, no prefix
    ]
  }
}
```

```hocon
# evochora.conf (user override for multi-organism simulation):
pipeline.services.simulation-engine.options {
  compiler {
    source-roots = [
      { path = "shared" }                               # Default (no prefix) — shared libraries
      { path = "organisms/predator", prefix = "PRED" }  # Predator code
      { path = "organisms/prey",     prefix = "PREY" }  # Prey code
    ]
  }

  organisms = [
    {
      # program path is relative to a source root, prefixed with the root's namespace.
      # "PRED:" selects the source root with prefix "PRED" (→ organisms/predator/).
      program = "PRED:main.evo"
      initialEnergy = 100000
      placement { positions = [100, 100] }
    }
    {
      program = "PREY:main.evo"
      initialEnergy = 50000
      placement { positions = [500, 500] }
    }
  ]
}
```

**Example module names with this configuration:**

| File (absolute) | Source Root Match | Relative Path | Prefix | Module Name |
|---|---|---|---|---|
| `organisms/predator/main.evo` | `organisms/predator` | `main.evo` | `PRED` | `PRED.MAIN` |
| `organisms/predator/lib/move.evo` | `organisms/predator` | `lib/move.evo` | `PRED` | `PRED.LIB.MOVE` |
| `organisms/prey/main.evo` | `organisms/prey` | `main.evo` | `PREY` | `PREY.MAIN` |
| `shared/math.evo` | `shared` | `math.evo` | — | `MATH` |
| `shared/collections/list.evo` | `shared` | `collections/list.evo` | — | `COLLECTIONS.LIST` |

**Import syntax examples:**

```asm
# In organisms/predator/main.evo:
.IMPORT "math.evo" AS MATH                 # Default root (shared/) → shared/math.evo
.IMPORT "PRED:lib/move.evo" AS MOV         # Explicit root "PRED" → organisms/predator/lib/move.evo
.SOURCE "constants.evo"                    # Default root → shared/constants.evo
.SOURCE "PRED:local_macros.evo"            # PRED root → organisms/predator/local_macros.evo
```

The syntax `"PREFIX:path"` selects a source root by its namespace prefix. An unqualified
path always resolves from the default (unprefixed) root. The default root has no name — it
cannot be referenced explicitly because it has no prefix.

**Implementation:**

| Component | Change |
|---|---|
| `SourceRoot.java` (NEW) | Record: `(String path, String prefix)`. In `compiler/api/`. |
| `CompilerOptions.java` (NEW) | Holds `List<SourceRoot>`, created from config or CLI args. In `compiler/api/`. |
| `ICompiler.java` | New overload: `compile(sourceLines, programName, envProps, options)`. |
| `Compiler.java` | Accepts `CompilerOptions`, passes source roots to `DependencyScanner`. Falls back to `List.of(new SourceRoot(".", null))` when no options provided. |
| `DependencyScanner.java` | `resolvePath()` uses source roots instead of parent-relative resolution. Parses `"rootname:path"` syntax. Validates file exists under the resolved root. |
| `SimulationEngine.java` | Reads `compiler.source-roots` from config, constructs `CompilerOptions`, passes to `compiler.compile()`. |
| `CompileCommand.java` | New CLI option `--source-root=path[:prefix]` (repeatable). Constructs `CompilerOptions`. |
| `ModuleId.deriveModuleName()` | New signature: `deriveModuleName(String absolutePath, List<SourceRoot> sourceRoots)`. Finds matching root, computes relative path, splits on `/`, strips extension from last segment, uppercases, joins with `.`. Prepends prefix if present. If unprefixed root, the module name part is just the relative path segments — **no filename-derived fallback** for the root alias chain. |
| `reference.conf` | Add `compiler { source-roots = [{ path = "." }] }` section under `pipeline.services.simulation-engine.options`. |

**CLI example:**

```bash
# Simple (default root = main file directory, no prefix):
evochora compile --file=main.evo

# Multi-organism with source roots:
evochora compile --file=PRED:main.evo \
    --source-root=shared \
    --source-root=organisms/predator:PRED

# The --file path uses the same PREFIX:path syntax as .IMPORT directives.
# Here, "PRED:main.evo" resolves to organisms/predator/main.evo.
```

---

**C6 details (EXPORT Prefix Syntax):**

**Problem.** The `EXPORT` keyword is currently placed **after** the construct it modifies:

```asm
MY_LABEL: EXPORT              # EXPORT after the colon
.PROC MY_PROC EXPORT REF A    # EXPORT after the procedure name
```

C7 introduces `EXPORT` on `.IMPORT` directives. Placing it as a **prefix** modifier
(`EXPORT .IMPORT "x" AS X`) is more natural and consistent with modern languages
(`pub fn`, `export function`, `public class`), but creates an inconsistency with the
existing label and procedure syntax.

**Solution.** Change `EXPORT` to a **prefix modifier** for all constructs that support it.
This is a breaking syntax change (acceptable on `compiler-2.0`).

New syntax:

```asm
EXPORT MY_LABEL:                    # was: MY_LABEL: EXPORT
EXPORT .PROC MY_PROC REF A         # was: .PROC MY_PROC EXPORT REF A
EXPORT .IMPORT "math.evo" AS MATH  # new in C7
```

**Parser architecture change.** `EXPORT` is parsed once at statement level in
`Parser.statement()`, not inside each individual directive handler:

```java
// Parser.statement() — single location for EXPORT handling:
private AstNode statement() {
    boolean exported = false;
    if (check(TokenType.IDENTIFIER) && "EXPORT".equalsIgnoreCase(peek().text())) {
        exported = true;
        advance(); // consume EXPORT
    }
    if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
        // Label: EXPORT MY_LABEL: ...
        return parseLabel(exported);
    }
    if (check(TokenType.DIRECTIVE)) {
        // Directive: EXPORT .PROC / EXPORT .IMPORT
        return directive(exported);
    }
    if (exported) {
        diagnostics.reportError("EXPORT can only precede a label, .PROC, or .IMPORT", ...);
    }
    return instructionStatement();
}
```

The `exported` flag is passed to directive handlers via `ParsingContext`:

```java
// ParsingContext interface — new method:
boolean isExported();
```

Handlers that support EXPORT (`ProcDirectiveHandler`, `ImportDirectiveHandler`) read the
flag from context. Handlers that do not support EXPORT ignore it — the Parser validates
that EXPORT only precedes valid constructs.

**Affected files:**

| File | Change |
|---|---|
| `Parser.java` | Move EXPORT parsing from inside label/directive logic to `statement()` level. Parse `EXPORT` as prefix before dispatching. |
| `ProcDirectiveHandler.java` | Remove internal EXPORT parsing. Read `context.isExported()` instead. |
| `ParsingContext.java` | Add `boolean isExported()` method. |
| `LabelNode.java` | No structural change (already has `boolean exported` field). |
| `ProcedureNode.java` | No structural change (already has `boolean exported` field). |
| `ASSEMBLY_SPEC.md` | Update syntax examples: labels, procedures. |

**Assembly files to update** (in `assembly/`, excluding `build/`):

| File | Occurrences | Types |
|---|---|---|
| `primordial/lib/energy.evo` | 2 | 1 label (`HARVEST_STATE: EXPORT`), 1 proc |
| `primordial/lib/reproduce.evo` | 2 | 1 label (`CONTINUE_STATE: EXPORT`), 1 proc |
| `examples/complex.evo` | 19 | all procs |
| `examples/simple.evo` | 1 | 1 proc |
| `examples/modules/movement.evo` | 4 | all procs |
| `examples/modules/math.evo` | 3 | 1 label, 2 procs |
| `test/lib/lib1.evo` | 1 | 1 proc |
| `test/lib/lib2.evo` | 1 | 1 proc |

**Test files to update** (EXPORT in source strings):

| File | Occurrences |
|---|---|
| `ParserTest.java` | 2 (`L1: EXPORT` → `EXPORT L1:`) |
| `ProcedureDirectiveTest.java` | 1 |
| `UsingClauseIntegrationTest.java` | 6 |
| `ModuleSourceDefineIntegrationTest.java` | 6 |
| `CompilerEndToEndTest.java` | 2 |
| `RuntimeIntegrationTest.java` | 3 |
| `RefWithParameterBugTest.java` | 2 |
| `WithParameterTokenMapTest.java` | 1 |
| `simple.evo` (test resource) | 1 |

---

**C7 details (Placement-Aware Module Naming — Import Alias Chains):**

**Problem.** In Evochora's evolution simulation, the same module file may be physically placed
multiple times in the environment. Each `.IMPORT` directive creates an independent copy of the
module's code that can mutate independently at runtime. These copies **must** have different
module names (and therefore different label hashes) so that fuzzy jumps do not accidentally
cross between placements.

C4's `qualifyName()` infrastructure derives module names from file paths via
`deriveModuleName(fileName)`. This creates a fundamental ambiguity: two `.IMPORT` directives
pointing to the same file produce the same module name — their labels collide in
`labelToAddress` and have identical runtime hashes.

The root cause is that the entire downstream pipeline uses `sourceInfo.fileName()` as the
module identifier. Since two placements of the same file produce tokens with the same
`fileName`, every phase that qualifies names produces identical results for both placements:

- `fileToModule.get(fileName)` in Compiler.java maps both placements to the same `ModuleId`
- `ModuleContextTracker` switches context based on `PushCtxNode.targetPath()` = file path,
  so both placements trigger the same module context
- `SymbolTable.setCurrentModule()` receives the same `ModuleId` for both, causing symbol
  collisions ("Symbol 'X' already defined") when the second placement is processed
- `LabelRefLinkingRule` extracts `instruction.source().fileName()` and cannot distinguish
  which placement the instruction belongs to
- `IrGenContext.qualifyName()` produces identical qualified names for both placements
- `procNameToParamNames` and register-alias map keys silently overwrite

**Solution.** Replace `fileName` as the module identifier with the **import alias chain** —
the path of import aliases from the compilation root to the module. Each `.IMPORT` extends
the chain; each `.REQUIRE` + `USING` references an existing chain position (shared placement).
The alias chain is the single source of truth for placement identity throughout all phases.

**Visibility model — EXPORT on .IMPORT.**

By default, a module's imports are **private** — they are not visible to the module's parent
(the importer). This prevents uncontrolled coupling to a module's internal dependencies. To
make an import visible to the parent, the `EXPORT` keyword is placed before `.IMPORT`:

```asm
# move.evo:
EXPORT .IMPORT "utils.evo" AS UTILS    # UTILS visible to move.evo's importer
.IMPORT "internal.evo" AS INTERNAL     # INTERNAL private to move.evo

EXPORT .PROC WALK                      # WALK visible to move.evo's importer
    ; ...
.ENDP
```

When a parent references a nested module's labels, the compiler performs **recursive scope
resolution** with a visibility check at each level:

```asm
# main.evo (root = PRED):
.IMPORT "PRED:lib/move.evo" AS MOV

CALL MOV.WALK                # OK — WALK is exported by move.evo
CALL MOV.UTILS.DISTANCE      # OK — MOV exports UTILS, UTILS exports DISTANCE
CALL MOV.INTERNAL.HELPER     # ERROR — MOV does not export INTERNAL
```

Resolution of `MOV.UTILS.DISTANCE` from PRED's context:

1. In PRED's scope: `MOV` → import alias, directly imported → accessible. ✓
2. In PRED.MOV's scope: `UTILS` → import alias. Is the `.IMPORT` of UTILS marked `EXPORT`? ✓
3. In PRED.MOV.UTILS's scope: `DISTANCE` → procedure. Is it exported (`EXPORT` keyword)? ✓
4. Qualified name: `PRED.MOV.UTILS.DISTANCE`

If any check fails, the compiler reports an error (e.g., "INTERNAL is not exported by MOV").
Multi-level references (`A.B.C.LABEL`) are fully supported with no depth limit — the compiler
resolves recursively through the scope chain, checking visibility at each level.

**Core rules:**

1. **Root module name** = source root prefix (from C5) of the main file. If no prefix
   (default root), the root alias chain is **empty** — identifiers in the root module
   are unqualified (just their local names, no module prefix). This is explicit and
   fail-fast: no filename-derived fallback naming.
2. **`.IMPORT "x" AS X`** = new physical placement. Module name = `<parent-module-name>.X`.
   The code is inlined, labels get unique hashes. The import is **private** by default —
   not visible to the parent's parent.
3. **`EXPORT .IMPORT "x" AS X`** = same as rule 2, but the import is **visible** to the
   parent. Labels from X can be referenced as `X.LABEL` from the parent's context (subject
   to X's own export rules for individual labels/procedures).
4. **`.REQUIRE "x" AS X`** + `USING Y AS X` = shared placement. Module name = whatever `Y`'s
   module name is. No new code is placed — the module references Y's existing placement.
5. **`.SOURCE "x"`** = text inclusion, no module identity. Tokens inherit the parent's module
   context.
6. **Cross-module references** use the import alias as prefix: `ALIAS.LABEL` for one level,
   `ALIAS.NESTED_ALIAS.LABEL` for deeper nesting. The compiler resolves recursively through
   the scope chain and checks EXPORT visibility at each level.
7. **Unqualified references** are always local to the current module.
8. **Cross-compilation identifier collisions** are a deployment concern, not a compile-time
   concern. Each compilation is independent — the compiler cannot detect that two separate
   compilations (each with an empty root alias chain) define identifiers with the same name.
   Source root prefixes (C5) are the mechanism for preventing collisions in multi-organism
   deployments.

**Example — two organisms in separate compilations:**

```
Source Roots: [shared (default), organisms/predator (PRED), organisms/prey (PREY)]
```

Compilation 1: `PRED:main.evo` (organisms/predator/main.evo)
```asm
.IMPORT "math.evo" AS MATH                # Default root → new placement → PRED.MATH
.IMPORT "PRED:lib/move.evo" AS MOV        # PRED root → new placement → PRED.MOV
```

Compilation 2: `PREY:main.evo` (organisms/prey/main.evo)
```asm
.IMPORT "math.evo" AS MATH                # Default root → new placement → PREY.MATH
.IMPORT "PREY:lib/hide.evo" AS HIDE       # PREY root → new placement → PREY.HIDE
```

| Compilation | File | Alias Chain | Module Name | Label `ADD` Hash |
|---|---|---|---|---|
| 1 (Predator) | math.evo | PRED → MATH | `PRED.MATH` | `"PRED.MATH.ADD".hashCode()` |
| 1 (Predator) | move.evo | PRED → MOV | `PRED.MOV` | `"PRED.MOV.WALK".hashCode()` |
| 2 (Prey) | math.evo | PREY → MATH | `PREY.MATH` | `"PREY.MATH.ADD".hashCode()` |
| 2 (Prey) | hide.evo | PREY → HIDE | `PREY.HIDE` | `"PREY.HIDE.BURROW".hashCode()` |

Same file (`math.evo`), different placements, different hashes. No cross-jumping at runtime.

**Example — shared placement within a single compilation:**

```asm
# world.evo (compiled from default root, no prefix → root alias chain is empty):
.IMPORT "math.evo" AS MATH                             # Default root → one placement → MATH
.IMPORT "PRED:main.evo" AS A USING MATH AS MATH        # PRED root → A uses existing MATH placement
.IMPORT "PREY:main.evo" AS B USING MATH AS MATH        # PREY root → B uses existing MATH placement
```

```asm
# organisms/predator/main.evo:
.REQUIRE "math.evo" AS MATH          # No placement — satisfied by USING from parent
CALL MATH.ADD                        # Resolves to MATH.ADD (the shared placement)
```

Here, `MATH.ADD` exists once (root alias chain is empty, so just `MATH.ADD` — no prefix).
Both A and B reference the same placement.

**Example — nested imports:**

```asm
# organisms/predator/main.evo (root = PRED):
.IMPORT "PRED:lib/move.evo" AS MOV           # PRED root → PRED.MOV
```

```asm
# organisms/predator/lib/move.evo:
.IMPORT "utils.evo" AS UTILS                 # Default root → PRED.MOV.UTILS (parent is PRED.MOV)
```

The alias chain grows with each nesting level. Note that `move.evo` imports from the default
root (shared/) — it does not need to know which prefixed root it lives under.

**Example — export visibility:**

```asm
# shared/math.evo:
EXPORT .PROC ADD
    ; ...
.ENDP

.PROC INTERNAL_HELPER       # No EXPORT — private to math.evo
    ; ...
.ENDP
```

```asm
# organisms/predator/lib/move.evo:
EXPORT .IMPORT "math.evo" AS MATH      # MATH visible to move.evo's importer
.IMPORT "internal.evo" AS HELPERS      # HELPERS private to move.evo

EXPORT .PROC WALK
    CALL MATH.ADD
    CALL HELPERS.CLEANUP               # OK — HELPERS is accessible within move.evo
.ENDP
```

```asm
# organisms/predator/main.evo (root = PRED):
.IMPORT "PRED:lib/move.evo" AS MOV

CALL MOV.WALK                    # OK — WALK is exported by move.evo
CALL MOV.MATH.ADD                # OK — MOV exports MATH, MATH exports ADD
CALL MOV.MATH.INTERNAL_HELPER   # ERROR — INTERNAL_HELPER not exported by math.evo
CALL MOV.HELPERS.CLEANUP         # ERROR — HELPERS not exported by move.evo
```

Resolution of `MOV.MATH.ADD` from PRED's context:

1. PRED's scope: `MOV` → import alias (directly imported) → accessible ✓
2. PRED.MOV's scope: `MATH` → import alias. `.IMPORT` marked `EXPORT`? ✓
3. PRED.MOV.MATH's scope: `ADD` → procedure. Marked `EXPORT`? ✓
4. Qualified name: `PRED.MOV.MATH.ADD`

**The placement identity problem.**

The fundamental challenge is that `fileName` (from `sourceInfo()` or `Token.fileName()`) is
not a unique placement identifier. The PreProcessor inlines tokens from imported files, but
those tokens retain their original `fileName`. Two copies of `math.evo` inlined by different
parents produce tokens, AST nodes, and IR items with identical `fileName` values. Every
downstream phase that uses `fileName` for module identification (via `fileToModule.get()` or
`deriveModuleName()`) produces the same result for both placements.

The alias chain replaces `fileName` as the placement identifier. It flows through the
compilation pipeline via `PUSH_CTX`/`POP_CTX` context markers:

```
Phase 2 (PreProcessor):   PUSH_CTX carries alias chain alongside file path
Phase 3 (Parser):         PushCtxNode stores alias chain
Phase 4 (Semantics):      ModuleContextTracker exposes currentAliasChain()
                           SymbolTable creates separate scopes per alias chain
Phase 5 (TokenMap):        Uses currentAliasChain() for qualification
Phase 6 (PostProcess):     Uses currentAliasChain() for register alias / constant resolution
Phase 7 (IrGen):           IrGenContext tracks alias chain via PushCtx/PopCtx AST nodes
                           qualifyName() uses current alias chain as module prefix
Phase 9 (Layout):          No change — IrLabelDef names already qualified in Phase 7
Phase 10 (Linking):        Linker tracks alias chain via PushCtx/PopCtx IrDirectives
                           LabelRefLinkingRule qualifies using current alias chain
Phase 11 (Emit):           EmissionContributorRegistry dispatches IrDirectives to contributors.
                           ProcedureEmissionContributor extracts procNameToParamNames from
                           proc_enter directives. Compiler no longer builds this map.
```

**Implementation by phase:**

*Phase 0 — Placement Tree.*

The `DependencyScanner` currently builds a `DependencyGraph` that deduplicates modules by
`ModuleId` (= file path). A file that is imported by two different parents appears once.
This is correct for **scanning** (no need to read the file twice) but not for **naming**
(each import creates a separate placement with a unique alias chain).

Introduce `PlacementTree` — a tree of placement descriptors built **after** the
`DependencyGraph`. The two structures have separate responsibilities:

- `DependencyGraph` = "which files exist" (file-level deduplication, topological order)
- `PlacementTree` = "how placements are named" (alias chain hierarchy, USING bindings)

Each node represents one placement:

```java
record PlacementDescriptor(
    String aliasChain,               // e.g., "PRED.MOV.UTILS" — the module name
    String sourcePath,               // e.g., "shared/utils.evo" — the physical file
    ModuleId moduleId,               // From DependencyScanner (for token lookup)
    boolean exported,                // Is this .IMPORT marked EXPORT?
    List<PlacementDescriptor> children  // Nested imports
)
```

The root node's `aliasChain` is the source root prefix of the main file. If the main
file is under the default (unprefixed) source root, the root alias chain is **empty**
(`""`). Each `.IMPORT "x" AS X` child's alias chain is
`parent.aliasChain + "." + X`. `.REQUIRE` nodes reference an existing placement (via
USING resolution) — they do not create new tree nodes.

File scanning remains deduplicated (each file loaded and lexed once). The placement tree
is an additional structure that tracks the import hierarchy.

**Construction is two-pass** to avoid ordering constraints for the programmer:

*Pass 1 — Build all placement nodes.* Walk the import declarations top-down. For each
`.IMPORT`, create a `PlacementDescriptor` child node. For each `.REQUIRE`, record the
USING binding as unresolved. After this pass, all placement nodes exist at every level
of the hierarchy.

*Pass 2 — Resolve USING bindings.* For each unresolved USING binding, resolve the target
reference in the PlacementTree. USING targets may be dotted references (e.g.,
`USING PRED.MATH AS MATH`) — resolution follows the same multi-level algorithm as
`SymbolTable.resolve()`, walking the tree and checking EXPORT visibility at each level:

1. Parse `PRED.MATH` into segments `[PRED, MATH]`
2. Find `PRED` as a sibling import on the same level → found
3. Find `MATH` as a child of `PRED` → EXPORT marked? → yes → alias chain `PRED.MATH`
4. Bind the `.REQUIRE`'s alias to `PRED.MATH`

If a USING target references a non-exported placement → error: "MATH is not exported by
PRED". If a `.REQUIRE` has no matching USING binding from any parent → error: "Unsatisfied
dependency: MATH is required by child.evo but not provided via USING". This check runs
during Pass 2 — targeted errors instead of confusing "label not resolved" failures in
Phase 10.

**Circular USING detection.** After Pass 2, verify that USING bindings do not form cycles.
A cycle would mean A requires B requires A (via USING chains). Detection: walk the resolved
USING graph and check for back edges. Report error: "Circular USING dependency: A → B → A".

Because all nodes exist before USING resolution begins (Pass 1 completes first), there is
**no ordering constraint** — imports can appear in any order within a file, and USING can
reference any import regardless of declaration position.

| Component | Change |
|---|---|
| `PlacementDescriptor.java` (NEW) | Record as above. In `compiler/frontend/module/`. |
| `PlacementTree.java` (NEW) | Holds root `PlacementDescriptor`, provides `findByAliasChain(String)` lookup and `resolveReference(String dottedName, PlacementDescriptor fromScope)` for USING resolution. |
| `DependencyScanner.java` | After building `DependencyGraph`, build `PlacementTree` in two passes: (1) create all placement nodes from import declarations, (2) resolve USING bindings and detect circular USING. |

PlacementTree is Phase-0-internal. It is used during dependency scanning for USING
resolution and conflict detection, but it is **not** passed to downstream phases.
Alias chains reach downstream phases through the data stream instead — see Phase 2 below.

*Phase 2 — PUSH_CTX carries alias chain.*

Currently, `ImportSourceHandler` creates a `PUSH_CTX` token with `value = resolvedPath`
(the absolute file path). This is the point where placement identity is established and
must be extended.

The alias chain is **computed incrementally** during preprocessing — no PlacementTree
lookup is needed. The PreProcessor maintains an alias chain stack (analogous to the
existing import chain stack used for circular detection):

1. The stack is initialized with the root alias chain (the main file's source root
   prefix, or empty `""` if unprefixed).
2. When `ImportSourceHandler` processes `.IMPORT "math.evo" AS MATH`:
   - It reads the current parent alias chain from the stack top (e.g., `"PRED"`, or `""`).
   - It computes the new alias chain: if parent is empty, just the alias (`"MATH"`);
     otherwise parent + `"."` + alias (`"PRED.MATH"`).
   - It injects PUSH_CTX with the new alias chain into the token stream.
3. `PopCtxDirectiveHandler` pops the stack when processing POP_CTX.
4. When `SourceDirectiveHandler` processes `.SOURCE "macros.evo"`:
   - It injects PUSH_CTX with `aliasChain = null` (no module context change).
   - The stack is not modified — `.SOURCE` content inherits the parent's context.

This works for arbitrarily nested imports because the PreProcessor processes injected
tokens before continuing, so the stack reflects the current nesting depth at all times.

| Component | Change |
|---|---|
| `PreProcessorContext.java` | Add alias chain stack: `pushAliasChain(String)`, `popAliasChain()`, `currentAliasChain()`. Initialized with the root alias chain (passed by the orchestrator as a plain `String`). |
| `PlacementContext.java` (NEW) | Record `(String sourcePath, String aliasChain)` in `compiler/frontend/module/`. Replaces the plain `String` that PUSH_CTX tokens carried as their value. `sourcePath` = physical file path (for error messages). `aliasChain` = placement identity (for name qualification), or `null` for `.SOURCE` (text inclusion, no module identity). |
| `ImportSourceHandler.java` | PUSH_CTX token value changes from `resolvedPath` (String) to `new PlacementContext(resolvedPath, aliasChain)`. The alias chain is computed from `preProcessorContext.currentAliasChain() + "." + importAlias`. After injecting tokens, pushes the new alias chain onto the stack. |
| `SourceDirectiveHandler.java` | PUSH_CTX token value changes from `null` to `new PlacementContext(sourcePath, null)`. The `null` alias chain signals text inclusion — no module context change. |
| `PopCtxDirectiveHandler.java` | Pops the alias chain stack when processing `.POP_CTX` with value `"IMPORT"`. |
| `PushCtxNode.java` | Add `String aliasChain` field alongside existing `String targetPath`: `PushCtxNode(String targetPath, String aliasChain, SourceInfo sourceInfo)`. |
| `PushCtxDirectiveHandler.java` | Accepts only `PlacementContext` as token value (no String fallback). Extracts `sourcePath` and `aliasChain` from the record: `new PushCtxNode(pc.sourcePath(), pc.aliasChain(), sourceInfo)`. All three producers (`ImportSourceHandler`, `SourceDirectiveHandler`) are migrated in the same step, so no backward compatibility branch is needed. |

*Phase 3 — ImportNode gains `exported` field.*

| Component | Change |
|---|---|
| `ImportNode.java` | Add `boolean exported` field. |
| `ImportDirectiveHandler.java` | Read `context.isExported()` (from C6's EXPORT prefix parsing). |

*Phase 4 — SymbolTable scoping uses alias chain.*

The `ModuleContextTracker` currently switches context via
`fileToModule.get(pushCtx.targetPath())`, which maps file paths to `ModuleId`. Since two
placements of the same file have the same path, they get the same `ModuleId` and the same
scope — causing symbol collisions.

| Component | Change |
|---|---|
| `ModuleContextTracker.java` | Remove `fileToModule` constructor parameter. Use `pushCtx.aliasChain()` directly for context switching (no lookup needed). Expose `currentAliasChain()` method for downstream phases. Constructor signature: `ModuleContextTracker(SymbolTable symbolTable)`. Each phase creates its own instance — instances are NOT shared across phases (each traversal drives the stack independently from start to finish). |
| `SymbolTable.java` | Module scopes keyed by alias chain (`String`) instead of `ModuleId`. Method signature changes: `registerModule(String aliasChain, String sourcePath)`, `setCurrentModule(String aliasChain)`, `getCurrentAliasChain()` (returns `String`, replaces `getCurrentModuleId()`), `getModuleScope(String aliasChain)`, `resolve(String name, String currentAliasChain)` (replaces `requestingFile` parameter). Internal map: `Map<String, ModuleScope>` replaces `Map<ModuleId, ModuleScope>`. Each placement gets its own scope — two placements of `math.evo` get scopes `PRED.MATH` and `PREY.MATH`, no collisions. |
| `SymbolTable` export metadata | Key pattern changes from `fileName + "\|" + name` to `aliasChain + "\|" + name`. Affected methods: `registerProcedureMeta()`, `registerLabelMeta()`, `isProcExported()`, `isLabelExported()`. The `Token` parameter for these methods changes to `(String aliasChain, String name, boolean exported)` — decoupled from Token, uses alias chain from current context. |
| `ResolvedSymbol.java` (NEW) | Record `(Symbol symbol, String qualifiedName)` in `frontend/semantics/`. Returned by `SymbolTable.resolve()`. The `qualifiedName` is the fully qualified name including the alias chain of the defining scope (e.g., `"PRED.MATH.ADD"`). This keeps `Symbol` as a pure definition record — the alias chain is a property of the scope where the symbol lives, not of the symbol itself. Callers use `resolved.qualifiedName()` directly instead of calling a separate `qualifyName()` for references. |
| `SymbolTable.resolve()` | Returns `Optional<ResolvedSymbol>` instead of `Optional<Symbol>`. Algorithm for `resolve(String name, String currentAliasChain)`: **(1) Local reference** (no dot in `name`): look up `name` in `currentAliasChain`'s scope. If found, return `ResolvedSymbol(symbol, currentAliasChain + "." + name)`. If not found, report error: "`name` is not defined in `currentAliasChain`". **(2) Cross-module reference** (dots in `name`, e.g., `MOV.MATH.ADD`): split into segments `[MOV, MATH, ADD]`. Set `scope = currentAliasChain`. For each intermediate segment (`MOV`, `MATH`): (a) look up segment as import alias in `scope`; (b) not found → error "`segment` is not defined in `scope`"; (c) not an import alias → error "`segment` is not an import alias in `scope`"; (d) import not marked EXPORT and `scope ≠ currentAliasChain` → error "`segment` is not exported by `scope`"; (e) advance `scope` to the import's alias chain. For the final segment (`ADD`): (a) look up as symbol in `scope`; (b) not found → error; (c) not exported → error "`ADD` is not exported by `scope`"; (d) return `ResolvedSymbol(symbol, scope + "." + ADD)`. Note: the EXPORT check on intermediate segments (step 2d) applies only when the caller is NOT the direct parent — a module may freely access its own private imports. |

Two distinct qualification paths exist — one for definitions, one for references:

| Use Case | Mechanism | Example |
|---|---|---|
| **Qualifying definitions** (label/proc defined in current module) | Phase context stack: `currentAliasChain + "." + localName` | `IrGenContext.qualifyName("ADD")` → `"PRED.MATH.ADD"` |
| **Qualifying references** (cross-module calls like `MATH.ADD`) | `SymbolTable.resolve("MATH.ADD", currentAliasChain)` → `ResolvedSymbol.qualifiedName()` | → `"PRED.MATH.ADD"` |

This separation keeps `Symbol` free of placement knowledge and centralizes qualification logic: the SymbolTable handles references (it owns the scope hierarchy), and the phase context handles definitions (it owns the current alias chain).
| `ModuleScope.java` | `imports()` returns `Map<String, String>` (alias → alias chain) instead of `Map<String, ModuleId>`. `usingBindings()` same change. |
| `ModuleId.java` | No longer used for module scoping. Retained only in `DependencyScanner` / `DependencyGraph` / `ModuleDescriptor` for file-level deduplication (loading each file once). Not passed to downstream phases. |
| `ImportSymbolCollector.java` | When defining import aliases in the SymbolTable, store the `exported` flag from `ImportNode.exported()`. This enables `resolve()` to check visibility at each level. |

Affected callers (main source — 12 files, ~30 call sites):

| File | Sites | Change |
|---|---|---|
| `SemanticAnalyzer.java` | 6 | `registerModule()`, `getModuleScope()`, `setCurrentModule()` — all switch from `ModuleId` to alias chain. Remove `fileToModule` parameter. |
| `ModuleContextTracker.java` | 6 | `getCurrentModuleId()` → `getCurrentAliasChain()`, `setCurrentModule(ModuleId)` → `setCurrentModule(String)`. Remove `fileToModule` field. |
| `ImportAnalysisHandler.java` | 2 | `getModuleScope(ModuleId)` → `getModuleScope(aliasChain)`. |
| `RequireAnalysisHandler.java` | 2 | `getCurrentModuleId()` → `getCurrentAliasChain()`, `getModuleScope()` signature. |
| `ProcedureSymbolCollector.java` | 2 | `getCurrentModuleId()` → `getCurrentAliasChain()`, `registerProcedureMeta()` signature. |
| `LabelSymbolCollector.java` | 1 | `registerLabelMeta()` signature. |
| `InstructionAnalysisHandler.java` | 5 | `resolve(name, fileName)` → `resolve(name, aliasChain)` — aliasChain from `ModuleContextTracker.currentAliasChain()`. |
| `AstPostProcessor.java` | 2 | `resolve(name, fileName)` → `resolve(name, aliasChain)`, `getCurrentModuleId()` → `getCurrentAliasChain()`. |
| `TokenMapGenerator.java` | 1 | `resolve(name, fileName)` → `resolve(name, aliasChain)`. Needs its own `ModuleContextTracker` instance to drive during AST walk. |
| `LabelRefLinkingRule.java` | 1 | `resolve(name, instrFile)` → `resolve(name, aliasChain)` — aliasChain from `LinkingContext.currentAliasChain()`. |
| `Compiler.java` | 4 | Remove `fileToModule` map entirely. `ModuleContextTracker` constructor calls lose `fileToModule`. `resolveQualifiedName()` helper deleted. `procNameToParamNames` and register-alias keys use alias chain from respective phase contexts. |
| `LinkingRegistry.java` | 1 | Remove `fileToModule` parameter from `initializeWithDefaults()`. |

Tests (~43 `SymbolTable` constructor calls, ~20 `ModuleId` usages in test setup). Test changes are mechanical — replace `ModuleId` with alias chain strings in setup code.

*Phase 5 — TokenMap uses alias chain.*

| Component | Change |
|---|---|
| `TokenMapGenerator.java` | Qualification uses `ModuleContextTracker.currentAliasChain()` instead of `deriveModuleName(fileName)`. Gains its own `ModuleContextTracker` instance, driven during AST walk in `generateAll()`. Constructor adds `ModuleContextTracker` parameter (replacing `fileToModule`). |

*Phase 6 — PostProcess uses alias chain.*

| Component | Change |
|---|---|
| `AstPostProcessor.java` | Register alias and constant resolution uses `ModuleContextTracker.currentAliasChain()` as the module prefix for lookups, instead of `deriveModuleName(fileName)`. |

*Phase 7 — IrGen tracks placement context.*

The `IrGenerator` processes AST nodes sequentially. PushCtxNode / PopCtxNode nodes in the
AST stream provide placement context. The `IrGenContext` maintains a stack of alias chains.

| Component | Change |
|---|---|
| `IrGenContext.java` | Add alias chain stack. `PushCtxNodeConverter` pushes the alias chain; `PopCtxNodeConverter` pops it. `qualifyName()` uses the top of the stack as the module prefix. Method signature changes from `qualifyName(localName, fileName)` to `qualifyName(localName)` — the placement context is tracked internally. |
| `PushCtxNodeConverter.java` | Reads `node.aliasChain()` and (1) pushes it onto `IrGenContext`'s alias chain stack for Phase 7 use, and (2) embeds it in the emitted IrDirective's args map: `Map.of("aliasChain", aliasChain)` (or empty map if `aliasChain == null` for `.SOURCE`). This makes the IR stream self-describing — the Linker (Phase 10) reads the alias chain from the IrDirective without needing access to the AST. |
| `PopCtxNodeConverter.java` | Pops `IrGenContext`'s alias chain stack. Emits `IrDirective("core", "pop_ctx", ...)` as before. |
| `IrGenerator.java` | No longer needs `fileToModule` parameter. No PlacementTree either — alias chains are read from the AST data stream via `PushCtxNode.aliasChain()`. REQUIRE/USING resolution is handled by the SymbolTable (populated in Phase 4), not by IrGenContext. |
| `ProcedureNodeConverter.java` | Calls `ctx.qualifyName(procName)` instead of `ctx.qualifyName(procName, fileName)`. |
| `LabelNodeConverter.java` | Same simplification. |
| `DefineNodeConverter.java` | Same simplification. |
| `InstructionNodeConverter.java` | Same simplification. |

*Phase 10 — Linker tracks placement context.*

The Linker processes IR items sequentially. PushCtx / PopCtx `IrDirective` items in the IR
stream provide placement context (these are emitted by `PushCtxNodeConverter` /
`PopCtxNodeConverter` in Phase 7).

| Component | Change |
|---|---|
| `Linker.java` | Maintain an alias chain stack while iterating IR items. When encountering a PushCtx IrDirective, read the alias chain from `directive.args().get("aliasChain")` and push it. When encountering PopCtx, pop. Pass the current alias chain to linking rules via `LinkingContext`. This mirrors how Phases 4-6 read alias chains from PushCtxNode and Phase 7 reads them from the AST — each phase reads placement context from its own data stream. |
| `LinkingContext.java` | Add `String currentAliasChain()` accessor. |
| `LabelRefLinkingRule.java` | `qualifyName()` uses `context.currentAliasChain()` instead of `fileToModule.get(instruction.source().fileName())`. For cross-module references (`MATH.ADD`): resolve `MATH` as an alias in the current placement's scope (via SymbolTable), then use the resolved alias chain for qualification. |
| `LinkingRegistry.java` | No longer needs `fileToModule` parameter. |

*Compiler.java — orchestration changes.*

| Component | Change |
|---|---|
| `Compiler.java` | Remove `fileToModule` map — no replacement needed. Alias chains flow through the data stream (PUSH_CTX markers in tokens/AST/IR), not through the orchestrator. The orchestrator passes only the root alias chain (a plain `String`, derived from the main file's source root prefix) to the `PreProcessorContext` at Phase 2 initialization. `resolveQualifiedName()` helper deleted — qualification is encapsulated in each phase's context object, driven by the alias chain stack that each context maintains from the PUSH_CTX/POP_CTX markers in the data it processes. Three extraction sites that currently live in the orchestrator move into their respective phases: |
| `Compiler.java` — `procNameToParamNames` (line 188) | Moves to **Phase 7**. `ProcedureNodeConverter` registers procedure parameters directly in `IrGenContext`, qualifying the procedure name with `ctx.currentAliasChain()`. The orchestrator no longer extracts this from the parser's procedure table. |
| `Compiler.java` — `.REG` register aliases (line 217) | Moves to **Phase 6**. `AstPostProcessor` extracts register aliases from `RegNode` instances during its AST walk, qualifying with `ModuleContextTracker.currentAliasChain()`. The orchestrator no longer calls `parser.getGlobalRegisterAliases()`. |
| `Compiler.java` — `.PREG` register aliases (line 306) | Moves to **Phase 6**. `AstPostProcessor` extracts register aliases from `PregNode` instances during its AST walk, qualifying with `ModuleContextTracker.currentAliasChain()`. The `extractProcedureRegisterAliases()` helper in Compiler.java is deleted. |

*REQUIRE/USING — alias chain resolution.*

When a parent imports a child with `USING MATH AS MATH`, the parent tells the child: "your
`MATH` alias should resolve to my `MATH` placement". In the `PlacementTree`:

- The parent's `.IMPORT "child.evo" AS A USING MATH AS MATH` creates a child node for `A`.
- The child's `.REQUIRE "math.evo" AS MATH` does NOT create a new placement.
- The USING clause binds the child's `MATH` alias to the parent's `MATH` placement's alias
  chain (e.g., `WORLD.MATH`).

In the `SymbolTable`, when the child's code is processed:
- `MATH` is registered as an import alias pointing to the scope `WORLD.MATH` (the shared
  placement), not to a new scope.
- `CALL MATH.ADD` resolves through `WORLD.MATH` scope → finds `ADD` → qualified name
  `WORLD.MATH.ADD`.

The `DependencyScanner` builds this mapping during `PlacementTree` construction (Pass 2 —
USING resolution). USING targets may be dotted references (e.g., `USING PRED.MATH AS MATH`)
resolved via multi-level lookup in the PlacementTree with EXPORT visibility checks.
The resolved USING bindings are stored in the `PlacementDescriptor` and propagated to the
`SymbolTable` during Phase 4 (via `ImportSymbolCollector`).

**Impact on C4 infrastructure.**

The `qualifyName()` pattern established in C4 remains — it is still the central mechanism for
name qualification, encapsulated in context objects and called by converters. What changes:

| C4 pattern | C7 replacement |
|---|---|
| `qualifyName(localName, fileName)` | `qualifyName(localName)` — context tracked internally |
| `fileToModule.get(fileName)` → `ModuleId` | Alias chain stack (from PUSH_CTX/POP_CTX) |
| `deriveModuleName(path)` → module name | Alias chain IS the module name |
| `fileToModule` map in Compiler.java | Removed — alias chains flow through the data stream (PUSH_CTX markers), not through the orchestrator |

`TokenInfo.qualifiedName`, frontend JS handlers, and `ITokenMapContext` overloads from C4
remain as-is — they consume qualified names regardless of how they were computed.

`ModuleId.deriveModuleName()` is no longer the primary naming mechanism. It becomes a
fallback for files with no placement context (e.g., in-memory single-file compilation, test
helpers).

**Tests:**

| Test | Purpose |
|---|---|
| Two parents import same file | Each placement gets unique module name, different label hashes. Labels do not collide in `labelToAddress`. |
| Nested imports (3 levels) | Alias chain grows correctly: `A.B` (or `PREFIX.A.B` with source root prefix). Third level label qualified as `A.B.LABEL`. |
| `.REQUIRE` + `USING` shared placement | Shared module has single alias chain name. Both referrers resolve to the same qualified labels. |
| Same filename in different directories | Different module names (from alias chain, not filename). |
| Source root prefix on root module | Root module name = prefix, not filename. |
| No source root prefix (default root) | Root alias chain is empty. Root labels are unqualified (e.g., `MY_LABEL`, not `MAIN.MY_LABEL`). Imported modules' alias chains start directly with the alias (e.g., `MATH`, not `MAIN.MATH`). |
| EXPORT .IMPORT visibility | Parent can reference nested module's exported labels (`MOV.MATH.ADD`). |
| Non-exported import is hidden | `CALL MOV.HELPERS.CLEANUP` produces compiler error "HELPERS is not exported by MOV". |
| Multi-level EXPORT chain (3 levels) | `A.B.C.LABEL` resolves if B and C imports are exported at each level. |
| Non-exported label in exported module | `CALL MOV.MATH.INTERNAL_HELPER` produces error "INTERNAL_HELPER is not exported by MATH". |
| SymbolTable scoping with duplicate files | Two placements of same file get separate scopes, no "already defined" errors. |
| No source roots configured (default) | Falls back to single root = main file directory. |
| CLI `--source-root` flag | Same behavior as config-based source roots. |

### Phase D: Feature Consolidation

Move all feature components into `compiler/features/*/` packages, one feature at a time.
Each step creates the feature package, moves all components, creates the `XxxFeature`
registration class, **wires the feature into Compiler.java**, and removes the
corresponding inline handler registrations.

#### Registry Cutover Strategy

When a D step is the **first to touch a registry**, it performs a full cutover for
that registry:

1. Delete the registry's `initialize()`/`initializeWithDefaults()` method
2. Reduce the phase class (e.g., `PreProcessor`) to a single constructor that accepts
   a pre-built registry — no internal `initialize()` call
3. Compiler.java builds the full registry: feature-contributed handlers (from
   `FeatureRegistry`) + inline registrations for not-yet-migrated features
4. Update ALL tests that construct the phase class to provide explicit registries

Subsequent D steps that touch the same registry simply move their inline registrations
from Compiler.java into their `XxxFeature.register()`. No legacy code to clean up,
no shrinking `initialize()`, no merge logic.

**D1** introduces the wiring infrastructure in Compiler.java (`FeatureRegistry`,
feature list, registration loop) and performs the full cutover for
`PreProcessorHandlerRegistry`. By D14, all registries are fully feature-driven and
all `initialize()`/`initializeWithDefaults()` methods are gone.

**Registry cutover points:**

| Registry | First D-step | Features at cutover |
|---|---|---|
| `PreProcessorHandlerRegistry` | **D1** | repeat, source, macro, ctx, importdir |
| `ParserDirectiveRegistry` | **D4b** | ctx, define, reg, proc, preg, org, dir, place, importdir, require |
| `IrConverterRegistry` | **D4c** | ctx + 12 others |
| `LayoutDirectiveRegistry` | **D4d** | ctx, org, dir, place (built inside `LayoutEngine`) |
| `PostProcessHandlerRegistry` | **D7** | define, reg, preg |
| `AnalysisHandlerRegistry` | **D7** | define, reg, label, proc, preg, import, require, instruction |
| `EmissionContributorRegistry` | **D8** | reg, proc |
| `LinkingRegistry` | **D9** | label, proc |
| `EmissionRegistry` | **D13** | proc only |
| `TokenMapContributorRegistry` | — | Already hand-wired in Compiler.java (no `initialize()`) |

Feature-specific AST nodes that still hold Token references are decoupled from Token
as part of their move (same approach as Phase B: replace Token fields with extracted
values + SourceInfo, implement `ISourceLocatable`). Steps marked with **[+decouple]**
include this Token decoupling work.

| Step | Feature | Files to Move | New Files |
|------|---------|--------------|-----------|
| D1 | repeat | RepeatDirectiveHandler, CaretDirectiveHandler. **[+wiring-infra]** Introduces feature wiring in Compiler.java. **[+cutover: PreProcessorHandlerRegistry]** Deletes `initialize()`, single-constructor `PreProcessor`, all PP tests updated. **DONE.** | RepeatFeature.java |
| D2 | source | SourceDirectiveHandler (move inline registration from Compiler.java to SourceFeature) **DONE.** | SourceFeature.java |
| D3 | macro | MacroDirectiveHandler, MacroDefinition, MacroExpansionHandler **DONE.** | MacroFeature.java |
| D4a | ctx (move) | Move all ctx files into `features/ctx/` and create `CtxFeature.java`. Magic strings remain as-is until D4e extracts directive dispatch. See D4 details below. **DONE.** | CtxFeature.java |
| D4b | ctx (cutover: Parser) | **[+cutover: ParserDirectiveRegistry]** Delete `initialize()`, `Parser` takes pre-built registry, Compiler.java builds it. See D4 details below. **DONE.** | — |
| D4c | ctx (cutover: IrConverter) | **[+cutover: IrConverterRegistry]** Delete `initializeWithDefaults()`, `IrGenerator` takes pre-built registry, Compiler.java builds it. See D4 details below. **DONE.** | — |
| D4d | ctx (cutover: Layout) | **[+cutover: LayoutDirectiveRegistry]** Delete `initializeWithDefaults()`, `LayoutEngine` takes pre-built registry, Compiler.java builds it. See D4 details below. **DONE.** | — |
| D4e | Linker directive dispatch | **[+linker-dispatch]** Extract hardcoded `IrDirective` handling from `Linker` into feature-registered handlers via a new `ILinkingDirectiveHandler` interface and `LinkingDirectiveRegistry`. See D4 details below. **DONE.** | ILinkingDirectiveHandler.java, LinkingDirectiveRegistry.java |
| D5 | org | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler **DONE.** | OrgFeature.java |
| D6 | dir | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler **DONE.** | DirFeature.java |
| D7 | define | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefinePostProcessHandler, DefineNodeConverter. **[+cutover: PostProcessHandlerRegistry, AnalysisHandlerRegistry]** Introduced `StandardFeatures.java` (Single Source of Truth for built-in features) and `TestRegistries.java` (test helper mirroring Compiler.java registry building). Eliminated convenience constructors on SemanticAnalyzer and AstPostProcessor — callers must pass explicit registries. Token decoupling handled in D7a. **DONE.** | DefineFeature.java, StandardFeatures.java, TestRegistries.java (test) |
| D7a | (refactoring) | No feature move. **[+decouple: Symbol, DefineNode]** Two-part decoupling: (1) `Symbol` record: `Token name` → `String name` + `SourceInfo sourceInfo`. Adapts `SymbolTable.define()` and all callers of `new Symbol(Token, ...)` (all Analysis-Handlers, ~18 test sites). This unblocks all subsequent `[+decouple]` steps in D8–D13 because they can pass `new Symbol(name, sourceInfo, ...)` directly. (2) `DefineNode`: `Token name` → `String name` + `SourceInfo sourceInfo`. `DefineDirectiveHandler` extracts values from Token at parse time. `DefineAnalysisHandler`, `DefinePostProcessHandler`, `DefineNodeConverter` use String fields. **DONE.** | — |
| D7b | (refactoring) | No feature move. **[+DRY]** Extracted `IrGenContext.convertOperand(AstNode)` — single method handling all AST→IR operand mapping (registers, literals, identifiers with procedure-param/constant/label resolution). `InstructionNodeConverter` delegates via `ctx.convertOperand()`. `DefineNodeConverter` simplified from 30 lines to 3 lines. **[+fix]** Silent fallback `return new IrLabelRef(node.toString())` for unrecognized node types needs to be replaced with `throw new IllegalArgumentException` — see D7c. **DONE.** | — |
| D7c | (refactoring) | No feature move. **[+fix]** Replaced silent fallback `return new IrLabelRef(node.toString())` in `IrGenContext.convertOperand()` with `throw new IllegalArgumentException("Unsupported operand node type: " + node.getClass().getSimpleName())`. Unhandled node types now cause an immediate, diagnosable error instead of producing invalid IR silently. **DONE.** | — |
| D7d | (refactoring) | No feature move. **[+DRY]** Introduced `Token.toSourceInfo()` method in `model/token/Token.java` returning `new SourceInfo(fileName(), line(), column())`. Replaced all 23 `new SourceInfo(t.fileName(), t.line(), t.column())` call sites across 13 files with `t.toSourceInfo()`. Affected: Parser (8×), ProcedureSymbolCollector (4×), ProcedureTokenMapContributor (2×), LabelNode, RequireNode, ImportNode, ProcedureNode, DefineDirectiveHandler, LabelSymbolCollector, RegAnalysisHandler, PregAnalysisHandler, ImportSymbolCollector, RequireSymbolCollector. All subsequent `[+decouple]` steps (D8–D13) must use `Token.toSourceInfo()` for new extraction sites. **DONE.** | — |
| D8 | reg | See D8 details below. **[+cutover: EmissionContributorRegistry]** (Symbol already decoupled in D7a.) Moved 6 files to `features/reg/`: RegNode (decoupled Token→String+SourceInfo), RegDirectiveHandler, RegAnalysisHandler, RegPostProcessHandler, RegNodeConverter, RegisterAliasEmissionContributor. Deleted `RegisterAliasState` entirely (write-only store — alias resolution runs through AST path, not parser state). Created RegFeature.java with 5 registrations (parser, analysis, postprocess, irconverter, emissionContributor). Added `emissionContributor()` to IFeatureRegistrationContext/FeatureRegistry. Removed `EmissionContributorRegistry.initializeWithDefaults()`. Removed dead `Parser.getGlobalRegisterAliases()`. **DONE.** | RegFeature.java |
| D9 | label | See D9 details below. **[+decouple]** LabelNode: Token `labelToken` → String `name` + SourceInfo. **[+cutover: LinkingRegistry]** via LinkingContext expansion: added `symbolTable()` and `isa()` to LinkingContext (runtime deps via phase context, not constructor params). LabelRefLinkingRule + CallSiteBindingRule refactored to parameterless constructors. Moved 5 files to `features/label/`: LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter, LabelRefLinkingRule. Deleted `LinkingRegistry.initializeWithDefaults()`. Added `registerAll()` to LinkingRegistry. Linker reads ISA from context instead of creating locally. **DONE.** | LabelFeature.java |
| D10 | place | Moved 12 files to `features/place/` (+ `features/place/placement/` sub-package for AST placement types). **[+decouple]** 4 placement components: SingleValueComponent (Token→int), RangeValueComponent (Token pair→int pair), SteppedRangeValueComponent (Token triple→int triple), WildcardValueComponent (Token→pure marker, no data). PlaceNode: added SourceInfo + ISourceLocatable. PlaceNodeConverter simplified (direct int access, no Integer.parseInt). PlaceDirectiveHandler extracts int values at parse boundary. Created PlaceFeature with 3 registrations (parser, irConverter, layoutHandler). **DONE.** | PlaceFeature.java |
| D11 | require | Moved 5 files to `features/require/`. **[+decouple]** RequireNode: Token `path`, `alias` → String `path` (unquoted via Token.value()), String `alias` + SourceInfo. RequireSymbolCollector/RequireAnalysisHandler updated to use decoupled fields. Created RequireFeature with 4 registrations (parser, symbolCollector, analysisHandler, irConverter). **DONE.** | RequireFeature.java |
| D12 | importdir | Move 6 files to `features/importdir/`. **[+decouple]** ImportNode: Token `path`, `alias` + UsingClause Tokens → String + SourceInfo (Symbol already decoupled in D7a). **[+context]** PreProcessorContext expanded with `moduleTokens` (constructor injection, immutable). PreProcessor constructor changed: `String rootAliasChain` → `PreProcessorContext ppContext`. ImportSourceHandler becomes parameterless, reads `moduleTokens` from context at execution time. Conditional registration (`if !empty`) removed from Compiler.java — handler checks data availability itself. ImportFeature registers all 5 handlers (preprocessor, parser, symbolCollector, analysisHandler, irConverter), part of StandardFeatures. 7 PreProcessor call sites updated (Compiler.java + 6 tests). **DONE.** | ImportFeature.java |
| D13a | proc | File Move: 15 files → `features/proc/`. ProcFeature.java with 13 registrations. **[+cutover: EmissionRegistry]** — added `EmissionRegistry.registerAll()`, wired `featureRegistry.emissionRules()` in Compiler.java Phase 8, deleted `EmissionRegistry.initializeWithDefaults()`. **[+cutover: TokenMapContributorRegistry]** — added `TokenMapContributorRegistry.registerAll()`, wired `featureRegistry.tokenMapContributors()` in Compiler.java Phase 5, removed inline registration. Deleted `frontend/parser/ast/`, `frontend/parser/features/proc/`, `backend/emit/features/`, `backend/link/features/`. **DONE.** | ProcFeature.java |
| D13b | proc | Delete `procedureTable`, `registerProcedure()`, `getProcedureTable()` from Parser. Remove `((Parser) context).registerProcedure()` cast from ProcDirectiveHandler. Duplicate checking runs via SymbolTable only. Removed `HashMap`/`Map` imports and `ProcedureNode` import from Parser. Updated ProcedureDirectiveTest (removed procedureTable assertions). **DONE.** |  |
| D13c | proc | **[+decouple]** ProcedureNode: Token `name` → String + SourceInfo + ISourceLocatable. `List<Token>` ×3 → `List<ParamDecl>` (inner record `ParamDecl(String name, SourceInfo sourceInfo)`). PregNode: Token `alias`, `targetRegister` → String + ISourceLocatable. PregAnalysisHandler: removed redundant Token-type validation (PregDirectiveHandler validates at parse time). IrGenContext: `pushProcedureParams(List<Token>)` → `pushProcedureParams(List<String>)`, Token import eliminated. PregDirectiveHandler: D7d fix (`new SourceInfo(...)` → `Token.toSourceInfo()`). Also fixed Emitter programId bug: `HashMap.hashCode()` (identity-based) → `Arrays.hashCode()` (content-based) for deterministic builds. **DONE.** |  |
| D13d | parser | **[+infra]** `ParserDirectiveRegistry` → `ParserStatementRegistry` with keyword-based dispatch + default handler + duplicate-registration guard. `IParserDirectiveHandler` → `IParserStatementHandler`. `declaration()` consolidated as single dispatch point (deleted `directiveStatement()` + `statement()`). `ctx.parser()` → `ctx.parserStatement()` + `ctx.defaultParserStatement()` on IFeatureRegistrationContext/FeatureRegistry. 11 handler files + 9 feature files + 13 test files + Compiler.java updated. **DONE.** |  |
| D13e | label | **[+rewrite]** Label syntax (`NAME:`) rewritten in preprocessor to `.LABEL NAME` via `ColonLabelHandler` (same pattern as `^` → `.REPEAT`). `LabelDirectiveHandler` parses `.LABEL` in the parser. LabelFeature registers both. Preprocessor dispatch expanded (TokenType restriction removed). Label/typed-literal disambiguation via forward look (NUMBER after COLON = typed literal). CaretDirectiveHandler updated to skip `.LABEL IDENTIFIER` instead of `IDENTIFIER COLON`. Parser `LabelNode` import eliminated — zero feature imports in Parser. **DONE.** |  |
| D13f | proc | **[+ast+ir]** Created `CallNode` (AST) + `IrCallInstruction extends IrInstruction` (IR) in `features/proc/`. Cleaned up `InstructionNode` (removed refArguments/valArguments) and `IrInstruction` (removed refOperands/valOperands, made non-final). Added `TokenKind.INSTRUCTION` — generic opcode mapping replaces hardcoded CALL/RET. Extracted CALL logic: Parser → `CallStatementHandler`, InstructionAnalysisHandler → `CallAnalysisHandler`, InstructionNodeConverter → `CallNodeConverter`. `CallerMarshallingRule` + `CallSiteBindingRule` updated to use `IrCallInstruction`. `InstructionTokenMapContributor` now fully generic. Artifact impact: tokenMap has new INSTRUCTION entries for all opcodes; machineCodeLayout identical. **DONE.** |  |
| D13g | ctx | **[+interface]** Created `IModuleContextBoundary` capability interface in `model/ast/` (`isPush()`, `aliasChain()`). PushCtxNode + PopCtxNode implement it. ModuleContextTracker uses interface — `features/ctx/` imports eliminated. **Completion criterion met: zero framework→feature imports in `frontend/` and `backend/` (verified: imports + fully-qualified references).** **DONE.** |  |
| D14 | instruction | Moved InstructionAnalysisHandler, InstructionNodeConverter, InstructionTokenMapContributor to `features/instruction/`. Created `InstructionParsingHandler` (default handler, extracted from Parser.instructionStatement()). Created `InstructionFeature` with 4 registrations (defaultParserStatement, analysisHandler, tokenMapContributor, irConverter). InstructionAnalysisHandler made stateless. Parser.instructionStatement() deleted — declaration() dispatches via default handler. Compiler.java: zero inline registrations remaining. **Phase D complete.** **DONE.** | InstructionFeature.java |

Order rationale: start with simple features (few phases), end with complex ones
(proc spans 6+ phases). Each step is independently committable with all tests green.
D1–D6 have no Token decoupling. D7a decouples `Symbol` (Token → String + SourceInfo)
and `DefineNode`, establishing the foundation for all subsequent `[+decouple]` steps.
D8–D13 decouple their feature AST nodes; because `Symbol` is already decoupled,
each handler can pass `new Symbol(name, sourceInfo, ...)` directly.
D7b is a pure DRY refactoring step separated from D7 to keep the cutover step focused.
D7c fixes a silent fallback in the shared operand conversion method.
D7d eliminates repetitive Token→SourceInfo boilerplate via `Token.toSourceInfo()`.
D8–D13 must use `Token.toSourceInfo()` (from D7d) for all new Token→SourceInfo extraction sites.

**D4 details (ctx — split into D4a-D4d):**

D4 is the largest D step because the ctx feature spans 4 phases (2, 3, 7, 9) and
is the first to touch three registries. To keep each commit focused and independently
verifiable, D4 is split into four sub-steps.

*D4a — Feature move + constants:*

Move 9 files into `features/ctx/` package and create `CtxFeature.java`:
- `frontend/preprocessor/PopCtxDirectiveHandler` (preprocessor, Phase 2)
- `frontend/parser/features/ctx/PushCtxDirectiveHandler` (parser, Phase 3)
- `frontend/parser/features/ctx/PopCtxDirectiveHandler` (parser, Phase 3)
- `frontend/parser/ast/PushCtxNode` (AST node)
- `frontend/parser/ast/PopCtxNode` (AST node)
- `frontend/irgen/converters/PushCtxNodeConverter` (Phase 7)
- `frontend/irgen/converters/PopCtxNodeConverter` (Phase 7)
- `backend/layout/features/PushCtxLayoutHandler` (Phase 9)
- `backend/layout/features/PopCtxLayoutHandler` (Phase 9)

Magic strings (`"core"`, `"push_ctx"`, `"pop_ctx"`) remain as raw string literals
for now. They become feature-internal constants naturally once D4e extracts directive
dispatch from the Linker — at that point the only remaining consumers are inside
`features/ctx/` itself.

Update imports in `ParserDirectiveRegistry.initialize()`,
`IrConverterRegistry.initializeWithDefaults()`, and
`LayoutDirectiveRegistry.initializeWithDefaults()` to point to the new package.
The `initialize()` methods remain functional — they are deleted in D4b-D4d.

*D4b — Cutover: ParserDirectiveRegistry:*

Delete `ParserDirectiveRegistry.initialize()` and all feature handler imports.
`Parser` currently calls `initialize()` in its constructor (line 47). Replace with
a single constructor that accepts a pre-built `ParserDirectiveRegistry`. Compiler.java
builds the registry: feature-contributed handlers (from `FeatureRegistry`) + inline
registrations for not-yet-migrated features (define, reg, proc, preg, org, dir,
place, import, require).

13 test files construct `Parser` directly (44 call sites total) and must be updated
to provide an explicit registry:

- `ParserTest` (8), `ProcedureDirectiveTest` (8), `RequireDirectiveTest` (6),
  `ImportDirectiveTest` (5), `RegDirectiveTest` (5), `LayoutDirectiveTest` (3),
  `DefineDirectiveTest` (2), `PregDirectiveTest` (2),
  `UsingClauseIntegrationTest` (1), `ModuleSourceDefineIntegrationTest` (1),
  `EmissionIntegrationTest` (1), `IrGeneratorTest` (1), `SemanticAnalyzerTest` (1)

Each test builds a `ParserDirectiveRegistry` with exactly the handlers it needs
(same approach as D1 for `PreProcessorHandlerRegistry`).

*D4c — Cutover: IrConverterRegistry:*

Delete `IrConverterRegistry.initializeWithDefaults()` and all feature converter
imports. `IrGenerator` currently calls `initializeWithDefaults()` in `Compiler.java`
(line 246). Replace: Compiler.java builds the registry externally using feature
contributions + inline registrations for not-yet-migrated converters (instruction,
label, org, dir, place, proc, define, import, require, reg, preg). Two test files
construct `IrConverterRegistry` directly and need updating: `IrGeneratorTest` and
`EmissionIntegrationTest`.

*D4d — Cutover: LayoutDirectiveRegistry:*

Delete `LayoutDirectiveRegistry.initializeWithDefaults()` and all feature handler
imports. `LayoutEngine` currently calls `initializeWithDefaults()` inside its
`layout()` method (line 32). Replace: `LayoutEngine.layout()` accepts a pre-built
`LayoutDirectiveRegistry` parameter (or Compiler.java passes it in). Compiler.java
builds the registry: feature-contributed handlers + inline registrations for
not-yet-migrated handlers (org, dir, place). `LayoutEngineTest` reaches the
registry indirectly through `LayoutEngine` — it needs updating to pass an explicit
registry.

*D4e — Linker directive dispatch:*

The Linker currently has hardcoded `if/else` logic for `push_ctx`/`pop_ctx`
`IrDirective` items (lines 46-57). This is feature-specific logic inside a phase
class — a direct violation of the feature-agnostic core principle.

The fix follows the same dual-dispatch pattern that Layout (Phase 9) already uses:
instructions dispatch through `ILinkingRule`, directives dispatch through a new
`ILinkingDirectiveHandler`. These are separate interfaces because `IrInstruction`
and `IrDirective` are different types with different processing semantics
(instruction rules transform and return a rewritten instruction; directive handlers
mutate context state).

New files in `backend/link/`:

- `ILinkingDirectiveHandler`: `void handle(IrDirective directive, LinkingContext context)`
- `LinkingDirectiveRegistry`: keyed by `namespace:name` string (same pattern as
  `LayoutDirectiveRegistry`). `resolve(IrDirective)` returns the matching handler.

Changes:

1. Create `ILinkingDirectiveHandler` interface in `backend/link/`.
2. Create `LinkingDirectiveRegistry` in `backend/link/`.
3. Add `linkingDirectiveHandler(String namespace, String name, ILinkingDirectiveHandler handler)`
   to `IFeatureRegistrationContext` and implement in `FeatureRegistry`.
4. Refactor `Linker`: constructor takes both `LinkingRegistry` (instructions) and
   `LinkingDirectiveRegistry` (directives). The `link()` loop dispatches directives
   through `registry.resolve(dir).handle(dir, context)` instead of hardcoded if/else.
5. `Compiler.java` builds `LinkingDirectiveRegistry`: feature-contributed handlers
   from `FeatureRegistry` (including ctx).
6. Update `CtxFeature.register()` to include:
   `ctx.linkingDirectiveHandler("core", "push_ctx", new PushCtxLinkingHandler())`
   and `ctx.linkingDirectiveHandler("core", "pop_ctx", new PopCtxLinkingHandler())`.
   These two small handler classes live in `features/ctx/`.
7. Remove the hardcoded `push_ctx`/`pop_ctx` if/else from `Linker.link()`.

After this step, `Linker` is a pure dispatcher with zero feature knowledge — the
same property that `LayoutEngine` already has via `LayoutDirectiveRegistry`.

**D8 details (reg):**

Move to `features/reg/`: RegNode, RegDirectiveHandler, RegAnalysisHandler, RegPostProcessHandler (NEW, extracted from AstPostProcessor).

Additionally:
1. ~~Move `RegisterAliasState` from `parser/` to `features/reg/`.~~ **Deleted entirely** — `RegisterAliasState` was a write-only store. The `addAlias()` calls in RegDirectiveHandler and PregDirectiveHandler had no readers; alias resolution runs entirely through the AST path (RegNode → RegPostProcessHandler → AstPostProcessor register replacement). Removed `addAlias()` calls from both directive handlers and deleted RegisterAliasState.java.
2. Remove `getGlobalRegisterAliases()` from Parser (dead method after RegisterAliasState removal).
3. Update tests that call `parser.getGlobalRegisterAliases()`: RegDirectiveTest, DefineDirectiveTest, ModuleSourceDefineIntegrationTest.
4. **[+decouple]** RegNode: Token `alias`, `register` → String + SourceInfo. `RegAnalysisHandler` passes `new Symbol(name, sourceInfo, ...)` directly (Symbol already decoupled in D7a).

**D9 details (label):**

Two parts, executed in order: infrastructure (LinkingContext expansion + LinkingRegistry cutover), then feature consolidation.

**Part 1: LinkingContext infrastructure**

`LabelRefLinkingRule` needs `SymbolTable` and `CallSiteBindingRule` needs `IInstructionSet` — but only at execution time (in `apply()`), not at construction time. Currently both receive these via constructor parameters, which blocks parameterless feature registration. Fix: move runtime dependencies into `LinkingContext`, matching the established pattern in all other phases (IrGenContext, EmissionContext, LayoutContext).

1. Expand `LinkingContext`: add `SymbolTable symbolTable()` and `IInstructionSet isa()` fields, set at construction time by the `Linker`.
2. Refactor `LabelRefLinkingRule`: remove `SymbolTable` constructor parameter. Read from `context.symbolTable()` in `apply()`.
3. Refactor `CallSiteBindingRule`: remove `IInstructionSet` constructor parameter. Read from `context.isa()` in `apply()`. (This benefits D13 — CallSiteBindingRule becomes parameterless and can be registered via `ctx.linkingRule()` when it moves to `features/proc/`.)
4. Update `Linker`: pass `SymbolTable` and `IInstructionSet` when constructing `LinkingContext` (or via setter before `link()` is called). Remove local `IInstructionSet isa = new RuntimeInstructionSetAdapter()` in Linker line 46 — read from `context.isa()` instead.
5. Delete `LinkingRegistry.initializeWithDefaults()` — hardcodes feature knowledge into a phase registry.
6. Update `Compiler.java`: register both rules parameterlessly. Pass `SymbolTable` + `IInstructionSet` to LinkingContext construction instead of to rule constructors.

**Part 2: Label feature consolidation**

Move to `features/label/`: LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter, LabelRefLinkingRule.

1. **[+decouple]** `LabelNode`: `Token labelToken` → `String name` + `SourceInfo sourceInfo`. The 2-arg convenience constructor `LabelNode(Token, AstNode)` is removed — `Parser.statement()` (only call site) passes extracted values. `reconstructWithChildren()` uses String fields.
2. Update `LabelSymbolCollector`: `lbl.labelToken().text()` → `lbl.name()`, `lbl.labelToken().toSourceInfo()` → `lbl.sourceInfo()`.
3. Update `LabelNodeConverter`: `node.labelToken().text()` → `node.name()`.
4. Create `LabelFeature.java` with registrations: symbolCollector, analysisHandler, irConverter, linkingRule.
5. Add `new LabelFeature()` to `StandardFeatures.all()`.
6. Remove hardcoded label registrations from `Compiler.java` and `TestRegistries.java`.
7. Update all import references in tests.

**D10 details (place):**

Moved to `features/place/`: PlaceNode (+ISourceLocatable, +SourceInfo), PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler.
Moved to `features/place/placement/`: IPlacementComponent, IPlacementArgumentNode, SingleValueComponent, RangeValueComponent, SteppedRangeValueComponent, WildcardValueComponent, VectorPlacementNode, RangeExpressionNode.
IR placement types (`model/ir/placement/`) unchanged — belong to IR data format layer.
Token decoupling: 4 placement components stripped of Token fields → extracted int values at parse boundary. WildcardValueComponent became a pure marker record (no fields). PlaceNodeConverter simplified: direct int field access replaces `Integer.parseInt(token.text())`.

**D12 details (importdir):**

Two parts: infrastructure (PreProcessorContext expansion), then feature consolidation.

**Part 1: PreProcessorContext infrastructure**

`ImportSourceHandler` needs `moduleTokens` — a Phase 1 artifact (pre-lexed module tokens) not available at feature registration time. Fix: move runtime dependency into `PreProcessorContext`, matching the established pattern (LinkingContext in D9).

1. Expand `PreProcessorContext`: add `Map<String, List<Token>> moduleTokens` as constructor parameter (constructor injection, immutable). Add getter `moduleTokens()`. Constructor signature: `PreProcessorContext(String rootAliasChain, Map<String, List<Token>> moduleTokens)`.
2. Change `PreProcessor` constructor: replace `String rootAliasChain` parameter with `PreProcessorContext ppContext`. PreProcessor no longer creates its own context internally.
3. Refactor `ImportSourceHandler`: remove `moduleTokens` constructor parameter. Read from `preProcessorContext.moduleTokens()` in `process()`.
4. Remove conditional registration from `Compiler.java`: `if (!moduleTokens.isEmpty()) { ppRegistry.register(...) }` is deleted. The handler checks data availability itself.
5. Update 7 PreProcessor call sites: `Compiler.java` + 6 tests (`UsingClauseIntegrationTest`, `ModuleSourceDefineIntegrationTest` ×2, `RepeatDirectiveTest`, `MacroDirectiveTest`, `PreProcessorTest`). Tests without modules pass `new PreProcessorContext()` (empty map default).

**Part 2: Feature consolidation + Token decoupling**

Move to `features/importdir/`: ImportNode, ImportDirectiveHandler, ImportSourceHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter.

Token decoupling:
- `ImportNode`: `Token path` → `String path` (unquoted via `Token.value()`), `Token alias` → `String alias`, add `SourceInfo sourceInfo` (record accessor replaces explicit `alias.toSourceInfo()` delegation). Retains `ISourceLocatable`. Remove backward-compatible 3-arg constructor.
- `UsingClause`: `Token sourceAlias, Token targetAlias` → `String sourceAlias, String targetAlias, SourceInfo sourceSourceInfo, SourceInfo targetSourceInfo`. Both SourceInfo needed because `ImportAnalysisHandler` reports errors at both source and target locations independently.
- `ImportDirectiveHandler`: extracts String values and SourceInfo at parse boundary via `Token.value()`, `Token.text()`, `Token.toSourceInfo()`.
- `ImportSymbolCollector`: `t.text()` → `importNode.alias()`, `t.toSourceInfo()` → `importNode.sourceInfo()`. Remove Token import.
- `ImportAnalysisHandler`: all `.text()` → direct String fields, all `.fileName()`/`.line()` → SourceInfo fields. Remove Token access.

Create `ImportFeature.java` with 5 registrations (preprocessor, parser, symbolCollector, analysisHandler, irConverter). Add to `StandardFeatures.all()`.

**D13 details (proc — includes preg + parser unification):**

Split into 7 sub-steps (D13a → D13b → D13c → D13d → D13e → D13f → D13g) due to complexity. D13a-c focus on the proc feature consolidation. D13d-f resolve Parser dependency violations by unifying all statement dispatch through a single `ParserStatementRegistry`. D13g resolves the ModuleContextTracker dependency violation via a capability interface. After D13g, zero framework→feature imports remain.

**D13a — File Move + ProcFeature + EmissionRegistry Cutover:**

Move 15 files to `features/proc/`:
- Already in `frontend/parser/features/proc/`: ProcDirectiveHandler, ProcedureNode, PregDirectiveHandler
- Move from `frontend/parser/ast/`: PregNode
- Move from `frontend/semantics/analysis/`: ProcedureSymbolCollector, ProcedureAnalysisHandler, PregAnalysisHandler
- Move from `frontend/tokenmap/`: ProcedureTokenMapContributor
- Move from `frontend/postprocess/`: PregPostProcessHandler
- Move from `frontend/irgen/converters/`: ProcedureNodeConverter, PregNodeConverter
- Already in `backend/emit/features/`: ProcedureMarshallingRule, CallerMarshallingRule
- Move from `backend/emit/`: ProcedureEmissionContributor
- Already in `backend/link/features/`: CallSiteBindingRule

EmissionRegistry cutover (last `initializeWithDefaults()`):
1. `ctx.emissionRule()` already exists on `IFeatureRegistrationContext` + `FeatureRegistry`
2. Add `EmissionRegistry.registerAll(List<IEmissionRule>)` method (same pattern as `LinkingRegistry.registerAll()`)
3. Wire in Compiler.java Phase 8: `emissionRegistry.registerAll(featureRegistry.emissionRules())` — replaces `initializeWithDefaults()`
4. Delete `EmissionRegistry.initializeWithDefaults()`
5. ProcFeature registers ProcedureMarshallingRule + CallerMarshallingRule via `ctx.emissionRule()`

TokenMapContributorRegistry cutover:
1. Add `TokenMapContributorRegistry.registerAll(Map<Class<? extends AstNode>, ITokenMapContributor>)` method
2. Wire in Compiler.java Phase 5: `tokenMapRegistry.registerAll(featureRegistry.tokenMapContributors())`
3. Remove inline registration of ProcedureTokenMapContributor from Compiler.java

Create ProcFeature.java with 13 registrations: parser ×2 (.PROC, .PREG), symbolCollector, analysisHandler ×2, tokenMapContributor, postProcessHandler, irConverter ×2, emissionRule ×2, linkingRule, emissionContributor.

CallSiteBindingRule is already parameterless after D9.

**D13b — Procedure Table Deletion:**

`procedureTable` is a Parser-internal `Map<String, ProcedureNode>` that duplicates the SymbolTable's role. The only runtime writer is `ProcDirectiveHandler` via `((Parser) context).registerProcedure()` — a framework cast that embeds feature knowledge. The only reader is `ProcedureDirectiveTest`. No compilation phase reads it.

1. Delete from Parser: `procedureTable` field, `registerProcedure()`, `getProcedureTable()`
2. Remove `((Parser) context).registerProcedure(procNode)` from ProcDirectiveHandler
3. Update ProcedureDirectiveTest: verify procedure registration via SymbolTable instead of procedureTable

**D13c — Token Decoupling:**

ProcedureNode:
- `Token name` → `String name` + `SourceInfo sourceInfo` (ISourceLocatable)
- `List<Token> parameters/refParameters/valParameters` → `List<ParamDecl>` each
- Inner record: `ParamDecl(String name, SourceInfo sourceInfo)` — analogous to `ImportNode.UsingClause`, needed because ProcedureTokenMapContributor requires per-parameter SourceInfo for IDE/LSP support
- Remove backward-compatible constructors if any

PregNode:
- `Token alias` → `String alias`
- `Token targetRegister` → `String targetRegister`
- Add `implements ISourceLocatable` (SourceInfo field already exists, accessor already present)
- Helper methods `aliasName()`, `registerIndexValue()`, `fullAliasText()` operate on String fields directly

PregDirectiveHandler (parse boundary extraction):
- Extract alias/targetRegister as String at parse boundary, pass SourceInfo via `Token.toSourceInfo()` (fixes D7d violation: manual `new SourceInfo(fileName, line, column)` → `alias.toSourceInfo()`)

PregAnalysisHandler:
- Remove `isValidProcedureRegister()` and `isValidAliasName()` — both check `Token.type()` (TokenType.REGISTER, TokenType.IDENTIFIER) which is unavailable after decoupling. PregDirectiveHandler already performs identical validations at parse time (alias type check lines 31-37, register validation lines 44-72). The duplicate validation is redundant.
- Remaining logic: `symbolTable.define(...)` using decoupled String/SourceInfo fields directly

IrGenContext:
- `pushProcedureParams(List<Token>)` → `pushProcedureParams(List<String>)` — method body already only extracts `token.text().toUpperCase()`
- Remove Token import from IrGenContext

Update consumers: ProcedureSymbolCollector, ProcedureAnalysisHandler, PregAnalysisHandler, ProcedureNodeConverter, PregNodeConverter, ProcedureTokenMapContributor, PregPostProcessHandler.

**D13d — Unified ParserStatementRegistry:**

Parser (framework code) currently imports from feature packages: `LabelNode` from `features/label/` and `ProcedureNode` from `features/proc/`. This violates the dependency direction rule (framework must never depend on features). Root cause: Parser can only dispatch directives (`.XXX` prefix) generically via `ParserDirectiveRegistry`. Labels (`NAME:`) and the special case `CALL` are hardcoded directly in Parser.

Solution: Generalize the Parser dispatch so that **every statement** is dispatched through a registry keyed by keyword. The Parser knows no features — it only knows the registry.

Infrastructure changes:
1. Rename `ParserDirectiveRegistry` → `ParserStatementRegistry` — dispatches any keyword (case-insensitive), not just directive names
2. Rename `IParserDirectiveHandler` → `IParserStatementHandler` — same contract: `AstNode parse(ParsingContext context)`
3. Add support for exactly one **default handler** — invoked when no keyword matches (handles generic instructions like MOV, ADD). Double registration of keywords or double default registration throws immediately.
4. Rename `ctx.parser(String, IParserDirectiveHandler)` → `ctx.parserStatement(String keyword, IParserStatementHandler)` on `IFeatureRegistrationContext` + `FeatureRegistry`. Add `ctx.defaultParserStatement(IParserStatementHandler)` for the default handler.
5. Consolidate `declaration()` and `statement()` into a single dispatch path. Currently `declaration()` checks for DIRECTIVE → `directiveStatement()`, then falls through to `statement()` which checks for label, DIRECTIVE again, and instructions — two dispatch points for the same registry. After D13d: `declaration()` skips newlines, handles EXPORT keyword, then dispatches through the registry (single lookup). The separate `directiveStatement()` method is deleted. EXPORT handling stays in `declaration()` — it is recognized before the registry lookup, and the export flag is passed to handlers via `ParsingContext`.
6. Parser constructor takes `ParserStatementRegistry` instead of `ParserDirectiveRegistry`
7. All existing directive handlers work unchanged — directives are just keywords with a `.` prefix
8. Update all call sites constructing `ParserDirectiveRegistry` (Compiler.java + tests)

Note: ProcDirectiveHandler's use of `context.state().pushScope()`/`popScope()` is already clean generic `ParserState` API — no extraction or refactoring needed.

**D13e — Label Preprocessor Rewrite:**

Label syntax (`NAME:`) is currently parsed directly in `Parser.statement()` (line 112-116), creating a `LabelNode` and importing it from `features/label/`. This violates dependency direction.

Solution: Rewrite label syntax in Phase 2 (preprocessor), following the established `CaretDirectiveHandler` pattern (`^` → `.REPEAT`).

Prerequisite — Preprocessor dispatch expansion:
The preprocessor currently only looks up DIRECTIVE and IDENTIFIER tokens in the registry (`PreProcessor.expand()` line 55: `if (token.type() == TokenType.DIRECTIVE || token.type() == TokenType.IDENTIFIER)`). A handler registered for `:` (COLON token) would never fire. Fix: Remove the TokenType restriction — the preprocessor looks up every token in the registry. No handler registered → token is skipped as before. This is safe because the registry lookup is a no-op for unregistered tokens.

Label rewrite:
1. Create `ColonLabelHandler implements IPreProcessorHandler` in `features/label/` — detects `IDENTIFIER COLON` pattern in token stream, rewrites to `.LABEL IDENTIFIER` tokens (plus the rest of the line as the labeled statement)
2. Create `LabelDirectiveHandler implements IParserStatementHandler` in `features/label/` — parses `.LABEL NAME <statement>` syntax, creates `LabelNode`. Handles EXPORT flag from ParsingContext.
3. LabelFeature registers both: `ctx.preprocessor(":", new ColonLabelHandler())` and `ctx.parserStatement(".LABEL", new LabelDirectiveHandler())`
4. Remove hardcoded label detection from `Parser.declaration()` (formerly in `statement()`, consolidated in D13d)
5. Parser import of `LabelNode` eliminated
6. Existing assembly files unchanged — `NAME:` remains valid syntax (rewritten transparently)

**D13f — CALL Logic Extraction via CallNode + IrCallInstruction:**

CALL-specific logic is scattered across 4 framework classes at the AST level, and CALL-specific fields exist in both `InstructionNode` (AST) and `IrInstruction` (IR). Both are feature-specific data in generic types.

Solution: CALL gets its own types at both data layers — `CallNode` (AST) in `features/proc/` and `IrCallInstruction` (IR) in `features/proc/`. Both generic types are cleaned up. Additionally, `InstructionTokenMapContributor` is made fully generic by introducing `TokenKind.INSTRUCTION`.

**Part 1: CallNode AST type**

Create `CallNode` record in `features/proc/`:
- `String procedureName` — the called procedure (extracted from first argument)
- `List<AstNode> refArguments` — REF arguments (new syntax)
- `List<AstNode> valArguments` — VAL arguments (new syntax)
- `List<AstNode> legacyArguments` — old-style WITH arguments (empty for new syntax)
- `SourceInfo sourceInfo` — source location
- Implements `AstNode`, `ISourceLocatable`

**Part 2: InstructionNode cleanup**

Remove `refArguments` and `valArguments` from `InstructionNode` (`model/ast/`). The record becomes:
- `String opcode, List<AstNode> arguments, SourceInfo sourceInfo`
- Remove the 5-arg constructor, compact constructor null-checks for ref/val, and the `getChildren()`/`reconstructWithChildren()` complexity

**Part 3: IrCallInstruction IR type**

`IrInstruction` currently has `refOperands` and `valOperands` fields that only exist for CALL — same problem as InstructionNode.

1. Make `IrInstruction` non-final (remove `final` modifier). It keeps only: `opcode`, `operands`, `source`, `synthetic`
2. Remove `refOperands` and `valOperands` from `IrInstruction`
3. Create `IrCallInstruction extends IrInstruction` in `features/proc/` — adds `refOperands` and `valOperands` fields
4. `IrCallInstruction` overrides `equals()`/`hashCode()` including the additional fields. `IrInstruction` already uses `getClass()` in equals (not `instanceof`), so subclass comparison is correct.
5. Framework phases (LayoutEngine, Linker) see `IrInstruction` via inheritance — `opcode()`, `operands()` work transparently
6. Proc emission rules (`CallerMarshallingRule`, `CallSiteBindingRule`) cast to `IrCallInstruction` to access REF/VAL operands

**Part 4: TokenKind.INSTRUCTION + generic opcode mapping**

`InstructionTokenMapContributor` currently hardcodes CALL and RET with `TokenKind.CONSTANT` — feature knowledge in a generic contributor, and CONSTANT is semantically wrong for opcodes.

1. Add `INSTRUCTION` to the `TokenKind` enum (`api/TokenKind.java`)
2. `InstructionTokenMapContributor` maps ALL opcodes to `TokenKind.INSTRUCTION` — fully generic, zero feature knowledge
3. No separate `CallTokenMapContributor` needed — the generic contributor handles all opcodes uniformly
4. Protobuf serializes tokenType as string — `"INSTRUCTION"` is backward-compatible (new artifacts work with new code)

**Part 5: Parser → CallStatementHandler**

1. Create `CallStatementHandler implements IParserStatementHandler` in `features/proc/`
2. Move `parseCallInstruction()` logic into `CallStatementHandler.parse()` — produces `CallNode` instead of `InstructionNode`
3. ProcFeature registers via `ctx.parserStatement("CALL", new CallStatementHandler())`
4. Remove `parseCallInstruction()` and the `if ("CALL".equalsIgnoreCase(...))` check from `Parser.instructionStatement()`

**Part 6: InstructionAnalysisHandler → CallAnalysisHandler**

1. Create `CallAnalysisHandler implements IAnalysisHandler` in `features/proc/`
2. Move the entire CALL validation block (procedure resolution, `instanceof ProcedureNode`, REF/VAL count validation, argument type validation, legacy WITH validation) into `CallAnalysisHandler.analyze()`
3. ProcFeature registers via `ctx.analysisHandler(CallNode.class, new CallAnalysisHandler())`
4. Remove CALL block from `InstructionAnalysisHandler` — including the fully-qualified `instanceof org.evochora.compiler.features.proc.ProcedureNode` reference
5. `InstructionAnalysisHandler` becomes a pure generic instruction validator

**Part 7: InstructionNodeConverter → CallNodeConverter**

1. Create `CallNodeConverter implements IAstNodeToIrConverter<CallNode>` in `features/proc/`
2. Move CALL IR generation into `CallNodeConverter.convert()` — emits `IrCallInstruction` (new syntax) or `IrDirective("core", "call_with", ...)` (legacy syntax)
3. ProcFeature registers via `ctx.irConverter(CallNode.class, new CallNodeConverter())`
4. Remove CALL branches from `InstructionNodeConverter` — it becomes a pure generic instruction converter
5. `IrGenContext.resolveProcedureParam()` stays in IrGenContext (generic parameter scope infrastructure)

**Artifact impact:** The `tokenMap` section of the Program Artifact changes — all opcodes get `TokenKind.INSTRUCTION` entries instead of only CALL/RET with `TokenKind.CONSTANT`. The `machineCodeLayout`, `labels`, `procedures`, `registers` sections must remain identical. Verification: diff baseline, confirm only tokenMap differences, set new baseline.

After D13f: zero CALL/proc references in any framework class. `InstructionNode` and `IrInstruction` are pure generic types. All registries remain `Map<Class, Handler>` — no multi-handler complexity.

**D13g — ModuleContextTracker Dependency Decoupling:**

`ModuleContextTracker` (framework code, `frontend/semantics/`) imports `PushCtxNode` and `PopCtxNode` from `features/ctx/` and uses `instanceof` to detect module context boundaries during AST traversal. This violates the dependency direction rule.

Module context tracking is a framework concern — multiple phases (SemanticAnalyzer, TokenMapGenerator, AstPostProcessor) need to know which module context they are in. The ctx feature *creates* the context boundaries in the AST, but *using* those boundaries is framework responsibility. Analogous to SymbolTable: it is framework, even though features write the symbols.

Solution: A capability interface in `model/ast/` — the same pattern as `ISourceLocatable`. The framework checks only the interface; the feature nodes implement it.

1. Create `IModuleContextBoundary` in `model/ast/`:
   - `boolean isPush()` — true for context entry, false for context exit
   - `String aliasChain()` — the module alias chain for push (null for pop and for .SOURCE-type push where parent context is preserved)
2. `PushCtxNode implements IModuleContextBoundary` — `isPush()` returns `true`, `aliasChain()` returns the existing field
3. `PopCtxNode implements IModuleContextBoundary` — `isPush()` returns `false`, `aliasChain()` returns `null`
4. `ModuleContextTracker.handleNode()`: replace `instanceof PushCtxNode`/`PopCtxNode` with `instanceof IModuleContextBoundary boundary`, then `boundary.isPush()` to distinguish push/pop
5. Remove imports of `PushCtxNode` and `PopCtxNode` from `ModuleContextTracker`

**Completion criterion:** After D13g, zero imports from `features/` packages exist in `frontend/` and `backend/` packages. The dependency direction rule is fully enforced: features depend on the framework, never the reverse.

### Phase E: Cleanup & Relocation

E1 (wiring) has been absorbed into Phase D — each D step wires its feature, and
cutover steps eliminate `initialize()`/`initializeWithDefaults()` methods completely.
By D14, all registries are fully feature-driven.

| Step | Description |
|------|-------------|
| ~~E1~~ | ~~Wire `CompilerFeature` registration in Compiler.java~~ — **absorbed into Phase D**. **DONE.** |
| E2 | Delete empty `frontend/*/features/` directories. Deleted: `frontend/parser/features/reg/`, `frontend/parser/features/`, `frontend/irgen/converters/`. **DONE.** |
| E3a | Moved `SourceRootResolver` to `compiler/util/`. Updated 9 consumers + 2 fully-qualified references. **DONE.** |
| E3b | Moved SymbolTable, Symbol, ResolvedSymbol, ModuleScope to `compiler/model/symbols/`. Updated 32+ consumers. Added explicit imports for same-package files (SemanticAnalyzer, ModuleVisibilityTest, etc.). **DONE.** |
| E3c | Moved `ModuleContextTracker` to `compiler/model/`. Updated 7 consumers. **DONE.** |

### Phase F: Cleanup (optional, low priority)

| Step | Description |
|------|-------------|
| F1 | **Source pre-lexing + cleanup.** Part 1: DependencyScanner collects .SOURCE content, Compiler.java pre-lexes in Phase 1, tokens flow via `PreProcessorContext.sourceTokens()`. SourceDirectiveHandler rewritten to read pre-lexed tokens (no Lexer, no file I/O, no loadContent()). Part 2: Dead Classpath-fallback deleted (entire loadContent() method removed). Part 3: EOF-stripping centralized in `Lexer.stripEofToken()`. .SOURCE file contents added to artifact sources via `depScanner.sourceContents()`. **DONE.** |
| ~~F2~~ | ~~Extract cross-phase data extraction from Compiler.java~~ — **absorbed into Phase D**: `getGlobalRegisterAliases()` removed in D8, `getProcedureTable()` removed in D13. |
| F3a | **DependencyScanner generification.** IDependencyInfo marker interface + directiveName(). IDependencyScanContext redesigned (generic operations). 3 feature scan handlers (ImportDependencyScanHandler, RequireDependencyScanHandler, SourceDependencyScanHandler) with 3 DependencyInfo records. DependencyScanner rewritten as generic line dispatcher — zero regex patterns, zero feature knowledge. scanLines() with sourceFileMode for .SOURCE validation. ModuleDescriptor transition: builds typed lists from IDependencyInfo. **DONE.** |
| F3b | **Module-setup generification + ModuleDescriptor cleanup.** ModuleDescriptor cleaned up (only `List<IDependencyInfo> dependencies()`, inner records deleted). IDependencySetupHandler<T> + ModuleSetupRegistry + ModuleSetupContext created. ImportModuleSetupHandler + RequireModuleSetupHandler in feature packages. SemanticAnalyzer.setupModuleRelationships() replaced with 3-pass registry dispatch. IDependencyInfo.resolvedModuleId() enables generic topologicalSort(). **DONE.** |

**F3a details — DependencyScanner Generification:**

DependencyScanner has hardcoded IMPORT_PATTERN, REQUIRE_PATTERN, SOURCE_PATTERN, USING_PATTERN — feature knowledge in framework code. Same violation eliminated in all other phases. Phase 0 is no exception.

**IDependencyInfo marker interface:**

Create `IDependencyInfo` in `frontend/module/` — marker interface for feature-specific dependency data (analogous to `AstNode` for AST). Feature-specific records implement it.

**IDependencyScanContext redesign:**

Replace the three feature-specific methods (`addImport()`, `addRequire()`, `addSourceFile()`) with generic operations:
- `String resolve(String path)` — resolve path relative to current file via SourceRootResolver
- `String loadContent(String resolvedPath)` — load file content (filesystem or HTTP)
- `void registerSourceContent(String resolvedPath, String content)` — register content for Phase 1 pre-lexing
- `void reportError(String message)` — report error at current line
- `void scanNestedFile(String resolvedPath, String content)` — trigger recursive scanning of nested file
- `void addDependency(IDependencyInfo dependency)` — generic method for handlers to report results

**Three feature scan handlers:**

`ImportDependencyScanHandler` in `features/importdir/`:
- Owns IMPORT_PATTERN + USING_PATTERN (USING is part of IMPORT syntax)
- On match: parses path, alias, USING clauses; resolves path via `ctx.resolve()`; triggers recursive module scan via `ctx.scanNestedFile()`; reports `ImportDependencyInfo` via `ctx.addDependency()`

`RequireDependencyScanHandler` in `features/require/`:
- Owns REQUIRE_PATTERN
- On match: parses path, alias; reports `RequireDependencyInfo` via `ctx.addDependency()`

`SourceDependencyScanHandler` in `features/source/`:
- Owns SOURCE_PATTERN
- On match: resolves path, loads content via `ctx.loadContent()`, registers content via `ctx.registerSourceContent()`, triggers recursive .SOURCE scan via `ctx.scanNestedFile()`, validates no forbidden directives (replaces `scanSourceFile()`)
- Reports `SourceDependencyInfo` via `ctx.addDependency()`

**DependencyScanner becomes generic dispatcher:**

Retains: line-by-line iteration, comment stripping, handler dispatch (iterate handlers, pattern match, call handleMatch on first match), cycle detection, topological sorting, DependencyGraph construction from `IDependencyInfo` objects collected via `addDependency()`.

Loses: all four regex patterns, all match-handling logic, `scanSourceFile()`, direct knowledge of any directive.

Receives `List<IDependencyScanHandler>` from Compiler.java via `featureRegistry.dependencyScanHandlers()`.

**ModuleDescriptor transition:**

ModuleDescriptor gets `List<IDependencyInfo> dependencies()`. In F3a, the existing typed lists (`imports`, `requires`, `sourceFiles`) and inner records (`ImportDecl`, `RequireDecl`, `UsingDecl`) may still exist in parallel as transition — they are cleaned up in F3b.

**Feature registrations:**

- `ImportFeature`: `ctx.dependencyScanHandler(new ImportDependencyScanHandler())`
- `RequireFeature`: `ctx.dependencyScanHandler(new RequireDependencyScanHandler())`
- `SourceFeature`: `ctx.dependencyScanHandler(new SourceDependencyScanHandler())`

**Compiler.java wiring:**

```java
DependencyScanner depScanner = new DependencyScanner(diagnostics, resolver, featureRegistry.dependencyScanHandlers());
```

After F3a: DependencyScanner has zero feature knowledge. All dependency directive patterns and processing logic live in feature packages.

**F3b details — Module-Setup Generification + ModuleDescriptor Cleanup:**

**Feature-specific dependency records to feature packages:**

- `ModuleDescriptor.ImportDecl` + `ModuleDescriptor.UsingDecl` → `ImportDependencyInfo` record in `features/importdir/` (implements `IDependencyInfo`)
- `ModuleDescriptor.RequireDecl` → `RequireDependencyInfo` record in `features/require/` (implements `IDependencyInfo`)
- Source data → `SourceDependencyInfo` record in `features/source/` (implements `IDependencyInfo`)

**ModuleDescriptor cleanup:**

Remove typed lists (`imports`, `requires`, `sourceFiles`) and inner records (`ImportDecl`, `RequireDecl`, `UsingDecl`). Only `List<IDependencyInfo> dependencies()` remains.

**IDependencySetupHandler<T extends IDependencyInfo>:**

New interface in `frontend/semantics/`:
- `void registerScope(T dependency, ModuleSetupContext ctx)` — Pass 1: set up scopes and alias chains
- `void resolveBindings(T dependency, ModuleSetupContext ctx)` — Pass 2: resolve cross-references (e.g., USING bindings)

**ModuleSetupRegistry:**

New registry in `frontend/semantics/` — maps `Class<? extends IDependencyInfo>` → `IDependencySetupHandler`. Same dispatch pattern as `AnalysisHandlerRegistry` (class-key → handler lookup).

**ModuleSetupContext:**

Provides SymbolTable, moduleAliasChain, pathToAliasChain mapping — everything handlers need to configure scopes.

**SemanticAnalyzer.setupModuleRelationships() replacement:**

Two passes over all modules in topological order:
- Pass 1: for each `IDependencyInfo`, look up handler via `ModuleSetupRegistry`, call `handler.registerScope()`
- Pass 2: for each `IDependencyInfo`, call `handler.resolveBindings()`

Two passes because USING bindings require all import scopes to be registered first. Dispatch via registry (class lookup, no instanceof in framework).

**Two feature setup handlers:**

- `ImportModuleSetupHandler` in `features/importdir/`: registers import alias chains (Pass 1), resolves USING bindings (Pass 2)
- `RequireModuleSetupHandler` in `features/require/`: registers require relationships (Pass 1), Pass 2 is no-op
- SourceFeature needs no setup handler (no module scopes)

**Registration:**

New method on `IFeatureRegistrationContext`: `<T extends IDependencyInfo> void dependencySetupHandler(Class<T> type, IDependencySetupHandler<T> handler)` + corresponding getter on `FeatureRegistry`.

- `ImportFeature`: `ctx.dependencySetupHandler(ImportDependencyInfo.class, new ImportModuleSetupHandler())`
- `RequireFeature`: `ctx.dependencySetupHandler(RequireDependencyInfo.class, new RequireModuleSetupHandler())`

After F3b: ModuleDescriptor is generic (no feature-specific inner types), SemanticAnalyzer.setupModuleRelationships() is pure registry dispatch (no feature knowledge).

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
8. **AST nodes and Symbol records store no Token references** — only extracted values (String, int, etc.) + SourceInfo
9. **`SourceLocatable` modernized to `ISourceLocatable`** in `model/ast/` — returns `SourceInfo` instead of `String getSourceFileName()`; all source-tracked AST nodes implement it, synthetic nodes (PushCtxNode, PopCtxNode) do not
10. **Phase 0 (DependencyScanner) has zero feature knowledge** — all dependency directive patterns and processing logic live in feature packages. Dispatch via `IDependencyScanHandler` registry.
11. **Module setup is registry-driven** — `SemanticAnalyzer.setupModuleRelationships()` dispatches via `ModuleSetupRegistry`, no feature-specific imports or instanceof checks.
12. **All tests green** after each step

## Constraints

- Each step must leave all tests green
- Each step is an independent commit
- Discussion and approval required before each step
- No behavioral changes — same compiler output before and after
