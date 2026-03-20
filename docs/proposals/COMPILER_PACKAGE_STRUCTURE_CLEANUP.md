# Compiler Architecture Cleanup

## Vision

A compiler where every component has exactly one responsibility, every dependency
points in the right direction, and every phase is a pure orchestrator that delegates
all feature-specific logic to handlers via registries. A compiler architect should be
able to look at the package tree and immediately understand the architecture without
reading a single line of code.

## Architectural Principles

1. **Phase N must NEVER call Phase M** — no cross-phase calls
2. **Each phase has its own handler/registry system** — no shared registries
3. **Handlers are independent** — no handler calls another handler
4. **Phases are dumb orchestrators** — they dispatch to handlers, contain zero feature knowledge
5. **Shared data flows through phase-independent packages** — no shared type lives in a phase package
6. **The orchestrator (Compiler.java) only connects phases** — no business logic
7. **No instanceof in phases** — use polymorphism and interfaces
8. **No magic strings** — opcodes and directives referenced through constants

---

## Target Package Structure

```
org.evochora.compiler
├── model/                              # Shared data structures (compiler-internal)
│   ├── Token.java                      # From: frontend/lexer/
│   ├── TokenType.java                  # From: frontend/lexer/
│   ├── ast/                            # Full AST node hierarchy
│   │   ├── AstNode.java               # From: frontend/parser/ast/
│   │   ├── SourceLocatable.java        # From: frontend/parser/ast/
│   │   ├── InstructionNode.java        # From: frontend/parser/ast/
│   │   ├── IdentifierNode.java         # From: frontend/parser/ast/
│   │   ├── NumberLiteralNode.java      # From: frontend/parser/ast/
│   │   ├── RegisterNode.java           # From: frontend/parser/ast/
│   │   ├── TypedLiteralNode.java       # From: frontend/parser/ast/
│   │   ├── VectorLiteralNode.java      # From: frontend/parser/ast/
│   │   ├── PushCtxNode.java            # From: frontend/parser/ast/
│   │   ├── PopCtxNode.java             # From: frontend/parser/ast/
│   │   ├── PregNode.java              # From: frontend/parser/ast/
│   │   ├── ProcedureNode.java          # From: frontend/parser/features/proc/
│   │   ├── ExportNode.java             # From: frontend/parser/features/proc/
│   │   ├── DefineNode.java             # From: frontend/parser/features/def/
│   │   ├── LabelNode.java              # From: frontend/parser/features/label/
│   │   ├── ImportNode.java             # From: frontend/parser/features/importdir/
│   │   ├── RequireNode.java            # From: frontend/parser/features/require/
│   │   ├── OrgNode.java                # From: frontend/parser/features/org/
│   │   ├── DirNode.java                # From: frontend/parser/features/dir/
│   │   ├── PlaceNode.java              # From: frontend/parser/features/place/
│   │   └── RegNode.java                # From: frontend/parser/features/reg/
│   ├── symbol/                         # Symbol table types
│   │   ├── SymbolTable.java            # From: frontend/semantics/
│   │   ├── Symbol.java                 # From: frontend/semantics/
│   │   └── ModuleId.java               # From: frontend/semantics/
│   └── module/                         # Module metadata types
│       ├── ModuleDescriptor.java       # From: frontend/module/
│       └── DependencyGraph.java        # From: frontend/module/
├── util/                               # Infrastructure utilities
│   └── SourceLoader.java               # From: frontend/io/
├── api/                                # Public API (unchanged)
├── internal/                           # API serialization helpers (unchanged)
├── Compiler.java                       # Orchestrator (thin, no business logic)
└── frontend/                           # Phase-specific logic ONLY
    ├── lexer/                          # Phase 1: Lexer.java only
    ├── preprocessor/                   # Phase 2: PreProcessor.java + handlers
    │   └── features/
    │       ├── source/                 # SourceDirectiveHandler (token injection only)
    │       ├── macro/                  # MacroDirectiveHandler + MacroExpansionHandler
    │       ├── repeat/                 # RepeatDirectiveHandler + CaretDirectiveHandler
    │       └── importdir/              # ImportSourceHandler (token injection only)
    ├── parser/                         # Phase 3: Parser.java + ParsingContext + handlers
    │   └── features/                   # Handlers only — NO AST nodes here
    ├── semantics/                      # Phase 4: SemanticAnalyzer.java + handlers
    │   └── analysis/                   # Handlers only — NO Symbol/SymbolTable here
    ├── tokenmap/                       # Phase 5: TokenMapGenerator.java (+ registry if needed)
    ├── postprocess/                    # Phase 6: AstPostProcessor.java
    ├── irgen/                          # Phase 7: IrGenerator.java + converters
    └── module/                         # Phase 0: DependencyScanner.java only
```

