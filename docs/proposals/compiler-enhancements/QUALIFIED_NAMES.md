# Qualified Names

**Status: TO BE REVIEWED** — specification complete after one architecture review, implementation on branch `feature/qualified-names`.

A module and a procedure are **levels** that hold names. A name is reached from inside its level
by itself and from outside by its path, `LEVEL.NAME`, the way `package.class.member` reaches a
member in Java. Every level is a visibility boundary: it shows its own names to the levels inside
it, and to everyone else only what it marks `EXPORT`. The path is also the identity of a name
throughout the compiler, in the IR, in the artifact and in the visualizer, so that one name in
two procedures is two names. A jump into a procedure is what `EXPORT` on a label inside it
declares; whether a jump out of a procedure without `RET` is reported is issue #201, which
builds on this document.

## Problem

The compiler already has levels: the module and, inside it, one level per procedure. Definitions
go into the level they stand in, a lookup searches from the current level outward, and a
procedure's names are invisible outside it. That part is right, and MASM and HLA do the same.

Four things are wrong with what the compiler does around the levels:

1. **The identity of a label drops its procedure.** From the IR on, a label is named
   `MODULE.LABEL`, whatever procedure it stands in. Two procedures of one module with a label
   `DONE` each produce two `IrLabelDef("UTIL.DONE")`; the layout keeps one address per name, the
   emitter writes both label cells at the second address and continues its counter from there,
   and every `JMPI DONE` in both procedures carries one value. The same happens for a label in
   a procedure and a label of the same name on the module level after it, and for a label of a
   module imported inside a procedure next to a label of that procedure. The program compiles
   without a message and is wrong (verified by compiling such programs). Constants and register
   aliases do not have the defect, because the artifact files them under `DefinitionKey`, the
   qualified name plus `@` plus the scope (`UTIL.N@UTIL.CLAMP`); labels never got that treatment.
2. **`EXPORT` inside a procedure is silently ignored.** A name is entered into the list that
   importers see only when it is defined at module level. `EXPORT` on a label or constant in a
   procedure compiles and does nothing.
3. **A name may contain a dot.** The lexer accepts `X.Y:` as a label, and the symbol table files
   it under `X.Y`, where it can only be confused with the qualified name `Y` of a module `X`.
4. **A jump out of a procedure is silent.** A jump from a procedure body to a module-level
   label compiles and leaves the procedure without `RET`: the entry stays on the call stack, and
   the marshalling at the call site never runs. Issue #201 asks for the report. It is not part of
   this document (see Decisions), but it builds on the scope this document gives every lookup.

Found while designing CONTROL_FLOW_DIRECTIVES: a control block would be a level too, and every
defect above would multiply with it.

## Options considered

| Option | Why not chosen |
|---|---|
| Do nothing; forbid one label name in two procedures | Takes away the locality that MASM and HLA give a procedure, and leaves `EXPORT` in a procedure meaningless. |
| Give labels the `DefinitionKey` form, `UTIL.DONE@UTIL.CLAMP`, as constants have | Fixes the identity and nothing else: two key forms stay (`@` for the artifact, `.` for the language), and the module stands in the key twice. |
| Make the identity `UTIL.CLAMP.DONE` but keep lookups as they are | The identity would be a form no program can write; a procedure's name would be reachable from nowhere. |
| A lookup-only scope for a block's short names (CONTROL_FLOW_DIRECTIVES as first drafted) | A second kind of scope in the core for one feature; and it leaves defects 1 to 3 in place. |
| Allow a name on an inner level to shadow one of an enclosing level, the innermost winning | A word with two meanings depending on where it is written, at the first segment of a path where it hurts most; Java allows it for fields and regrets it (obscuring), C# reports it. No program does it today. |
| Report a jump into a procedure as well as out of it (this document as first reviewed) | The jump in is what `EXPORT` on a label inside a procedure declares; the rule would take that declaration back for some instructions while `CALL` and the location instructions still carry an address into the procedure. The jump out has no declaration in the source and is a rule of its own: issue #201. |
| **Levels with paths, one rule for visibility, one rule against shadowing, the path as the identity** | Chosen. |

## Solution

### Terms

