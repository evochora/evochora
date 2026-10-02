# Conditional Compilation

**Status: TO BE REVIEWED**

## Problem

### 1. No mechanism for build variants

The compiler has no way to include or exclude code by a switch. A variant of an organism —
boundary checks on or off, a different level of jump redundancy, a scanner-based or a manual
search — is a second copy of the source today. `assembly/primordial/classic` and
`assembly/primordial/shell-first` share their main loop nearly line by line and each carry their
own `energy.evo`. An experiment that runs two variants of one program against each other in one
world needs two hand-maintained trees.

A library of assembly routines that primordials plug in needs the same thing from the other
side: a module names the switches it reacts to, the program that imports it sets them.

### 2. `.DEFINE` is taken by the wrong concept

In C and NASM, `#define` / `%define` introduce a preprocessor symbol that `#ifdef` / `%ifdef`
test. In Evochora, `.DEFINE` is a Phase 3 constant: a symbol with module scope and `EXPORT`,
resolved in the parser and the semantic analyzer. A preprocessor handler for `.DEFINE` would
consume every constant before the parser saw it, so the same word cannot serve both. The
constant is renamed to `.CONST`, which frees `.DEFINE` for the preprocessor and matches what it
is: a constant, not a macro.

### 3. Inconsistent END directives

Block ends are abbreviated today: `.ENDP`, `.ENDM`, `.ENDR`. Conditional compilation adds
`.ENDDEF`, and [CONTROL_FLOW_DIRECTIVES](CONTROL_FLOW_DIRECTIVES.WIP.md) adds `.ENDIF` and the
loop ends, none of which abbreviate well. All ends repeat the word they close: `.ENDPROC`,
`.ENDMACRO`, `.ENDREPEAT`, `.ENDDEF`, `.ENDIF`.

### 4. Blocks are not a concept of the preprocessor

`.MACRO` reads to the first `.ENDM`, `.REPEAT` to the first `.ENDR`, each on its own, neither
counting the other's blocks nor noticing the end of the file it was opened in. A `.MACRO`
without its end in a `.SOURCE`d file swallows the rest of the includer, `.POP_CTX` included; a
missing `.ENDM` is reported at the end of the input, a missing `.ENDR` at the `.REPEAT`. A third
block kind would add a third private reading. Conditional compilation needs a rule for blocks
that nest, and that rule has to be one rule for every block.

## Precedent

The design follows what the C preprocessor, NASM, GNU as, ca65 and MASM agree on, and departs
from them only where the pipeline or the reserved `.IF` family forces it. Consulted: the NASM
manual (preprocessor chapter), the GNU as pseudo-op reference and `gas/cond.c`, the ca65 manual
and the MASM directives reference.

| Question | Practice | Here |
|---|---|---|
| Scope of preprocessor symbols | global, one pass, settable from the command line (`-D`, `-d`, `--defsym`) | the same |
| Defining and testing | `%define X [value]`, `%ifdef X`, `%if X > 1`, `%undef X` | `.DEFINE X [value]`, `.IFDEF X [op value]`, `.UNDEF X` |
| A symbol used in code and in a condition | C and NASM: one text macro serves both; gas and ca65: the assembler symbol serves both | two concepts: `.CONST` for code, `.DEFINE` for conditions (see *Flags and constants*) |
| Directives on their own line | `#if`, `%if`, `.if` are line directives everywhere | the same |
| Closing words | every `if` variant closes with the same `else` / `endif` | an own family, `.ELSEDEF` / `.ENDDEF`, because `.ELSE` / `.ENDIF` belong to the runtime control flow of proposal 2. MASM, the one assembler with both kinds, separates them the same way: `IFDEF … ENDIF` for assembly time, `.IF … .ENDIF` for run time |
| Chained alternatives | NASM `%elifdef`, C23 `#elifdef`, MASM `ELSEIFDEF` | `.ELSEIFDEF`, in line with `.ELSEIF` of proposal 2 |
| A block opened in a macro body | gas: "end of macro inside conditional" at the end of the expansion; NASM: the body must be balanced when the macro is defined; C: a macro body cannot hold a directive | balanced when the body is read, as NASM does (see *Blocks*) |
| Include inside a conditional | normal everywhere; include guards depend on it | supported; Phase 0 evaluates the conditions (see *Dependencies inside conditional blocks*) |
| Skipped branches | ca65: must still consist of valid tokens | the same; the lexer runs before the preprocessor |
| Long block ends | ca65 `.endproc` / `.endmacro` / `.endrepeat`, NASM `%endmacro` / `%endrep` | the same |

## Solution

### Syntax

```
.DEFINE <name> [<integer>]        # set a flag, optionally with a value
.UNDEF <name>                     # remove a flag; no error if it is not set
.IFDEF <name> [<op> <operand>]    # block if the flag is set (and the comparison holds)
.IFNDEF <name>                    # block if the flag is not set
.ELSEIFDEF <name> [<op> <operand>]
.ELSEIFNDEF <name>
.ELSEDEF
.ENDDEF
```

- `<name>` is an identifier. Names are compared case-insensitively, like every other name.
- `<integer>` is an integer literal in the forms the lexer accepts: decimal, `0x…`, `0b…`,
  each with an optional leading minus. No typed literals (`DATA:5`) and no vectors: a
  comparison of vectors has no order, and a type adds nothing a condition can use. (A negative
  hexadecimal or binary literal does not lex today, anywhere in the language: `Lexer.number()`
  looks for the prefix only when the character before it is the `0`, which after a minus it is
  not, so `-0x10` becomes `-0` and `x10`. Step 3 fixes this.)
- `<op>` is one of `=`, `<>`, `<`, `<=`, `>`, `>=`; `==` and `!=` are synonyms of `=` and `<>`,
  as they are in NASM, for the hand that comes from C.
- `<operand>` is an integer literal or the name of another flag, whose value is used.
- Each of these directives stands alone on a physical line: nothing before it on the line, not
  a label, and nothing after its operands but the end of the line. `;` does not separate it
  from another statement. This is what every precedent does, and it is what lets Phase 0, which
  reads lines, see the same directives the preprocessor sees. The preprocessor checks it on the
  tokens' positions: every token of the same file on the same line either belongs to the
  directive or is the `\n` that ends the line, walking backwards and forwards from the
  directive while file and line are equal. Tokens another handler injected — the `.PUSH_CTX`
  before line 1 of an included file, which carries the includer's file name, or the separator
  the repeat handler creates, which carries the `.REPEAT` line — therefore never count, while
  `NOP; .IFDEF X`, `L: .IFDEF X` and `.IFDEF X; NOP` are each rejected.

### Semantics

