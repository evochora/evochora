# Conditional Compilation

**Status: TO BE REVIEWED**

## Problem

### 1. No mechanism for build variants

The compiler has no way to conditionally include or exclude code. Writing defensive vs. aggressive organism variants requires maintaining separate assembly files with duplicated code. A single codebase with conditional blocks (e.g., skip boundary checks in aggressive mode) is not possible.

### 2. `.DEFINE` name conflict

`.DEFINE` is the standard name for preprocessor symbol definition across assemblers (C `#define`, NASM `%define`). In Evochora, `.DEFINE` is a Phase 3 (Parser) compile-time constant — not a preprocessor directive. This is confusing for anyone coming from other assembly languages. `.DEFINE` should be freed for preprocessor use and the existing constant directive renamed to `.CONST`.

### 3. Inconsistent END-directive naming

Block-closing directives use cryptic abbreviations: `.ENDP` (proc), `.ENDM` (macro), `.ENDR` (repeat). Conditional compilation introduces `.ENDDEF`. For consistency, all END-directives should use explicit names: `.ENDPROC`, `.ENDMACRO`, `.ENDREPEAT`.

## Solution

### Syntax

```
.DEFINE <name>          ; Preprocessor symbol setzen (Flag, kein Wert)
.UNDEF <name>           ; Preprocessor symbol entfernen
.IFDEF <name>           ; Block wenn definiert
.IFNDEF <name>          ; Block wenn nicht definiert
.ELIFDEF <name>         ; Verkettetes IFDEF
.ELIFNDEF <name>        ; Verkettetes IFNDEF
.ELSEDEF                ; Alternativ-Block
.ENDDEF                 ; Block-Ende
```

### Evaluation phase

All conditional directives are evaluated in **Phase 2 (Preprocessor)**. Code in skipped branches is removed from the token stream before parsing. This means:

- `.CONST` constants (Phase 3) are NOT visible to conditionals — they don't exist yet during preprocessing.
- `.DEFINE` preprocessor symbols are a separate namespace from `.CONST` constants.
- Conditional blocks can contain syntactically incomplete code (e.g., only half a procedure).

### Scope and visibility

Preprocessor symbols are **global within a single preprocessor run**, visible from the point of definition onward (single-pass semantics):

- **`.SOURCE`**: Defines flow freely in both directions (tokens inlined, same preprocessor context).
- **`.IMPORT`**: Defines from imported module visible after the import point (tokens inlined via .IMPORT).
- **`.REQUIRE`**: Defines NOT visible — `.REQUIRE` does not inject tokens into the preprocessor. The required module's tokens are provided by the importer's `USING` clause.
- **Command-line/config defines**: Available before the first token in the stream.

A `.DEFINE` inside a skipped `.IFDEF` block is NOT executed — skipped code does not exist.

### Symbol definition sources

Preprocessor symbols can be defined from three sources, all feeding the same symbol map in `PreProcessorContext`:

1. **In assembly code**: `.DEFINE AGGRESSIVE` — available from that token onward.
2. **Via CompilerOptions**: New `Map<String, Boolean> defines` field — populated from simulation config (e.g., `compiler.defines.AGGRESSIVE=true`). Available before the first token.
3. **Via CLI `--define` flag**: `--define AGGRESSIVE` — for testing. Populates CompilerOptions.

### Macro interaction

Both macros and conditionals run in Phase 2. `.IFDEF` inside a macro body is evaluated at **expansion** time, not at definition time. The macro body is stored as a token list; conditional directives in it are processed when the tokens are injected into the stream during expansion.

A `.DEFINE` inside a macro body is executed at expansion time — a macro can set preprocessor symbols.

Unmatched conditional blocks in a macro body (`.IFDEF` without `.ENDDEF`) cause a compile error at expansion time.

### Nesting

Arbitrary nesting depth supported. In skipped branches, the preprocessor must track `.IFDEF`/`.IFNDEF`/`.ENDDEF` pairs to find the correct closing directive.

### Skip mechanism

The `PreProcessorContext` gets a generic `isProcessingSuppressed()` method. When true, the preprocessor main loop skips normal handler dispatch and only allows the conditional feature's handler to inspect tokens. This keeps the preprocessor feature-agnostic — it does not know WHY processing is suppressed.

The conditional handler manages a `skipDepth` counter internally. When a branch is false, it increments `skipDepth`, removes non-conditional tokens from the stream, and tracks nesting to find the matching `.ELSEDEF`/`.ELIFDEF`/`.ELIFNDEF`/`.ENDDEF`.

### Error handling