A **level** is a module or a procedure. A **name** is what a definition gives: a label, a
constant, a register alias, a procedure, an import or requirement alias. A **path** is a name
preceded by the levels it stands in, joined by dots, from the module outward: `UTIL.CLAMP.DONE`
is the label `DONE` in the procedure `CLAMP` of the module imported as `UTIL`. A **segment** is
one name of a path. A directive that opens a further level in future, such as a control block,
joins these rules by registering its scope and naming its segment; nothing here is decided for
it.

### One name per level, no shadowing, one segment per name

- A level holds each name once, whatever kind of thing it names. A second label, constant,
  procedure or import alias of the same name on one level is reported, as it is today.
- A name that a level the definition stands in already has is reported as well: "`UTIL` is
  already defined at util.evo:3, on an enclosing level". Two sibling levels may hold the same
  name, two procedures each with a `DONE`; a name along the path outward is unique, so that a
  word never means two things depending on where it is written. Register aliases are no
  exception: `%TMP` in two procedures is allowed, `%TMP` in a procedure next to `%TMP` on the
  module level is not. Because definitions arrive in two passes and in text order, the check runs
  once over every registered level by the semantic analysis, after both of its passes.
- A name is one segment: a definition whose name contains a dot is reported, for every kind of
  name, by the symbol table itself when the name is defined.
- `.IMPORT` and `.REQUIRE` stand only on the module level; inside a procedure they are reported
  ("may stand only at the module level"). `.SOURCE` stays allowed anywhere, it inserts text.

### Visibility

- A level sees its own names and the names of every level it stands in: a procedure sees the
  module. A plain name is found in the innermost level that has it, as today.
- Of a level it does not stand in, a level sees only the names marked `EXPORT`, and only through
  the path: the module sees `CLAMP` and, if `CLAMP` exports `DONE`, `CLAMP.DONE`. An importing
  module sees `UTIL.CLAMP.DONE` if `UTIL` exports `CLAMP` and `CLAMP` exports `DONE`: every
  segment of a path that leads into a level the writer does not stand in has to be exported. An
  import passed on with `EXPORT .IMPORT` is an exported segment like any other.
- `EXPORT` therefore means the same on every level: the name is visible one level further out.
  Today's rule for modules is this rule applied to modules; today's rule for procedures (nothing
  visible, `EXPORT` ignored) is replaced by it.

### Jumps and procedures

A jump into a procedure is in the programmer's hands. A label inside a procedure is reachable
from outside only when it is marked `EXPORT`, and whoever marks it declares that the place may
be entered from outside, by a jump, a `CALL` or a location instruction alike; the compiler
checks form and visibility and does not take that declaration back for some of the
instructions. A jump out of a procedure without `RET` has no such declaration in the source.
Whether the compiler reports it, and how, is issue #201: it builds on the scope this document
gives every lookup result and is not part of this proposal.

### Identity

The path is the one name a definition has from the lookup on: the symbol table resolves a use to
its path, Phase 6 replaces the identifier by it, the IR carries it, the layout hashes it into the
label value, the artifact files aliases, constants and labels under it, and the token map
reports it as the qualified name. `DefinitionKey` and its `@` form disappear; the module stands
once in a path and the name stands last. The core forms every path from the module's alias chain
and the segments of the levels; a feature that opens a level hands over its segment only.

### What a programmer writes

```
.PROC CLAMP REF VALUE VAL MIN MAX
  LTR VALUE MIN
  JMPI TO_MIN                      # own level: the plain name
  …
EXPORT TO_MIN:                     # visible to the module as CLAMP.TO_MIN
  SETR VALUE MIN
  RET
.ENDPROC

  PSLI CLAMP.TO_MIN                # from the module: the path, for the data pointer
  JMPI CLAMP.TO_MIN                # from the module: the path; EXPORT declared the entry
```

From another module, after `.IMPORT "lib/util.evo" AS UTIL` and `EXPORT .PROC CLAMP`:
`PSLI UTIL.CLAMP.TO_MIN`. Without the `EXPORT` on the label, both uses are reported: "`TO_MIN` of
`CLAMP` is not marked EXPORT".

### Architecture

Everything changes in the core, in the places that own names, and in the two features that
build a name of their own today; no feature learns another feature.