**Flags and values.** A flag is set or not set; a set flag may carry an integer. `.IFDEF X`
holds if X is set. `.IFDEF X >= 2` holds if X is set **and** X has a value **and** the
comparison holds. A comparison against a flag that is set without a value is an error: the
programmer meant something else. A comparison whose right-hand name is not set, or set without a
value, is an error for the same reason. `.IFNDEF` and `.ELSEIFNDEF` take no comparison: "not
(set and greater)" reads wrong, and `.ELSEDEF` covers the case.

**One pass, global.** A flag exists from the point of its `.DEFINE` to the end of the
preprocessor run or its `.UNDEF`, throughout the token stream: in the main file, inside
`.SOURCE`d files, inside imported modules. Flags set by the configuration exist before the
first token. A flag set inside an imported module is visible after the import, as a macro
defined in a C header is visible after the `#include`. Unlike macros, flags have no module
scope: a macro is triggered by any identifier that matches its name, so two modules with a
macro of the same name would corrupt each other; a flag is read only where `.IFDEF` names it.
Libraries avoid collisions by prefixing their flag names (`ENERGY_NO_BOUNDS_CHECK`), as C
libraries do.

**Redefinition.** Defining a flag that is already set is allowed when the definition is the
same, both without value or with the same value, and an error when it differs, whether the
first definition came from the configuration or from the source. `.UNDEF` first, then
`.DEFINE`, changes a flag. This makes the configuration decisive: a source that wants a default
it can be overruled on writes it as C does,

```
.IFNDEF REDUNDANCY
  .DEFINE REDUNDANCY 1          # the default, unless the configuration set it
.ENDDEF
```

and a source that sets a flag outright is told that the configuration already set it,
instead of silently overriding the experiment's setting.

**Top level only.** `.DEFINE`, `.UNDEF`, `.IMPORT`, `.REQUIRE` and `.SOURCE` may not stand
inside a `.MACRO` body or a `.REPEAT` block. Those bodies are stored and injected later, once
or many times or never; everything else is processed where it is written, `.IFDEF` blocks,
`.SOURCE`d files and imported modules included. Phase 0, which reads the text in the order it
is written, decides on dependencies and on the flags they depend on, so those five directives
must stand where the text and the token stream agree. A `.DEFINE` in a macro that is never
expanded, or a `.SOURCE` in a macro expanded twice, would otherwise turn a module on in Phase 0
that the preprocessor never inlines, and the program would fail with a message about a file.
Conditional blocks in macro and repeat bodies stay allowed and are evaluated at every
expansion; a macro parameter may stand where a flag name stands. C loses nothing by this rule,
a macro body there cannot hold a directive; gas and NASM allow a symbol definition in a macro,
and that is given up.

**Flags and constants.** `.CONST` is a Phase 3 symbol: module-scoped, exportable, reachable as
`ALIAS.NAME`, and used as a value in code. A flag is a Phase 2 name: global, without module
scope, and usable only in conditions. `.IFDEF` does not see constants, because they do not exist
yet when the preprocessor runs, and code cannot read a flag's value. A value needed in both
places is written twice, once as each. This is the split C has between `#define` and `const`.

**Skipped branches.** The lexer runs over the whole file before the preprocessor, so a skipped
branch must still lex; it need not parse. It may hold half a procedure or a lone `.ENDPROC`.
Its block directives, however, must nest like everywhere else (see *Blocks*): a `.MACRO` opened
in a skipped branch closes in it. Nothing in a skipped branch happens: no `.DEFINE`, no macro
definition, no inclusion.

### Blocks

The preprocessor gets blocks as a concept of its own. A feature registers the pair that opens
and closes its block: `.MACRO` / `.ENDMACRO`, `.REPEAT` / `.ENDREPEAT`, `.IFDEF` and `.IFNDEF` /
`.ENDDEF`, with the words that divide a block (`.ELSEIFDEF`, `.ELSEIFNDEF`, `.ELSEDEF`) named
as its dividers. The preprocessor offers the handlers `readBlock(opener)`, which walks from the
opening directive to its end and keeps a stack of every registered block kind on the way:

- a block inside a block nests: the inner one is pushed at its opener and popped at its end;
- an end that does not match the top of the stack is an error naming both blocks:
  `.ENDMACRO closes the macro opened at lib.evo:12, but the .IFDEF opened at lib.evo:14 is still
  open`. Blocks nest, they never overlap;
- the end of the input while the stack is not empty is an error naming the opener: `.MACRO
  opened at lib.evo:12 is not closed before the end of the input`;
- a block word — an opener, a closer or a divider — from a file other than the one its block
  opened in is the same error, at the opener: a block closes in the file it opened in. Only
  block words are compared, never the tokens between them: a macro body carries the file name
  of its definition, but the arguments substituted into it carry the caller's, and
  `.IFDEF FLAG` with a parameter as the flag name, or a parameter as an operand inside a
  block, must work when the macro comes from a `.SOURCE`d library, which is the normal case. A
  block opened in a body cannot close after the call anyway, because the body was balanced when
  it was read; what the rule catches is a block end forgotten in an included file, and it
  catches it at the includer's next block word or at the end of the input;
- the dividers of the opened block are reported to the caller only at depth zero: an `.ELSEDEF`
  inside a nested macro body belongs to that body, not to the surrounding condition. A divider
  whose block kind is not on top of the stack is an error where it stands: an `.ELSEDEF` at
  depth zero of a `.MACRO` body can never divide anything, because `.IFDEF` reads its block
  before the macros inside it are expanded, and is reported when the body is read rather than
  at some expansion or never;
- a body that is stored for later, the block of `.MACRO` or `.REPEAT`, may not hold a
  directive registered as *top level only*; one found there is an error at its line, naming the
  opener: `.DEFINE may not stand inside a .MACRO body; the body opened at lib.evo:2`. A block
  processed in place, `.IFDEF`, may hold anything. Which of the two a block is, its feature says
  when it registers the pair.

`MacroDirectiveHandler` and `RepeatDirectiveHandler` read their bodies through `readBlock`
instead of scanning for their own end, so a missing `.ENDMACRO` is reported at the `.MACRO`
like a missing `.ENDREPEAT` is reported at the `.REPEAT` today, and a `.MACRO` in a `.SOURCE`d
file cannot run past the inclusion any more. `MacroExpansionHandler` rejects an argument that is
a block word or a top-level-only directive, asked of the registry by text through
`isBlockWord` and `isTopLevelOnly`, queries of `PreProcessorHandlerRegistry` that handlers reach
through `handlers()`, so that no argument
can close a block from the call site or smuggle a `.DEFINE` into a body. The runtime blocks of
proposal 2, `.IF` / `.ENDIF`, `.PROC` / `.ENDPROC`, are parser blocks and unknown to the
preprocessor; a macro that opens one and a second macro that closes it, the C idiom for braces,
stays possible.

