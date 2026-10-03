# Compiler Core Boundary

This document records where the line between the compiler core and its features runs today,
which language concepts each feature brings, and where the core still knows more about a feature
than the rules in AGENTS.md allow. It describes the state as it is. The couplings listed here
are accepted, not sanctioned: a change may not deepen them, and each is a candidate for a
later decoupling.

The findings come from two sources, taken on 2026-09-04: an inventory of every place outside
`features/` that names a feature, a directive or a language construct, and a probe that compiled
the reference program under `src/test/resources/org/evochora/compiler/reference` fourteen times,
each time with one feature left out of the compiler, and once with no feature at all.

## What each feature brings to the language

The core owns no directive and no statement. Every construct a programmer writes comes from a
feature package under `src/main/java/org/evochora/compiler/features/`.

| Feature | Language concepts | Where it plugs in |
|---|---|---|
| `instruction` | Instruction statements, `OPCODE operand …`, the default statement form; operand type checking against the instruction set; instruction tokens in the token map | parser default, analysis, IR conversion, token map |
| `label` | Labels `NAME:` (rewritten by the preprocessor to `.LABEL NAME`), `EXPORT` on a label, a label as jump target or operand, label references resolved to addresses at link time | preprocessor `:`, parser `.LABEL`, symbol collection, analysis, IR conversion, linking rule |
| `proc` | `.PROC … .ENDPROC` with `REF`, `VAL`, `LREF`, `LVAL` parameters, `EXPORT` on a procedure, `CALL` with arguments, parameter marshalling around a call, call-site bindings and parameter names for the visualizer, procedure-scoped register banks | parser `.PROC` and `CALL`, symbol collection, analysis, token map, IR conversion, rewrite rules, linking rule, emission |
| `reg` | Register aliases `.REG %ALIAS %REGISTER`, alias names in the artifact | parser, analysis, IR conversion, emission |
| `constdir` | Constants `[EXPORT] .CONST NAME VALUE` | parser, analysis, IR conversion |
| `macro` | `.MACRO NAME [PARAMS] … .ENDMACRO`, macro invocation by name, file-local macro scope | preprocessor `.MACRO`, block `.MACRO`/`.ENDMACRO` (stored) |
| `repeat` | `.REPEAT n … .ENDREPEAT`, the shorthand `body^n` | preprocessor `.REPEAT` and `^`, block `.REPEAT`/`.ENDREPEAT` (stored) |
| `conditional` | Flags `.DEFINE NAME [integer]` and `.UNDEF NAME`, global to a compilation and seeded from `CompilerOptions.defines`; conditional blocks `.IFDEF`/`.IFNDEF NAME` … `.ELSEIFDEF`/`.ELSEIFNDEF` … `.ELSEDEF` … `.ENDDEF`, a condition comparing a flag's value with `=`, `<>`, `<`, `<=`, `>` or `>=`; dependencies inside conditional blocks, followed by the dependency scan | lexer symbols `=` `<>` `<` `<=` `>` `>=`, dependency scan, preprocessor `.DEFINE`, `.UNDEF`, `.IFDEF` and `.IFNDEF` (`.DEFINE` and `.UNDEF` top level only), block `.IFDEF`/`.IFNDEF` … `.ENDDEF` divided by `.ELSEIFDEF`, `.ELSEIFNDEF` and `.ELSEDEF` (in place) |
| `org` | `.ORG vector`, absolute in the main file, relative inside an imported module; a component marked `@+` or `@-` counts from the write cursor instead | parser, IR conversion, layout |
| `dir` | `.DIR vector`, the direction in which code is laid out; `.DIR @+i\|j`, a 90-degree rotation of that direction in the plane of two axes | parser, IR conversion, layout |
| `place` | `.PLACE literal placement…` with vectors, ranges, stepped ranges and wildcards; the initial world objects of the artifact | parser, IR conversion, layout |
| `importdir` | `[EXPORT] .IMPORT "path" AS ALIAS [USING x AS y]…`, qualified names `ALIAS.NAME`, dependency injection with `USING`, re-export of an import | dependency scan, preprocessor (top level only), parser, module setup, symbol collection, analysis, IR conversion |
| `require` | `.REQUIRE "path" AS ALIAS`, a dependency the importer satisfies | dependency scan, preprocessor (top level only), parser, module setup, symbol collection, analysis, IR conversion |
| `source` | `.SOURCE "path"`, textual inclusion that shares the including file's scope | dependency scan, preprocessor (top level only) |
| `ctx` | `.PUSH_CTX` and `.POP_CTX`, the module context around an inclusion; relative placement of an imported module. A programmer never writes them: `importdir` and `source` emit them around the tokens they include | preprocessor `.POP_CTX`, parser, IR conversion, layout |