**Key decisions:**
- `compiler/model/` for ALL internal shared data structures — not `compiler/api/`
  (external-facing) and not `frontend/model/` (suggests frontend-only scope).
- `compiler/util/` for `SourceLoader` — `compiler/io/` rejected as too specific.
- `compiler/internal/` stays as-is (API serialization helpers).
- `frontend/module/` becomes pure Phase 0 (only `DependencyScanner`).
- `frontend/io/` deleted after moving `SourceLoader`.
- ALL AST node types (including feature nodes like `ProcedureNode`, `LabelNode`, etc.)
  move to `compiler/model/ast/` — they are shared data structures, not handler logic.
- `frontend/parser/features/*/` retains only handler classes.

---

## Violations and Fixes

### V1: Shared types in phase-specific packages

**Severity: HIGH — Structural foundation, blocks all other improvements**

Shared data structures live inside phase packages, creating false coupling between
phases. Every phase that uses a `Token` appears to depend on the Lexer.

| Type(s) | Current Package | Used By |
|---------|----------------|---------|
| `Token`, `TokenType` | `frontend/lexer/` | 50+ files across all phases |
| `AstNode` + 10 node types | `frontend/parser/ast/` | Semantics, IrGen, PostProcess, TokenMap, Compiler |
| `ProcedureNode`, `LabelNode`, `DefineNode`, `ImportNode`, `RequireNode`, `OrgNode`, `DirNode`, `PlaceNode`, `RegNode`, `ExportNode` | `frontend/parser/features/*/` | Semantics, IrGen, PostProcess, TokenMap, Compiler |
| `SymbolTable`, `Symbol`, `ModuleId` | `frontend/semantics/` | Backend (LinkingRegistry, LabelRefLinkingRule), PostProcess, TokenMap |
| `ModuleDescriptor`, `DependencyGraph` | `frontend/module/` | SemanticAnalyzer, Compiler |
| `SourceLoader` | `frontend/io/` | DependencyScanner, SourceDirectiveHandler |

**Fix:** Move all shared types to `compiler/model/` and `compiler/util/` as described
in Target Package Structure above. This is purely mechanical — no behavioral changes,
only import path updates.

**Steps:**
1. Move `Token` + `TokenType` → `compiler/model/` (~50+ files)
2. Move all AST nodes → `compiler/model/ast/` (~30+ files, includes merging `parser/ast/` and `parser/features/*/ *Node.java`)
3. Move `SymbolTable` + `Symbol` + `ModuleId` → `compiler/model/symbol/` (~10+ files)
4. Move `ModuleDescriptor` + `DependencyGraph` → `compiler/model/module/` (~5 files)
5. Move `SourceLoader` → `compiler/util/`, delete `frontend/io/` (~3 files)

Each step is an independent commit with all tests green.

---

### V2: SourceDirectiveHandler calls Lexer and loads files (Phase 2 → Phase 1 + Phase 0)

**Severity: HIGH — Active phase separation violation**

`SourceDirectiveHandler.process()` instantiates a `Lexer` (Phase 1) and calls
`SourceLoader` to load files from disk (Phase 0 responsibility) from within the
PreProcessor (Phase 2). This is the most severe active violation — not just a
misplaced type, but actual cross-phase execution.

