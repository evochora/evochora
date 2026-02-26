# Module System Proposal

This document specifies the new module system for the Evochora assembly compiler. It replaces the old `.INCLUDE`, `.REQUIRE`, and `.SCOPE` directives with a modern, namespace-aware system that supports local and HTTP-based code loading.

## Design Principles

- **One file = one module.** The file path or URL is the module's identity (no `.MODULE` declaration needed).
- **Private by default.** All symbols are module-internal unless explicitly exported.
- **Explicit dependencies.** Modules declare what they need and what they provide.
- **No circular dependencies.** The compiler rejects dependency cycles.
- **Each import is a copy.** Every `.IMPORT` places a separate copy of the module's code and data on the grid. Multiple imports of the same file with different aliases create independent instances with their own state.

## Directives

### `.IMPORT` — Load and namespace a module

Loads a module from a local file or HTTP URL, places its code on the grid, and makes its exported symbols available under a local alias.

```assembly
# Local file (relative to current file)
.IMPORT "./lib/energy.evo" AS E

# HTTP URL
.IMPORT "https://raw.githubusercontent.com/evochora/stdlib/main/nav.evo" AS NAV
```

Symbols are accessed via qualified names:

```assembly
CALL E.HARVEST
CALL NAV.MOVE_NORTH
```

Multiple imports of the same file create independent copies (instances):

```assembly
.IMPORT "./energy.evo" AS HARVESTER_A
.IMPORT "./energy.evo" AS HARVESTER_B

CALL HARVESTER_A.HARVEST
CALL HARVESTER_B.HARVEST
```

Alias names must be unique within a file. Duplicate aliases are a compiler error.

### `.REQUIRE` — Declare an unsatisfied dependency

Declares that the current module needs access to another module, but does not place it. The dependency must be satisfied by the importing module via `USING`.

```assembly
# energy.evo
.REQUIRE "./utils.evo" AS UTILS

.PROC HARVEST EXPORT
  CALL UTILS.HELPER
.ENDP
```

If a `.REQUIRE` dependency is not satisfied via `USING`, the compiler reports an error.

### `USING` — Inject a dependency into an imported module

When importing a module that has `.REQUIRE` dependencies, the importing module satisfies them with `USING` clauses on the `.IMPORT` directive.

```assembly
# main.evo
.IMPORT "./utils.evo" AS U
.IMPORT "./energy.evo" AS E USING U AS UTILS
```

This reads: "Import energy.evo as E, and satisfy its UTILS requirement with my U instance."

Multiple dependencies are satisfied by repeating `USING`:

```assembly
.IMPORT "./utils.evo" AS U
.IMPORT "./math.evo" AS M
.IMPORT "./energy.evo" AS E USING U AS UTILS USING M AS MATH
```

The compiler validates:
- Every `.REQUIRE` in the imported module is satisfied by a `USING` clause.
- The `USING` source (e.g., `U`) must reference the same file that the `.REQUIRE` declares. The source can be either an `.IMPORT` in the current file or an alias that was itself received via `.REQUIRE` (and satisfied by an outer `USING`).
- No unsatisfied requirements remain.

### `.SOURCE` — Textual code injection

Injects the content of another file into the current file at the preprocessing stage. This is a low-level mechanism for sharing macros and `.DEFINE` constants across files.

```assembly
.SOURCE "./macros.evo"
```

Key properties:
- Supports both local files and HTTP URLs.
- Injected content becomes part of the current file — no namespace, no export.
- Macros defined in the sourced file are available in the current file after the `.SOURCE` directive.
- Macros are always file-local: they are expanded during preprocessing and are not visible to modules that import this file.
- `.SOURCE` is processed in the preprocessor phase (Phase 2), before parsing.
- Replaces the old `.INCLUDE` directive.

Restrictions:
- `.SOURCE` files **must not** contain `.IMPORT` or `.REQUIRE` directives. The compiler reports an error if they do. `.SOURCE` is for macros and constants only — module dependencies belong in the file that uses the macros, not in the macro file itself.
- `.SOURCE` files may contain other `.SOURCE` directives (transitive sourcing is allowed).
- If a macro from a `.SOURCE`d file expands to code that references a qualified name (e.g., `CALL UTILS.HELPER`), the file that uses the macro is responsible for declaring the corresponding `.IMPORT` or `.REQUIRE`. Macros are pure text substitution — they produce tokens that are resolved in the context of the using file, not the defining file.

### `EXPORT` — Make a symbol public

The `EXPORT` modifier on a symbol declaration makes it visible to modules that import this file. Without `EXPORT`, symbols are private.

Exportable symbols:

| Symbol Type | Syntax | Example |
|---|---|---|
| Procedure | `.PROC name EXPORT ...` | `.PROC HARVEST EXPORT` |
| Top-level label | `name: EXPORT` | `HARVEST_STATE: EXPORT` |
| Constant | `.DEFINE name value EXPORT` | `.DEFINE MAX_ENERGY DATA:9999 EXPORT` |

Non-exportable symbols:
- Labels inside procedures (always private to the procedure).
- Macros (always local to the file, expanded at preprocessing).

## Visibility Rules

1. **Within a module:** All symbols (labels, procs, defines) are visible everywhere in the same file.
2. **Across modules:** Only exported symbols are visible, and only via qualified names (`ALIAS.SYMBOL`).
3. **Transitive dependencies are invisible:** If module A imports module B, and B requires module C, A cannot see C's symbols — only B's exported symbols.
4. **Macros are file-local:** Macros from `.SOURCE`d files are available in the sourcing file but not in modules that import it.

