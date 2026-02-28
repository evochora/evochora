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

15 features spanning 11 phases, with 7 phase-level registries:

| # | Feature | Phases Active In | Components |
|---|---------|-----------------|------------|
| 1 | **instruction** | 3, 4, 7, 8, 10, 11 | InstructionNode, InstructionAnalysisHandler, InstructionNodeConverter, + violations in Parser, TokenMapGenerator, Linker, IrGenContext |
| 2 | **label** | 3, 4, 7, 9, 11 | LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter, + violation in Parser |
| 3 | **proc** | 3, 4, 5, 7, 8, 10 | ProcedureNode, ExportNode, ProcDirectiveHandler, ProcedureSymbolCollector, ProcedureAnalysisHandler, ProcedureNodeConverter, ProcedureMarshallingRule, CallerMarshallingRule, CallBindingCaptureRule, RefValBindingCaptureRule, + violations in TokenMapGenerator, Linker, Compiler |
| 4 | **reg** | 3, 4, 6 | RegNode, RegDirectiveHandler, RegAnalysisHandler, + AstPostProcessor |
| 5 | **preg** | 3, 4 | PregNode, PregDirectiveHandler, PregAnalysisHandler, + violation in Compiler |
| 6 | **define** | 3, 4, 6, 7 | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefineNodeConverter, + violation in AstPostProcessor |
| 7 | **org** | 3, 7, 9 | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler |
| 8 | **dir** | 3, 7, 9 | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler |
| 9 | **place** | 3, 7, 9 | PlaceNode, PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler, + placement AST/IR types |
| 10 | **import** | 0, 2, 3, 4, 7 | ImportNode, ImportSourceHandler, ImportDirectiveHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter |
| 11 | **require** | 0, 3, 4, 7 | RequireNode, RequireDirectiveHandler, RequireSymbolCollector, RequireAnalysisHandler, RequireNodeConverter |
| 12 | **source** | 0, 2 | SourceDirectiveHandler, SourceLoader |
| 13 | **macro** | 2 | MacroDirectiveHandler, MacroDefinition, + hardcoded expandMacro() in PreProcessor |
| 14 | **repeat** | 2 | RepeatDirectiveHandler, CaretDirectiveHandler |
| 15 | **ctx** | 2, 3, 7, 9 | PushCtxNode, PopCtxNode, PopCtxDirectiveHandler (preprocessor), PushCtxDirectiveHandler (parser), PopCtxDirectiveHandler (parser), PushCtxNodeConverter, PopCtxNodeConverter, PushCtxLayoutHandler, PopCtxLayoutHandler |

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

**Missing registries** (feature logic hardcoded in phase):
- Phase 5 (TokenMap) — no registry, all logic hardcoded in `TokenMapGenerator`
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

`SourceLocatable` becomes obsolete — replaced by `SourceInfo` on every AST node.

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
│   │   ├── ProcedureSymbolCollector.java
│   │   ├── ProcedureAnalysisHandler.java
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
│   │   ├── RegPostProcessHandler.java  # NEW (extracted from AstPostProcessor)
│   │   └── RegFeature.java
│   ├── preg/
│   │   ├── PregNode.java
│   │   ├── PregDirectiveHandler.java
│   │   ├── PregAnalysisHandler.java
│   │   └── PregFeature.java
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
├── CompilerFeature.java                # Feature registration interface
├── FeatureRegistrationContext.java     # Registration API for features
├── Compiler.java                       # Orchestrator (thin)
├── frontend/                           # Pure phase orchestrators + registries
│   ├── lexer/                          # Phase 1: Lexer.java only
│   ├── preprocessor/                   # Phase 2: PreProcessor.java + registry
│   ├── parser/                         # Phase 3: Parser.java + ParsingContext + registry
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

Each feature has a registration class that implements `CompilerFeature`:

```java
// compiler/CompilerFeature.java
public interface CompilerFeature {
    void register(FeatureRegistrationContext ctx);
}

// compiler/FeatureRegistrationContext.java
public interface FeatureRegistrationContext {
    // Phase registration methods
    void preprocessor(String directiveName, IPreProcessorDirectiveHandler handler);
    void parser(String directiveName, IParserDirectiveHandler handler);
    <T extends AstNode> void symbolCollector(Class<T> nodeType, ISymbolCollector collector);
    <T extends AstNode> void analysisHandler(Class<T> nodeType, IAnalysisHandler handler);
    <T extends AstNode> void tokenMapContributor(Class<T> nodeType, ITokenMapContributor contributor);
    <T extends AstNode> void postProcessHandler(Class<T> nodeType, IPostProcessHandler handler);
    <T extends AstNode> void irConverter(Class<T> nodeType, IAstNodeToIrConverter<T> converter);
    void emissionRule(IEmissionRule rule);
    void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler);
    void linkingRule(ILinkingRule rule);

    // Context accessors (provided by Compiler.java)
    Map<String, List<Token>> moduleTokens();  // Pre-lexed tokens per module (for .IMPORT)
}
```