| Where | Today | Afterwards |
|---|---|---|
| `SymbolTable.enterScope`, `IrGenContext.enterScope` | take a full name the feature built (`UTIL.CLAMP`) | take the segment (`CLAMP`) and form the path from the enclosing scope's path and the module's alias chain, in both phases from one rule; `ProcedureSymbolCollector` and `ProcedureNodeConverter` pass the segment |
| `SymbolTable.lookUp` | the qualified name of a found symbol is `chain.NAME`, whatever scope it was found in; after the scope search, a dotted name is split and its first segment looked up in the import and `USING` tables only | the qualified name is the path of the scope the symbol was found in plus the name; the first segment of a dotted name is looked up like a plain name, from the current scope outward, and the walk descends from what it finds |
| `SymbolTable.resolveMultiLevel` | descends through imports only; a segment that is no import is an error | descends into a module through an import or requirement alias, as today, and into a scope through a symbol whose node has a registered scope (`getNodeScope(symbol.node())`). Crossing into a level the writer does not stand in requires the segment's symbol to be exported; the walk knows whether it stands inside a scope because it starts from the current scope. The rule "a requirement is not a step through another module" stays. Messages: "`X` has no member `Y`", "`Y` of `X` is not marked EXPORT". |
| `Lookup`, `ResolvedSymbol.scope`, `SymbolTable.bindingOf` | the lookup result carries the placement only; a chain of bindings into another placement continues from that placement's root; `resolveMultiLevel` reports the root as the scope | the result carries the scope the symbol was found in; a chain continues from it; the reported scope is that scope, so that `UTIL.CLAMP.N` defined as `LIMIT` inside `CLAMP` resolves and the token map names the procedure |
| `SymbolTable.define` | files the symbol; returns a second definition on the same level for the caller to report | also reports, itself, a name containing a dot: "a name is one segment"; the callers keep reporting duplicates, which they know the kind of |
| `SymbolTable.reportShadowing`, `SemanticAnalyzer.analyze` | — | walks every registered scope outward and reports a name an enclosing level has, naming both lines; the analysis calls it once after both passes, as an explicit step, so that `freeze` stays a pure state change |
| `ImportSymbolCollector` | creates the alias symbol with `exported = false`; whether an import is passed on lives in `ModuleScope.importExported` | the alias symbol carries the `EXPORT .IMPORT` flag of its node, so that every segment answers "exported" from the same field; `importExported` goes, and with it the dependency scan's own reading of the `EXPORT` prefix and the check that compared the two |
| `ImportDirectiveHandler`, `RequireDirectiveHandler` | accept the directive anywhere the parser reads a statement | ask the parser state whether the line stands at the module level (a query on the scope depth the parser keeps, naming no feature) and report otherwise |
| `IrGenContext.qualifyName` | `chain.NAME` | the current scope's path plus the name; the scope stack holds paths |
| `DefinitionKey` | `qualified@scope` for aliases and constants | removed. `ConstantValueEmissionContributor` and `RegisterAliasEmissionContributor` file under the name the IR directive carries, which is the path; the `scope` argument of the `const_value` and `reg_alias` directives is removed with its only readers, and `IrGenContext.currentScope()` with it unless a reader remains. `TokenInfo.GLOBAL_SCOPE` takes over the constant `"global"`, which `TokenMapGenerator` and `ProcedureTokenMapContributor` use instead of the literal. |
| `TokenMapGenerator` | the identifier branch takes the qualified name from the resolved symbol; a register-alias branch builds `chain.alias` itself, but is never reached, because aliases are still identifiers in Phase 5 and become registers only in Phase 6 | the register-alias branch is removed; a register is a register, an identifier is classified by its symbol |
| `AnnotationUtils.definitionKey` (visualizer) | rebuilds the `@` key | removed; `resolveToCanonicalRegister`, `RegisterTokenHandler` and the constant lookup in `SourceAnnotator` look up the qualified name; `ParameterTokenHandler` compares the scope with `TokenInfo.GLOBAL_SCOPE` exactly, so that a procedure named `GLOBAL` keeps its annotations |
| Layout, linker, emitter, artifact maps, `AstPostProcessor`, the data pipeline | key by the name they are given, or pass the maps through | unchanged |
| Marshalling bridge labels `_safe_call_N`, `_safe_ret_N` | unqualified, lower case | unchanged: no program can write them |

The module system's description in `docs/COMPILER_CORE_BOUNDARY.md` gains one sentence: the
symbol table resolves a path through imports, requirements and the scopes that symbols open.
Nothing in the boundary document's list of couplings grows; the removal of `DefinitionKey` takes
one item out of what #153 has to carry.

### Documentation