**Current code** (`SourceDirectiveHandler.java:52-53`):
```java
Lexer lexer = new Lexer(content, preProcessor.getDiagnostics(), logicalName);
List<Token> newTokens = lexer.scanTokens();
```

**Fix:** Apply the same pattern already used by `ImportSourceHandler`:
1. Phase 0 (DependencyScanner) resolves ALL `.SOURCE` dependencies transitively
2. Phase 1 (Compiler/Lexer loop) pre-lexes ALL source files into a `Map<String, List<Token>>`
3. `SourceDirectiveHandler` receives the pre-lexed token map and only injects tokens

This requires DependencyScanner to also scan `.SOURCE` directives (not just `.IMPORT`
and `.REQUIRE`), which is a natural extension since it already handles file resolution.

**Consequence:** SourceDirectiveHandler becomes a pure token injection handler — no
filesystem access, no Lexer instantiation. The handler signature changes to accept
`Map<String, List<Token>> sourceTokens` via constructor injection, same as
`ImportSourceHandler`.

---

### V3: Macro expansion hardcoded in PreProcessor

**Severity: MEDIUM — Phase contains feature-specific logic**

`PreProcessor.expand()` has a hardcoded `IDENTIFIER` branch that checks
`ppContext.getMacro()` and calls the private method `expandMacro(MacroDefinition)` —
50+ lines of macro expansion logic that bypasses the handler registry entirely.

**Current code** (`PreProcessor.java:58-63`):
```java
} else if (token.type() == TokenType.IDENTIFIER) {
    Optional<MacroDefinition> macroOpt = ppContext.getMacro(token.text());
    if (macroOpt.isPresent()) {
        expandMacro(macroOpt.get());
        streamWasModified = true;
    }
}
```

**Complication:** Macros are dynamically defined during preprocessing via `.MACRO`.
They cannot be statically registered in the registry at initialization time.

**Fix:** Introduce a `MacroExpansionHandler` that is registered not for a directive
name but as a fallback handler for `IDENTIFIER` tokens. The dispatch pattern in
`expand()` becomes:

```java
if (token.type() == TokenType.DIRECTIVE) {
    handler = directiveRegistry.get(token.text());
} else {
    handler = directiveRegistry.getFallback(token.type());
}
```

The `MacroExpansionHandler` receives the `PreProcessorContext` (which holds the macro
table) and contains all expansion logic. The `expand()` loop becomes fully generic —
it dispatches ALL token processing through the registry and contains zero feature
knowledge.

`expandMacro()` and the `MacroDefinition` import are removed from `PreProcessor`.

---

### V4: Parser handlers downcast ParsingContext to Parser

**Severity: MEDIUM — Systematic interface bypass**

Seven parser directive handlers downcast the `ParsingContext` interface to the concrete
`Parser` class to access methods not in the interface:

| Handler | Methods called via downcast |
|---------|---------------------------|
| `ProcDirectiveHandler` | `expression()`, `declaration()`, `pushRegisterAliasScope()`, `popRegisterAliasScope()`, `registerProcedure()` |
| `DefineDirectiveHandler` | `expression()` |
| `OrgDirectiveHandler` | `expression()` |
| `DirDirectiveHandler` | `expression()` |
| `PlaceDirectiveHandler` | `expression()` |
| `PregDirectiveHandler` | `addRegisterAlias()` |
| `RegDirectiveHandler` | `addRegisterAlias()` |

**Example** (`ProcDirectiveHandler.java:27`):
```java
Parser parser = (Parser) context;
```

**Fix:** Extend `ParsingContext` to include the methods that handlers legitimately need:

```java
public interface ParsingContext {
    // Existing token stream methods...
    boolean match(TokenType... types);
    boolean check(TokenType type);
    Token advance();
    Token peek();
    Token previous();
    Token consume(TokenType type, String errorMessage);
    DiagnosticsEngine getDiagnostics();
    boolean isAtEnd();

    // Expression/statement parsing (needed by directive handlers)
    AstNode expression();
    AstNode declaration();

    // Register alias management (needed by .REG and .PREG handlers)
    void addRegisterAlias(String alias, String register);
    void pushRegisterAliasScope();
    void popRegisterAliasScope();

    // Procedure registration (needed by .PROC handler)
    void registerProcedure(ProcedureNode procNode);
}
```