**`.REPEAT` always opens a block.** The inline form `.REPEAT 3 NOP`, chosen today by the absence
of a line break after the count, is dropped: a block reader recognises an opener by its word and
cannot tell the two forms apart, and the inline form stores a body that no block check would
see. The shorthand `NOP^3` stays and is what the inline form was for; `CaretDirectiveHandler`
rewrites it to the block form (`.REPEAT 3`, body, `.ENDREPEAT`), so the shorthand's body goes
through `readBlock` like every other stored body, and `.SOURCE "x"^2` is rejected like
`.SOURCE` in any body. NASM, gas and ca65 know `%rep`, `.rept` and `.repeat` as blocks only.
The inline form is used in no program under `assembly/`; the reference program has one
`.REPEAT 3 NOP`, which becomes `NOP^3`.

The stack, the matching and the messages are logic, not dispatch, and live in a class
`BlockReader` in `frontend/preprocessor`, which `PreProcessor` hands its handlers; the block
pairs, dividers and top-level-only names live in `PreProcessorHandlerRegistry`, the one registry
of the phase, not in a second one on the context. The preprocessor learns "block", "divider" and
"top level only" as properties features register, never a directive. It knows nothing of what a
block means.

### Evaluation in Phase 2

The conditional directives are preprocessor handlers. The `.IFDEF` / `.IFNDEF` handler reads
its block with `readBlock`, which gives it the body and the positions of its dividers at depth
zero. It then parses the head, evaluates the conditions of the chain in order against the flags
as they are at that moment, and replaces the whole block by the tokens of the first branch
whose condition holds, or by nothing. The walk continues at the first kept token, so a nested
block in the kept branch is evaluated when it is reached, and a `.DEFINE` in the kept branch
takes effect before anything after it. Evaluating every condition of a chain at the head is
equivalent to evaluating them one by one: a `.DEFINE` inside a branch that is not taken never
runs, and once a branch is taken the rest are not evaluated. Reading the block before the head
means a malformed head, a `.IFDEF` without a name, removes its whole block and reports once,
instead of letting the body through and reporting every divider as stray.

The handler keeps no state between blocks. There is no suppression mode in the core, only a
handler that consumes what belongs to it; the one thing `PreProcessor.expand()` does for blocks
is the generic report of a stray closer or divider.

A divider or `.ENDDEF` met outside a block is reported by the preprocessor itself, as every
stray closer or divider of a registered block is.

**Macros.** A macro body is stored as tokens and injected at expansion, and the walk continues
at the injected tokens. An `.IFDEF` in a macro body is therefore evaluated at each expansion,
and a macro parameter may stand where a flag name stands. The body was read with `readBlock`
when the macro was defined, so its blocks are balanced and it holds no `.DEFINE`.

**Errors.**

- a conditional directive, `.DEFINE` or `.UNDEF` that is not alone on its physical line. The
  message names the rule and, for `.DEFINE`, adds that a constant is written `.CONST`, which
  covers `EXPORT .DEFINE X 5` written in the old spelling
- `.ELSEIFDEF`, `.ELSEIFNDEF`, `.ELSEDEF` or `.ENDDEF` outside a block
- `.ELSEIFDEF` or `.ELSEIFNDEF` after `.ELSEDEF`; a second `.ELSEDEF`
- an open block at the end of the input or of the file it opened in; an end that closes a
  different block (both from `readBlock`)
- `.DEFINE` or `.UNDEF` without a name; `.DEFINE` with anything but an integer after the name.
  A `.DEFINE NAME DATA:5` or `.DEFINE NAME 1|0` is the constant written with the old word, and
  the message says so: "a constant is written .CONST"
- `.DEFINE`, `.UNDEF` or a dependency directive inside a `.MACRO` or `.REPEAT` body (from
  `readBlock`, at the definition)
- a divider whose block is not the innermost open one, `.ELSEDEF` at depth zero of a `.MACRO`
  body included (from `readBlock`, at the definition)
- a macro argument that is a block word or a top-level-only directive (at the call)
- a comparison after `.IFNDEF` or `.ELSEIFNDEF`
- a comparison against a flag without a value, or against a name that is not set
- redefinition with a different value or a different presence of a value
- `.UNDEF` of a flag that is not set is **not** an error

### Dependencies inside conditional blocks

`.IMPORT`, `.REQUIRE` and `.SOURCE` may stand inside conditional blocks, so that a program can
choose a module by a flag. Phase 0 (`DependencyScanner`) reads the raw text of every file
before the lexer runs and builds the module graph from it; it does not see the token stream the
preprocessor works on. Left as it is, it would load the module of a skipped branch, register it
under its alias, and two alternative imports under one alias would collide in the symbol table.

Phase 0 therefore evaluates the conditions itself, in text order, with the same flag state:
the configuration's flags first, then every `.DEFINE` and `.UNDEF` line as the scan meets it,
files inlined at their directive as the scanner already does. The feature registers scan
handlers for its directives:

- `.DEFINE` and `.UNDEF` update the flags held in the scan state.
- `.IFDEF` / `.IFNDEF` evaluate their condition. When it holds, the handler consumes the
  directive line and pushes an open block; scanning continues normally inside the branch. When
  it does not hold, the handler reads on, line by line, to the next `.ELSEIFDEF`,
  `.ELSEIFNDEF`, `.ELSEDEF` or `.ENDDEF` at depth zero, evaluates that, and so on, until a
  branch is taken or the block ends.
- `.ELSEIFDEF`, `.ELSEIFNDEF` and `.ELSEDEF`, met while scanning a taken branch, read on to
  the matching `.ENDDEF`: the block already had its branch.
- `.ENDDEF` pops the open block.

The scan handlers match the directive by its word and read the rest of the line themselves; a
head the handler cannot read makes the scan skip the whole block, as the preprocessor removes it. Phase 0 reports no error
about conditionals: the preprocessor owns those diagnostics, and `Compiler.runPhases` stops
after Phase 0 only for errors Phase 0 reports, so a malformed head reaches the preprocessor's
message instead of being hidden behind a file Phase 0 would otherwise have loaded from the
wrong branch.

The evaluation of a condition is one function of the feature, called from the Phase 0 handler
with the strings of a regex match and from the Phase 2 handler with the tokens; the same holds
for reading an integer.

With the line rule and the top-level rule, Phase 0 and the preprocessor see the same
directives in the same order. The dependency directives themselves obey the line rule as well:
`.IMPORT`, `.REQUIRE` and `.SOURCE` are the first word of their line (`.IMPORT` after an
`EXPORT`), and nothing follows their clauses, because that is the line Phase 0 recognises.