- `docs/ASSEMBLY_SPEC.md`, section 4: a subsection "Qualified names" after "Labels", in the form
  of its neighbours, saying what a level is, what a path is, the one visibility rule and the
  rule against shadowing; "Exported Labels" and "Exported Constants" refer to it; section 6
  "Control Flow": an exported label inside a procedure may be entered from outside, and a jump
  that leaves a procedure without `RET` is not reported; section 7, `.IMPORT`, `.REQUIRE` and
  `.PROC`: `EXPORT` as the rule says, `.IMPORT`/`.REQUIRE` at module level only.
- `docs/COMPILER_CORE_BOUNDARY.md`: the sentence above.
- `.claude/skills/evoasm/SKILL.md`: paths, `EXPORT` inside procedures.
- `src/main/proto/.../metadata_contracts.proto`, `internal/LinearizedProgramArtifact.java`,
  `ui/organism/OrganismSourceView.js`: the comments that describe the `@` key.
- Issue #153: a comment that `DefinitionKey` is gone and the artifact's keys are paths. Issue
  #201: a comment with the design that builds on this document.
- `docs/proposals/README.md`: this document's row; CONTROL_FLOW_DIRECTIVES builds on it.

## Consequences for programs and experiments

- Every existing program compiles unchanged: no program under `assembly/` or in the test
  resources defines a dotted name, writes `EXPORT` inside a procedure, has a name on two levels
  of one file, or imports inside a procedure (checked by search, with `.SOURCE` resolved). Two tests change: `RegisterAliasScopeTest.shadowingModuleLevelAlias`
  asserts that an alias may shadow one of the module level and turns into the opposite
  assertion; `PlacementArtifactIntegrationTest` expects the label of a procedure under the
  module's name and expects its path instead.
- The label values of labels inside procedures change, because the value is the hash of the
  identity and the identity now carries the procedure. The reference artifact of
  `CompilerOutputEquivalenceTest` is regenerated once, deliberately, after a diff has shown that
  what differs is: label and label-reference cells inside procedures, the two label maps, the
  qualified names of procedure-local symbols in the token map (`UTIL.MIN` becomes
  `UTIL.CLAMP.MIN`), the keys of the alias and constant maps, any label value that the
  collision rule (`#attempt`) moves along, the operand texts of the jumps to those labels in the
  source-line index, and the program identity, which is a digest of the machine code. The pull
  request says so. A run is read with the build that wrote it.
- The artifact's alias and constant maps are keyed by paths instead of `@` keys. The data
  pipeline passes the maps through and is not affected; the visualizer's lookups change with
  the keys.

## Pitfalls

- **A name added on the module level** breaks a procedure that defines the same name locally,
  because the inner definition is now a shadowing one; the message names both lines. Java's rule
  for local variables has the same property.
- **Two placements of one module** keep separate names, as today: the path begins with the
  placement's alias chain, and the shadowing check is filed by placement.
- **No JavaScript tests exist.** The visualizer change is verified by hand: `%TMP` in
  `LOCAL_STEP` of the reference program and a procedure-local constant must be annotated.
- **Hot path**: none; the compiler only.

## Implementation

