# Module Values

**Status: TO BE REVIEWED** — specification complete, not yet implemented.
Builds on [QUALIFIED_NAMES](../outdated/proposals/accomplished/QUALIFIED_NAMES.md) and
[CONDITIONAL_COMPILATION](../outdated/proposals/accomplished/CONDITIONAL_COMPILATION.md).

A module declares what it needs from its importer with `.REQUIRE`: today a module, from now on
also a value. The importer supplies both on its `.IMPORT` line with `USING`, a module under the
alias the requiring module chose, a value for the constant it named; the configuration of a
simulation supplies the values of the main program. The module stays a text that explains
itself: every name it uses is defined in it, declared as a requirement, or reached by a path. The
compiler core stops knowing what a requirement is: it keeps, for every placement, which aliases
lead where and a place where a feature keeps its own records, and the `require` feature becomes
part of the import feature, which owns both ends of the mechanism.

## Problem

1. **A module cannot be configured by its importer.** A module sees its own names and those of
   the levels inside it, never the names of the module that imports it. That is the rule of
   QUALIFIED_NAMES and it is right: a module means the same wherever it is placed, and the same
   file may be placed twice. But a module that depends on a number — a step count, a threshold,
   a size — has today two ways to get it, and both are poor. It can `.SOURCE` a file of constants,
   which hard-wires a file name into its body, cannot differ between two placements and hides the
   dependency in the text; the module example does this, `math.evo`, `movement.evo` and `main.evo`
   each `.SOURCE` `lib/constants.evo`. Or the importer wraps the values in a module of exported
   constants and injects it with `USING`, one file per configuration, for a single number.
2. **A module cannot have a default.** `.REQUIRE` must be satisfied; a module that would be
   content with a default unless told otherwise cannot say so.