One case remains in which the scan and the preprocessor would disagree: a module imported a
second time, with other flags than at its first import, whose own dependency directives test
those flags. Today the scanner scans a file once (`scanModule` returns when the file is already
in the graph) and the module system binds a file to one alias chain, so a second import of the
same file does not work at all, with or without flags. Step 7 makes the placement the module's
identity and rescans the file at every import with the flags of that import; from then on the
two phases agree in this case too. Until then the preprocessor, meeting an import or a source
whose tokens the graph does not hold, reports an internal error, as `ImportAnalysisHandler`
does for an alias the scan did not register: no program can reach the case through a mistake
of its own.

### Sources of flags

**Configuration, per organism.** Flags are part of the organism entry, next to the program:

```hocon
organisms = [
  { program = "assembly/primordial/classic/main.evo"
    defines { AGGRESSIVE = true, REDUNDANCY = 2 }
    initialEnergy = 100000
    placement { positions = [175, 135] } }
  { program = "assembly/primordial/classic/main.evo"
    defines { REDUNDANCY = 1 }
    initialEnergy = 100000
    placement { positions = [25, 135] } }
]
```

`true` sets a flag without value, an integer sets it with that value, `false` or an absent key
leaves it unset. `SimulationEngine` drops the `false` entries and hands the rest to
`CompilerOptions`, which normalises the names (see *Architecture*, item 5); two keys that are
one name after normalisation are an error. Two entries
with the same program and different flags are two programs: the compile loop of
`SimulationEngine` deduplicates by program path and normalised flags, no longer by path alone,
and so does the map it places organisms from. A global `compiler.defines` is deliberately not
offered: the value of flags for an experiment is to run variants of one program against each
other, which a global setting cannot express, and a default for a common variant is one
repeated line.

**Command line.** `evochora compile` gets `--define NAME` and `--define NAME=INTEGER`,
repeatable.

**Program identity.** The program ID is a digest of what the compiler put into the world.
Today it is a sum over the machine code, `Σ(Arrays.hashCode(coordinate) * 31 + value)`, which is
commutative: it depends only on the set of occupied cells and the multiset of values, so two
variants that lay the same instructions in another order, or swap molecule types between
`.PLACE` positions, share an ID, and every consumer of the ID — `SimulationEngine`, which keeps
one artifact per ID, `H2DatabaseReader`, the visualizer's artifact cache — would show one
variant's source map for the other's organisms. The ID becomes an ordered digest:

- The input is a stream of 32-bit big-endian integers, length-prefixed instead of separated:
  the number of dimensions; the number of code entries; for each entry in coordinate order (the
  sorting by `Arrays.compare` that the emitter already does) the coordinate's components, then
  the cell value; the number of initial objects; for each object in coordinate order the
  components, then `type`, `value` and `marker` of the `PlacedMolecule`. The dimension count
  keeps `1|2` in a 2D world and `1|2|0` in a 3D world apart.
- The function is SHA-256 over that stream, rendered as hex and truncated to 16 characters
  (64 bits): unique within any run and any analysis, short enough to display. No Java
  `hashCode` goes in; a record's generated hash is unspecified by the language and `int[]`
  keys hash by identity.
- The flags, the source text, the token map, label and procedure names stay out: the ID says
  what stands in the world, not how it was chosen, and two organisms with the same genome are
  one program in every analysis by ID. Two variants that compile to the same code and the same
  objects share an ID and one artifact in the metadata; then only the token map's line
  attribution may be off for one of them, while code, objects and source text are right.
- Every existing ID changes. `program_id` is `TEXT` in the database and a string in the
  metadata contract, so the longer string fits. A test pins the ID of a small program as a
  constant, so that a later change of the procedure is visible; the step searches the test
  resources and the notebooks under `docs/` for hard-wired IDs and updates them.

**Reproducibility.** `SimulationMetadata` carries the resolved configuration as JSON, which now
includes `organisms[].defines`, and the compiled artifact of every program. A resume or a fork
takes the artifacts from the metadata and does not compile; a re-run from the metadata compiles
with the recorded flags. Nothing new is stored.

### Architecture

The feature is one package, `features/conditional`. The core changes in five places, and each
change is a capability any feature can use; the core learns no directive and no feature type.