All seven handlers then use the interface contract only — zero downcasts remain.

---

### V5: Compiler.java contains business logic

**Severity: MEDIUM — Orchestrator is not thin**

`Compiler.java` contains two blocks of non-trivial feature logic that belong in
dedicated phases or handlers:

**a) Procedure parameter extraction** (lines 152-178):
Iterates `parser.getProcedureTable()`, manually extracts REF/VAL/WITH parameters,
constructs `ParamInfo` objects. This is parser-phase metadata extraction inside the
orchestrator.

**b) Register alias resolution** (lines 196-226):
Calls `extractProcedureRegisterAliases()` which walks the AST with `instanceof PregNode`
checks, then manually converts register name strings (`%PR0`, `%FPR0`, `%DR0`) to
register IDs using runtime constants (`Instruction.FPR_BASE`, `Instruction.PR_BASE`).

**Fix:** Extract both into a dedicated post-processing step or integrate into existing
phases:
- Parameter extraction → Part of semantic analysis or a new `MetadataCollector` that
  runs after parsing. The `procNameToParamNames` map should be produced by a phase,
  not assembled ad-hoc in the orchestrator.
- Register alias resolution → Part of `AstPostProcessor` (Phase 6), which already
  receives `astRegisterAliases`. The register ID conversion (string → int) should be
  encapsulated there, not scattered in the orchestrator.

After this fix, `Compiler.java` contains only phase invocations and data passing.

---

### V6: IrGenContext hardcodes instanceof chains over AST nodes

**Severity: MEDIUM — Feature knowledge in infrastructure**

`IrGenContext.getRepresentativeToken()` enumerates 7 concrete AST node types via
`instanceof` to extract a representative `Token` for source location tracking.

**Current code** (`IrGenContext.java:95-103`):
```java
private Token getRepresentativeToken(AstNode node) {
    if (node instanceof LabelNode n) return n.labelToken();
    if (node instanceof RegisterNode n) return n.registerToken();
    if (node instanceof NumberLiteralNode n) return n.numberToken();
    if (node instanceof TypedLiteralNode n) return n.type();
    if (node instanceof IdentifierNode n) return n.identifierToken();
    if (node instanceof ProcedureNode n) return n.name();
    if (node instanceof VectorLiteralNode n && !n.components().isEmpty()) return n.components().get(0);
    return null;
}
```

**Fix:** The `SourceLocatable` interface already exists but only provides
`getSourceFileName()`. Add a `getRepresentativeToken()` method to `AstNode`:

```java
public interface AstNode {
    default List<AstNode> getChildren() { return Collections.emptyList(); }
    default AstNode reconstructWithChildren(List<AstNode> newChildren) { return this; }
    default Token getRepresentativeToken() { return null; }  // NEW
}
```

Each concrete AST node overrides this to return its primary token. `IrGenContext`
collapses to:

```java
public SourceInfo sourceOf(AstNode node) {
    Token token = node.getRepresentativeToken();
    if (token != null) return new SourceInfo(token.fileName(), token.line(), token.column());
    return new SourceInfo("unknown", -1, -1);
}
```

All `instanceof` checks and all concrete AST type imports are eliminated from
`IrGenContext`.

---

### V7: TokenMapGenerator hardcodes feature knowledge

**Severity: MEDIUM — Phase contains feature-specific logic and instanceof chains**

`TokenMapGenerator.visit()` (lines 144-219) uses `instanceof` chains to handle
`ProcedureNode`, `IdentifierNode`, `RegisterNode`, and `InstructionNode` with
feature-specific logic. It also hardcodes `"CALL"` and `"RET"` opcode names as
string literals.