3. **The core knows the concept "requirement".** `ModuleScope` holds four maps, imports,
   requirements, `USING` bindings and symbols; `SymbolTable` reads the requirements in two places
   for two messages of its own ("is a requirement that no import supplied", "is a requirement of
   M; use the module you supplied for it") and for the rule that a path from outside never
   continues through a requirement. The core boundary document lists this as an accepted
   coupling and a candidate for decoupling. The `require` feature cannot live without the import
   feature, which checks and binds what `.REQUIRE` declares, yet nothing says so: two features,
   one mechanism, the contract in core data.

## Precedent

- **Verilog** gives a module `parameter STEPS = 10` and overrides it per instance,
  `walk #(.STEPS(20)) w1`; a `localparam` is fixed. **VHDL** has `generic (STEPS : integer := 10)`
  and `generic map (STEPS => 20)`; **Ada** generic packages are instantiated with their values.
  A module instantiated several times with different values is exactly a module placed several
  times.
- **Terraform** modules declare `variable "steps" { default = 10 }` and are given `steps = 20`
  where they are used; **Nix** modules are functions of their arguments.
- **Assemblers** configure files through symbols from the command line and `IFDEF`, as the
  `.DEFINE` flags of CONDITIONAL_COMPILATION do, and give macros keyword parameters with
  defaults (HLASM `&STEPS=10`, MASM `STEPS:=<10>`); their modules meet in the linker, not by
  placement, so none has a module parameter.
- **C** keeps `#define` for conditions and `const` for code apart; CONDITIONAL_COMPILATION chose
  the same split for flags and constants.

## Options considered

| Option | Why not chosen |
|---|---|
| Let a module see the names of its importer | A module would mean something else in every importer, its text would not say where a name comes from, and a module that means its own `MAX` would silently get the importer's. No module system does this; the textual inclusion `.SOURCE` already covers the case where that is wanted. |
| Keep the status quo: a module of exported constants, injected with `USING` | Works and stays available. One file per configuration for one number, and no default; the pattern was never written down. |
| A new directive for the declaration (`.PARAM`, `.GENERIC`, `.VALUE`) and a new keyword on the import (`WITH NAME=VALUE`) | `PARAM` is the word for procedure parameters and would be confused with them; `GENERIC` is foreign to assembler readers; a second keyword next to `USING` and a `=` that the language has nowhere else. `.REQUIRE` already means "the importer supplies this" and `USING` already means "this is what I supply". |
| `USING` lists without `AS` or without the kind word | `MATH CALC` says not which name is the importer's and which the module's; without `MODULE` and `VALUE` the two kinds cannot be told apart in one list. |
| Values usable in `.IFDEF` | A flag is decided by the preprocessor, before a module is parsed; a value is bound per placement and may be a name of the importer that only the analysis resolves. It would work for literals and not for names, a rule with an exception. The split of CONDITIONAL_COMPILATION stays: a value needed in both places is written as a flag and as a constant. |
| Only literals as values | The first thing a programmer writes after `USING VALUE STEPS DATA:20` is `USING VALUE STEPS MAX_STEPS`, with a constant of the importer. Supported from the start. |
| Keep `require` a feature of its own and declare the dependency | A declaration the registration has no use for, since `StandardFeatures` registers every feature; bureaucracy. The slicing rule of AGENTS.md names the unit of change, and the unit here is the injection, both ends of it. |

## Solution

### Terms

- A **requirement** is what a module declares with `.REQUIRE`: a **module requirement**, satisfied
  by a module of the importer, or a **value requirement**, satisfied by a value. A value
  requirement may carry a **default**.
- A **binding** is what an `.IMPORT` supplies with `USING`: a module under the alias the
  requirement chose, or a value for the constant the requirement named.
- A **placement** is one import of a module, as in QUALIFIED_NAMES; every placement has its own
  bindings.

### The directives

```
.REQUIRE MODULE "<path>" AS <Alias>          # a module the importer supplies
[EXPORT] .REQUIRE VALUE <Name> [<Default>]   # a value the importer supplies, or the default

.IMPORT "<path>" AS <Alias> [USING {MODULE <Source> AS <Target> ... | VALUE <Name> <Value> ...} ...]
```

- `.REQUIRE MODULE "<path>" AS <Alias>` is today's `.REQUIRE "<path>" AS <Alias>` with the kind
  written out. Nothing else about it changes.
- `.REQUIRE VALUE <Name> [<Default>]` defines the constant `<Name>` on the module's level, as
  `.CONST <Name> <Value>` would and in the same pass, with the value the importer binds, or the
  default when the importer binds none; it stands before its first use, and in a clash of names
  the first definition in the text wins, as between two `.CONST`. Without a default the importer
  has to bind it. A default is what a `.CONST` accepts: a typed literal, a vector, or a name the
  module can see. The constant is a
  constant in everything that follows: it is reached by its path, is reported like a constant
  when its name is already taken, and `EXPORT .REQUIRE VALUE` makes it reachable from outside as
  `EXPORT .CONST` does, so that an importer may read a default it did not set, or an importer two
  levels up a value it did not bind. One requirement per line, as one constant per line.
- `EXPORT .REQUIRE MODULE` stays rejected, as `EXPORT .REQUIRE` is today: whoever reaches the
  module from outside stands in the chain that supplied the required module and knows it under
  its own name, so an exported alias would be a second name for the same placement. The parser
  reports `EXPORT` only before a directive whose handler takes none; `.REQUIRE` takes it, so the
  directive handler reports the kind that does not, with the reason only it knows:
  `EXPORT is not supported before '.REQUIRE MODULE': the importer supplies the module and already knows it under its own name.`
  The parser's message stays for every directive that takes no `EXPORT` at all. This is how the
  project divides reports: the core says what needs no feature knowledge, or hands the feature
  the reason a lookup stopped; the feature that knows the cause reports it.
- `USING` on an `.IMPORT` binds, in one clause, as `.PROC` reads its parameters: each kind word
  opens a list, and the groups that follow it, up to the next kind word, belong to it. After
  `MODULE`, groups `<Source> AS <Target>`, the importer's module `<Source>` for the module
  requirement `<Target>`, as today; after `VALUE`, groups `<Name> <Value>`, the value for the
  value requirement `<Name>`. The kind words stand in any order and may repeat, each repetition
  adding to its list; whoever wants it plain writes one kind word per group. No commas: every
  group has a fixed length, so the next group begins where the previous one ends. `USING` itself
  stands once on an import; a second `USING` is reported ("USING stands once on an import; a
  further list begins with its kind word."), so that the old form of several clauses is not half
  kept. A `<Value>` is
  what a `.CONST` accepts, written in the importer: a typed literal, a vector, or a name the
  importer can see, whose constant is bound.
- A module is named with `AS`, as everywhere: `.IMPORT … AS`, `.REQUIRE MODULE … AS`,
  `USING MODULE … AS`. A value is set like a constant, name first and then the value:
  `.REQUIRE VALUE STEPS DATA:10`, `USING VALUE STEPS DATA:20`. Each kind looks the same at its
  declaration and at its binding.
- The import reports a binding for a name the module does not require, a value requirement
  without default that the import does not bind, a name bound twice, and a module requirement
  without a binding, all at the `.IMPORT` line, as the unsatisfied module requirement is
  reported today.
- `EXPORT .IMPORT` passes an import on as today; the values are bound where the module is
  placed, not where it is passed on.
- **The main program** is a placement without an importer. Its value requirements are bound by
  the configuration: `values { STEPS = "DATA:20" }` in the organism's entry, and
  `evochora compile --value STEPS=DATA:20` for one compilation, next to `defines` and `--define`.
  A configured value takes the place of the default: the compiler lexes every configured value
  text in phase 1, as it lexes every file that may enter the token stream, and the preprocessor
  handler of `.REQUIRE` puts those tokens where the default stands, in the root placement alone.
  From there on nothing is special. The configuration is decisive, as it is for flags.
- **Values and flags stay apart.** A value is a constant: it is used in code and never in
  `.IFDEF`. A flag is used in `.IFDEF` and never in code.
- **A name is used after it is defined, everywhere.** An instruction may name a constant only
  after its `.CONST` (today: "Cannot use 'MAX' as an argument: the name is not defined."). The
  same holds for a name as the value of a `.CONST`, which the compiler lets pass today and will
  report ("Cannot define constant 'A' from 'B': the name is not defined."), for a name bound with `USING VALUE`,
  resolved at the `.IMPORT` line ("Cannot bind VALUE SPEED SLOW_SPEDD: the name is not defined."),
  and for a default that is a name, resolved at the `.REQUIRE VALUE` line. A constant of the
  importer that an import binds therefore stands before that import. A second definition of a
  name is rejected as today ("Cannot define constant 'MAX': the name is already used at …"),
  whichever directive made the first. Single-pass tools, the C preprocessor and NASM's
  `%assign` among them, work this way; Evochora chose it for instructions, and an exception for
  `.CONST` or `USING VALUE` would be the kind of special case nobody expects. A circle of
  constants can no longer be written, because its second line already names an undefined name;
  the circle guard of `bindingOf` stays for the other kinds of binding.

Example, a library module placed twice with different values and one module requirement:

```
# walk.evo
.REQUIRE MODULE "math.evo" AS CALC
.REQUIRE VALUE STEPS DATA:10              # how far one walk goes; 10 unless the importer says
.REQUIRE VALUE SPEED                      # the importer has to say
  ...
  GETI %I STEPS
  CALL CALC.CLAMP REF %I VAL DATA:0 SPEED

# main.evo
.CONST SLOW_SPEED DATA:1                  # defined before the import that binds it
.IMPORT "math.evo" AS MATH
.IMPORT "walk.evo" AS WALK USING MODULE MATH AS CALC VALUE SPEED DATA:3
.IMPORT "walk.evo" AS SLOW USING MODULE MATH AS CALC VALUE STEPS DATA:4 VALUE SPEED SLOW_SPEED
```

### What the core keeps and what it forgets

The core keeps for every placement what it needs to resolve a path: the symbols of the module
level and the **aliases** of that level, each leading to a placement, whether an `.IMPORT` or a
satisfied module requirement put it there. Whether a path from outside may continue through an
alias is carried by the alias symbol's `EXPORT` flag, as today; a module requirement's alias is
never exported, so the generic message "alias 'X' of M is not marked EXPORT" replaces the
requirement's own.
The core forgets the concept: `requires()`, `imports()` and `usingBindings()` leave `ModuleScope`,
and the branches of `SymbolTable` that read them go. The import feature reports the cause of an
unsatisfied requirement, at the `.IMPORT` line or, for the main program, at the `.REQUIRE` line. A
use of the alias says only what the core knows: the alias symbol has no entry in the alias map, so
`CALC.CLAMP` is explained with "'CALC' leads to no module." The wording names no requirement and
holds for any alias that leads nowhere. The table tells an alias symbol from any other through a
capability of the model, `IPlacementAlias` in `model/ast`, which `ImportNode` and `RequireNode`
implement; it never reads the kind of a symbol (`CompilerArchitectureRulesTest.nobodyReadsTheKindOfASymbol`),
as `IIdentifierBinding` and `IJumpTarget` already answer what a definition is. The other explanations of the symbol table lose the two
words the same way: a first segment that is nothing of the module's is "neither a name nor an alias
of this module" (today "neither an import nor a requirement"); an unexported segment reached from
outside is "alias 'CALC' of WALK is not marked EXPORT." when the segment is an alias (today
"import '…'" for an import and no prefix for a requirement); the branch that explains a path from
outside through a requirement goes, because such an alias is never exported and the message
before covers it. The Javadoc of `SymbolTable` and of `descendModule` describes an alias of the
module level that leads to a placement, and the `EXPORT` flag of its symbol as what lets a path
continue through it. The tests that pin today's texts (`ImportAnalysisHandlerTest`,
`UsingClauseIntegrationTest`, `SemanticAnalyzerTest`, `QualifiedPathResolutionTest`) change with
them.

### One reader for the clause

Today three places read `USING x AS y`: the dependency scan by pattern, to feed the module setup;
the preprocessor token by token, to check that the line is well formed; the parser, to build the
node. The module setup is the one step after the parser that works from raw-text data. With
values in the clause, all three would grow. Instead the clause gets one reader, the parser, and
the bindings are made from the AST:

- The scan matches `[EXPORT] .IMPORT "<path>" AS <Alias>` and `.REQUIRE MODULE "<path>" AS <Alias>`
  and takes the rest of the line as the directive's without reading it. That is the address of a
  file, which the scan has to know before the lexer runs over every file; `ImportDependencyInfo`
  loses its `usings`. The module setup registers the placements and the import aliases from it,
  as today, and nothing else: `IDependencySetupHandler.resolveBindings` and
  `RequireModuleSetupHandler` go.
- The preprocessor reads path and alias, places the module's tokens, and skips to the end of the
  line; its one check that stays is that the directive takes the whole line, so that
  `.IMPORT "a.evo" AS A; NOP` is still reported.
- Pass 1 makes the bindings, in text order, which is the order of the placements: the module's
  tokens follow the `.IMPORT` line, so the importer's node is collected before anything of the
  module. `ImportSymbolCollector` binds each `USING MODULE` source to the requirement's alias in
  the imported placement with `ModuleScope.bindAlias`, the one method of the alias map, resolving the source through the importer's
  aliases or through the bindings the importer itself received earlier in the pass, and records
  the `USING VALUE` bindings in the imported placement's record. `RequireAnalysisHandler`, in
  pass 2, finds the binding for its placement there, or takes the default. Whether every
  requirement is bound and every binding names a requirement is checked in pass 2, when both sides
  are known.

What a feature keeps inside phase 4, for every placement — the module requirements declared, the
values bound at the import until the module's `.REQUIRE VALUE` is reached — lives in a store the
core offers and does not read: `ModuleScope` gets `state(Class<T> key, Supplier<T> factory)`,
keyed by a type the feature owns, as `PreProcessorContext.getOrCreate` already lets a feature
keep a record per compilation. Features and handlers hold no compilation state (AGENTS.md,
"Stateless Features"); their records go here. The store is written and read in phase 4 alone:
`freeze()` makes it immutable with the scope's maps at the end of the analysis, a later write is
the same defect as a later `defineSymbol`, and no phase after 4 reads it. `freeze()` can stop new
records, not changes inside one, so the record itself is immutable once pass 1 has filled it. What
later phases need, the bound value, stands in the symbol and in the AST.

A value bound to a name of the importer is resolved where that name is written. Every token
carries the placement it stands in (`SourceInfo.placement`, set by the preprocessor for the main
file and by `ImportSourceHandler` for a module's tokens), and `resolve` already looks a name up in
the placement of its position. `bindingOf` does not yet: when it follows a definition to the
identifier it binds, it looks that identifier up in the placement of the definition. The rule
becomes: in the scope of the definition, in the placement the identifier's own `SourceInfo` names.
For every binding that exists today the two placements are the same, so nothing changes, and the
reference artifact proves it; for a value bound to a name of the importer, the identifier stands in
the importer. No capability, no field on a node: placement knowledge stays in the symbol table
(AGENTS.md, "Pure Data Records"). The tokens of a configured value get the root placement from the
preprocessor handler that injects them, as module tokens get theirs. The symbol table files names
under one of two keys, chosen once from its state: the alias chain of the placement when
placements are registered, which the compiler always does, and the file name when none is, the
documented single-file mode that most unit tests of the analyzer use. `bindingOf` makes that
choice through the one place `lookUp` already has for it, never by trying one key and then the
other.

### What is reported, and where

- A module requirement without binding, a `USING MODULE` target or `USING VALUE` name the module
  does not require, a name bound twice, and a value requirement without default that the import
  does not bind: at the `.IMPORT` line, in pass 2, by `ImportAnalysisHandler`, in the pattern of
  today's message:
  `Cannot import WALK: it requires the value 'SPEED'; add USING VALUE SPEED <value> to the import.`
- A module requirement of the main program, which nothing imports and so nothing can supply: at the
  `.REQUIRE MODULE` line, in pass 2, by `RequireAnalysisHandler`, whether or not the alias is used:
  `The main program cannot require a module: nothing imports it, so nothing can supply 'CALC'; import "math.evo" instead.`
  Today the only report comes from the core at a use of the alias, and none when it is unused.
- A use of an alias that leads to no module, in a module or in the main program, from the core at
  the use: `Cannot call 'CALC.CLAMP': 'CALC' leads to no module.` It follows the report above and
  replaces today's "'CALC' is a requirement that no import supplied", which the core can no longer
  word.
- A value requirement of the main program without default and without a configured value: at the
  `.REQUIRE VALUE` line, in pass 2, by `RequireAnalysisHandler`:
  `The value 'SPEED' has no default and the configuration sets none; set it with values { SPEED = "<value>" } in the organism's entry or --value SPEED=<value>.`
  Pass 2 defines the symbol all the same, bound to nothing, so that the uses report nothing of their own.
- A configured value the main program does not require: against the configuration, file empty and
  line 0, as `Configured flag 'X' is not a valid name` is reported today. Pass 2 cannot see the
  configuration and runs no handler when the main program has no `.REQUIRE VALUE` at all, so the
  `.REQUIRE` preprocessor handler reports it: it records in its context record which values it put
  in place, and at the end of the preprocessor run, in a new `finish(PreProcessorContext)` of
  `IPreProcessorHandler` that the preprocessor calls once per registered handler after
  `expand()`, it reports the rest:
  `Configured value 'STEP' is not required by main.evo: it has no .REQUIRE VALUE STEP.`
- A configured value text that is not one operand, because it lexes to more than one token before
  the end, such as `DATA:1; NOP` (a `;` lexes to NEWLINE): by the `.REQUIRE` preprocessor handler
  when it would inject it, `Configured value 'X' is not one operand.`
- A `USING VALUE` whose value is a name the importer has not defined at the `.IMPORT` line, by
  `ImportAnalysisHandler`: `Cannot bind VALUE SPEED from 'SLOW_SPEDD': the name is not defined.`; a default
  that is a name the module has not defined, at the `.REQUIRE VALUE` line, by
  `RequireAnalysisHandler`, worded the same. The value is resolved there as a use, so that the uses
  inside the module, analysed before any later line of the importer, find it bound.
- A configured value text the lexer rejects: by the lexer under the synthetic file name of that
  value, worded as `value STEPS of the configuration`, never as a line.

### Into the artifact

A constant reaches the artifact's `constantValues`, which the visualizer reads, because
`ConstNodeConverter` emits a `const_value` directive in phase 7 with the constant's path and the
text of its value, and a contributor of `constdir` writes it in phase 11. Phase 6 follows every
`IdentifierNode` through `bindingOf` to the end of its chain and replaces it, but a replacement
lands only in a node that rebuilds itself with new children (`reconstructWithChildren`); the default
returns the node unchanged, and `ConstNode` keeps that default. So today `.CONST ALSO LIMIT` reaches
the artifact as `LIMIT`, and the source view notes `[=LIMIT]` after every `ALSO` instead of
`[=DATA:9]`; `ConstantValuesTest` pins it. Phase 7 has no symbol table and cannot repair that.

**The source view shows the literal at the end of the chain, everywhere.** `ConstNode` and
`ValueRequirementNode` implement `reconstructWithChildren`, as `CallNode` and `ProcedureNode` do;
phase 6 then puts the literal into the node, and phase 7 emits it. `ConstantValuesTest` expects
`DATA:9` for `ALSO`. This is a correction of today's `.CONST` and goes into the first step with the
definition-before-use rule.

A value requirement's node holds only the default; the bound value lives in the symbol the module's
placement defines. The node therefore carries, in its value slot, an `IdentifierNode` with its own
name, and returns the slot and the default as its children. Phase 6 resolves the slot as any
identifier: to the symbol of this placement, whose `ValueBindingNode` binds it to the bound value or
the default, and on to the importer's constant when the value is a name, down to the literal. It is
not a circle: the binding leads to the value, not back to the name; a test pins that the circle
guard stays quiet. In phase 7 the converter sees the literal and emits `importdir`'s own
directive; `importdir`'s own contributor calls `EmissionContext.constantValue`. Neither feature
recognises the other's directive.

**The visualizer annotates a value like a constant, unchanged.** It writes `[=VALUE]` after every
token the token map marks `CONSTANT`, with the value `constantValues` gives for the token's
qualified name, per placement (`SourceAnnotator.constantNotes`). One difference to a `.CONST`: the
self-reference on the `.REQUIRE VALUE STEPS` line is an identifier, so that line too is annotated,
`STEPS [=DATA:4]`, and shows what the placement received; the name on a `.CONST` line is no
identifier and stays bare. Three things make that hold for a
value requirement, and the design provides each: the symbol has `Symbol.Type.CONSTANT`, so the token
map marks a use of `STEPS` in the module as `CONSTANT` with the qualified name of its definition in
that placement (`WALK.STEPS`); phase 7 qualifies the directive's name under the same alias chain; and
each placement gets its own entry, `WALK.STEPS` with `DATA:10` and `SLOW.STEPS` with `DATA:4`. A name
the importer writes in `USING VALUE SPEED SLOW_SPEED` is an ordinary identifier of the importer and
is annotated as today. Step 5 tests the token map entry and the two `constantValues` entries.

### Architecture

One feature, `features/importdir`, owns both ends: `.IMPORT` with `USING`, `.REQUIRE` in both
kinds, the dependency scan and module setup of both, the bindings and their checks. The
`require` package goes, its classes move. The core changes in `ModuleScope`, `SymbolTable`
(`bindingOf`), `CompilerOptions`, `Compiler` and `PreProcessorContext` (lexed value texts),
`ProgramIdentity` and `DebugInfo`, all generically.

| Component | Phase | Does |
|---|---|---|
| `RequireDependencyScanHandler` (moved) | 0, dependency scan | Matches `.REQUIRE MODULE "<path>" AS <Alias>`; a `.REQUIRE VALUE` is no dependency and is not matched. It and `RequireDependencyInfo` stay although the module setup no longer reads them: through `allowedInSourceFile` the scan still reports a `.REQUIRE` inside a `.SOURCE` file. |
| `ImportDependencyScanHandler` | 0 | Matches `[EXPORT] .IMPORT "<path>" AS <Alias>` and leaves the rest of the line unread; `ImportDependencyInfo` without `usings`. |
| `ImportSourceHandler` | 2, preprocessor | Reads path and alias, injects the module's tokens, skips to the end of the line; the `;` report stays, the `USING` check goes. A token after the clauses (`AS LIB^2`) is then the parser's to report, with the line rule's text that `.REQUIRE` already uses in the parser. |
| `RequireDirectiveHandler` (moved) | 3, parser | `supportsExport()` true. After `.REQUIRE` a kind word: `MODULE` then `"<path>" AS <Alias>` into `RequireNode`, an `EXPORT` before it reported by the handler with its own message; `VALUE` then `<Name>` and an optional default through `context.expression()` into `ValueRequirementNode(name, value, default, exported, sourceInfo)`, where `value` is an `IdentifierNode` carrying the requirement's own name, the slot phase 6 fills; the node returns value and default as children and implements `reconstructWithChildren` (see "Into the artifact"); a missing or unknown kind word is reported. |
| `ImportDirectiveHandler` | 3, parser | After `USING`, the kind words `MODULE` and `VALUE` in any order, each opening a list of groups up to the next kind word, as `ProcDirectiveHandler` reads `REF`, `VAL`, `LREF`, `LVAL`; a value is read with `context.expression()`. `ImportNode` gets `List<ValueBinding(name, value, nameSourceInfo)>` beside `usings`. A name bound twice is reported here. |
| `ImportModuleSetupHandler` | 4, module setup | `registerRelationships` as today: the import alias leads to the imported placement, `ModuleScope.addAlias`. `resolveBindings` goes; `RequireModuleSetupHandler` goes. |
| `ImportSymbolCollector` | 4, pass 1 | Defines the alias symbol as today; then, for the imported placement, binds each `USING MODULE` source with `ModuleScope.bindAlias(target, aliasChain)` and records each `USING VALUE` binding in the feature's record, with the importer's alias chain. |
| `ImportAnalysisHandler` | 4, pass 2 | Reports at the `.IMPORT` line, against the imported placement's record: a module requirement without binding, a `USING MODULE` target that is no requirement (as today, from the record instead of `requires()`), a `VALUE` name that is no value requirement, a value requirement without default and without binding, a bound value that names an undefined name (resolved in the importer at this line, as a use). |
| `RequireSymbolCollector` (moved) | 4, pass 1 | For a module requirement: the alias symbol as today, and the requirement into its placement's record. A value requirement defines nothing in pass 1, as a `.CONST` defines nothing there. |
| `RequireAnalysisHandler` (moved) | 4, pass 2 | For a module requirement, as today against the record; in the root placement, the report that the main program cannot require a module. For a value requirement, as `ConstAnalysisHandler` does for a `.CONST`: takes the binding of the current placement from the record, or the default; defines the constant with `Symbol.Type.CONSTANT`, exported as the directive says, and the node `ValueBindingNode(value)` implementing `IIdentifierBinding`; a taken name is reported as for a constant; a default that is a name is resolved here as a use and reported when undefined; without binding and default, defines the symbol without a value and gives the report above. |
| `RequirePreprocessorHandler` | 2, preprocessor | In the root placement, for `.REQUIRE VALUE <Name> [<Default>]`: when `context.values()` holds tokens for `<Name>`, injects them in place of the default, each with the root placement in its `SourceInfo`, after rejecting tokens that are not one operand; notes the name in its context record; elsewhere leaves the line. In `finish`, reports every configured value it did not put in place. Registered as the `.REQUIRE` handler next to the top-level-only rule. |
| `ConstNode` | `features/constdir` | Implements `reconstructWithChildren`, so that the literal phase 6 resolved reaches the artifact. |
| `ConstAnalysisHandler` | 4, pass 2 | A value that is a name is resolved at the `.CONST` line as a use and reported when undefined: "Cannot define constant 'A' from 'B': the name is not defined." Today the name is followed only at a use of `A`. |
| `ValueRequirementNodeConverter` | 7, IR | Emits `IrDirective("importdir", "value", {name, value})` with the name qualified under the current alias chain (`WALK.STEPS`, `SLOW.STEPS`) and the text of the literal phase 6 put into the value slot. |
| `ValueEmissionContributor` | 11, emission | Reads the feature's own `value` directive and calls `EmissionContext.constantValue(qualifiedName, text)`, the core's API that `ConstantValueEmissionContributor` calls for `.CONST`. |
| `OperandText` | `model/ast` | `static String of(OperandNode)`: the rendering of an operand as text (`DATA:10`, `1\|0`), today private to `ConstNodeConverter`, next to the operand nodes, where both converters use it. |
| `IIdentifierBinding` | `model/ast` | `bind` may return null when the definition has no value, which the phase that defined it has reported; `bindingOf` then finds nothing, and a use adds no report of its own. This is how a value requirement without default and binding keeps its symbol without a cascade of "is not a value" reports. |
| `ModuleScope` | core | `symbols`, `aliases` (one map, one method `bindAlias(alias, aliasChain)` that binds once, whether an import binds its own alias or an importer binds a requirement's alias of the imported placement; read through `aliases()`), `state(Class, Supplier)` frozen by `freeze()`; `imports`, `requires`, `usingBindings` go. |
| `IPlacementAlias` | `model/ast` | Capability: the definition of a name that is an alias of the module level, leading into another placement when the alias map has an entry. Implemented by `ImportNode` and `RequireNode`. |
| `SymbolTable` | core | `registerRoot(aliasChain)` and `rootAliasChain()`, set by the semantic analyzer, so that a handler can tell the compilation root from a module (null in the single-file mode of the unit tests); `descendModule` and the first-segment lookup read `aliases()` alone; the requirement branches go and the explanations speak of names and aliases; `bindingOf` looks the identifier a definition binds up in the placement that identifier's `SourceInfo` names. |
| `CompilerOptions` | `api` | `values`, a map from name to the value text, next to `defines`, names normalised as the defines are: upper-cased, two spellings of one name rejected. |
| `Compiler` | compiler | In phase 1 lexes every value text of the options under the synthetic file name `<value NAME>`, as `Lexer.lexFiles` lexes the included files, and gives the tokens to `PreProcessorContext` as `values()`, a map from name to tokens next to `fileTokens()`. The core knows texts of the options, not requirements. |
| `PreProcessorContext` | core | `values()`, the lexed value texts by name. |
| `IPreProcessorHandler`, `PreProcessor` | core | `default void finish(PreProcessorContext)`, called once per registered handler at the end of `expand()`; a generic end-of-phase hook, used here to report what no directive took. |
| `CompileCommand`, `SimulationEngine` | cli, datapipeline | `--value NAME=TEXT`; `values { … }` in the organism entry, read as `readDefines` reads `defines`; `ProgramVariant` keys the compiled artifacts by path, defines and values, so two entries with the same program and different values get two artifacts. |
| `ProgramIdentity`, `DebugInfo` | backend, `model/ir` | The values enter the identity's digest after the defines, each name and value text as length-prefixed UTF-8 bytes in alphabetical order of the names, with a count of 0 when there are none; `DebugInfo` carries them into the artifact beside the flags. Two variants that compile to the same code under different values keep separate artifacts, for the reason the defines already enter. |

What the core does not get: a notion of "requirement" or "default"; a field on a phase context
that names a feature; a second resolution rule. It knows the configured values as texts of the
options, as it knows the defines, and nothing of what a feature makes of them.

### Documentation

- `docs/ASSEMBLY_SPEC.md`, "Modules and Procedures": `.REQUIRE` with its two kinds and the
  default; the `.IMPORT` syntax with `USING MODULE` and `USING VALUE`; under "Flags from outside"
  the values of the main program from the configuration; under "Flags and constants" that a
  value is a constant.
- `docs/EVOASM_GUIDELINES.md`: a section "Configure a module through its importer": values and
  modules with `USING`, macros with `.SOURCE`, constants a module offers with `EXPORT .CONST`.
- `docs/CLI_USAGE.md`: `--value` next to `--define`; `src/main/resources/reference.conf`: `values`
  next to `defines`.
- `docs/COMPILER_CORE_BOUNDARY.md`: the `importdir` row absorbs `require`; the coupling paragraph
  on the four maps of `ModuleScope` goes.
- `AGENTS.md`, "Stateless Features" gains the sentence: a feature keeps what it needs within phase
  4, per placement, in the symbol table's store `ModuleScope.state(Class, Supplier)`; the store is
  frozen with the scope and read by no later phase. "Registry-Based Dispatch" and
  "Feature-Agnostic Core" do not change.
- `assembly/examples/modules`: `.REQUIRE MODULE` and `USING MODULE`; the sourced `constants.evo`
  becomes value requirements; `src/test/resources/org/evochora/compiler/reference`: the two
  `.REQUIRE` lines and the two `USING` clauses (`main.evo`, `lib/nav.evo`), a value requirement
  with default, one without, bound from `main.evo`.

## Consequences for programs and experiments

- `.REQUIRE "x" AS Y` and `USING X AS Y` need the kind word `MODULE`: the module example and the
  reference program change, the reference artifact is regenerated once and the pull request names
  the regeneration. The primordial uses neither directive and compiles unchanged.
- A module can be placed twice with different values, which `.SOURCE` could not give; a module
  can have a default.
- The main program of a simulation can be tuned from the configuration without a flag per value.
- No hot path: the compiler only.

## Pitfalls

- **Long import lines.** A module with several modules and values makes one long `.IMPORT`
  line, and the language has no line continuation. Not solved here; the first construct that
  makes continuation worth having. Without commas, several groups of one kind run into each
  other (`MODULE MATH AS CALC MOVE AS WALKER`); a kind word per group keeps them apart.
- **A value is not a flag.** `.IFDEF STEPS` on a value requirement is not an error: `STEPS` is no
  flag, so the block is left out silently, as it is for a `.CONST` today (`Condition.holds` makes
  an unset name false, which is what `.IFNDEF` is for). The specification says so under "Flags and
  constants"; a test pins the silence.
- **A default that names a module name** is resolved in the module, a bound value that names a
  name in the importer; the two look alike in the source and differ in where they are looked up.
  The specification states both.
- **Order inside the main program:** a value of the configuration takes the default's place in the
  preprocessor, before the parser reads the line; a default is taken only when the configuration
  has none. A constant the importer binds with `USING VALUE` stands before the `.IMPORT`; the
  uses inside the module are analysed right after the import line, before anything the importer
  writes below it.
- **Synthetic tokens.** The tokens of a configured value name a file that does not exist; they
  describe no source line, the token map keeps them without effect, and `sources` must not list
  the name. A test pins that.
- **The dependency scan** must keep matching `.REQUIRE MODULE "…" AS …` and
  `.IMPORT "…" AS …` in raw text before the preprocessor runs; the address of a file is read three
  times, by the scan, the preprocessor and the parser, and that stays as long as one token stream
  is assembled before the parser runs. Only the clause is read once.
- **Text order carries the bindings.** Pass 1 relies on the module's tokens following the
  `.IMPORT` line and on the importer's own bindings arriving before it hands one on; both hold
  because the preprocessor inlines a module after its import. A test with a requirement handed
  on through two levels pins it.

## Implementation

The rule that a name is used after its definition comes first, as a correction of today's `.CONST`
that the later steps build on. The move comes before the core forgets: once `require` is part of
`importdir`, the record both collectors share is a type of one package, and
`CompilerArchitectureRulesTest.featuresDoNotReferenceEachOther` stays green.

Strictly in this order; every step leaves the build green, and a defect is reproduced by a test
that fails before its fix. One branch, one pull request.
The architecture rules (`CompilerArchitectureRulesTest`) live in `org.evochora.architecture`, outside
the compiler package, so every verification runs both packages.

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | A constant names only what is defined, and the artifact holds its literal | test first: `CompilerDiagnosticsTest` for `.CONST A B` before `.CONST B`, `aCircularDefinitionNamesTheCircle` expecting the new report at line 1 beside the circle, `ConstantValuesTest` expecting `DATA:9` for `ALSO`; then `ConstAnalysisHandler` resolves a name value at its line, `ConstNode` implements `reconstructWithChildren` | `gw test --tests 'org.evochora.compiler.*' --tests 'org.evochora.architecture.*'`; `CompilerOutputEquivalenceTest` | a `.CONST` naming a later or missing constant is reported at its line; a constant of a constant reaches the artifact as its literal; the reference artifact is unchanged (no constant of the reference program names another) |
| 2 | `require` moves into `importdir` | the nine classes of `features/require` into `features/importdir`, `RequireFeature` dissolved into `ImportFeature`, `StandardFeatures` (one feature fewer, `StandardFeaturesTest` 15), the test package `features/require` (`RequireDirectiveHandlerTest`) and the imports of `EmissionIntegrationTest`, `RequireDirectiveTest`, `IrGeneratorTest`, `SemanticAnalyzerTest`, `ModuleSetupRegistryTest`, `DependencyScannerTest`, `ModuleSourceConstIntegrationTest`, `UsingClauseIntegrationTest`, `COMPILER_CORE_BOUNDARY.md` row | `gw test --tests 'org.evochora.compiler.*' --tests 'org.evochora.architecture.*'` | `features/require` is gone; the reference artifact is unchanged |
| 3 | The core forgets the requirement | test first: a `SymbolTable` test that an unsatisfied requirement alias resolves to "not marked EXPORT" from outside and to "'CALC' leads to no module." inside, and a `CompilerDiagnosticsTest` for the main program's `.REQUIRE MODULE` with and without a use of the alias; then `ModuleScope` (`aliases`, `state`), `SymbolTable` (the branches reading the three maps go, `aliases()`, the four explanations and the two Javadocs reworded; `bindingOf` chooses the key as `lookUp` does, in one shared place), the feature's record (an immutable record, complete after pass 1), `ImportModuleSetupHandler` (`resolveBindings` goes), `RequireModuleSetupHandler` (goes), `ImportSymbolCollector` (binds), `RequireSymbolCollector` (records), `ImportAnalysisHandler`, `RequireAnalysisHandler` (the record instead of `requires()`), `IDependencySetupHandler` (`resolveBindings` goes), `SemanticAnalyzer` (step 2 of the setup goes), `ImportDependencyScanHandler` and `ImportDependencyInfo` (no `usings`), `ImportSourceHandler` (no `USING` check); `CompilerDiagnosticsTest` for the two messages; the tests that read or write the three maps: `ModuleScopeTest`, `SymbolTableFreezeTest`, `SymbolTableTest`, `ModuleVisibilityTest`, `ImportAnalysisHandlerTest`, `AstPostProcessorTest`, `UsingClauseIntegrationTest`; the tests of the scan and the setup | `gw test --tests 'org.evochora.compiler.*' --tests 'org.evochora.architecture.*'` | no reader of `requires()`, `imports()` or `usingBindings()` remains; the `USING` clause is read by the parser alone; the reference artifact is unchanged |
| 4 | The kind word | test first: `RequireDirectiveTest`, `ImportDirectiveTest` for `MODULE` and for a missing kind word; then `RequireDependencyScanHandler`, `RequireDirectiveHandler`, `ImportDirectiveHandler`, the module example, the reference program (syntax only), `expected/main.json` regenerated, `ASSEMBLY_SPEC.md` syntax lines | `gw test --tests 'org.evochora.compiler.*' --tests 'org.evochora.architecture.*'`; a diff of the artifact showing no changed cell | `.REQUIRE MODULE` and `USING MODULE` are the only forms |
| 5 | Value requirements and bindings | test first: a new `ModuleValuesTest` in `features/importdir` with the example above through `Compiler.compile`: both placements carry their values in `constantValues` under `WALK.STEPS` and `SLOW.STEPS`, the token map marks a use of `STEPS` inside the module as `CONSTANT` with that qualified name, the default holds where nothing is bound, a self-referencing value slot raises no circle report, a value bound from an importer's constant, an exported value requirement read as `WALK.STEPS` from the importer and an unexported one reported as not marked EXPORT, `.IFDEF` on a value leaving the block out and reporting nothing, the six reports (unknown name, missing value, bound twice, a bound name not defined, a default not defined, `EXPORT .REQUIRE MODULE`; the parser's message in `CompilerDiagnosticsTest.anExportedRequireGetsTheParsersMessageAlone` becomes the handler's); then `ValueRequirementNode`, `ValueBindingNode`, `SymbolTable.bindingOf`, `RequireDirectiveHandler`, `ImportDirectiveHandler`, `ImportNode`, `ImportSymbolCollector`, `RequireAnalysisHandler` (defines, resolves the default), `ImportAnalysisHandler` (resolves bound names), `ValueRequirementNodeConverter`, `ValueEmissionContributor`, the operand text helper in `model/ast` (`ConstNodeConverter` uses it), `ImportFeature` | `gw test --tests 'org.evochora.compiler.*' --tests 'org.evochora.architecture.*'` | every case of the test passes; the reference artifact is unchanged |
| 6 | Values from the configuration | test first: `CompileCommand` test for `--value`, `SimulationEngine` test for `values { }`, a `ModuleValuesTest` case where the main program's requirement is bound by `CompilerOptions.values` and one where the default holds, the reports above (missing value, not required, not one operand), a malformed value text reported under `value X of the configuration`, two spellings of one value name rejected, `sources` of the artifact without a synthetic file, a `SimulationEngine` test with two entries of one program and different values yielding two artifacts of different identity, a `ProgramIdentity` test that the values change the digest; then `CompilerOptions` (`values`, normalised), `Compiler` (lexing the values), `PreProcessorContext.values()`, `IPreProcessorHandler.finish` and its call in `PreProcessor.expand`, `RequirePreprocessorHandler`, `RequireAnalysisHandler`, `ImportAnalysisHandler`, `CompileCommand`, `SimulationEngine.readValues` and `ProgramVariant`, `ProgramIdentity`, `DebugInfo`, `reference.conf` | `gw test --tests 'org.evochora.compiler.*' --tests '*CompileCommand*' --tests '*SimulationEngine*'` | a value of the configuration binds the main program's requirement |
| 7 | The reference program and the example | the reference program gets a value requirement with default and one without, bound from `main.evo`; `expected/main.json` regenerated; `assembly/examples/modules` without the sourced `constants.evo` | `gw test --tests '*CompilerOutputEquivalence*'`; a diff of the artifact showing only added entries | the pull request names the regeneration |
| 8 | Documentation | the documents listed under Documentation; this document's status | review of the diff against the neighbouring sections | every document uses the terms requirement, binding, value, default |
| 9 | Gate | — | `gw check` | PMD and the full suite green |

## Decisions

1. A module declares what it needs with `.REQUIRE`, in two kinds: `MODULE`, today's requirement
   with the kind written out, and `VALUE`, a constant the importer supplies, with an optional
   default.
2. The importer supplies both with one `USING` clause: `MODULE <Source> AS <Target>` groups and
   `VALUE <Name> <Value>` groups, read as `.PROC` reads its parameter lists: kind words in any
   order, repeatable, no commas.
3. A module is named with `AS`; a value is set like a constant, name first. No `=`.
4. A value is what a `.CONST` accepts, a literal, a vector or a name; a name is looked up where it
   is written: a default in the module, a bound value in the importer.
5. A value is a constant and never a flag: it is not seen by `.IFDEF` (CONDITIONAL_COMPILATION,
   "Flags and constants").
6. The main program's value requirements are bound by the configuration, `values { }` and
   `--value`, next to `defines` and `--define`; the configuration is decisive.
7. The core keeps one alias map per placement and a typed store for feature records, frozen with
   the scope; it forgets imports, requirements and `USING` bindings as concepts. The import
   feature reports an unsatisfied requirement at the `.IMPORT` line.
8. `bindingOf` looks the identifier a definition binds up in the placement the identifier's own
   `SourceInfo` names; no capability and no field on a node carry the placement.
9. `require` becomes part of `importdir`; one feature owns both ends of the injection.
10. The syntax change is made in one step with the regeneration of the reference artifact, and
    the pull request says so.
11. Line continuation is not part of this proposal.
12. `EXPORT .REQUIRE VALUE` is allowed and means what `EXPORT .CONST` means; `EXPORT .REQUIRE
    MODULE` stays rejected, reported by the directive handler with its own message and reason.
13. The `USING` clause is read by the parser alone. The scan and the preprocessor read the address
    of a file, path and alias, and nothing after it. The bindings, modules and values alike, are
    made in pass 1 from the nodes, in text order; `resolveBindings` leaves the module setup.
14. The values of a compilation are part of the program's variant and identity, as the defines
    are: the artifact cache, `ProgramIdentity` and `DebugInfo` carry them.
15. A value requirement reaches `constantValues` through its own IR directive and contributor of
    `importdir`, calling the core's `constantValue`; its node's value slot refers to its own name
    and is filled by phase 6. The visualizer's annotation of constants needs no change.
16. A configured value is lexed by the compiler in phase 1 and put in the default's place by the
    `.REQUIRE` preprocessor handler in the root placement; the section "What is reported, and
    where" lists every report of the mechanism.
17. An alias symbol without an entry in the alias map is explained by the core as "leads to no
    module"; the table knows an alias symbol through the capability `IPlacementAlias`, never through
    the symbol's kind. The cause is reported by the import feature, for the main program at the
    `.REQUIRE` line.
18. The symbol table's explanations and Javadoc speak of names and aliases of a module, never of
    imports or requirements.
19. A value requirement is defined in pass 2, as a `.CONST` is: it stands before its first use,
    and in a clash of names the first definition in the text wins.
20. A name is used after it is defined, everywhere: as the value of a `.CONST` (a correction of
    today's behaviour, first step), as a value bound with `USING VALUE` (resolved at the `.IMPORT`
    line), as a default (resolved at the `.REQUIRE VALUE` line). A second definition is rejected as
    today.
21. The preprocessor gets a generic end-of-phase hook, `IPreProcessorHandler.finish`; the `.REQUIRE`
    handler uses it to report configured values the main program does not require. A configured
    value is one operand; `CompilerOptions.values` normalises names as `defines` does.
22. The artifact holds, for every constant and value, the literal at the end of its chain:
    `ConstNode` and `ValueRequirementNode` rebuild themselves in phase 6. A correction of today's
    `.CONST`, first step.
23. `USING` stands once on an import; a further list begins with its kind word, a second `USING` is
    reported.