1. **A symbol registry in the lexer.** The lexer is the one phase without a registry today; a
   feature that needs a character has to add a case to `scanToken`, which is why `*`, `..`,
   `,`, `@+`, `@-` and `^` stand in `docs/COMPILER_CORE_BOUNDARY.md` as a coupling. A feature
   now registers the character sequences it reads, `ctx.lexerSymbol(">=")`, and the lexer emits
   one generic `SYMBOL` token with the text; handlers compare the text, as they do for directive
   names, through `checkSymbol` / `matchSymbol` on the parsing context. At every position the
   lexer tries the registered symbols first, longest first, so that `<=` is one token and `..`
   is found before the fixed case for `.`. Because the symbols are tried before identifiers,
   numbers, strings and comments, a symbol may consist only of characters from the alphabet
   `! & * + , . / < = > ? @ ^ ~ -`: no letters, digits, `_` or `$`, which would split identifiers
   and numbers (`AS` would shadow `ASSERT`, `.E` the directive `.ENDDEF`); no `#`, `"` or `;`,
   which open comments, strings and statement ends; no `%`, `|` or `:`, which the lexer and the
   parser read for registers and the core literals; no whitespace. The single characters `.` and
   `-` are rejected as well, being fixed cases of the lexer; `..`, `->` or `@+` match only their
   own sequence and are allowed. The alphabet is one constant in the registration check; it grows
   by a character when a feature needs one and the core does not claim it, and brackets, `\` and
   `§` are deliberately kept out of it as a reserve for the core, should expressions or grouping
   ever need them. The same symbol registered by two features is one symbol. A character that
   matches no symbol is still "Unexpected character", and the message names the registered
   symbols that begin with it: `Unexpected character '@'; symbols beginning with it are '@+' and
   '@-'`.

   The six existing feature characters move behind the registry in this proposal: `place`
   registers `*`, `..` and `,`, `org` and `dir` register `@+` and `@-`, `repeat` registers `^`,
   which is already dispatched by its text. `TokenType` loses `STAR`, `DOT_DOT`, `COMMA`,
   `AT_PLUS` and `AT_MINUS`. `|` and `:` stay fixed: the parser itself reads them in the vector
   and typed literals every operand may take. The `Lexer` constructors that take no symbol set
   are removed, so that a test which builds a lexer directly and is missed in the migration
   fails to compile instead of lexing `..`, `*`, `,`, `@+` and `^` as errors; the tests pass the
   symbols of the standard features through one helper, `TestLexers`, as they pass the
   instruction set. `IFeatureRegistrationContext` gains `lexerSymbol(String)` and loses the
   sentence that Phase 1 has no extension point; the boundary document drops the paragraph on
   single-feature character tokens and its sentences "eleven of the twelve phases have a
   registry" and "a feature cannot introduce a token type"; AGENTS.md's list of registries gains
   the lexer's.

2. **Blocks in the preprocessor**, as described under *Blocks*: registered pairs with dividers
   and the stored/in-place distinction, the *top level only* property on a directive, the
   `BlockReader` next to the phase, and the block registrations in `PreProcessorHandlerRegistry`.
   Registration goes through `IFeatureRegistrationContext`, `preprocessorBlock(opener, closer,
   dividers, stored)` and `preprocessorTopLevelOnly(name)`; `require` registers its directive as
   top level only without having a preprocessor handler. `PreProcessorHandlerRegistry` answers
   `isBlockWord(text)` and `isTopLevelOnly(text)`, which handlers reach through `handlers()` and
   which is what the macro feature asks of an argument. AGENTS.md's list of registries names the block registrations as
   part of the preprocessor's.

3. **A typed state slot on the Phase 0 and Phase 2 contexts.** `ParserState` offers
   `getOrCreate(Class<T>, Supplier<T>)`, a slot in which a feature keeps its own state type
   without the core knowing it; it has no production user yet, so it is a pattern the core
   provides, not one it has proven. `PreProcessorContext` and `IDependencyScanContext` get the
   same method, the scanner's slot held by its `ScanState` so that it spans the files of one
   scan. The feature's flags and, in Phase 0, its block stack live in classes of the feature,
   held in that slot; no field named after the feature is added to either context.

4. **A line cursor for scan handlers.** `IDependencyScanContext` gains `String nextLine()`,
   which hands the handler the following line, comment stripped and trimmed as the scanner
   itself reads lines, or `null` at the end of the file; a line the handler takes is not
   offered to the handlers again. This is the counterpart of the token cursor the preprocessor
   gives its handlers.

5. **Options on the contexts.** `CompilerOptions` gets `defines`, a map from flag name to an
   optional integer. Normalisation happens in one place, the record's compact constructor:
   names are upper-cased and the map copied (`Map.copyOf`); two keys that are one name after
   upper-casing throw `IllegalArgumentException` there, as duplicate prefixes do in
   `validate()` today, and `validate()` gets nothing new. `SimulationEngine` and the CLI only
   translate `true`, an integer and `false` into an entry or its absence and pass the map in;
   the (program, flags) key of the compile loop is the normalised map of the `CompilerOptions`,
   not a second normalisation. The syntax of a name is the lexer's rule and is checked by the
   feature, once, when the Phase 2 seeding first reads the options, reported against the
   configuration without a source line; the Phase 0 seeding is silent, a bad name simply never
   matches there. A program without a single conditional directive never seeds and therefore
   never checks the names; a flag nothing reads can change nothing, and a valid but misspelt
   name is caught nowhere, as with `-D` in C. `Compiler.runPhases` hands the options to the
   `PreProcessorContext` and to the scanner, as it hands the preprocessor its resolver, and both
   contexts offer `options()`. The feature seeds its state on first use:
   `getOrCreate(Flags.class, () -> Flags.of(ctx.options().defines()))`; every reader seeds, the
   Phase 0 slot sits in `ScanState` across files, and seeding twice from the same options is
   harmless. Handlers keep their no-argument constructors; nothing about a compilation reaches a
   handler except through its context, which is what "Stateless Features" in AGENTS.md asks.
   `CompilerOptions.defines` is the first option field that only a feature reads — `sourceRoots`
   is read by core code — and `docs/COMPILER_CORE_BOUNDARY.md` records it as a new coupling,
   with the later proposal for constants from the configuration as the expected second field
   beside it, read the same way from the parser's context.

No phase learns a directive name, no handler calls another. `Compiler.java` changes only to pass
the options into the two contexts and the registered symbols into the lexer.

### Nesting example

```
.IFDEF AGGRESSIVE
  .IFDEF HAS_ENERGY_SCANNER
    # aggressive with scanner: skip checks, use the scanner
  .ELSEDEF
    # aggressive without scanner: skip checks, scan by hand
  .ENDDEF
.ELSEIFDEF REDUNDANCY >= 2
  # defensive, with the extra jumps
.ELSEDEF
  # defensive