Example — the `import` feature:

```java
// compiler/features/importdir/ImportFeature.java
public class ImportFeature implements CompilerFeature {
    @Override
    public void register(FeatureRegistrationContext ctx) {
        ctx.preprocessor(".IMPORT", new ImportSourceHandler(ctx.moduleTokens()));
        ctx.parser(".IMPORT", new ImportDirectiveHandler());
        ctx.symbolCollector(ImportNode.class, new ImportSymbolCollector());
        ctx.analysisHandler(ImportNode.class, new ImportAnalysisHandler());
        ctx.irConverter(ImportNode.class, new ImportNodeConverter());
    }
}
```

Example — the `proc` feature (spans many phases):

```java
// compiler/features/proc/ProcFeature.java
public class ProcFeature implements CompilerFeature {
    @Override
    public void register(FeatureRegistrationContext ctx) {
        ctx.parser(".PROC", new ProcDirectiveHandler());
        ctx.symbolCollector(ProcedureNode.class, new ProcedureSymbolCollector());
        ctx.analysisHandler(ProcedureNode.class, new ProcedureAnalysisHandler());
        ctx.tokenMapContributor(ProcedureNode.class, new ProcedureTokenMapContributor());
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
List<CompilerFeature> features = List.of(
    new InstructionFeature(),
    new LabelFeature(),
    new ProcFeature(),
    new RegFeature(),
    new PregFeature(),
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
FeatureRegistrationContext ctx = new DefaultFeatureRegistrationContext(...);
features.forEach(f -> f.register(ctx));
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
| Parser handlers downcast ParsingContext | `ParsingContext` extended with `expression()`, `declaration()`, etc. |
| Compiler.java contains business logic | Procedure metadata extraction moves to `ProcFeature` components |
| IrGenContext instanceof chains | `AstNode.sourceInfo()` method — IrGenContext uses SourceInfo directly |
| TokenMapGenerator hardcodes features | Registry-based `ITokenMapContributor` dispatch |
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
| A1 | Move Token + TokenType → `compiler/model/token/` (currently in `compiler/model/`, needs one more move). **PARTIALLY DONE.** |
| A2 | Move core AST nodes → `compiler/model/ast/` (AstNode, InstructionNode, IdentifierNode, NumberLiteralNode, RegisterNode, TypedLiteralNode, VectorLiteralNode). Keep Token references and SourceLocatable as-is — decoupled in Phase B. |
| A3 | Move IR types → `compiler/model/ir/` (from existing `compiler/ir/`). |

After Phase A all three data format layers are in their target packages.
AST still imports Token — that's intentional, decoupled incrementally in Phase B.

### Phase B: AST-Token Decoupling

Incremental refactoring — each step is additive or changes one thing at a time.

| Step | Description |
|------|-------------|
| B1 | Add `sourceInfo()` method to AstNode (returns SourceInfo). Parser populates it when creating nodes. Purely additive — nothing else changes yet. |
| B2 | Simplify IrGenContext: use `node.sourceInfo()` for error reporting instead of instanceof chains that call `getRepresentativeToken()`. |
| B3 | Refactor AST nodes to store extracted values (String, int) + SourceInfo instead of Token references. **One node type per commit.** Parser extracts values from Tokens at creation time. Consumers updated along with each node. |
| B4 | Remove `SourceLocatable` interface (all consumers now use `sourceInfo()`). |
| B5 | Verify data format purity: `model/ast/` has zero imports from `model/token/` or `model/ir/`. |

### Phase C: Registration Infrastructure

Create the interfaces and registries needed for feature consolidation.

| Step | Description |
|------|-------------|
| C1 | Create `CompilerFeature` + `FeatureRegistrationContext` interfaces in `compiler/`. |
| C2 | Extend `ParsingContext` with `expression()`, `declaration()`, etc. — eliminate Parser downcasts in handlers. |
| C3 | Create `ITokenMapContributor` + `TokenMapContributorRegistry`, refactor `TokenMapGenerator` to dispatch through registry. |
| C4 | Create `IPostProcessHandler` + `PostProcessHandlerRegistry`, refactor `AstPostProcessor` to dispatch through registry. |
| C5 | Extract `MacroExpansionHandler` from `PreProcessor.expandMacro()`, register as handler in `PreProcessorDirectiveRegistry`. |
| C6 | Extract `CallSiteBindingRule` from `Linker`, move CALL detection into linking rule. |

### Phase D: Feature Consolidation

Move all feature components into `compiler/features/*/` packages, one feature at a time.
Each step creates the feature package, moves all components, creates the `XxxFeature`
registration class, and updates the corresponding registry initialization.

| Step | Feature | Files to Move | New Files |
|------|---------|--------------|-----------|
| D1 | repeat | RepeatDirectiveHandler, CaretDirectiveHandler | RepeatFeature.java |
| D2 | source | SourceDirectiveHandler | SourceFeature.java |
| D3 | macro | MacroDirectiveHandler, MacroDefinition, MacroExpansionHandler | MacroFeature.java |
| D4 | ctx | PushCtx/PopCtx Nodes + Parser Handlers + Preprocessor PopCtxHandler + Converters + LayoutHandlers | CtxFeature.java |
| D5 | org | OrgNode, OrgDirectiveHandler, OrgNodeConverter, OrgLayoutHandler | OrgFeature.java |
| D6 | dir | DirNode, DirDirectiveHandler, DirNodeConverter, DirLayoutHandler | DirFeature.java |
| D7 | define | DefineNode, DefineDirectiveHandler, DefineAnalysisHandler, DefinePostProcessHandler, DefineNodeConverter | DefineFeature.java |
| D8 | reg | RegNode, RegDirectiveHandler, RegAnalysisHandler, RegPostProcessHandler | RegFeature.java |
| D9 | preg | PregNode, PregDirectiveHandler, PregAnalysisHandler | PregFeature.java |
| D10 | label | LabelNode, LabelSymbolCollector, LabelAnalysisHandler, LabelNodeConverter | LabelFeature.java |
| D11 | place | PlaceNode, PlaceDirectiveHandler, PlaceNodeConverter, PlaceLayoutHandler, placement/ | PlaceFeature.java |
| D12 | require | RequireNode, RequireDirectiveHandler, RequireSymbolCollector, RequireAnalysisHandler, RequireNodeConverter | RequireFeature.java |
| D13 | importdir | ImportNode, ImportSourceHandler, ImportDirectiveHandler, ImportSymbolCollector, ImportAnalysisHandler, ImportNodeConverter | ImportFeature.java |
| D14 | proc | ProcedureNode, ExportNode, ProcDirectiveHandler, ProcedureSymbolCollector, ProcedureAnalysisHandler, ProcedureNodeConverter, ProcedureMarshallingRule, CallerMarshallingRule, CallBindingCaptureRule, RefValBindingCaptureRule, CallSiteBindingRule, ProcedureTokenMapContributor | ProcFeature.java |
| D15 | instruction | InstructionAnalysisHandler, InstructionNodeConverter, InstructionTokenMapContributor | InstructionFeature.java |

Order rationale: start with simple features (few phases), end with complex ones
(proc spans 6+ phases). Each step is independently committable with all tests green.

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
| F2 | Extract procedure metadata logic from Compiler.java into ProcFeature. |
| F3 | Introduce MachineConstraints, remove runtime Config imports from features. |

---

## Verification Criteria

After ALL steps are complete:

1. **Every feature is one package** under `compiler/features/`
2. **Every feature has a registration class** implementing `CompilerFeature`
3. **Phase classes contain zero feature-specific imports** — only handler interfaces from their own phase package
4. **No cross-feature imports** — features are independent
5. **Compiler.java is thin** — only phase orchestration and feature discovery
6. **All phase registries are populated via FeatureRegistrationContext** — no `initializeWithDefaults()`
7. **Data format layer purity**: `model/token/` has zero imports from `model/ast/` or `model/ir/`; `model/ast/` has zero imports from `model/token/` or `model/ir/`; `model/ir/` has zero imports from `model/token/` or `model/ast/`. The only shared type across layers is `SourceInfo`.
8. **AST nodes store no Token references** — only extracted values (String, int, etc.) + SourceInfo
9. **`SourceLocatable` is deleted** — replaced by `SourceInfo` on every AST node
10. **All tests green** after each step

## Constraints

- Each step must leave all tests green
- Each step is an independent commit
- Discussion and approval required before each step
- No behavioral changes — same compiler output before and after