## What the core does on its own

The core is the pipeline in `Compiler.java`, the twelve phases with their registries and
contexts, the three data formats under `model/`, the symbol table and the instruction-set view.
Five phases are pure dispatch and hold no language logic: the preprocessor, the IR generator,
the IR rewriter, the linker and the compiler itself.

The core does contain logic for three groups of concepts. None of them is declared a core
concept in AGENTS.md; they are listed here because they are where the coupling lives.

1. **The module system.** The dependency scanner detects cycles between files, records a
   placement of a module at every import under its alias chain, each after the placements it
   imports, and distinguishes a `.SOURCE` file from a module. The placement of a position is
   carried by the scanner, which records it, by the preprocessor, which gives the tokens of the
   main file the placement of the compilation root, and by the symbol table, which files the
   names of a module under its placement and looks a name up in the placement of the position it
   is written at; the lexer gives every token an empty placement, and the handlers of
   `importdir` and `source` set the placement of the tokens they inline. The semantic analyzer
   wires the placements together before any handler runs; the symbol table keeps the names of
   two placements of one file apart. The symbol table resolves qualified names through imports,
   requirements, `USING` bindings and the `EXPORT` flag. `ModuleScope` holds six maps, all of
   which belong to `importdir` and `require`. The parser knows the `EXPORT` keyword and asks
   each statement handler whether it accepts it. The directives are features; the system behind
   them is core.
2. **Labels.** The layout engine records label addresses, claims a cell per label and assigns
   every label a machine value of its own. The emitter encodes label cells and builds the two
   label maps of the artifact. `SourceLineIndex` formats a label reference as a jump delta for
   the visualizer. The IR generator turns every identifier that survives phase 6 into a label
   reference. The `label` feature owns only the syntax, the symbol collection and the linking
   rule.
3. **Registers and procedure parameters.** The lexer recognises and validates register tokens
   and knows that one bank is reserved for procedure parameters. The token-map generator has
   its own branches for identifiers and register aliases. `LinkingContext` has a field for
   call-site bindings, `EmissionContext` fields for procedure parameter names and register
   aliases, and `ProgramArtifact` carries all of them as named fields.

Smaller instances: the lexer emits a colon token for `:` (label); `Symbol.Type` is a closed
enum whose eight members are named after individual features, mapped exhaustively in
`TokenKindMapper`.

`CompilerOptions.defines`, the preprocessor flags of a compilation, is the one option field
that only a feature reads; `sourceRoots` is read by the core. The core upper-cases the flag
names, rejects two keys that are one name, and hands the options to the contexts of Phases 0
and 2 without looking at the flags.

A token carries two fields of provenance that the core passes on and never interprets: the
instance of injected tokens its position stands in, `SourceInfo.expansion`, 0 for the text as
written, and the positions it replaced, `Token.replaces`, one per level of substitution, each
with the instance it stands in. A feature that injects tokens sets them, with a number the
preprocessor context hands out (`nextInstance`): the `macro` feature stamps the body tokens and
the arguments it substitutes, `source` the tokens of every inclusion. For a source view the
preprocessor keeps the source files of the compilation as one entry per inclusion: the main
file, and every inclusion a feature adds through `includes`, named by its placement, file and
instance and by the position of the directive that made it; `importdir` and `source` add theirs.
An instance that is no inclusion of its own is tied through `homeOf` to the entry whose lines its
tokens stand on; `macro` does this for every expansion. The preprocessor offers `leftOut`, a
region of lines owned by a directive line, and `note`, a text at a position; each records at
exactly the position it is given, in its placement, file and instance, and equal records are
kept once. A feature that records at a token decides which of its positions count: `conditional`
notes the state of a flag at the token that names it and at every position that token replaced.
The preprocessor returns the entries with these records attached — a record goes to the entry
whose instance it carries, or to the entry its instance is tied to — together with the map of
those ties; from phase 7 on they travel, with the token map, in the IR's `DebugInfo`, which the
emitter copies into the artifact.