.ENDDEF
```

### Recorded decisions

Decisions taken against alternatives on the way to this text, so that they are not reopened
without a new reason:

- **Global flags, not flags passed at the import.** Passing flags on the `.IMPORT` line
  (`.IMPORT "x.evo" AS X WITH AGGRESSIVE`) would keep modules isolated but adds a clause to a
  syntax that is described in three places, and no assembler does it. Global flags with a
  prefix convention are what every precedent does.
- **Flags and constants stay two things.** A constant known to the preprocessor would couple
  `const` and `conditional` through a shared core concept and rebuild module scope in Phase 2;
  a constant that is text substitution would lose module scope, `EXPORT` and `ALIAS.NAME`, which
  ca65's manual warns of for its `.define`. gas and ca65 combine the two only because they are
  single-pass assemblers whose symbols exist when the condition is read.
- **Values and comparisons now, not later.** The cost is six symbols, an optional tail on two
  handlers and one comparison function; the use is ordered levels such as jump redundancy,
  which flags alone express only by stacking one flag per level.
- **Own closing words.** `.ELSE` / `.ENDIF` are reserved for proposal 2. Sharing them would
  force the preprocessor to count the parser's `.IF` blocks to find its own end, and a branch
  holding half an `.IF` block could not be expressed.
- **Blocks as a core concept, not a file-name check per handler.** A check per handler would be
  written three times, would not see overlap, and would leave the messages as uneven as they
  are; a marker at the end of every expansion, gas's way, would be a core concept of its own
  for one error. The registered block is one rule for every block and reports at the opener.
- **The file rule of a block is checked on block words only.** Comparing the file of every
  token inside a block would reject every macro argument substituted into a block of a macro
  from another file, `.IFDEF FLAG` with a parameter included, which is the library case; a
  block opened in a body cannot close after the call anyway, because the body is balanced when
  it is read.
- **`.REPEAT` loses its inline form.** A block reader cannot tell `.REPEAT 3 NOP` from a block
  opener by its word, and the inline form stores a body no block check sees; `NOP^3` says the
  same, and the precedents know repeat blocks only.
- **A symbol registry, not six fixed tokens.** Six token types and six cases in `scanToken`
  would deepen the coupling the boundary document says may not deepen; word operators
  (`GE`, `LT`) would need no lexer change but have no precedent. The registry is the pattern of
  the other eleven phases, costs about the same as the fixed tokens, and takes the existing
  feature characters with it, so that no second change is needed for them.
- **Symbols on the context, not in the state slot's API.** A `defineSymbol` / `isSymbolDefined`
  API on `PreProcessorContext` would be a method on a phase context that runs only for one
  feature, which `.agents/architecture-guidelines.md` flags; the slot holds a type the feature
  owns.
- **Options on the contexts, not at registration.** Handing the initial flags to handler
  constructors, or offering `options()` on `IFeatureRegistrationContext`, would open a second
  channel for the same data, make every test that registers the handlers or builds a
  `FeatureRegistry` pass options, and let a feature's registration depend on the input, so that
  `StandardFeatures` would no longer say which directives exist. The instruction set, which
  handlers do take in their constructors, is the compiler's fixed target and the same for every
  compilation; the flags are not.
- **Phase 0 evaluates conditions, rather than forbidding dependencies in blocks or moving the
  module wiring to the AST.** A ban would need the same scanner change to be enforced and would
  take away the most natural use of flags; wiring the modules from the `ImportNode`s instead of
  the Phase 0 graph would move the largest listed coupling, the module system, for one
  remaining limit.
- **Configuration per organism, not global.** See *Sources of flags*.
- **A file may be imported more than once.** The module system identified a module by its
  file, so a second `.IMPORT` of the same file failed with an error about a name in that file.
  Nothing in the language forbade it, and with flags the second placement may be other code;
  for an experiment, two independent copies of one routine in one organism are a legitimate
  design. The identity of a module becomes its placement (Step 7). That two placements put the
  same labels into one body twice is the ordinary behaviour of the machine, as after a
  reproduction, and is not mentioned in the specification: the label matching weighs the
  candidates, and a programmer who wants one copy's labels found sets the flags that place them.
- **The source view shows a file once per placement.** Two placements of one file may hold
  other code, and their label values and parameter annotations differ, so the visualizer cannot
  key its source view by the file. The placement travels in `SourceInfo` (Step 8), the one type
  made for a token's origin; a placement-decorated file name was rejected because the file name
  serves path resolution and error locations. An entry of the view reads `CHAIN → path as
  written`, with the resolved path in the tooltip: the arrow says that the alias stands for the
  file, a colon would read as a source-root prefix, and the written path shows the root prefix
  the program used.
- **Constants from the configuration** are a separate proposal after this one; they raise
  their own questions (qualified names of module-local constants, lexing of configured values,
  unknown names, precedence) and reuse the options path of this proposal.

---

## Implementation Steps

### Dependencies

```text
Step 1 (.CONST) → Step 2 (END directives) → Step 3 (lexer symbol registry) → Step 4 (blocks, slots, cursor, options) → Step 5 (feature) → Step 6 (configuration, CLI, documentation) → Step 7 (module identity by placement) → Step 8 (the placement in the artifact and the visualizer)
```

Steps 1 and 2 are renames without behavioural change. Steps 3 and 4 add generic core
capabilities and move the existing handlers onto them. Step 5 is the feature. Step 6 wires the
outside.

Every step ends with `gw check` green. `CompilerOutputEquivalenceTest` compiles the reference
program under `src/test/resources/org/evochora/compiler/reference`, whose artifact holds the
source text and the program ID; steps 1, 2 and 4 change the text and step 6 changes the ID, so
the reference artifact is regenerated in each of them and the pull request says so.

### Step 1: Rename `.DEFINE` → `.CONST`

Pure rename. Package `features/define` → `features/constdir` (`const` is a Java reserved word, as `import` is for `features/importdir`); classes `DefineDirectiveHandler`,
`DefineFeature`, `DefineNode`, `DefineNodeConverter`, `DefineAnalysisHandler` →
`Const…`; registration `ctx.parserStatement(".CONST", …)`; JavaDoc in `Symbol`, `TokenKind`,
`IParserStatementHandler`; `StandardFeatures`. Every `.DEFINE` in `assembly/`, in the test
sources and test resources, in `docs/ASSEMBLY_SPEC.md` and `docs/COMPILER_CORE_BOUNDARY.md`, and
in the directive list of the VS Code grammar `extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json`.
The error messages of the constant name `.CONST`.

**Tests:** all existing tests green after the rename; reference artifact regenerated.

### Step 2: Rename the END directives

Pure rename: `.ENDP` → `.ENDPROC` (`ProcDirectiveHandler`), `.ENDM` → `.ENDMACRO`
(`MacroDirectiveHandler`), `.ENDR` → `.ENDREPEAT` (`RepeatDirectiveHandler`), including the
string comparisons and error messages in those handlers, every occurrence in `assembly/`, the
tests, `docs/ASSEMBLY_SPEC.md`, `docs/COMPILER_CORE_BOUNDARY.md`, `docs/COMPILER_IR_SPEC.md`,
`docs/proposals/ideas/LOCAL_STATE.md`, the directive list of the VS Code grammar `evochora.tmLanguage.json`, and the program embedded in
`src/jmh/java/org/evochora/runtime/SimulationBenchmark.java`. That program is a string that
only the running benchmark compiles, so no test proves it; the rename there is checked by eye.

**Tests:** all existing tests green; reference artifact regenerated.

### Step 3: Symbol registry in the lexer

- Own commit first: `Lexer.number()` recognises the `0x` / `0b` prefix after a leading minus, so
  that `-0x10` and `-0b11` lex as one number everywhere; lexer tests for both and for
  `DATA:-0x10`.
- `TokenType.SYMBOL`; `Lexer` takes the registered symbols, tries them longest first before its
  fixed cases, and names the symbols beginning with an unexpected character in its message; the
  constructors without a symbol set are removed.
- `IFeatureRegistrationContext.lexerSymbol(String)`; `FeatureRegistry` collects the symbols and
  rejects one outside the alphabet `! & * + , . / < = > ? @ ^ ~ -` or equal to `.` or `-`;
  `Compiler.runPhases` passes them to `Lexer.lexFiles` and the main lexer.
- `IParsingContext.checkSymbol(String)` and `matchSymbol(String)`.
- `place`, `org`, `dir` and `repeat` register their symbols and read `SYMBOL` tokens by text;
  `STAR`, `DOT_DOT`, `COMMA`, `AT_PLUS`, `AT_MINUS` and the fixed cases for `*`, `,`, `^`, `@`
  and the second `.` leave the lexer.
- `TestLexers`, the helper that builds the standard symbol set; the tests that construct a
  `Lexer` directly use it.
- `docs/COMPILER_CORE_BOUNDARY.md`: the paragraph on single-feature character tokens is
  replaced by the registry with its alphabet, the reserve and the growth rule; the sentences
  "eleven of the twelve phases have a registry" and "cannot introduce a token type" go;
  AGENTS.md's registry list gains the lexer's symbol registry.

**Tests:** lexer tests for longest match (`<=` one token, `< =` two), for `..` against `.X`,
for rejected registrations of `-`, `.`, `AS`, `.E` and `#-`, for the message naming `@+` and
`@-`; all existing directive tests green; the reference artifact unchanged.