- `.ELSEDEF` without preceding `.IFDEF`/`.IFNDEF` → compile error
- `.ENDDEF` without preceding `.IFDEF`/`.IFNDEF` → compile error
- `.ELIFDEF`/`.ELIFNDEF` after `.ELSEDEF` → compile error
- End of file inside open conditional block → compile error
- `.DEFINE` without name → compile error
- `.UNDEF` of non-defined symbol → no error (idempotent)

### Verschachtelung example

```
.IFDEF AGGRESSIVE
  .IFDEF HAS_ENERGY_SCANNER
    ; aggressive + scanner: skip checks, use scanner
  .ELSEDEF
    ; aggressive + no scanner: skip checks, manual scan
  .ENDDEF
.ELSEDEF
  ; defensive: full boundary checks
.ENDDEF
```

---

## Implementation Steps

### Dependencies

```text
Step 1 (.CONST rename) → Step 2 (END-directive rename) → Step 3 (Conditional infra) → Step 4 (Conditional directives) → Step 5 (CLI/Config integration)
```

Steps 1 and 2 are pure renames (no behavioral change). Step 3 adds preprocessor infrastructure. Step 4 adds the actual directives. Step 5 wires command-line/config defines.

---

### Step 1: Rename `.DEFINE` → `.CONST`

Pure mechanical rename. No behavioral change. All existing tests adapted.

**Package rename**: `features/define/` → `features/const/`

**Class renames** (6 files):
- `DefineDirectiveHandler` → `ConstDirectiveHandler`
- `DefineFeature` → `ConstFeature`
- `DefineNode` → `ConstNode`
- `DefineNodeConverter` → `ConstNodeConverter`
- `DefinePostProcessHandler` → `ConstPostProcessHandler`
- `DefineAnalysisHandler` → `ConstAnalysisHandler`

**Registration**: `ctx.parserStatement(".DEFINE", ...)` → `ctx.parserStatement(".CONST", ...)`

**Compiler infrastructure** (string/reference updates):
- `Symbol.java` — JavaDoc: "A constant defined with .CONST"
- `TokenKind.java` — JavaDoc: "A constant defined with .CONST"
- `CompilerErrorCode.java` — `DEFINE_INVALID_ARGUMENT_COUNT` → `CONST_INVALID_ARGUMENT_COUNT`
- `IrGenContext.java` — comment: "Constant registry for .CONST"
- `IParserStatementHandler.java` — JavaDoc reference
- `StandardFeatures.java` — `DefineFeature` → `ConstFeature`

**Assembly files**: All `.DEFINE` → `.CONST` (18 occurrences in 7 files)

**Test files**: All references updated (~16 test files)

**Tests:**
- All existing tests remain green (pure rename)
- All 5 CLI smoke tests green

---

### Step 2: Rename END-directives

Pure mechanical rename. No behavioral change.

| Old | New | Handler file |
|---|---|---|
| `.ENDP` | `.ENDPROC` | `ProcDirectiveHandler.java` |
| `.ENDM` | `.ENDMACRO` | `MacroDirectiveHandler.java` |
| `.ENDR` | `.ENDREPEAT` | `RepeatDirectiveHandler.java` |

**Prod code**: ~19 occurrences in handler files and JavaDoc

**Assembly files**: 33 occurrences in 10 files

**Test files**: ~66 occurrences in 15 files

**Tests:**
- All existing tests remain green (pure rename)
- All 5 CLI smoke tests green

---

### Step 3: Preprocessor infrastructure for conditional compilation

Adds the suppression mechanism and symbol map. No new directives yet.

**PreProcessorContext** (`frontend/preprocessor/`):
- New field: `Map<String, Boolean> symbols` — preprocessor symbol map (name → defined).
- New method: `defineSymbol(String name)` — adds symbol to map.
- New method: `undefineSymbol(String name)` — removes symbol from map.
- New method: `isSymbolDefined(String name)` — checks if symbol exists in map (case-insensitive).
- New field: `boolean processingSuppressed` — generic suppression flag.
- New method: `isProcessingSuppressed()` — returns suppression state.
- New method: `setProcessingSuppressed(boolean suppressed)` — sets suppression state.
- Constructor: accepts initial symbols from `CompilerOptions.defines()` (pre-populated before first token).

**PreProcessor.expand()** (`frontend/preprocessor/`):
- In main loop: if `ppContext.isProcessingSuppressed()`, skip normal handler dispatch. Only attempt dispatch to the static registry (where the conditional handler is registered). If no conditional handler matches the token, remove the token from the stream.
- This is feature-agnostic — the preprocessor does not know about conditionals, only about suppression.