**Fix:** Apply the same converter/registry pattern used by `IrGenerator`:
- Define `ITokenMapContributor` interface with `void contribute(AstNode, TokenMapContext)`
- Create `TokenMapContributorRegistry` that maps AST node classes to contributors
- Each contributor handles one node type (e.g., `ProcedureTokenContributor`,
  `InstructionTokenContributor`)
- `TokenMapGenerator` becomes a pure orchestrator: walks the AST, dispatches to
  registry, contains zero feature knowledge

---

### V8: LabelRefLinkingRule imports SymbolTable from frontend (Backend → Frontend)

**Severity: HIGH — Cross-layer dependency**

`LabelRefLinkingRule` (backend Phase 10) imports `SymbolTable` from `frontend/semantics/`
and even constructs `Token` objects inline to call `symbolTable.resolve()`:

```java
var symbolOpt = symbolTable.resolve(new org.evochora.compiler.frontend.lexer.Token(
    null, labelNameToFind, null, instruction.source().lineNumber(), 0, instruction.source().fileName()
));
```

**Fix:** The backend must not reach into frontend infrastructure. Two options:

**Option A (minimal):** After V1 is done, `SymbolTable` lives in `compiler/model/symbol/`
which is phase-independent. The cross-layer dependency is structurally resolved by the
package move. However, the backend still depends on the full `SymbolTable` API when it
only needs qualified name resolution.

**Option B (clean):** The semantic analyzer produces a `Map<String, String>` of
qualified-name resolutions (e.g., `"ENERGY.HARVEST" → "HARVEST"`) during Phase 4.
This map is passed to the linker via the orchestrator. `LabelRefLinkingRule` receives
only the pre-resolved map, not the full `SymbolTable`. This eliminates the dependency
entirely and prevents the backend from constructing `Token` objects.

**Recommended:** Option B — keeps the backend truly independent of frontend semantics.

---

### V9: Linker hardcodes "CALL" opcode detection

**Severity: LOW — Feature knowledge in phase**

`Linker.link()` (line 44) checks `"CALL".equalsIgnoreCase(ins.opcode())` to trigger
call-site binding resolution. The linker is supposed to be a dumb orchestrator that
delegates all instruction-specific logic to `ILinkingRule` implementations.

**Fix:** Move the `CALL` binding logic into a dedicated `CallSiteBindingRule` registered
in `LinkingRegistry`. The `Linker.link()` loop becomes:

```java
for (IrItem item : program.items()) {
    if (item instanceof IrInstruction ins) {
        for (ILinkingRule rule : registry.rules()) {
            ins = rule.apply(ins, context, layout);
        }
        out.add(ins);
        context.nextAddress();
        // ... address counting stays (it's generic infrastructure, not feature logic)
    }
}
```

---

### V10: Magic opcode strings across emission and linking rules

**Severity: LOW — Fragile, but not a structural violation**

Opcode names like `"CALL"`, `"RET"`, `"PUSH"`, `"POP"`, `"PUSI"`, `"PUSV"`, `"JMPI"`
appear as raw string literals in `CallerMarshallingRule`, `ProcedureMarshallingRule`,
`LabelRefLinkingRule`, `Linker`, and `TokenMapGenerator`.

**Fix:** Introduce an `Opcodes` constants class in `compiler/model/` (or `compiler/isa/`):

```java
public final class Opcodes {
    public static final String CALL = "CALL";
    public static final String RET  = "RET";
    public static final String PUSH = "PUSH";
    public static final String POP  = "POP";
    public static final String JMPI = "JMPI";
    // ... etc
    private Opcodes() {}
}
```

All magic strings are replaced with constant references. Changes are caught at compile
time when opcodes are renamed.

---

### V11: Frontend handlers import runtime Config directly

**Severity: LOW — Not a phase violation, but a layer violation**

`PregDirectiveHandler`, `RegAnalysisHandler`, and `InstructionAnalysisHandler` import
`org.evochora.runtime.Config` to access constants like `Config.NUM_PROC_REGISTERS`.