### Step 4: Blocks, slots, cursor, options

- `BlockReader` in `frontend/preprocessor`, handed out by `PreProcessor`; the block pairs,
  dividers, stored/in-place distinction and *top level only* names in
  `PreProcessorHandlerRegistry`; `IFeatureRegistrationContext.preprocessorBlock(...)` and
  `preprocessorTopLevelOnly(...)`; `isBlockWord` and `isTopLevelOnly` on
  `PreProcessorHandlerRegistry`, reached through `handlers()`; `PreProcessor.expand()` reports a
  closer or divider the walk reaches as standing outside any block (`.ENDMACRO closes no open
  block`) and removes it.
- `MacroDirectiveHandler` and `RepeatDirectiveHandler` read their bodies through the
  `BlockReader`; `RepeatDirectiveHandler` loses the inline mode and `CaretDirectiveHandler`
  rewrites `X^n` to the block form; `MacroExpansionHandler` rejects block words and
  top-level-only directives as arguments; `importdir`, `require` and `source` register their
  directives as top level only, and `.IMPORT`, `.REQUIRE` and `.SOURCE` stand alone on their
  line (`.SOURCE must stand alone on its line`), checked by `SourceDirectiveHandler`,
  `ImportSourceHandler` and `RequireDirectiveHandler`.
- The reference program's `.REPEAT 3 NOP` becomes `NOP^3`; `RepeatDirectiveTest` and
  `docs/ASSEMBLY_SPEC.md` lose the inline form; the reference artifact is regenerated.
- `PreProcessorContext.getOrCreate(Class<T>, Supplier<T>)` and `options()`.
- `IDependencyScanContext.getOrCreate(Class<T>, Supplier<T>)`, held by `ScanState`; `options()`;
  `String nextLine()`, implemented in `DependencyScanner.scanLines` by moving the line loop's
  index.
- `CompilerOptions(List<SourceRoot> sourceRoots, Map<String, OptionalInt> defines)` with the
  compact constructor that upper-cases, copies and rejects duplicates; `defaults()` with an empty
  map.
- `Compiler.runPhases` passes the options to both contexts.
- `docs/COMPILER_CORE_BOUNDARY.md`: `CompilerOptions.defines` recorded as the first option field
  only a feature reads; AGENTS.md's registry list names the block registrations.

**Tests:** the `BlockReader` nests two kinds, reports overlap with both positions, reports the
end of input and a block word from another file with the opener, lets a non-block token from
another file pass, hands dividers only at depth zero, reports a divider whose block is not the
innermost open one, reports a top-level-only directive in a stored body and accepts it in an
in-place block; a `.MACRO` without end in a `.SOURCE`d file is reported at the `.MACRO` and the
includer's text survives; a macro from a `.SOURCE`d file with an `.IFDEF` around a parameter,
called from the includer, expands; a block word as a macro argument is rejected; `NOP^3` and a
`.REPEAT 3` block expand alike, `.REPEAT 3 NOP` is rejected, `.SOURCE "x"^2` is rejected; the
slot returns the same instance for the same key and creates once; `nextLine()` returns
following lines and `null` at the end, and a line taken by a handler is not dispatched again;
`CompilerOptions` upper-cases, rejects `{a=1, A=2}` and keeps `{x=1}` equal to `{X=1}`.

### Step 5: The feature `features/conditional`

- `Flags`: the feature's state type — name → optional value, `define`, `undefine`, `isSet`,
  `valueOf`, seeded from `options()`; the Phase 2 seeding reports a name that is not an
  identifier, once, the Phase 0 seeding is silent; the redefinition rule lives here and returns
  what the caller reports.
- `Condition`: parsing and evaluation of `NAME [op operand]` from strings (Phase 0) and from
  tokens (Phase 2), one comparison function and one integer reader.
- Phase 2: `DefineHandler` (`.DEFINE`), `UndefHandler` (`.UNDEF`), `ConditionalBlockHandler`
  (`.IFDEF`, `.IFNDEF`); the line rule checked on the token positions.
- Phase 0: `DefineScanHandler`, `UndefScanHandler`, `ConditionalScanHandler` with the block
  stack in the slot.
- `ConditionalFeature` registers the handlers, the block pair with its dividers, the six
  symbols and the two top-level-only directives; `StandardFeatures` lists it;
  `docs/COMPILER_CORE_BOUNDARY.md` gets its row in the feature table.
- `ImportSourceHandler` and `SourceDirectiveHandler`: a file whose tokens the graph does not
  hold is an internal error (see *Dependencies inside conditional blocks*); the "must be a
  literal" message goes, its case being unreachable now that these directives stand at top
  level. The same three handlers, with `RequireDirectiveHandler`, check that their directive is
  the first word of its line, `.IMPORT` after an `EXPORT`.

**Tests**, each as a compilation of a small program unless noted:

- `.DEFINE FOO` + `.IFDEF FOO` → included; `.IFDEF UNSET` → excluded; `.IFNDEF UNSET` →
  included
- `.IFDEF` / `.ELSEDEF`; `.IFDEF A` / `.ELSEIFDEF B` / `.ELSEDEF`; `.ELSEIFNDEF`
- values: `.DEFINE LEVEL 2` with each of the six operators, true and false; right-hand flag
  name; hex, negative and negative hex literals
- errors: comparison against a flag without value, against an unset name, after `.IFNDEF`
- redefinition: same again is accepted, different value is an error, `.UNDEF` then `.DEFINE`
  is accepted; the same against a flag from the options; the `.IFNDEF` default idiom against
  an option that is set and one that is not
- the line rule: `L: .IFDEF X`, `.IFDEF X; NOP`, `EXPORT .DEFINE X 5` each rejected with the
  message, the last naming `.CONST`
- nesting: inner block in the kept branch; inner block in a skipped branch, balance kept
- `.DEFINE` inside a skipped branch has no effect; `.UNDEF FOO` + `.IFDEF FOO` → excluded
- skipped branch with a lone `.ENDPROC` compiles; skipped branch with an unbalanced `.MACRO`
  is reported
- every error of the list under *Errors*, including a block open at the end of a `.SOURCE`d
  file, and `.IFDEF` without a name reported once