## Module Resolution and Loading

### Dependency Scan (before compilation)

Before the compilation pipeline starts, the compiler performs a lightweight dependency scan:

1. Read the main file and find all `.IMPORT`, `.REQUIRE`, and `.SOURCE` directives.
2. For each `.SOURCE`, load the file and scan it for further `.SOURCE` directives (recursively). Verify that `.SOURCE` files contain no `.IMPORT` or `.REQUIRE` — report an error if they do.
3. For each `.IMPORT`, load the referenced file (local path resolution or HTTP fetch).
4. Recursively scan imported files for their own `.IMPORT`, `.REQUIRE`, and `.SOURCE` directives (applying the same rules).
5. Build the full dependency graph (`.IMPORT` and `.REQUIRE` edges only — `.SOURCE` files are not part of the module graph).
6. Detect and report circular dependencies.
7. Topologically sort the modules.

### Path Resolution

- **Local paths:** Resolved relative to the directory of the file containing the directive.
- **HTTP URLs:** Fetched as-is. Relative paths within HTTP-loaded files are resolved relative to the base URL.
- **No caching:** Files are fetched fresh on every compilation. The compiler runs once at simulation start; caching is unnecessary.

### Compilation Pipeline

After the dependency scan, all files are known. Each phase processes all modules before the next phase starts:

```
Phase 0: Dependency Scan → load all files, build graph, topological sort
Phase 1: Lexer           → lex each module in dependency order
Phase 2: Preprocessor    → expand .SOURCE and macros per module
Phase 3: Parser          → parse each module into its own AST
Phase 4: Semantic Analysis → build module-aware SymbolTable across all modules
Phase 5+: IR, Layout, Linking, Emission → all modules merged into one pipeline
```

Each phase remains strictly separated. No phase invokes an earlier phase.

## Symbol Table Architecture

The symbol table is redesigned to be module-aware, following modern compiler best practices.

### Symbol

Each symbol has a unique, immutable identifier:

```
Symbol {
    id: SymbolId              // e.g., "energy.evo::HARVEST" — deterministic, unique
    name: String              // unqualified name ("HARVEST")
    module: ModuleId          // owning module
    type: SymbolType          // PROCEDURE | LABEL | DEFINE | STATE
    exported: boolean
    source: SourceInfo        // file, line, column
}
```

### SymbolTable

```
SymbolTable {
    modules: Map<ModuleId, ModuleScope>   // primary: module-based lookup
    byId: Map<SymbolId, Symbol>           // secondary: direct access by ID
}

ModuleScope {
    moduleId: ModuleId
    sourcePath: String                    // file path or URL
    symbols: Map<String, Symbol>          // unqualified name → Symbol
    imports: Map<String, ModuleId>        // alias → imported module
    requires: Map<String, String>         // alias → required path/URL
}
```

### Resolution

- **Unqualified name** (e.g., `HARVEST`): search current module scope only.
- **Qualified name** (e.g., `E.HARVEST`): look up alias `E` in current module's imports → find target module → look up `HARVEST` in that module → verify it is exported.

### Impact on ProgramArtifact

The SymbolId is compiler-internal and does not flow into the ProgramArtifact. The existing `TokenInfo` record remains unchanged:

```java
record TokenInfo(String tokenText, Symbol.Type tokenType, String scope)
```

The compiler ensures that:
- `labelNameToValue` contains entries for both qualified names (`"E.HARVEST"`) and the hash resolution works correctly.
- `tokenText` in the TokenMap contains the source text as written (e.g., `"E.HARVEST"`).
- The visualizer's source view and annotation system continues to work without frontend changes.

## Removed Directives

The following directives are removed and replaced by the new system:

| Removed | Replaced by |
|---|---|
| `.INCLUDE "path"` | `.SOURCE "path"` (for macros/defines) or `.IMPORT "path" AS ALIAS` (for modules) |
| `.REQUIRE "path" AS ALIAS` | `.IMPORT "path" AS ALIAS` (with placement) or `.REQUIRE "path" AS ALIAS` (without placement, new semantics) |
| `.SCOPE name / .ENDS` | Module-level namespacing (implicit) |

## Example: Complete Modular Program

**`macros.evo`** — Shared macro definitions:
```assembly
.MACRO NOP_PAD N
  NOP^N
.ENDM
```

**`utils.evo`** — Utility procedures:
```assembly
.PROC HELPER EXPORT
  NOP
  RET
.ENDP
```

**`energy.evo`** — Energy harvesting module with state and a dependency:
```assembly
.SOURCE "./macros.evo"
.REQUIRE "./utils.evo" AS UTILS

.STATE HARVEST_STATE
  FWD_MASK DATA:1
  KIDX DATA:0
  KLEFT DATA:89
.ENDS

.PROC HARVEST EXPORT
  .LOAD HARVEST_STATE %PR0 %PR1 %PR2

  CALL UTILS.HELPER
  NOP_PAD 4

  .STORE HARVEST_STATE %PR0 %PR1 %PR2
  RET
.ENDP
```

**`main.evo`** — Main program:
```assembly
.SOURCE "./macros.evo"
.IMPORT "./utils.evo" AS U
.IMPORT "./energy.evo" AS E USING U AS UTILS

START:
  NOP_PAD 4
  CALL E.HARVEST
  JMPI START
```
