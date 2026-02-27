# Compiler Package Structure Cleanup

## Problem

Several types that serve as shared data structures across the entire compiler pipeline
are located in phase-specific packages. This creates false coupling: every phase that
works with tokens appears to depend on the Lexer, every phase that walks the AST appears
to depend on the Parser, etc.

The compiler pipeline is strictly phase-separated. Each phase runs once, receives its
input from the previous phase, and produces output for the next. The orchestrator
(`Compiler.java`) connects phases. Shared data structures that flow between phases
must live in phase-independent packages.

## Violations

### 1. `Token` + `TokenType` — currently in `frontend/lexer/`

The token is the fundamental data unit of the entire pipeline. It flows from Phase 1
(Lexer) through Phase 2 (PreProcessor), Phase 3 (Parser), and is referenced by AST
nodes, the semantic analyzer, the IR generator, and the backend.

**Imported by:** PreProcessor, Parser, SemanticAnalyzer, AstPostProcessor, IrGenerator,
TokenMapGenerator, Emitter, Linker, all directive handlers, all AST node types — 50+ files.

**Fix:** Move `Token`, `TokenType`, and `TokenUtil` (if present) to a shared package,
e.g. `org.evochora.compiler.frontend.token`.

**Effort:** Purely mechanical — import path changes only. ~50+ files affected.

### 2. `AstNode` + subclasses — currently in `frontend/parser/ast/`

The AST is the shared data structure between parsing and all downstream phases. The
Parser produces it, but the SemanticAnalyzer, AstPostProcessor, TokenMapGenerator, and
IrGenerator all consume and traverse it.

**Imported by:** SemanticAnalyzer, AstPostProcessor, TokenMapGenerator, IrGenerator,
Compiler — 13+ files outside the parser package.

**Fix:** Move the AST node hierarchy to a shared package, e.g.
`org.evochora.compiler.frontend.ast`. Parser-internal types (e.g. `ParsingContext`,
`ParserDirectiveRegistry`) stay in `frontend/parser/`.

**Effort:** Moderate — need to distinguish parser-internal types from shared AST types.
~30+ files affected.

### 3. `SymbolTable` + `Symbol` + `ModuleId` — currently in `frontend/semantics/`

The symbol table is produced by the SemanticAnalyzer but consumed by the backend
(LinkingRegistry, Linker), AstPostProcessor, and TokenMapGenerator.

**Imported by:** Backend (LinkingRegistry), AstPostProcessor, TokenMapGenerator,
Compiler — 10+ files outside semantics.

**Fix:** Move shared symbol types to a shared package, e.g.
`org.evochora.compiler.frontend.symbol`. Analyzer-internal types stay in
`frontend/semantics/`.

**Effort:** Moderate — ~10+ files affected.

### 4. `ModuleDescriptor` + `DependencyGraph` — currently in `frontend/module/`

Module metadata is produced by Phase 0 (DependencyScanner) but consumed by the
SemanticAnalyzer (Phase 4) and the Compiler (orchestrator).

**Imported by:** SemanticAnalyzer, Compiler.

**Fix:** Move shared module types to a shared package, e.g.
`org.evochora.compiler.frontend.module.api`, or keep in `frontend/module/` if that
package is considered phase-independent (Phase 0 is not a processing phase but a
scanning/loading step).

**Effort:** Small — ~5 files affected. Lowest priority since `frontend/module/` could
be argued to already be a shared package rather than a phase-specific one.

### 5. Macro expansion hardcoded in `PreProcessor`

The `PreProcessor.expand()` loop has a hardcoded branch for macro expansion: when it
encounters an `IDENTIFIER` token, it checks `ppContext.getMacro()` and calls the private
method `expandMacro(MacroDefinition)` directly — bypassing the handler registry entirely.

This means the PreProcessor knows about `MacroDefinition` (imports it from the
`features.macro` package) and contains ~50 lines of macro expansion logic that should
live in a handler.

**Complication:** Macros are dynamically defined during preprocessing (via `.MACRO`
directive). They cannot be statically registered in the registry at initialization time.
A different dispatch pattern is needed — e.g. a dynamic handler that queries `ppContext`
for known macros, or the registry supports dynamic lookups.

**Fix:** Extract `expandMacro()` into a handler class. Design a pattern that allows
the expand loop to delegate dynamic token processing without hardcoded type checks.

**Effort:** Moderate — requires design decision on dynamic dispatch pattern.

### 6. `SourceDirectiveHandler` phase violations

`SourceDirectiveHandler` calls the Lexer (Phase 1) and loads files from the filesystem
(Phase 0 responsibility) from within the PreProcessor (Phase 2). All `.SOURCE` content
could be pre-loaded in Phase 0 and pre-lexed in Phase 1, then the handler would only
inject pre-prepared tokens.

**Fix:** Same pattern as `ImportSourceHandler` — pass pre-lexed tokens via a map,
handler only injects them.

**Effort:** Moderate — requires coordination between DependencyScanner, Compiler, and
the handler.

## Recommended Order

1. **Token + TokenType** — highest impact, purely mechanical, no design decisions
2. **AstNode hierarchy** — high impact, requires separating parser internals from shared types
3. **SymbolTable + Symbol + ModuleId** — moderate impact
4. **ModuleDescriptor + DependencyGraph** — low impact, may not even need moving
5. **Macro expansion in PreProcessor** — moderate, requires dynamic dispatch design
6. **SourceDirectiveHandler phase violations** — moderate, same pattern as ImportSourceHandler

## Constraints

- Each cleanup step must leave all tests green
- No behavioral changes — purely structural refactoring
- Each step is an independent commit