**Fix:** Introduce a compiler-side `CompilerConfig` or `MachineConstraints` class in
`compiler/model/` that mirrors the relevant runtime constants. The compiler should
validate against its own configuration, not reach into runtime classes. Alternatively,
if these are truly universal constants (not deployment-specific), they can live in
`compiler/isa/` alongside `RuntimeInstructionSetAdapter`.

This is lowest priority — the coupling is read-only and the constants rarely change.

---

## Implementation Order

Each step produces a green build and is committed independently.

### Phase A: Structural Foundation (mechanical, no behavioral changes)

| Step | Violation | Description | Effort |
|------|-----------|-------------|--------|
| A1 | V1 | Move `Token` + `TokenType` → `compiler/model/` | ~50+ files |
| A2 | V1 | Move all AST nodes → `compiler/model/ast/` (merge `parser/ast/` + `parser/features/*/ *Node.java`) | ~30+ files |
| A3 | V1 | Move `SymbolTable` + `Symbol` + `ModuleId` → `compiler/model/symbol/` | ~10+ files |
| A4 | V1 | Move `ModuleDescriptor` + `DependencyGraph` → `compiler/model/module/` | ~5 files |
| A5 | V1 | Move `SourceLoader` → `compiler/util/`, delete `frontend/io/` | ~3 files |
| A6 | V10 | Introduce `Opcodes` constants class, replace all magic strings | ~10 files |

### Phase B: Interface and Contract Fixes

| Step | Violation | Description | Effort |
|------|-----------|-------------|--------|
| B1 | V4 | Extend `ParsingContext` interface, eliminate all Parser downcasts | ~8 files |
| B2 | V6 | Add `getRepresentativeToken()` to `AstNode`, simplify `IrGenContext` | ~12 files |
| B3 | V8 | Pre-resolve qualified names in SemanticAnalyzer, pass map to Linker instead of SymbolTable | ~5 files |

### Phase C: Phase Purity (behavioral changes)

| Step | Violation | Description | Effort |
|------|-----------|-------------|--------|
| C1 | V2 | Refactor `SourceDirectiveHandler` to inject pre-lexed tokens (same pattern as `ImportSourceHandler`) | ~6 files |
| C2 | V3 | Extract macro expansion into `MacroExpansionHandler`, add fallback dispatch to registry | ~4 files |
| C3 | V5 | Extract business logic from `Compiler.java` into phases | ~3 files |
| C4 | V9 | Move CALL binding logic from `Linker` into `CallSiteBindingRule` | ~3 files |
| C5 | V7 | Introduce `TokenMapContributorRegistry`, make TokenMapGenerator a pure orchestrator | ~6 files |

### Phase D: Layer Boundaries (optional, low priority)

| Step | Violation | Description | Effort |
|------|-----------|-------------|--------|
| D1 | V11 | Introduce `MachineConstraints` in `compiler/isa/`, remove runtime Config imports | ~4 files |

---

## Verification Criteria

After ALL steps are complete:

1. **Zero cross-phase imports** — no file in `frontend/X/` imports from `frontend/Y/`
   (except through `compiler/model/`)
2. **Zero instanceof in phases** — all type dispatch goes through registries or interfaces
3. **Zero feature knowledge in phases** — `PreProcessor`, `Parser`, `SemanticAnalyzer`,
   `Linker`, `TokenMapGenerator` contain only generic dispatch loops
4. **Zero business logic in Compiler.java** — only phase invocations and data passing
5. **Zero Parser downcasts** — all handler interaction through `ParsingContext` interface
6. **Zero magic opcode strings** — all referenced through `Opcodes` constants
7. **Zero `new Lexer()` outside Phase 1** — no phase re-invokes the lexer
8. **Zero filesystem access outside Phase 0** — no handler loads files
9. **All tests green** after each individual step

## Constraints

- Each step must leave all tests green
- Each step is an independent commit
- No behavioral changes in Phase A (purely mechanical)
- Discussion and approval required before each Phase B/C/D step