Strictly in this order; every step leaves the build green, and a defect is reproduced by a test
that fails before its fix (AGENTS.md, "Defect tests").

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | The path as the identity | test first: new `QualifiedNamesTest` in `compiler/features/label` (inline assembly, `Compiler.compile`, `@Tag("unit")`), compiling two procedures with a label `DONE` each, and a procedure label followed by a module-level label of the same name, asserting two label cells at two addresses and distinct reference values (fails today); then `SymbolTable.enterScope` (segment, path formed in the core), `lookUp` (qualified name from the scope's path), `Lookup`/`ResolvedSymbol.scope`/`bindingOf` (the scope of the find), `IrGenContext.enterScope`/`qualifyName`, `ProcedureSymbolCollector`, `ProcedureNodeConverter`, `TokenMapGenerator` (alias branch, `GLOBAL_SCOPE`), `ProcedureTokenMapContributor`, `DefinitionKey` removed, `TokenInfo.GLOBAL_SCOPE`, `ConstNodeConverter`/`RegNodeConverter` (no `scope` argument), the two emission contributors, `EmissionContext`, `ProgramArtifact` and `LinearizedProgramArtifact` Javadoc, `AnnotationUtils.js`, `SourceAnnotator.js`, `RegisterTokenHandler.js`, `ParameterTokenHandler.js`, `ConstantValuesTest` (keys are paths), regenerated `expected/main.json` | `gw test --tests '*QualifiedNames*' --tests '*ConstantValues*' --tests '*TokenMap*' --tests '*CompilerOutputEquivalence*'`; the artifact diff limited to what the Consequences list; the visualizer check by hand | the defect test passes; every alias, constant and label key is a path; `%TMP` and a procedure-local constant are annotated |
| 2 | One name per level, no shadowing, one segment, imports at module level | `SymbolTable.define` (dot reported), `SymbolTable.reportShadowing` (shadowing check over every registered scope, called by `SemanticAnalyzer`), `ImportDirectiveHandler`, `RequireDirectiveHandler`, the parser-state query; tests first: `SymbolTableTest`, `SemanticAnalyzerTest` (a parameter named like a later module constant; a procedure label named like a later module label; an alias named like a module alias; an import alias; two procedures with the same label stay allowed), `RegisterAliasScopeTest` (assertion turned), `LabelDirectiveTest` or the parser test of labels, `ProcedureDirectiveTest`, `ConstDirectiveTest`, `RegDirectiveTest`, `ImportDirectiveTest` (a dotted definition of each kind; `.IMPORT` and `.REQUIRE` in a procedure) | `gw test --tests '*SymbolTable*' --tests '*SemanticAnalyzer*' --tests '*RegisterAliasScope*' --tests '*Directive*'` | every rule has a red-then-green test for both definition orders; sibling levels keep their locality |
| 3 | Paths and visibility | `SymbolTable.lookUp` (first segment as a plain name), `resolveMultiLevel` (descent into scopes, the visibility rule, messages), `ImportSymbolCollector` (exported flag), `ModuleScope` (`importExported` removed), `ImportDependencyInfo`/`ImportDependencyScanHandler` (the scan no longer records the prefix), `ImportAnalysisHandler` (drift check removed); `SymbolTableTest`, `SemanticAnalyzerTest` (`CLAMP.TO_MIN` from the module with and without `EXPORT`; a plain `TO_MIN` from the module still reported; a constant defined through another constant inside an exported procedure resolved by path, with the procedure as the token's scope), a two-file test under `@TempDir`, `@Tag("integration")`, modelled on `ReExportedImportResolutionTest` (`UTIL.CLAMP.TO_MIN` with both exports, one missing, none; the reference program's `NAV.STEP.FORWARD` through a passed-on import) | `gw test --tests '*SymbolTable*' --tests '*SemanticAnalyzer*' --tests '*Import*' --tests '*Resolution*' --tests '*CompilerOutputEquivalence*'` | every case of the visibility rule has a test; `EXPORT` inside a procedure is meaningful; the reference artifact is unchanged by this step |
| 4 | Documentation | the documents listed under Documentation; this document's status; the comments on #153 and #201 | review of the diff against the neighbouring sections | every document uses the terms level, path, segment, and no more words than its neighbours |
| 5 | Gate | — | `gw check` | PMD and the full suite green |

## Decisions

1. A module and a procedure are levels; a level holds each name once, whatever its kind, and
   never a name that a level it stands in already has; register aliases are no exception.
2. A name is one segment; a definition with a dot in its name is reported, by the symbol table.
3. The shadowing check runs once at the end of the semantic analysis, over every registered
   level, as an explicit step of the analysis.
4. `.IMPORT` and `.REQUIRE` stand only on the module level.
5. A path is the levels of a name from the module outward, joined by dots; the core forms it
   from segments.
6. A level sees its own names and those of the levels it stands in; of any other level it sees
   only what is marked `EXPORT`, through the path, every segment exported; a passed-on import is
   an exported segment.
7. The path is the identity of a name from the lookup on; `DefinitionKey` and the IR's `scope`
   argument are removed.
8. A jump into a procedure is what `EXPORT` on a label inside it declares; the compiler checks
   form and visibility and no more. The jump out of a procedure without `RET` is issue #201,
   decided and built after this document.
9. The marshalling's bridge labels stay unqualified.
10. The label values of labels inside procedures change once; the reference artifact is
    regenerated and the pull request says so.
11. Nothing is decided for control blocks; CONTROL_FLOW_DIRECTIVES builds on this document and
    is rewritten after it.