- macros: `.IFDEF` in a body evaluated at each expansion, with a parameter as the flag name,
  the macro defined in a `.SOURCE`d file and called from the includer; `.DEFINE` and `.SOURCE`
  in a body rejected at the definition; `.ELSEDEF` at depth zero of a body rejected at the
  definition; `.ENDDEF` as an argument rejected at the call
- `.REPEAT` block with an `.IFDEF` inside; `.DEFINE` inside rejected
- `.SOURCE` and `.IMPORT` inside a taken and inside a skipped branch: the skipped file is not
  loaded (a path that does not exist compiles); two imports under one alias in two branches;
  `.DEFINE` in a `.SOURCE`d file seen by a later `.IFDEF` in the includer
- Phase 0 unit tests on `DependencyScanner`: a graph with and without the skipped module; the
  block stack across `.ELSEDEF`; a head that cannot be read takes no branch
- a module imported a second time with other flags, until Step 7: the internal error
- `==` and `!=` compare like `=` and `<>`; a dependency directive with a label or a statement
  before it on the line is rejected

### Step 6: Configuration, CLI, program identity, documentation

- `Emitter`: the program ID becomes the SHA-256 digest described under *Program identity*,
  16 hex characters; a test pins the ID of a small program; hard-wired IDs in test resources and
  in the notebooks under `docs/` are searched for and updated.
- `SimulationEngine`: reads `organisms[].defines`, normalises, builds one `CompilerOptions` per
  distinct (program, flags) pair, deduplicates the compile loop and the placement map by that
  pair; `reference.conf` documents the block.
- `CompileCommand`: `--define NAME[=INTEGER]`, repeatable.
- `docs/ASSEMBLY_SPEC.md`: a section *Conditional Compilation* under *Compiler Directives*
  with the syntax, the semantics, the line and top-level rules, the `.IFNDEF` default idiom,
  the limit and the difference to `.CONST`, and the block rule under *Macros*;
  `docs/CLI_USAGE.md`: the option. The `evoasm` skill points at the specification and needs no
  change.

**Tests:** two programs with the same code and different `.PLACE` objects get different IDs;
two programs with the same instructions in a different order get different IDs; the pinned ID;
`SimulationEngine` compiles two entries of one program whose flags change the code into two
artifacts, and one program twice with equal flags once, `{x=1}` and `{X=1}` counting as equal;
`defines { a = 1, A = 2 }` rejected; CLI test for `--define A --define B=2`; a metadata round
trip whose resolved configuration carries the flags; reference artifact regenerated for the new
ID.

### Step 7: A module's identity is its placement

A file may be imported more than once; every `.IMPORT` is a placement with its own alias chain,
and that chain, not the file, identifies the module from Phase 0 on.

- `DependencyScanner`: a module file is scanned at every import, with the flags as they stand at
  that import, and its dependencies are kept per placement; the scan descends from the main
  module along the imports and gives every placement the alias chain the preprocessor will give
  it (parent chain plus alias). The cycle check stays per file: a file that imports itself,
  directly or through others, is reported as today. The contents of a file are loaded once.
- `DependencyGraph` carries the placements with their chains and dependencies instead of one
  descriptor per file, in an order in which a placement's dependencies precede it.
- `SemanticAnalyzer.setupModuleRelationships` walks the placements: it registers each chain as a
  module, and the setup handlers (`ImportModuleSetupHandler`, `RequireModuleSetupHandler`) read
  the chain of the placement they are called for instead of `aliasChainOf(path)`;
  `ModuleSetupContext.bindPath` / `aliasChainOf` go.
- The preprocessor already inlines a module at every import under its own chain; nothing
  changes there. The internal error of the step before becomes unreachable and stays as an
  internal error.
- `docs/ASSEMBLY_SPEC.md`, `.IMPORT`: one sentence that a file may be imported more than once and
  that each import places the module again under its alias.

**Tests:** the same file imported twice under two aliases, both procedures called, compiles and
places the code twice; the two placements take different branches when a flag changes between
the imports, including a conditional `.IMPORT` inside the module; a module imported twice with
a `.REQUIRE` satisfied by different `USING` clauses at the two imports; a diamond (`main`
imports `A` and `B`, both import `M`) places `M` twice under `A.M` and `B.M`; a file that imports
itself through another is still reported as a cycle; the reference artifact unchanged.


### Step 8: The placement in the artifact and the visualizer

Two placements of one file share its text but not its code, and not the annotations the debugger
hangs on its tokens: label values and parameter names belong to a placement. The source view of
the visualizer therefore shows a file once per placement, and the artifact carries the
placement with every position.

- `SourceInfo` gains `placement`, the alias chain of the placement a token belongs to, empty
  for the main file; `Token` carries it too, and `Token.toSourceInfo` passes it on. The
  preprocessor stamps it when it inlines a module: the copied tokens of the module get the
  placement's chain, the tokens of a `.SOURCE`d file the chain of the placement that includes
  it. Synthetic tokens (`.PUSH_CTX`, `.POP_CTX`, the repeat separator, the label rewrite) take
  the placement of the token they are made from. Equality of `SourceInfo` includes the
  placement, so the token map and the source map keep two placements apart.
- The artifact: `sources` becomes a list of placements in the order of the graph (main file
  first, then each placement after the one that imports it), each with `placement` (the chain),
  `path` (as written in the import, with its source-root prefix; the main file as given to the
  compiler), `resolvedPath` and `lines`; the file name of every `SourceInfo` in `sourceMap`,
  `tokenMap` and `tokenLookup` is joined by its placement. The persisted format changes; a run
  is read with the build that wrote it.
- The visualizer: the dropdown of the source view lists one entry per placement, labelled
  `CHAIN → path` (`ENERGY → lib/energy.evo`, `X → PREY:lib/x.evo`), the main file by its path
  alone, in the order of the list, with the resolved path as the entry's tooltip; the view
  switches to the placement the active position names, and `SourceAnnotator` looks tokens up by
  placement and file. Where the datapipeline converts the artifact (`SimulationEngine`,
  `SimulationRestorer`, the protobuf contract) the new shape is carried through.
- Diagnostics keep reporting `file:line`; which placement a message belongs to is not shown.

**Tests:** a program importing one module twice has two entries with that file, each with its
own token map and the label values of its placement; the source map of an instruction in the
second placement names the second placement; the reference artifact is regenerated, and its
diff holds the new shape of `sources` and the placement fields only; a metadata round trip keeps
the placements. The frontend change is checked in Chrome and Firefox against a run of a program
that imports a module twice: the dropdown shows both entries, stepping through ticks switches
between them, and the annotations of each placement sit on their tokens with no `mismatch`
message.