Any other character sequence a feature reads is not a case of the lexer but a symbol the
feature registers through `IFeatureRegistrationContext.lexerSymbol`; the lexer emits it as one
`SYMBOL` token whose text the feature's handlers compare: `*`, `..` and `,` for `place`,
`@+` and `@-` for `org` and `dir`, `^` for `repeat`. The lexer tries the registered symbols at
the start of every token, longest first, before its fixed cases, so a symbol may consist only
of the characters `! & * + , . / < = > ? @ ^ ~ -`, which cut into no identifier, number,
register, string, comment or statement end. `|` and `:` stay fixed tokens, because the parser
itself reads them in the vector and typed literals every operand may take; the single
characters `.` and `-` are rejected as symbols, being the lexer's cases for directives and
negative numbers, while longer sequences beginning with them are allowed. Brackets, `\` and
`§` are kept out of the alphabet as a reserve for the core. The alphabet is one constant in
`FeatureRegistry`; it grows by a character when a feature needs one and the core does not
claim it.

## What the probe showed

- With no feature registered, the core compiles nothing and reports `Expected instruction or
  directive` at the first statement. It does not fail internally.
- With any one feature left out, the reference program is rejected with `Unknown directive`
  at the first use of that feature's directive, and with nothing else. The core does not
  notice that a feature is missing.
- Two exceptions show where features depend on each other:
  - Without `ctx`, the program fails with a wrong message, `Circular .SOURCE detected`, far
    from any `.CTX` directive. `source` and `importdir` emit `.PUSH_CTX` and `.POP_CTX` tokens
    that only `ctx` handles; without it, an inclusion is never closed. This is a feature
    recognising another feature through a directive name on a token, which the architecture
    test cannot see.
  - Without `label`, a program with a label reference would not fail at the reference but in
    the emitter, with an internal error, because the core assumes that every surviving
    identifier is a label.

## What cannot be added without touching the core

A feature can add a directive, a statement, an AST node, an IR directive, a rewrite rule, a
linking rule, an emission contributor and a lexer symbol through `IFeatureRegistrationContext`.
A feature cannot, without a core change:

- put data of its own into `ProgramArtifact`, whose fields are fixed (issue #153);
- introduce a symbol kind, because `Symbol.Type` is closed;
- introduce an operand form or an IR item kind. Both are sealed by decision (AGENTS.md,
  "Fixed Kinds"); the open alternative is recorded under
  `docs/proposals/ideas/OPEN_COMPILER_BACKEND.md`.

## Candidates for decoupling

In the order in which they would be worth taking on. None is scheduled.

1. The `.PUSH_CTX`/`.POP_CTX` coupling between `source`, `importdir` and `ctx`: either the
   including features close their own inclusion, or the context boundary becomes a core
   concept with a core handler, so that no feature emits a directive another feature owns.
2. The feature-named fields on `LinkingContext`, `EmissionContext` and `ProgramArtifact`
   (issue #153): a feature-owned metadata container would let `proc`, `reg`, `place` and
   `label` carry their data under a key of their own.
3. `Symbol.Type`: the kinds a symbol can have could be asked of the defining node, as the
   phases already do through `IIdentifierBinding` and `IJumpTarget`; the token map is the only
   reader left.
4. The label logic in layout, emitter and `SourceLineIndex`, once the label is not one of the
   three fixed IR item kinds any more.
5. The module system, which is the largest block and the least likely to move: the symbol
   table would need to resolve names through a handler the module features register.

## Repeating the probe

The probe is not a test in the repository. It compiles a program with `new Compiler(features)`,
the package-private constructor that takes a feature list, once per feature left out of
`StandardFeatures.all()`, and records the outcome. A future test along these lines would make a
new hidden coupling visible: every feature left out must produce a programmer-facing message at
the directive of that feature, never an internal error and never a message about another
feature.