**CompilerOptions** (`api/`):
- New field: `Map<String, Boolean> defines` — preprocessor symbols from config/CLI.
- Default: empty map.

**Tests:**
- `PreProcessorContext`: defineSymbol/undefineSymbol/isSymbolDefined unit tests
- `PreProcessorContext`: processingSuppressed flag unit tests
- Suppression mode: non-conditional tokens removed from stream
- Build + all existing tests green

---

### Step 4: Conditional directives

New feature package `features/conditional/`.

**ConditionalFeature** — registers all handlers in StandardFeatures:
- Phase 2: `DefineHandler` for `.DEFINE`
- Phase 2: `UndefHandler` for `.UNDEF`
- Phase 2: `ConditionalBlockHandler` for `.IFDEF`, `.IFNDEF`, `.ELIFDEF`, `.ELIFNDEF`, `.ELSEDEF`, `.ENDDEF`

**DefineHandler** (`features/conditional/`):
- Consumes `.DEFINE` token + identifier token.
- Calls `preProcessorContext.defineSymbol(name)`.
- Removes directive tokens from stream.

**UndefHandler** (`features/conditional/`):
- Consumes `.UNDEF` token + identifier token.
- Calls `preProcessorContext.undefineSymbol(name)`.
- Removes directive tokens from stream.

**ConditionalBlockHandler** (`features/conditional/`):
- Manages a stack of conditional states: `Deque<ConditionalState>`.
- `ConditionalState` tracks: has any branch been taken, is current branch active, has `.ELSEDEF` been seen.
- On `.IFDEF <name>`: push new state. If symbol defined → branch active, processing normal. If not → set `processingSuppressed(true)`, begin skipping.
- On `.IFNDEF <name>`: same logic, inverted condition.
- On `.ELIFDEF <name>`: if a previous branch was taken → skip. If no branch taken and symbol defined → activate branch, unsuppress.
- On `.ELIFNDEF <name>`: same logic, inverted condition.
- On `.ELSEDEF`: if any branch was taken → skip. Otherwise → activate, unsuppress. Mark `.ELSEDEF` seen (reject further `.ELIFDEF`/`.ELIFNDEF`).
- On `.ENDDEF`: pop state. If stack empty → unsuppress.
- All conditional directive tokens are removed from the output stream.

**Nesting in skip mode**: when `skipDepth > 0` and a nested `.IFDEF`/`.IFNDEF` is encountered, increment `skipDepth`. On `.ENDDEF`, decrement. Only when `skipDepth` reaches 0 does the handler evaluate the condition.

**Tests:**
- `.DEFINE FOO` + `.IFDEF FOO` → block included
- `.IFDEF UNDEFINED` → block excluded
- `.IFNDEF UNDEFINED` → block included
- `.IFDEF FOO` + `.ELSEDEF` → correct branch taken
- `.IFDEF A` + `.ELIFDEF B` + `.ELSEDEF` → correct branch
- `.ELIFNDEF` → correct negated chaining
- Nested conditionals: inner `.IFDEF` inside outer `.IFDEF`
- Nested conditionals: inner `.IFDEF` inside skipped outer branch (nesting balance)
- `.DEFINE` inside `.IFDEF` block → only executed if branch active
- `.UNDEF FOO` + `.IFDEF FOO` → block excluded
- `.ELSEDEF` without `.IFDEF` → compile error
- `.ENDDEF` without `.IFDEF` → compile error
- `.ELIFDEF` after `.ELSEDEF` → compile error
- End of file inside open block → compile error
- `.DEFINE` without name → compile error
- `.UNDEF` of undefined symbol → no error
- Interaction with `.MACRO`: `.IFDEF` in macro body evaluated at expansion
- Interaction with `.IMPORT`: `.DEFINE` in imported module visible after import
- All 5 CLI smoke tests green

---

### Step 5: CLI and config integration

Wire `--define` flag through CLI to CompilerOptions.

**CLI** (`cli/` or command infrastructure):
- New `--define <name>` flag (repeatable).
- Populates `CompilerOptions.defines()` map.

**SimulationEngine** (`datapipeline/services/`):
- Read `compiler.defines` from simulation config.
- Pass to `CompilerOptions.defines()`.

**Compiler.java**:
- Pass `CompilerOptions.defines()` to `PreProcessorContext` constructor.

**Tests:**
- CLI: `--define AGGRESSIVE` → symbol available in preprocessor
- Config: `compiler.defines.AGGRESSIVE=true` → symbol available
- Multiple defines: `--define A --define B` → both available
- All 5 CLI smoke tests green
